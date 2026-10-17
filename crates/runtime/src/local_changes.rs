//! Read-only Git selection and explicit baseline overlays. Git never writes the
//! developer's index; materialization touches only an owned new worktree.
use crate::worktrees::{
    self, Error, Result,
    io::{git, text},
};
use history::{ContentId, FileInput, FileKind};
use protocol::OverlaySelection;
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

fn paths(bytes: &[u8]) -> Result<Vec<String>> {
    bytes
        .split(|b| *b == 0)
        .filter(|p| !p.is_empty())
        .map(|p| {
            let s = std::str::from_utf8(p).map_err(|_| Error::Invalid("UTF-8 paths required"))?;
            if s.contains('\\')
                || Path::new(s)
                    .components()
                    .any(|p| !matches!(p, std::path::Component::Normal(_)))
                || s.split('/').any(|p| p.eq_ignore_ascii_case(".git"))
            {
                return Err(Error::Invalid("unsafe selection path"));
            }
            Ok(s.into())
        })
        .collect()
}
fn admin(root: &Path) -> Result<PathBuf> {
    Ok(PathBuf::from(text(git(
        root,
        ["rev-parse", "--absolute-git-dir"],
    )?)?))
}
fn index(root: &Path) -> Result<Vec<u8>> {
    match fs::read(admin(root)?.join("index")) {
        Ok(v) => Ok(v),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(vec![]),
        Err(e) => Err(e.into()),
    }
}
fn head(root: &Path) -> Result<String> {
    match git(root, ["rev-parse", "--verify", "HEAD^{commit}"]) {
        Ok(v) => text(v),
        Err(_) => {
            // An unborn symbolic HEAD is supported; broken/detached refs are not.
            let branch = text(git(root, ["symbolic-ref", "HEAD"])?)?;
            if git(root, ["show-ref", "--verify", &branch]).is_ok() {
                return Err(Error::Invalid("invalid HEAD"));
            }
            Ok("unborn".into())
        }
    }
}
fn staged_files(root: &Path, raw: &[u8]) -> Result<Vec<Value>> {
    if raw.len() > 32768 {
        return Err(Error::Invalid("staged selection metadata exceeds budget"));
    }
    let mut fields = raw.split(|b| *b == 0).filter(|p| !p.is_empty());
    let mut files = Vec::new();
    let mut remaining = 128 * 1024;
    while let Some(header) = fields.next() {
        if files.len() == 64 {
            return Err(Error::Invalid("staged selection exceeds 64 files"));
        }
        let header =
            std::str::from_utf8(header).map_err(|_| Error::Invalid("invalid staged metadata"))?;
        let parts: Vec<_> = header.trim_start_matches(':').split(' ').collect();
        if parts.len() != 5 {
            return Err(Error::Invalid("invalid staged metadata"));
        }
        let old = fields.next().ok_or(Error::Invalid("missing staged path"))?;
        let new = if parts[4].starts_with(['R', 'C']) {
            fields
                .next()
                .ok_or(Error::Invalid("missing renamed path"))?
        } else {
            old
        };
        let old = std::str::from_utf8(old).map_err(|_| Error::Invalid("non-UTF-8 staged path"))?;
        let new = std::str::from_utf8(new).map_err(|_| Error::Invalid("non-UTF-8 staged path"))?;
        let mut content = Vec::new();
        for oid in [&parts[2], &parts[3]] {
            if oid.bytes().all(|b| b == b'0') {
                content.push(None);
                continue;
            }
            let size: usize = text(git(root, ["cat-file", "-s", oid])?)?
                .parse()
                .map_err(|_| Error::Invalid("invalid staged blob size"))?;
            if size > remaining {
                return Err(Error::Invalid(
                    "staged before/after content exceeds 128 KiB",
                ));
            }
            remaining -= size;
            let bytes = git(root, ["cat-file", "blob", oid])?;
            content.push(Some(
                String::from_utf8(bytes).map_err(|_| Error::Invalid("staged content is binary"))?,
            ));
        }
        files.push(json!({"oldPath":if parts[2].bytes().all(|b|b==b'0') {None} else {Some(old)},"newPath":if parts[3].bytes().all(|b|b==b'0') {None} else {Some(new)},"beforeMode":parts[0],"afterMode":parts[1],"beforeBlob":parts[2],"afterBlob":parts[3],"status":parts[4],"before":content[0],"after":content[1]}));
    }
    if serde_json::to_vec(&files).unwrap().len() > 512 * 1024 {
        return Err(Error::Invalid("staged content envelope exceeds budget"));
    }
    Ok(files)
}

