use super::*;
use protocol::AttachmentRef;

impl HistoryStore {
    pub fn import_attachment(&self, input: AttachmentInput) -> Result<AttachmentRef> {
        self.import_attachments(vec![input])
            .map(|mut refs| refs.remove(0))
    }
    pub fn import_attachments(&self, inputs: Vec<AttachmentInput>) -> Result<Vec<AttachmentRef>> {
        let total = inputs.iter().map(|i| i.bytes.len() as u64).sum::<u64>();
        if inputs.is_empty()
            || inputs.len() > 32
            || total > 64 * 1024 * 1024
            || inputs.iter().any(|input| {
                input.bytes.len() > 32 * 1024 * 1024
                    || input.name.is_empty()
                    || input.name.len() > 1024
                    || input.name.chars().any(char::is_control)
                    || input.mime_type.len() > 128
                    || !input.mime_type.contains('/')
                    || input.mime_type.chars().any(char::is_control)
            })
        {
            return Err(Error::Invalid("invalid file attachments"));
        }
        self.call(move |db| {
            db.purge_attachment_drafts()?;
            db.require_write(total*3)?;
            let tx=db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let mut refs=Vec::new();
            for input in inputs {
                let blob=db.blobs.put(&input.bytes)?;
                let id=ContentId::hash(serde_json::to_string(&(blob.as_str(),&input.name,&input.mime_type)).map_err(|_|Error::CorruptMetadata)?.as_bytes());
                let record=AttachmentRef{id:id.as_str().into(),name:input.name,mime_type:input.mime_type,size:input.bytes.len() as u32};
                tx.execute("INSERT OR IGNORE INTO blobs(id,size) VALUES(?1,?2)",params![blob.as_str(),record.size])?;
                tx.execute("INSERT INTO attachments(id,blob_id,record,created_at_ms) VALUES(?1,?2,?3,?4) ON CONFLICT(id) DO UPDATE SET created_at_ms=excluded.created_at_ms",params![record.id,blob.as_str(),json(&record)?,now_ms()])?;
                refs.push(record);
            }
            tx.commit()?;Ok(refs)
        })
    }
    pub fn attachment_bytes(&self, reference: AttachmentRef) -> Result<Vec<u8>> {
        self.call(move |db| attachment_bytes(db, &reference))
    }
    pub fn validate_attachments(&self, refs: Vec<AttachmentRef>) -> Result<()> {
        self.call(move |db| validate(&db.conn, &refs))
    }
    pub fn store_attachments(
        &self,
        session: SessionId,
        turn: String,
        refs: Vec<AttachmentRef>,
    ) -> Result<()> {
        self.call(move |db| {
            db.require_write(0)?;
            let record: Turn =
                load_record(&db.conn, "SELECT record FROM turns WHERE id=?1", &turn)?;
            if record.session_id != session {
                return Err(Error::Conflict);
            }
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            validate(&tx, &refs)?;
            for (i, reference) in refs.iter().enumerate() {
                tx.execute(
                    "INSERT INTO turn_attachments(turn_id,ordinal,attachment_id) VALUES(?1,?2,?3)",
                    params![turn, i as i64, reference.id],
                )?;
            }
            tx.commit()?;
            Ok(())
        })
    }
    pub fn verify_turn_attachment(
        &self,
        session: SessionId,
        turn: String,
        reference: AttachmentRef,
    ) -> Result<()> {
        self.call(move |db| {
            let exists:bool=db.conn.query_row("SELECT EXISTS(SELECT 1 FROM turn_attachments a JOIN turns t ON t.id=a.turn_id WHERE t.session_id=?1 AND t.id=?2 AND a.attachment_id=?3)",params![session.as_str(),turn,reference.id],|r|r.get(0))?;
            if !exists {return Err(Error::NotFound);}
            validate(&db.conn,&[reference])
        })
    }
}

pub(super) fn validate(conn: &Connection, refs: &[AttachmentRef]) -> Result<()> {
    if refs.len() > 32 || refs.iter().map(|r| r.size as u64).sum::<u64>() > 64 * 1024 * 1024 {
        return Err(Error::Invalid(
            "attach at most 32 files and 64 MiB per message",
        ));
    }
    for reference in refs {
        let stored: AttachmentRef = load_record(
            conn,
            "SELECT record FROM attachments WHERE id=?1",
            &reference.id,
        )?;
        if &stored != reference {
            return Err(Error::Conflict);
        }
    }
    Ok(())
}
fn attachment_bytes(db: &Database, reference: &AttachmentRef) -> Result<Vec<u8>> {
    validate(&db.conn, std::slice::from_ref(reference))?;
    let id: String = db.conn.query_row(
        "SELECT blob_id FROM attachments WHERE id=?1",
        [&reference.id],
        |r| r.get(0),
    )?;
    db.blobs.read(
        &ContentId::try_from(id).map_err(|_| Error::CorruptMetadata)?,
        reference.size as u64,
    )
}
fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

impl Database {
    fn purge_attachment_drafts(&mut self) -> Result<()> {
        self.require_healthy()?;
        let cutoff = now_ms().saturating_sub(7 * 24 * 60 * 60 * 1000);
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let stale=tx.prepare("SELECT id,blob_id FROM attachments WHERE created_at_ms<?1 AND NOT EXISTS(SELECT 1 FROM turn_attachments WHERE attachment_id=attachments.id) AND NOT EXISTS(SELECT 1 FROM queue_attachments WHERE attachment_id=attachments.id) LIMIT 100")?.query_map([cutoff],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?)))?.collect::<std::result::Result<Vec<_>,_>>()?;
        if stale.is_empty() {
            return Ok(());
        }
        let mut files = Vec::new();
        let mut blobs = Vec::new();
        for (id, blob) in stale {
            tx.execute("DELETE FROM attachments WHERE id=?1", [&id])?;
            files.push(id);
            if tx.execute("DELETE FROM blobs WHERE id=?1 AND NOT EXISTS(SELECT 1 FROM snapshot_blobs WHERE blob_id=?1) AND NOT EXISTS(SELECT 1 FROM turn_images WHERE blob_id=?1) AND NOT EXISTS(SELECT 1 FROM attachments WHERE blob_id=?1)",[&blob])?>0 {blobs.push(ContentId::try_from(blob).map_err(|_|Error::CorruptMetadata)?);}
        }
        tx.execute(
            "INSERT INTO deletions(session_id,blobs,attachment_files) VALUES(?1,?2,?3)",
            params![
                format!("attachment-drafts-{}", random_id()?),
                json(&blobs)?,
                json(&files)?
            ],
        )?;
        tx.commit()?;
        self.finish_deletions()
    }
}
pub(super) fn remove_cache(root: &Path, id: &str) -> Result<()> {
    let _ = ContentId::try_from(id.to_owned()).map_err(|_| Error::CorruptMetadata)?;
    if !root.exists() {
        return Ok(());
    }
    if fs::symlink_metadata(root)?.file_type().is_symlink() {
        return Err(Error::Conflict);
    }
    let path = root.join(id);
    let meta = match path.symlink_metadata() {
        Ok(meta) => meta,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e.into()),
    };
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err(Error::Conflict);
    }
    #[cfg(windows)]
    for entry in fs::read_dir(&path)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            return Err(Error::Conflict);
        }
        let mut permissions = metadata.permissions();
        permissions.set_readonly(false);
        fs::set_permissions(entry.path(), permissions)?;
    }
    fs::remove_dir_all(path)?;
    Ok(())
}
