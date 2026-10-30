use super::*;

impl HistoryStore {
    /// Absolute-value updates are safe to retry after an unknown reply. Labels
    /// describe a conversation; favorites do not override retention policy.
    pub fn label_session(&self, query: protocol::SessionLabel) -> Result<()> {
        if query
            .title
            .as_ref()
            .is_some_and(|s| s.chars().count() > 120 || s.chars().any(char::is_control))
            || (query.title.is_none() && query.favorite.is_none())
        {
            return Err(Error::Invalid(
                "title must be at most 120 characters without controls",
            ));
        }
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let _: String = tx
                .query_row(
                    "SELECT record FROM sessions WHERE id=?1",
                    [query.session_id.as_str()],
                    |r| r.get(0),
                )
                .optional()?
                .ok_or(Error::NotFound)?;
            tx.execute(
                "INSERT OR IGNORE INTO session_labels(session_id) VALUES(?1)",
                [query.session_id.as_str()],
            )?;
            if let Some(title) = query.title {
                tx.execute(
                    "UPDATE session_labels SET title=?2 WHERE session_id=?1",
                    params![query.session_id.as_str(), title.trim()],
                )?;
            }
            if let Some(favorite) = query.favorite {
                tx.execute(
                    "UPDATE session_labels SET favorite=?2 WHERE session_id=?1",
                    params![query.session_id.as_str(), favorite],
                )?;
            }
            tx.commit()?;
            Ok(())
        })
    }

    pub fn search_sessions(&self, query: protocol::SessionSearch) -> Result<Value> {
        if query.query.len() > 1024
            || query.query.chars().count() > 256
            || query.query.contains('\0')
        {
            return Err(Error::Invalid("search must be at most 256 characters"));
        }
        self.call(move |db| {
            // ponytail: indexed per-session journal scan; add FTS if profiling
            // demonstrates that local retained histories need it. %/_ are literal.
            let mut statement = db.conn.prepare(
                "SELECT s.record,coalesce(nullif(l.title,''),
                   (SELECT substr(json_extract(payload,'$.text'),1,120) FROM events
                    WHERE session_id=s.id AND kind='turn.started' ORDER BY sequence LIMIT 1),''),
                   coalesce(l.favorite,0) FROM sessions s
                 LEFT JOIN session_labels l ON l.session_id=s.id
                 WHERE s.id>?1 AND (?2=0 OR l.favorite=1)
                 AND (?3='' OR instr(lower(coalesce(l.title,'')),lower(?3))>0
                   OR instr(lower(s.id),lower(?3))>0
                   OR instr(lower(coalesce((SELECT group_concat(text,'') FROM
                     (SELECT CASE WHEN kind='turn.started' THEN char(10) ELSE '' END || coalesce(json_extract(payload,'$.text'),'') AS text
                      FROM events WHERE session_id=s.id AND kind IN ('turn.started','turn.prompt.delta','message.delta') ORDER BY sequence)),'')),lower(?3))>0)
                 ORDER BY s.id LIMIT 21")?;
            let rows = statement.query_map(params![query.after.as_ref().map(SessionId::as_str).unwrap_or(""), query.favorites_only, query.query.trim()], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,bool>(2)?)))?;
            let mut items = Vec::new();
            for row in rows {
                let (record,title,favorite) = row?;
                let title = title.lines().next().unwrap_or_default().trim().to_owned();
                let session: Session = serde_json::from_str(&record).map_err(|_| Error::CorruptMetadata)?;
                items.push(value!({"id":session.id,"status":session.status,"createdAtMs":session.created_at_ms.to_string(),"workspaceId":session.workspace_id,"title":title,"favorite":favorite}));
            }
            let next = (items.len()>20).then(|| items[19]["id"].clone());
            items.truncate(20);
            Ok(value!({"items":items,"next":next}))
        })
    }

    /// Read a stable prefix of the durable journal, independent of the bounded
    /// active-session projection. No process is started and no live file is read.
    pub fn conversation(&self, query: protocol::ConversationQuery) -> Result<Value> {
        fn cursor(value: Option<String>) -> Result<Option<i64>> {
            value
                .map(|s| {
                    s.parse::<i64>()
                        .ok()
                        .filter(|v| *v >= 0)
                        .ok_or(Error::InvalidCursor)
                })
                .transpose()
        }
        let after = cursor(query.after)?.unwrap_or(0);
        let through = cursor(query.through)?;
        self.call(move |db| {
            let record: String = db.conn.query_row("SELECT record FROM sessions WHERE id=?1", [query.session_id.as_str()], |r| r.get(0)).optional()?.ok_or(Error::NotFound)?;
            let session: Session = serde_json::from_str(&record).map_err(|_| Error::CorruptMetadata)?;
            let maximum: i64 = db.conn.query_row("SELECT coalesce(max(sequence),0) FROM events WHERE session_id=?1", [query.session_id.as_str()], |r| r.get(0))?;
            let through = through.unwrap_or(maximum);
            if after>through || through>maximum { return Err(Error::InvalidCursor); }
            let mut statement = db.conn.prepare("SELECT sequence,kind,payload,created_at_ms FROM events WHERE session_id=?1 AND sequence>?2 AND sequence<=?3 AND json_type(payload,'$.text')='text' ORDER BY sequence LIMIT 21")?;
            let rows = statement.query_map(params![query.session_id.as_str(),after,through], |r| Ok((r.get::<_,i64>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,i64>(3)?)))?;
            let mut items = Vec::new();
            let mut bytes=0usize;let mut has_more=false;
            for row in rows {
                let (sequence,kind,payload,time)=row?;
                let data:Value = serde_json::from_str(&payload).map_err(|_| Error::CorruptMetadata)?;
                let item=value!({"sequence":sequence.to_string(),"kind":kind,"text":data["text"],"toolCallId":data["toolCallId"],"tool":data["tool"],"turnId":data["turnId"],"images":data["images"],"attachments":data["attachments"],"createdAtMs":time.to_string(),"legacy":data["transcriptVersion"]!=1});
                let size=item.to_string().len();
                if !items.is_empty() && bytes+size>512*1024 {has_more=true;break;}
                bytes+=size;items.push(item);
            }
            if items.len()>20 {items.truncate(20);has_more=true;}
            let next = has_more.then(|| items.last().unwrap()["sequence"].clone());
            Ok(value!({"sessionId":session.id,"status":session.status,"items":items,"next":next,"through":through.to_string()}))
        })
    }
}
