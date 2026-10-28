use crate::{
    blobs::{self, Blobs},
    model::*,
    *,
};
use protocol::{
    DurableEvent, EventCursor, EventSequence, MutationKey, MutationReceipt, MutationStatus,
    ProtocolStore, ReplayPage, ReplayParams, SessionId, StateSnapshot, StoreId, WorkspaceId,
};
use rusqlite::{Connection, OptionalExtension, Transaction, TransactionBehavior, params};
use serde_json::{Value, json as value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
    sync::{
        Arc,
        mpsc::{self, SyncSender, TrySendError},
    },
    thread::{self, JoinHandle},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const SCHEMA_VERSION: u32 = 8;
mod application;
mod attachments;
mod candidates;
mod conversations;
mod lifecycle;
mod maintenance;
mod prompts;
pub use maintenance::StorageReport;
const QUEUE_CAPACITY: usize = 4;
type Job = Box<dyn FnOnce(&mut Database) + Send>;
struct Worker {
    sender: Option<SyncSender<Job>>,
    thread: Option<JoinHandle<()>>,
}
impl Drop for Worker {
    fn drop(&mut self) {
        self.sender.take(); // Drain admitted jobs before joining; receipts survive dropped waiters.
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
#[derive(Clone)]
pub struct HistoryStore {
    worker: Arc<Worker>,
    directory: PathBuf,
}
impl HistoryStore {
    /// Derived caches have separate versioned databases. Their failure never
    /// changes authoritative history or puts it into recovery mode.
    pub fn context_database(&self) -> Result<Connection> {
        let directory = self.directory.join("derived");
        blobs::directory(&directory)?;
        let path = directory.join("context-v1.sqlite");
        if path
            .symlink_metadata()
            .is_ok_and(|m| !m.is_file() || m.file_type().is_symlink())
        {
            return Err(Error::Invalid("derived database was redirected"));
        }
        let conn = Connection::open(path)?;
        conn.busy_timeout(std::time::Duration::from_secs(2))?;
        Ok(conn)
    }
    /// `data_root` must exist and be outside every repository. Storage uses a
    /// canonical-project hash below it; callers cannot select arbitrary DB files.
    pub fn open(project_root: &Path, data_root: &Path) -> Result<Self> {
        let project = project_root.canonicalize()?;
        let data = data_root.canonicalize()?;
        if !project.is_dir()
            || !data.is_dir()
            || data.starts_with(&project)
            || inside_repository(&data)
        {
            return Err(Error::Invalid(
                "history must be stored outside repositories",
            ));
        }
        let project_name = project
            .to_str()
            .ok_or(Error::Invalid("non-UTF-8 project roots are unsupported"))?
            .to_owned();
        let directory = data.join(format!(
            "project-{}",
            ContentId::hash(project_name.as_bytes()).as_str()
        ));
        blobs::directory(&directory)?;
        let (sender, receiver) = mpsc::sync_channel::<Job>(QUEUE_CAPACITY);
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let db_dir = directory.clone();
        let thread = thread::Builder::new()
            .name("flow-history".into())
            .spawn(move || {
                let mut db = match Database::open(&db_dir, &project_name) {
                    Ok(db) => {
                        let _ = ready_tx.send(Ok(()));
                        db
                    }
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };
                for job in receiver {
                    job(&mut db);
                }
            })?;
        let worker = Arc::new(Worker {
            sender: Some(sender),
            thread: Some(thread),
        });
        ready_rx.recv().map_err(|_| Error::WorkerStopped)??;
        Ok(Self { worker, directory })
    }
    /// Native per-user data location. Explicit-root open() is useful for tests.
    pub fn open_default(project_root: &Path) -> Result<Self> {
        #[cfg(target_os = "macos")]
        let base = std::env::var_os("HOME")
            .map(PathBuf::from)
            .map(|p| p.join("Library/Application Support"));
        #[cfg(target_os = "windows")]
        let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        let base = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")));
        let base = base.ok_or(Error::Invalid("application data location unavailable"))?;
        let project = project_root.canonicalize()?;
        if !base.is_absolute()
            || base
                .components()
                .any(|part| matches!(part, std::path::Component::ParentDir))
        {
            return Err(Error::Invalid("application data location must be absolute"));
        }
        // A fresh Linux account may not have ~/.local/share yet. Check the
        // existing ancestor before creating directories, including XDG overrides.
        if !base.exists() {
            let ancestor = base
                .ancestors()
                .find(|p| p.exists())
                .ok_or(Error::Invalid("application data parent unavailable"))?
                .canonicalize()?;
            if ancestor.starts_with(&project) || inside_repository(&ancestor) {
                return Err(Error::Invalid(
                    "history must be stored outside repositories",
                ));
            }
            fs::create_dir_all(&base)?;
        }
        let base = base.canonicalize()?;
        if base.starts_with(&project) || inside_repository(&base) {
            return Err(Error::Invalid(
                "application data location is inside a repository",
            ));
        }
        let root = base.join("Flow");
        blobs::directory(&root)?;
        Self::open(&project, &root)
    }
    pub fn directory(&self) -> &Path {
        &self.directory
    }
    fn call<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Database) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let (reply, response) = mpsc::sync_channel(1);
        let job: Job = Box::new(move |db| {
            let result = f(db);
            if let Err(error) = &result
                && (matches!(
                    error,
                    Error::MissingContent(_)
                        | Error::CorruptContent(_)
                        | Error::CorruptMetadata
                        | Error::Io(_)
                ) || matches!(error, Error::Sql(rusqlite::Error::SqliteFailure(e, _)) if matches!(e.code, rusqlite::ErrorCode::DiskFull | rusqlite::ErrorCode::SystemIoFailure | rusqlite::ErrorCode::DatabaseCorrupt)))
            {
                db.damage = Some(error.to_string());
                let _ = db.conn.pragma_update(None, "query_only", true);
            }
            let _ = reply.send(result);
        });
        self.worker
            .sender
            .as_ref()
            .ok_or(Error::WorkerStopped)?
            .try_send(job)
            .map_err(|e| match e {
                TrySendError::Full(_) => Error::Busy,
                TrySendError::Disconnected(_) => Error::WorkerStopped,
            })?;
        response.recv().map_err(|_| Error::WorkerStopped)?
    }
    pub fn register_workspace(
        &self,
        key: MutationKey,
        mut workspace: Workspace,
    ) -> Result<MutationReceipt> {
        identifier(&workspace.owner_token)?;
        identifier(&workspace.lifecycle)?;
        if ![40, 64].contains(&workspace.base_commit.len())
            || !workspace.base_commit.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err(Error::Invalid("invalid baseline commit"));
        }
        workspace.worktree_path = workspace.worktree_path.canonicalize()?;
        workspace.git_common_dir = workspace.git_common_dir.canonicalize()?;
        if !workspace.worktree_path.is_dir() || !workspace.git_common_dir.is_dir() {
            return Err(Error::Invalid(
                "workspace and common Git paths must be directories",
            ));
        }
        let record = json(&workspace)?;
        self.call(move |db| {
            db.mutate(key, "workspace.register", &record, |tx| {
                tx.execute(
                    "INSERT INTO workspaces(id,path,record) VALUES(?1,?2,?3)",
                    params![
                        workspace.id.as_str(),
                        workspace
                            .worktree_path
                            .to_str()
                            .ok_or(Error::Invalid("non-UTF-8 worktree path"))?,
                        record
                    ],
                )?;
                event(
                    tx,
                    &workspace.id,
                    None,
                    "workspace.registered",
                    value!({"id":workspace.id}),
                )?;
                Ok(value!({"id":workspace.id}))
            })
        })
    }
    pub fn create_session(&self, key: MutationKey, session: Session) -> Result<MutationReceipt> {
        identifier(&session.status)?;
        interval(session.created_at_ms, session.created_at_ms)?;
        let record = json(&session)?;
        self.call(move |db| {
            db.mutate(key, "session.create", &record, |tx| {
                if tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM deletions WHERE session_id=?1)",
                    [session.id.as_str()],
                    |r| r.get::<_, bool>(0),
                )? {
                    return Err(Error::Conflict);
                }
                let baseline = load_snapshot(tx, &session.baseline)?;
                if baseline.manifest.workspace_id != session.workspace_id {
                    return Err(Error::Conflict);
                }
                tx.execute(
                    "INSERT INTO sessions(id,workspace_id,baseline,record) VALUES(?1,?2,?3,?4)",
                    params![
                        session.id.as_str(),
                        session.workspace_id.as_str(),
                        session.baseline.as_str(),
                        record
                    ],
                )?;
                event(
                    tx,
                    &session.workspace_id,
                    Some(&session.id),
                    "session.created",
                    value!({"id":session.id}),
                )?;
                Ok(value!({"id":session.id}))
            })
        })
    }
    pub fn create_turn(&self, key: MutationKey, turn: Turn) -> Result<MutationReceipt> {
        identifier(&turn.id)?;
        identifier(&turn.status)?;
        interval(turn.created_at_ms, turn.created_at_ms)?;
        let record = json(&turn)?;
        self.call(move |db| {
            db.mutate(key, "turn.create", &record, |tx| {
                let session: Session = load_record(
                    tx,
                    "SELECT record FROM sessions WHERE id=?1",
                    turn.session_id.as_str(),
                )?;
                tx.execute(
                    "INSERT INTO turns(id,session_id,record) VALUES(?1,?2,?3)",
                    params![turn.id, turn.session_id.as_str(), record],
                )?;
                event(
                    tx,
                    &session.workspace_id,
                    Some(&session.id),
                    "turn.created",
                    value!({"id":turn.id}),
                )?;
                Ok(value!({"id":turn.id}))
            })
        })
    }
    pub fn publish_snapshot(
        &self,
        key: MutationKey,
        input: SnapshotInput,
    ) -> Result<MutationReceipt> {
        validate_capture(&input)?;
        self.call(move |db| db.publish_snapshot(key, input))
    }
    pub fn record_operation(
        &self,
        key: MutationKey,
        input: OperationInput,
    ) -> Result<MutationReceipt> {
        identifier(&input.id)?;
        identifier(&input.turn_id)?;
        if let Some(parent) = &input.parent_id {
            identifier(parent)?;
        }
        interval(input.started_at_ms, input.completed_at_ms)?;
        let record = json(&input)?;
        self.call(move |db| {
        let receipt = db.mutate(key, "operation.record", &record, |tx| {
            let session: Session = load_record(tx, "SELECT record FROM sessions WHERE id=?1", input.session_id.as_str())?;
            let turn: Turn = load_record(tx, "SELECT record FROM turns WHERE id=?1", &input.turn_id)?;
            let before = load_snapshot(tx, &input.before)?;
            let after = load_snapshot(tx, &input.after)?;
            if turn.session_id != session.id || before.manifest.workspace_id != session.workspace_id || after.manifest.workspace_id != session.workspace_id {
                return Err(Error::Conflict);
            }
            if let Some(parent) = &input.parent_id {
                let parent: OperationInput = load_record(tx, "SELECT record FROM operations WHERE id=?1", parent)?;
                if parent.session_id != input.session_id { return Err(Error::Conflict); }
            }
            let sequence: u64 = tx.query_row("SELECT COALESCE(MAX(sequence),0)+1 FROM operations WHERE session_id=?1", [session.id.as_str()], |r| uint(r, 0))?;
            tx.execute("INSERT INTO operations(id,session_id,turn_id,before_id,after_id,sequence,record) VALUES(?1,?2,?3,?4,?5,?6,?7)", params![input.id, session.id.as_str(), input.turn_id, input.before.as_str(), input.after.as_str(), sequence as i64, record])?;
            for (path, change) in changes(&before.manifest, &after.manifest) {
                tx.execute("INSERT INTO file_changes(operation_id,path,record) VALUES(?1,?2,?3)", params![input.id, path, json(&change)?])?;
            }
            event(tx, &session.workspace_id, Some(&session.id), "operation.recorded", value!({"id":input.id,"sequence":sequence,"turnId":input.turn_id}))?;
            Ok(value!({"id":input.id,"sequence":sequence}))
        })?;
        boundary(Boundary::OperationCommitted, #[cfg(test)] db.crash);
        Ok(receipt)
        })
    }
    pub fn get_snapshot(&self, id: ContentId) -> Result<Snapshot> {
        self.call(move |db| load_snapshot(&db.conn, &id))
    }
    pub fn workspaces(
        &self,
        after: Option<WorkspaceId>,
        limit: u16,
    ) -> Result<Page<Workspace, String>> {
        page_limit(0, limit)?;
        self.call(move |db| {
            record_page(
                &db.conn,
                "SELECT id,record FROM workspaces WHERE id>?1 ORDER BY id LIMIT ?2",
                params![
                    after.as_ref().map(WorkspaceId::as_str).unwrap_or(""),
                    limit as u32 + 1
                ],
                limit,
            )
        })
    }
    pub fn sessions(
        &self,
        workspace: WorkspaceId,
        after: Option<SessionId>,
        limit: u16,
    ) -> Result<Page<Session, String>> {
        page_limit(0, limit)?;
        self.call(move |db| record_page(&db.conn, "SELECT id,record FROM sessions WHERE workspace_id=?1 AND id>?2 ORDER BY id LIMIT ?3", params![workspace.as_str(),after.as_ref().map(SessionId::as_str).unwrap_or(""),limit as u32 + 1], limit))
    }
    pub fn get_workspace(&self, id: WorkspaceId) -> Result<Workspace> {
        self.call(move |db| {
            load_record(
                &db.conn,
                "SELECT record FROM workspaces WHERE id=?1",
                id.as_str(),
            )
        })
    }
    pub fn get_session(&self, id: SessionId) -> Result<Session> {
        self.call(move |db| {
            load_record(
                &db.conn,
                "SELECT record FROM sessions WHERE id=?1",
                id.as_str(),
            )
        })
    }
    /// Persist runtime transitions and bounded agent events without claiming
    /// tool-reported edits are verified filesystem operations.
    pub fn context_event(&self, session: SessionId, payload: Value) -> Result<()> {
        if json(&payload)?.len() > 4096 {
            return Err(Error::BudgetExceeded);
        }
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let record: Session = load_record(
                &tx,
                "SELECT record FROM sessions WHERE id=?1",
                session.as_str(),
            )?;
            event(
                &tx,
                &record.workspace_id,
                Some(&session),
                "context.query",
                payload,
            )?;
            tx.commit()?;
            Ok(())
        })
    }
    pub fn agent_event(
        &self,
        session: SessionId,
        turn: Option<String>,
        status: String,
        kind: String,
        payload: Value,
    ) -> Result<()> {
        identifier(&status)?;
        identifier(&kind)?;
        if json(&payload)?.len() > 32 * 1024 {
            return Err(Error::Invalid("agent event exceeds limit"));
        }
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let mut record: Session = load_record(
                &tx,
                "SELECT record FROM sessions WHERE id=?1",
                session.as_str(),
            )?;
            record.status = status.clone();
            tx.execute(
                "UPDATE sessions SET record=?2 WHERE id=?1",
                params![session.as_str(), json(&record)?],
            )?;
            if let Some(turn) = turn {
                let mut record: Turn =
                    load_record(&tx, "SELECT record FROM turns WHERE id=?1", &turn)?;
                if record.session_id != session {
                    return Err(Error::Conflict);
                }
                record.status = status;
                tx.execute(
                    "UPDATE turns SET record=?2 WHERE id=?1",
                    params![turn, json(&record)?],
                )?;
            }
            if kind == "session.recovered" {
                let turns = tx
                    .prepare("SELECT record FROM turns WHERE session_id=?1")?
                    .query_map([session.as_str()], |r| r.get::<_, String>(0))?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                for raw in turns {
                    let mut turn: Turn = decode(&raw)?;
                    if ["running", "cancelling", "starting"].contains(&turn.status.as_str()) {
                        turn.status = "interrupted".into();
                        tx.execute(
                            "UPDATE turns SET record=?2 WHERE id=?1",
                            params![turn.id, json(&turn)?],
                        )?;
                    }
                }
            }
            event(&tx, &record.workspace_id, Some(&session), &kind, payload)?;
            tx.commit()?;
            Ok(())
        })
    }
    pub fn bind_agent(
        &self,
        id: SessionId,
        upstream: String,
        version: String,
        capabilities: Value,
    ) -> Result<()> {
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let mut record: Session =
                load_record(&tx, "SELECT record FROM sessions WHERE id=?1", id.as_str())?;
            record.upstream_id = Some(upstream);
            record.adapter_version = version;
            record.capabilities = capabilities;
            tx.execute(
                "UPDATE sessions SET record=?2 WHERE id=?1",
                params![id.as_str(), json(&record)?],
            )?;
            tx.commit()?;
            Ok(())
        })
    }
    /// New engine never silently resumes a previous process or its approvals.
    pub fn interrupt_agent_sessions(&self) -> Result<()> {
        self.call(|db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let records: Vec<String> = tx
                .prepare("SELECT record FROM sessions")?
                .query_map([], |r| r.get(0))?
                .collect::<std::result::Result<_, _>>()?;
            for raw in records {
                let mut session: Session = decode(&raw)?;
                if ![
                    "running",
                    "ready",
                    "starting",
                    "authenticating",
                    "authenticationRequired",
                    "cancelling",
                    "closing",
                ]
                .contains(&session.status.as_str())
                {
                    continue;
                }
                session.status = "interrupted".into();
                tx.execute(
                    "UPDATE sessions SET record=?2 WHERE id=?1",
                    params![session.id.as_str(), json(&session)?],
                )?;
                let turns: Vec<String> = tx
                    .prepare("SELECT record FROM turns WHERE session_id=?1")?
                    .query_map([session.id.as_str()], |r| r.get(0))?
                    .collect::<std::result::Result<_, _>>()?;
                for raw in turns {
                    let mut turn: Turn = decode(&raw)?;
                    if ["running", "cancelling", "closing"].contains(&turn.status.as_str()) {
                        turn.status = "interrupted".into();
                        tx.execute(
                            "UPDATE turns SET record=?2 WHERE id=?1",
                            params![turn.id, json(&turn)?],
                        )?;
                    }
                }
                event(
                    &tx,
                    &session.workspace_id,
                    Some(&session.id),
                    "session.interrupted",
                    value!({}),
                )?;
            }
            tx.commit()?;
            Ok(())
        })
    }
    pub fn get_turn(&self, id: String) -> Result<Turn> {
        self.call(move |db| load_record(&db.conn, "SELECT record FROM turns WHERE id=?1", &id))
    }
    pub fn get_operation(&self, id: String) -> Result<Operation> {
        self.call(move |db| {
            let input = load_record(&db.conn, "SELECT record FROM operations WHERE id=?1", &id)?;
            let sequence =
                db.conn
                    .query_row("SELECT sequence FROM operations WHERE id=?1", [&id], |r| {
                        uint(r, 0)
                    })?;
            let mut stmt = db
                .conn
                .prepare("SELECT record FROM file_changes WHERE operation_id=?1 ORDER BY path")?;
            let changes = stmt
                .query_map([&id], |r| r.get::<_, String>(0))?
                .map(|r| decode(&r?))
                .collect::<Result<Vec<_>>>()?;
            Ok(Operation {
                input,
                sequence,
                changes,
            })
        })
    }
    pub fn snapshots(
        &self,
        workspace: WorkspaceId,
        after: u64,
        limit: u16,
    ) -> Result<Page<SnapshotSummary>> {
        page_limit(after, limit)?;
        self.call(move |db| {
            let mut stmt = db.conn.prepare("SELECT sequence,id,manifest FROM snapshots WHERE workspace_id=?1 AND sequence>?2 ORDER BY sequence LIMIT ?3")?;
            let rows = stmt.query_map(params![workspace.as_str(), after as i64, limit as u32 + 1], |r| Ok((uint(r, 0)?,r.get::<_, String>(1)?,r.get::<_, String>(2)?)))?;
            let mut items = Vec::new();
            for row in rows {
                let (sequence, id, text) = row?;
                let manifest: Manifest = decode(&text)?;
                items.push(SnapshotSummary { sequence, id: ContentId::try_from(id).map_err(|_| Error::CorruptMetadata)?, completed_at_ms:manifest.completed_at_ms });
            }
            let next = if items.len() > limit as usize { items.truncate(limit as usize); items.last().map(|i| i.sequence) } else { None };
            Ok(Page { items, next })
        })
    }
    pub fn operations(
        &self,
        session: SessionId,
        after: u64,
        limit: u16,
    ) -> Result<Page<OperationInput>> {
        self.operations_for_turn(session, None, after, limit)
    }
    pub fn operations_for_turn(
        &self,
        session: SessionId,
        turn: Option<String>,
        after: u64,
        limit: u16,
    ) -> Result<Page<OperationInput>> {
        page_limit(after, limit)?;
        self.call(move |db| {
            let mut stmt = db.conn.prepare("SELECT sequence,record FROM operations WHERE session_id=?1 AND sequence>?2 AND (?4 IS NULL OR turn_id=?4) ORDER BY sequence LIMIT ?3")?;
            let rows = stmt.query_map(params![session.as_str(), after as i64, limit as u32 + 1,turn], |r| Ok((uint(r, 0)?,r.get::<_, String>(1)?)))?;
            let mut entries = Vec::new();
            for row in rows { let (seq, data) = row?; entries.push((seq, decode(&data)?)); }
            let next = if entries.len() > limit as usize { entries.truncate(limit as usize); entries.last().map(|e| e.0) } else { None };
            Ok(Page { items: entries.into_iter().map(|e| e.1).collect(), next })
        })
    }
    pub fn read_content(&self, id: ContentId, offset: u64, length: usize) -> Result<ContentChunk> {
        if length == 0 || length > MAX_CHUNK_BYTES {
            return Err(Error::Invalid("invalid content chunk length"));
        }
        self.call(move |db| {
            let size: u64 = db
                .conn
                .query_row("SELECT size FROM blobs WHERE id=?1", [id.as_str()], |r| {
                    uint(r, 0)
                })
                .optional()?
                .ok_or(Error::NotFound)?;
            if offset > size {
                return Err(Error::Invalid("content offset exceeds size"));
            }
            // ponytail: verify/decompress the bounded whole object per chunk. Add seekable
            // frames or a bounded verified cache only when recorder benchmarks justify it.
            let bytes = db.blobs.read(&id, size)?;
            let end = (offset + length as u64).min(size);
            Ok(ContentChunk {
                bytes: bytes[offset as usize..end as usize].to_vec(),
                total_bytes: size,
                next_offset: (end < size).then_some(end),
            })
        })
    }
}

