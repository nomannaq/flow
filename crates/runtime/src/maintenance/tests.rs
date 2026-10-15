use super::*;
use crate::{
    test_repository::Repository,
    worktrees::{BaselineChoice, PreparedWorktree},
};
use history::Session;
use std::fs;

struct Fixture {
    repo: Repository,
    store: HistoryStore,
    session: SessionId,
    prepared: Option<PreparedWorktree>,
}
impl Fixture {
    fn new() -> Self {
        let repo = Repository::new();
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let manager =
            WorktreeManager::open(&repo.root, store.clone(), Recovery::RefuseStale).unwrap();
        let prepared = manager
            .prepare(BaselineChoice::CleanCommit(
                manager.repository().head.clone(),
            ))
            .unwrap();
        let session = SessionId::try_from("maintenance-session".to_owned()).unwrap();
        store
            .create_session(
                MutationKey::try_from("session".to_owned()).unwrap(),
                Session {
                    id: session.clone(),
                    workspace_id: prepared.preparation.workspace.id.clone(),
                    baseline: prepared.preparation.baseline.clone().unwrap(),
                    agent: "fixture".into(),
                    adapter_version: "1".into(),
                    upstream_id: None,
                    capabilities: json!({}),
                    status: "running".into(),
                    created_at_ms: now().unwrap(),
                },
            )
            .unwrap();
        preparing_process(&prepared.preparation.workspace).unwrap();
        Self {
            repo,
            store,
            session,
            prepared: Some(prepared),
        }
    }
    fn workspace(&self) -> history::Workspace {
        self.store
            .get_workspace(
                self.store
                    .get_session(self.session.clone())
                    .unwrap()
                    .workspace_id,
            )
            .unwrap()
    }
}

#[test]
fn explicit_recovery_preserves_newer_bytes_and_can_delete_only_closed_history() {
    let mut f = Fixture::new();
    let workspace = f.workspace();
    fs::write(
        workspace.worktree_path.join("tracked.txt"),
        b"newer after crash\n",
    )
    .unwrap();
    let before = fs::read(f.repo.root.join("tracked.txt")).unwrap();
    assert!(matches!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            true
        ),
        Err(Error::Busy)
    ));
    drop(f.prepared.take());
    assert!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            false
        )
        .is_err()
    );
    assert!(
        delete_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            true
        )
        .is_err()
    );
    let report = inspect(&f.store).unwrap();
    assert_eq!(report["sessions"][0]["worktree"], "differs_from_checkpoint");
    assert_eq!(report["sessions"][0]["process"]["state"], "launch_pending");
    let op = recover_session(
        f.store.clone(),
        f.session.clone(),
        Recovery::RefuseStale,
        true,
    )
    .unwrap()
    .unwrap();
    assert_eq!(
        f.store.get_operation(op).unwrap().input.status,
        OperationStatus::Interrupted
    );
    assert_eq!(
        f.store.get_session(f.session.clone()).unwrap().status,
        "closed"
    );
    assert_eq!(
        fs::read(workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"newer after crash\n"
    );
    assert_eq!(fs::read(f.repo.root.join("tracked.txt")).unwrap(), before);
    delete_session(
        f.store.clone(),
        f.session.clone(),
        Recovery::RefuseStale,
        true,
    )
    .unwrap();
    assert!(workspace.worktree_path.join("tracked.txt").is_file());
    assert_eq!(f.store.snapshot().unwrap().state["sessions"], 0);
}

#[test]
fn missing_or_redirected_worktree_keeps_history_readable_and_blocks_recovery() {
    let mut f = Fixture::new();
    let workspace = f.workspace();
    let baseline = f.store.get_session(f.session.clone()).unwrap().baseline;
    drop(f.prepared.take());
    let moved = workspace.worktree_path.with_file_name("retained-tree");
    fs::rename(&workspace.worktree_path, &moved).unwrap();
    assert_eq!(
        inspect(&f.store).unwrap()["sessions"][0]["worktree"],
        "missing"
    );
    assert!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            true
        )
        .is_err()
    );
    assert!(f.store.get_snapshot(baseline.clone()).is_ok());
    std::os::unix::fs::symlink(&moved, &workspace.worktree_path).unwrap();
    assert_eq!(
        inspect(&f.store).unwrap()["sessions"][0]["worktree"],
        "unreadable_or_redirected"
    );
    assert!(
        delete_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            true
        )
        .is_err()
    );
    assert!(f.store.get_snapshot(baseline).is_ok());
}

#[test]
fn stale_owner_needs_exact_acknowledgement_and_never_replays_agent_work() {
    let mut f = Fixture::new();
    let workspace = f.workspace();
    drop(f.prepared.take());
    let owner = worktrees::Owner {
        pid: 999999,
        instance: "previous-instance".into(),
        started_at_ms: 1,
    };
    let lock = f.repo.root.join(".git/flow-worktrees.lock");
    write(&lock, &owner).unwrap();
    assert!(matches!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::RefuseStale,
            true
        ),
        Err(Error::StaleOwner(_))
    ));
    assert!(matches!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::Acknowledge("wrong".into()),
            true
        ),
        Err(Error::StaleOwner(_))
    ));
    assert!(
        recover_session(
            f.store.clone(),
            f.session.clone(),
            Recovery::Acknowledge(owner.instance),
            true
        )
        .unwrap()
        .is_none()
    );
    assert!(
        f.store
            .operations(f.session, 0, 100)
            .unwrap()
            .items
            .is_empty()
    );
    assert_eq!(
        fs::read(workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"base\n"
    );
}

#[test]
fn continuation_requires_closed_checkpoint_and_unknown_shutdown_preserves_ownership() {
    let mut f = Fixture::new();
    let workspace = f.workspace();
    f.prepared.take();
    let manager =
        WorktreeManager::open(&f.repo.root, f.store.clone(), Recovery::RefuseStale).unwrap();
    assert!(continue_workspace(&f.store, &manager, f.session.clone()).is_err());
    f.store
        .agent_event(
            f.session.clone(),
            None,
            "closed".into(),
            "session.closed".into(),
            json!({}),
        )
        .unwrap();
    let (prepared, baseline) = continue_workspace(&f.store, &manager, f.session.clone()).unwrap();
    assert_eq!(baseline, f.store.checkpoint(f.session.clone()).unwrap().id);
    drop(prepared);
    fs::write(
        workspace.worktree_path.join("unrecorded.txt"),
        b"preserve this",
    )
    .unwrap();
    assert!(continue_workspace(&f.store, &manager, f.session.clone()).is_err());
    assert_eq!(
        fs::read(workspace.worktree_path.join("unrecorded.txt")).unwrap(),
        b"preserve this"
    );
    let prepared = manager
        .acquire(&workspace.id, Recovery::RefuseStale)
        .unwrap();
    prepared.quarantine();
    drop(prepared);
    drop(manager);
    assert!(matches!(
        WorktreeManager::open(&f.repo.root, f.store.clone(), Recovery::RefuseStale),
        Err(Error::StaleOwner(_))
    ));
}
