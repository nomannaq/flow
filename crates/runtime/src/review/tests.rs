use super::*;
use crate::test_repository::Repository;
use history::{Attribution, Observation, OperationStatus, Recorder, Session, Turn};
use protocol::SessionId;

pub(crate) struct Fixture {
    pub(crate) repo: Repository,
    pub(crate) store: HistoryStore,
    pub(crate) workspace: history::Workspace,
    pub(crate) session: SessionId,
}
impl Fixture {
    pub(crate) fn new(changed: bool) -> Self {
        let repo = Repository::new();
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let manager =
            WorktreeManager::open(&repo.root, store.clone(), Recovery::RefuseStale).unwrap();
        let prepared = manager
            .prepare(worktrees::BaselineChoice::CleanCommit(
                manager.repository().head.clone(),
            ))
            .unwrap();
        let workspace = prepared.preparation.workspace.clone();
        let session = SessionId::try_from("review-session".to_owned()).unwrap();
        store
            .create_session(
                key("session"),
                Session {
                    id: session.clone(),
                    workspace_id: workspace.id.clone(),
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
        store
            .create_turn(
                key("turn"),
                Turn {
                    id: "turn".into(),
                    session_id: session.clone(),
                    prompt_provenance: "fixture".into(),
                    upstream_id: None,
                    status: "completed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        if changed {
            let mut journal = Recorder::new(store.clone(), session.clone()).unwrap();
            std::fs::write(
                workspace.worktree_path.join("tracked.txt"),
                "candidate result\n",
            )
            .unwrap();
            journal
                .observe(
                    SnapshotInput {
                        workspace_id: workspace.id.clone(),
                        files: worktrees::capture_all(&workspace.worktree_path).unwrap(),
                        coverage: Coverage {
                            complete: true,
                            excluded: vec![],
                        },
                        started_at_ms: 2,
                        completed_at_ms: 2,
                        provenance: "fixture".into(),
                    },
                    Observation {
                        turn_id: "turn".into(),
                        status: OperationStatus::Completed,
                        attribution: Attribution::CheckpointInterval,
                        tool_call_id: None,
                        evidence: "fixture".into(),
                    },
                )
                .unwrap();
        }
        drop(prepared);
        drop(manager);
        Self {
            repo,
            store,
            workspace,
            session,
        }
    }
    pub(crate) fn prepare(&self) -> CandidateReview {
        prepare(
            self.store.clone(),
            CandidatePrepare {
                session_id: self.session.clone(),
                writers_stopped: true,
            },
        )
        .unwrap()
    }
    pub(crate) fn decide(&self, id: &ContentId, accept: bool) -> Result<CandidateReview> {
        decide(
            self.store.clone(),
            CandidateDecision {
                candidate_id: id.as_str().to_owned(),
                accept,
                writers_stopped: true,
            },
        )
    }
    #[cfg(unix)]
    fn check(&self, id: &ContentId, name: &str, script: &str) -> Value {
        check(
            self.store.clone(),
            CandidateCheck {
                candidate_id: id.as_str().to_owned(),
                check_id: key(name),
                command: vec!["/bin/sh".into(), "-c".into(), script.into()],
                timeout_seconds: 1,
                writers_stopped: true,
            },
        )
        .unwrap()
    }
}
fn key(s: &str) -> MutationKey {
    MutationKey::try_from(s.to_owned()).unwrap()
}

#[test]
fn candidate_is_idempotent_immutable_and_preserves_main_and_source() {
    let f = Fixture::new(true);
    let review = f.prepare();
    assert_eq!(review.id, f.prepare().id);
    let changes = f.store.candidate_changes(review.id.clone()).unwrap();
    assert_eq!(changes.len(), 1);
    assert_eq!(
        f.store
            .content_bytes(changes[0].after.as_ref().unwrap().blob.clone())
            .unwrap(),
        b"candidate result\n"
    );
    assert_eq!(f.decide(&review.id, true).unwrap().decision, "accepted");
    assert_eq!(f.decide(&review.id, false).unwrap().decision, "rejected");
    assert!(f.decide(&review.id, true).is_err());
    assert_eq!(
        std::fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
    assert_eq!(
        std::fs::read(f.workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"candidate result\n"
    );
    assert!(
        f.store
            .delete_closed_session(f.session.clone(), f.workspace.id.clone())
            .is_err()
    );
}

#[test]
fn approval_rejects_unverified_writers_live_edits_and_new_turns() {
    let f = Fixture::new(true);
    assert!(
        prepare(
            f.store.clone(),
            CandidatePrepare {
                session_id: f.session.clone(),
                writers_stopped: false
            }
        )
        .is_err()
    );
    let review = f.prepare();
    std::fs::write(f.workspace.worktree_path.join("tracked.txt"), b"unrecorded").unwrap();
    assert!(f.decide(&review.id, true).is_err());
    std::fs::write(
        f.workspace.worktree_path.join("tracked.txt"),
        b"candidate result\n",
    )
    .unwrap();
    f.store
        .create_turn(
            key("later"),
            Turn {
                id: "later".into(),
                session_id: f.session.clone(),
                prompt_provenance: "fixture".into(),
                upstream_id: None,
                status: "completed".into(),
                created_at_ms: 3,
            },
        )
        .unwrap();
    assert!(f.decide(&review.id, true).is_err());
    assert_ne!(f.prepare().id, review.id);
    assert_eq!(
        f.store.candidate_review(review.id).unwrap().decision,
        "pending"
    );
}

#[cfg(unix)]
#[test]
fn explicit_check_mutations_create_new_candidate_and_require_new_review() {
    let f = Fixture::new(true);
    let old = f.prepare();
    f.decide(&old.id, true).unwrap();
    let result = f.check(
        &old.id,
        "mutating-check",
        "printf 'validated result\\n' > tracked.txt",
    );
    assert_eq!(result["status"], "passed");
    assert_eq!(result["changedBytes"], true);
    let next: ContentId = serde_json::from_value(result["resultCandidateId"].clone()).unwrap();
    assert_ne!(old.id, next);
    assert!(f.decide(&old.id, true).is_err());
    assert_eq!(
        f.store.candidate_review(next.clone()).unwrap().decision,
        "pending"
    );
    assert_eq!(f.decide(&next, true).unwrap().decision, "accepted");
    assert_eq!(
        std::fs::read(f.workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"candidate result\n"
    );
    assert_eq!(
        std::fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
}

#[cfg(unix)]
#[test]
fn failed_and_interrupted_checks_block_acceptance_and_retries_do_not_execute_again() {
    let f = Fixture::new(true);
    let review = f.prepare();
    assert_eq!(f.check(&review.id, "failure", "exit 2")["status"], "failed");
    assert!(f.decide(&review.id, true).is_err());
    let marker = f.repo.data.join("explicit-check-marker");
    let script = format!("printf x >> '{}'", marker.display());
    let first = f.check(&review.id, "once", &script);
    let second = f.check(&review.id, "once", &script);
    assert_eq!(first, second);
    assert_eq!(std::fs::read(marker).unwrap(), b"x");
    let timed = f.check(&review.id, "timeout", "sleep 30 & wait");
    assert_eq!(timed["status"], "failed");
    assert!(f.decide(&review.id, true).is_err());
}

#[test]
fn empty_delta_is_explicit_and_incomplete_or_unsupported_sources_are_rejected() {
    let f = Fixture::new(false);
    let empty = f.prepare();
    assert!(
        f.store
            .candidate_changes(empty.id.clone())
            .unwrap()
            .is_empty()
    );
    assert_eq!(f.decide(&empty.id, true).unwrap().decision, "accepted");
    f.store
        .agent_event(
            f.session.clone(),
            None,
            "closed".into(),
            "workspace.captureIncomplete".into(),
            json!({}),
        )
        .unwrap();
    assert!(
        prepare(
            f.store.clone(),
            CandidatePrepare {
                session_id: f.session.clone(),
                writers_stopped: true
            }
        )
        .is_err()
    );
    assert!(f.decide(&empty.id, true).is_err());
}

#[test]
fn persisted_candidates_survive_restart_and_unfinished_checks_are_not_replayed() {
    let f = Fixture::new(true);
    let review = f.prepare();
    f.store
        .begin_candidate_check(
            "unknown-outcome".into(),
            review.id.clone(),
            vec!["never replay".into()],
        )
        .unwrap();
    assert!(
        f.store
            .claim_candidate_check("unknown-outcome".into())
            .unwrap()
    );
    let Fixture { repo, store, .. } = f;
    drop(store);
    let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
    let reopened = store.candidate_review(review.id.clone()).unwrap();
    assert_eq!(reopened.candidate, review.candidate);
    assert_eq!(reopened.checks[0]["status"], "interrupted");
    assert!(store.decide_candidate(review.id.clone(), true).is_err());
    assert!(!store.candidate_changes(review.id).unwrap().is_empty());
}

#[cfg(unix)]
#[test]
fn unsupported_results_and_unexpected_source_mutation_never_receive_approval() {
    let f = Fixture::new(true);
    let review = f.prepare();
    for (name, complete) in [("unsupported", true), ("incomplete", false)] {
        let mut files = worktrees::capture_all(&f.workspace.worktree_path).unwrap();
        if complete {
            files.get_mut("tracked.txt").unwrap().kind = history::FileKind::Symlink;
        }
        let receipt = f
            .store
            .publish_snapshot(
                key(name),
                SnapshotInput {
                    workspace_id: f.workspace.id.clone(),
                    files,
                    coverage: Coverage {
                        complete,
                        excluded: vec![],
                    },
                    started_at_ms: 4,
                    completed_at_ms: 4,
                    provenance: "unsupported validation fixture".into(),
                },
            )
            .unwrap();
        let mut candidate = review.candidate.clone();
        candidate.result = serde_json::from_value(receipt.outcome.unwrap()["id"].clone()).unwrap();
        assert!(f.store.save_candidate(candidate).is_err());
    }
    let script = format!(
        "printf newer > '{}'",
        f.workspace.worktree_path.join("tracked.txt").display()
    );
    let outcome = f.check(&review.id, "source-changed", &script);
    assert_eq!(outcome["status"], "failed");
    assert!(f.decide(&review.id, true).is_err());
    assert_eq!(
        std::fs::read(f.workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"newer"
    );
}

#[cfg(unix)]
#[test]
fn launch_failure_is_not_a_stale_writer_and_rejection_cannot_be_undone_by_a_check() {
    let f = Fixture::new(true);
    let review = f.prepare();
    let result = check(
        f.store.clone(),
        CandidateCheck {
            candidate_id: review.id.as_str().into(),
            check_id: key("missing-executable"),
            command: vec!["/nonexistent/flow-check".into()],
            timeout_seconds: 1,
            writers_stopped: true,
        },
    )
    .unwrap();
    assert_eq!(result["status"], "failed");
    assert_eq!(f.prepare().id, review.id);
    f.decide(&review.id, false).unwrap();
    assert!(
        check(
            f.store.clone(),
            CandidateCheck {
                candidate_id: review.id.as_str().into(),
                check_id: key("rejected-check"),
                command: vec!["/bin/true".into()],
                timeout_seconds: 1,
                writers_stopped: true
            }
        )
        .is_err()
    );
    assert_eq!(
        f.store.candidate_review(review.id).unwrap().decision,
        "rejected"
    );
}
