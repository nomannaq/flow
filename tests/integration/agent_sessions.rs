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
        assert!(value.get("error").is_none(), "{value}");
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
        self.ok("sessions.start",json!({"executable":"/usr/bin/python3","arguments":arguments,"commit":commit,"prompt":prompt,"authMethod":if flags.contains(&"--needs-auth") {Some("fixture")} else {None}})).await["sessionId"].clone()
    }
    async fn wait(&mut self, predicate: impl Fn(&Value) -> bool) -> Value {
        timeout(Duration::from_secs(15), async {
            loop {
                let view = self.ok("sessions.active", json!({})).await;
                if predicate(&view) {
                    return view;
                }
                sleep(Duration::from_millis(30)).await;
            }
        })
        .await
        .expect("agent state deadline")
    }
    async fn shutdown(mut self) {
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
    }
}
async fn heartbeat_stops(workspace: &Path) {
    // Check the real descendant's filesystem effect without privileged process enumeration.
    sleep(Duration::from_millis(150)).await;
    let before = fs::read(workspace.join("fixture-heartbeat")).unwrap();
    sleep(Duration::from_millis(250)).await;
    assert_eq!(
        fs::read(workspace.join("fixture-heartbeat")).unwrap(),
        before
    );
}

#[tokio::test]
async fn prompts_permissions_followups_cancel_and_durable_lifecycle() {
    let repo = Repository::new();
    let index = fs::read(repo.root.join(".git/index")).unwrap();
    repo.write("tracked.txt", b"developer changes");
    let mut engine = Engine::new(&repo).await;
    let session = engine.start(&repo, "write probe", &["--needs-auth"]).await;
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
    engine.wait(|v| v["status"] == "closed").await;
    heartbeat_stops(&workspace).await;
    engine.shutdown().await;
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    let record = store
        .get_session(serde_json::from_value(session).unwrap())
        .unwrap();
    assert_eq!(record.status, "closed");
    assert_eq!(record.upstream_id.as_deref(), Some("fixture"));
    assert_eq!(record.adapter_version, "1");
    assert_eq!(fs::read(repo.root.join(".git/index")).unwrap(), index);
    assert_eq!(
        fs::read(repo.root.join("tracked.txt")).unwrap(),
        b"developer changes"
    );
    assert!(!repo.root.join("probe.txt").exists());
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
    engine.shutdown().await;
    heartbeat_stops(&path).await;
    assert!(!path.join("probe.txt").exists());
    let store = history::HistoryStore::open(&repo.root, &repo.data).unwrap();
    assert_eq!(
        store
            .get_session(serde_json::from_value(session).unwrap())
            .unwrap()
            .status,
        "interrupted"
    );
}
