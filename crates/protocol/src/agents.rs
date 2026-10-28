use crate::SessionId;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentStart {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<PromptImage>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<AttachmentRef>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rewind_turn_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub include_context: Option<bool>,
    /// Connect and discover configuration without making an inference request.
    #[serde(default, skip_serializing_if = "is_false")]
    pub connect_only: bool,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub prompt_provenance: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub overlay: Option<crate::OverlaySelection>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub adapter_profile: String,
    /// Host-selected absolute executable; never read from repository config.
    pub executable: String,
    #[serde(default)]
    pub arguments: Vec<String>,
    #[serde(default)]
    pub commit: String,
    /// Explicit new conversation in a retained workspace; never upstream resume.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_session_id: Option<SessionId>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub writers_stopped: bool,
    pub prompt: String,
    /// Optional explicit SDK authentication method. Existing native login is default.
    pub auth_method: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentCommand {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<PromptImage>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<AttachmentRef>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub include_context: Option<bool>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub prompt_provenance: String,
    pub session_id: SessionId,
    #[serde(default)]
    pub prompt: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PromptImage {
    pub name: String,
    pub mime_type: String,
    pub data: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ImageRef {
    pub id: String,
    pub name: String,
    pub mime_type: String,
    pub size: u32,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct QueueAction {
    pub session_id: SessionId,
    pub action: String,
    pub id: Option<String>,
    pub position: Option<u16>,
    pub command: Option<AgentCommand>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct QueueItemQuery {
    pub session_id: SessionId,
    pub id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct QueuedPrompt {
    pub id: String,
    pub preview: String,
    pub status: String,
    pub image_count: u16,
    #[serde(default)]
    pub attachment_count: u16,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentAuthenticate {
    pub session_id: SessionId,
    pub method_id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AgentConfigure {
    pub session_id: SessionId,
    pub config_id: String,
    pub value: Value,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentConfigChoice {
    pub value: String,
    pub name: String,
    pub description: String,
    pub group: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentConfigOption {
    pub id: String,
    pub name: String,
    pub description: String,
    pub category: String,
    pub kind: String,
    pub current_value: Value,
    pub options: Vec<AgentConfigChoice>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PermissionDecision {
    pub session_id: SessionId,
    pub request_id: String,
    pub option_id: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PermissionChoice {
    #[serde(default, skip_serializing_if = "is_false")]
    pub allow_once: bool,
    pub id: String,
    pub name: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentPermission {
    pub request_id: String,
    pub title: String,
    pub tool_call_id: String,
    pub options: Vec<PermissionChoice>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentActivity {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool: Option<AgentToolCall>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub turn_id: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<ImageRef>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<AttachmentRef>,
    pub kind: String,
    pub text: String,
    pub tool_call_id: Option<String>,
}

/// Bounded display data reported by an adapter. Optional fields are replacement
/// patches; an empty collection clears the previous value. Diffs are not verified history.
#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentToolCall {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locations: Option<Vec<AgentToolLocation>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diffs: Option<Vec<AgentToolDiff>>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub truncated: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentToolLocation {
    pub path: String,
    pub line: Option<u32>,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentToolDiff {
    pub path: String,
    pub before: String,
    pub after: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentAvailableCommand {
    pub name: String,
    pub description: String,
    pub input_hint: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentUsage {
    /// Decimal strings retain the precision of provider u64 counters.
    pub used: String,
    pub size: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentPlanEntry {
    pub content: String,
    pub status: String,
    pub priority: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentPlan {
    pub entries: Vec<AgentPlanEntry>,
    pub truncated: bool,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentView {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plan: Option<AgentPlan>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub subagents: Vec<AgentActivity>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub connection_stage: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub startup_elapsed_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    pub startup_timings_ms: std::collections::BTreeMap<String, u64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub queue: Vec<QueuedPrompt>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub queue_paused: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub available_commands: Vec<AgentAvailableCommand>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<AgentUsage>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub configuration_error: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub config_options: Vec<AgentConfigOption>,
    pub session_id: Option<SessionId>,
    pub workspace: Option<String>,
    pub status: String,
    pub upstream_session_id: Option<String>,
    pub agent_info: Value,
    pub capabilities: Value,
    pub auth_methods: Vec<String>,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub recorded_operations: u64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub compatibility: String,
    pub activity: Vec<AgentActivity>,
    pub permissions: Vec<AgentPermission>,
}

/// Bounded read queries for retained history. Numeric cursors are decimal strings
/// so a JavaScript host never loses a SQLite sequence's precision.
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SessionList {
    pub after: Option<SessionId>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SessionSearch {
    #[serde(default)]
    pub query: String,
    #[serde(default)]
    pub favorites_only: bool,
    pub after: Option<SessionId>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SessionLabel {
    pub session_id: SessionId,
    pub title: Option<String>,
    pub favorite: Option<bool>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConversationQuery {
    pub session_id: SessionId,
    pub after: Option<String>,
    pub through: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ImageQuery {
    pub session_id: SessionId,
    pub turn_id: String,
    pub image_id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HistoryQuery {
    pub session_id: SessionId,
    #[serde(default)]
    pub after: Option<String>,
    pub operation_id: Option<String>,
    #[serde(default)]
    pub file_index: usize,
    pub turn_id: Option<String>,
    pub content_ids: Option<HistoryContentIds>,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SessionChangesQuery {
    pub session_id: SessionId,
    pub snapshot_id: Option<String>,
    pub after: Option<String>,
    #[serde(default)]
    pub file_index: usize,
    pub content_ids: Option<HistoryContentIds>,
}

fn is_false(value: &bool) -> bool {
    !value
}

fn is_zero(value: &u64) -> bool {
    *value == 0
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HistoryContentIds {
    pub before: Option<String>,
    pub after: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CandidatePrepare {
    pub session_id: SessionId,
    pub writers_stopped: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CandidateQuery {
    pub candidate_id: String,
    pub after: Option<String>,
    #[serde(default)]
    pub file_index: usize,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CandidateDecision {
    pub candidate_id: String,
    pub accept: bool,
    pub writers_stopped: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CandidateCheck {
    pub candidate_id: String,
    pub check_id: crate::MutationKey,
    pub command: Vec<String>,
    pub timeout_seconds: u64,
    pub writers_stopped: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApplyPrepare {
    pub plan_id: crate::MutationKey,
    pub candidate_id: String,
    pub writers_stopped: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApplyQuery {
    pub plan_id: crate::MutationKey,
    #[serde(default)]
    pub file_index: usize,
    #[serde(default)]
    pub rollback: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApplyAction {
    pub plan_id: crate::MutationKey,
    pub candidate_id: String,
    pub writers_stopped: bool,
    #[serde(default)]
    pub document_stamps: std::collections::BTreeMap<String, u64>,
}
#[derive(Clone, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApplyResolve {
    pub plan_id: crate::MutationKey,
    pub file_index: usize,
    pub content: String,
}

/// Exact imported file identity, independent of the original source path.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentRef {
    pub id: String,
    pub name: String,
    pub mime_type: String,
    pub size: u32,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentImport {
    pub paths: Vec<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentQuery {
    pub attachment: AttachmentRef,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HistoryAttachmentQuery {
    pub session_id: SessionId,
    pub turn_id: String,
    pub attachment: AttachmentRef,
}
