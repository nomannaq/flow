use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1::{
    AuthenticateRequest, CancelNotification, ContentBlock, InitializeRequest, NewSessionRequest,
    PermissionOptionKind, PromptRequest, RequestPermissionOutcome, RequestPermissionRequest,
    RequestPermissionResponse, SelectedPermissionOutcome, SessionNotification, StopReason,
    TextContent,
};
use agent_client_protocol::{
    AcpAgent, AcpAgentConfig, Agent, Client, ConnectionTo, Error, LineDirection,
};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

type Trace = Arc<Mutex<Vec<Value>>>;

fn record(trace: &Trace, event: Value) {
    let mut events = trace.lock().expect("trace lock poisoned");
    if events.len() < 1024 {
        events.push(event);
    }
}

#[cfg(unix)]
fn launch(mut arguments: impl Iterator<Item = String>) -> Result<(), Box<dyn std::error::Error>> {
    use std::os::unix::process::CommandExt;

    let workspace = arguments.next().ok_or("missing launcher workspace")?;
    let executable = arguments.next().ok_or("missing launcher executable")?;
    let allowed = [
        "PATH",
        "HOME",
        "TMPDIR",
        "LANG",
        "LC_ALL",
        "TERM",
        "CODEX_HOME",
        "CODEX_SANDBOX",
        "CODEX_SANDBOX_NETWORK_DISABLED",
        "__CF_USER_TEXT_ENCODING",
        "CODEX_API_KEY",
        "OPENAI_API_KEY",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
    ];
    let environment = allowed
        .into_iter()
        .filter_map(|name| std::env::var_os(name).map(|value| (name, value)));
    let error = std::process::Command::new(executable)
        .args(arguments)
        .current_dir(workspace)
        .env_clear()
        .envs(environment)
        .exec();
    Err(error.into())
}

#[cfg(not(unix))]
fn launch(_: impl Iterator<Item = String>) -> Result<(), Box<dyn std::error::Error>> {
    Err("The historical B01 launcher is Unix-only; use checkPackagedAgentSession for Windows acceptance".into())
}

fn diagnostic(line: &str) -> &'static str {
    let text = line.to_ascii_lowercase();
    if text.contains("operation not permitted") || text.contains("permission denied") {
        "permission_denied"
    } else if text.contains("failed to resolve")
        || text.contains("dns error")
        || text.contains("network is unreachable")
    {
        "network_unavailable"
    } else if text.contains("unauthorized")
        || text.contains("authentication")
        || text.contains("not logged in")
        || text.contains("invalid_api_key")
        || text.contains("token_expired")
    {
        "authentication"
    } else if text.contains("rate limit")
        || text.contains("rate_limit")
        || text.contains("insufficient_quota")
    {
        "rate_limit_or_quota"
    } else if text.contains("model_not_found") || text.contains("unsupported model") {
        "model_unavailable"
    } else if text.contains("unexpected status") || text.contains("status code:") {
        "http_error"
    } else if text.contains("stream disconnected") || text.contains("stream closed") {
        "stream_disconnected"
    } else if text.contains("error sending request")
        || text.contains("connection refused")
        || text.contains("connection reset")
        || text.contains("connect error")
    {
        "connection_error"
    } else if text.contains("certificate") || text.contains("tls error") {
        "tls_error"
    } else if text.contains("unexpected checkpoint content")
        || text.contains("unexpected background content")
    {
        "checkpoint_mismatch"
    } else if text.contains("timed out") || text.contains("timeout") {
        "timeout"
    } else if text.contains("unknown variant")
        || text.contains("invalid value")
        || text.contains("unknown field")
    {
        "incompatible_configuration"
    } else if text.contains("no such file") {
        "missing_file"
    } else if text.contains("system-configuration") {
        "system_configuration"
    } else if text.contains("panicked") {
        "adapter_panic"
    } else if text.contains("config") {
        "configuration"
    } else {
        "other"
    }
}