pub fn staged(root: &Path, mode: &str) -> Result<Value> {
    if mode != "staged" {
        return Err(Error::Invalid(
            "only the complete staged index in ordinary commit mode is supported",
        ));
    }
    let root = root.canonicalize()?;
    let repository =
        PathBuf::from(text(git(&root, ["rev-parse", "--show-toplevel"])?)?).canonicalize()?;
    if repository != root {
        return Err(Error::Invalid(
            "open the repository root for staged commits",
        ));
    }
    let directory = admin(&root)?;
    for state in [
        "MERGE_HEAD",
        "CHERRY_PICK_HEAD",
        "REVERT_HEAD",
        "rebase-merge",
        "rebase-apply",
        "sequencer",
    ] {
        if directory.join(state).exists() {
            return Err(Error::Invalid(
                "merge, cherry-pick, rebase and revert commit modes are unsupported",
            ));
        }
    }
    if !git(&root, ["ls-files", "--unmerged", "-z"])?.is_empty() {
        return Err(Error::Invalid("resolve index conflicts first"));
    }
    let before_index = index(&root)?;
    let before_head = head(&root)?;
    let raw = git(
        &root,
        [
            "diff",
            "--cached",
            "--raw",
            "-z",
            "--no-abbrev",
            "--find-renames",
            "--no-ext-diff",
            "--no-textconv",
        ],
    )?;
    if raw.is_empty() {
        return Err(Error::Invalid("stage changes before generating a message"));
    }
    let diff = git(
        &root,
        [
            "diff",
            "--cached",
            "--binary",
            "--full-index",
            "--find-renames",
            "--no-ext-diff",
            "--no-textconv",
        ],
    )?;
    if before_index != index(&root)? || before_head != head(&root)? {
        return Err(Error::Unstable);
    }
    let fingerprint = ContentId::hash(
        &serde_json::to_vec(&(root.to_str(), &before_head, &before_index, &raw, &diff)).unwrap(),
    );
    // Reject oversized/binary input explicitly rather than silently omitting
    // files or pretending a model has reviewed an encoded binary patch.
    let binary = diff.windows(16).any(|s| s == b"GIT binary patch");
    let frozen = if diff.len() <= 48 * 1024 && !binary {
        staged_files(&root, &raw)
    } else {
        Err(Error::Invalid("binary or oversized staged diff"))
    };
    let supported = frozen.is_ok();
    let reason = if binary {
        "Binary staged changes require a manually written message".to_owned()
    } else if diff.len() > 48 * 1024 {
        "Staged diff exceeds 48 KiB; reduce the staged selection or write the message manually"
            .to_owned()
    } else {
        frozen
            .as_ref()
            .err()
            .map(ToString::to_string)
            .unwrap_or_default()
    };
    if before_index != index(&root)? || before_head != head(&root)? {
        return Err(Error::Unstable);
    }
    Ok(
        json!({"mode":"staged","repository":root,"head":before_head,"fingerprint":fingerprint,
        "indexHash":ContentId::hash(&before_index),"supported":supported,"binary":binary,
        "diffBytes":diff.len(),"omittedBytes":if supported {0} else {diff.len()},
        "reason":reason,"files":frozen.unwrap_or_default(),
        "diff":if supported {String::from_utf8(diff).map_err(|_| Error::Invalid("staged diff is not UTF-8"))?} else {String::new()}}),
    )
}

