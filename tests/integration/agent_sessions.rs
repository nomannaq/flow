//! Real engine -> SDK process -> offline ACP fixture. Never runs a paid model.
#![cfg(unix)]
#[allow(dead_code)]
#[path = "../support/repository.rs"]
mod repository;
use repository::Repository;
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    process::{Child, ChildStdin, ChildStdout, Command},
    time::{sleep, timeout},
};

struct Engine {
    child: Child,
    input: ChildStdin,
    output: Lines<BufReader<ChildStdout>>,
    epoch: Value,
    serial: u64,
    log: tempfile::NamedTempFile,
}
impl Engine {
    async fn new(repo: &Repository) -> Self {
        let log = tempfile::NamedTempFile::new().unwrap();
        let mut child = Command::new(env!("CARGO_BIN_EXE_flow-engine"))
            .args([
                "--project-root",
                repo.root.to_str().unwrap(),
                "--data-root",
                repo.data.to_str().unwrap(),
            ])
            .env("FLOW_PROBE_SECRET", "never inherit this")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(log.reopen().unwrap())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut engine = Self {
            input: child.stdin.take().unwrap(),
            output: BufReader::new(child.stdout.take().unwrap()).lines(),
            child,
            epoch: Value::Null,
            serial: 0,
            log,
        };
        let hello=engine.call("engine.initialize",json!({"protocolVersion":1,"clientName":"B08 test","clientVersion":"1","projectId":"fixture"})).await;
        engine.epoch = hello["epoch"].clone();
        assert_eq!(hello["result"]["capabilities"]["agentSessions"], true);
        engine
    }
    async fn call(&mut self, method: &str, params: Value) -> Value {
        self.serial += 1;
        self.input.write_all(format!("{}\n",json!({"jsonrpc":"2.0","id":self.serial,"method":method,"epoch":self.epoch,"params":params})).as_bytes()).await.unwrap();
        let line = timeout(Duration::from_secs(20), self.output.next_line())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let value: Value = serde_json::from_str(&line).unwrap();
        assert_eq!(value["id"], self.serial);
        value
    }
    async fn ok(&mut self, method: &str, params: Value) -> Value {
        let value = self.call(method, params).await;
        assert!(value.get("error").is_none(), "{method}: {value}");
        value["result"].clone()
    }
    async fn start(&mut self, repo: &Repository, prompt: &str, flags: &[&str]) -> Value {
        let commit = self.ok("sessions.baseline", json!({})).await["commit"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(
            commit,
            String::from_utf8(repo.git(&["rev-parse", "HEAD"]))
                .unwrap()
                .trim()
        );
        let script = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../scripts/spikes/fixture_agent.py")
            .canonicalize()
            .unwrap();
        let mut arguments = vec![script.to_string_lossy().to_string(), "--heartbeat".into()];
        arguments.extend(flags.iter().map(|s| s.to_string()));
        self.ok("sessions.start",json!({"executable":"/usr/bin/python3","arguments":arguments,"commit":commit,"prompt":prompt,"connectOnly":prompt.is_empty(),"adapterProfile":if flags.contains(&"--cursor") {"cursor"} else if flags.contains(&"--claude") {"claude"} else {""},"authMethod":if flags.contains(&"--needs-auth") {Some("fixture")} else {None}})).await["sessionId"].clone()
    }
    async fn wait(&mut self, predicate: impl Fn(&Value) -> bool) -> Value {
        let mut last_status = String::new();
        let result = timeout(Duration::from_secs(15), async {
            loop {
                let view = self.ok("sessions.active", json!({})).await;
                last_status = format!(
                    "status={} kinds={:?}",
                    view["status"],
                    view["activity"].as_array().map(|items| items
                        .iter()
                        .map(|item| item["kind"].clone())
                        .collect::<Vec<_>>())
                );
                if predicate(&view) {
                    return view;
                }
                sleep(Duration::from_millis(30)).await;
            }
        })
        .await;
        result.unwrap_or_else(|_| panic!("agent state deadline: {last_status}"))
    }
    async fn shutdown(mut self) -> String {
        self.ok("engine.shutdown", json!({})).await;
        assert!(
            timeout(Duration::from_secs(10), self.child.wait())
                .await
                .unwrap()
                .unwrap()
                .success()
        );
        assert!(
            !fs::read_to_string(self.log.path())
                .unwrap()
                .contains("private-fixture-token")
        );
        fs::read_to_string(self.log.path()).unwrap()
    }
}
async fn heartbeat_stops(workspace: &Path) {
    // Check the real descendant's filesystem effect without privileged process enumeration.
    sleep(Duration::from_millis(150)).await;
    let before = fs::read(workspace.parent().unwrap().join("fixture-heartbeat")).unwrap();
    sleep(Duration::from_millis(250)).await;
    assert_eq!(
        fs::read(workspace.parent().unwrap().join("fixture-heartbeat")).unwrap(),
        before
    );
}

#[tokio::test]
async fn large_unicode_response_survives_live_projection_and_durable_replay() {
    use protocol::ProtocolStore;
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine
        .start(&repo, "Return a long response", &["--long-response"])
        .await;
    let view = engine.wait(|v| v["status"] == "ready").await;
    let expected = "line 👩🏽‍💻中\n".repeat(2000);
    let live: String = view["activity"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|row| row["kind"] == "message.delta")
        .map(|row| row["text"].as_str().unwrap())
        .collect();
    assert_eq!(live, expected);
    engine.shutdown().await;
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let mut after = store.snapshot().unwrap().high_water;
    after.sequence = protocol::EventSequence(0);
    let mut replayed = String::new();
    loop {
        let page = store
            .replay(&protocol::ReplayParams {
                after: after.clone(),
                limit: 100,
            })
            .unwrap();
        for event in &page.events {
            if event.session_id.as_ref().map(|id| id.as_str()) == session.as_str()
                && event.kind == "message.delta"
            {
                replayed.push_str(event.payload["text"].as_str().unwrap());
            }
            after = event.cursor.clone();
        }
        if !page.has_more {
            break;
        }
    }
    assert_eq!(replayed, expected);
}

#[tokio::test]
async fn conversation_features_use_sdk_metadata_and_retained_session_scoped_journal() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let prompt = "Review 🦀 project\n".repeat(300);
    let session = engine
        .start(
            &repo,
            &prompt,
            &[
                "--conversation-features",
                "--long-response",
                "--delegation-tools",
            ],
        )
        .await;
    let view = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(view["availableCommands"][0]["name"], "review");
    assert_eq!(view["availableCommands"][0]["inputHint"], "Optional scope");
    assert_eq!(view["usage"]["used"], "12345");
    assert_eq!(view["usage"]["size"], "200000");
    assert_eq!(view["plan"]["entries"][0]["content"], "Read the project");
    assert_eq!(view["plan"]["entries"][0]["status"], "completed");
    assert_eq!(view["subagents"][0]["tool"]["name"], "Agent");
    assert_eq!(view["subagents"][0]["tool"]["status"], "completed");
    assert!(
        view["activity"]
            .as_array()
            .unwrap()
            .iter()
            .any(|event| event["tool"]["parentToolCallId"] == view["subagents"][0]["toolCallId"])
    );
    engine
        .ok(
            "sessions.label",
            json!({"sessionId":session,"title":"Reviewed project","favorite":true}),
        )
        .await;
    let found = engine
        .ok(
            "sessions.search",
            json!({"query":"Reviewed project","favoritesOnly":true}),
        )
        .await;
    assert_eq!(found["items"][0]["id"], session);
    let mut after = Value::Null;
    let mut through = Value::Null;
    let mut user = String::new();
    let mut response = String::new();
    loop {
        let page = engine
            .ok(
                "history.conversation",
                json!({"sessionId":session,"after":after,"through":through}),
            )
            .await;
        through = page["through"].clone();
        for row in page["items"].as_array().unwrap() {
            assert_eq!(row["legacy"], false);
            match row["kind"].as_str().unwrap() {
                "turn.started" | "turn.prompt.delta" => {
                    user.push_str(row["text"].as_str().unwrap())
                }
                "message.delta" => response.push_str(row["text"].as_str().unwrap()),
                _ => {}
            }
        }
        after = page["next"].clone();
        if after.is_null() {
            break;
        }
    }
    assert_eq!(user, prompt);
    assert_eq!(response, "line 👩🏽‍💻中\n".repeat(2000));
    engine.shutdown().await;
    let mut restarted = Engine::new(&repo).await;
    assert!(restarted.ok("sessions.active", json!({})).await["sessionId"].is_null());
    let found = restarted
        .ok(
            "sessions.search",
            json!({"query":"Reviewed project","favoritesOnly":true}),
        )
        .await;
    assert_eq!(found["items"][0]["id"], session);
    assert!(
        restarted
            .ok(
                "history.conversation",
                json!({"sessionId":session,"through":through})
            )
            .await["items"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "turn.started")
    );
    assert!(restarted.ok("sessions.active", json!({})).await["sessionId"].is_null());
    restarted.shutdown().await;
}

