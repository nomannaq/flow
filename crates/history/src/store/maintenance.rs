//! B25: bounded admission, explicit retention manifests and recovery diagnostics.
//! No age policy, orphan sweep or filesystem worktree deletion.
use super::*;
use serde::Serialize;

// Admission reserve for metadata/WAL growth. This is a conservative application
// budget, not an OS disk quota; SQLite still handles ENOSPC transactionally.
const WRITE_RESERVE: u64 = 16 * 1024 * 1024;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageReport {
    pub bytes: u64,
    pub budget_bytes: u64,
    pub writable: bool,
    pub reason: Option<String>,
    pub pending_mutations: u64,
    pub pending_applications: u64,
    pub pending_deletions: u64,
    pub holds: u64,
    pub unlinked_snapshots: u64,
}

impl Database {
    pub(super) fn storage_report(&self) -> Result<StorageReport> {
        let budget_bytes = self.conn.query_row(
            "SELECT budget_bytes FROM storage_policy WHERE singleton=1",
            [],
            |r| uint(r, 0),
        )?;
        let pending_mutations = self.conn.query_row("SELECT COUNT(*) FROM mutations WHERE json_extract(receipt,'$.status') NOT IN ('succeeded','failed')", [], |r| uint(r, 0))?;
        let pending_applications = self.conn.query_row("SELECT COUNT(*) FROM apply_journals WHERE json_extract(record,'$.status') IN ('applying','rollingBack','recoveryRequired','recoveryConflict')", [], |r| uint(r,0))?;
        let holds = self
            .conn
            .query_row("SELECT COUNT(*) FROM history_holds", [], |r| uint(r, 0))?;
        let pending_deletions = self.conn.query_row(
            "SELECT COUNT(*) FROM deletions WHERE completed=0",
            [],
            |r| uint(r, 0),
        )?;
        let unlinked_snapshots = self.conn.query_row("SELECT COUNT(*) FROM snapshots s WHERE NOT EXISTS(SELECT 1 FROM sessions WHERE baseline=s.id) AND NOT EXISTS(SELECT 1 FROM operations WHERE before_id=s.id OR after_id=s.id) AND NOT EXISTS(SELECT 1 FROM candidates WHERE baseline_id=s.id OR source_id=s.id OR result_id=s.id) AND NOT EXISTS(SELECT 1 FROM apply_journals WHERE current_id=s.id)", [], |r| uint(r, 0))?;
        let bytes = storage_bytes(&self.directory)?;
        let reason = self.damage.clone().or_else(|| {
            if self.pending_at_open { Some("Unresolved mutations or history holds require a recovery handler; nothing was replayed".into()) }
            else if holds > 0 || pending_applications > 0 { Some("Application or retained history requires recovery; inspect the application journal".into()) }
            else if bytes.saturating_add(WRITE_RESERVE) > budget_bytes { Some(Error::BudgetExceeded.to_string()) }
            else { None }
        });
        Ok(StorageReport {
            bytes,
            budget_bytes,
            writable: reason.is_none(),
            reason,
            pending_mutations,
            pending_applications,
            pending_deletions,
            holds,
            unlinked_snapshots,
        })
    }
    pub(super) fn require_healthy(&self) -> Result<()> {
        if self.damage.is_some() {
            return Err(Error::ReadOnly);
        }
        Ok(())
    }
    pub(super) fn require_write(&self, additional: u64) -> Result<()> {
        self.require_healthy()?;
        let report = self.storage_report()?;
        if self.pending_at_open || report.holds > 0 || report.pending_applications > 0 {
            return Err(Error::ReadOnly);
        }
        if report
            .bytes
            .saturating_add(additional)
            .saturating_add(WRITE_RESERVE)
            > report.budget_bytes
        {
            return Err(Error::BudgetExceeded);
        }
        Ok(())
    }
    pub(super) fn finish_deletions(&mut self) -> Result<()> {
        self.require_healthy()?;
        let pending = self
            .conn
            .prepare("SELECT session_id,blobs,attachment_files FROM deletions WHERE completed=0")?
            .query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        for (session, raw, attachment_files) in pending {
            let cached: Vec<String> = decode(&attachment_files)?;
            for id in cached {
                let reachable: bool = self.conn.query_row(
                    "SELECT EXISTS(SELECT 1 FROM attachments WHERE id=?1)",
                    [&id],
                    |r| r.get(0),
                )?;
                if !reachable {
                    super::attachments::remove_cache(
                        &self.blobs.root.parent().unwrap().join("attachment-files"),
                        &id,
                    )?;
                }
            }
            let candidates: Vec<ContentId> = decode(&raw)?;
            for id in candidates {
                // A later publication may have reused an object after a crash.
                let reachable: bool = self.conn.query_row(
                    "SELECT EXISTS(SELECT 1 FROM blobs WHERE id=?1)",
                    [id.as_str()],
                    |r| r.get(0),
                )?;
                if reachable {
                    continue;
                }
                let path = self.blobs.root.join(format!("{}.zst", id.as_str()));
                match fs::symlink_metadata(&path) {
                    Ok(_) => {
                        blobs::regular(&path)?;
                        fs::remove_file(path)?;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => return Err(e.into()),
                }
            }
            blobs::sync_dir(&self.blobs.root)?;
            self.conn.execute(
                "UPDATE deletions SET completed=1 WHERE session_id=?1",
                [session],
            )?;
        }
        Ok(())
    }
}

// Count physical history files, including WAL, backups and unpublished blobs.
// Worktrees are intentionally excluded: externally running agents can grow them
// independently of Flow. Never traverse directory symlinks.
fn storage_bytes(root: &Path) -> Result<u64> {
    let mut total = 0u64;
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if entry.file_name() == "worktrees" {
            continue;
        }
        let meta = fs::symlink_metadata(entry.path())?;
        let size = if meta.is_dir() {
            storage_bytes(&entry.path())?
        } else {
            meta.len()
        };
        total = total
            .checked_add(size)
            .ok_or(Error::Invalid("storage size overflow"))?;
    }
    Ok(total)
}

