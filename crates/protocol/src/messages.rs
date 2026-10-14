use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

macro_rules! opaque_id {
    ($($name:ident),+ $(,)?) => {$ (
        #[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
        #[serde(try_from = "String", into = "String")]
        pub struct $name(String);
        impl JsonSchema for $name {
            fn schema_name() -> std::borrow::Cow<'static, str> { stringify!($name).into() }
            fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
                schemars::json_schema!({"type":"string", "minLength":1, "maxLength":128,
                    "pattern":"^[A-Za-z0-9._:-]+$"})
            }
        }
        impl TryFrom<String> for $name {
            type Error = &'static str;
            fn try_from(value: String) -> Result<Self, Self::Error> {
                if value.is_empty() || value.len() > 128 || !value.bytes().all(|c|
                    c.is_ascii_alphanumeric() || b"._:-".contains(&c)) {
                    return Err("invalid opaque ID");
                }
                Ok(Self(value))
            }
        }
        impl From<$name> for String {
            fn from(value: $name) -> Self { value.0 }
        }
        impl $name {
            pub fn as_str(&self) -> &str { &self.0 }
        }
    )+};
}

opaque_id!(
    RequestToken,
    EngineEpoch,
    ProjectId,
    WorkspaceId,
    SessionId,
    StoreId,
    MutationKey
);

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema)]
#[serde(untagged)]
pub enum RequestId {
    String(RequestToken),
    Number(#[schemars(range(min = -9007199254740991i64, max = 9007199254740991i64))] i64),
}
impl RequestId {
    pub fn is_valid(&self) -> bool {
        !matches!(self, Self::Number(n) if !(-9_007_199_254_740_991..=9_007_199_254_740_991).contains(n))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub enum JsonRpc {
    #[serde(rename = "2.0")]
    V2,
}

/// Known methods are typed internally; envelopes keep an open string so unknown
/// future methods produce METHOD_NOT_FOUND rather than a JSON parse failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub enum Method {
    #[serde(rename = "engine.initialize")]
    Initialize,
    #[serde(rename = "engine.health")]
    Health,
    #[serde(rename = "state.snapshot")]
    Snapshot,
    #[serde(rename = "events.replay")]
    Replay,
    #[serde(rename = "engine.shutdown")]
    Shutdown,
    #[serde(rename = "requests.cancel")]
    Cancel,
}
impl Method {
    pub fn parse(name: &str) -> Option<Self> {
        match name {
            "engine.initialize" => Some(Self::Initialize),
            "engine.health" => Some(Self::Health),
            "state.snapshot" => Some(Self::Snapshot),
            "events.replay" => Some(Self::Replay),
            "engine.shutdown" => Some(Self::Shutdown),
            "requests.cancel" => Some(Self::Cancel),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub jsonrpc: JsonRpc,
    pub id: RequestId,
    pub method: String,
    #[serde(default = "empty_params")]
    pub params: Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub epoch: Option<EngineEpoch>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u64>,
}
fn empty_params() -> Value {
    serde_json::json!({})
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
pub struct Notification {
    pub jsonrpc: JsonRpc,
    pub method: String,
    #[serde(default = "empty_params")]
    pub params: Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub epoch: Option<EngineEpoch>,
}

/// Always an ID (null when unrecoverable) and exactly one result/error.
#[derive(Clone, Debug, Serialize)]
pub struct Response {
    pub jsonrpc: JsonRpc,
    pub id: Option<RequestId>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub epoch: Option<EngineEpoch>,
    #[serde(flatten)]
    pub outcome: Outcome,
}

impl JsonSchema for Response {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "Response".into()
    }
    fn json_schema(generator: &mut schemars::SchemaGenerator) -> schemars::Schema {
        let id = generator.subschema_for::<RequestId>();
        let epoch = generator.subschema_for::<EngineEpoch>();
        let error = generator.subschema_for::<RpcError>();
        schemars::json_schema!({
            "type":"object", "required":["jsonrpc","id"],
            "properties": {
                "jsonrpc":{"const":"2.0"}, "id":{"anyOf":[id,{"type":"null"}]},
                "epoch":epoch, "result":true, "error":error
            },
            "oneOf":[
                {"required":["result"],"not":{"required":["error"]}},
                {"required":["error"],"not":{"required":["result"]}}
            ]
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(untagged)]
pub enum Outcome {
    Success { result: Value },
    Failure { error: RpcError },
}

impl<'de> Deserialize<'de> for Response {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        use serde::de::Error;
        let value = Value::deserialize(deserializer)?;
        let obj = value
            .as_object()
            .ok_or_else(|| D::Error::custom("invalid response"))?;
        if !obj.contains_key("id") || obj.contains_key("result") == obj.contains_key("error") {
            return Err(D::Error::custom("invalid response envelope"));
        }
        #[derive(Deserialize)]
        struct Wire {
            jsonrpc: JsonRpc,
            id: Option<RequestId>,
            epoch: Option<EngineEpoch>,
        }
        let wire: Wire = serde_json::from_value(value.clone()).map_err(D::Error::custom)?;
        if wire.id.as_ref().is_some_and(|id| !id.is_valid()) {
            return Err(D::Error::custom("invalid response ID"));
        }
        let outcome = if let Some(result) = obj.get("result") {
            Outcome::Success {
                result: result.clone(),
            }
        } else {
            Outcome::Failure {
                error: serde_json::from_value(obj["error"].clone()).map_err(D::Error::custom)?,
            }
        };
        Ok(Self {
            jsonrpc: wire.jsonrpc,
            id: wire.id,
            epoch: wire.epoch,
            outcome,
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
pub struct RpcError {
    pub code: i32,
    pub message: String,
}

pub mod error {
    pub const PARSE: i32 = -32700;
    pub const INVALID_REQUEST: i32 = -32600;
    pub const METHOD_NOT_FOUND: i32 = -32601;
    pub const INVALID_PARAMS: i32 = -32602;
    pub const INTERNAL: i32 = -32603;
    pub const NOT_INITIALIZED: i32 = -32001;
    pub const INCOMPATIBLE_VERSION: i32 = -32002;
    pub const ALREADY_INITIALIZED: i32 = -32003;
    pub const STALE_EPOCH: i32 = -32004;
    pub const BUSY: i32 = -32005;
    pub const DUPLICATE_ID: i32 = -32006;
    pub const CANCELLED: i32 = -32800;
    pub const TIMEOUT: i32 = -32801;
}
impl Response {
    pub fn success(id: RequestId, epoch: EngineEpoch, result: impl Serialize) -> Self {
        Self {
            jsonrpc: JsonRpc::V2,
            id: Some(id),
            epoch: Some(epoch),
            outcome: Outcome::Success {
                result: serde_json::to_value(result).expect("wire DTO is JSON"),
            },
        }
    }
    pub fn failure(id: Option<RequestId>, epoch: Option<EngineEpoch>, code: i32) -> Self {
        let message = match code {
            error::PARSE => "Parse error",
            error::INVALID_REQUEST => "Invalid request",
            error::METHOD_NOT_FOUND => "Method not found",
            error::INVALID_PARAMS => "Invalid params",
            error::NOT_INITIALIZED => "Initialize the connection first",
            error::INCOMPATIBLE_VERSION => "Unsupported protocol version",
            error::ALREADY_INITIALIZED => "Connection already initialized",
            error::STALE_EPOCH => "Stale engine epoch",
            error::BUSY => "Connection capacity exceeded",
            error::DUPLICATE_ID => "Request ID already used",
            error::CANCELLED => "Request cancelled",
            error::TIMEOUT => "Request deadline exceeded",
            _ => "Internal error",
        };
        Self {
            jsonrpc: JsonRpc::V2,
            id,
            epoch,
            outcome: Outcome::Failure {
                error: RpcError {
                    code,
                    message: message.into(),
                },
            },
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HostCapabilities {
    #[serde(default)]
    pub editor_context: bool,
    #[serde(default)]
    pub historical_diff: bool,
    #[serde(default)]
    pub native_merge: bool,
    #[serde(default)]
    pub commit_selection: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct InitializeParams {
    pub protocol_version: u32,
    pub client_name: String,
    pub client_version: String,
    pub project_id: ProjectId,
    #[serde(default)]
    pub capabilities: HostCapabilities,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct EngineCapabilities {
    pub cancellation: bool,
    pub persistent_events: bool,
    pub idempotent_mutations: bool,
    pub agent_sessions: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct InitializeResult {
    pub protocol_version: u32,
    pub engine_version: String,
    pub epoch: EngineEpoch,
    pub project_id: ProjectId,
    pub capabilities: EngineCapabilities,
    pub max_frame_bytes: usize,
    pub max_in_flight: usize,
    pub max_connection_requests: usize,
    pub default_timeout_ms: u64,
    pub max_timeout_ms: u64,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HealthParams {
    /// Optional bounded diagnostic wait for exercising cancellation/deadlines.
    #[serde(default)]
    pub wait_ms: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HealthResult {
    pub protocol_version: u32,
    pub engine_version: String,
    pub project_id: ProjectId,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
pub struct CancelParams {
    pub id: RequestId,
}
