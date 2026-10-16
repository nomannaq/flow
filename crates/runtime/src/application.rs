//! Rust plans and journals; the IDE alone performs project writes. Unknown host
//! outcomes are inspected, never replayed. No Git index mutation is used.
use crate::{
    review,
    worktrees::{self, Error, PreparedWorktree, Recovery, Result},
};
use history::{
    ApplyFile, ApplyJournal, CandidateReview, ContentId, Coverage, FileEntry, FileInput, FileKind,
    HistoryStore, SnapshotInput,
};
use protocol::{
    ApplyAction, ApplyPrepare, ApplyQuery, ApplyResolve, Method, MutationKey, ProtocolStore,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::Read,
    path::{Path, PathBuf},
    sync::Mutex,
};

pub const MAX_APPLY_BYTES: usize = 64 * 1024;
struct Active {
    id: String,
    guard: Option<PreparedWorktree>,
}
impl Drop for Active {
    fn drop(&mut self) {
        if let Some(guard) = &self.guard {
            guard.quarantine();
        }
    }
}
#[derive(Default)]
pub struct ApplyHost {
    active: Mutex<Option<Active>>,
}
fn invalid() -> Error {
    Error::Invalid(
        "Application preconditions changed or recovery is required; inspect the journal. No write was authorized.",
    )
}
fn key(s: String) -> Result<MutationKey> {
    MutationKey::try_from(s).map_err(Error::Invalid)
}
fn entry(file: &FileInput) -> FileEntry {
    FileEntry {
        blob: ContentId::hash(&file.bytes),
        size: file.bytes.len() as u64,
        mode: file.mode,
        kind: file.kind.clone(),
    }
}

