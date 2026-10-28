use crate::{Error, Result};
use protocol::{SessionId, WorkspaceId};
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, path::PathBuf};

pub const MAX_BLOB_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_CAPTURE_BYTES: usize = 128 * 1024 * 1024;
pub const MAX_CHUNK_BYTES: usize = 64 * 1024;
pub const MAX_ENTRIES: usize = 4096;
pub const MAX_PAGE_SIZE: u16 = 100;
pub(crate) const MAX_METADATA_BYTES: usize = 512 * 1024;
#[derive(Clone, Debug)]
pub struct AttachmentInput {
    pub name: String,
    pub mime_type: String,
    pub bytes: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct ContentId(String);
impl ContentId {
    pub fn hash(bytes: &[u8]) -> Self {
        Self(blake3::hash(bytes).to_hex().to_string())
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
}
impl TryFrom<String> for ContentId {
    type Error = &'static str;
    fn try_from(s: String) -> std::result::Result<Self, Self::Error> {
        if s.len() == 64
            && s.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            Ok(Self(s))
        } else {
            Err("invalid content ID")
        }
    }
}
impl From<ContentId> for String {
    fn from(id: ContentId) -> Self {
        id.0
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FileKind {
    Regular,
    Symlink,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub blob: ContentId,
    pub size: u64,
    /// Permission bits only; file type is separate. Symlink bytes are the link target.
    pub mode: u32,
    pub kind: FileKind,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileInput {
    pub bytes: Vec<u8>,
    pub mode: u32,
    pub kind: FileKind,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Coverage {
    pub complete: bool,
    pub excluded: Vec<String>,
}
#[derive(Clone, Debug)]
pub struct SnapshotInput {
    pub workspace_id: WorkspaceId,
    pub files: BTreeMap<String, FileInput>,
    pub coverage: Coverage,
    pub started_at_ms: u64,
    pub completed_at_ms: u64,
    pub provenance: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub workspace_id: WorkspaceId,
    pub files: BTreeMap<String, FileEntry>,
    pub coverage: Coverage,
    pub started_at_ms: u64,
    pub completed_at_ms: u64,
    pub provenance: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Snapshot {
    pub id: ContentId,
    pub manifest: Manifest,
}

/// Identity includes source generation as well as immutable bytes: new turns
/// invalidate an earlier approval even when the final file contents match.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub session_id: SessionId,
    pub baseline: ContentId,
    pub source: ContentId,
    pub result: ContentId,
    pub generation: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CandidateReview {
    pub id: ContentId,
    pub candidate: Candidate,
    pub decision: String,
    pub checks: Vec<serde_json::Value>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub id: WorkspaceId,
    pub worktree_path: PathBuf,
    pub git_common_dir: PathBuf,
    pub base_commit: String,
    /// Opaque ownership identity; registration never creates/deletes a worktree.
    pub owner_token: String,
    pub lifecycle: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: SessionId,
    pub workspace_id: WorkspaceId,
    pub baseline: ContentId,
    pub agent: String,
    pub adapter_version: String,
    pub upstream_id: Option<String>,
    pub capabilities: serde_json::Value,
    pub status: String,
    pub created_at_ms: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Turn {
    pub id: String,
    pub session_id: SessionId,
    /// Reference/provenance, not a second transcript or credentials store.
    pub prompt_provenance: String,
    pub upstream_id: Option<String>,
    pub status: String,
    pub created_at_ms: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Attribution {
    Exact,
    CheckpointInterval,
    Ambiguous,
    External,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OperationStatus {
    Completed,
    Failed,
    Interrupted,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationInput {
    pub id: String,
    pub session_id: SessionId,
    pub turn_id: String,
    pub before: ContentId,
    pub after: ContentId,
    pub tool_call_id: Option<String>,
    pub parent_id: Option<String>,
    pub status: OperationStatus,
    pub attribution: Attribution,
    pub evidence: String,
    pub started_at_ms: u64,
    pub completed_at_ms: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileChange {
    pub old_path: Option<String>,
    pub new_path: Option<String>,
    pub before: Option<FileEntry>,
    pub after: Option<FileEntry>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Operation {
    pub sequence: u64,
    pub input: OperationInput,
    pub changes: Vec<FileChange>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Page<T, Cursor = u64> {
    pub items: Vec<T>,
    pub next: Option<Cursor>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SnapshotSummary {
    pub sequence: u64,
    pub id: ContentId,
    pub completed_at_ms: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContentChunk {
    pub bytes: Vec<u8>,
    pub total_bytes: u64,
    pub next_offset: Option<u64>,
}

pub(crate) fn path(s: &str) -> Result<()> {
    if s.is_empty()
        || s.len() > 4096
        || s.contains(['\\', '\0'])
        || s.split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
        || (s.as_bytes().get(1) == Some(&b':'))
    {
        return Err(Error::Invalid("invalid relative history path"));
    }
    Ok(())
}
pub(crate) fn identifier(s: &str) -> Result<()> {
    protocol::RequestToken::try_from(s.to_owned())
        .map(|_| ())
        .map_err(|_| Error::Invalid("invalid history identifier"))
}
pub(crate) fn interval(start: u64, end: u64) -> Result<()> {
    if start > end || end > i64::MAX as u64 {
        return Err(Error::Invalid("invalid capture interval"));
    }
    Ok(())
}
pub(crate) fn json<T: Serialize>(value: &T) -> Result<String> {
    let text = serde_json::to_string(value)
        .map_err(|_| Error::Invalid("history metadata cannot be encoded"))?;
    if text.len() > MAX_METADATA_BYTES {
        return Err(Error::Invalid("history metadata exceeds limit"));
    }
    Ok(text)
}
pub(crate) fn decode<T: serde::de::DeserializeOwned>(s: &str) -> Result<T> {
    serde_json::from_str(s).map_err(|_| Error::CorruptMetadata)
}

/// Host writes are authorized one at a time after these preimages are durable.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyFile {
    #[serde(default)]
    pub authorized: bool,
    pub path: String,
    pub before: Option<FileEntry>,
    pub after: Option<FileEntry>,
    pub status: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyJournal {
    pub id: String,
    pub candidate_id: ContentId,
    pub current_id: ContentId,
    pub root: PathBuf,
    pub head: String,
    pub index_digest: ContentId,
    #[serde(default)]
    pub rollback_fingerprint: Option<(String, ContentId)>,
    pub files: Vec<ApplyFile>,
    pub status: String,
    pub owner: Option<String>,
    #[serde(default)]
    pub document_stamps: BTreeMap<String, u64>,
    pub superseded_by: Option<String>,
    pub resolution_digest: Option<ContentId>,
}