#[tokio::test]
async fn prompts_permissions_followups_cancel_and_durable_lifecycle() {
    for flags in [vec!["--needs-auth"], vec!["--needs-auth", "--claude"]] {
        let repo = Repository::new();
        let index = fs::read(repo.root.join(".git/index")).unwrap();
        repo.write("tracked.txt", b"developer changes");
        let mut engine = Engine::new(&repo).await;
        let session = engine.start(&repo, "write probe", &flags).await;
        let view = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        let workspace = PathBuf::from(view["workspace"].as_str().unwrap());
        assert_ne!(workspace, repo.root);
        assert_eq!(view["upstreamSessionId"], "fixture");
        assert_eq!(view["agentInfo"]["version"], "1");
        assert!(
            view["activity"]
                .as_array()
                .unwrap()
                .iter()
                .any(|e| e["kind"] == "message.delta")
        );
        assert_eq!(
            view["permissions"][0]["options"].as_array().unwrap().len(),
            1
        );
        let permission = view["permissions"][0]["requestId"].clone();
        for (id, option) in [
            (json!("other"), json!("once")),
            (session.clone(), json!("always")),
        ] {
            assert!(
                engine
                    .call(
                        "permissions.respond",
                        json!({"sessionId":id,"requestId":permission,"optionId":option})
                    )
                    .await
                    .get("error")
                    .is_some()
            );
        }
        engine
            .ok(
                "permissions.respond",
                json!({"sessionId":session,"requestId":permission,"optionId":"once"}),
            )
            .await;
        engine.wait(|v| v["status"] == "ready").await;
        assert_eq!(
            fs::read(workspace.join("probe.txt")).unwrap(),
            b"flow-probe-1\n"
        );
        let live = engine.ok("sessions.active", json!({})).await;
        let tool = live["activity"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["toolCallId"] == "1")
            .unwrap();
        assert_eq!(tool["tool"]["title"], "Fixture edit");
        assert_eq!(tool["tool"]["input"], r#"{"path":"probe.txt"}"#);
        assert_eq!(tool["tool"]["status"], "completed");
        assert_eq!(tool["tool"]["output"], "Fixture file updated");
        assert_eq!(tool["tool"]["diffs"][0]["after"], "flow-probe-1\n");
        let mut after = Value::Null;
        let mut replayed = false;
        loop {
            let page = engine
                .ok(
                    "history.conversation",
                    json!({"sessionId":session,"after":after}),
                )
                .await;
            replayed |= page["items"].as_array().unwrap().iter().any(|row| {
                row["toolCallId"] == "1"
                    && row["tool"]["status"] == "completed"
                    && row["tool"]["output"] == "Fixture file updated"
            });
            after = page["next"].clone();
            if after.is_null() {
                break;
            }
        }
        assert!(
            replayed,
            "Tool results must survive the durable conversation query"
        );
        assert!(
            engine
                .call(
                    "permissions.respond",
                    json!({"sessionId":session,"requestId":permission,"optionId":"once"})
                )
                .await
                .get("error")
                .is_some()
        );
        engine
            .ok(
                "sessions.prompt",
                json!({"sessionId":session,"prompt":"second turn"}),
            )
            .await;
        let view = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":view["permissions"][0]["requestId"],"optionId":null})).await;
        engine.wait(|v| v["status"] == "ready").await;
        assert_eq!(
            fs::read(workspace.join("probe.txt")).unwrap(),
            b"flow-probe-1\n"
        );
        engine
            .ok(
                "sessions.prompt",
                json!({"sessionId":session,"prompt":"command waits 30 seconds"}),
            )
            .await;
        sleep(Duration::from_millis(100)).await;
        engine
            .ok("sessions.cancel", json!({"sessionId":session}))
            .await;
        let view = engine.wait(|v| v["status"] == "ready").await;
        assert!(
            view["activity"]
                .as_array()
                .unwrap()
                .iter()
                .any(|e| e["text"].as_str().unwrap().contains("Turn cancelled"))
        );
        engine
            .ok("sessions.close", json!({"sessionId":session}))
            .await;
        let closed = engine
            .wait(|v| v["status"] == "closed" || v["status"] == "failed")
            .await;
        heartbeat_stops(&workspace).await;
        let log = engine.shutdown().await;
        if closed["status"] == "failed" {
            assert!(
                log.contains("adapter group shutdown check failed")
                    && log.contains("PermissionDenied"),
                "{log}"
            );
            assert!(
                !fs::read(repo.root.join(".git/flow-worktrees.lock"))
                    .unwrap()
                    .is_empty()
            );
        }
        let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
        let record = store
            .get_session(serde_json::from_value(session).unwrap())
            .unwrap();
        assert_eq!(record.status, closed["status"].as_str().unwrap());
        assert_eq!(record.upstream_id.as_deref(), Some("fixture"));
        assert_eq!(record.adapter_version, "1");
        assert_eq!(fs::read(repo.root.join(".git/index")).unwrap(), index);
        assert_eq!(
            fs::read(repo.root.join("tracked.txt")).unwrap(),
            b"developer changes"
        );
        assert!(!repo.root.join("probe.txt").exists());
    }
}

#[tokio::test]
async fn adapter_failures_and_cancel_timeout_preserve_partial_results() {
    for flag in [
        "--partial-crash",
        "--never-cancel",
        "--prompt-error",
        "--wrong-version",
    ] {
        let repo = Repository::new();
        let mut engine = Engine::new(&repo).await;
        let session = engine
            .start(
                &repo,
                if flag == "--never-cancel" {
                    "command waits 30 seconds"
                } else {
                    "write probe"
                },
                &[flag],
            )
            .await;
        if flag == "--partial-crash" {
            let view = engine
                .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
                .await;
            engine.ok("permissions.respond",json!({"sessionId":session,"requestId":view["permissions"][0]["requestId"],"optionId":"once"})).await;
        }
        if flag == "--never-cancel" {
            engine.wait(|v| v["status"] == "running").await;
            sleep(Duration::from_millis(100)).await;
            engine
                .ok("sessions.cancel", json!({"sessionId":session}))
                .await;
        }
        let view = engine.wait(|v| v["status"] == "failed").await;
        assert!(!view.to_string().contains("private-fixture-token"));
        let path = PathBuf::from(view["workspace"].as_str().unwrap());
        if flag == "--partial-crash" {
            assert_eq!(fs::read(path.join("probe.txt")).unwrap(), b"partial");
        }
        if flag == "--never-cancel" {
            heartbeat_stops(&path).await;
        }
        engine.shutdown().await;
        let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
        if flag == "--partial-crash" {
            let operations = store
                .operations(serde_json::from_value(session.clone()).unwrap(), 0, 100)
                .unwrap();
            let partial = operations
                .items
                .iter()
                .filter(|op| op.status == history::OperationStatus::Failed)
                .flat_map(|op| store.get_operation(op.id.clone()).unwrap().changes)
                .find(|change| change.new_path.as_deref() == Some("probe.txt"))
                .expect("failed write must be captured after SDK teardown");
            assert_eq!(
                store
                    .read_content(partial.after.unwrap().blob, 0, 100)
                    .unwrap()
                    .bytes,
                b"partial"
            );
        }
        assert_eq!(
            store
                .get_session(serde_json::from_value(session).unwrap())
                .unwrap()
                .status,
            "failed"
        );
    }
}

#[tokio::test]
async fn shutdown_denies_pending_approval_and_stops_adapter_group() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "write probe", &[]).await;
    let view = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    let path = PathBuf::from(view["workspace"].as_str().unwrap());
    let log = engine.shutdown().await;
    heartbeat_stops(&path).await;
    assert!(!path.join("probe.txt").exists());
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    assert_eq!(
        store
            .get_session(serde_json::from_value(session).unwrap())
            .unwrap()
            .status,
        if log.contains("adapter group shutdown check failed") && log.contains("PermissionDenied") {
            "failed"
        } else {
            "interrupted"
        }
    );
}

