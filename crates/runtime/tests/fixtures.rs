//! B05 acceptance suite for the initial native Unix target. No network or model.
#![cfg(unix)]
#[path = "../../../tests/support/agent.rs"]
mod agent;
#[path = "../../../tests/support/repository.rs"]
mod repository;

use agent::{Control, FakeAgent, Scenario};
use agent_client_protocol::schema::{ProtocolVersion, v1::*};
use agent_client_protocol::{Agent, Client, ConnectionTo, Error};
use repository::{ExpectedFile, Oracle, Repository, Sequence, regular, verify};
use std::{
    fs,
    os::unix::fs::{PermissionsExt, symlink},
    sync::Arc,
    time::Duration,
};

async fn initialize(cx: &ConnectionTo<Agent>, root: &std::path::Path) -> SessionId {
    let response = cx
        .send_request(InitializeRequest::new(ProtocolVersion::V1))
        .block_task()
        .await
        .unwrap();
    assert_eq!(response.protocol_version, ProtocolVersion::V1);
    cx.send_request(NewSessionRequest::new(root.to_owned()))
        .block_task()
        .await
        .unwrap()
        .session_id
}
fn prompt(session: SessionId) -> PromptRequest {
    PromptRequest::new(
        session,
        vec![ContentBlock::Text(TextContent::new("run fixture"))],
    )
}
fn baseline() -> Oracle {
    Oracle::from([("tracked.txt".into(), regular(b"base\n"))])
}

