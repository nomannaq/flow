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
    /// Segment-aware relative path prefix, applied before ranking.
    pub scope: Option<String>,
    pub symbol: Option<String>,
    pub direction: Option<String>,
    pub depth: Option<usize>,
    pub limit: Option<usize>,
    pub pattern: Option<String>,
    #[serde(default)]
    pub fixed: bool,
    #[serde(default)]
    pub ignore_case: bool,
    #[serde(default)]
    pub full: bool,
    #[serde(default)]
    pub paths: Vec<String>,
}

/// Explicitly reviewed guidance, stored as an immutable Markdown document.
#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LearningWrite {
    pub session_id: Option<SessionId>,
    pub title: String,
    pub body: String,
    pub author: String,
    #[serde(default)]
    pub touches: Vec<String>,
    #[serde(default)]
    pub constraints: Vec<String>,
    #[serde(default)]
    pub replaces: Vec<String>,
    #[serde(default)]
    pub private: bool,
    #[serde(default)]
    pub always_follow: bool,
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
