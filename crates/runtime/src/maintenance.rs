//! Explicit offline maintenance. No agent restart, shell replay or worktree writes.
use crate::worktrees::{self, Recovery, WorktreeManager};
use history::{
    Attribution, Coverage, HistoryStore, Observation, OperationStatus, Recorder, SnapshotInput,
    Turn,
};
use protocol::{MutationKey, ProtocolStore, SessionId};
use serde_json::{Value, json};
use std::path::Path;
use worktrees::io::{now, read, token, write};
use worktrees::{Error, Result};

/// Diagnostic record only: PIDs are never used as authority to kill or adopt a
/// process. SDK process groups may outlive an engine crash; escaped descendants
/// cannot be proven absent just from the parent's PID.
#[derive(serde::Serialize, serde::Deserialize)]
struct ProcessRecord {
    owner_token: String,
    engine_pid: u32,
    adapter_pid: Option<u32>,
    state: String,
}
pub(crate) fn preparing_process(workspace: &history::Workspace) -> Result<()> {
    write(
        &workspace
            .worktree_path
            .parent()
            .ok_or(Error::Invalid("workspace parent missing"))?
            .join("agent-process.json"),
        &ProcessRecord {
            owner_token: workspace.owner_token.clone(),
            engine_pid: std::process::id(),
            adapter_pid: None,
            state: "launch_pending".into(),
        },
    )
}
pub(crate) fn launched_process(workspace: &Path) -> Result<()> {
    let path = workspace
        .parent()
        .ok_or(Error::Invalid("workspace parent missing"))?
        .join("agent-process.json");
    let mut record: ProcessRecord = read(&path)?;
    record.adapter_pid = Some(std::process::id());
    record.state = "launched".into();
    write(&path, &record)
}
pub(crate) fn released_process(workspace: &history::Workspace) -> Result<()> {
    let path = workspace
        .worktree_path
        .parent()
        .ok_or(Error::Invalid("workspace parent missing"))?
        .join("agent-process.json");
    if !path.exists() {
        return Ok(());
    }
    let mut record: ProcessRecord = read(&path)?;
    if record.owner_token != workspace.owner_token {
        return Err(Error::Invalid("process owner changed"));
    }
    if record.engine_pid != std::process::id() {
        return Err(Error::Invalid("process belongs to another engine"));
    }
    let pid = record
        .adapter_pid
        .and_then(|p| i32::try_from(p).ok())
        .and_then(rustix::process::Pid::from_raw)
        .ok_or(Error::Invalid("adapter launch outcome unknown"))?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
    loop {
        match rustix::process::test_kill_process_group(pid) {
            Err(rustix::io::Errno::SRCH) => break,
            Ok(()) if std::time::Instant::now() < deadline => {
                std::thread::sleep(std::time::Duration::from_millis(25))
            }
            other => {
                tracing::warn!(result = ?other, "adapter group shutdown check failed");
                return Err(Error::Invalid("adapter process group shutdown unverified"));
            }
        }
    }
    // Group exit does not prove escaped/background writers absent; retained
    // workspace continuation still requires an explicit stopped-writer assertion.
    record.state = "sdk_group_exited_external_writers_unverified".into();
    write(&path, &record)
}