#[tokio::test]
async fn real_sdk_records_three_edits_and_idle_changes_without_main_project_writes() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "write probe", &[]).await;
    let mut workspace = PathBuf::new();
    for n in 1..=3 {
        if n > 1 {
            engine
                .ok(
                    "sessions.prompt",
                    json!({"sessionId":session,"prompt":"write again"}),
                )
                .await;
        }
        let waiting = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        workspace = PathBuf::from(waiting["workspace"].as_str().unwrap());
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
        engine.wait(|v| v["status"] == "ready").await;
    }
    fs::write(workspace.join("outside-turn"), b"external writer").unwrap();
    engine
        .wait(|v| {
            v["activity"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|e| e["kind"] == "workspace.recorded")
                .count()
                >= 4
        })
        .await;
    // Idle reconciliation runs every two seconds; close always reconciles too.
    sleep(Duration::from_secs(3)).await;
    engine.shutdown().await;
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let ops = store
        .operations(serde_json::from_value(session).unwrap(), 0, 100)
        .unwrap();
    let probe: Vec<_> = ops
        .items
        .iter()
        .flat_map(|op| store.get_operation(op.id.clone()).unwrap().changes)
        .filter(|c| c.new_path.as_deref() == Some("probe.txt"))
        .collect();
    assert_eq!(probe.len(), 3);
    for (i, change) in probe.iter().enumerate() {
        assert_eq!(
            store
                .read_content(change.after.as_ref().unwrap().blob.clone(), 0, 100)
                .unwrap()
                .bytes,
            format!("flow-probe-{}\n", i + 1).as_bytes()
        );
        if i > 0 {
            assert_eq!(
                change.before.as_ref().unwrap().blob,
                probe[i - 1].after.as_ref().unwrap().blob
            );
        }
    }
    assert!(
        ops.items
            .iter()
            .all(|op| op.attribution != history::Attribution::Exact)
    );
    assert!(ops.items.iter().any(|op| {
        op.attribution == history::Attribution::External
            && store
                .get_operation(op.id.clone())
                .unwrap()
                .changes
                .iter()
                .any(|c| c.new_path.as_deref() == Some("outside-turn"))
    }));
    assert!(!repo.root.join("probe.txt").exists());
}

#[tokio::test]
async fn missing_tool_events_and_capture_overflow_are_not_silent_success() {
    for flag in ["--unreported-write", "--capture-overflow"] {
        let repo = Repository::new();
        let mut engine = Engine::new(&repo).await;
        let session = engine.start(&repo, "fixture write", &[flag]).await;
        let view = engine
            .wait(|v| {
                v["status"]
                    == if flag == "--capture-overflow" {
                        "failed"
                    } else {
                        "ready"
                    }
            })
            .await;
        if flag == "--capture-overflow" {
            assert!(
                view["activity"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|e| e["kind"] == "workspace.captureIncomplete")
            );
        }
        engine.shutdown().await;
        let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
        let operations = store
            .operations(serde_json::from_value(session).unwrap(), 0, 100)
            .unwrap();
        if flag == "--unreported-write" {
            assert!(operations.items.iter().any(|op| {
                op.tool_call_id.is_none()
                    && op.attribution != history::Attribution::Exact
                    && store
                        .get_operation(op.id.clone())
                        .unwrap()
                        .changes
                        .iter()
                        .any(|c| c.new_path.as_deref() == Some("unreported.txt"))
            }));
        } else {
            assert!(operations.items.iter().all(|op| {
                store
                    .get_operation(op.id.clone())
                    .unwrap()
                    .changes
                    .iter()
                    .all(|c| c.new_path.as_deref() != Some("too-large.bin"))
            }));
        }
    }
}

#[tokio::test]
async fn background_writes_after_completed_tool_are_reconciled_while_idle() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "overlapping writers", &[]).await;
    let view = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    engine.ok("permissions.respond",json!({"sessionId":session,"requestId":view["permissions"][0]["requestId"],"optionId":"once"})).await;
    engine.wait(|v| v["status"] == "ready").await;
    sleep(Duration::from_secs(4)).await;
    engine.shutdown().await;
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let ops = store
        .operations(serde_json::from_value(session).unwrap(), 0, 100)
        .unwrap();
    assert!(ops.items.iter().any(|op| {
        op.attribution == history::Attribution::External
            && store
                .get_operation(op.id.clone())
                .unwrap()
                .changes
                .iter()
                .any(|c| c.new_path.as_deref() == Some("probe.txt"))
    }));
    assert!(
        ops.items
            .iter()
            .all(|op| op.evidence.contains("\"writerQuiescenceProven\":false"))
    );
}

