use crate::{MutationKey, SessionId, StoreId, WorkspaceId};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Decimal on the wire: sequences never lose precision in JavaScript hosts.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(try_from = "String", into = "String")]
pub struct EventSequence(pub u64);
impl TryFrom<String> for EventSequence {
    type Error = &'static str;
    fn try_from(s: String) -> Result<Self, Self::Error> {
        if s.is_empty()
            || (s.len() > 1 && s.starts_with('0'))
            || !s.bytes().all(|c| c.is_ascii_digit())
        {
            return Err("invalid event sequence");
        }
        s.parse().map(Self).map_err(|_| "invalid event sequence")
    }
}
impl From<EventSequence> for String {
    fn from(s: EventSequence) -> Self {
        s.0.to_string()
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct EventCursor {
    pub store_id: StoreId,
    pub sequence: EventSequence,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct DurableEvent {
    pub cursor: EventCursor,
    pub schema_version: u32,
    pub workspace_id: WorkspaceId,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<SessionId>,
    /// Open string: hosts retain unknown event kinds and their opaque payloads.
    pub kind: String,
    pub payload: Value,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StateSnapshot {
    pub high_water: EventCursor,
    pub state: Value,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
pub struct ReplayParams {
    pub after: EventCursor,
    pub limit: u16,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ReplayPage {
    pub events: Vec<DurableEvent>,
    pub high_water: EventCursor,
    pub has_more: bool,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum MutationStatus {
    Accepted,
    Running,
    Succeeded,
    Failed,
    #[serde(other)]
    Unknown,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct MutationReceipt {
    pub key: MutationKey,
    pub request_digest: String,
    pub status: MutationStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<Value>,
}

/// B04 implements these semantics. No in-memory durability substitute.
pub trait ProtocolStore {
    type Error;
    /// State and high-water cursor must come from one consistent read.
    fn snapshot(&self) -> Result<StateSnapshot, Self::Error>;
    /// Ordered, exclusive of `after`; reject foreign/pruned cursors and invalid limits.
    fn replay(&self, query: &ReplayParams) -> Result<ReplayPage, Self::Error>;
    /// Lookup before retrying a mutation whose transport outcome is unknown.
    fn mutation(&self, key: &MutationKey) -> Result<Option<MutationReceipt>, Self::Error>;
    /// Atomically claim a key+digest or return its existing receipt. A different
    /// digest for the same key is an error; never execute the mutation twice.
    fn claim_mutation(
        &mut self,
        key: &MutationKey,
        digest: &str,
    ) -> Result<MutationReceipt, Self::Error>;
    /// B04 must update the receipt in the same database transaction as its
    /// state/event changes. Require the original digest; reject conflicting or
    /// regressive terminal outcomes. Implementations own sequence allocation.
    fn finish_mutation(&mut self, receipt: &MutationReceipt) -> Result<(), Self::Error>;
}
