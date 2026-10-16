use crate::SessionId;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentStart {
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
    pub session_id: SessionId,
    #[serde(default)]
    pub prompt: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentAuthenticate {
    pub session_id: SessionId,
    pub method_id: String,
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
    pub kind: String,
    pub text: String,
    pub tool_call_id: Option<String>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct AgentView {
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