/// Startup/offline inspection uses actual immutable manifests, current worktree
/// bytes, preparation journals and process records. It never changes files or
/// acquires/acknowledges stale leases. Unknown preparations remain visible.
pub fn inspect(store: &HistoryStore) -> Result<Value> {
    let mut sessions = Vec::new();
    let mut after = None;
    loop {
        let page = store.sessions_page(after, 100)?;
        for session in &page.items {
            let checkpoint = store.checkpoint(session.id.clone());
            let workspace = store.get_workspace(session.workspace_id.clone())?;
            let directory = store
                .directory()
                .join("worktrees")
                .join(workspace.id.as_str());
            let preparation: Result<worktrees::Preparation> =
                read(&directory.join("preparation.json"));
            let owned = preparation.as_ref().is_ok_and(|p| {
                p.workspace.owner_token == workspace.owner_token
                    && p.workspace.worktree_path == directory.join("tree")
                    && p.workspace.id == workspace.id
                    && p.workspace.base_commit == workspace.base_commit
                    && p.workspace.git_common_dir == workspace.git_common_dir
                    && store
                        .get_snapshot(session.baseline.clone())
                        .is_ok_and(|s| s.manifest.workspace_id == workspace.id)
            });
            let mut current = "ownership_unverified";
            if owned {
                match std::fs::symlink_metadata(&workspace.worktree_path) {
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => current = "missing",
                    Ok(meta)
                        if meta.is_dir()
                            && workspace.worktree_path.canonicalize().ok().as_ref()
                                == Some(&workspace.worktree_path) =>
                    {
                        let captured = preparation
                            .as_ref()
                            .ok()
                            .ok_or(Error::Invalid("preparation unavailable"))
                            .and_then(worktrees::verify_owned_record)
                            .and_then(|()| worktrees::capture_all(&workspace.worktree_path));
                        current = match (&checkpoint, captured) {
                            (Ok(snapshot), Ok(files)) => {
                                if files.len() == snapshot.manifest.files.len()
                                    && files.iter().all(|(path, file)| {
                                        snapshot.manifest.files.get(path).is_some_and(|entry| {
                                            entry.blob == history::ContentId::hash(&file.bytes)
                                                && entry.mode == file.mode
                                                && entry.kind == file.kind
                                        })
                                    })
                                {
                                    "matches_checkpoint"
                                } else {
                                    "differs_from_checkpoint"
                                }
                            }
                            _ => "unreadable_or_unstable",
                        };
                    }
                    _ => current = "unreadable_or_redirected",
                }
            }
            let process: Result<ProcessRecord> = read(&directory.join("agent-process.json"));
            sessions.push(json!({"sessionId":session.id,"status":session.status,"workspaceId":workspace.id,
                "checkpoint":checkpoint.as_ref().ok().map(|s|&s.id),"checkpointError":checkpoint.err().map(|e|e.to_string()),
                "worktree":current,"process":process.ok().filter(|p|p.owner_token == workspace.owner_token),
                "writersVerifiedStopped":false}));
        }
        after = page
            .next
            .map(SessionId::try_from)
            .transpose()
            .map_err(|_| Error::Invalid("invalid session cursor"))?;
        if after.is_none() {
            break;
        }
    }
    let mut preparations = Vec::new();
    let root = store.directory().join("worktrees");
    if root.exists() {
        if !std::fs::symlink_metadata(&root)?.is_dir() {
            return Err(Error::Invalid("worktree inventory redirected"));
        }
        for entry in std::fs::read_dir(&root)? {
            let entry = entry?;
            if !entry
                .file_name()
                .to_string_lossy()
                .starts_with("workspace-")
            {
                continue;
            }
            if !entry.file_type()?.is_dir() {
                preparations.push(json!({"workspace":entry.file_name().to_string_lossy(),"error":"redirected ownership directory"}));
                continue;
            }
            let result: Result<worktrees::Preparation> =
                read(&entry.path().join("preparation.json"));
            preparations.push(match result {
                Ok(record) => json!({"workspaceId":record.workspace.id,"stage":record.stage,"treePresent":entry.path().join("tree").is_dir()}),
                Err(_) => json!({"workspace":entry.file_name().to_string_lossy(),"error":"incomplete or damaged ownership journal"}),
            });
        }
    }
    Ok(
        json!({"storage":store.storage_report()?,"sessions":sessions,"preparations":preparations,
        "policy":"No mutations replayed. Live worktrees and unlinked snapshots retained. Writer shutdown requires explicit verification."}),
    )
}

