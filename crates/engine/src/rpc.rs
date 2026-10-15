use futures_util::{SinkExt, StreamExt, future::BoxFuture, stream::FuturesUnordered};
use protocol::*;
use serde_json::Value;
use std::{
    collections::{HashMap, HashSet},
    io,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    time::{Instant, sleep_until, timeout},
};
use tokio_util::{
    codec::{FramedRead, FramedWrite, LinesCodec},
    sync::CancellationToken,
};

type Jobs = FuturesUnordered<BoxFuture<'static, (RequestId, Response)>>;
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);

async fn send<W: AsyncWrite + Unpin>(
    writer: &mut FramedWrite<W, LinesCodec>,
    response: Response,
) -> io::Result<()> {
    let frame = serde_json::to_string(&response)
        .map_err(|_| io::Error::other("response encoding failed"))?;
    if frame.len() > MAX_FRAME_BYTES {
        return Err(io::Error::other("response exceeds frame limit"));
    }
    timeout(WRITE_TIMEOUT, writer.send(frame))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "IPC write timeout"))?
        .map_err(|_| io::Error::other("IPC write failed"))
}

pub async fn serve<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: R,
    writer: W,
) -> io::Result<()> {
    serve_with_store(reader, writer, None).await
}

pub async fn serve_with_store<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: R,
    writer: W,
    store: Option<history::HistoryStore>,
) -> io::Result<()> {
    let mut agents = if let Some(history) = store.clone() {
        let check = history.clone();
        let writable =
            tokio::task::spawn_blocking(move || check.storage_report().is_ok_and(|r| r.writable))
                .await
                .unwrap_or(false);
        writable.then(|| runtime::agents::AgentHost::new(history))
    } else {
        None
    };
    let result = serve_connection(reader, writer, store, &mut agents).await;
    if let Some(host) = &mut agents {
        host.shutdown().await;
    }
    result
}

