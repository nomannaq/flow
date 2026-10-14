//! Stable ACP v1 SDK endpoint; scripts cannot select arbitrary filesystem paths.
use agent_client_protocol::schema::{ProtocolVersion, v1::*};
use agent_client_protocol::{Agent, Client, ConnectTo, Error, Result};
use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, Ordering},
    },
};
use tokio::sync::Notify;

#[derive(Clone, Copy)]
pub enum Scenario {
    Write,
    PartialFailure,
    Delayed,
    IgnoreCancellation,
}

#[derive(Default)]
pub struct Control {
    pub ready: Notify,
    pub release: Notify,
    pub cancelled: Notify,
    pub trace: Mutex<Vec<String>>,
}
impl Control {
    pub fn record(&self, event: impl Into<String>) {
        self.trace.lock().unwrap().push(event.into());
    }
}

pub struct FakeAgent {
    pub root: PathBuf,
    pub scenario: Scenario,
    pub control: Arc<Control>,
}

impl ConnectTo<Client> for FakeAgent {
    async fn connect_to(self, client: impl ConnectTo<Agent>) -> Result<()> {
        let control = self.control;
        let cancellation = control.clone();
        let root = self.root;
        let session_root = root.clone();
        let scenario = self.scenario;
        let stage = Arc::new(AtomicU8::new(0));
        let initialization = stage.clone();
        let session = stage.clone();
        let mut sequence = 0u64;
        Agent.builder()
            .on_receive_request(async move |request: InitializeRequest, responder, _cx| {
                if request.protocol_version != ProtocolVersion::V1 { return Err(Error::invalid_params()); }
                initialization.store(1, Ordering::SeqCst);
                responder.respond(InitializeResponse::new(ProtocolVersion::V1))
            }, agent_client_protocol::on_receive_request!())
            .on_receive_request(async move |request: NewSessionRequest, responder, _cx| {
                // Canonical workspace supplied by the owning TempDir, not an agent path.
                if session.load(Ordering::SeqCst) != 1 || request.cwd != session_root { return Err(Error::invalid_params()); }
                session.store(2, Ordering::SeqCst);
                responder.respond(NewSessionResponse::new("fixture-session"))
            }, agent_client_protocol::on_receive_request!())
            .on_receive_notification(async move |request: CancelNotification, _cx| {
                if request.session_id.to_string() != "fixture-session" { return Err(Error::invalid_params()); }
                cancellation.record("cancel.received");
                cancellation.cancelled.notify_one();
                Ok(())
            }, agent_client_protocol::on_receive_notification!())
            .on_receive_request(async move |request: PromptRequest, responder, cx| {
                if stage.load(Ordering::SeqCst) != 2 || request.session_id.to_string() != "fixture-session" { return Err(Error::invalid_params()); }
                sequence += 1;
                let operation = sequence;
                let root = root.clone();
                let control = control.clone();
                let peer = cx.clone();
                cx.spawn(async move {
                    control.record(format!("operation.{operation}.started"));
                    let response = peer.send_request(RequestPermissionRequest::new(
                        request.session_id.clone(),
                        ToolCallUpdate::new(format!("tool-{operation}"), ToolCallUpdateFields::default()),
                        vec![PermissionOption::new("once", "Allow once", PermissionOptionKind::AllowOnce)],
                    )).block_task().await?;
                    if !matches!(response.outcome, RequestPermissionOutcome::Selected(ref selected)
                        if selected.option_id.to_string() == "once") {
                        control.record("permission.denied");
                        return responder.respond(PromptResponse::new(StopReason::Cancelled));
                    }
                    control.record("permission.allowed");
                    if matches!(scenario, Scenario::Delayed | Scenario::IgnoreCancellation) {
                        control.ready.notify_one();
                        if matches!(scenario, Scenario::Delayed) {
                            tokio::select! {
                                _ = control.release.notified() => {},
                                _ = control.cancelled.notified() => {
                                    return responder.respond(PromptResponse::new(StopReason::Cancelled));
                                }
                            }
                        } else {
                            control.release.notified().await;
                        }
                    }
                    // Fixed path in a disposable fixture; never follow an existing symlink.
                    let target = root.join("tracked.txt");
                    if std::fs::symlink_metadata(&target).map_err(Error::into_internal_error)?.file_type().is_symlink() {
                        return Err(Error::invalid_params());
                    }
                    let bytes = if matches!(scenario, Scenario::PartialFailure) {
                        b"partial".to_vec()
                    } else { format!("edit-{operation}\n").into_bytes() };
                    std::fs::write(target, bytes).map_err(Error::into_internal_error)?;
                    if matches!(scenario, Scenario::PartialFailure) {
                        control.record("operation.failed-after-write");
                        return responder.respond_with_error(Error::internal_error());
                    }
                    peer.send_notification(SessionNotification::new(request.session_id,
                        SessionUpdate::AgentMessageChunk(ContentChunk::new(ContentBlock::Text(TextContent::new("fixture edit complete"))))))?;
                    control.record(format!("operation.{operation}.completed"));
                    responder.respond(PromptResponse::new(StopReason::EndTurn))
                })?;
                Ok(())
            }, agent_client_protocol::on_receive_request!())
            .connect_to(client).await
    }
}
