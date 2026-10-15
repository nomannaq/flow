//! Stable ACP SDK client. Flow owns policy/state, the SDK owns messaging and
//! adapter process groups. No client filesystem/terminal capabilities advertised.
use crate::recorder::{Boundary, WorkspaceRecorder};
use crate::worktrees::{BaselineChoice, Recovery, WorktreeManager};
use agent_client_protocol::schema::{ProtocolVersion, v1 as acp};
use agent_client_protocol::{
    AcpAgent, AcpAgentConfig, Agent, Client, ConnectTo, ConnectionTo, Error,
};
use history::{HistoryStore, Session, Turn};
use protocol::{
    AgentActivity, AgentAuthenticate, AgentCommand, AgentPermission, AgentStart, AgentView,
    MutationKey, PermissionChoice, PermissionDecision, ProtocolStore, SessionId,
};
use serde_json::json;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    sync::{mpsc, oneshot, watch},
    task::JoinHandle,
};

const CANCEL_GRACE: Duration = Duration::from_secs(5);
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);
const TURN_TIMEOUT: Duration = Duration::from_secs(30 * 60);
pub async fn baseline(store: HistoryStore) -> Result<String, Error> {
    tokio::task::spawn_blocking(move || {
        let snapshot = store.snapshot().map_err(|_| invalid())?;
        let root = snapshot.state["projectRoot"].as_str().ok_or_else(invalid)?;
        crate::worktrees::Repository::discover(Path::new(root))
            .map(|repo| repo.head)
            .map_err(|_| invalid())
    })
    .await
    .map_err(|_| Error::internal_error())?
}
pub const AGENT_ENV: &[&str] = &[
    "HOME",
    "PATH",
    "TMPDIR",
    "USERPROFILE",
    "LOCALAPPDATA",
    "APPDATA",
    "SYSTEMROOT",
    "WINDIR",
    "TEMP",
    "TMP",
    "PATHEXT",
    "COMSPEC",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "PROGRAMDATA",
    "LANG",
    "LC_ALL",
    "TERM",
    "CODEX_HOME",
    "CODEX_SANDBOX",
    "CODEX_SANDBOX_NETWORK_DISABLED",
    "CODEX_API_KEY",
    "OPENAI_API_KEY",
    "SSL_CERT_FILE",
    "SSL_CERT_DIR",
    "HTTPS_PROXY",
    "HTTP_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "__CF_USER_TEXT_ENCODING",
];