impl HistoryStore {
    pub fn sessions_page(
        &self,
        after: Option<SessionId>,
        limit: u16,
    ) -> Result<Page<Session, String>> {
        page_limit(0, limit)?;
        self.call(move |db| {
            record_page(
                &db.conn,
                "SELECT id,record FROM sessions WHERE id>?1 ORDER BY id LIMIT ?2",
                params![
                    after.as_ref().map(SessionId::as_str).unwrap_or(""),
                    limit as u32 + 1
                ],
                limit,
            )
        })
    }

    pub fn storage_report(&self) -> Result<StorageReport> {
        self.call(|db| db.storage_report())
    }

    /// Explicitly adjustable even when the old budget is exhausted. Does not
    /// remove data or bypass damage/unknown-outcome recovery gates.
    pub fn set_storage_budget(&self, bytes: u64) -> Result<()> {
        if bytes == 0 || bytes > i64::MAX as u64 {
            return Err(Error::Invalid("invalid storage budget"));
        }
        self.call(move |db| {
            db.require_healthy()?;
            db.conn.execute(
                "UPDATE storage_policy SET budget_bytes=?1 WHERE singleton=1",
                [bytes as i64],
            )?;
            Ok(())
        })
    }

    /// Persist before introducing any operation that can outlive a reader or
    /// mutate external files (e.g. B19 application). Release only after that
    /// feature's recovery handler has verified the outcome. Retention fails closed.
    pub fn hold_history(&self, id: String, session: SessionId, reason: String) -> Result<()> {
        identifier(&id)?;
        if reason.len() > 4096 {
            return Err(Error::Invalid("recovery reason too large"));
        }
        self.call(move |db| {
            db.require_write(0)?;
            db.conn.execute(
                "INSERT INTO history_holds VALUES(?1,?2,?3)",
                params![id, session.as_str(), reason],
            )?;
            Ok(())
        })
    }
    pub fn release_history(&self, id: String) -> Result<()> {
        self.call(move |db| {
            db.require_healthy()?;
            db.conn
                .execute("DELETE FROM history_holds WHERE id=?1", [id])?;
            let report = db.storage_report()?;
            db.pending_at_open =
                report.pending_mutations > 0 || report.holds > 0 || report.pending_applications > 0;
            Ok(())
        })
    }

