use super::*;

impl HistoryStore {
    /// Read-only baseline-to-checkpoint changes, never an uncommitted capture.
    pub fn session_changes(
        &self,
        session: SessionId,
        snapshot: Option<ContentId>,
    ) -> Result<(Snapshot, Snapshot, Vec<FileChange>)> {
        let selected = match snapshot {
            Some(id) => id,
            None => self.checkpoint(session.clone())?.id,
        };
        self.call(move |db| {
            let record:Session=load_record(&db.conn,"SELECT record FROM sessions WHERE id=?1",session.as_str())?;
            let owned:bool=db.conn.query_row("SELECT ?2=?3 OR EXISTS(SELECT 1 FROM operations WHERE session_id=?1 AND (before_id=?2 OR after_id=?2))",params![session.as_str(),selected.as_str(),record.baseline.as_str()],|r|r.get(0))?;
            if !owned { return Err(Error::Conflict); }
            let before=load_snapshot(&db.conn,&record.baseline)?;
            let after=load_snapshot(&db.conn,&selected)?;
            if before.manifest.workspace_id!=record.workspace_id || after.manifest.workspace_id!=record.workspace_id {return Err(Error::Conflict);}
            let files=changes(&before.manifest,&after.manifest).into_iter().map(|(_,file)|file).collect();
            Ok((before,after,files))
        })
    }

    pub fn queued_prompt(&self, session: SessionId, id: String) -> Result<Value> {
        self.call(move |db| {
            let raw: String = db
                .conn
                .query_row(
                    "SELECT record FROM prompt_queue WHERE session_id=?1 AND id=?2",
                    params![session.as_str(), id],
                    |r| r.get(0),
                )
                .optional()?
                .ok_or(Error::NotFound)?;
            serde_json::from_str(&raw).map_err(|_| Error::CorruptMetadata)
        })
    }
    pub fn save_turn_checkpoint(&self, session: SessionId, turn: String) -> Result<()> {
        let snapshot = self.checkpoint(session.clone())?;
        self.call(move |db| {
            db.require_write(0)?;
            let record: Turn =
                load_record(&db.conn, "SELECT record FROM turns WHERE id=?1", &turn)?;
            if record.session_id != session {
                return Err(Error::Conflict);
            }
            db.conn.execute(
                "INSERT INTO turn_checkpoints(turn_id,snapshot_id) VALUES(?1,?2)",
                params![turn, snapshot.id.as_str()],
            )?;
            Ok(())
        })
    }
    pub fn turn_checkpoint(&self, session: SessionId, turn: String) -> Result<Snapshot> {
        self.call(move |db| {
            let id:String = db.conn.query_row("SELECT c.snapshot_id FROM turn_checkpoints c JOIN turns t ON t.id=c.turn_id WHERE t.session_id=?1 AND t.id=?2",params![session.as_str(),turn],|r|r.get(0)).optional()?.ok_or(Error::NotFound)?;
            load_snapshot(&db.conn,&ContentId::try_from(id).map_err(|_|Error::CorruptMetadata)?)
        })
    }
    pub fn store_images(
        &self,
        session: SessionId,
        turn: String,
        images: Vec<AttachmentInput>,
    ) -> Result<Vec<protocol::ImageRef>> {
        if images.len() > 4
            || images.iter().any(|i| {
                i.bytes.is_empty()
                    || i.bytes.len() > 128 * 1024
                    || i.name.chars().count() > 128
                    || i.name.chars().any(char::is_control)
                    || !matches!(i.mime_type.as_str(), "image/png" | "image/jpeg")
            })
        {
            return Err(Error::Invalid("invalid image attachment"));
        }
        self.call(move |db| {
            db.require_write(images.iter().map(|i|i.bytes.len() as u64).sum::<u64>()*2)?;
            let record:Turn=load_record(&db.conn,"SELECT record FROM turns WHERE id=?1",&turn)?;
            if record.session_id!=session { return Err(Error::Conflict); }
            let tx=db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let mut refs=Vec::new();
            for (ordinal,image) in images.into_iter().enumerate() {
                let id=db.blobs.put(&image.bytes)?;
                tx.execute("INSERT OR IGNORE INTO blobs(id,size) VALUES(?1,?2)",params![id.as_str(),image.bytes.len() as i64])?;
                tx.execute("INSERT INTO turn_images(turn_id,ordinal,blob_id,name,mime_type) VALUES(?1,?2,?3,?4,?5)",params![turn,ordinal as i64,id.as_str(),image.name,image.mime_type])?;
                refs.push(protocol::ImageRef {id:id.as_str().into(),name:image.name,mime_type:image.mime_type,size:image.bytes.len() as u32});
            }
            tx.commit()?;
            Ok(refs)
        })
    }
    pub fn image_bytes(&self, session: SessionId, turn: String, image: String) -> Result<Vec<u8>> {
        self.call(move |db| {
            let size:u64=db.conn.query_row("SELECT b.size FROM turn_images i JOIN turns t ON t.id=i.turn_id JOIN blobs b ON b.id=i.blob_id WHERE t.session_id=?1 AND t.id=?2 AND b.id=?3",params![session.as_str(),turn,image],|r|uint(r,0)).optional()?.ok_or(Error::NotFound)?;
            db.blobs.read(&ContentId::try_from(image).map_err(|_|Error::Invalid("invalid image ID"))?,size)
        })
    }