/// Re-exec launcher preserves SDK child/process-group ownership while supplying
/// cwd and an explicit environment. Called only through flow-engine's private mode.
pub async fn launch(arguments: impl Iterator<Item = std::ffi::OsString>) -> std::io::Error {
    #[cfg(unix)]
    use std::os::unix::process::CommandExt;
    let mut arguments = arguments;
    let Some(workspace) = arguments.next() else {
        return std::io::Error::other("missing workspace");
    };
    let Some(executable) = arguments.next() else {
        return std::io::Error::other("missing adapter");
    };
    if crate::maintenance::launched_process(Path::new(&workspace)).is_err() {
        return std::io::Error::other("cannot persist adapter process ownership");
    }
    let mut command = std::process::Command::new(executable);
    command
        .args(arguments)
        .current_dir(workspace)
        .env_clear()
        .envs(
            AGENT_ENV
                .iter()
                .filter_map(|name| std::env::var_os(name).map(|value| (*name, value))),
        );
    #[cfg(unix)]
    {
        command.exec()
    }
    #[cfg(windows)]
    {
        use process_wrap::tokio::{CommandWrap, CreationFlags, JobObject, KillOnDrop};
        // The SDK owns this launcher. If it is terminated, Windows closes the
        // job handle and terminates the adapter and all non-escaped descendants.
        // Assignment happens while suspended, before the adapter can spawn.
        let mut wrapped = CommandWrap::from(tokio::process::Command::from(command));
        let mut flags = CreationFlags(Default::default());
        flags.0.0 = 0x08000000; // CREATE_NO_WINDOW
        wrapped.wrap(flags).wrap(KillOnDrop).wrap(JobObject);
        let mut child = match wrapped.spawn() {
            Ok(child) => child,
            Err(error) => return error,
        };
        match child.wait().await {
            Ok(status) => std::process::exit(status.code().unwrap_or(1)),
            Err(error) => error,
        }
    }
}
fn id(prefix: &str) -> Result<String, Error> {
    let mut bytes = [0; 16];
    getrandom::fill(&mut bytes).map_err(|_| Error::internal_error())?;
    Ok(format!(
        "{prefix}-{}",
        bytes.iter().map(|b| format!("{b:02x}")).collect::<String>()
    ))
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn limited(s: &str, max: usize) -> String {
    let mut chars = s.chars();
    let mut value: String = chars.by_ref().take(max).collect();
    if chars.next().is_some() {
        value.push_str("… [truncated]");
    }
    value
}
fn invalid() -> Error {
    Error::invalid_params()
}
async fn database<T: Send + 'static>(
    f: impl FnOnce() -> history::Result<T> + Send + 'static,
) -> Result<T, Error> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|_| Error::internal_error())?
        .map_err(|_| Error::internal_error())
}
struct PendingPermission {
    options: Vec<String>,
    reply: oneshot::Sender<Option<String>>,
}
#[derive(Default)]
struct State {
    view: AgentView,
    pending: HashMap<String, PendingPermission>,
    turn: Option<String>,
    events: usize,
    bound: bool,
    capture_failed: bool,
}
struct Shared {
    state: Mutex<State>,
    store: HistoryStore,
    recorder: Arc<tokio::sync::Mutex<Option<WorkspaceRecorder>>>,
}
impl Shared {
    async fn record(&self, boundary: Boundary) -> Result<(), Error> {
        // The blocking job owns the guard even if an ACP callback is cancelled.
        // Final reconciliation waits behind it before the workspace lease drops.
        let mut recorder = self.recorder.clone().lock_owned().await;
        let result = tokio::task::spawn_blocking(move || {
            recorder.as_mut().map(|r| r.reconcile(boundary)).transpose()
        })
        .await
        .map_err(|_| Error::internal_error())?;
        match result {
            Ok(Some(Some(id))) => {
                self.state.lock().unwrap().view.recorded_operations += 1;
                self.event(
                    "workspace.recorded",
                    &format!("Verified filesystem interval {id}; writer quiescence is unproven"),
                    None,
                    None,
                )
                .await
            }
            Ok(_) => Ok(()),
            Err(_) => {
                self.state.lock().unwrap().capture_failed = true;
                let _ = self.event("workspace.captureIncomplete", "Workspace is unstable, exceeds capture limits, or could not be recorded. Last verified checkpoint retained; safe application is unavailable.", None, None).await;
                Err(Error::internal_error())
            }
        }
    }
    fn view(&self) -> AgentView {
        self.state.lock().unwrap().view.clone()
    }
    fn clear_permissions(&self) {
        let mut state = self.state.lock().unwrap();
        state.pending.clear(); // dropped oneshots yield cancelled outcomes
        state.view.permissions.clear();
    }
    async fn event(
        &self,
        kind: &str,
        text: &str,
        tool: Option<String>,
        status: Option<&str>,
    ) -> Result<(), Error> {
        let (session, turn, status, activity, bound) = {
            let mut state = self.state.lock().unwrap();
            state.events += 1;
            if state.events > 8192 && !matches!(kind, "session.error" | "session.closed") {
                return Err(Error::internal_error());
            }
            if let Some(status) = status {
                state.view.status = status.into();
            }
            let activity = AgentActivity {
                kind: kind.into(),
                text: limited(text, 2048),
                tool_call_id: tool.map(|s| limited(&s, 256)),
            };
            project_activity(&mut state.view.activity, activity.clone());
            (
                state.view.session_id.clone().ok_or_else(invalid)?,
                state.turn.clone(),
                state.view.status.clone(),
                activity,
                state.bound,
            )
        };
        if bound {
            let store = self.store.clone();
            let kind = kind.to_owned();
            database(move || store.agent_event(session, turn, status, kind, json!(activity)))
                .await?;
        }
        Ok(())
    }
}
enum Control {
    Prompt(String),
    Authenticate(String),
    Cancel,
}