    /// Recovery hook for a future mutation-specific handler. The handler must
    /// inspect its external journal before supplying this terminal outcome;
    /// generic startup/CLI deliberately never calls it or guesses success.
    pub fn resolve_interrupted_mutation(&self, resolved: MutationReceipt) -> Result<()> {
        if !matches!(
            resolved.status,
            MutationStatus::Succeeded | MutationStatus::Failed
        ) {
            return Err(Error::Invalid("recovery requires a terminal outcome"));
        }
        json(&resolved)?;
        self.call(move |db| {
            db.require_healthy()?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let old = receipt(&tx, &resolved.key)?.ok_or(Error::NotFound)?;
            if old.request_digest != resolved.request_digest
                || matches!(
                    old.status,
                    MutationStatus::Succeeded | MutationStatus::Failed
                )
            {
                return Err(Error::Conflict);
            }
            write_receipt(&tx, &resolved)?;
            tx.commit()?;
            let report = db.storage_report()?;
            db.pending_at_open =
                report.pending_mutations > 0 || report.holds > 0 || report.pending_applications > 0;
            Ok(())
        })
    }

    /// Last durable operation, never a merely published/orphan snapshot.
    pub fn checkpoint(&self, session: SessionId) -> Result<Snapshot> {
        self.call(move |db| {
            let record: Session = load_record(
                &db.conn,
                "SELECT record FROM sessions WHERE id=?1",
                session.as_str(),
            )?;
            let mut id = record.baseline;
            let mut sequence = 0;
            let mut stmt = db.conn.prepare(
                "SELECT sequence,record FROM operations WHERE session_id=?1 ORDER BY sequence",
            )?;
            for row in stmt.query_map([session.as_str()], |r| {
                Ok((uint(r, 0)?, r.get::<_, String>(1)?))
            })? {
                let (next, raw) = row?;
                let operation: OperationInput = decode(&raw)?;
                if next != sequence + 1 || operation.before != id {
                    return Err(Error::CorruptMetadata);
                }
                id = operation.after;
                sequence = next;
            }
            load_snapshot(&db.conn, &id)
        })
    }

