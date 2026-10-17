use crate::SessionId;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ContextQuery {
    pub session_id: Option<SessionId>,
    #[serde(default)]
    pub query: String,
    pub path: Option<String>,
    pub content_hash: Option<String>,
    pub line: Option<usize>,
    pub byte_budget: Option<usize>,
    pub token_budget: Option<usize>,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OverlaySelection {
    pub fingerprint: String,
    pub files: Vec<OverlayFile>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OverlayFile {
    pub path: String,
    /// Exactly "index" or "disk". Disk includes the staged layer plus local edits.
    pub source: String,
}

// Intentionally no Debug: this ephemeral request carries a credential over the
// private host/engine pipe. It is never persisted or logged.
#[derive(Clone, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UtilityRequest {
    pub purpose: String,
    pub model: String,
    pub api_key: String,
    pub input: String,
    pub input_hash: String,
}
