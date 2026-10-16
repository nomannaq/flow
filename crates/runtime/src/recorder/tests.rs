use super::*;
use crate::test_repository as repository;
use crate::worktrees::{BaselineChoice, PreparedWorktree, Recovery, WorktreeManager};
use history::{FileKind, Session};
use std::{
    fs,
    os::unix::fs::{PermissionsExt, symlink},
};

struct Fixture {
    repo: repository::Repository,
    store: HistoryStore,
    prepared: PreparedWorktree,
    recorder: WorkspaceRecorder,
    session: SessionId,
}
impl Fixture {
    fn new() -> Self {
        let repo = repository::Repository::new();
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let manager =
            WorktreeManager::open(&repo.root, store.clone(), Recovery::RefuseStale).unwrap();
        let prepared = manager
            .prepare(BaselineChoice::CleanCommit(
                manager.repository().head.clone(),
            ))
            .unwrap();
        let session = SessionId::try_from("recorder-session".to_owned()).unwrap();
        store
            .create_session(
                key("session"),
                Session {
                    id: session.clone(),
                    workspace_id: prepared.preparation.workspace.id.clone(),
                    baseline: prepared.preparation.baseline.clone().unwrap(),
                    agent: "fixture".into(),
                    adapter_version: "1".into(),
                    upstream_id: None,
                    capabilities: json!({}),
                    status: "ready".into(),
                    created_at_ms: now(),
                },
            )
            .unwrap();
        let recorder = WorkspaceRecorder::new(store.clone(), session.clone()).unwrap();
        Self {
            repo,
            store,
            prepared,
            recorder,
            session,
        }
    }
    fn path(&self, file: &str) -> PathBuf {
        self.prepared.preparation.workspace.worktree_path.join(file)
    }
    fn turn(&mut self) {
        self.store
            .create_turn(
                key("turn"),
                Turn {
                    id: "turn".into(),
                    session_id: self.session.clone(),
                    prompt_provenance: "fixture".into(),
                    upstream_id: None,
                    status: "running".into(),
                    created_at_ms: now(),
                },
            )
            .unwrap();
        self.recorder
            .reconcile(Boundary::TurnStart("turn".into()))
            .unwrap();
    }
    fn tool(&mut self, id: &str, terminal: bool, failed: bool) -> Option<String> {
        self.recorder
            .reconcile(Boundary::Tool {
                id: id.into(),
                terminal,
                failed,
            })
            .unwrap()
    }
}
fn key(s: &str) -> MutationKey {
    MutationKey::try_from(s.to_owned()).unwrap()
}

#[test]
fn three_historical_pairs_survive_reopen_and_transient_reverts_are_unobservable() {
    let mut f = Fixture::new();
    f.turn();
    let mut pairs = Vec::new();
    for i in 1..=3 {
        f.tool(&i.to_string(), false, false);
        fs::write(f.path("tracked.txt"), format!("edit-{i}\n")).unwrap();
        let id = f.tool(&i.to_string(), true, false).unwrap();
        pairs.push(id);
    }
    fs::write(f.path("tracked.txt"), b"transient").unwrap();
    fs::write(f.path("tracked.txt"), b"edit-3\n").unwrap();
    assert!(f.recorder.reconcile(Boundary::Idle).unwrap().is_none());
    let Fixture {
        repo,
        store,
        prepared,
        recorder,
        session,
    } = f;
    drop(recorder);
    drop(prepared);
    drop(store);
    let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
    assert_eq!(store.operations(session, 0, 100).unwrap().items.len(), 3);
    for (i, id) in pairs.into_iter().enumerate() {
        let op = store.get_operation(id).unwrap();
        assert_eq!(op.input.attribution, Attribution::CheckpointInterval);
        let change = &op.changes[0];
        let before = store
            .read_content(change.before.as_ref().unwrap().blob.clone(), 0, 1024)
            .unwrap()
            .bytes;
        let after = store
            .read_content(change.after.as_ref().unwrap().blob.clone(), 0, 1024)
            .unwrap()
            .bytes;
        assert_eq!(
            before,
            if i == 0 {
                b"base\n".to_vec()
            } else {
                format!("edit-{i}\n").into_bytes()
            }
        );
        assert_eq!(after, format!("edit-{}\n", i + 1).into_bytes());
    }
    assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"base\n");
}