#[test]
fn offline_maintenance_cli_reconciles_and_deletes_selected_history() {
    use protocol::{MutationKey, SessionId};
    use runtime::worktrees::{BaselineChoice, Recovery, WorktreeManager};
    let repo = Repository::new();
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let manager = WorktreeManager::open(&repo.root, store.clone(), Recovery::RefuseStale).unwrap();
    let prepared = manager
        .prepare(BaselineChoice::CleanCommit(
            manager.repository().head.clone(),
        ))
        .unwrap();
    let workspace = prepared.preparation.workspace.clone();
    let session = SessionId::try_from("cli-session".to_owned()).unwrap();
    store
        .create_session(
            MutationKey::try_from("cli-session".to_owned()).unwrap(),
            history::Session {
                id: session,
                workspace_id: workspace.id,
                baseline: prepared.preparation.baseline.clone().unwrap(),
                agent: "fixture".into(),
                adapter_version: "1".into(),
                upstream_id: None,
                capabilities: json!({}),
                status: "interrupted".into(),
                created_at_ms: 1,
            },
        )
        .unwrap();
    fs::write(
        workspace.worktree_path.join("tracked.txt"),
        b"retained current bytes",
    )
    .unwrap();
    drop(prepared);
    drop(manager);
    drop(store);
    let command = |action: &str, extra: &[&str]| {
        std::process::Command::new(env!("CARGO_BIN_EXE_flow-engine"))
            .args([
                "--history",
                action,
                "--project-root",
                repo.root.to_str().unwrap(),
                "--data-root",
                repo.data.to_str().unwrap(),
            ])
            .args(extra)
            .output()
            .unwrap()
    };
    let inspected = command("inspect", &[]);
    assert!(
        inspected.status.success(),
        "{}",
        String::from_utf8_lossy(&inspected.stderr)
    );
    let report: Value = serde_json::from_slice(&inspected.stdout).unwrap();
    assert_eq!(report["sessions"][0]["worktree"], "differs_from_checkpoint");
    assert!(
        !command("recover", &["--session", "cli-session"])
            .status
            .success()
    );
    let recovered = command(
        "recover",
        &["--session", "cli-session", "--writers-stopped"],
    );
    assert!(
        recovered.status.success(),
        "{}",
        String::from_utf8_lossy(&recovered.stderr)
    );
    let report: Value = serde_json::from_slice(&recovered.stdout).unwrap();
    assert_eq!(report["sessions"][0]["status"], "closed");
    assert_eq!(report["sessions"][0]["worktree"], "matches_checkpoint");
    assert!(
        command(
            "delete-session",
            &["--session", "cli-session", "--writers-stopped"]
        )
        .status
        .success()
    );
    assert_eq!(
        fs::read(workspace.worktree_path.join("tracked.txt")).unwrap(),
        b"retained current bytes"
    );
    assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"base\n");
}

#[tokio::test]
async fn retained_history_archive_and_explicit_new_conversation_survive_restart() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "write probe", &[]).await;
    let waiting = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    let old_permission = waiting["permissions"][0]["requestId"].clone();
    // Active sessions cannot be archived or displaced by a second writer.
    assert!(
        engine
            .call("sessions.archive", json!({"sessionId":session}))
            .await
            .get("error")
            .is_some()
    );
    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../scripts/spikes/fixture_agent.py")
        .canonicalize()
        .unwrap();
    let continuation = json!({"executable":"/usr/bin/python3","arguments":[script],"sourceSessionId":session,"writersStopped":true,"prompt":"new conversation"});
    assert!(
        engine
            .call("sessions.start", continuation.clone())
            .await
            .get("error")
            .is_some()
    );
    engine
        .ok(
            "permissions.respond",
            json!({"sessionId":session,"requestId":old_permission,"optionId":"once"}),
        )
        .await;
    engine.wait(|v| v["status"] == "ready").await;
    engine = close_fixture_for_history(engine, &repo, &session).await;
    let page = engine
        .ok("history.operations", json!({"sessionId":session}))
        .await;
    let mut expected = None;
    for operation in page["items"].as_array().unwrap() {
        let id = &operation["id"];
        let files = engine
            .ok(
                "history.files",
                json!({"sessionId":session,"operationId":id}),
            )
            .await;
        if let Some(file) = files["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|f| f["newPath"] == "probe.txt")
        {
            let query = json!({"sessionId":session,"operationId":id,"fileIndex":file["index"]});
            let diff = engine.ok("history.diff", query.clone()).await;
            assert_eq!(diff["before"], "");
            assert_eq!(diff["after"], "flow-probe-1\n");
            expected = Some((query, diff));
        }
    }
    let (query, diff) = expected.expect("recorded file diff");
    engine
        .ok("sessions.archive", json!({"sessionId":session}))
        .await;
    engine
        .ok("sessions.archive", json!({"sessionId":session}))
        .await; // lost-reply retry
    engine.shutdown().await;
    let mut engine = Engine::new(&repo).await;
    assert!(engine.ok("sessions.active", json!({})).await["sessionId"].is_null());
    assert_eq!(engine.ok("history.diff", query.clone()).await, diff);
    let list = engine.ok("sessions.list", json!({})).await;
    assert_eq!(list["items"][0]["status"], "archived");
    assert!(
        engine
            .call(
                "permissions.respond",
                json!({"sessionId":session,"requestId":old_permission,"optionId":"once"})
            )
            .await
            .get("error")
            .is_some()
    );
    let mut unsafe_continue = continuation.clone();
    unsafe_continue["writersStopped"] = json!(false);
    assert!(
        engine
            .call("sessions.start", unsafe_continue)
            .await
            .get("error")
            .is_some()
    );
    let new_session = engine.ok("sessions.start", continuation).await["sessionId"].clone();
    assert_ne!(new_session, session);
    let waiting = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    assert_eq!(
        fs::read(Path::new(waiting["workspace"].as_str().unwrap()).join("probe.txt")).unwrap(),
        b"flow-probe-1\n"
    );
    // Old session approvals cannot authorize the new conversation, even if the
    // adapter reuses the same upstream session/tool IDs.
    assert!(
        engine
            .call(
                "permissions.respond",
                json!({"sessionId":session,"requestId":old_permission,"optionId":"once"})
            )
            .await
            .get("error")
            .is_some()
    );
    assert_eq!(engine.ok("history.diff", query).await, diff);
    engine
        .ok("sessions.close", json!({"sessionId":new_session}))
        .await;
    engine
        .wait(|v| {
            v["status"] == "interrupted" || v["status"] == "closed" || v["status"] == "failed"
        })
        .await;
    engine.shutdown().await;
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let prior = store
        .get_session(protocol::SessionId::try_from(session.as_str().unwrap().to_owned()).unwrap())
        .unwrap();
    let next = store
        .get_session(
            protocol::SessionId::try_from(new_session.as_str().unwrap().to_owned()).unwrap(),
        )
        .unwrap();
    assert_eq!(prior.workspace_id, next.workspace_id);
    assert_eq!(store.checkpoint(prior.id).unwrap().id, next.baseline);
    assert!(!repo.root.join("probe.txt").exists());
}

