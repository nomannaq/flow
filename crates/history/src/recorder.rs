//! Immutable checkpoint journal, independent of ACP and filesystem discovery.
use crate::*;
use protocol::{MutationKey, SessionId};

pub struct Observation {
    pub turn_id: String,
    pub status: OperationStatus,
    pub attribution: Attribution,
    pub tool_call_id: Option<String>,
    /// Bounded JSON/text evidence including boundary and logical grouping hints.
    pub evidence: String,
}

pub struct Recorder {
    store: HistoryStore,
    session: SessionId,
    checkpoint: Snapshot,
    serial: u64,
    healthy: bool,
    prefix: String,
}

impl Recorder {
    /// New sessions only. Crash reconciliation/resumption is a separate workflow.
    pub fn new(store: HistoryStore, session: SessionId) -> Result<Self> {
        if !store.operations(session.clone(), 0, 1)?.items.is_empty() {
            return Err(Error::Invalid(
                "recorded session requires explicit recovery",
            ));
        }
        let record = store.get_session(session.clone())?;
        let checkpoint = store.get_snapshot(record.baseline)?;
        Ok(Self {
            store,
            session: session.clone(),
            checkpoint,
            prefix: session.as_str().to_owned(),
            serial: 0,
            healthy: true,
        })
    }

    /// Caller has acquired workspace ownership and explicitly resolved old
    /// writers. Resume the durable chain using a fresh namespace so abandoned
    /// snapshot publication keys are never mistaken for completed operations.
    pub fn recover(store: HistoryStore, session: SessionId) -> Result<Self> {
        let checkpoint = store.checkpoint(session.clone())?;
        let mut nonce = [0u8; 16];
        getrandom::fill(&mut nonce).map_err(|_| Error::Invalid("recovery identity unavailable"))?;
        let prefix = format!(
            "recovery-{}",
            nonce.iter().map(|b| format!("{b:02x}")).collect::<String>()
        );
        Ok(Self {
            store,
            session,
            checkpoint,
            serial: 0,
            healthy: true,
            prefix,
        })
    }

    pub fn checkpoint(&self) -> &Snapshot {
        &self.checkpoint
    }

    /// Advances only after operation metadata is durable. Publication failure
    /// latches the journal closed; an orphan snapshot cannot become the baseline.
    pub fn observe(
        &mut self,
        input: SnapshotInput,
        observation: Observation,
    ) -> Result<Option<String>> {
        if !self.healthy {
            return Err(Error::Invalid("recorder requires recovery"));
        }
        if input.workspace_id != self.checkpoint.manifest.workspace_id || !input.coverage.complete {
            return Err(Error::Invalid("recorder requires full workspace coverage"));
        }
        if observation.evidence.len() > 4096 || observation.attribution == Attribution::Exact {
            return Err(Error::Invalid(
                "checkpoint observation cannot claim exact tool attribution",
            ));
        }
        let unchanged = input.files.len() == self.checkpoint.manifest.files.len()
            && input.files.iter().all(|(path, file)| {
                self.checkpoint.manifest.files.get(path).is_some_and(|old| {
                    old.blob == ContentId::hash(&file.bytes)
                        && old.mode == file.mode
                        && old.kind == file.kind
                })
            });
        if unchanged {
            return Ok(None);
        }
        self.serial += 1;
        let id = format!("{}-observation-{}", self.prefix, self.serial);
        self.healthy = false;
        let start = self.checkpoint.manifest.completed_at_ms;
        let end = input.completed_at_ms.max(start);
        let published = self
            .store
            .publish_snapshot(key(&format!("{id}-snapshot"))?, input)?;
        let after: ContentId =
            serde_json::from_value(published.outcome.ok_or(Error::CorruptMetadata)?["id"].clone())
                .map_err(|_| Error::CorruptMetadata)?;
        self.store.record_operation(
            key(&format!("{id}-record"))?,
            OperationInput {
                id: id.clone(),
                session_id: self.session.clone(),
                turn_id: observation.turn_id,
                before: self.checkpoint.id.clone(),
                after: after.clone(),
                tool_call_id: observation.tool_call_id,
                parent_id: None,
                status: observation.status,
                attribution: observation.attribution,
                evidence: observation.evidence,
                started_at_ms: start,
                completed_at_ms: end,
            },
        )?;
        self.checkpoint = self.store.get_snapshot(after)?;
        self.healthy = true;
        Ok(Some(id))
    }
}
fn key(value: &str) -> Result<MutationKey> {
    MutationKey::try_from(value.to_owned()).map_err(|_| Error::Invalid("recorder key too long"))
}