#[tokio::test]
async fn sdk_edits_preserve_history_and_repeatable_traces() {
    let mut runs = Vec::new();
    for _ in 0..2 {
        let repo = Repository::new();
        let store = repo.store();
        let control = Arc::new(Control::default());
        let fake = FakeAgent {
            root: repo.root.clone(),
            scenario: Scenario::Write,
            control: control.clone(),
        };
        let mut sequence = Sequence::default();
        let first = sequence.capture(&repo, &store);
        let session_id = protocol::SessionId::try_from("history-session".to_owned()).unwrap();
        store
            .create_session(
                repository::key("session"),
                history::Session {
                    id: session_id.clone(),
                    workspace_id: repository::workspace_id(),
                    baseline: first.clone(),
                    agent: "sdk-fixture".into(),
                    adapter_version: "1".into(),
                    upstream_id: None,
                    capabilities: serde_json::json!({}),
                    status: "running".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        store
            .create_turn(
                repository::key("turn"),
                history::Turn {
                    id: "turn-1".into(),
                    session_id: session_id.clone(),
                    prompt_provenance: "fixture".into(),
                    upstream_id: None,
                    status: "running".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        let mut ids = vec![first.clone()];
        let mut manifests = vec![baseline()];
        tokio::time::timeout(Duration::from_secs(10), Client.builder()
            .on_receive_request(async |request: RequestPermissionRequest, responder, _cx| {
                assert_eq!(request.options.len(), 1);
                assert_eq!(request.options[0].kind, PermissionOptionKind::AllowOnce);
                responder.respond(RequestPermissionResponse::new(RequestPermissionOutcome::Selected(
                    SelectedPermissionOutcome::new(request.options[0].option_id.clone()))))
            }, agent_client_protocol::on_receive_request!())
            .on_receive_notification(async |_request: SessionNotification, _cx| Ok(()), agent_client_protocol::on_receive_notification!())
            .connect_with(fake, async |cx| {
                let session = initialize(&cx, &repo.root).await;
                for i in 1..=2 {
                    let result = cx.send_request(prompt(session.clone())).block_task().await?;
                    assert_eq!(result.stop_reason, StopReason::EndTurn);
                    let expected = Oracle::from([("tracked.txt".into(), regular(format!("edit-{i}\n").as_bytes()))]);
                    let id = sequence.capture(&repo, &store);
                    verify(&store, id.clone(), &expected).unwrap();
                    store.record_operation(repository::key(&format!("record-{i}")), history::OperationInput {
                        id: format!("operation-{i}"), session_id: session_id.clone(), turn_id: "turn-1".into(),
                        before: ids.last().unwrap().clone(), after: id.clone(), tool_call_id: Some(format!("tool-{i}")),
                        parent_id: None, status: history::OperationStatus::Completed,
                        attribution: history::Attribution::CheckpointInterval,
                        evidence: "explicit fixture checkpoints; SDK messages alone do not prove attribution".into(),
                        started_at_ms: i, completed_at_ms: i + 1,
                    }).unwrap();
                    ids.push(id);
                    manifests.push(expected);
                }
                Ok(())
            })).await.unwrap().unwrap();
        drop(store);
        let reopened = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
        for (id, expected) in ids.iter().zip(&manifests) {
            verify(&reopened, id.clone(), expected).unwrap();
        }
        for i in 1..=2 {
            let operation = reopened.get_operation(format!("operation-{i}")).unwrap();
            assert_eq!(operation.changes.len(), 1);
            assert_eq!(
                operation.changes[0].old_path.as_deref(),
                Some("tracked.txt")
            );
            assert_eq!(
                operation.changes[0].new_path.as_deref(),
                Some("tracked.txt")
            );
            assert_eq!(operation.input.before, ids[i - 1]);
            assert_eq!(operation.input.after, ids[i]);
        }
        assert!(verify(&reopened, first, manifests.last().unwrap()).is_err());
        runs.push((ids, control.trace.lock().unwrap().clone()));
    }
    assert_eq!(runs[0], runs[1]);
    assert_eq!(
        runs[0].1,
        [
            "operation.1.started",
            "permission.allowed",
            "operation.1.completed",
            "operation.2.started",
            "permission.allowed",
            "operation.2.completed"
        ]
    );
}

#[tokio::test]
async fn denied_permissions_and_failed_partial_writes_are_distinct() {
    for allow in [false, true] {
        let repo = Repository::new();
        let store = repo.store();
        let fake = FakeAgent {
            root: repo.root.clone(),
            scenario: Scenario::PartialFailure,
            control: Arc::default(),
        };
        tokio::time::timeout(
            Duration::from_secs(10),
            Client
                .builder()
                .on_receive_request(
                    async move |_request: RequestPermissionRequest, responder, _cx| {
                        let outcome = if allow {
                            RequestPermissionOutcome::Selected(SelectedPermissionOutcome::new(
                                "once",
                            ))
                        } else {
                            RequestPermissionOutcome::Cancelled
                        };
                        responder.respond(RequestPermissionResponse::new(outcome))
                    },
                    agent_client_protocol::on_receive_request!(),
                )
                .connect_with(fake, async |cx| {
                    let session = initialize(&cx, &repo.root).await;
                    let result = cx.send_request(prompt(session)).block_task().await;
                    if allow {
                        assert!(result.is_err());
                    } else {
                        assert_eq!(result?.stop_reason, StopReason::Cancelled);
                    }
                    Ok(())
                }),
        )
        .await
        .unwrap()
        .unwrap();
        let expected = if allow {
            Oracle::from([("tracked.txt".into(), regular(b"partial"))])
        } else {
            baseline()
        };
        verify(
            &store,
            Sequence::default().capture(&repo, &store),
            &expected,
        )
        .unwrap();
    }
}

#[tokio::test]
async fn delayed_agent_can_cancel_or_resist_until_explicit_release() {
    for resist in [false, true] {
        let repo = Repository::new();
        let control = Arc::new(Control::default());
        let fake = FakeAgent {
            root: repo.root.clone(),
            scenario: if resist {
                Scenario::IgnoreCancellation
            } else {
                Scenario::Delayed
            },
            control: control.clone(),
        };
        tokio::time::timeout(
            Duration::from_secs(10),
            Client
                .builder()
                .on_receive_request(
                    async |_request: RequestPermissionRequest, responder, _cx| {
                        responder.respond(RequestPermissionResponse::new(
                            RequestPermissionOutcome::Selected(SelectedPermissionOutcome::new(
                                "once",
                            )),
                        ))
                    },
                    agent_client_protocol::on_receive_request!(),
                )
                .on_receive_notification(
                    async |_request: SessionNotification, _cx| Ok(()),
                    agent_client_protocol::on_receive_notification!(),
                )
                .connect_with(fake, async |cx| {
                    let session = initialize(&cx, &repo.root).await;
                    let response = cx.send_request(prompt(session.clone())).block_task();
                    control.ready.notified().await;
                    assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"base\n");
                    cx.send_notification(CancelNotification::new(session))?;
                    if resist {
                        control.cancelled.notified().await;
                        // Cancellation reached the agent, yet no work completes until released.
                        assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"base\n");
                        control.release.notify_one();
                    }
                    assert_eq!(
                        response.await?.stop_reason,
                        if resist {
                            StopReason::EndTurn
                        } else {
                            StopReason::Cancelled
                        }
                    );
                    Ok(())
                }),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(
            fs::read(repo.root.join("tracked.txt")).unwrap(),
            if resist {
                b"edit-1\n".as_slice()
            } else {
                b"base\n"
            }
        );
        assert!(
            control
                .trace
                .lock()
                .unwrap()
                .iter()
                .any(|s| s == "cancel.received")
        );
    }
}

#[test]
fn repository_edge_cases_and_independent_oracle() {
    let repo = Repository::new();
    let store = repo.store();
    let mut sequence = Sequence::default();
    let mut expected = baseline();
    // Exact index and disk states deliberately differ.
    repo.write("tracked.txt", b"staged\n");
    repo.git(&["add", "--", "tracked.txt"]);
    repo.write("tracked.txt", b"unstaged\n");
    assert_eq!(repo.git(&["show", ":tracked.txt"]), b"staged\n");
    expected.insert("tracked.txt".into(), regular(b"unstaged\n"));
    let index_before = fs::read(repo.root.join(".git/index")).unwrap();
    // A shell writes without any tool-reported patch or SDK event.
    let shell = std::process::Command::new("/bin/sh")
        .args(["-c", "printf 'shell\\n' > shell.txt"])
        .current_dir(&repo.root)
        .env_clear()
        .status()
        .unwrap();
    assert!(shell.success());
    fs::set_permissions(
        repo.root.join("shell.txt"),
        fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    expected.insert("shell.txt".into(), regular(b"shell\n"));
    let binary = vec![0, 255, 13, 10, 0, 128];
    repo.write("binary.dat", &binary);
    expected.insert("binary.dat".into(), regular(&binary));
    let large = vec![42; history::MAX_CHUNK_BYTES + 19];
    repo.write("large.dat", &large);
    expected.insert("large.dat".into(), regular(&large));
    repo.write("odd dir/日本語\n-leading.txt", b"unusual\n");
    expected.insert("odd dir/日本語\n-leading.txt".into(), regular(b"unusual\n"));
    repo.write("run.sh", b"#!/bin/sh\n");
    fs::set_permissions(repo.root.join("run.sh"), fs::Permissions::from_mode(0o755)).unwrap();
    expected.insert(
        "run.sh".into(),
        ExpectedFile(b"#!/bin/sh\n".to_vec(), 0o755, history::FileKind::Regular),
    );
    fs::write(repo.temp.path().join("sentinel"), b"outside repository").unwrap();
    symlink("../sentinel", repo.root.join("outside-link")).unwrap();
    // macOS applies umask to symlinks; chmod -h changes the link, never its target.
    #[cfg(target_os = "macos")]
    assert!(
        std::process::Command::new("/bin/chmod")
            .args(["-h", "777"])
            .arg(repo.root.join("outside-link"))
            .status()
            .unwrap()
            .success()
    );
    expected.insert(
        "outside-link".into(),
        ExpectedFile(b"../sentinel".to_vec(), 0o777, history::FileKind::Symlink),
    );
    let original = sequence.capture(&repo, &store);
    verify(&store, original.clone(), &expected).unwrap();
    for corruption in 0..4 {
        let mut wrong = expected.clone();
        match corruption {
            0 => {
                wrong.remove("binary.dat");
            }
            1 => wrong.get_mut("tracked.txt").unwrap().0 = b"wrong".to_vec(),
            2 => wrong.get_mut("run.sh").unwrap().1 = 0o644,
            _ => wrong.get_mut("outside-link").unwrap().2 = history::FileKind::Regular,
        }
        assert!(verify(&store, original.clone(), &wrong).is_err());
    }
    fs::rename(repo.root.join("tracked.txt"), repo.root.join("renamed.txt")).unwrap();
    let file = expected.remove("tracked.txt").unwrap();
    expected.insert("renamed.txt".into(), file);
    verify(&store, sequence.capture(&repo, &store), &expected).unwrap();
    fs::remove_file(repo.root.join("renamed.txt")).unwrap();
    expected.remove("renamed.txt");
    verify(&store, sequence.capture(&repo, &store), &expected).unwrap();
    repo.write("renamed.txt", b"recreated\n");
    expected.insert("renamed.txt".into(), regular(b"recreated\n"));
    verify(&store, sequence.capture(&repo, &store), &expected).unwrap();
    assert_eq!(
        index_before,
        fs::read(repo.root.join(".git/index")).unwrap()
    );
    assert_eq!(
        fs::read(repo.temp.path().join("sentinel")).unwrap(),
        b"outside repository"
    );
}

#[test]
fn conflicting_branches_and_cleanup_stay_inside_owned_directory() {
    let sentinel = tempfile::NamedTempFile::new().unwrap();
    fs::write(sentinel.path(), b"not owned by fixture").unwrap();
    let owned;
    {
        let repo = Repository::new();
        owned = repo.temp.path().to_owned();
        repo.git(&["checkout", "-b", "agent"]);
        repo.write("tracked.txt", b"agent\n");
        repo.git(&["commit", "-am", "agent edit"]);
        repo.git(&["checkout", "main"]);
        repo.write("tracked.txt", b"developer\n");
        repo.git(&["commit", "-am", "developer edit"]);
        let merge = repo.git_output(&["merge", "--no-edit", "agent"]);
        assert_eq!(merge.status.code(), Some(1));
        assert_eq!(repo.git(&["show", ":1:tracked.txt"]), b"base\n");
        assert_eq!(repo.git(&["show", ":2:tracked.txt"]), b"developer\n");
        assert_eq!(repo.git(&["show", ":3:tracked.txt"]), b"agent\n");
        repo.git(&["merge", "--abort"]);
        assert_eq!(
            fs::read(repo.root.join("tracked.txt")).unwrap(),
            b"developer\n"
        );
    }
    assert!(!owned.exists());
    assert_eq!(fs::read(sentinel.path()).unwrap(), b"not owned by fixture");
}

#[tokio::test]
async fn raw_transport_rejects_malformed_output_and_abrupt_eof() {
    use futures_util::{SinkExt, sink, stream};
    for line in [
        Some("not json"),
        Some("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"),
        None,
    ] {
        // Intentionally bypass typed SDK messages only at this malformed-stream seam.
        let incoming = stream::iter(line.map(|s| Ok(s.to_owned())));
        let outgoing = sink::drain()
            .sink_map_err(|_: std::convert::Infallible| std::io::Error::other("unreachable"));
        let transport = agent_client_protocol::Lines::new(outgoing, incoming);
        let result = tokio::time::timeout(
            Duration::from_secs(2),
            Client
                .builder()
                .connect_with(transport, async |cx| -> Result<(), Error> {
                    cx.send_request(InitializeRequest::new(ProtocolVersion::V1))
                        .block_task()
                        .await?;
                    Ok(())
                }),
        )
        .await
        .expect("malformed peer must not hang");
        assert!(result.is_err());
    }
}