#[test]
fn overlap_missing_events_external_writes_and_failed_effects_remain_honest() {
    let mut f = Fixture::new();
    f.turn();
    f.tool("a", false, false);
    f.tool("b", false, false);
    fs::write(f.path("tracked.txt"), b"partial failure").unwrap();
    assert!(f.tool("a", true, true).is_none());
    let id = f.tool("b", true, false).unwrap();
    let op = f.store.get_operation(id).unwrap();
    assert_eq!(op.input.status, OperationStatus::Failed);
    assert_eq!(op.input.attribution, Attribution::Ambiguous);
    assert!(op.input.tool_call_id.is_none());
    assert!(op.input.evidence.contains("\"overlap\":true"));
    fs::write(f.path("tracked.txt"), b"unreported").unwrap();
    let id = f
        .recorder
        .reconcile(Boundary::TurnEnd(OperationStatus::Completed))
        .unwrap()
        .unwrap();
    assert_ne!(
        f.store.get_operation(id).unwrap().input.attribution,
        Attribution::Exact
    );
    fs::write(f.path("tracked.txt"), b"idle external or background").unwrap();
    let id = f.recorder.reconcile(Boundary::Idle).unwrap().unwrap();
    let op = f.store.get_operation(id).unwrap();
    assert_eq!(op.input.attribution, Attribution::External);
    assert!(op.input.evidence.contains("\"safeToApply\":false"));
}

#[test]
fn binary_ignored_files_rename_mode_delete_and_capture_overflow() {
    let mut f = Fixture::new();
    f.turn();
    fs::write(f.path(".gitignore"), b"*.bin\n").unwrap();
    fs::write(f.path("new.bin"), [0, 255, 13, 10]).unwrap();
    symlink("/outside/never-read", f.path("link")).unwrap();
    f.recorder.reconcile(Boundary::Idle).unwrap().unwrap();
    fs::rename(f.path("new.bin"), f.path("renamed.bin")).unwrap();
    fs::set_permissions(f.path("renamed.bin"), fs::Permissions::from_mode(0o755)).unwrap();
    fs::remove_file(f.path("tracked.txt")).unwrap();
    let id = f.recorder.reconcile(Boundary::Idle).unwrap().unwrap();
    let op = f.store.get_operation(id).unwrap();
    assert!(
        op.changes
            .iter()
            .any(|c| c.old_path.as_deref() == Some("new.bin")
                && c.new_path.as_deref() == Some("renamed.bin")
                && c.after.as_ref().unwrap().mode == 0o755)
    );
    assert!(
        op.changes
            .iter()
            .any(|c| c.old_path.as_deref() == Some("tracked.txt") && c.after.is_none())
    );
    let checkpoint = f.recorder.journal.checkpoint().clone();
    assert_eq!(checkpoint.manifest.files["link"].kind, FileKind::Symlink);
    fs::File::create(f.path("large"))
        .unwrap()
        .set_len(history::MAX_BLOB_BYTES as u64 + 1)
        .unwrap();
    assert!(f.recorder.reconcile(Boundary::Idle).is_err());
    assert_eq!(f.recorder.journal.checkpoint(), &checkpoint);
    fs::remove_file(f.path("large")).unwrap();
    fs::write(f.path("after-gap"), b"recovered observation").unwrap();
    let id = f
        .recorder
        .reconcile(Boundary::Closed(OperationStatus::Interrupted))
        .unwrap()
        .unwrap();
    assert!(
        f.store
            .get_operation(id)
            .unwrap()
            .input
            .evidence
            .contains("\"priorCaptureGap\":true")
    );
}

#[test]
fn b26_duplicate_tool_events_and_idle_polls_do_not_invent_operations() {
    let mut f = Fixture::new();
    f.turn();
    for sequence in 0..8 {
        let tool = format!("duplicate-{sequence}");
        assert!(f.tool(&tool, false, false).is_none());
        assert!(f.tool(&tool, false, false).is_none());
        let bytes = format!("observed effect {sequence}\n");
        fs::write(f.path("tracked.txt"), &bytes).unwrap();
        let id = f.tool(&tool, true, false).unwrap();
        assert!(f.tool(&tool, true, false).is_none());
        assert!(f.recorder.reconcile(Boundary::Idle).unwrap().is_none());
        let operations = f.store.operations(f.session.clone(), 0, 100).unwrap().items;
        assert_eq!(operations.len(), sequence + 1);
        let operation = f.store.get_operation(id).unwrap();
        let snapshot = f.store.get_snapshot(operation.input.after).unwrap();
        assert_eq!(
            f.store
                .content_bytes(snapshot.manifest.files["tracked.txt"].blob.clone())
                .unwrap(),
            bytes.as_bytes()
        );
    }
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
}
