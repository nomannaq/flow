//! Flow IPC v1. Independent of ACP, persistence implementations and IDE APIs.
mod agents;
mod durable;
mod messages;
mod productivity;
pub use productivity::*;

pub use agents::*;
pub use durable::*;
pub use messages::*;

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;
pub const MAX_IN_FLIGHT: usize = 32;
pub const MAX_CONNECTION_REQUESTS: usize = 65536;
pub const DEFAULT_TIMEOUT_MS: u64 = 30_000;
pub const MAX_TIMEOUT_MS: u64 = 30_000;

/// A schema catalogue, not an additional wire envelope. Review generated changes.
#[derive(schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct Contract {
    pub context_query: ContextQuery,
    pub learning_write: LearningWrite,
    pub overlay_selection: OverlaySelection,
    pub utility_request: UtilityRequest,
    pub apply_prepare: ApplyPrepare,
    pub apply_query: ApplyQuery,
    pub apply_action: ApplyAction,
    pub apply_resolve: ApplyResolve,
    pub candidate_prepare: CandidatePrepare,
    pub candidate_query: CandidateQuery,
    pub candidate_decision: CandidateDecision,
    pub candidate_check: CandidateCheck,
    pub method: Method,
    pub request: Request,
    pub notification: Notification,
    pub response: Response,
    pub initialize_params: InitializeParams,
    pub initialize_result: InitializeResult,
    pub health_params: HealthParams,
    pub health_result: HealthResult,
    pub cancel_params: CancelParams,
    pub durable_event: DurableEvent,
    pub snapshot: StateSnapshot,
    pub replay_params: ReplayParams,
    pub replay_page: ReplayPage,
    pub mutation_receipt: MutationReceipt,
    pub session_list: SessionList,
    pub session_search: SessionSearch,
    pub session_label: SessionLabel,
    pub conversation_query: ConversationQuery,
    pub image_query: ImageQuery,
    pub attachment_import: AttachmentImport,
    pub attachment_query: AttachmentQuery,
    pub history_attachment_query: HistoryAttachmentQuery,
    pub queue_action: QueueAction,
    pub queue_item_query: QueueItemQuery,
    pub history_query: HistoryQuery,
    pub session_changes_query: SessionChangesQuery,
    pub agent_start: AgentStart,
    pub agent_command: AgentCommand,
    pub agent_authenticate: AgentAuthenticate,
    pub agent_configure: AgentConfigure,
    pub permission_decision: PermissionDecision,
    pub agent_view: AgentView,
}

pub fn schema() -> serde_json::Value {
    serde_json::to_value(schemars::schema_for!(Contract)).expect("schema is JSON")
}
