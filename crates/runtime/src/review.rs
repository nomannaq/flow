//! Whole-result candidates. No operation here writes the main project or live
//! agent worktree. An accepted review is not an application receipt.
use crate::worktrees::{self, PreparedWorktree, Recovery, WorktreeManager};
use history::{Candidate, CandidateReview, ContentId, Coverage, HistoryStore, SnapshotInput};
use protocol::{CandidateCheck, CandidateDecision, CandidatePrepare, MutationKey, ProtocolStore};
use serde_json::{Value, json};
use std::{
    path::{Component, Path},
    process::Stdio,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use worktrees::{Error, Result};

pub(crate) fn verify(
    store: &HistoryStore,
    candidate: &Candidate,
    stopped: bool,
) -> Result<PreparedWorktree> {
    verify_recovery(store, candidate, stopped, Recovery::RefuseStale)
}
pub(crate) fn verify_recovery(
    store: &HistoryStore,
    candidate: &Candidate,
    stopped: bool,
    recovery: Recovery,
) -> Result<PreparedWorktree> {
    if !stopped {
        return Err(Error::Invalid(
            "verify all session writers stopped before preparing or accepting a candidate",
        ));
    }
    let source = store.candidate_source(candidate.session_id.clone())?;
    if source.baseline != candidate.baseline
        || source.source != candidate.source
        || source.generation != candidate.generation
    {
        return Err(Error::Invalid(
            "candidate source changed; prepare and review again",
        ));
    }
    let session = store.get_session(candidate.session_id.clone())?;
    let state = store.snapshot()?;
    let root = state.state["projectRoot"]
        .as_str()
        .ok_or(Error::Invalid("project root unavailable"))?;
    let manager = WorktreeManager::open(Path::new(root), store.clone(), recovery.clone())?;
    let prepared = manager.acquire(&session.workspace_id, recovery)?;
    if prepared.preparation.baseline.as_ref() != Some(&candidate.baseline)
        || store.checkpoint(session.id)?.id != candidate.source
    {
        return Err(Error::Invalid(
            "candidate requires its original clean-commit baseline",
        ));
    }
    let snapshot = store.get_snapshot(candidate.source.clone())?;
    let current = worktrees::capture_all(&prepared.preparation.workspace.worktree_path)?;
    if current.len() != snapshot.manifest.files.len()
        || current.iter().any(|(path, f)| {
            snapshot.manifest.files.get(path).is_none_or(|old| {
                old.blob != ContentId::hash(&f.bytes) || old.mode != f.mode || old.kind != f.kind
            })
        })
    {
        return Err(Error::Invalid(
            "live worktree changed; reconcile and review a new candidate",
        ));
    }
    Ok(prepared)
}

pub fn prepare(store: HistoryStore, params: CandidatePrepare) -> Result<CandidateReview> {
    let candidate = store.candidate_source(params.session_id)?;
    let _guard = verify(&store, &candidate, params.writers_stopped)?;
    Ok(store.save_candidate(candidate)?)
}
pub fn decide(store: HistoryStore, params: CandidateDecision) -> Result<CandidateReview> {
    let id = ContentId::try_from(params.candidate_id).map_err(Error::Invalid)?;
    let current = store.candidate_review(id.clone())?;
    let _guard = if params.accept {
        Some(verify(&store, &current.candidate, params.writers_stopped)?)
    } else {
        None
    };
    Ok(store.decide_candidate(id, params.accept)?)
}

/// Commands are supplied by an explicit host action, never read from repository
/// instructions. Persist intent before execution; retries with that ID only read.
pub fn check(store: HistoryStore, params: CandidateCheck) -> Result<Value> {
    // ponytail: checks fit the existing 30s RPC window. Longer checks need a
    // separately polled persisted job rather than a longer transport timeout.
    if params.check_id.as_str().len() > 120
        || params.command.iter().map(String::len).sum::<usize>() > 8192
        || params.command.is_empty()
        || params.command.len() > 32
        || params
            .command
            .iter()
            .any(|s| s.len() > 4096 || s.contains('\0'))
        || params.command[0].is_empty()
        || !(1..=15).contains(&params.timeout_seconds)
    {
        return Err(Error::Invalid(
            "use an explicit executable/arguments and a check timeout of 1–15 seconds",
        ));
    }
    let id = ContentId::try_from(params.candidate_id).map_err(Error::Invalid)?;
    let candidate = store.candidate_review(id.clone())?.candidate;
    let _guard = verify(&store, &candidate, params.writers_stopped)?;
    let mut record = store.begin_candidate_check(
        params.check_id.as_str().to_owned(),
        id.clone(),
        params.command.clone(),
    )?;
    // On a lost reply, even a still-running intent is returned without replay.
    if record["status"] != "running" || record.get("executionStarted").is_some() {
        return Ok(record);
    }
    // Claiming execution is durable and atomic in the store, including duplicate
    // requests while a previous blocking check is still running.
    if !store.claim_candidate_check(params.check_id.as_str().to_owned())? {
        return Ok(record);
    }
    record["executionStarted"] = json!(true);
    let outcome = (|| -> Result<()> {
        let scratch = tempfile::Builder::new()
            .prefix("candidate-check-")
            .tempdir_in(store.directory())?;
        let snapshot = store.get_snapshot(candidate.result.clone())?;
        for (path, entry) in &snapshot.manifest.files {
            if !Path::new(path)
                .components()
                .all(|p| matches!(p, Component::Normal(_)))
                || entry.kind != history::FileKind::Regular
            {
                return Err(Error::Invalid("unsupported candidate path or file type"));
            }
            let destination = scratch.path().join(path);
            std::fs::create_dir_all(
                destination
                    .parent()
                    .ok_or(Error::Invalid("candidate parent missing"))?,
            )?;
            std::fs::write(&destination, store.content_bytes(entry.blob.clone())?)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(
                    &destination,
                    std::fs::Permissions::from_mode(entry.mode),
                )?;
            }
        }
        worktrees::io::git(
            scratch.path(),
            ["-c", "init.templateDir=", "init", "--quiet"],
        )?;
        let started = std::time::Instant::now();
        let execution = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(execute(
                scratch.path(),
                &params.command,
                params.timeout_seconds,
            ));
        let status = match execution {
            Ok(status) => status,
            Err(error) => {
                if !matches!(
                    error,
                    Error::Invalid("check executable could not be launched")
                ) {
                    _guard.quarantine();
                    let retained = scratch.keep();
                    record["retainedScratch"] =
                        json!(retained.file_name().and_then(|n| n.to_str()));
                }
                return Err(error);
            }
        };
        record["elapsedMs"] = json!(started.elapsed().as_millis() as u64);
        record["exitCode"] = json!(status);
        record["status"] = json!(if status == Some(0) {
            "passed"
        } else {
            "failed"
        });
        let files = worktrees::capture_all(scratch.path())?;
        let unchanged = files.len() == snapshot.manifest.files.len()
            && files.iter().all(|(path, f)| {
                snapshot.manifest.files.get(path).is_some_and(|old| {
                    old.blob == ContentId::hash(&f.bytes)
                        && old.mode == f.mode
                        && old.kind == f.kind
                })
            });
        let mut next = candidate.clone();
        if !unchanged {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;
            let receipt=store.publish_snapshot(MutationKey::try_from(format!("{}-result",params.check_id.as_str())).map_err(Error::Invalid)?,SnapshotInput {workspace_id:snapshot.manifest.workspace_id,files,coverage:Coverage {complete:true,excluded:vec![]},started_at_ms:now,completed_at_ms:now,provenance:"Explicit validation command produced new candidate bytes; source worktree unchanged".into()})?;
            next.result = serde_json::from_value(
                receipt.outcome.ok_or(history::Error::CorruptMetadata)?["id"].clone(),
            )
            .map_err(|_| Error::Invalid("check snapshot unavailable"))?;
        }
        // Recheck the source after command execution before associating results.
        // The original leases are still held, so compare the current bytes here.
        let source = store.get_snapshot(candidate.source.clone())?;
        let files = worktrees::capture_all(&_guard.preparation.workspace.worktree_path)?;
        if files.len() != source.manifest.files.len()
            || files.iter().any(|(p, f)| {
                source.manifest.files.get(p).is_none_or(|old| {
                    old.blob != ContentId::hash(&f.bytes)
                        || old.mode != f.mode
                        || old.kind != f.kind
                })
            })
        {
            return Err(Error::Invalid("source changed during candidate check"));
        }
        record["resultCandidateId"] = json!(store.save_candidate(next)?.id);
        record["changedBytes"] = json!(!unchanged);
        Ok(())
    })();
    if outcome.is_err() {
        record["status"] = json!("failed");
        record["diagnostic"] = json!(
            "Check failed, exceeded its bound, or changed unsupported/source files; no approval was recorded."
        );
    }
    store.finish_candidate_check(params.check_id.as_str().to_owned(), record.clone())?;
    Ok(record)
}