/// One live writing session per engine/project. Commands are accepted quickly;
/// the host polls authoritative state while setup/prompts continue asynchronously.
pub struct AgentHost {
    shared: Arc<Shared>,
    controls: Option<mpsc::Sender<Control>>,
    stop: Option<watch::Sender<bool>>,
    task: Option<JoinHandle<()>>,
}
impl AgentHost {
    pub fn new(store: HistoryStore) -> Self {
        Self {
            shared: Arc::new(Shared {
                state: Mutex::new(State::default()),
                store,
                recorder: Arc::new(tokio::sync::Mutex::new(None)),
            }),
            controls: None,
            stop: None,
            task: None,
        }
    }
    pub fn view(&self) -> AgentView {
        self.shared.view()
    }
    pub fn start(&mut self, params: AgentStart) -> Result<SessionId, Error> {
        if self.task.as_ref().is_some_and(|task| !task.is_finished()) {
            return Err(invalid());
        }
        if !Path::new(&params.executable).is_absolute()
            || params.executable.len() > 4096
            || params.arguments.len() > 32
            || params
                .arguments
                .iter()
                .any(|a| a.len() > 4096 || a.contains('\0'))
            || params.prompt.is_empty()
            || params.prompt.len() > 32 * 1024
            || if params.source_session_id.is_some() {
                !params.writers_stopped || !params.commit.is_empty()
            } else {
                ![40, 64].contains(&params.commit.len())
                    || !params.commit.bytes().all(|c| c.is_ascii_hexdigit())
            }
            || params.auth_method.as_ref().is_some_and(|v| v.len() > 256)
        {
            return Err(invalid());
        }
        let session = SessionId::try_from(id("session")?).map_err(|_| invalid())?;
        *self.shared.state.lock().unwrap() = State {
            view: AgentView {
                session_id: Some(session.clone()),
                status: "starting".into(),
                ..AgentView::default()
            },
            ..State::default()
        };
        let (tx, rx) = mpsc::channel(8);
        let (stop, stopped) = watch::channel(false);
        self.controls = Some(tx);
        self.stop = Some(stop);
        let shared = self.shared.clone();
        self.task = Some(tokio::spawn(async move {
            run_session(shared, params, rx, stopped).await;
        }));
        Ok(session)
    }
    pub fn command(&self, command: AgentCommand, cancel: bool) -> Result<(), Error> {
        let mut state = self.shared.state.lock().unwrap();
        if state.view.session_id.as_ref() != Some(&command.session_id) {
            return Err(invalid());
        }
        let control = if cancel {
            if state.view.status != "running" {
                return Err(invalid());
            }
            Control::Cancel
        } else {
            if state.view.status != "ready"
                || command.prompt.is_empty()
                || command.prompt.len() > 32 * 1024
            {
                return Err(invalid());
            }
            Control::Prompt(command.prompt)
        };
        self.controls
            .as_ref()
            .ok_or_else(invalid)?
            .try_send(control)
            .map_err(|_| invalid())?;
        state.view.status = if cancel { "cancelling" } else { "running" }.into();
        Ok(())
    }
    pub fn authenticate(&self, command: AgentAuthenticate) -> Result<(), Error> {
        let mut state = self.shared.state.lock().unwrap();
        if state.view.session_id.as_ref() != Some(&command.session_id)
            || state.view.status != "authenticationRequired"
            || !state.view.auth_methods.contains(&command.method_id)
        {
            return Err(invalid());
        }
        self.controls
            .as_ref()
            .ok_or_else(invalid)?
            .try_send(Control::Authenticate(command.method_id))
            .map_err(|_| invalid())?;
        state.view.status = "authenticating".into();
        Ok(())
    }
    pub fn close(&self, session: &SessionId) -> Result<(), Error> {
        if self.view().session_id.as_ref() != Some(session) {
            return Err(invalid());
        }
        if self.task.as_ref().is_none_or(|task| task.is_finished()) {
            return Ok(());
        }
        self.shared.state.lock().unwrap().view.status = "closing".into();
        self.shared.clear_permissions();
        self.stop
            .as_ref()
            .ok_or_else(invalid)?
            .send(true)
            .map_err(|_| invalid())
    }
    pub fn permission(&self, decision: PermissionDecision) -> Result<(), Error> {
        let mut state = self.shared.state.lock().unwrap();
        if state.view.session_id.as_ref() != Some(&decision.session_id)
            || state.view.status != "running"
        {
            return Err(invalid());
        }
        let pending = state
            .pending
            .get(&decision.request_id)
            .ok_or_else(invalid)?;
        if decision
            .option_id
            .as_ref()
            .is_some_and(|id| !pending.options.contains(id))
        {
            return Err(invalid());
        }
        let pending = state.pending.remove(&decision.request_id).unwrap();
        state
            .view
            .permissions
            .retain(|p| p.request_id != decision.request_id);
        pending
            .reply
            .send(decision.option_id)
            .map_err(|_| invalid())
    }
    pub async fn shutdown(&mut self) {
        if let Some(stop) = &self.stop {
            let _ = stop.send(true);
        }
        self.shared.clear_permissions();
        if let Some(task) = self.task.take() {
            let _ = task.await;
        }
    }
}
impl Drop for AgentHost {
    fn drop(&mut self) {
        if let Some(stop) = &self.stop {
            let _ = stop.send(true);
        }
        self.shared.clear_permissions();
    }
}