/// The managed macOS sandbox forbids signal-0 inspection. Verify that production
/// refuses admission, then explicitly recover only our stopped offline fixture.
async fn close_fixture_for_history(
    mut engine: Engine,
    repo: &Repository,
    session: &Value,
) -> Engine {
    engine
        .ok("sessions.close", json!({"sessionId":session}))
        .await;
    let closed = engine
        .wait(|v| v["status"] == "closed" || v["status"] == "failed")
        .await;
    engine
        .ok("sessions.close", json!({"sessionId":session}))
        .await;
    if closed["status"] == "closed" {
        return engine;
    }
    heartbeat_stops(Path::new(closed["workspace"].as_str().unwrap())).await;
    let log = engine.shutdown().await;
    assert!(
        log.contains("adapter group shutdown check failed") && log.contains("PermissionDenied"),
        "{log}"
    );
    let owner: Value =
        serde_json::from_slice(&fs::read(repo.root.join(".git/flow-worktrees.lock")).unwrap())
            .unwrap();
    let recovered = std::process::Command::new(env!("CARGO_BIN_EXE_flow-engine"))
        .args([
            "--history",
            "recover",
            "--project-root",
            repo.root.to_str().unwrap(),
            "--data-root",
            repo.data.to_str().unwrap(),
            "--session",
            session.as_str().unwrap(),
            "--writers-stopped",
            "--owner",
            owner["instance"].as_str().unwrap(),
        ])
        .output()
        .unwrap();
    assert!(
        recovered.status.success(),
        "{}",
        String::from_utf8_lossy(&recovered.stderr)
    );
    Engine::new(repo).await
}

#[tokio::test]
async fn authentication_retry_keeps_workspace_and_only_accepts_advertised_methods() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let commit = engine.ok("sessions.baseline", json!({})).await["commit"].clone();
    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../scripts/spikes/fixture_agent.py")
        .canonicalize()
        .unwrap();
    let session = engine.ok("sessions.start", json!({"executable":"/usr/bin/python3", "arguments":[script,"--needs-auth","--reject-auth-once","--heartbeat"], "commit":commit,"prompt":"write probe"})).await["sessionId"].clone();
    let view = engine
        .wait(|v| v["status"] == "authenticationRequired")
        .await;
    let workspace = PathBuf::from(view["workspace"].as_str().unwrap());
    assert_eq!(view["authMethods"], json!(["fixture"]));
    assert!(
        view["compatibility"]
            .as_str()
            .unwrap()
            .starts_with("Unverified")
    );
    for (id, method) in [
        (session.clone(), "invented"),
        (json!("wrong-session"), "fixture"),
    ] {
        assert!(
            engine
                .call(
                    "sessions.authenticate",
                    json!({"sessionId":id,"methodId":method})
                )
                .await
                .get("error")
                .is_some()
        );
    }
    engine
        .ok(
            "sessions.authenticate",
            json!({"sessionId":session,"methodId":"fixture"}),
        )
        .await;
    engine
        .wait(|v| v["status"] == "authenticationRequired")
        .await;
    engine
        .ok(
            "sessions.authenticate",
            json!({"sessionId":session,"methodId":"fixture"}),
        )
        .await;
    let view = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    assert_eq!(view["sessionId"], session);
    assert_eq!(
        view["workspace"].as_str().unwrap(),
        workspace.to_str().unwrap()
    );
    assert!(
        view["activity"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "message.delta" && e["text"] == "fixture output")
    );
    engine.ok("permissions.respond",json!({"sessionId":session,"requestId":view["permissions"][0]["requestId"],"optionId":"once"})).await;
    let view = engine.wait(|v| v["status"] == "ready").await;
    let operations = engine
        .ok("history.operations", json!({"sessionId":session}))
        .await;
    assert!(view["recordedOperations"].as_u64().unwrap() > 0);
    assert_eq!(
        view["recordedOperations"].as_u64().unwrap(),
        operations["items"].as_array().unwrap().len() as u64
    );
    assert!(
        engine
            .call(
                "sessions.authenticate",
                json!({"sessionId":session,"methodId":"fixture"})
            )
            .await
            .get("error")
            .is_some()
    );
    assert!(!repo.root.join("probe.txt").exists());
    engine.shutdown().await;
    heartbeat_stops(&workspace).await;
}

#[tokio::test]
async fn automatic_rmcp_tools_work_for_both_profiles_and_degrade_explicitly() {
    for profile in [
        vec!["--context-tools"],
        vec!["--claude", "--context-tools"],
        vec!["--reject-mcp"],
    ] {
        let repo = Repository::new();
        repo.write("lib.rs", b"pub fn probe_symbol() {}\n");
        repo.git(&["add", "lib.rs"]);
        repo.git(&["commit", "-m", "context fixture"]);
        let mut engine = Engine::new(&repo).await;
        let initialized = engine.ok("context.initialize", json!({})).await;
        assert_eq!(initialized["files"], 1);
        assert!(repo.root.join(".flow/map.md").is_file());
        assert!(repo.git(&["status", "--porcelain"]).is_empty());
        let session = engine.start(&repo, "inspect probe_symbol", &profile).await;
        let pending = engine
            .wait(|v| v["status"] == "failed" || !v["permissions"].as_array().unwrap().is_empty())
            .await;
        assert_ne!(pending["status"], "failed", "{pending}");
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":pending["permissions"][0]["requestId"],"optionId":null})).await;
        let view = engine
            .wait(|v| matches!(v["status"].as_str(), Some("ready" | "failed")))
            .await;
        assert_eq!(view["status"], "ready", "{view}");
        if profile.contains(&"--context-tools") {
            let prompt: Value = serde_json::from_slice(
                &fs::read(Path::new(view["workspace"].as_str().unwrap()).join("prompt-proof.json"))
                    .unwrap(),
            )
            .unwrap();
            let text = prompt["text"].as_str().unwrap();
            assert!(text.contains("Flow relevant code and learnings"), "{text}");
            assert!(text.contains("probe_symbol"));
            assert!(text.len() < 5000);
            let proof: Value = serde_json::from_slice(
                &fs::read(
                    Path::new(view["workspace"].as_str().unwrap()).join("context-proof.json"),
                )
                .unwrap(),
            )
            .unwrap();
            assert_eq!(proof["wrongCredentialRejected"], true);
            assert_eq!(proof["wrongSessionRejected"], true);
            assert_eq!(proof["traversalRejected"], true);
        } else {
            assert!(
                view["activity"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|v| v["kind"] == "context.degraded")
            );
        }
        let preview = engine
            .ok(
                "context.preview",
                json!({"sessionId":session,"query":"inspect probe_symbol"}),
            )
            .await;
        assert_eq!(preview["freshness"], "verifiedAtRead");
        assert_eq!(preview["results"][0]["name"], "probe_symbol");
        if profile.contains(&"--context-tools") {
            let frozen = format!(
                "inspect probe_symbol\n\nFlow reviewed worktree context (untrusted data):\n{preview}"
            );
            for text in [
                "inspect probe_symbol without automatic hints".to_owned(),
                frozen,
            ] {
                engine
                    .ok(
                        "sessions.prompt",
                        json!({"sessionId":session,"prompt":text,"includeContext":false}),
                    )
                    .await;
                let pending = engine
                    .wait(|view| {
                        view["status"] == "failed"
                            || !view["permissions"].as_array().unwrap().is_empty()
                    })
                    .await;
                assert_ne!(pending["status"], "failed", "{pending}");
                engine.ok("permissions.respond",json!({"sessionId":session,"requestId":pending["permissions"][0]["requestId"],"optionId":null})).await;
                let ready = engine
                    .wait(|view| matches!(view["status"].as_str(), Some("ready" | "failed")))
                    .await;
                assert_eq!(ready["status"], "ready", "{ready}");
                let actual: Value = serde_json::from_slice(
                    &fs::read(
                        Path::new(ready["workspace"].as_str().unwrap()).join("prompt-proof.json"),
                    )
                    .unwrap(),
                )
                .unwrap();
                assert_eq!(
                    actual["text"], text,
                    "Opt-out and reviewed context must never receive hidden extra hints"
                );
            }
        }
        engine
            .ok("sessions.close", json!({"sessionId":session}))
            .await;
        engine.wait(|v| v["status"] == "closed").await;
        engine.shutdown().await;
    }
}