    /// Caller must hold the repository/workspace leases and verify its ownership
    /// and stopped writers first. Worktree bytes and the preparation baseline are
    /// retained. Shared blobs, other sessions and active references are protected.
    pub fn delete_closed_session(&self, session: SessionId, workspace: WorkspaceId) -> Result<()> {
        self.call(move |db| {
            db.require_healthy()?;
            if db.storage_report()?.pending_mutations > 0 { return Err(Error::ReadOnly); }
            let tx = db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let exists: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM deletions WHERE session_id=?1)", [session.as_str()], |r| r.get(0))?;
            if exists { drop(tx); return db.finish_deletions(); }
            let record: Session = load_record(&tx,"SELECT record FROM sessions WHERE id=?1",session.as_str())?;
            if record.workspace_id != workspace || !matches!(record.status.as_str(), "closed" | "archived") { return Err(Error::Invalid("only explicitly closed sessions may be deleted")); }
            let references: u64 = tx.query_row("SELECT (SELECT COUNT(*) FROM history_holds WHERE session_id=?1)+(SELECT COUNT(*) FROM candidates WHERE session_id=?1)+(SELECT COUNT(*) FROM sessions WHERE workspace_id=?2 AND id<>?1)", params![session.as_str(),workspace.as_str()], |r| uint(r,0))?;
            if references != 0 { return Err(Error::Invalid("session has active references or a shared workspace")); }
            // Keep replay sequences intact. Old clients must resnapshot after the
            // store identity rotates; session payloads are explicitly redacted.
            tx.execute("UPDATE events SET session_id=NULL,kind='history.deleted',payload='{}' WHERE session_id=?1", [session.as_str()])?;
            tx.execute("DELETE FROM file_changes WHERE operation_id IN (SELECT id FROM operations WHERE session_id=?1)", [session.as_str()])?;
            tx.execute("DELETE FROM operations WHERE session_id=?1", [session.as_str()])?;
            let image_candidates=tx.prepare("SELECT blob_id FROM turn_images WHERE turn_id IN (SELECT id FROM turns WHERE session_id=?1)")?.query_map([session.as_str()],|r|r.get::<_,String>(0))?.collect::<std::result::Result<Vec<_>,_>>()?;
            let attachment_candidates=tx.prepare("SELECT DISTINCT a.id,a.blob_id FROM attachments a JOIN turn_attachments t ON t.attachment_id=a.id JOIN turns r ON r.id=t.turn_id WHERE r.session_id=?1")?.query_map([session.as_str()],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?)))?.collect::<std::result::Result<Vec<_>,_>>()?;
            tx.execute("DELETE FROM turns WHERE session_id=?1", [session.as_str()])?;
            tx.execute("DELETE FROM sessions WHERE id=?1", [session.as_str()])?;
            // Only snapshots belonging to the selected workspace. Keep its exact
            // preparation baseline and any foreign live snapshot references.
            let mut candidates = tx.prepare("SELECT DISTINCT blob_id FROM snapshot_blobs WHERE snapshot_id IN (SELECT id FROM snapshots WHERE workspace_id=?1 AND id<>?2)")?
                .query_map(params![workspace.as_str(), record.baseline.as_str()], |r| r.get::<_,String>(0))?
                .collect::<std::result::Result<Vec<_>,_>>()?;
            tx.execute("DELETE FROM snapshot_blobs WHERE snapshot_id IN (SELECT id FROM snapshots WHERE workspace_id=?1 AND id<>?2 AND id NOT IN (SELECT baseline FROM sessions) AND id NOT IN (SELECT before_id FROM operations UNION SELECT after_id FROM operations))",params![workspace.as_str(),record.baseline.as_str()])?;
            tx.execute("DELETE FROM snapshots WHERE workspace_id=?1 AND id<>?2 AND id NOT IN (SELECT baseline FROM sessions) AND id NOT IN (SELECT before_id FROM operations UNION SELECT after_id FROM operations)",params![workspace.as_str(),record.baseline.as_str()])?;
            candidates.extend(image_candidates);
            let mut attachment_files=Vec::new();
            for (id,blob) in attachment_candidates {
                if tx.execute("DELETE FROM attachments WHERE id=?1 AND NOT EXISTS(SELECT 1 FROM turn_attachments WHERE attachment_id=?1) AND NOT EXISTS(SELECT 1 FROM queue_attachments WHERE attachment_id=?1)",[&id])?>0 {attachment_files.push(id);candidates.push(blob);}
            }
            let mut removed = Vec::new();
            for id in candidates {
                if tx.execute("DELETE FROM blobs WHERE id=?1 AND NOT EXISTS(SELECT 1 FROM snapshot_blobs WHERE blob_id=?1) AND NOT EXISTS(SELECT 1 FROM turn_images WHERE blob_id=?1) AND NOT EXISTS(SELECT 1 FROM attachments WHERE blob_id=?1)", [&id])? > 0 { removed.push(ContentId::try_from(id).map_err(|_|Error::CorruptMetadata)?); }
            }
            tx.execute("INSERT INTO deletions(session_id,blobs,attachment_files) VALUES(?1,?2,?3)",params![session.as_str(),serde_json::to_string(&removed).map_err(|_|Error::CorruptMetadata)?,json(&attachment_files)?])?;
            let new_id = StoreId::try_from(random_id()?).map_err(|_|Error::CorruptMetadata)?;
            tx.execute("UPDATE meta SET store_id=?1 WHERE singleton=1",[new_id.as_str()])?;
            tx.commit()?;
            db.store_id = new_id;
            boundary(Boundary::AfterCommit, #[cfg(test)] db.crash);
            db.finish_deletions()
        })
    }
    /// Retry only committed explicit deletion manifests. No orphan sweep.
    pub fn finish_retention(&self) -> Result<()> {
        self.call(|db| db.finish_deletions())
    }
}