    pub fn mutate_queue(&self, action: protocol::QueueAction) -> Result<Value> {
        self.call(move |db| {
            let record=action.command.as_ref().map(serde_json::to_string).transpose().map_err(|_|Error::Invalid("invalid queued prompt"))?;
            if record.as_ref().is_some_and(|r|r.len()>768*1024) { return Err(Error::Invalid("queued prompt exceeds limit")); }
            db.require_write(record.as_ref().map_or(0,|r|r.len() as u64 *2))?;
            let tx=db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let _:Session=load_record(&tx,"SELECT record FROM sessions WHERE id=?1",action.session_id.as_str())?;
            tx.execute("INSERT OR IGNORE INTO queue_policy(session_id) VALUES(?1)",[action.session_id.as_str()])?;
            let id=action.id.as_deref().unwrap_or("");
            match action.action.as_str() {
                "enqueue" => {
                    identifier(id)?;
                    let command=action.command.ok_or(Error::Invalid("queued command required"))?;
                    if command.session_id!=action.session_id || command.prompt.is_empty() || command.prompt.len()>32*1024 || command.images.len()>4 || command.attachments.len()>32 { return Err(Error::Invalid("invalid queued prompt")); }
                    super::attachments::validate(&tx,&command.attachments)?;
                    let record=record.unwrap();
                    let digest=ContentId::hash(record.as_bytes());
                    let receipt:Option<(String,String)>=tx.query_row("SELECT session_id,record_hash FROM queue_receipts WHERE id=?1",[id],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
                    if let Some((session,hash))=receipt {
                        if session!=action.session_id.as_str() || hash!=digest.as_str() {return Err(Error::Conflict);}
                        tx.commit()?;
                        return queue_view(&db.conn,&action.session_id);
                    }
                    let old:Option<(String,String)>=tx.query_row("SELECT session_id,record FROM prompt_queue WHERE id=?1",[id],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
                    if let Some((session,old))=old {
                        if session!=action.session_id.as_str() || old!=record {return Err(Error::Conflict);}
                    } else {
                        let count:u64=tx.query_row("SELECT COUNT(*) FROM prompt_queue WHERE session_id=?1",[action.session_id.as_str()],|r|uint(r,0))?;
                        if count>=20 {return Err(Error::Invalid("queue holds at most 20 prompts"));}
                        tx.execute("INSERT INTO queue_receipts VALUES(?1,?2,?3)",params![id,action.session_id.as_str(),digest.as_str()])?;
                        tx.execute("INSERT INTO prompt_queue VALUES(?1,?2,(SELECT coalesce(max(position),-1)+1 FROM prompt_queue WHERE session_id=?2),'pending',?3)",params![id,action.session_id.as_str(),record])?;
                        for reference in &command.attachments {tx.execute("INSERT OR IGNORE INTO queue_attachments(prompt_id,attachment_id) VALUES(?1,?2)",params![id,reference.id])?;}
                    }
                }
                "pause"|"resume" => {
                    if action.action=="resume" {
                        let uncertain:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM prompt_queue WHERE session_id=?1 AND status='uncertain')",[action.session_id.as_str()],|r|r.get(0))?;
                        if uncertain {return Err(Error::Invalid("inspect and remove unknown-outcome prompts before resuming"));}
                    }
                    tx.execute("UPDATE queue_policy SET paused=?2 WHERE session_id=?1",params![action.session_id.as_str(),action.action=="pause"])?;
                }
                "remove" => { if tx.execute("DELETE FROM prompt_queue WHERE session_id=?1 AND id=?2 AND status<>'dispatching'",params![action.session_id.as_str(),id])?!=1 {return Err(Error::Conflict);} }
                "clear" => {
                    tx.execute("UPDATE queue_policy SET paused=1 WHERE session_id=?1",[action.session_id.as_str()])?;
                    tx.execute("DELETE FROM prompt_queue WHERE session_id=?1 AND status<>'dispatching'",[action.session_id.as_str()])?;
                }
                "move" => {
                    let mut ids=tx.prepare("SELECT id FROM prompt_queue WHERE session_id=?1 ORDER BY position,id")?.query_map([action.session_id.as_str()],|r|r.get::<_,String>(0))?.collect::<std::result::Result<Vec<_>,_>>()?;
                    let from=ids.iter().position(|i|i==id).ok_or(Error::NotFound)?;
                    let to=action.position.ok_or(Error::Invalid("queue position required"))? as usize;
                    if to>=ids.len() {return Err(Error::Invalid("queue position out of range"));}
                    let item=ids.remove(from);ids.insert(to,item);
                    for (i,id) in ids.iter().enumerate() {tx.execute("UPDATE prompt_queue SET position=?2 WHERE id=?1",params![id,i as i64])?;}
                }
                _=>return Err(Error::Invalid("unknown queue action")),
            }
            tx.commit()?;
            queue_view(&db.conn,&action.session_id)
        })
    }
    pub fn queue_view(&self, session: SessionId) -> Result<Value> {
        self.call(move |db| queue_view(&db.conn, &session))
    }
    pub fn claim_queued_prompt(
        &self,
        session: SessionId,
    ) -> Result<Option<(String, protocol::AgentCommand)>> {
        self.call(move |db| {
            db.require_write(0)?;
            let tx=db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let paused:bool=tx.query_row("SELECT coalesce((SELECT paused FROM queue_policy WHERE session_id=?1),0)",[session.as_str()],|r|r.get(0))?;
            if paused {return Ok(None);}
            let next:Option<(String,String)>=tx.query_row("SELECT id,record FROM prompt_queue WHERE session_id=?1 AND status='pending' AND NOT EXISTS(SELECT 1 FROM prompt_queue WHERE session_id=?1 AND status IN ('dispatching','uncertain')) ORDER BY position,id LIMIT 1",[session.as_str()],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
            let Some((id,record))=next else {return Ok(None)};
            tx.execute("UPDATE prompt_queue SET status='dispatching' WHERE id=?1",[&id])?;
            tx.commit()?;
            Ok(Some((id,serde_json::from_str(&record).map_err(|_|Error::CorruptMetadata)?)))
        })
    }
    pub fn finish_queued_prompt(
        &self,
        session: SessionId,
        id: String,
        consumed: bool,
    ) -> Result<()> {
        self.call(move |db| {
            db.require_write(0)?;
            let sql=if consumed {"DELETE FROM prompt_queue WHERE session_id=?1 AND id=?2 AND status='dispatching'"}else{"UPDATE prompt_queue SET status='pending' WHERE session_id=?1 AND id=?2 AND status='dispatching'"};
            if db.conn.execute(sql,params![session.as_str(),id])?!=1 {return Err(Error::Conflict);}
            Ok(())
        })
    }
}
fn queue_view(conn: &Connection, session: &SessionId) -> Result<Value> {
    let paused: bool = conn.query_row(
        "SELECT coalesce((SELECT paused FROM queue_policy WHERE session_id=?1),0)",
        [session.as_str()],
        |r| r.get(0),
    )?;
    let rows=conn.prepare("SELECT id,status,substr(json_extract(record,'$.prompt'),1,160),coalesce(json_array_length(record,'$.images'),0),coalesce(json_array_length(record,'$.attachments'),0) FROM prompt_queue WHERE session_id=?1 ORDER BY position,id LIMIT 20")?.query_map([session.as_str()],|r|Ok(value!({"id":r.get::<_,String>(0)?,"status":r.get::<_,String>(1)?,"preview":r.get::<_,String>(2)?,"imageCount":r.get::<_,u16>(3)?,"attachmentCount":r.get::<_,u16>(4)?})))?.collect::<std::result::Result<Vec<_>,_>>()?;
    Ok(value!({"sessionId":session,"paused":paused,"items":rows}))
}