pub fn preview(root: &Path) -> Result<Value> {
    let before = worktrees::source_fingerprint(root)?;
    let mut choices: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (source, args) in [
        (
            "index",
            vec![
                "diff",
                "--cached",
                "--name-only",
                "-z",
                "--no-renames",
                "--no-ext-diff",
                "--no-textconv",
            ],
        ),
        (
            "disk",
            vec![
                "diff",
                "HEAD",
                "--name-only",
                "-z",
                "--no-renames",
                "--no-ext-diff",
                "--no-textconv",
            ],
        ),
        (
            "disk",
            vec!["ls-files", "--others", "--exclude-standard", "-z"],
        ),
    ] {
        for path in paths(&git(root, args)?)? {
            choices.entry(path).or_default().insert(source.into());
        }
    }
    if before != worktrees::source_fingerprint(root)? {
        return Err(Error::Unstable);
    }
    Ok(
        json!({"fingerprint":before,"head":head(root)?,"files":choices.into_iter().map(|(path,sources)| json!({"path":path,"sources":sources})).collect::<Vec<_>>(),"unsavedPolicy":"Choose save or exclude in the IDE; only captured disk/index bytes are imported"}),
    )
}

pub fn freeze(
    root: &Path,
    selection: &OverlaySelection,
) -> Result<BTreeMap<String, Option<FileInput>>> {
    if selection.files.is_empty() || selection.files.len() > 1024 {
        return Err(Error::Invalid("select 1–1024 overlay files"));
    }
    let preview = preview(root)?;
    if preview["fingerprint"].as_str() != Some(&selection.fingerprint) {
        return Err(Error::Unstable);
    }
    let disk = worktrees::capture(root)?;
    let mut files = BTreeMap::new();
    for selected in &selection.files {
        if !preview["files"].as_array().unwrap().iter().any(|f| {
            f["path"] == selected.path
                && f["sources"]
                    .as_array()
                    .unwrap()
                    .contains(&json!(selected.source))
        }) {
            return Err(Error::Invalid(
                "overlay selection is not in the reviewed preview",
            ));
        }
        let file = if selected.source == "disk" {
            disk.get(&selected.path).cloned()
        } else {
            let entry = git(root, ["ls-files", "--stage", "-z", "--", &selected.path])?;
            if entry.is_empty() {
                None
            } else {
                let entry = std::str::from_utf8(&entry)
                    .map_err(|_| Error::Invalid("invalid index entry"))?;
                let header = entry.split('\t').next().unwrap();
                let fields: Vec<_> = header.split(' ').collect();
                if fields.len() != 3
                    || fields[2] != "0"
                    || !matches!(fields[0], "100644" | "100755")
                {
                    return Err(Error::Invalid(
                        "only regular stage-zero overlay files are supported",
                    ));
                }
                Some(FileInput {
                    bytes: git(root, ["cat-file", "blob", fields[1]])?,
                    mode: if fields[0] == "100755" { 0o755 } else { 0o644 },
                    kind: FileKind::Regular,
                })
            }
        };
        if file
            .as_ref()
            .is_some_and(|f| f.kind != FileKind::Regular || f.bytes.len() > history::MAX_BLOB_BYTES)
        {
            return Err(Error::Invalid(
                "overlay contains a symlink or oversized file",
            ));
        }
        if files.insert(selected.path.clone(), file).is_some() {
            return Err(Error::Invalid("duplicate overlay path"));
        }
    }
    if worktrees::source_fingerprint(root)?.as_str() != selection.fingerprint {
        return Err(Error::Unstable);
    }
    Ok(files)
}