async fn run_session(
    shared: Arc<Shared>,
    params: AgentStart,
    controls: mpsc::Receiver<Control>,
    mut stop: watch::Receiver<bool>,
) {
    let store = shared.store.clone();
    let commit = params.commit.clone();
    let source = params.source_session_id.clone();
    let executable = PathBuf::from(&params.executable);
    let prepared = tokio::task::spawn_blocking(move || {
        let meta =
            std::fs::metadata(&executable).map_err(|_| "Adapter executable is unavailable")?;
        #[cfg(unix)]
        let runnable = {
            use std::os::unix::fs::PermissionsExt;
            meta.permissions().mode() & 0o111 != 0
        };
        #[cfg(windows)]
        let runnable = executable.extension().is_some_and(|e| e.eq_ignore_ascii_case("exe"));
        if !meta.is_file() || !runnable {
            return Err("Select an executable ACP adapter");
        }
        let root = store
            .snapshot()
            .map_err(|_| "Cannot read project history")?
            .state["projectRoot"]
            .as_str()
            .ok_or("Project root missing")?
            .to_owned();
        let manager = WorktreeManager::open(Path::new(&root), store.clone(), Recovery::RefuseStale)
            .map_err(|_| "Workspace ownership unavailable; inspect B07 recovery before retrying")?;
        if let Some(source) = source {
            crate::maintenance::continue_workspace(&store, &manager, source)
                .map_err(|_| "Retained workspace is active, changed, or needs recovery. Close or recover it and verify all writers stopped before retrying.")
        } else {
            let prepared = manager.prepare(BaselineChoice::CleanCommit(commit))
                .map_err(|_| "Cannot prepare baseline; inspect retained worktree and repository constraints")?;
            let baseline = prepared.preparation.baseline.clone().unwrap();
            Ok((prepared, baseline))
        }
    })
    .await;
    let (prepared, baseline) = match prepared {
        Ok(Ok(prepared)) => prepared,
        Ok(Err(message)) => {
            let _ = shared
                .event("session.error", message, None, Some("failed"))
                .await;
            return;
        }
        _ => {
            let _ = shared
                .event(
                    "session.error",
                    "Workspace preparation failed",
                    None,
                    Some("failed"),
                )
                .await;
            return;
        }
    };
    let workspace = prepared.preparation.workspace.clone();
    let session = shared.view().session_id.unwrap();
    shared.state.lock().unwrap().view.workspace =
        Some(workspace.worktree_path.to_string_lossy().into());
    let record = Session {
        id: session.clone(),
        workspace_id: workspace.id.clone(),
        baseline,
        agent: "acp".into(),
        adapter_version: "initializing".into(),
        upstream_id: None,
        capabilities: json!({}),
        status: "starting".into(),
        created_at_ms: now(),
    };
    let store = shared.store.clone();
    let key = MutationKey::try_from(format!("{}-create", session.as_str())).unwrap();
    if database(move || store.create_session(key, record))
        .await
        .is_err()
    {
        let _ = shared
            .event(
                "session.error",
                "Cannot persist session",
                None,
                Some("failed"),
            )
            .await;
        return;
    }
    shared.state.lock().unwrap().bound = true;
    if let Some(source) = &params.source_session_id {
        let source = source.clone();
        let store = shared.store.clone();
        let target = session.clone();
        if database(move || store.agent_event(target, None, "starting".into(), "session.continued".into(), json!({"sourceSessionId":source,"newConversation":true,"writersStoppedAcknowledged":true}))).await.is_err() {
            let _ = shared.event("session.error", "Cannot persist conversation provenance", None, Some("failed")).await;
            return;
        }
    }
    let mut process_prepared = false;
    let result = async {
        let store = shared.store.clone();
        let recorded_session = session.clone();
        let recorder = tokio::task::spawn_blocking(move || WorkspaceRecorder::new(store, recorded_session))
            .await.map_err(|_| Error::internal_error())?.map_err(|_| Error::internal_error())?;
        *shared.recorder.lock().await = Some(recorder);
        shared.record(Boundary::Start).await?;
        if *stop.borrow() {
            return Ok(());
        }
        process_prepared = true;
        let owned = workspace.clone();
        tokio::task::spawn_blocking(move || crate::maintenance::preparing_process(&owned)).await.map_err(|_|Error::internal_error())?.map_err(|_|Error::internal_error())?;
        let engine = std::env::current_exe().map_err(|_| Error::internal_error())?;
        let agent = AcpAgent::new(
            AcpAgentConfig::new(engine)
                .args([
                    "--agent-launch".to_owned(),
                    workspace.worktree_path.to_string_lossy().into_owned(),
                    params.executable.clone(),
                ])
                .args(params.arguments.clone()),
        );
        let client = drive(
            agent,
            shared.clone(),
            workspace.worktree_path.clone(),
            params,
            controls,
        );
        tokio::pin!(client);
        loop {
            tokio::select! {
                result=&mut client=>break result,
                _=stop.changed()=>{
                    shared.clear_permissions();
                    let _ = shared.event("session.closing", "Stopping the agent and reconciling its workspace", None, Some("closing")).await;
                    // Dropping the SDK connection terminates its Unix process group.
                    break Ok(());
                }
                _=tokio::time::sleep(Duration::from_millis(100))=>{
                    if shared.state.lock().unwrap().capture_failed {break Err(Error::internal_error());}
                }
            }
        }
    }
    .await;
    if let Err(error) = &result {
        let _ = shared
            .event("session.diagnostic", diagnostic(error), None, None)
            .await;
    }
    let owned = workspace.clone();
    let released = !process_prepared
        || tokio::task::spawn_blocking(move || crate::maintenance::released_process(&owned))
            .await
            .is_ok_and(|r| r.is_ok());
    if !released {
        prepared.quarantine();
    }
    shared.clear_permissions();
    let mut failed = !released || result.is_err() || shared.state.lock().unwrap().capture_failed;
    let interrupted = !failed && shared.state.lock().unwrap().turn.is_some();
    // The SDK connection has dropped and killed its process group. Observe any
    // final/failed/background effects before releasing writer ownership.
    if shared
        .record(Boundary::Closed(if failed {
            history::OperationStatus::Failed
        } else if interrupted {
            history::OperationStatus::Interrupted
        } else {
            history::OperationStatus::Completed
        }))
        .await
        .is_err()
    {
        failed = true;
    }
    *shared.recorder.lock().await = None;
    let _=shared.event(if failed {"session.error"} else {"session.closed"},
        if !released {"Adapter shutdown could not be verified. Writer ownership is retained for explicit history recovery; do not start another writer until all previous processes are stopped."} else if failed {"Agent connection failed or timed out. Check native login, model support and adapter configuration."} else {"Session closed; workspace retained"},None,
        Some(if failed {"failed"} else if interrupted {"interrupted"} else {"closed"})).await;
    // PreparedWorktree remains alive through SDK connection teardown.
    drop(prepared);
}