#[tokio::test]
async fn connection_discovers_and_applies_configuration_without_inference() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let id = engine.start(&repo, "", &["--configuration"]).await;
    let view = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(view["configOptions"].as_array().unwrap().len(), 5);
    assert!(
        !view["activity"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["kind"] == "turn.started")
    );
    engine
        .ok(
            "sessions.configure",
            json!({"sessionId":id,"configId":"model","value":"fixture-fast"}),
        )
        .await;
    let view = engine
        .wait(|v| v["status"] == "ready" && v["configOptions"][0]["currentValue"] == "fixture-fast")
        .await;
    assert_eq!(
        view["configOptions"][1]["options"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert!(
        engine
            .call(
                "sessions.configure",
                json!({"sessionId":id,"configId":"effort","value":"high"})
            )
            .await
            .get("error")
            .is_some()
    );
    assert!(
        engine
            .call(
                "sessions.configure",
                json!({"sessionId":"wrong-session","configId":"speed","value":"fast"})
            )
            .await
            .get("error")
            .is_some()
    );
    for (key, value) in [
        ("speed", json!("fast")),
        ("mode", json!("auto")),
        ("verbose", json!(true)),
    ] {
        engine
            .ok(
                "sessions.configure",
                json!({"sessionId":id,"configId":key,"value":value}),
            )
            .await;
        engine.wait(|v| v["status"] == "ready").await;
    }
    let view = engine.ok("sessions.active", json!({})).await;
    assert_eq!(view["configOptions"][2]["currentValue"], "fast");
    assert_eq!(view["configOptions"][3]["currentValue"], "auto");
    assert_eq!(view["configOptions"][4]["currentValue"], true);
    let workspace = PathBuf::from(view["workspace"].as_str().unwrap());
    assert!(!workspace.join("configuration-proof.json").exists());
    engine
        .ok(
            "sessions.prompt",
            json!({"sessionId":id,"prompt":"waits 30 seconds"}),
        )
        .await;
    engine
        .wait(|v| v["status"] == "running" && workspace.join("configuration-proof.json").is_file())
        .await;
    assert!(
        engine
            .call(
                "sessions.configure",
                json!({"sessionId":id,"configId":"mode","value":"ask"})
            )
            .await
            .get("error")
            .is_some()
    );
    assert_eq!(
        serde_json::from_slice::<Value>(
            &fs::read(workspace.join("configuration-proof.json")).unwrap()
        )
        .unwrap()[2]["currentValue"],
        "fast"
    );
    engine.ok("sessions.cancel", json!({"sessionId":id})).await;
    engine.wait(|v| v["status"] == "ready").await;
    engine.ok("sessions.close", json!({"sessionId":id})).await;
    engine.wait(|v| v["status"] == "closed").await;
    engine.shutdown().await;
}

#[tokio::test]
async fn rejected_configuration_preserves_connection_and_confirmed_values() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let id = engine
        .start(&repo, "", &["--configuration", "--reject-config"])
        .await;
    engine.wait(|v| v["status"] == "ready").await;
    engine
        .ok(
            "sessions.configure",
            json!({"sessionId":id,"configId":"speed","value":"fast"}),
        )
        .await;
    let view = engine
        .wait(|v| {
            v["status"] == "ready"
                && v["configurationError"]
                    .as_str()
                    .is_some_and(|s| !s.is_empty())
        })
        .await;
    assert_eq!(view["configOptions"][2]["currentValue"], "standard");
    assert!(!view.to_string().contains("private-fixture-token"));
    engine.ok("sessions.close", json!({"sessionId":id})).await;
    engine.wait(|v| v["status"] == "closed").await;
    engine.shutdown().await;
}

#[tokio::test]
async fn legacy_modes_remain_available_without_a_model_catalog() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let id = engine
        .start(&repo, "", &["--legacy-modes", "--claude"])
        .await;
    engine.wait(|v| v["status"] == "ready").await;
    engine
        .ok(
            "sessions.configure",
            json!({"sessionId":id,"configId":"mode","value":"plan"}),
        )
        .await;
    let view = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(view["configOptions"][0]["currentValue"], "plan");
    engine.ok("sessions.close", json!({"sessionId":id})).await;
    engine.wait(|v| v["status"] == "closed").await;
    engine.shutdown().await;
}

#[tokio::test]
async fn lost_configuration_acknowledgement_ends_connection_without_starting_a_turn() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let id = engine
        .start(&repo, "", &["--configuration", "--drop-config"])
        .await;
    engine.wait(|v| v["status"] == "ready").await;
    engine
        .ok(
            "sessions.configure",
            json!({"sessionId":id,"configId":"mode","value":"auto"}),
        )
        .await;
    let view = engine.wait(|v| v["status"] == "failed").await;
    assert!(
        !view["activity"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v["kind"] == "turn.started")
    );
    assert!(
        engine
            .call(
                "sessions.prompt",
                json!({"sessionId":id,"prompt":"must not run"})
            )
            .await
            .get("error")
            .is_some()
    );
    engine.shutdown().await;
}

