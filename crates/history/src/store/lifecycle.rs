use super::*;

impl HistoryStore {
    /// Idempotent metadata-only archive. Unknown/active writers require recovery
    /// or close first. Archiving does not delete content or imply quiescence.
    pub fn archive_session(&self, session: SessionId) -> Result<()> {
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
            if record.status == "archived" {
                return Ok(());
            }
            if record.status != "closed" {
                return Err(Error::Conflict);
            }
            record.status = "archived".into();
            tx.execute(
                "UPDATE sessions SET record=?2 WHERE id=?1",
                params![session.as_str(), json(&record)?],
            )?;
            event(
                &tx,
                &record.workspace_id,
                Some(&session),
                "session.archived",
                value!({}),
            )?;
            tx.commit()?;
            Ok(())
        })
    }
}

impl HistoryStore {
    /// Stable timestamp/ID ordering. Cursor lookup is scoped to the same session.
    pub fn turns(
        &self,
        session: SessionId,
        after: Option<String>,
        limit: u16,
    ) -> Result<Page<Turn, String>> {
        page_limit(0, limit)?;
        self.call(move |db| {
            let (time, id) = if let Some(id) = after {
                let turn: Turn = load_record(&db.conn, "SELECT record FROM turns WHERE id=?1", &id)?;
                if turn.session_id != session { return Err(Error::InvalidCursor); }
                (turn.created_at_ms as i64, id)
            } else { (-1, String::new()) };
            record_page(&db.conn,
                "SELECT id,record FROM turns WHERE session_id=?1 AND (json_extract(record,'$.createdAtMs')>?2 OR (json_extract(record,'$.createdAtMs')=?2 AND id>?3)) ORDER BY json_extract(record,'$.createdAtMs'),id LIMIT ?4",
                params![session.as_str(),time,id,limit as u32+1],limit)
        })
    }
    /// A later checkpoint cannot fill an unobserved capture gap. Keep this warning
    /// while this session's history exists, even after explicit reconciliation.
    pub fn capture_gap(&self, session: SessionId) -> Result<bool> {
        self.call(move |db| Ok(db.conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM events WHERE session_id=?1 AND kind='workspace.captureIncomplete')",
            [session.as_str()], |r| r.get(0))?))
    }
}
