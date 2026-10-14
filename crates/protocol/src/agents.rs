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
    pub commit: String,
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
pub struct PermissionDecision {
    pub session_id: SessionId,
    pub request_id: String,
    pub option_id: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PermissionChoice {
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
    pub activity: Vec<AgentActivity>,
    pub permissions: Vec<AgentPermission>,
}