pub(crate) fn materialize(root: &Path, files: BTreeMap<String, Option<FileInput>>) -> Result<()> {
    for (path, file) in files {
        let target = root.join(&path);
        let mut parent = root.to_path_buf();
        for part in Path::new(&path).parent().unwrap().components() {
            parent.push(part);
            if parent
                .symlink_metadata()
                .is_ok_and(|m| !m.is_dir() || m.file_type().is_symlink())
            {
                return Err(Error::Invalid("overlay parent is not a directory"));
            }
            fs::create_dir_all(&parent)?;
        }
        if target
            .symlink_metadata()
            .is_ok_and(|m| !m.is_file() || m.file_type().is_symlink())
        {
            return Err(Error::Invalid("overlay destination is not a regular file"));
        }
        match file {
            None => {
                if target.exists() {
                    fs::remove_file(target)?;
                }
            }
            Some(file) => {
                fs::write(&target, &file.bytes)?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(&target, fs::Permissions::from_mode(file.mode))?;
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_repository::Repository;
    use history::HistoryStore;
    #[test]
    fn staged_excludes_disk_and_rejects_mode_changes() {
        let repo = Repository::new();
        repo.write("tracked.txt", b"staged\n");
        repo.git(&["add", "tracked.txt"]);
        repo.write("tracked.txt", b"unstaged-neighbor\n");
        let before = index(&repo.root).unwrap();
        let result = staged(&repo.root, "staged").unwrap();
        assert!(result["diff"].as_str().unwrap().contains("+staged"));
        assert!(
            !result["diff"]
                .as_str()
                .unwrap()
                .contains("unstaged-neighbor")
        );
        assert_eq!(before, index(&repo.root).unwrap());
        assert!(staged(&repo.root, "partial").is_err());
        repo.git(&["add", "tracked.txt"]);
        assert_ne!(
            result["fingerprint"],
            staged(&repo.root, "staged").unwrap()["fingerprint"]
        );
        fs::write(repo.root.join(".git/MERGE_HEAD"), b"fixture").unwrap();
        assert!(staged(&repo.root, "staged").is_err());
    }
    #[test]
    fn overlay_is_baseline_preserves_index_and_rejects_stale_preview() {
        let repo = Repository::new();
        repo.write("tracked.txt", b"staged\n");
        repo.git(&["add", "tracked.txt"]);
        repo.write("tracked.txt", b"disk\n");
        repo.write("new.txt", b"selected\n");
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let preview = preview(&repo.root).unwrap();
        let selection = OverlaySelection {
            fingerprint: preview["fingerprint"].as_str().unwrap().into(),
            files: vec![
                protocol::OverlayFile {
                    path: "tracked.txt".into(),
                    source: "index".into(),
                },
                protocol::OverlayFile {
                    path: "new.txt".into(),
                    source: "disk".into(),
                },
            ],
        };
        let before = index(&repo.root).unwrap();
        let manager = worktrees::WorktreeManager::open(
            &repo.root,
            store.clone(),
            worktrees::Recovery::RefuseStale,
        )
        .unwrap();
        let prepared = manager
            .prepare(worktrees::BaselineChoice::SelectedOverlay(
                preview["head"].as_str().unwrap().into(),
                selection.clone(),
            ))
            .unwrap();
        assert_eq!(
            fs::read(
                prepared
                    .preparation
                    .workspace
                    .worktree_path
                    .join("tracked.txt")
            )
            .unwrap(),
            b"staged\n"
        );
        let snapshot = store
            .get_snapshot(prepared.preparation.baseline.unwrap())
            .unwrap();
        assert!(snapshot.manifest.provenance.contains("B13"));
        assert_eq!(before, index(&repo.root).unwrap());
        assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"disk\n");
        repo.write("new.txt", b"changed\n");
        assert!(freeze(&repo.root, &selection).is_err());
    }
    #[test]
    fn unborn_rename_delete_and_binary_staged_selections() {
        let repo = Repository::new();
        repo.git(&["checkout", "--orphan", "unborn"]);
        let initial = staged(&repo.root, "staged").unwrap();
        assert_eq!(initial["head"], "unborn");
        assert!(initial["diff"].as_str().unwrap().contains("+base"));
        repo.git(&["checkout", "main"]);
        repo.git(&["mv", "tracked.txt", "renamed.txt"]);
        let renamed = staged(&repo.root, "staged").unwrap();
        assert!(
            renamed["diff"]
                .as_str()
                .unwrap()
                .contains("rename to renamed.txt")
        );
        repo.git(&["rm", "-f", "renamed.txt"]);
        assert!(
            staged(&repo.root, "staged").unwrap()["diff"]
                .as_str()
                .unwrap()
                .contains("deleted file")
        );
        repo.write("binary.bin", b"a\0b");
        repo.git(&["add", "binary.bin"]);
        let binary = staged(&repo.root, "staged").unwrap();
        assert_eq!(binary["binary"], true);
        assert_eq!(binary["supported"], false);
        assert_eq!(binary["diff"], "");
    }
    #[test]
    fn imported_edits_are_excluded_from_the_agent_candidate() {
        let repo = Repository::new();
        repo.write("tracked.txt", b"imported baseline\n");
        repo.write("selected.txt", b"imported untracked\n");
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let preview = preview(&repo.root).unwrap();
        let selection = OverlaySelection {
            fingerprint: preview["fingerprint"].as_str().unwrap().into(),
            files: ["tracked.txt", "selected.txt"]
                .into_iter()
                .map(|p| protocol::OverlayFile {
                    path: p.into(),
                    source: "disk".into(),
                })
                .collect(),
        };
        let manager = worktrees::WorktreeManager::open(
            &repo.root,
            store.clone(),
            worktrees::Recovery::RefuseStale,
        )
        .unwrap();
        let prepared = manager
            .prepare(worktrees::BaselineChoice::SelectedOverlay(
                preview["head"].as_str().unwrap().into(),
                selection,
            ))
            .unwrap();
        let session = protocol::SessionId::try_from("overlay-session".to_owned()).unwrap();
        store
            .create_session(
                protocol::MutationKey::try_from("overlay-create".to_owned()).unwrap(),
                history::Session {
                    id: session.clone(),
                    workspace_id: prepared.preparation.workspace.id.clone(),
                    baseline: prepared.preparation.baseline.clone().unwrap(),
                    agent: "fixture".into(),
                    adapter_version: "1".into(),
                    upstream_id: None,
                    capabilities: json!({}),
                    status: "closed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        let mut recorder =
            crate::recorder::WorkspaceRecorder::new(store.clone(), session.clone()).unwrap();
        fs::write(
            prepared
                .preparation
                .workspace
                .worktree_path
                .join("tracked.txt"),
            b"agent delta\n",
        )
        .unwrap();
        recorder.reconcile(crate::recorder::Boundary::Idle).unwrap();
        drop(prepared);
        drop(manager);
        let candidate = crate::review::prepare(
            store.clone(),
            protocol::CandidatePrepare {
                session_id: session,
                writers_stopped: true,
            },
        )
        .unwrap();
        let changes = store.candidate_changes(candidate.id).unwrap();
        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].new_path.as_deref(), Some("tracked.txt"));
        assert_eq!(
            store
                .content_bytes(changes[0].before.as_ref().unwrap().blob.clone())
                .unwrap(),
            b"imported baseline\n"
        );
        assert_eq!(
            fs::read(repo.root.join("tracked.txt")).unwrap(),
            b"imported baseline\n"
        );
    }
    #[test]
    fn imported_ignore_rules_do_not_turn_imported_files_into_agent_changes() {
        let repo = Repository::new();
        repo.write(".gitignore", b"\n");
        repo.git(&["add", ".gitignore"]);
        repo.git(&["commit", "-m", "ignore baseline"]);
        repo.write(".gitignore", b"selected.txt\n");
        repo.git(&["add", ".gitignore"]);
        repo.write(".gitignore", b"\n");
        repo.write("selected.txt", b"imported\n");
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let preview = preview(&repo.root).unwrap();
        let selection = OverlaySelection {
            fingerprint: preview["fingerprint"].as_str().unwrap().into(),
            files: vec![
                protocol::OverlayFile {
                    path: ".gitignore".into(),
                    source: "index".into(),
                },
                protocol::OverlayFile {
                    path: "selected.txt".into(),
                    source: "disk".into(),
                },
            ],
        };
        let manager = worktrees::WorktreeManager::open(
            &repo.root,
            store.clone(),
            worktrees::Recovery::RefuseStale,
        )
        .unwrap();
        let prepared = manager
            .prepare(worktrees::BaselineChoice::SelectedOverlay(
                preview["head"].as_str().unwrap().into(),
                selection,
            ))
            .unwrap();
        let baseline = store
            .get_snapshot(prepared.preparation.baseline.clone().unwrap())
            .unwrap();
        assert!(baseline.manifest.files.contains_key("selected.txt"));
        assert_eq!(
            baseline.manifest.files.len(),
            worktrees::capture_all(&prepared.preparation.workspace.worktree_path)
                .unwrap()
                .len()
        );
    }
}
