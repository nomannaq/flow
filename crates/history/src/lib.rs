//! Durable, agent-independent history. All SQLite and content I/O runs on one
//! bounded worker. Calls are blocking: async hosts must use bounded spawn_blocking,
//! never an IDE event thread. Dropping a caller does not roll back queued work.
mod blobs;
mod model;
mod recorder;
mod store;
pub use model::*;
pub use recorder::{Observation, Recorder};
pub use store::{HistoryStore, StorageReport};

#[derive(Debug)]
pub enum Error {
    Io(std::io::Error),
    Sql(rusqlite::Error),
    Invalid(&'static str),
    CorruptMetadata,
    MissingContent(ContentId),
    CorruptContent(ContentId),
    NotFound,
    Conflict,
    InvalidCursor,
    Locked,
    Busy,
    WorkerStopped,
    ReadOnly,
    BudgetExceeded,
    UnsupportedSchema(u32),
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Do not copy OS/SQL diagnostics, paths or repository contents to logs.
        f.write_str(match self {
            Self::Io(_) => "history I/O failed",
            Self::Sql(_) => "history database operation failed",
            Self::Invalid(message) => message,
            Self::CorruptMetadata => "history metadata is corrupt",
            Self::MissingContent(_) => "referenced history content is missing",
            Self::CorruptContent(_) => "history content failed verification",
            Self::NotFound => "history record not found",
            Self::Conflict => "history identity or mutation conflict",
            Self::InvalidCursor => "history cursor requires resynchronization",
            Self::Locked => "history is owned by another writer",
            Self::Busy => "history queue is full",
            Self::ReadOnly => "history requires recovery; writes are disabled",
            Self::BudgetExceeded => {
                "history storage budget exhausted; close sessions and review retention"
            }
            Self::WorkerStopped => "history worker stopped; mutation outcome may be unknown",
            Self::UnsupportedSchema(_) => "history schema is newer than this engine",
        })
    }
}
impl std::error::Error for Error {}
impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}
impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Self::Sql(e)
    }
}
pub type Result<T> = std::result::Result<T, Error>;