#[tokio::test]
async fn images_queue_and_rewind_use_durable_scoped_state_and_preserve_original_work() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "", &["--images"]).await;
    engine.wait(|v| v["status"] == "ready").await;
    // A small real PNG, frozen inline rather than a path or URL.
    let photo = json!({"name":"diagram.png","mimeType":"image/png","data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1kAAAAASUVORK5CYII="});
    engine
        .ok(
            "sessions.prompt",
            json!({"sessionId":session,"prompt":"first edit","images":[photo]}),
        )
        .await;
    let waiting = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    let original = PathBuf::from(waiting["workspace"].as_str().unwrap());
    for number in 1..=2 {
        let action = json!({"sessionId":session,"action":"enqueue","id":format!("queued-{number}"),"command":{"sessionId":session,"prompt":format!("queued edit {number}")}});
        engine.ok("sessions.queue", action.clone()).await;
        engine.ok("sessions.queue", action).await; // retry acknowledgement, never enqueue twice
    }
    engine
        .ok(
            "sessions.queue",
            json!({"sessionId":session,"action":"pause"}),
        )
        .await;
    engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
    let ready = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(ready["queue"].as_array().unwrap().len(), 2);
    assert_eq!(ready["queuePaused"], true);
    let image_event = ready["activity"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["kind"] == "turn.started")
        .unwrap();
    let turn = image_event["turnId"].clone();
    let image_id = image_event["images"][0]["id"].clone();
    let stored = engine
        .ok(
            "history.image",
            json!({"sessionId":session,"turnId":turn,"imageId":image_id}),
        )
        .await;
    assert_eq!(stored["data"], photo["data"]);
    assert!(
        engine
            .call(
                "history.image",
                json!({"sessionId":"foreign","turnId":turn,"imageId":image_id})
            )
            .await
            .get("error")
            .is_some()
    );
    let proof: Value =
        serde_json::from_slice(&fs::read(original.join("image-proof.json")).unwrap()).unwrap();
    assert_eq!(proof[0]["mimeType"], "image/png");
    assert_eq!(proof[0]["hash"].as_str().unwrap().len(), 64);
    engine
        .ok(
            "sessions.queue",
            json!({"sessionId":session,"action":"resume"}),
        )
        .await;
    for expected in 2..=3 {
        let waiting = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
        if expected == 3 {
            engine
                .wait(|v| v["status"] == "ready" && v["queue"].as_array().is_none_or(Vec::is_empty))
                .await;
        }
    }
    let ready = engine.ok("sessions.active", json!({})).await;
    let turns: Vec<_> = ready["activity"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|e| e["kind"] == "turn.started")
        .collect();
    assert_eq!(turns.len(), 3);
    let second_turn = turns[1]["turnId"].clone();
    let preview = engine
        .ok(
            "history.rewindPreview",
            json!({"sessionId":session,"turnId":second_turn}),
        )
        .await;
    assert_eq!(preview["complete"], true);
    engine
        .ok("sessions.close", json!({"sessionId":session}))
        .await;
    engine.wait(|v| v["status"] == "closed").await;
    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../scripts/spikes/fixture_agent.py")
        .canonicalize()
        .unwrap();
    let rewound=engine.ok("sessions.start",json!({"executable":"/usr/bin/python3","arguments":[script,"--images"],"sourceSessionId":session,"rewindTurnId":second_turn,"writersStopped":true,"prompt":"","connectOnly":true})).await["sessionId"].clone();
    assert_ne!(rewound, session);
    let view = engine.wait(|v| v["status"] == "ready").await;
    let new_root = PathBuf::from(view["workspace"].as_str().unwrap());
    assert_ne!(new_root, original);
    assert_eq!(
        fs::read(new_root.join("probe.txt")).unwrap(),
        b"flow-probe-1\n"
    );
    assert_eq!(
        fs::read(original.join("probe.txt")).unwrap(),
        b"flow-probe-3\n"
    );
    assert!(!repo.root.join("probe.txt").exists());
    engine.shutdown().await;
    let mut restarted = Engine::new(&repo).await;
    assert_eq!(
        restarted
            .ok(
                "history.image",
                json!({"sessionId":session,"turnId":turn,"imageId":image_id})
            )
            .await["data"],
        photo["data"]
    );
    restarted.shutdown().await;
}

#[tokio::test]
async fn unsupported_images_preserve_ready_session_and_stop_pauses_queued_prompts() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "", &[]).await;
    engine.wait(|v| v["status"] == "ready").await;
    assert!(engine.call("sessions.prompt",json!({"sessionId":session,"prompt":"look","images":[{"name":"test.png","mimeType":"image/png","data":"iVBORw0KGgo="}]})).await.get("error").is_some());
    assert_eq!(
        engine.ok("sessions.active", json!({})).await["status"],
        "ready"
    );
    engine
        .ok(
            "sessions.prompt",
            json!({"sessionId":session,"prompt":"waits 30 seconds"}),
        )
        .await;
    engine
        .wait(|v| {
            v["activity"]
                .as_array()
                .unwrap()
                .iter()
                .any(|e| e["kind"] == "message.delta")
        })
        .await;
    engine.ok("sessions.queue",json!({"sessionId":session,"action":"enqueue","id":"after-stop","command":{"sessionId":session,"prompt":"must not run"}})).await;
    engine
        .ok("sessions.cancel", json!({"sessionId":session}))
        .await;
    let ready = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(ready["queuePaused"], true);
    assert_eq!(ready["queue"].as_array().unwrap().len(), 1);
    sleep(Duration::from_millis(500)).await;
    assert_eq!(
        engine.ok("sessions.active", json!({})).await["status"],
        "ready"
    );
    engine.shutdown().await;
    let mut restarted = Engine::new(&repo).await;
    let queue = restarted
        .ok("queue.list", json!({"sessionId":session}))
        .await;
    assert_eq!(queue["paused"], true);
    assert_eq!(
        restarted
            .ok("queue.get", json!({"sessionId":session,"id":"after-stop"}))
            .await["prompt"],
        "must not run"
    );
    restarted.shutdown().await;
}

#[tokio::test]
async fn cursor_connects_once_and_reuses_its_acp_process_for_followups() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine
        .start(&repo, "", &["--cursor", "--configuration", "--reject-mcp"])
        .await;
    let connected = engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(connected["agentInfo"]["name"], "cursor-agent");
    for directory in fs::read_dir(&repo.data).unwrap() {
        assert!(
            !directory
                .unwrap()
                .path()
                .join("derived/context-v1.sqlite")
                .exists(),
            "Connect must not run fallback indexing before a task"
        );
    }
    assert_eq!(connected["connectionStage"], "ready");
    let phases = connected["startupTimingsMs"].as_object().unwrap();
    assert_eq!(phases.len(), 6);
    assert!(phases["ready"].as_u64().unwrap() >= phases["adapter"].as_u64().unwrap());
    assert!(
        !connected["activity"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "turn.started")
    );
    let workspace = PathBuf::from(connected["workspace"].as_str().unwrap());
    let process = fs::read(workspace.join("fixture-pids.json")).unwrap();
    for prompt in ["first task", "follow-up task"] {
        engine
            .ok(
                "sessions.prompt",
                json!({"sessionId":session,"prompt":prompt}),
            )
            .await;
        let permission = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":permission["permissions"][0]["requestId"],"optionId":"once"})).await;
        engine.wait(|v| v["status"] == "ready").await;
        assert_eq!(
            fs::read(workspace.join("fixture-pids.json")).unwrap(),
            process
        );
    }
    engine.shutdown().await;
}