fn inside_repository(path: &Path) -> bool {
    path.ancestors().any(|p| {
        fs::symlink_metadata(p.join(".git")).is_ok()
            || (p.join("HEAD").is_file() && p.join("objects").is_dir() && p.join("refs").is_dir())
    })
}
fn page_limit(after: u64, limit: u16) -> Result<()> {
    if after > i64::MAX as u64 || limit == 0 || limit > MAX_PAGE_SIZE {
        return Err(Error::Invalid("invalid history page"));
    }
    Ok(())
}
fn validate_capture(input: &SnapshotInput) -> Result<()> {
    interval(input.started_at_ms, input.completed_at_ms)?;
    if input.files.len() > MAX_ENTRIES
        || input.coverage.excluded.len() > MAX_ENTRIES
        || input.provenance.len() > 4096
    {
        return Err(Error::Invalid("capture exceeds metadata limits"));
    }
    if input.coverage.complete && !input.coverage.excluded.is_empty() {
        return Err(Error::Invalid("complete capture cannot have exclusions"));
    }
    let mut total = 0usize;
    for (name, file) in &input.files {
        path(name)?;
        if file.mode > 0o7777 || file.bytes.len() > MAX_BLOB_BYTES {
            return Err(Error::Invalid("invalid file mode or size"));
        }
        total = total
            .checked_add(file.bytes.len())
            .ok_or(Error::Invalid("capture size overflow"))?;
        if total > MAX_CAPTURE_BYTES {
            return Err(Error::Invalid("capture exceeds size limit"));
        }
        for prefix in name.match_indices('/').map(|(i, _)| &name[..i]) {
            if input.files.contains_key(prefix) {
                return Err(Error::Invalid("file is also a path ancestor"));
            }
        }
    }
    for name in &input.coverage.excluded {
        path(name)?;
    }
    Ok(())
}
fn changes(before: &Manifest, after: &Manifest) -> Vec<(String, FileChange)> {
    let paths: BTreeSet<_> = before.files.keys().chain(after.files.keys()).collect();
    let mut changes: Vec<_> = paths
        .into_iter()
        .filter_map(|p| {
            let a = before.files.get(p);
            let b = after.files.get(p);
            // Absence in an incomplete capture is unknown, not creation/deletion.
            if (a.is_none() && !before.coverage.complete)
                || (b.is_none() && !after.coverage.complete)
            {
                return None;
            }
            (a != b).then(|| {
                (
                    p.clone(),
                    FileChange {
                        old_path: a.map(|_| p.clone()),
                        new_path: b.map(|_| p.clone()),
                        before: a.cloned(),
                        after: b.cloned(),
                    },
                )
            })
        })
        .collect();
    // Infer only unique delete/create content matches. Duplicate copies remain
    // separate changes: snapshots cannot establish which file was renamed.
    let mut removed = BTreeSet::new();
    let mut renames = Vec::new();
    for (i, (_, deleted)) in changes.iter().enumerate() {
        let Some(before) = deleted.before.as_ref().filter(|_| deleted.after.is_none()) else {
            continue;
        };
        let candidates: Vec<_> = changes
            .iter()
            .enumerate()
            .filter(|(_, (_, created))| {
                created.before.is_none()
                    && created
                        .after
                        .as_ref()
                        .is_some_and(|after| after.blob == before.blob && after.kind == before.kind)
            })
            .collect();
        let deleted_count = changes
            .iter()
            .filter(|(_, other)| {
                other.after.is_none()
                    && other
                        .before
                        .as_ref()
                        .is_some_and(|entry| entry.blob == before.blob && entry.kind == before.kind)
            })
            .count();
        if candidates.len() == 1 && deleted_count == 1 {
            let (j, (path, created)) = candidates[0];
            removed.insert(i);
            removed.insert(j);
            renames.push((
                path.clone(),
                FileChange {
                    old_path: deleted.old_path.clone(),
                    new_path: created.new_path.clone(),
                    before: deleted.before.clone(),
                    after: created.after.clone(),
                },
            ));
        }
    }
    changes = changes
        .into_iter()
        .enumerate()
        .filter_map(|(i, change)| (!removed.contains(&i)).then_some(change))
        .collect();
    changes.extend(renames);
    changes.sort_by(|a, b| a.0.cmp(&b.0));
    changes
}
fn record_page<T: serde::de::DeserializeOwned>(
    conn: &Connection,
    sql: &str,
    params: impl rusqlite::Params,
    limit: u16,
) -> Result<Page<T, String>> {
    let mut stmt = conn.prepare(sql)?;
    let rows = stmt.query_map(params, |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
    })?;
    let mut records = Vec::new();
    for row in rows {
        let (id, text) = row?;
        records.push((id, decode(&text)?));
    }
    let next = if records.len() > limit as usize {
        records.truncate(limit as usize);
        records.last().map(|(id, _)| id.clone())
    } else {
        None
    };
    Ok(Page {
        items: records.into_iter().map(|(_, record)| record).collect(),
        next,
    })
}
fn load_record<T: serde::de::DeserializeOwned>(
    conn: &Connection,
    sql: &str,
    id: &str,
) -> Result<T> {
    let text: String = conn
        .query_row(sql, [id], |r| r.get(0))
        .optional()?
        .ok_or(Error::NotFound)?;
    decode(&text)
}
fn load_snapshot(conn: &Connection, id: &ContentId) -> Result<Snapshot> {
    let text: String = conn
        .query_row(
            "SELECT manifest FROM snapshots WHERE id=?1",
            [id.as_str()],
            |r| r.get(0),
        )
        .optional()?
        .ok_or(Error::NotFound)?;
    if ContentId::hash(text.as_bytes()) != *id {
        return Err(Error::CorruptMetadata);
    }
    Ok(Snapshot {
        id: id.clone(),
        manifest: decode(&text)?,
    })
}
fn receipt(conn: &Connection, key: &MutationKey) -> Result<Option<MutationReceipt>> {
    let text: Option<String> = conn
        .query_row(
            "SELECT receipt FROM mutations WHERE key=?1",
            [key.as_str()],
            |r| r.get(0),
        )
        .optional()?;
    text.map(|s| decode(&s)).transpose()
}
fn write_receipt(tx: &Transaction<'_>, r: &MutationReceipt) -> Result<()> {
    tx.execute("INSERT INTO mutations(key,receipt) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET receipt=excluded.receipt", params![r.key.as_str(),json(r)?])?;
    Ok(())
}
fn event(
    tx: &Transaction<'_>,
    workspace: &WorkspaceId,
    session: Option<&SessionId>,
    kind: &str,
    payload: Value,
) -> Result<()> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| Error::Invalid("clock precedes epoch"))?
        .as_millis();
    tx.execute("INSERT INTO events(workspace_id,session_id,kind,payload,created_at_ms) VALUES(?1,?2,?3,?4,?5)", params![workspace.as_str(), session.map(SessionId::as_str), kind, json(&payload)?, i64::try_from(now).map_err(|_| Error::Invalid("clock out of range"))?])?;
    Ok(())
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Boundary {
    BlobPublished,
    BeforeCommit,
    AfterCommit,
    OperationCommitted,
}
struct Database {
    conn: Connection,
    blobs: Blobs,
    store_id: StoreId,
    _lock: File,
    directory: PathBuf,
    damage: Option<String>,
    pending_at_open: bool,
    #[cfg(test)]
    crash: Option<Boundary>,
}
fn boundary(point: Boundary, #[cfg(test)] crash: Option<Boundary>) {
    #[cfg(test)]
    if crash == Some(point) {
        std::process::exit(86);
    }
    #[cfg(not(test))]
    let _ = point;
}
impl Database {
    fn open(directory: &Path, project: &str) -> Result<Self> {
        let lock_path = directory.join("writer.lock");
        if fs::symlink_metadata(&lock_path).is_ok() {
            blobs::regular(&lock_path)?;
        }
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(lock_path)?;
        crate::try_lock_file(&lock).map_err(|_| Error::Locked)?;
        let content = directory.join("blobs");
        blobs::directory(&content)?;
        let path = directory.join("history.sqlite3");
        for file in [
            path.clone(),
            directory.join("history.sqlite3-wal"),
            directory.join("history.sqlite3-shm"),
        ] {
            if fs::symlink_metadata(&file).is_ok() {
                blobs::regular(&file)?;
            }
        }
        let mut conn = Connection::open(&path)?;
        conn.busy_timeout(Duration::from_secs(2))?;
        let version: u32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version > SCHEMA_VERSION {
            return Err(Error::UnsupportedSchema(version));
        }
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.pragma_update(None, "synchronous", "FULL")?;
        conn.pragma_update(None, "fullfsync", "ON")?;
        if version > 0 {
            let stored: String =
                conn.query_row("SELECT project_root FROM meta WHERE singleton=1", [], |r| {
                    r.get(0)
                })?;
            if stored != project {
                return Err(Error::Conflict);
            }
        } else {
            let tables: u64 = conn.query_row("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", [], |r| uint(r, 0))?;
            if tables != 0 {
                return Err(Error::CorruptMetadata);
            }
        }
        if version < SCHEMA_VERSION {
            if version > 0 {
                let backup =
                    directory.join(format!("migration-v{version}-{}.sqlite3", random_id()?));
                // VACUUM INTO includes committed WAL contents; never copy the main DB alone.
                conn.execute(
                    "VACUUM INTO ?1",
                    [backup
                        .to_str()
                        .ok_or(Error::Invalid("non-UTF-8 backup path"))?],
                )?;
                blobs::sync_file(&backup)?;
                blobs::sync_dir(directory)?;
            }
            let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            if version < 1 {
                tx.execute_batch(include_str!("schema-1.sql"))?;
                tx.execute(
                    "INSERT INTO meta VALUES(1,?1,?2)",
                    params![random_id()?, project],
                )?;
            }
            if version < 2 {
                tx.execute_batch(include_str!("schema-2.sql"))?;
            }
            if version < 3 {
                tx.execute_batch(include_str!("schema-3.sql"))?;
            }
            if version < 4 {
                tx.execute_batch(include_str!("schema-4.sql"))?;
            }
            if version < 5 {
                tx.execute_batch(include_str!("schema-5.sql"))?;
            }
            if version < 6 {
                tx.execute_batch(include_str!("schema-6.sql"))?;
            }
            if version < 7 {
                tx.execute_batch(include_str!("schema-7.sql"))?;
            }
            if version < 8 {
                tx.execute_batch(include_str!("schema-8.sql"))?;
            }
            tx.pragma_update(None, "user_version", SCHEMA_VERSION)?;
            tx.commit()?;
        }
        conn.pragma_update(None, "journal_mode", "WAL")?;
        // A new engine owner never replays an unsent or unknown-outcome task.
        conn.execute("UPDATE queue_policy SET paused=1", [])?;
        conn.execute(
            "UPDATE prompt_queue SET status='uncertain' WHERE status='dispatching'",
            [],
        )?;
        // A fresh owner never replays a validation command with an unknown
        // outcome. The next explicit check needs a fresh intent ID.
        conn.execute("UPDATE candidate_checks SET record=json_set(record,'$.status','interrupted') WHERE json_extract(record,'$.status')='running'", [])?;
        conn.pragma_update(None, "synchronous", "FULL")?;
        conn.execute("UPDATE apply_journals SET record=json_set(record,'$.status','recoveryRequired') WHERE json_extract(record,'$.status') IN ('applying','rollingBack')", [])?;
        // macOS requests F_FULLFSYNC through SQLite's VFS when available.
        conn.pragma_update(None, "fullfsync", "ON")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
        }
        blobs::sync_dir(directory)?;
        let id: String =
            conn.query_row("SELECT store_id FROM meta WHERE singleton=1", [], |r| {
                r.get(0)
            })?;
        let mut db = Self {
            conn,
            directory: directory.to_owned(),
            damage: None,
            pending_at_open: false,
            blobs: Blobs { root: content },
            store_id: StoreId::try_from(id).map_err(|_| Error::CorruptMetadata)?,
            _lock: lock,
            #[cfg(test)]
            crash: None,
        };
        if let Err(error) = db.integrity() {
            // Keep independently readable metadata/content available. Never repair
            // or discard damaged history implicitly.
            db.damage = Some(error.to_string());
            db.conn.pragma_update(None, "query_only", true)?;
        }
        let report = db.storage_report()?;
        db.pending_at_open =
            report.pending_mutations > 0 || report.holds > 0 || report.pending_applications > 0;
        Ok(db)
    }
    fn integrity(&self) -> Result<()> {
        candidates::integrity(&self.conn)?;
        application::integrity(&self.conn)?;
        let check: String = self
            .conn
            .query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        if check != "ok"
            || self
                .conn
                .prepare("PRAGMA foreign_key_check")?
                .query([])?
                .next()?
                .is_some()
        {
            return Err(Error::CorruptMetadata);
        }
        // ponytail: verify all referenced bytes at startup. Incremental audits belong
        // after measured repository-size limits, not before correctness is established.
        let mut stmt = self.conn.prepare("SELECT id,size FROM blobs")?;
        for row in stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, uint(r, 1)?)))? {
            let (id, size) = row?;
            let id = ContentId::try_from(id).map_err(|_| Error::CorruptMetadata)?;
            self.blobs.read(&id, size)?;
        }
        let mut stmt = self
            .conn
            .prepare("SELECT id,workspace_id,manifest FROM snapshots")?;
        for row in stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })? {
            let (id, workspace, text) = row?;
            if ContentId::hash(text.as_bytes()).as_str() != id {
                return Err(Error::CorruptMetadata);
            }
            let manifest: Manifest = decode(&text)?;
            if manifest.workspace_id.as_str() != workspace {
                return Err(Error::CorruptMetadata);
            }
            let mut expected = BTreeSet::new();
            for entry in manifest.files.values() {
                let size: u64 = self
                    .conn
                    .query_row(
                        "SELECT size FROM blobs WHERE id=?1",
                        [entry.blob.as_str()],
                        |r| uint(r, 0),
                    )
                    .optional()?
                    .ok_or(Error::CorruptMetadata)?;
                if size != entry.size {
                    return Err(Error::CorruptMetadata);
                }
                expected.insert(entry.blob.as_str().to_owned());
            }
            let mut refs = self
                .conn
                .prepare("SELECT blob_id FROM snapshot_blobs WHERE snapshot_id=?1")?;
            let actual = refs
                .query_map([id], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<BTreeSet<_>, _>>()?;
            if expected != actual {
                return Err(Error::CorruptMetadata);
            }
        }
        Ok(())
    }
    fn mutate(
        &mut self,
        key: MutationKey,
        method: &str,
        record: &str,
        apply: impl FnOnce(&Transaction<'_>) -> Result<Value>,
    ) -> Result<MutationReceipt> {
        self.require_write(0)?;
        let mut hasher = blake3::Hasher::new();
        hasher
            .update(method.as_bytes())
            .update(b"\0")
            .update(record.as_bytes());
        let digest = hasher.finalize().to_hex().to_string();
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        if let Some(r) = receipt(&tx, &key)? {
            if r.request_digest != digest {
                return Err(Error::Conflict);
            }
            if r.status == MutationStatus::Succeeded {
                return Ok(r);
            }
            if !matches!(r.status, MutationStatus::Accepted | MutationStatus::Running) {
                return Err(Error::Conflict);
            }
        }
        let outcome = apply(&tx)?;
        let r = MutationReceipt {
            key,
            request_digest: digest,
            status: MutationStatus::Succeeded,
            outcome: Some(outcome),
        };
        write_receipt(&tx, &r)?;
        boundary(
            Boundary::BeforeCommit,
            #[cfg(test)]
            self.crash,
        );
        tx.commit()?;
        boundary(
            Boundary::AfterCommit,
            #[cfg(test)]
            self.crash,
        );
        Ok(r)
    }
    fn publish_snapshot(
        &mut self,
        key: MutationKey,
        input: SnapshotInput,
    ) -> Result<MutationReceipt> {
        self.require_write(
            input
                .files
                .values()
                .map(|f| f.bytes.len() as u64 + 1024)
                .sum(),
        )?;
        let mut files = BTreeMap::new();
        for (name, file) in &input.files {
            let blob = self.blobs.put(&file.bytes)?;
            files.insert(
                name.clone(),
                FileEntry {
                    blob,
                    size: file.bytes.len() as u64,
                    mode: file.mode,
                    kind: file.kind.clone(),
                },
            );
        }
        let manifest = Manifest {
            workspace_id: input.workspace_id,
            files,
            coverage: input.coverage,
            started_at_ms: input.started_at_ms,
            completed_at_ms: input.completed_at_ms,
            provenance: input.provenance,
        };
        let record = json(&manifest)?;
        let id = ContentId::hash(record.as_bytes());
        boundary(
            Boundary::BlobPublished,
            #[cfg(test)]
            self.crash,
        );
        self.mutate(key, "snapshot.publish", &record, |tx| {
            for entry in manifest.files.values() {
                tx.execute("INSERT INTO blobs(id,size) VALUES(?1,?2) ON CONFLICT(id) DO NOTHING", params![entry.blob.as_str(),entry.size as i64])?;
                let size: u64 = tx.query_row("SELECT size FROM blobs WHERE id=?1", [entry.blob.as_str()], |r| uint(r, 0))?;
                if size != entry.size { return Err(Error::CorruptMetadata); }
            }
            let inserted = tx.execute("INSERT INTO snapshots(id,workspace_id,manifest) VALUES(?1,?2,?3) ON CONFLICT(id) DO NOTHING", params![id.as_str(),manifest.workspace_id.as_str(),record])?;
            for entry in manifest.files.values() {
                tx.execute("INSERT OR IGNORE INTO snapshot_blobs(snapshot_id,blob_id) VALUES(?1,?2)", params![id.as_str(),entry.blob.as_str()])?;
            }
            if inserted > 0 { event(tx, &manifest.workspace_id, None, "snapshot.published", value!({"id":id}))?; }
            Ok(value!({"id":id}))
        })
    }
}
fn random_id() -> Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| Error::Invalid("random identity unavailable"))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