// Portable V1 envelope: reject ambiguous/control paths instead of guessing on a
// case-insensitive host. Unicode/path expansion needs native platform fixtures.
pub fn validate_path(path: &str) -> Result<()> {
    if path.len() > 4096
        || path.is_empty()
        || !path.is_ascii()
        || path.contains(['\\', ':', '*', '?', '<', '>', '|', '"'])
        || path.bytes().any(|c| c < 32 || c == 127)
    {
        return Err(invalid());
    }
    for part in path.split('/') {
        let stem = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        if part.is_empty()
            || part == "."
            || part == ".."
            || part.eq_ignore_ascii_case(".git")
            || part.to_ascii_lowercase().starts_with("git~")
            || part.to_ascii_lowercase().starts_with(".git~")
            || part.ends_with(['.', ' '])
            || ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
            || (stem.len() == 4
                && (stem.starts_with("COM") || stem.starts_with("LPT"))
                && stem.as_bytes()[3].is_ascii_digit())
        {
            return Err(invalid());
        }
    }
    Ok(())
}
fn read_file(root: &Path, path: &str) -> Result<Option<FileInput>> {
    validate_path(path)?;
    if root.canonicalize()? != root {
        return Err(invalid());
    }
    let mut target = root.to_owned();
    let parts: Vec<_> = path.split('/').collect();
    for (i, part) in parts.iter().enumerate() {
        for item in fs::read_dir(&target)? {
            let name = item?.file_name();
            if name.to_string_lossy().eq_ignore_ascii_case(part) && name != *part {
                return Err(invalid());
            }
        }
        target.push(part);
        match fs::symlink_metadata(&target) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e.into()),
            Ok(meta) if meta.file_type().is_symlink() => return Err(invalid()),
            Ok(meta) if i + 1 < parts.len() => {
                if !meta.is_dir()
                    || target.canonicalize()? != target
                    || target.join(".git").symlink_metadata().is_ok()
                {
                    return Err(invalid());
                }
            }
            Ok(meta) => {
                if !meta.is_file() || meta.len() > MAX_APPLY_BYTES as u64 {
                    return Err(invalid());
                }
                #[cfg(unix)]
                let mode = {
                    use std::os::unix::fs::MetadataExt;
                    if meta.nlink() != 1 {
                        return Err(invalid());
                    }
                    meta.mode() & 0o7777
                };
                #[cfg(windows)]
                let mode = if meta.permissions().readonly() {
                    0o444
                } else {
                    0o644
                };
                if mode & 0o7000 != 0 {
                    return Err(invalid());
                }
                let file = fs::File::open(&target)?;
                #[cfg(windows)]
                if winapi_util::file::information(&file)?.number_of_links() != 1 {
                    return Err(invalid());
                }
                let mut bytes = Vec::new();
                file.take(MAX_APPLY_BYTES as u64 + 1)
                    .read_to_end(&mut bytes)?;
                if bytes.len() > MAX_APPLY_BYTES || target.canonicalize()? != target {
                    return Err(invalid());
                }
                return Ok(Some(FileInput {
                    bytes,
                    mode,
                    kind: FileKind::Regular,
                }));
            }
        }
    }
    Err(invalid())
}
fn stable_file(root: &Path, path: &str) -> Result<Option<FileInput>> {
    let first = read_file(root, path)?;
    if first != read_file(root, path)? {
        return Err(Error::Unstable);
    }
    Ok(first)
}
fn fingerprint(root: &Path) -> Result<(String, ContentId)> {
    let head = String::from_utf8(worktrees::io::git(
        root,
        ["rev-parse", "HEAD", "--symbolic-full-name", "HEAD"],
    )?)
    .map_err(|_| invalid())?;
    let index = worktrees::io::git(
        root,
        ["rev-parse", "--path-format=absolute", "--git-path", "index"],
    )?;
    let path = PathBuf::from(String::from_utf8(index).map_err(|_| invalid())?.trim_end());
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take(history::MAX_BLOB_BYTES as u64 + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() > history::MAX_BLOB_BYTES {
        return Err(invalid());
    }
    Ok((head, ContentId::hash(&bytes)))
}
fn merge_text(store: &HistoryStore, file: Option<&FileEntry>) -> Result<String> {
    let bytes = file
        .map(|entry| store.content_bytes(entry.blob.clone()))
        .transpose()?
        .unwrap_or_default();
    if bytes.len() > 32768 || bytes.contains(&0) {
        return Err(invalid());
    }
    String::from_utf8(bytes).map_err(|_| invalid())
}
fn root(store: &HistoryStore) -> Result<PathBuf> {
    Ok(PathBuf::from(
        store.snapshot()?.state["projectRoot"]
            .as_str()
            .ok_or_else(invalid)?,
    ))
}
fn publish(
    store: &HistoryStore,
    workspace: protocol::WorkspaceId,
    files: BTreeMap<String, FileInput>,
    complete: bool,
    id: String,
) -> Result<ContentId> {
    let now = worktrees::io::now()?;
    let receipt = store.publish_snapshot(
        key(id)?,
        SnapshotInput {
            workspace_id: workspace,
            files,
            coverage: Coverage {
                complete,
                excluded: if complete {
                    vec![]
                } else {
                    vec!["Only application target preimages captured".into()]
                },
            },
            started_at_ms: now,
            completed_at_ms: now,
            provenance: "Explicit application planning / native merge; project not written".into(),
        },
    )?;
    serde_json::from_value(receipt.outcome.ok_or_else(invalid)?["id"].clone())
        .map_err(|_| invalid())
}
fn approved(store: &HistoryStore, id: &ContentId) -> Result<CandidateReview> {
    let c = store.candidate_review(id.clone())?;
    if c.decision != "accepted" {
        return Err(invalid());
    }
    // Reuse the same check/decision rules, without mutating the review.
    let mut commands = BTreeSet::new();
    for check in &c.checks {
        if commands.insert(check["command"].to_string())
            && (check["status"] != "passed" || check["resultCandidateId"] != id.as_str())
        {
            return Err(invalid());
        }
    }
    Ok(c)
}
fn git_state(j: &ApplyJournal) -> (String, ContentId) {
    j.rollback_fingerprint
        .clone()
        .unwrap_or_else(|| (j.head.clone(), j.index_digest.clone()))
}
fn check_targets(j: &ApplyJournal) -> Result<()> {
    if fingerprint(&j.root)? != git_state(j) {
        return Err(invalid());
    }
    for f in &j.files {
        let expected = if ["applied", "writingRollback"].contains(&f.status.as_str()) {
            &f.after
        } else {
            &f.before
        };
        if stable_file(&j.root, &f.path)?.as_ref().map(entry) != *expected {
            return Err(invalid());
        }
    }
    Ok(())
}
impl ApplyHost {
    pub fn request(&self, store: HistoryStore, method: Method, params: Value) -> Result<Value> {
        let mut active = self.active.lock().map_err(|_| invalid())?;
        match method {
            Method::ApplyList => {
                return Ok(json!(
                    store
                        .apply_journals()?
                        .iter()
                        .map(|j| json!({"id":j.id,"candidateId":j.candidate_id,"status":j.status}))
                        .collect::<Vec<_>>()
                ));
            }
            Method::ApplyPlan => {
                let p: ApplyPrepare = serde_json::from_value(params).map_err(|_| invalid())?;
                if p.plan_id.as_str().len() > 80 {
                    return Err(invalid());
                }
                let id = ContentId::try_from(p.candidate_id).map_err(Error::Invalid)?;
                match store.apply_journal(p.plan_id.as_str().into()) {
                    Ok(old) if old.candidate_id == id => return Ok(json!(old)),
                    Ok(_) => return Err(invalid()),
                    Err(history::Error::NotFound) => {}
                    Err(e) => return Err(e.into()),
                }
                let candidate = approved(&store, &id)?;
                let _guard = review::verify(&store, &candidate.candidate, p.writers_stopped)?;
                let root = root(&store)?;
                let base = store.get_snapshot(candidate.candidate.baseline.clone())?;
                let result = store.get_snapshot(candidate.candidate.result.clone())?;
                let paths: BTreeSet<_> = base
                    .manifest
                    .files
                    .keys()
                    .chain(result.manifest.files.keys())
                    .filter(|p| base.manifest.files.get(*p) != result.manifest.files.get(*p))
                    .cloned()
                    .collect();
                if paths.len() > 128 {
                    return Err(Error::Invalid(
                        "Application supports at most 128 changed paths per complete result",
                    ));
                }
                let (head, index_digest) = fingerprint(&root)?;
                let mut current = BTreeMap::new();
                let mut files = Vec::new();
                for path in paths {
                    validate_path(&path)?;
                    let before = stable_file(&root, &path)?;
                    let now = before.as_ref().map(entry);
                    let baseline = base.manifest.files.get(&path);
                    let after = result.manifest.files.get(&path).cloned();
                    for f in baseline
                        .into_iter()
                        .chain(after.as_ref())
                        .chain(now.as_ref())
                    {
                        if f.kind != FileKind::Regular || f.size > MAX_APPLY_BYTES as u64 {
                            return Err(invalid());
                        }
                    }
                    if let Some(f) = &before {
                        current.insert(path.clone(), f.clone());
                    }
                    let mode_change = now
                        .as_ref()
                        .zip(after.as_ref())
                        .is_some_and(|(a, b)| a.mode != b.mode)
                        || (now.is_none() && after.as_ref().is_some_and(|a| a.mode != 0o644));
                    if mode_change {
                        return Err(Error::Invalid(
                            "Application of permission-mode changes is unsupported; files were preserved",
                        ));
                    }
                    let status = if now == after {
                        "unchanged"
                    } else if now.as_ref() == baseline {
                        "todo"
                    } else {
                        "conflict"
                    };
                    files.push(ApplyFile {
                        authorized: false,
                        path,
                        before: now,
                        after,
                        status: status.into(),
                    });
                }
                for pair in files.iter().enumerate() {
                    for other in files.iter().skip(pair.0 + 1) {
                        if pair.1.path.eq_ignore_ascii_case(&other.path)
                            || pair.1.path.starts_with(&format!("{}/", other.path))
                            || other.path.starts_with(&format!("{}/", pair.1.path))
                        {
                            return Err(invalid());
                        }
                    }
                }
                let current_id = publish(
                    &store,
                    base.manifest.workspace_id,
                    current,
                    false,
                    format!("{}-preimages", p.plan_id.as_str()),
                )?;
                let j = ApplyJournal {
                    id: p.plan_id.as_str().into(),
                    candidate_id: id,
                    current_id,
                    root,
                    head,
                    index_digest,
                    rollback_fingerprint: None,
                    status: if files.iter().any(|f| f.status == "conflict") {
                        "conflicted"
                    } else {
                        "planned"
                    }
                    .into(),
                    files,
                    owner: None,
                    document_stamps: BTreeMap::new(),
                    superseded_by: None,
                    resolution_digest: None,
                };
                check_targets(&j)?;
                store.save_apply_journal(j.clone())?;
                return Ok(json!(j));
            }
            Method::ApplyResolve => {
                let p: ApplyResolve = serde_json::from_value(params).map_err(|_| invalid())?;
                let mut j = store.apply_journal(p.plan_id.as_str().into())?;
                let digest = ContentId::hash(
                    serde_json::to_vec(&(p.plan_id.as_str(), p.file_index, &p.content))
                        .map_err(|_| invalid())?
                        .as_slice(),
                );
                if let Some(next) = &j.superseded_by {
                    if j.resolution_digest.as_ref() == Some(&digest) {
                        return Ok(json!(store.apply_journal(next.clone())?));
                    }
                    return Err(invalid());
                }
                if j.status != "conflicted"
                    || p.content.len() > MAX_APPLY_BYTES
                    || p.content.contains('\0')
                {
                    return Err(invalid());
                }
                let c = store.candidate_review(j.candidate_id.clone())?;
                let _guard = review::verify(&store, &c.candidate, true)?;
                check_targets(&j)?;
                let f = j.files.get(p.file_index).ok_or_else(invalid)?;
                if f.status != "conflict" || f.before.is_none() || f.after.is_none() {
                    return Err(Error::Invalid(
                        "Deletion/addition collisions cannot use text merge; preserve files and replan",
                    ));
                }
                let snapshot = store.get_snapshot(c.candidate.result.clone())?;
                let baseline = store.get_snapshot(c.candidate.baseline.clone())?;
                for input in [
                    baseline.manifest.files.get(&f.path),
                    f.before.as_ref(),
                    f.after.as_ref(),
                ] {
                    merge_text(&store, input)?;
                }
                let mut files = BTreeMap::new();
                for (path, e) in &snapshot.manifest.files {
                    files.insert(
                        path.clone(),
                        FileInput {
                            bytes: store.content_bytes(e.blob.clone())?,
                            mode: e.mode,
                            kind: e.kind.clone(),
                        },
                    );
                }
                let output = FileInput {
                    bytes: p.content.into_bytes(),
                    mode: f.after.as_ref().ok_or_else(invalid)?.mode,
                    kind: FileKind::Regular,
                };
                files.insert(f.path.clone(), output.clone());
                let snapshot_id = publish(
                    &store,
                    snapshot.manifest.workspace_id.clone(),
                    files,
                    true,
                    format!("merge-{}", digest.as_str()),
                )?;
                let mut candidate = c.candidate;
                candidate.result = snapshot_id;
                let next_candidate = store.save_candidate(candidate)?;
                // A merge creates a new review version, even if the output bytes
                // coincide with a previously reviewed result.
                store.reset_candidate_review(next_candidate.id.clone())?;
                let mut next = j.clone();
                next.id = format!("plan-{}", worktrees::io::token()?);
                next.candidate_id = next_candidate.id;
                next.files[p.file_index].after = Some(entry(&output));
                next.files[p.file_index].status =
                    if next.files[p.file_index].before == next.files[p.file_index].after {
                        "unchanged"
                    } else {
                        "todo"
                    }
                    .into();
                next.status = if next.files.iter().any(|f| f.status == "conflict") {
                    "conflicted"
                } else {
                    "planned"
                }
                .into();
                // Other complete-result files remain exactly the reviewed result.
                store.save_apply_journal(next.clone())?;
                j.status = "superseded".into();
                j.superseded_by = Some(next.id.clone());
                j.resolution_digest = Some(digest);
                store.update_apply_journal(j)?;
                return Ok(json!(next));
            }
            Method::ApplyBegin | Method::ApplyReconcile | Method::ApplyRollback => {
                let p: ApplyAction = serde_json::from_value(params).map_err(|_| invalid())?;
                let mut j = store.apply_journal(p.plan_id.as_str().into())?;
                if p.candidate_id != j.candidate_id.as_str() || !p.writers_stopped {
                    return Err(invalid());
                }
                if active.is_some() {
                    return Err(Error::Busy);
                }
                if p.document_stamps.len() > 128
                    || p.document_stamps
                        .keys()
                        .any(|p| !j.files.iter().any(|f| &f.path == p))
                {
                    return Err(invalid());
                }
                if method == Method::ApplyBegin {
                    if j.status != "planned" {
                        return Err(invalid());
                    }
                    let candidate = approved(&store, &j.candidate_id)?;
                    let guard = review::verify(&store, &candidate.candidate, true)?;
                    check_targets(&j)?;
                    j.owner = Some(guard.owner_instance().into());
                    j.document_stamps = p.document_stamps;
                    j.status = "applying".into();
                    store.update_apply_journal(j.clone())?;
                    *active = Some(Active {
                        id: j.id.clone(),
                        guard: Some(guard),
                    });
                } else {
                    if ![
                        "complete",
                        "applying",
                        "rollingBack",
                        "recoveryRequired",
                        "recoveryConflict",
                    ]
                    .contains(&j.status.as_str())
                    {
                        return Err(invalid());
                    }
                    let workspace = store
                        .get_session(
                            store
                                .candidate_review(j.candidate_id.clone())?
                                .candidate
                                .session_id,
                        )?
                        .workspace_id;
                    let recovery = j
                        .owner
                        .clone()
                        .map(Recovery::Acknowledge)
                        .unwrap_or_default();
                    let manager =
                        worktrees::WorktreeManager::open(&j.root, store.clone(), recovery.clone())?;
                    let guard = manager.acquire(&workspace, recovery)?;
                    if method == Method::ApplyRollback {
                        j.rollback_fingerprint = Some(fingerprint(&j.root)?);
                    }
                    reconcile(&mut j)?;
                    j.owner = Some(guard.owner_instance().into());
                    j.document_stamps = p.document_stamps;
                    if method == Method::ApplyRollback && j.status != "recoveryConflict" {
                        j.status = "rollingBack".into();
                    }
                    store.update_apply_journal(j.clone())?;
                    if j.status == "rollingBack" {
                        *active = Some(Active {
                            id: j.id.clone(),
                            guard: Some(guard),
                        });
                    }
                }
                return Ok(json!(j));
            }
            _ => {}
        }
        let p: ApplyQuery = serde_json::from_value(params).map_err(|_| invalid())?;
        let mut j = store.apply_journal(p.plan_id.as_str().into())?;
        if method == Method::ApplyGet {
            return Ok(json!(j));
        }
        if method == Method::ApplyPreview {
            let f = j.files.get(p.file_index).ok_or_else(invalid)?;
            let candidate = store.candidate_review(j.candidate_id.clone())?;
            let base = store
                .get_snapshot(candidate.candidate.baseline)?
                .manifest
                .files
                .get(&f.path)
                .cloned();
            return Ok(
                json!({"path":f.path,"baseline":merge_text(&store,base.as_ref())?,"current":merge_text(&store,f.before.as_ref())?,"candidate":merge_text(&store,f.after.as_ref())?}),
            );
        }
        if method == Method::ApplyCancel {
            if ["planned", "conflicted"].contains(&j.status.as_str()) {
                j.status = "cancelled".into();
            } else if ["applying", "rollingBack"].contains(&j.status.as_str()) {
                j.status = "recoveryRequired".into();
            }
            store.update_apply_journal(j.clone())?;
            if active.as_ref().is_some_and(|a| a.id == j.id) {
                active.take();
            }
            return Ok(json!(j));
        }
        if method == Method::ApplyFinish
            && ((j.status == "complete" && !p.rollback) || (j.status == "rolledBack" && p.rollback))
        {
            return Ok(json!(j));
        }
        if active.as_ref().is_none_or(|a| a.id != j.id) {
            return Err(invalid());
        }
        let rollback = j.status == "rollingBack";
        if !rollback && j.status != "applying" {
            return Err(invalid());
        }
        if p.rollback != rollback {
            return Err(invalid());
        }
        if method == Method::ApplyFinish {
            let done = if rollback {
                j.files
                    .iter()
                    .all(|f| ["todo", "rolledBack", "unchanged"].contains(&f.status.as_str()))
            } else {
                j.files
                    .iter()
                    .all(|f| ["applied", "unchanged"].contains(&f.status.as_str()))
            };
            if !done {
                return Err(invalid());
            }
            let validation = check_targets(&j).and_then(|()| {
                if rollback {
                    Ok(())
                } else {
                    let c = approved(&store, &j.candidate_id)?;
                    verify_source(&store, &c.candidate, active.as_ref().ok_or_else(invalid)?)
                }
            });
            if let Err(e) = validation {
                j.status = "recoveryRequired".into();
                store.update_apply_journal(j)?;
                active.take();
                return Err(e);
            }
            j.status = if rollback { "rolledBack" } else { "complete" }.into();
            store.update_apply_journal(j.clone())?;
            // A terminal, verified outcome may release the owner metadata.
            if let Some(mut a) = active.take() {
                drop(a.guard.take());
            }
            return Ok(json!(j));
        }
        if !matches!(method, Method::ApplyAuthorize | Method::ApplyReceipt) {
            return Err(invalid());
        }
        if fingerprint(&j.root)? != git_state(&j) {
            return Err(invalid());
        }
        if !rollback {
            let c = approved(&store, &j.candidate_id)?;
            verify_source(&store, &c.candidate, active.as_ref().ok_or_else(invalid)?)?;
        }
        let f = j.files.get_mut(p.file_index).ok_or_else(invalid)?;
        let (before, after) = if rollback {
            (&f.after, &f.before)
        } else {
            (&f.before, &f.after)
        };
        let writing = if rollback {
            "writingRollback"
        } else {
            "writing"
        };
        if method == Method::ApplyAuthorize {
            let eligible = if rollback { "applied" } else { "todo" };
            if f.status != eligible || stable_file(&j.root, &f.path)?.as_ref().map(entry) != *before
            {
                return Err(invalid());
            }
            let expected = before
                .as_ref()
                .map(|e| store.content_bytes(e.blob.clone()))
                .transpose()?;
            let replacement = after
                .as_ref()
                .map(|e| store.content_bytes(e.blob.clone()))
                .transpose()?;
            let value = json!({"planId":j.id,"fileIndex":p.file_index,"path":f.path,"expected":expected,"replacement":replacement,"beforeMode":before.as_ref().map(|e|e.mode),"afterMode":after.as_ref().map(|e|e.mode),"documentStamp":j.document_stamps.get(&f.path)});
            if j.files
                .iter()
                .any(|f| f.status == "writing" || f.status == "writingRollback")
            {
                return Err(invalid());
            }
            j.files[p.file_index].status = writing.into();
            j.files[p.file_index].authorized = true;
            store.update_apply_journal(j)?;
            return Ok(value);
        }
        let finished = if rollback { "rolledBack" } else { "applied" };
        if ![writing, finished].contains(&f.status.as_str()) {
            return Err(invalid());
        }
        if stable_file(&j.root, &f.path)?.as_ref().map(entry) != *after {
            j.status = "recoveryRequired".into();
            store.update_apply_journal(j)?;
            active.take();
            return Err(invalid());
        }
        f.status = finished.into();
        store.update_apply_journal(j.clone())?;
        Ok(json!(j))
    }
}
// Drop quarantines unknown ownership; successful completion takes the guard out.
fn verify_source(store: &HistoryStore, c: &history::Candidate, a: &Active) -> Result<()> {
    let source = store.candidate_source(c.session_id.clone())?;
    if source.source != c.source || source.generation != c.generation {
        return Err(invalid());
    }
    let files = worktrees::capture_all(
        &a.guard
            .as_ref()
            .ok_or_else(invalid)?
            .preparation
            .workspace
            .worktree_path,
    )?;
    let manifest = store.get_snapshot(c.source.clone())?.manifest;
    if files.len() != manifest.files.len()
        || files
            .iter()
            .any(|(p, f)| manifest.files.get(p) != Some(&entry(f)))
    {
        return Err(invalid());
    }
    Ok(())
}
fn reconcile(j: &mut ApplyJournal) -> Result<()> {
    let fingerprint_ok = fingerprint(&j.root)? == git_state(j);
    for f in &mut j.files {
        let actual = stable_file(&j.root, &f.path)?.as_ref().map(entry);
        f.status = if f.status == "unchanged" && actual == f.before {
            "unchanged"
        } else if actual == f.before {
            if f.status == "writingRollback" || f.status == "rolledBack" {
                "rolledBack"
            } else {
                "todo"
            }
        } else if actual == f.after && f.authorized {
            "applied"
        } else {
            "recoveryConflict"
        }
        .into();
    }
    j.status = if !fingerprint_ok || j.files.iter().any(|f| f.status == "recoveryConflict") {
        "recoveryConflict"
    } else {
        "recoveryRequired"
    }
    .into();
    Ok(())
}

#[cfg(test)]
mod tests;
