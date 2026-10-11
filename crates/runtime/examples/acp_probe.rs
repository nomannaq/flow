use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1::{
    AuthenticateRequest, CancelNotification, ContentBlock, InitializeRequest, NewSessionRequest,
    PermissionOptionKind, PromptRequest, RequestPermissionOutcome, RequestPermissionRequest,
    RequestPermissionResponse, SelectedPermissionOutcome, SessionNotification, StopReason,
    TextContent,
};
use agent_client_protocol::{AcpAgent, AcpAgentConfig, Agent, Client, ConnectionTo, Error};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

type Trace = Arc<Mutex<Vec<Value>>>;

fn record(trace: &Trace, event: Value) {
    let mut events = trace.lock().expect("trace lock poisoned");
    if events.len() < 1024 {
        events.push(event);
    }
}

fn checkpoint(workspace: &Path, evidence: &Path, sequence: usize) -> Result<Value, Error> {
    let path = workspace.join("probe.txt");
    let metadata = std::fs::symlink_metadata(&path).map_err(Error::into_internal_error)?;
    if !metadata.is_file() || metadata.len() > 64 * 1024 {
        return Err(Error::internal_error().data("probe.txt must be a small regular file"));
    }
    let bytes = std::fs::read(path).map_err(Error::into_internal_error)?;
    let expected = format!("flow-probe-{sequence}\n");
    if bytes != expected.as_bytes() {
        return Err(Error::internal_error().data("unexpected checkpoint content"));
    }
    let blob = format!("checkpoint-{sequence}.txt");
    std::fs::write(evidence.join(&blob), &bytes).map_err(Error::into_internal_error)?;
    Ok(
        json!({"sequence": sequence, "blob": blob, "blake3": blake3::hash(&bytes).to_hex().as_str()}),
    )
}