/// Explicit operator acknowledgement of stopped writers is mandatory, even
/// after normal teardown: worktrees are not OS sandboxes. Stale lease tokens
/// still have to match exactly, and an active OS lease can never be overridden.
pub fn recover_session(
    store: HistoryStore,
    session: SessionId,
    recovery: Recovery,
    writers_stopped: bool,
) -> Result<Option<String>> {
    if !writers_stopped {
        return Err(Error::Invalid(
            "verify all session writers stopped before recovery",
        ));
    }
    let record = store.get_session(session.clone())?;
    let root = store.snapshot()?.state["projectRoot"]
        .as_str()
        .ok_or(Error::Invalid("project root missing"))?
        .to_owned();
    let manager = WorktreeManager::open(Path::new(&root), store.clone(), recovery.clone())?;
    let prepared = manager.acquire(&record.workspace_id, recovery)?;
    if store
        .get_snapshot(record.baseline.clone())?
        .manifest
        .workspace_id
        != prepared.preparation.workspace.id
    {
        return Err(Error::Invalid("baseline ownership mismatch"));
    }
    let mut recorder = Recorder::recover(store.clone(), session.clone())?;
    let turn = format!("recovery-{}", token()?);
    let started = now()?;
    let files = worktrees::capture_all(&prepared.preparation.workspace.worktree_path)?;
    store.create_turn(
        MutationKey::try_from(turn.clone())
            .map_err(|_| Error::Invalid("recovery identity invalid"))?,
        Turn {
            id: turn.clone(),
            session_id: session.clone(),
            prompt_provenance: "Explicit recovery observation; no agent prompt replayed".into(),
            upstream_id: None,
            status: "interrupted".into(),
            created_at_ms: started,
        },
    )?;
    let result = recorder.observe(SnapshotInput {workspace_id:record.workspace_id,files,coverage:Coverage{complete:true,excluded:vec![]},started_at_ms:started,completed_at_ms:now()?,provenance:"explicit-crash-reconciliation".into()}, Observation {
        turn_id:turn,status:OperationStatus::Interrupted,attribution:Attribution::Ambiguous,tool_call_id:None,
        evidence:"Explicit recovery; writer shutdown acknowledged by operator; stable reads are not proof of quiescence; intermediate writes unknown".into(),
    })?;
    // Closing only after successful capture keeps failures recoverable.
    store.agent_event(
        session,
        None,
        "closed".into(),
        "session.recovered".into(),
        json!({"operation":result,"replayed":false}),
    )?;
    Ok(result)
}

pub fn delete_session(
    store: HistoryStore,
    session: SessionId,
    recovery: Recovery,
    writers_stopped: bool,
) -> Result<()> {
    if !writers_stopped {
        return Err(Error::Invalid(
            "verify all session writers stopped before retention",
        ));
    }
    let record = store.get_session(session.clone())?;
    let root = store.snapshot()?.state["projectRoot"]
        .as_str()
        .ok_or(Error::Invalid("project root missing"))?
        .to_owned();
    let manager = WorktreeManager::open(Path::new(&root), store.clone(), recovery.clone())?;
    let prepared = manager.acquire(&record.workspace_id, recovery)?;
    if store
        .get_snapshot(record.baseline.clone())?
        .manifest
        .workspace_id
        != prepared.preparation.workspace.id
    {
        return Err(Error::Invalid("baseline ownership mismatch"));
    }
    store.delete_closed_session(session, record.workspace_id)?;
    Ok(())
}

#[cfg(test)]
mod tests;

/// Caller holds the project writer lease. A new local/upstream session gets the
/// selected immutable checkpoint as baseline; earlier histories remain untouched.
pub(crate) fn continue_workspace(
    store: &HistoryStore,
    manager: &WorktreeManager,
    source: SessionId,
) -> Result<(worktrees::PreparedWorktree, history::ContentId)> {
    let session = store.get_session(source.clone())?;
    if !matches!(session.status.as_str(), "closed" | "archived") {
        return Err(Error::Invalid("close or recover the source session first"));
    }
    let prepared = manager.acquire(&session.workspace_id, Recovery::RefuseStale)?;
    let checkpoint = store.checkpoint(source)?;
    let files = worktrees::capture_all(&prepared.preparation.workspace.worktree_path)?;
    if files.len() != checkpoint.manifest.files.len()
        || !files.iter().all(|(path, input)| {
            checkpoint.manifest.files.get(path).is_some_and(|entry| {
                entry.blob == history::ContentId::hash(&input.bytes)
                    && entry.kind == input.kind
                    && entry.mode == input.mode
            })
        })
    {
        return Err(Error::Invalid(
            "workspace differs from retained checkpoint; recover explicitly",
        ));
    }
    Ok((prepared, checkpoint.id))
}