fn http_error_details(text: &str) -> Value {
    let text = text.to_ascii_lowercase();
    let status = ["unexpected status", "status code", "http status"]
        .into_iter()
        .find_map(|marker| {
            text.match_indices(marker).find_map(|(position, _)| {
                let tail = text[position + marker.len()..].trim_start_matches([' ', ':', '=']);
                let digits: String = tail.chars().take_while(char::is_ascii_digit).collect();
                (digits.len() == 3)
                    .then(|| digits.parse::<u16>().ok())
                    .flatten()
                    .filter(|code| (100..=599).contains(code))
            })
        });
    // Only fixed diagnostic signals leave this function, never server-supplied text.
    let signals: Vec<_> = [
        "invalid_api_key",
        "token_expired",
        "invalid_request_error",
        "unsupported_value",
        "unsupported_parameter",
        "model_not_found",
        "rate_limit_exceeded",
        "insufficient_quota",
        "credit_balance_exhausted",
        "usage_limit_reached",
        "slow_down",
        "server_is_overloaded",
        "unauthorized",
        "forbidden",
        "not supported",
        "does not exist",
        "not found",
        "invalid model",
        "chatgpt",
        "cloudflare",
        "access denied",
        "bad gateway",
    ]
    .into_iter()
    .filter(|signal| text.contains(signal))
    .collect();
    json!({"status": status, "signals": signals})
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
                let status = match update["status"].as_str() {
                    Some("pending") => Some("pending"),
                    Some("in_progress") => Some("in_progress"),
                    Some("completed") => Some("completed"),
                    Some("failed") => Some("failed"),
                    _ => None,
                };
                let tool_id = update["toolCallId"].as_str().map(|id| blake3::hash(id.as_bytes()).to_hex().to_string());
                record(&updates, json!({"event": "session.update", "kind": kind, "status": status, "toolCallIdHash": tool_id}));
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
                "capabilities": {
                    "loadSession": initialized.agent_capabilities.load_session,
                    "image": initialized.agent_capabilities.prompt_capabilities.image,
                    "audio": initialized.agent_capabilities.prompt_capabilities.audio,
                    "embeddedContext": initialized.agent_capabilities.prompt_capabilities.embedded_context,
                    "mcpHttp": initialized.agent_capabilities.mcp_capabilities.http,
                    "mcpSse": initialized.agent_capabilities.mcp_capabilities.sse
                },
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
                record(&trace, json!({"event": "authenticate.sent"}));
                connection.send_request(AuthenticateRequest::new(method)).block_task().await?;
                record(&trace, json!({"event": "authenticated"}));
            }
            if mode == "auth" {
                return Ok(());
            }
            record(&trace, json!({"event": "session.new.sent"}));
            let session = connection
                .send_request(NewSessionRequest::new(workspace.clone()))
                .block_task()
                .await?;
            record(&trace, json!({"event": "session.created"}));
            if mode == "session" {
                return Ok(());
            }
            record(&trace, json!({"event": "checkpoint", "snapshot": checkpoint(&workspace, &evidence, 0)?}));
            if mode == "background" {
                let response = connection.send_request(PromptRequest::new(
                    session.session_id.clone(),
                    vec![ContentBlock::Text(TextContent::new(
                        "In this disposable repository, run this exact shell command to start two overlapping writers, then finish your turn immediately without waiting: \
                         (sleep 1; printf 'flow-probe-1\\n' > first.tmp; mv first.tmp probe.txt) & \
                         (sleep 2; printf 'flow-probe-2\\n' > second.tmp; mv second.tmp probe.txt) & \
                         Do not change any other files."
                    ))],
                )).block_task().await?;
                if response.stop_reason != StopReason::EndTurn {
                    return Err(Error::internal_error().data("background turn did not complete"));
                }
                record(&trace, json!({"event": "turn.completed", "stopReason": response.stop_reason}));
                let mut previous = 0;
                let mut changed_after_turn = false;
                let mut final_state = 0;
                for sample in 0..=15 {
                    let bytes = std::fs::read(workspace.join("probe.txt")).map_err(Error::into_internal_error)?;
                    let state = (0..=2).find(|state| bytes == format!("flow-probe-{state}\n").as_bytes())
                        .ok_or_else(|| Error::internal_error().data("unexpected background content"))?;
                    if state != previous {
                        changed_after_turn |= sample > 0;
                        record(&trace, json!({"event": "checkpoint", "sample": sample, "snapshot": checkpoint(&workspace, &evidence, state)?}));
                    }
                    previous = state;
                    final_state = state;
                    if sample < 15 {
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    }
                }
                record(&trace, json!({"event": "background.observed", "changedAfterTurn": changed_after_turn, "finalState": final_state}));
                if final_state != 2 {
                    return Err(Error::internal_error().data("background writers did not finish"));
                }
                return Ok(());
            }
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
                let instruction = if mode == "shell" { format!(
                    "Use a shell command to replace only probe.txt with exactly flow-probe-{sequence} followed by a newline. \
                     Execute: printf 'flow-probe-{sequence}\\n' > probe.txt"
                ) } else { format!(
                    "Replace only probe.txt with exactly flow-probe-{sequence} followed by a newline. \
                     Use a file editing tool or shell command. Do not modify any other files."
                ) };
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
    if mode == "launch" {
        return launch(arguments);
    }
    if ![
        "inspect",
        "auth",
        "session",
        "edit",
        "shell",
        "background",
        "cancel",
    ]
    .contains(&mode.as_str())
    {
        return Err("Usage: acp_probe inspect|auth|session|edit|shell|background|cancel [--allow-once] [--auth-method ID] [--timeout-seconds N] -- EXECUTABLE [ARGS...]".into());
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
    if mode == "auth" && auth_method.is_none() {
        return Err("auth mode requires --auth-method ID from an inspect report".into());
    }
    let executable = PathBuf::from(arguments.next().ok_or("missing adapter executable")?);
    let executable = if executable.components().count() > 1 {
        std::fs::canonicalize(executable)?
    } else {
        executable
    };
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
    let trace: Trace = Arc::default();
    let diagnostics = trace.clone();
    let adapter_panicked = Arc::new(tokio::sync::Notify::new());
    let panic_signal = adapter_panicked.clone();
    let started = Instant::now();
    let agent = AcpAgent::new(AcpAgentConfig::new(std::env::current_exe()?)
        .args(["launch".to_owned(), workspace.to_str().ok_or("workspace path must be UTF-8")?.to_owned(),
            executable.to_str().ok_or("executable path must be UTF-8")?.to_owned()])
        .args(arguments))
        .with_debug(move |line, direction| {
            if direction == LineDirection::Stderr {
                // A worker can panic while the adapter process and RPC stream stay open.
                let panicked = line.starts_with("thread '") && line.contains(" panicked at ");
                record(&diagnostics, json!({
                    "event": "adapter.stderr", "category": diagnostic(line),
                    "panic": panicked,
                    "nullObject": line.to_ascii_lowercase().contains("attempted to create a null object"),
                    "elapsedMs": started.elapsed().as_millis()
                }));
                if panicked {
                    panic_signal.notify_one();
                }
            }
        });
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    let result = runtime.block_on(async {
        tokio::time::timeout(Duration::from_secs(timeout_seconds), async {
            tokio::select! {
                biased;
                () = adapter_panicked.notified() => {
                    record(&trace, json!({"event": "adapter.panic"}));
                    Err(Error::internal_error().data("adapter panicked"))
                }
                result = probe(
                    mode.clone(), agent, workspace, evidence.clone(),
                    allow_once, auth_method, trace.clone(),
                ) => result,
            }
        })
        .await
    });
    drop(runtime);
    let parent_directory_unchanged = std::env::current_dir()? == original_directory;
    let status = match &result {
        Ok(Ok(())) => "passed",
        Ok(Err(_)) => "failed",
        Err(_) => "timed_out",
    };
    let error_code = match &result {
        Ok(Err(error)) => Some(error.code),
        _ => None,
    };
    // Classify RPC error data as well as stderr; never persist the raw payload.
    let (error_category, http_error) = match &result {
        Ok(Err(error)) => {
            let details = format!(
                "{} {}",
                error.message,
                error
                    .data
                    .as_ref()
                    .map(Value::to_string)
                    .unwrap_or_default()
            );
            (
                Some(diagnostic(&details)),
                Some(http_error_details(&details)),
            )
        }
        _ => (None, None),
    };
    let events = trace.lock().expect("trace lock poisoned");
    let failure_reason = if events.iter().any(|event| event["event"] == "adapter.panic") {
        Some("adapter_panic")
    } else if status == "timed_out" {
        Some("timeout")
    } else if status == "failed" {
        Some("rpc_or_probe_error")
    } else {
        None
    };
    let report = json!({
        "mode": mode, "status": status, "errorCode": error_code,
        "failureReason": failure_reason, "errorCategory": error_category,
        "httpError": http_error,
        "sdkVersion": "3.3.0", "protocol": "stable-v1", "allowOnce": allow_once,
        "timeoutSeconds": timeout_seconds,
        "elapsedMs": started.elapsed().as_millis(),
        "parentDirectoryUnchanged": parent_directory_unchanged,
        "launcher": "unix-exec-with-cwd-and-environment-allowlist",
        "traceMayBeTruncated": events.len() == 1024,
        "capture": "single fixture file at completed-turn boundaries; not tool-atomic or complete-workspace history",
        "events": *events
    });
    let report_path = evidence.join("report.json");
    std::fs::write(&report_path, serde_json::to_vec_pretty(&report)?)?;
    println!("{}", report_path.display());
    if failure_reason == Some("adapter_panic") {
        return Err("adapter panicked; probe stopped and SDK subprocess cleanup ran; inspect report.json for redacted diagnostics".into());
    }
    if status != "passed" || !parent_directory_unchanged {
        if let Some(details) = &http_error {
            eprintln!("HTTP diagnostics: {details}");
        }
        return Err(format!("probe failed ({}); inspect report.json (raw adapter errors omitted to avoid credential leakage)", error_category.unwrap_or("timeout_or_lifecycle")).into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{diagnostic, http_error_details};

    #[test]
    fn http_details_keep_status_and_fixed_signals_only() {
        let details = http_error_details(
            r#"stream disconnected: unexpected status 400 Bad Request: {"error":{"code":"unsupported_value","message":"Model private-fixture-token is not supported with ChatGPT"}}"#,
        );
        assert_eq!(details["status"], 400);
        assert_eq!(
            details["signals"],
            serde_json::json!(["unsupported_value", "not supported", "chatgpt"])
        );
        assert!(!details.to_string().contains("private-fixture-token"));
        assert_eq!(http_error_details("status code: 429")["status"], 429);
        for text in [
            "unexpected status 4000",
            "unexpected status 999",
            "request id 401",
            "unclassified secret",
        ] {
            assert!(http_error_details(text)["status"].is_null());
        }
    }

    #[test]
    fn diagnostics_classify_errors_without_returning_payloads() {
        for (message, expected) in [
            (
                "stream disconnected: unexpected status 401 Unauthorized secret",
                "authentication",
            ),
            ("unexpected status 400 Bad Request secret", "http_error"),
            ("model_not_found secret", "model_unavailable"),
            ("insufficient_quota secret", "rate_limit_or_quota"),
            (
                "stream disconnected before completion secret",
                "stream_disconnected",
            ),
            ("error sending request for url secret", "connection_error"),
            ("invalid certificate secret", "tls_error"),
            ("unexpected checkpoint content", "checkpoint_mismatch"),
            ("unclassified private-fixture-token", "other"),
        ] {
            assert_eq!(diagnostic(message), expected);
        }
    }
}