#[tokio::test]
async fn chat_changed_files_are_session_scoped_and_diffs_survive_later_edits() {
    let repo = Repository::new();
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "write a file", &[]).await;
    let waiting = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
    engine.wait(|v| v["status"] == "ready").await;
    let files = engine
        .ok("history.changes", json!({"sessionId":session}))
        .await;
    assert_eq!(files["complete"], true);
    let probe = files["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|f| f["newPath"] == "probe.txt")
        .unwrap();
    let query = json!({"sessionId":session,"snapshotId":files["snapshotId"],"fileIndex":probe["index"],"contentIds":probe["contentIds"]});
    let first = engine.ok("history.changeDiff", query.clone()).await;
    assert_eq!(first["before"], "");
    assert_eq!(first["after"], "flow-probe-1\n");
    engine
        .ok(
            "sessions.prompt",
            json!({"sessionId":session,"prompt":"write again"}),
        )
        .await;
    let waiting = engine
        .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
        .await;
    engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
    engine.wait(|v| v["status"] == "ready").await;
    assert_eq!(
        engine.ok("history.changeDiff", query.clone()).await["after"],
        "flow-probe-1\n"
    );
    let latest = engine
        .ok("history.changes", json!({"sessionId":session}))
        .await;
    assert_ne!(latest["snapshotId"], files["snapshotId"]);
    let mut forged = query.clone();
    forged["sessionId"] = json!("foreign");
    assert!(
        engine
            .call("history.changeDiff", forged)
            .await
            .get("error")
            .is_some()
    );
    let mut forged = query;
    forged["contentIds"]["after"] = json!("0".repeat(64));
    assert!(
        engine
            .call("history.changeDiff", forged)
            .await
            .get("error")
            .is_some()
    );
    engine.shutdown().await;
}

#[tokio::test]
async fn generic_files_reach_claude_and_cursor_and_remain_frozen_in_queue_and_history() {
    for profile in ["--claude", "--cursor"] {
        let repo = Repository::new();
        let mut engine = Engine::new(&repo).await;
        let source = repo.root.join("external-report.pdf");
        fs::write(&source, b"%PDF-1.7\noriginal\0binary").unwrap();
        let imported = engine
            .ok("attachments.import", json!({"paths":[source]}))
            .await;
        let file = imported["items"][0].clone();
        fs::write(&source, b"developer changed the original").unwrap();
        let session = engine.start(&repo, "", &[profile, "--attachments"]).await;
        engine.wait(|v| v["status"] == "ready").await;
        engine
            .ok(
                "sessions.prompt",
                json!({"sessionId":session,"prompt":"Read this PDF","attachments":[file]}),
            )
            .await;
        let waiting = engine
            .wait(|v| !v["permissions"].as_array().unwrap().is_empty())
            .await;
        let workspace = PathBuf::from(waiting["workspace"].as_str().unwrap());
        let proof: Value =
            serde_json::from_slice(&fs::read(workspace.join("attachment-proof.json")).unwrap())
                .unwrap();
        assert_eq!(proof[1]["type"], "resource_link");
        assert_eq!(proof[1]["mimeType"], "application/pdf");
        let frozen = url::Url::parse(proof[1]["uri"].as_str().unwrap())
            .unwrap()
            .to_file_path()
            .unwrap();
        assert_eq!(fs::read(&frozen).unwrap(), b"%PDF-1.7\noriginal\0binary");
        engine.ok("sessions.queue",json!({"sessionId":session,"action":"enqueue","id":"queued-pdf","command":{"sessionId":session,"prompt":"Read it again","attachments":[file]}})).await;
        engine
            .ok(
                "sessions.queue",
                json!({"sessionId":session,"action":"pause"}),
            )
            .await;
        engine.ok("permissions.respond",json!({"sessionId":session,"requestId":waiting["permissions"][0]["requestId"],"optionId":"once"})).await;
        let ready = engine.wait(|v| v["status"] == "ready").await;
        let event = ready["activity"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["kind"] == "turn.started")
            .unwrap();
        assert_eq!(event["attachments"][0], file);
        assert_eq!(ready["queue"][0]["attachmentCount"], 1);
        let query = json!({"sessionId":session,"turnId":event["turnId"],"attachment":file});
        assert_eq!(
            engine.ok("history.attachment", query.clone()).await["path"],
            frozen.to_string_lossy().as_ref()
        );
        let mut wrong = query.clone();
        wrong["sessionId"] = json!("foreign");
        assert!(
            engine
                .call("history.attachment", wrong)
                .await
                .get("error")
                .is_some()
        );
        let queued = engine
            .ok("queue.get", json!({"sessionId":session,"id":"queued-pdf"}))
            .await;
        assert_eq!(queued["attachments"][0], file);
        assert_eq!(fs::read(source).unwrap(), b"developer changed the original");
        engine.shutdown().await;
        let mut restarted = Engine::new(&repo).await;
        assert_eq!(
            restarted.ok("history.attachment", query).await["attachment"],
            file
        );
        restarted.shutdown().await;
    }
}

#[tokio::test]
async fn code_intelligence_rpc_and_cli_use_the_same_fresh_project_index() {
    let repo = Repository::new();
    repo.write("helpers.ts", b"export function helper() {}\n");
    repo.write(
        "main.ts",
        b"import { helper } from './helpers';\nfunction main() { helper(); }\n",
    );
    repo.git(&["add", "helpers.ts", "main.ts"]);
    repo.git(&["commit", "-m", "code graph fixture"]);
    // The offline CLI exits before the IDE engine opens the same history store.
    let cli = Command::new(env!("CARGO_BIN_EXE_flow-engine"))
        .args([
            "--context",
            "find",
            "--project-root",
            repo.root.to_str().unwrap(),
            "--data-root",
            repo.data.to_str().unwrap(),
            "--params",
            r#"{"query":"helper"}"#,
        ])
        .output()
        .await
        .unwrap();
    assert!(
        cli.status.success(),
        "{}",
        String::from_utf8_lossy(&cli.stderr)
    );
    let from_cli: Value = serde_json::from_slice(&cli.stdout).unwrap();
    assert_eq!(from_cli["results"][0]["name"], "helper");
    let mut engine = Engine::new(&repo).await;
    let found = engine.ok("context.find", json!({"query":"helper"})).await;
    assert_eq!(from_cli["generation"], found["generation"]);
    let trace = engine
        .ok("context.trace", json!({"symbol":"helper","direction":"in"}))
        .await;
    assert!(
        trace["results"]
            .as_array()
            .unwrap()
            .iter()
            .any(|edge| edge["relation"] == "calls" && edge["source"]["name"] == "main")
    );
    assert_eq!(
        engine
            .ok("context.fileApi", json!({"path":"helpers.ts"}))
            .await["results"][0]["name"],
        "helper"
    );
    let note=engine.ok("context.saveLearning",json!({"title":"Helper must stay small","body":"Keep this helper focused.","author":"Reviewer","touches":["helpers.ts"]})).await;
    assert_eq!(note["immutable"], true);
    assert_eq!(
        engine
            .ok("context.review", json!({"paths":["helpers.ts"]}))
            .await["results"][0]["title"],
        "Helper must stay small"
    );
    let rejected = engine
        .ok(
            "context.find",
            json!({"query":"helper","root":"/elsewhere"}),
        )
        .await;
    assert!(rejected["error"].as_str().is_some());
    repo.write("helpers.ts", b"export function renamed() {}\n");
    assert_eq!(
        engine.ok("context.check", json!({})).await["freshness"],
        "stale"
    );
    let fresh = engine.ok("context.find", json!({"query":"renamed"})).await;
    assert_ne!(fresh["generation"], found["generation"]);
    assert_eq!(fresh["results"][0]["name"], "renamed");
    assert!(
        engine.ok("context.graph", json!({})).await["results"]
            .as_array()
            .unwrap()
            .iter()
            .any(|row| row["type"] == "node")
    );
    engine.shutdown().await;
}