async fn probe(
    mode: String,
    agent: AcpAgent,
    workspace: PathBuf,
    evidence: PathBuf,
    allow_once: bool,
    auth_method: Option<String>,
    trace: Trace,
) -> Result<(), Error> {
    let updates = trace.clone();
    let permissions = trace.clone();
    Client.builder()
        .on_receive_notification(
            async move |notification: SessionNotification, _connection| {
                let update = serde_json::to_value(notification.update)
                    .map_err(Error::into_internal_error)?;
                let kind = match update["sessionUpdate"].as_str() {
                    Some("agent_message_chunk") => "agent_message_chunk",
                    Some("agent_thought_chunk") => "agent_thought_chunk",
                    Some("tool_call") => "tool_call",
                    Some("tool_call_update") => "tool_call_update",
                    Some("plan") => "plan",
                    _ => "other",
                };
                record(&updates, json!({"event": "session.update", "kind": kind}));
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .on_receive_request(
            async move |request: RequestPermissionRequest, responder, _connection| {
                let option = request.options.iter().find(|option| {
                    allow_once && option.kind == PermissionOptionKind::AllowOnce
                });
                let outcome = match option {
                    Some(option) => RequestPermissionOutcome::Selected(
                        SelectedPermissionOutcome::new(option.option_id.clone()),
                    ),
                    None => RequestPermissionOutcome::Cancelled,
                };
                record(&permissions, json!({
                    "event": "permission.requested",
                    "decision": if option.is_some() { "allow_once" } else { "cancelled" }
                }));
                responder.respond(RequestPermissionResponse::new(outcome))
            },
            agent_client_protocol::on_receive_request!(),
        )
        .connect_with(agent, |connection: ConnectionTo<Agent>| async move {
            record(&trace, json!({"event": "initialize.sent"}));
            let initialized = connection
                .send_request(InitializeRequest::new(ProtocolVersion::V1))
                .block_task()
                .await?;
            record(&trace, json!({
                "event": "initialized",
                "protocolVersion": initialized.protocol_version,
                "agentInfo": initialized.agent_info.as_ref().map(|info| json!({"name": info.name, "version": info.version})),
                "authMethods": initialized.auth_methods.iter().map(|method| method.id()).collect::<Vec<_>>()
            }));
            if initialized.protocol_version != ProtocolVersion::V1 {
                return Err(Error::internal_error().data("stable ACP v1 required"));
            }
            if mode == "inspect" {
                return Ok(());
            }
            if let Some(method) = auth_method {
                let advertised = initialized.auth_methods.iter().any(|entry| entry.id().to_string() == method);
                if !advertised {
                    return Err(Error::internal_error().data("authentication method not advertised"));
                }
                connection.send_request(AuthenticateRequest::new(method)).block_task().await?;
                record(&trace, json!({"event": "authenticated"}));
            }
            record(&trace, json!({"event": "session.new.sent"}));
            let session = connection
                .send_request(NewSessionRequest::new(workspace.clone()))
                .block_task()
                .await?;
            record(&trace, json!({"event": "session.created"}));
            record(&trace, json!({"event": "checkpoint", "snapshot": checkpoint(&workspace, &evidence, 0)?}));
            if mode == "cancel" {
                let response = connection.send_request(PromptRequest::new(
                    session.session_id.clone(),
                    vec![ContentBlock::Text(TextContent::new(
                        "Run a shell command that waits 30 seconds. Do not edit any files.",
                    ))],
                )).block_task();
                tokio::pin!(response);
                tokio::select! {
                    result = &mut response => {
                        result?;
                        return Err(Error::internal_error().data("prompt ended before cancellation could be exercised"));
                    }
                    () = tokio::time::sleep(Duration::from_secs(1)) => {}
                }
                connection.send_notification(CancelNotification::new(session.session_id))?;
                record(&trace, json!({"event": "session.cancel.sent"}));
                let response = tokio::time::timeout(Duration::from_secs(10), response)
                    .await.map_err(Error::into_internal_error)??;
                if response.stop_reason != StopReason::Cancelled {
                    return Err(Error::internal_error().data("agent did not acknowledge cancellation"));
                }
                record(&trace, json!({"event": "session.cancel.acknowledged"}));
                return Ok(());
            }
            for sequence in 1..=2 {
                let instruction = format!(
                    "Replace only probe.txt with exactly flow-probe-{sequence} followed by a newline. \
                     Use a file editing tool or shell command. Do not modify any other files."
                );
                let response = connection.send_request(PromptRequest::new(
                    session.session_id.clone(),
                    vec![ContentBlock::Text(TextContent::new(instruction))],
                )).block_task().await?;
                record(&trace, json!({"event": "turn.completed", "stopReason": response.stop_reason}));
                if response.stop_reason != StopReason::EndTurn {
                    return Err(Error::internal_error().data("edit turn did not complete"));
                }
                record(&trace, json!({"event": "checkpoint", "snapshot": checkpoint(&workspace, &evidence, sequence)?}));
            }
            Ok(())
        })
        .await
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = std::env::args().skip(1);
    let mode = arguments.next().unwrap_or_default();
    if !["inspect", "edit", "cancel"].contains(&mode.as_str()) {
        return Err("Usage: acp_probe inspect|edit|cancel [--allow-once] [--auth-method ID] [--timeout-seconds N] -- EXECUTABLE [ARGS...]".into());
    }
    let mut allow_once = false;
    let mut auth_method = None;
    let mut timeout_seconds = 120;
    loop {
        match arguments.next().as_deref() {
            Some("--allow-once") => allow_once = true,
            Some("--timeout-seconds") => {
                timeout_seconds = arguments.next().ok_or("missing timeout")?.parse::<u64>()?;
                if !(1..=120).contains(&timeout_seconds) {
                    return Err("timeout must be between 1 and 120 seconds".into());
                }
            }
            Some("--auth-method") => {
                auth_method = Some(arguments.next().ok_or("missing auth method")?)
            }
            Some("--") => break,
            _ => return Err("expected probe options followed by -- EXECUTABLE [ARGS...]".into()),
        }
    }
    let executable = PathBuf::from(arguments.next().ok_or("missing adapter executable")?);
    let executable = if executable.components().count() > 1 {
        std::fs::canonicalize(executable)?
    } else {
        executable
    };
    let agent = AcpAgent::new(AcpAgentConfig::new(executable).args(arguments));
    let evidence = tempfile::Builder::new()
        .prefix("flow-acp-probe-")
        .tempdir()?
        .keep();
    let workspace = evidence.join("workspace");
    std::fs::create_dir(&workspace)?;
    let git = std::process::Command::new("git")
        .args(["init", "--quiet"])
        .arg(&workspace)
        .output()?;
    if !git.status.success() {
        return Err("could not initialize disposable Git repository".into());
    }
    std::fs::write(workspace.join("probe.txt"), "flow-probe-0\n")?;
    let original_directory = std::env::current_dir()?;
    std::env::set_current_dir(&workspace)?;
    let trace: Trace = Arc::default();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    let result = runtime.block_on(async {
        tokio::time::timeout(
            Duration::from_secs(timeout_seconds),
            probe(
                mode.clone(),
                agent,
                workspace,
                evidence.clone(),
                allow_once,
                auth_method,
                trace.clone(),
            ),
        )
        .await
    });
    drop(runtime);
    std::env::set_current_dir(original_directory)?;
    let status = match &result {
        Ok(Ok(())) => "passed",
        Ok(Err(_)) => "failed",
        Err(_) => "timed_out",
    };
    let error_code = match &result {
        Ok(Err(error)) => Some(error.code),
        _ => None,
    };
    let events = trace.lock().expect("trace lock poisoned");
    let report = json!({
        "mode": mode, "status": status, "errorCode": error_code,
        "sdkVersion": "3.3.0", "protocol": "stable-v1", "allowOnce": allow_once,
        "timeoutSeconds": timeout_seconds,
        "traceMayBeTruncated": events.len() == 1024,
        "capture": "single fixture file at completed-turn boundaries; not tool-atomic or complete-workspace history",
        "events": *events
    });
    let report_path = evidence.join("report.json");
    std::fs::write(&report_path, serde_json::to_vec_pretty(&report)?)?;
    println!("{}", report_path.display());
    if status != "passed" {
        return Err("probe failed; inspect report.json (raw adapter errors omitted to avoid credential leakage)".into());
    }
    Ok(())
}