async fn execute(root: &Path, args: &[String], seconds: u64) -> Result<Option<i32>> {
    let mut command = tokio::process::Command::new(&args[0]);
    command
        .args(&args[1..])
        .current_dir(root)
        .env_clear()
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    for name in [
        "HOME",
        "PATH",
        "SYSTEMROOT",
        "WINDIR",
        "USERPROFILE",
        "LOCALAPPDATA",
        "APPDATA",
        "TEMP",
        "TMP",
        "TMPDIR",
        "LANG",
    ] {
        if let Some(value) = std::env::var_os(name) {
            command.env(name, value);
        }
    }
    #[cfg(unix)]
    {
        command.process_group(0).kill_on_drop(true);
        let mut child = command
            .spawn()
            .map_err(|_| Error::Invalid("check executable could not be launched"))?;
        let pid = child
            .id()
            .and_then(|p| i32::try_from(p).ok())
            .and_then(rustix::process::Pid::from_raw)
            .ok_or(Error::Invalid("check process unavailable"))?;
        let status = tokio::time::timeout(Duration::from_secs(seconds), child.wait()).await;
        // Always terminate remaining group members before inspecting scratch bytes.
        let _ = rustix::process::kill_process_group(pid, rustix::process::Signal::KILL);
        tokio::time::timeout(Duration::from_secs(3), child.wait())
            .await
            .map_err(|_| Error::Invalid("check process reaping unverified"))??;
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        loop {
            match rustix::process::test_kill_process_group(pid) {
                Err(rustix::io::Errno::SRCH) => break,
                Ok(()) | Err(rustix::io::Errno::PERM) if std::time::Instant::now() < deadline => {
                    tokio::time::sleep(Duration::from_millis(25)).await
                }
                _ => return Err(Error::Invalid("check process-group shutdown unverified")),
            }
        }
        Ok(status.ok().transpose()?.and_then(|s| s.code()))
    }
    #[cfg(windows)]
    {
        use process_wrap::tokio::{CommandWrap, CreationFlags, JobObject, KillOnDrop};
        let mut wrapped = CommandWrap::from(command);
        let mut flags = CreationFlags(Default::default());
        flags.0.0 = 0x08000000;
        wrapped.wrap(flags).wrap(KillOnDrop).wrap(JobObject);
        let mut child = wrapped.spawn()?;
        match tokio::time::timeout(Duration::from_secs(seconds), child.wait()).await {
            Ok(Ok(status)) => Ok(status.code()),
            Ok(Err(error)) => {
                let _ = child.start_kill();
                Err(error.into())
            }
            Err(_) => {
                child.start_kill()?;
                tokio::time::timeout(Duration::from_secs(3), child.wait())
                    .await
                    .map_err(|_| Error::Invalid("check job shutdown unverified"))??;
                Ok(None)
            }
        }
    }
}

#[cfg(test)]
pub(crate) mod tests;