impl ProtocolStore for HistoryStore {
    type Error = Error;
    fn snapshot(&self) -> Result<StateSnapshot> {
        self.call(|db| {
            let report = db.storage_report()?;
            let tx = db.conn.transaction()?;
            let high: u64 = tx.query_row("SELECT COALESCE(MAX(sequence),0) FROM events", [], |r| uint(r, 0))?;
            let (workspaces,sessions,snapshots,operations): (u64,u64,u64,u64) = tx.query_row("SELECT (SELECT COUNT(*) FROM workspaces),(SELECT COUNT(*) FROM sessions),(SELECT COUNT(*) FROM snapshots),(SELECT COUNT(*) FROM operations)", [], |r| Ok((uint(r,0)?,uint(r,1)?,uint(r,2)?,uint(r,3)?)))?;
            let project: String = tx.query_row("SELECT project_root FROM meta WHERE singleton=1", [], |r| r.get(0))?;
            Ok(StateSnapshot { high_water:EventCursor { store_id:db.store_id.clone(),sequence:EventSequence(high) }, state:value!({"projectRoot":project,"workspaces":workspaces,"sessions":sessions,"snapshots":snapshots,"operations":operations,"history":report}) })
        })
    }
    fn replay(&self, query: &ReplayParams) -> Result<ReplayPage> {
        let query = query.clone();
        page_limit(query.after.sequence.0, query.limit)?;
        self.call(move |db| {
            if query.after.store_id != db.store_id { return Err(Error::InvalidCursor); }
            let tx = db.conn.transaction()?;
            let high: u64 = tx.query_row("SELECT COALESCE(MAX(sequence),0) FROM events", [], |r| uint(r, 0))?;
            let after = query.after.sequence.0;
            if after > high || (after > 0 && !tx.query_row("SELECT EXISTS(SELECT 1 FROM events WHERE sequence=?1)", [after as i64], |r| r.get::<_, bool>(0))?) {
                return Err(Error::InvalidCursor);
            }
            let mut stmt = tx.prepare("SELECT sequence,workspace_id,session_id,kind,payload FROM events WHERE sequence>?1 ORDER BY sequence LIMIT ?2")?;
            let rows = stmt.query_map(params![after as i64,query.limit], |r| Ok((uint(r, 0)?,r.get::<_,String>(1)?,r.get::<_,Option<String>>(2)?,r.get::<_,String>(3)?,r.get::<_,String>(4)?)))?;
            let mut events = Vec::new();
            let mut last = after;
            for row in rows {
                let (seq,workspace,session,kind,payload) = row?;
                if seq != last + 1 { return Err(Error::InvalidCursor); }
                last = seq;
                events.push(DurableEvent { cursor:EventCursor { store_id:db.store_id.clone(),sequence:EventSequence(seq) },schema_version:1,workspace_id:WorkspaceId::try_from(workspace).map_err(|_| Error::CorruptMetadata)?,session_id:session.map(SessionId::try_from).transpose().map_err(|_| Error::CorruptMetadata)?,kind,payload:decode(&payload)? });
            }
            Ok(ReplayPage { events,high_water:EventCursor { store_id:db.store_id.clone(),sequence:EventSequence(high) },has_more:last < high })
        })
    }
    fn mutation(&self, key: &MutationKey) -> Result<Option<MutationReceipt>> {
        let key = key.clone();
        self.call(move |db| receipt(&db.conn, &key))
    }
    fn claim_mutation(&mut self, key: &MutationKey, digest: &str) -> Result<MutationReceipt> {
        ContentId::try_from(digest.to_owned())
            .map_err(|_| Error::Invalid("invalid mutation digest"))?;
        let key = key.clone();
        let digest = digest.to_owned();
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            if let Some(r) = receipt(&tx, &key)? {
                return if r.request_digest == digest {
                    Ok(r)
                } else {
                    Err(Error::Conflict)
                };
            }
            let r = MutationReceipt {
                key,
                request_digest: digest,
                status: MutationStatus::Accepted,
                outcome: None,
            };
            write_receipt(&tx, &r)?;
            tx.commit()?;
            Ok(r)
        })
    }
    fn finish_mutation(&mut self, new: &MutationReceipt) -> Result<()> {
        let new = new.clone();
        json(&new)?;
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let old = receipt(&tx, &new.key)?.ok_or(Error::NotFound)?;
            if json(&old)? == json(&new)? {
                return Ok(());
            }
            if old.request_digest != new.request_digest
                || !matches!(
                    old.status,
                    MutationStatus::Accepted | MutationStatus::Running
                )
                || !matches!(
                    new.status,
                    MutationStatus::Running | MutationStatus::Succeeded | MutationStatus::Failed
                )
            {
                return Err(Error::Conflict);
            }
            write_receipt(&tx, &new)?;
            tx.commit()?;
            Ok(())
        })
    }
}

#[cfg(test)]
mod tests;

fn uint(row: &rusqlite::Row<'_>, index: usize) -> rusqlite::Result<u64> {
    let value: i64 = row.get(index)?;
    value
        .try_into()
        .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(index, value))
}