async fn drive<A: ConnectTo<Client>>(
    agent: A,
    shared: Arc<Shared>,
    workspace: PathBuf,
    params: AgentStart,
    mut commands: mpsc::Receiver<Control>,
) -> Result<(), Error> {
    let updates = shared.clone();
    let permissions = shared.clone();
    Client.builder()
        .on_receive_notification(async move |notification:acp::SessionNotification,_cx| {
            {
                let mut state=updates.state.lock().unwrap();
                let upstream=notification.session_id.to_string();
                if upstream.len()>256 { return Err(invalid()); }
                if state.view.upstream_session_id.is_none() && state.view.status=="starting" {
                    state.view.upstream_session_id=Some(upstream.clone());
                }
                if state.view.upstream_session_id.as_deref()!=Some(&upstream) { return Err(invalid()); }
            }
            let value=serde_json::to_value(notification.update).map_err(|_| Error::internal_error())?;
            let native_kind=value["sessionUpdate"].as_str().unwrap_or("unknown");
            let kind=match native_kind { "agent_message_chunk"=>"message.delta", "tool_call"=>"operation.started", "tool_call_update" if matches!(value["status"].as_str(),Some("completed"|"failed"))=>"operation.finished", "tool_call_update"=>"operation.updated", _=>"agent.update" };
            let text=value["content"]["text"].as_str().or(value["title"].as_str()).unwrap_or(native_kind);
            let text=if let Some(status)=value["status"].as_str() { format!("{text} ({status})") } else {text.to_owned()};
            let tool=value["toolCallId"].as_str().map(str::to_owned);
            updates.event(kind,&text,tool.clone(),None).await?;
            if matches!(native_kind, "tool_call" | "tool_call_update") && let Some(id) = tool.clone() {
                updates.record(Boundary::Tool { id, terminal: matches!(value["status"].as_str(), Some("completed" | "failed")), failed: value["status"] == "failed" }).await?;
            }
            if value["content"].as_array().is_some_and(|items| items.iter().any(|v| v["type"]=="diff")) {
                updates.event("workspace.changeReported","Agent reported a diff; filesystem effect is not yet verified",tool,None).await?;
            }
            Ok(())
        },agent_client_protocol::on_receive_notification!())
        .on_receive_request(async move |request:acp::RequestPermissionRequest,responder,_cx| {
            if permissions.view().upstream_session_id.as_deref()!=Some(&request.session_id.to_string()) { return Err(invalid()); }
            let request_id=id("permission")?;
            let options:Vec<_>=request.options.iter().filter(|p| p.kind==acp::PermissionOptionKind::AllowOnce || p.kind==acp::PermissionOptionKind::RejectOnce)
                .filter(|p| p.option_id.to_string().len()<=128).take(8).map(|p| PermissionChoice{allow_once:p.kind==acp::PermissionOptionKind::AllowOnce,id:p.option_id.to_string(),name:limited(&p.name,128)}).collect();
            let tool=serde_json::to_value(&request.tool_call).map_err(|_| invalid())?;
            let title=limited(&format!("{}{}",tool["title"].as_str().unwrap_or("Agent requests permission"),
                tool.get("rawInput").filter(|v| !v.is_null()).map(|v| format!("\n{v}")).unwrap_or_default()),2048);
            let (tx,rx)=oneshot::channel();
            {
                let mut state=permissions.state.lock().unwrap();
                if state.pending.len()>=8 || state.view.status!="running" { return responder.respond(acp::RequestPermissionResponse::new(acp::RequestPermissionOutcome::Cancelled)); }
                state.pending.insert(request_id.clone(),PendingPermission { options:options.iter().map(|p| p.id.clone()).collect(),reply:tx });
                state.view.permissions.push(AgentPermission{request_id:request_id.clone(),title:title.clone(),tool_call_id:request.tool_call.tool_call_id.to_string(),options});
            }
            permissions.event("permission.requested",&title,Some(request.tool_call.tool_call_id.to_string()),None).await?;
            let selected=tokio::time::timeout(Duration::from_secs(300),rx).await.ok().and_then(Result::ok).flatten();
            {
                let mut state=permissions.state.lock().unwrap();
                state.pending.remove(&request_id); state.view.permissions.retain(|p| p.request_id!=request_id);
            }
            let outcome=match selected { Some(option)=>acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(option)),None=>acp::RequestPermissionOutcome::Cancelled };
            permissions.event("permission.resolved",if matches!(outcome,acp::RequestPermissionOutcome::Selected(_)) {"Selected one-time adapter option"} else {"Permission denied or expired"},None,None).await?;
            responder.respond(acp::RequestPermissionResponse::new(outcome))
        },agent_client_protocol::on_receive_request!())
        .connect_with(agent,move |connection:ConnectionTo<Agent>| async move {
            let initialized=tokio::time::timeout(SETUP_TIMEOUT,
                connection.send_request(acp::InitializeRequest::new(ProtocolVersion::V1)).block_task())
                .await.map_err(|_| Error::internal_error())??;
            if initialized.protocol_version!=ProtocolVersion::V1 { return Err(invalid()); }
            let capabilities=serde_json::to_value(&initialized.agent_capabilities).map_err(|_| invalid())?;
            let info=serde_json::to_value(&initialized.agent_info).map_err(|_| invalid())?;
            // Terminal authentication needs a separate host capability we do not advertise.
            let auth:Vec<String>=initialized.auth_methods.iter().filter(|m| matches!(m, acp::AuthMethod::Agent(_))).map(|m| m.id().to_string()).collect();
            if serde_json::to_vec(&capabilities).map_err(|_| invalid())?.len()>8192 || serde_json::to_vec(&info).map_err(|_| invalid())?.len()>4096 || auth.len()>32 || auth.iter().any(|s| s.len()>256) {return Err(invalid());}
            let compatibility = compatibility(&info);
            { let mut state=shared.state.lock().unwrap(); state.view.capabilities=capabilities;state.view.agent_info=info;state.view.auth_methods=auth.clone(); state.view.compatibility=compatibility.into(); }
            shared.event("adapter.compatibility", compatibility, None, None).await?;
            let mut method = params.auth_method;
            let upstream = loop {
                let result = async {
                    if let Some(method) = method.take() {
                        if !auth.contains(&method) { return Err(invalid()); }
                        shared.event("session.authenticating", "Complete authentication in the adapter's native login flow. Flow does not collect credentials.", None, Some("authenticating")).await?;
                        connection.send_request(acp::AuthenticateRequest::new(method)).block_task().await?;
                    }
                    shared.state.lock().unwrap().view.status = "starting".into();
                    connection.send_request(acp::NewSessionRequest::new(workspace.clone())).block_task().await
                };
                let result = tokio::time::timeout(Duration::from_secs(120), result).await.map_err(|_| Error::internal_error())?;
                match result {
                    Ok(session) => break session.session_id,
                    Err(error) if error.code == Error::auth_required().code && !auth.is_empty() => {
                        shared.event("session.authenticationRequired", "Authentication required or rejected. Select an advertised method and retry, or close this session.", None, Some("authenticationRequired")).await?;
                        match tokio::time::timeout(Duration::from_secs(300), commands.recv()).await {
                            Ok(Some(Control::Authenticate(next))) => method=Some(next),
                            _ => return Err(Error::auth_required()),
                        }
                    }
                    Err(error) => return Err(error),
                }
            };
            let upstream_text=upstream.to_string();
            {
                let mut state=shared.state.lock().unwrap();
                if upstream_text.len()>256 || state.view.upstream_session_id.as_ref().is_some_and(|id| id!=&upstream_text) { return Err(invalid()); }
                state.view.upstream_session_id=Some(upstream_text.clone());
            }
            let view=shared.view(); let store=shared.store.clone();
            database(move || store.bind_agent(view.session_id.unwrap(),upstream_text,view.agent_info["version"].as_str().unwrap_or("unknown").to_owned(),json!({"agent":view.agent_info,"capabilities":view.capabilities,"authMethods":view.auth_methods}))).await?;
            let mut prompt=params.prompt;
            loop {
                let turn=id("turn")?; let store=shared.store.clone(); let session=shared.view().session_id.unwrap();
                let record=Turn{id:turn.clone(),session_id:session,prompt_provenance:"explicit IDE prompt".into(),upstream_id:None,status:"running".into(),created_at_ms:now()};
                let key=MutationKey::try_from(format!("{turn}-create")).unwrap();
                database(move || store.create_turn(key,record)).await?;
                shared.state.lock().unwrap().turn=Some(turn.clone());
                shared.record(Boundary::TurnStart(turn)).await?;
                shared.event("turn.started",&prompt,None,Some("running")).await?;
                let response=connection.send_request(acp::PromptRequest::new(upstream.clone(),vec![acp::ContentBlock::Text(acp::TextContent::new(prompt))])).block_task();
                tokio::pin!(response);
                let result=tokio::select! {
                    result=tokio::time::timeout(TURN_TIMEOUT,&mut response)=>result.map_err(|_| Error::internal_error())?,
                    command=commands.recv()=>{
                        if !matches!(command,Some(Control::Cancel)) { return Err(invalid()); }
                        shared.clear_permissions();
                        connection.send_notification(acp::CancelNotification::new(upstream.clone()))?;
                        shared.event("turn.cancelling","Cancellation requested",None,Some("cancelling")).await?;
                        let result=tokio::time::timeout(CANCEL_GRACE,&mut response).await.map_err(|_| Error::internal_error())??;
                        if result.stop_reason!=acp::StopReason::Cancelled { return Err(Error::internal_error()); }
                        Ok(result)
                    }
                }?;
                shared.clear_permissions();
                let cancelled=result.stop_reason==acp::StopReason::Cancelled;
                shared.record(Boundary::TurnEnd(if cancelled {history::OperationStatus::Interrupted} else {history::OperationStatus::Completed})).await?;
                shared.event("turn.completed",if cancelled {"Turn cancelled; partial effects reconciled"} else {"Turn finished; observed changes recorded, not applied. Background writers may still be active."},None,Some(if cancelled {"cancelled"} else {"completed"})).await?;
                shared.state.lock().unwrap().turn=None;
                shared.event("session.ready","Ready for a follow-up",None,Some("ready")).await?;
                loop {
                    tokio::select! {
                        command=commands.recv()=>match command { Some(Control::Prompt(next))=>{prompt=next;break},_=>return Ok(()) },
                        _=tokio::time::sleep(Duration::from_secs(2))=>shared.record(Boundary::Idle).await?,
                    }
                }
            }
        }).await
}