async fn serve_connection<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: R,
    writer: W,
    store: Option<history::HistoryStore>,
    agents: &mut Option<runtime::agents::AgentHost>,
) -> io::Result<()> {
    let mut random = [0u8; 16];
    getrandom::fill(&mut random).map_err(|_| io::Error::other("epoch generation failed"))?;
    let epoch = EngineEpoch::try_from(
        random
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>(),
    )
    .unwrap();
    let mut input = FramedRead::new(reader, LinesCodec::new_with_max_length(MAX_FRAME_BYTES));
    let mut output = FramedWrite::new(writer, LinesCodec::new_with_max_length(MAX_FRAME_BYTES));
    let mut initialized: Option<InitializeParams> = None;
    let mut used = HashSet::new();
    let mut pending: HashMap<RequestId, CancellationToken> = HashMap::new();
    let mut jobs = Jobs::new();
    let mut closing = false;
    let mut shutdown = None;
    loop {
        if closing && jobs.is_empty() {
            if let Some(host) = agents.as_mut() {
                host.shutdown().await;
            }
            if let Some(id) = shutdown.take() {
                send(
                    &mut output,
                    Response::success(id, epoch.clone(), Value::Null),
                )
                .await?;
            }
            return Ok(());
        }
        tokio::select! {
            completion = jobs.next(), if !jobs.is_empty() => {
                let (id, response) = completion.expect("nonempty job set");
                pending.remove(&id);
                send(&mut output, response).await?;
            }
            next = input.next(), if !closing => {
                let Some(frame) = next else {
                    closing = true;
                    for token in pending.values() { token.cancel(); }
                    continue;
                };
                let active_epoch = initialized.as_ref().map(|_| epoch.clone());
                let frame = match frame {
                    Ok(frame) => frame,
                    Err(_) => {
                        // Oversize/invalid UTF-8 is terminal; never resynchronize midway through a frame.
                        send(&mut output, Response::failure(None, active_epoch, error::INVALID_REQUEST)).await?;
                        return Err(io::Error::other("invalid IPC frame"));
                    }
                };
                let value: Value = match serde_json::from_str(&frame) {
                    Ok(value) => value,
                    Err(_) => {
                        send(&mut output, Response::failure(None, active_epoch, error::PARSE)).await?;
                        continue;
                    }
                };
                if !value.is_object() || value.get("jsonrpc") != Some(&Value::String("2.0".into()))
                    || !value.get("method").is_some_and(|v| v.as_str().is_some_and(|s| !s.is_empty() && s.len() <= 128)) {
                    send(&mut output, Response::failure(None, active_epoch, error::INVALID_REQUEST)).await?;
                    continue;
                }
                if value.get("id").is_none() {
                    // Notifications are never answered, including unknown methods/invalid params.
                    if let Ok(note) = serde_json::from_value::<Notification>(value)
                        && initialized.is_some() && note.epoch.as_ref() == Some(&epoch)
                        && Method::parse(&note.method) == Some(Method::Cancel)
                        && let Ok(cancel) = serde_json::from_value::<CancelParams>(note.params)
                        && let Some(token) = pending.get(&cancel.id) {
                            token.cancel();
                    }
                    continue;
                }
                let recovered_id = value.get("id").and_then(|id| serde_json::from_value::<RequestId>(id.clone()).ok()).filter(RequestId::is_valid);
                if let Some(id) = recovered_id.as_ref() {
                    if used.contains(id) || used.len() >= MAX_CONNECTION_REQUESTS {
                        let code = if used.contains(id) { error::DUPLICATE_ID } else { error::BUSY };
                        // Check before decoding the remaining fields: even a malformed duplicate
                        // must never send an error that could complete the original request.
                        send(&mut output, Response::failure(None, active_epoch, code)).await?;
                        closing = true;
                        for token in pending.values() { token.cancel(); }
                        continue;
                    }
                    used.insert(id.clone());
                }
                let request: Request = match serde_json::from_value(value) {
                    Ok(request) if recovered_id.is_some() => request,
                    _ => {
                        send(&mut output, Response::failure(recovered_id, active_epoch, error::INVALID_REQUEST)).await?;
                        continue;
                    }
                };
                let id = request.id.clone();
                let failure = |code| Response::failure(Some(id.clone()), active_epoch.clone(), code);
                let timeout_ms = request.timeout_ms.unwrap_or(DEFAULT_TIMEOUT_MS);
                if !request.params.is_object() || !(1..=MAX_TIMEOUT_MS).contains(&timeout_ms) {
                    send(&mut output, failure(error::INVALID_PARAMS)).await?;
                    continue;
                }
                if Method::parse(&request.method) == Some(Method::Initialize) {
                    if initialized.is_some() {
                        send(&mut output, failure(error::ALREADY_INITIALIZED)).await?;
                        continue;
                    }
                    let params: InitializeParams = match serde_json::from_value(request.params) {
                        Ok(p) => p,
                        Err(_) => { send(&mut output, failure(error::INVALID_PARAMS)).await?; continue; }
                    };
                    if params.client_name.is_empty() || params.client_name.len() > 128
                        || params.client_version.is_empty() || params.client_version.len() > 128 {
                        send(&mut output, failure(error::INVALID_PARAMS)).await?;
                        continue;
                    }
                    if params.protocol_version != PROTOCOL_VERSION {
                        send(&mut output, failure(error::INCOMPATIBLE_VERSION)).await?;
                        continue;
                    }
                    let result = InitializeResult {
                        protocol_version: PROTOCOL_VERSION, engine_version: env!("CARGO_PKG_VERSION").into(),
                        epoch: epoch.clone(), project_id: params.project_id.clone(),
                        capabilities: EngineCapabilities { cancellation: true, persistent_events: store.is_some(),
                            idempotent_mutations: false, agent_sessions: agents.is_some() },
                        max_frame_bytes: MAX_FRAME_BYTES, max_in_flight: MAX_IN_FLIGHT,
                        max_connection_requests: MAX_CONNECTION_REQUESTS,
                        default_timeout_ms: DEFAULT_TIMEOUT_MS, max_timeout_ms: MAX_TIMEOUT_MS,
                    };
                    initialized = Some(params);
                    send(&mut output, Response::success(id, epoch.clone(), result)).await?;
                    continue;
                }
                let Some(host) = initialized.as_ref() else {
                    send(&mut output, failure(error::NOT_INITIALIZED)).await?; continue;
                };
                if request.epoch.as_ref() != Some(&epoch) {
                    send(&mut output, failure(error::STALE_EPOCH)).await?; continue;
                }
                match Method::parse(&request.method) {
                    Some(Method::SessionBaseline) => {
                        let Some(store) = store.clone() else { send(&mut output, failure(error::METHOD_NOT_FOUND)).await?; continue; };
                        if pending.len() >= MAX_IN_FLIGHT { send(&mut output, failure(error::BUSY)).await?; continue; }
                        let token = CancellationToken::new(); pending.insert(id.clone(),token.clone());
                        let epoch = epoch.clone();
                        jobs.push(Box::pin(async move {
                            let result = tokio::select! {
                                _ = token.cancelled() => Err(error::CANCELLED),
                                result = timeout(Duration::from_millis(timeout_ms),runtime::agents::baseline(store)) => match result {
                                    Ok(Ok(commit)) => Ok(serde_json::json!({"commit":commit})),
                                    Ok(Err(_)) => Err(error::INVALID_PARAMS),
                                    Err(_) => Err(error::TIMEOUT),
                                }
                            };
                            let response = match result {
                                Ok(value) => Response::success(id.clone(),epoch,value),
                                Err(code) => Response::failure(Some(id.clone()),Some(epoch),code),
                            };
                            (id,response)
                        }));
                    }
                    Some(method @ (Method::SessionStart | Method::SessionAuthenticate | Method::SessionActive | Method::SessionPrompt | Method::SessionCancel | Method::SessionClose | Method::PermissionRespond)) => {
                        let Some(host) = agents.as_mut() else {
                            send(&mut output, failure(error::METHOD_NOT_FOUND)).await?; continue;
                        };
                        let result: Result<Value, ()> = match method {
                            Method::SessionActive => serde_json::to_value(host.view()).map_err(|_| ()),
                            Method::SessionAuthenticate => serde_json::from_value::<AgentAuthenticate>(request.params).map_err(|_| ())
                                .and_then(|params| host.authenticate(params).map(|()| Value::Null).map_err(|_| ())),
                            Method::SessionStart => serde_json::from_value::<AgentStart>(request.params).map_err(|_| ())
                                .and_then(|params| host.start(params).map(|id| serde_json::json!({"sessionId":id})).map_err(|_| ())),
                            Method::PermissionRespond => serde_json::from_value::<PermissionDecision>(request.params).map_err(|_| ())
                                .and_then(|params| host.permission(params).map(|()| Value::Null).map_err(|_| ())),
                            _ => serde_json::from_value::<AgentCommand>(request.params).map_err(|_| ())
                                .and_then(|params| {
                                    let result = if method == Method::SessionClose { host.close(&params.session_id) }
                                        else { host.command(params, method == Method::SessionCancel) };
                                    result.map(|()| Value::Null).map_err(|_| ())
                                }),
                        };
                        match result {
                            Ok(value) => send(&mut output, Response::success(id, epoch.clone(), value)).await?,
                            Err(()) => send(&mut output, failure(error::INVALID_PARAMS)).await?,
                        }
                    }
                    Some(method @ (Method::SessionList | Method::SessionArchive | Method::HistoryTurns | Method::HistoryLocation | Method::HistoryOperations | Method::HistoryFiles | Method::HistoryDiff)) => {
                        let Some(store) = store.clone() else { send(&mut output, failure(error::METHOD_NOT_FOUND)).await?; continue; };
                        if pending.len() >= MAX_IN_FLIGHT { send(&mut output, failure(error::BUSY)).await?; continue; }
                        let token = CancellationToken::new(); pending.insert(id.clone(), token.clone());
                        let epoch = epoch.clone();
                        jobs.push(Box::pin(async move {
                            // A timed-out archive may have committed. Retrying is idempotent;
                            // neither cancellation nor reconnect replays a new conversation.
                            let work = tokio::task::spawn_blocking(move || crate::session_history::request(store, method, request.params));
                            let result = tokio::select! {
                                _ = token.cancelled() => Err(error::CANCELLED),
                                result = timeout(Duration::from_millis(timeout_ms),work) => match result {
                                    Ok(Ok(Ok(value))) => Ok(value),
                                    Ok(Ok(Err(history::Error::Busy))) => Err(error::BUSY),
                                    Ok(Ok(Err(_))) => Err(error::INVALID_PARAMS),
                                    Ok(Err(_)) => Err(error::INTERNAL),
                                    Err(_) => Err(error::TIMEOUT),
                                }
                            };
                            let response = match result {
                                Ok(value) => Response::success(id.clone(),epoch,value),
                                Err(code) => Response::failure(Some(id.clone()),Some(epoch),code),
                            };
                            (id,response)
                        }));
                    }
                    Some(method @ (Method::Snapshot | Method::Replay)) => {
                        let Some(store) = store.clone() else {
                            send(&mut output, failure(error::METHOD_NOT_FOUND)).await?; continue;
                        };
                        let query = if method == Method::Replay {
                            match serde_json::from_value::<ReplayParams>(request.params) {
                                Ok(query) if (1..=100).contains(&query.limit) => Some(query),
                                _ => { send(&mut output, failure(error::INVALID_PARAMS)).await?; continue; }
                            }
                        } else { None };
                        if pending.len() >= MAX_IN_FLIGHT {
                            send(&mut output, failure(error::BUSY)).await?; continue;
                        }
                        let token = CancellationToken::new();
                        pending.insert(id.clone(), token.clone());
                        let epoch = epoch.clone();
                        jobs.push(Box::pin(async move {
                            let work = tokio::task::spawn_blocking(move || {
                                let value = match query {
                                    Some(query) => store.replay(&query).and_then(|v| serde_json::to_value(v).map_err(|_| history::Error::CorruptMetadata)),
                                    None => store.snapshot().and_then(|v| serde_json::to_value(v).map_err(|_| history::Error::CorruptMetadata)),
                                };
                                value.map_err(|error| match error {
                                    history::Error::InvalidCursor => error::INVALID_PARAMS,
                                    history::Error::Busy => error::BUSY,
                                    _ => error::INTERNAL,
                                })
                            });
                            let result = tokio::select! {
                                biased;
                                _ = token.cancelled() => Err(error::CANCELLED),
                                result = timeout(Duration::from_millis(timeout_ms), work) => match result {
                                    Ok(Ok(value)) => value,
                                    Ok(Err(_)) => Err(error::INTERNAL),
                                    Err(_) => Err(error::TIMEOUT),
                                }
                            };
                            let response = match result {
                                Ok(value) => Response::success(id.clone(), epoch, value),
                                Err(code) => Response::failure(Some(id.clone()), Some(epoch), code),
                            };
                            (id, response)
                        }));
                    }
                    Some(Method::Shutdown) => {
                        closing = true;
                        shutdown = Some(id);
                        for token in pending.values() { token.cancel(); }
                    }
                    Some(Method::Health) => {
                        let params: HealthParams = match serde_json::from_value(request.params) {
                            Ok(p) => p,
                            Err(_) => { send(&mut output, failure(error::INVALID_PARAMS)).await?; continue; }
                        };
                        if params.wait_ms > MAX_TIMEOUT_MS {
                            send(&mut output, failure(error::INVALID_PARAMS)).await?; continue;
                        }
                        if pending.len() >= MAX_IN_FLIGHT {
                            send(&mut output, failure(error::BUSY)).await?; continue;
                        }
                        let token = CancellationToken::new();
                        pending.insert(id.clone(), token.clone());
                        let epoch = epoch.clone();
                        let result = HealthResult { protocol_version: PROTOCOL_VERSION,
                            engine_version: env!("CARGO_PKG_VERSION").into(), project_id: host.project_id.clone() };
                        let now = Instant::now();
                        jobs.push(Box::pin(async move {
                            let code = tokio::select! {
                                biased;
                                _ = token.cancelled() => Some(error::CANCELLED),
                                _ = sleep_until(now + Duration::from_millis(timeout_ms)) => Some(error::TIMEOUT),
                                _ = sleep_until(now + Duration::from_millis(params.wait_ms)) => None,
                            };
                            let response = match code {
                                Some(code) => Response::failure(Some(id.clone()), Some(epoch), code),
                                None => Response::success(id.clone(), epoch, result),
                            };
                            (id, response)
                        }));
                    }
                    _ => send(&mut output, failure(error::METHOD_NOT_FOUND)).await?,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, DuplexStream, ReadHalf, WriteHalf};

    struct Client {
        input: BufReader<ReadHalf<DuplexStream>>,
        output: WriteHalf<DuplexStream>,
    }
    impl Client {
        async fn send(&mut self, value: Value) {
            self.output
                .write_all((value.to_string() + "\n").as_bytes())
                .await
                .unwrap();
        }
        async fn read(&mut self) -> Value {
            let mut line = String::new();
            let n = timeout(Duration::from_secs(2), self.input.read_line(&mut line))
                .await
                .unwrap()
                .unwrap();
            assert!(n > 0, "unexpected EOF");
            serde_json::from_str(&line).unwrap()
        }
        async fn initialize(&mut self) -> Value {
            self.send(json!({"jsonrpc":"2.0","id":"init","method":"engine.initialize","params":{
                "protocolVersion":1,"clientName":"Flow 日本語","clientVersion":"test","projectId":"project-test",
                "capabilities":{"futureFlag":true},"futureField":42}})).await;
            let reply = self.read().await;
            assert_eq!(reply["result"]["capabilities"]["persistentEvents"], false);
            assert_eq!(reply["result"]["epoch"], reply["epoch"]);
            reply["epoch"].clone()
        }
    }
    fn connection() -> (Client, tokio::task::JoinHandle<io::Result<()>>) {
        let (client, server) = tokio::io::duplex(4096);
        let (read, write) = tokio::io::split(server);
        let task = tokio::spawn(serve(read, write));
        let (read, write) = tokio::io::split(client);
        (
            Client {
                input: BufReader::new(read),
                output: write,
            },
            task,
        )
    }
    fn request(id: i64, method: &str, epoch: &Value, params: Value) -> Value {
        json!({"jsonrpc":"2.0","id":id,"method":method,"epoch":epoch,"params":params})
    }

    #[tokio::test]
    async fn blocked_history_stays_readable_and_disables_agent_sessions() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let data = temp.path().join("data");
        std::fs::create_dir(&project).unwrap();
        std::fs::create_dir(&data).unwrap();
        let store = history::HistoryStore::open(&project, &data).unwrap();
        store.set_storage_budget(1).unwrap();
        let (client, server) = tokio::io::duplex(4096);
        let (read, write) = tokio::io::split(server);
        let task = tokio::spawn(serve_with_store(read, write, Some(store)));
        let (read, write) = tokio::io::split(client);
        let mut c = Client {
            input: BufReader::new(read),
            output: write,
        };
        c.send(
            json!({"jsonrpc":"2.0","id":"init","method":"engine.initialize","params":{
            "protocolVersion":1,"clientName":"test","clientVersion":"test","projectId":"test"}}),
        )
        .await;
        let reply = c.read().await;
        assert_eq!(reply["result"]["capabilities"]["agentSessions"], false);
        assert_eq!(reply["result"]["capabilities"]["persistentEvents"], true);
        let epoch = reply["epoch"].clone();
        c.send(request(1, "state.snapshot", &epoch, json!({})))
            .await;
        let snapshot = c.read().await;
        assert_eq!(snapshot["result"]["state"]["sessions"], 0);
        assert_eq!(snapshot["result"]["state"]["history"]["writable"], false);
        c.send(request(3, "sessions.list", &epoch, json!({}))).await;
        assert_eq!(c.read().await["result"]["items"], json!([]));
        c.send(request(2, "engine.shutdown", &epoch, json!({})))
            .await;
        c.read().await;
        task.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn durable_snapshot_and_replay_survive_connection_restart() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let data = temp.path().join("data");
        std::fs::create_dir(&project).unwrap();
        std::fs::create_dir(project.join(".git")).unwrap();
        std::fs::create_dir(&data).unwrap();
        let store = history::HistoryStore::open(&project, &data).unwrap();
        store
            .register_workspace(
                MutationKey::try_from("register".to_owned()).unwrap(),
                history::Workspace {
                    id: WorkspaceId::try_from("workspace".to_owned()).unwrap(),
                    worktree_path: project.clone(),
                    git_common_dir: project.join(".git"),
                    base_commit: "a".repeat(40),
                    owner_token: "owner".into(),
                    lifecycle: "ready".into(),
                },
            )
            .unwrap();
        let mut high_water = None;
        let mut previous_epoch = Value::Null;
        for _ in 0..2 {
            let (client, server) = tokio::io::duplex(4096);
            let (read, write) = tokio::io::split(server);
            let task = tokio::spawn(serve_with_store(read, write, Some(store.clone())));
            let (read, write) = tokio::io::split(client);
            let mut c = Client {
                input: BufReader::new(read),
                output: write,
            };
            c.send(json!({"jsonrpc":"2.0","id":"init","method":"engine.initialize","params":{
                "protocolVersion":1,"clientName":"test","clientVersion":"test","projectId":"test"}})).await;
            let reply = c.read().await;
            assert_eq!(reply["result"]["capabilities"]["persistentEvents"], true);
            let epoch = reply["epoch"].clone();
            assert_ne!(epoch, previous_epoch);
            previous_epoch = epoch.clone();
            c.send(request(1, "state.snapshot", &epoch, json!({})))
                .await;
            let snapshot = c.read().await["result"].clone();
            assert_eq!(snapshot["state"]["workspaces"], 1);
            let cursor = snapshot["highWater"].clone();
            if let Some(old) = &high_water {
                assert_eq!(old, &cursor);
            }
            high_water = Some(cursor.clone());
            let mut start = cursor.clone();
            start["sequence"] = json!("0");
            c.send(request(
                2,
                "events.replay",
                &epoch,
                json!({"after":start,"limit":1}),
            ))
            .await;
            let replay = c.read().await;
            assert_eq!(replay["result"]["events"].as_array().unwrap().len(), 1);
            assert_eq!(replay["result"]["events"][0]["cursor"], cursor);
            assert_eq!(replay["result"]["hasMore"], false);
            start["storeId"] = json!("foreign");
            c.send(request(
                3,
                "events.replay",
                &epoch,
                json!({"after":start,"limit":100}),
            ))
            .await;
            assert_eq!(c.read().await["error"]["code"], error::INVALID_PARAMS);
            c.send(request(4, "engine.shutdown", &epoch, json!({})))
                .await;
            assert!(c.read().await["result"].is_null());
            task.await.unwrap().unwrap();
        }
    }

    #[tokio::test]
    async fn framing_errors_forward_compatibility_and_shutdown() {
        let (mut c, task) = connection();
        // Split/coalesced frames and CRLF all use one standard codec.
        c.output
            .write_all(b"{not JSON}\n{\"jsonrpc\":\"2.0\",\"id\":1,")
            .await
            .unwrap();
        c.output
            .write_all(b"\"method\":\"engine.health\"}\r\n")
            .await
            .unwrap();
        assert_eq!(c.read().await["error"]["code"], error::PARSE);
        assert_eq!(c.read().await["error"]["code"], error::NOT_INITIALIZED);
        let epoch = c.initialize().await;
        c.send(request(2, "engine.health", &json!("old-epoch"), json!({})))
            .await;
        assert_eq!(c.read().await["error"]["code"], error::STALE_EPOCH);
        c.send(request(3, "future.method", &epoch, json!({}))).await;
        assert_eq!(c.read().await["error"]["code"], error::METHOD_NOT_FOUND);
        c.send(json!({"jsonrpc":"2.0","method":"future.notification","params":{},"epoch":epoch}))
            .await;
        c.send(request(4, "engine.health", &epoch, json!({"future":true})))
            .await;
        let health = c.read().await;
        assert_eq!(health["id"], 4);
        assert_eq!(health["result"]["projectId"], "project-test");
        c.send(request(5, "engine.shutdown", &epoch, json!({})))
            .await;
        assert_eq!(c.read().await["result"], Value::Null);
        assert!(task.await.unwrap().is_ok());
    }

    #[tokio::test]
    async fn cancellation_deadlines_and_late_cancels() {
        let (mut c, task) = connection();
        let epoch = c.initialize().await;
        c.send(request(1, "engine.health", &epoch, json!({"waitMs":30000})))
            .await;
        c.send(json!({"jsonrpc":"2.0","method":"requests.cancel","epoch":epoch,"params":{"id":1}}))
            .await;
        assert_eq!(c.read().await["error"]["code"], error::CANCELLED);
        let mut delayed = request(2, "engine.health", &epoch, json!({"waitMs":30000}));
        delayed["timeoutMs"] = json!(5);
        c.send(delayed).await;
        assert_eq!(c.read().await["error"]["code"], error::TIMEOUT);
        c.send(json!({"jsonrpc":"2.0","method":"requests.cancel","epoch":epoch,"params":{"id":2}}))
            .await;
        c.send(request(3, "engine.health", &epoch, json!({}))).await;
        assert_eq!(c.read().await["id"], 3);
        c.output.shutdown().await.unwrap();
        assert!(task.await.unwrap().is_ok());
    }

    #[tokio::test]
    async fn bad_handshake_can_retry_and_invalid_envelopes_are_redacted() {
        let (mut c, task) = connection();
        c.send(json!({"jsonrpc":"2.0","id":0,"method":"engine.initialize","params":{
            "protocolVersion":999,"clientName":"secret-token","clientVersion":"1","projectId":"p"}})).await;
        let reply = c.read().await;
        assert_eq!(reply["error"]["code"], error::INCOMPATIBLE_VERSION);
        assert!(!reply.to_string().contains("secret-token"));
        let epoch = c.initialize().await;
        for (id, params) in [
            (1, json!({"waitMs":30001})),
            (2, json!({"waitMs":-1})),
            (3, json!([])),
        ] {
            c.send(request(id, "engine.health", &epoch, params)).await;
            assert_eq!(c.read().await["error"]["code"], error::INVALID_PARAMS);
        }
        for bad in [
            json!([]),
            json!({"jsonrpc":"1.0","id":4,"method":"engine.health"}),
            json!({"jsonrpc":"2.0","id":null,"method":"engine.health"}),
            json!({"jsonrpc":"2.0","id":9007199254740992i64,"method":"engine.health"}),
        ] {
            c.send(bad).await;
            assert_eq!(c.read().await["error"]["code"], error::INVALID_REQUEST);
        }
        drop(c);
        assert!(task.await.unwrap().is_ok());
    }

    #[tokio::test]
    async fn eof_cancels_inflight_and_accepts_complete_last_frame() {
        let (mut c, task) = connection();
        let epoch = c.initialize().await;
        let frame = request(1, "engine.health", &epoch, json!({"waitMs":30000})).to_string();
        c.output.write_all(frame.as_bytes()).await.unwrap(); // no final LF
        c.output.shutdown().await.unwrap();
        assert_eq!(c.read().await["error"]["code"], error::CANCELLED);
        assert!(task.await.unwrap().is_ok());
    }

    #[tokio::test]
    async fn oversized_or_invalid_utf8_frames_close_the_connection() {
        for bytes in [vec![b'x'; MAX_FRAME_BYTES + 2], vec![0xff, b'\n']] {
            let (mut c, task) = connection();
            let _ = c.output.write_all(&bytes).await;
            assert_eq!(c.read().await["error"]["code"], error::INVALID_REQUEST);
            assert!(task.await.unwrap().is_err());
        }
    }

    #[tokio::test]
    async fn duplicate_ids_close_without_confusing_the_original_response() {
        for malformed in [false, true] {
            let (mut c, task) = connection();
            let epoch = c.initialize().await;
            c.send(request(1, "engine.health", &epoch, json!({"waitMs":30000})))
                .await;
            let mut duplicate = request(1, "engine.health", &epoch, json!({}));
            if malformed {
                duplicate["timeoutMs"] = json!("invalid timeout");
            }
            c.send(duplicate).await;
            let error = c.read().await;
            assert_eq!(error["id"], Value::Null);
            assert_eq!(error["error"]["code"], error::DUPLICATE_ID);
            let original = c.read().await;
            assert_eq!(original["id"], 1);
            assert_eq!(original["error"]["code"], error::CANCELLED);
            assert!(task.await.unwrap().is_ok());
        }
    }

    #[tokio::test]
    async fn inflight_capacity_is_bounded_but_shutdown_remains_available() {
        let (mut c, task) = connection();
        let epoch = c.initialize().await;
        for id in 0..=MAX_IN_FLIGHT {
            c.send(request(
                id as i64,
                "engine.health",
                &epoch,
                json!({"waitMs":30000}),
            ))
            .await;
        }
        assert_eq!(c.read().await["error"]["code"], error::BUSY);
        c.send(request(100, "engine.shutdown", &epoch, json!({})))
            .await;
        for _ in 0..MAX_IN_FLIGHT {
            assert_eq!(c.read().await["error"]["code"], error::CANCELLED);
        }
        assert_eq!(c.read().await["id"], 100);
        assert!(task.await.unwrap().is_ok());
    }
}