/// UI projection only. The durable event stream still contains individual chunks.
fn project_activity(items: &mut Vec<AgentActivity>, activity: AgentActivity) {
    if activity.kind == "message.delta"
        && let Some(previous) = items.last_mut()
        && previous.kind == activity.kind
        && previous.text.len() + activity.text.len() <= 8192
    {
        previous.text.push_str(&activity.text);
    } else {
        items.push(activity);
    }
    while items.len() > 100 || items.iter().map(|a| a.text.len()).sum::<usize>() > 96 * 1024 {
        items.remove(0);
    }
}
fn compatibility(info: &serde_json::Value) -> &'static str {
    if info["name"] == "codex-acp" && info["version"] == "0.5.2" {
        "Codex ACP 0.5.2 matches the B01 experiment's reported version. Binary provenance is not verified. Flow supports text tasks and one-time permissions; upstream resume, attachments, and MCP context are not available yet."
    } else {
        "Unverified adapter release: no recorded Flow compatibility result for this reported name/version. Validate with the opt-in Codex acceptance check. Flow supports text tasks and one-time permissions; upstream resume, attachments, and MCP context are not available yet."
    }
}
/// Only static messages cross into durable history; provider error data can contain secrets.
fn diagnostic(error: &Error) -> &'static str {
    let detail = format!(
        "{} {}",
        error.message,
        error
            .data
            .as_ref()
            .map(ToString::to_string)
            .unwrap_or_default()
    )
    .to_ascii_lowercase();
    if error.code == Error::auth_required().code
        || detail.contains("401")
        || detail.contains("unauthorized")
    {
        "Authentication failed. Check the adapter's native login, then start a new conversation; Flow does not store provider credentials."
    } else if detail.contains("not supported") || detail.contains("model") {
        "The provider rejected the model or configuration. Choose a model supported by your login and retry in a new conversation."
    } else {
        "Adapter request failed or timed out. Check connectivity and adapter configuration. Raw provider errors are omitted to protect credentials."
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stream_projection_coalesces_without_merging_turns_and_bounds_wire_size() {
        let chunk = |text: &str| AgentActivity {
            kind: "message.delta".into(),
            text: text.into(),
            tool_call_id: None,
        };
        let mut items = Vec::new();
        project_activity(&mut items, chunk("Hello "));
        project_activity(&mut items, chunk("world"));
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].text, "Hello world");
        project_activity(
            &mut items,
            AgentActivity {
                kind: "turn.completed".into(),
                ..chunk("")
            },
        );
        project_activity(&mut items, chunk("Next"));
        assert_eq!(items.len(), 3);
        for _ in 0..1000 {
            project_activity(&mut items, chunk(&"\0".repeat(2048)));
        }
        assert!(items.len() <= 100);
        assert!(serde_json::to_vec(&items).unwrap().len() < 700_000);
    }
    #[test]
    fn profile_is_explicitly_uncertified_and_errors_are_redacted() {
        assert!(
            compatibility(&json!({"name":"codex-acp","version":"0.5.2"}))
                .contains("provenance is not verified")
        );
        assert!(
            compatibility(&json!({"name":"codex-acp","version":"999"})).starts_with("Unverified")
        );
        let mut error = Error::internal_error();
        error.data = Some(json!({"details":"401 Unauthorized private-fixture-token"}));
        assert!(diagnostic(&error).contains("Authentication failed"));
        assert!(!diagnostic(&error).contains("private-fixture-token"));
    }
}
