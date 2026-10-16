use super::*;

pub(super) fn integrity(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("SELECT id FROM candidates")?;
    for row in stmt.query_map([], |r| r.get::<_, String>(0))? {
        let id = ContentId::try_from(row?).map_err(|_| Error::CorruptMetadata)?;
        let candidate = review(conn, &id)?;
        if !["pending", "accepted", "rejected"].contains(&candidate.decision.as_str()) {
            return Err(Error::CorruptMetadata);
        }
    }
    Ok(())
}

fn source(conn: &Connection, session: &SessionId) -> Result<Candidate> {
    let record: Session = load_record(
        conn,
        "SELECT record FROM sessions WHERE id=?1",
        session.as_str(),
    )?;
    if record.status != "closed" {
        return Err(Error::Invalid(
            "close the session and verify all writers stopped before review",
        ));
    }
    let unsafe_source: bool = conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM sessions WHERE workspace_id=?1 AND json_extract(record,'$.status') NOT IN ('closed','archived')) OR EXISTS(SELECT 1 FROM events WHERE session_id=?2 AND kind='workspace.captureIncomplete')",
        params![record.workspace_id.as_str(),session.as_str()], |r| r.get(0))?;
    if unsafe_source {
        return Err(Error::Conflict);
    }
    let final_id: String = conn.query_row(
        "SELECT COALESCE((SELECT after_id FROM operations WHERE session_id=?1 ORDER BY sequence DESC LIMIT 1),?2)",
        params![session.as_str(),record.baseline.as_str()], |r| r.get(0))?;
    let generation = conn.query_row(
        "SELECT COALESCE(MAX(sequence),0) FROM events WHERE workspace_id=?1 AND kind IN ('session.created','turn.created','operation.recorded','workspace.captureIncomplete','session.recovered','session.interrupted','session.archived')",
        [record.workspace_id.as_str()], |r| uint(r,0))?;
    let final_id = ContentId::try_from(final_id).map_err(|_| Error::CorruptMetadata)?;
    Ok(Candidate {
        session_id: session.clone(),
        baseline: record.baseline,
        source: final_id.clone(),
        result: final_id,
        generation,
    })
}

fn validate_source(conn: &Connection, candidate: &Candidate) -> Result<()> {
    let current = source(conn, &candidate.session_id)?;
    if current.baseline != candidate.baseline
        || current.source != candidate.source
        || current.generation != candidate.generation
    {
        return Err(Error::Conflict);
    }
    let baseline = load_snapshot(conn, &candidate.baseline)?;
    let result = load_snapshot(conn, &candidate.result)?;
    if baseline.manifest.workspace_id != result.manifest.workspace_id
        || !baseline.manifest.coverage.complete
        || !result.manifest.coverage.complete
        || baseline
            .manifest
            .files
            .values()
            .chain(result.manifest.files.values())
            .any(|f| f.kind != FileKind::Regular || f.mode & 0o7000 != 0)
    {
        return Err(Error::Invalid(
            "candidate requires complete regular-file snapshots",
        ));
    }
    Ok(())
}

pub(super) fn review(conn: &Connection, id: &ContentId) -> Result<CandidateReview> {
    let candidate: Candidate = load_record(
        conn,
        "SELECT record FROM candidates WHERE id=?1",
        id.as_str(),
    )?;
    if ContentId::hash(json(&candidate)?.as_bytes()) != *id {
        return Err(Error::CorruptMetadata);
    }
    let decision = conn.query_row(
        "SELECT decision FROM candidates WHERE id=?1",
        [id.as_str()],
        |r| r.get(0),
    )?;
    let checks = conn.prepare("SELECT record FROM candidate_checks WHERE candidate_id=?1 OR json_extract(record,'$.resultCandidateId')=?1 ORDER BY rowid DESC LIMIT 33")?
        .query_map([id.as_str()],|r|r.get::<_,String>(0))?
        .map(|r|decode(&r?)).collect::<Result<Vec<Value>>>()?;
    if checks.len() > 32 {
        return Err(Error::Invalid(
            "candidate check history exceeds review limit",
        ));
    }
    Ok(CandidateReview {
        id: id.clone(),
        candidate,
        decision,
        checks,
    })
}

impl HistoryStore {
    pub fn reset_candidate_review(&self, id: ContentId) -> Result<()> {
        self.call(move |db| {
            db.require_write(0)?;
            if db.conn.execute(
                "UPDATE candidates SET decision='pending' WHERE id=?1 AND decision<>'rejected'",
                [id.as_str()],
            )? != 1
            {
                return Err(Error::Conflict);
            }
            Ok(())
        })
    }
    pub fn content_bytes(&self, id: ContentId) -> Result<Vec<u8>> {
        self.call(move |db| {
            let size = db
                .conn
                .query_row("SELECT size FROM blobs WHERE id=?1", [id.as_str()], |r| {
                    uint(r, 0)
                })
                .optional()?
                .ok_or(Error::NotFound)?;
            if size > MAX_BLOB_BYTES as u64 {
                return Err(Error::CorruptMetadata);
            }
            db.blobs.read(&id, size)
        })
    }
    pub fn candidate_source(&self, session: SessionId) -> Result<Candidate> {
        self.call(move |db| source(&db.conn, &session))
    }
    pub fn save_candidate(&self, candidate: Candidate) -> Result<CandidateReview> {
        let record = json(&candidate)?;
        let id = ContentId::hash(record.as_bytes());
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            validate_source(&tx,&candidate)?;
            tx.execute("INSERT OR IGNORE INTO candidates(id,session_id,baseline_id,source_id,result_id,record) VALUES(?1,?2,?3,?4,?5,?6)",
                params![id.as_str(),candidate.session_id.as_str(),candidate.baseline.as_str(),candidate.source.as_str(),candidate.result.as_str(),record])?;
            tx.commit()?;
            review(&db.conn,&id)
        })
    }
    pub fn candidate_review(&self, id: ContentId) -> Result<CandidateReview> {
        self.call(move |db| review(&db.conn, &id))
    }
    pub fn candidate_changes(&self, id: ContentId) -> Result<Vec<FileChange>> {
        self.call(move |db| {
            let candidate = review(&db.conn, &id)?.candidate;
            Ok(changes(
                &load_snapshot(&db.conn, &candidate.baseline)?.manifest,
                &load_snapshot(&db.conn, &candidate.result)?.manifest,
            )
            .into_iter()
            .map(|(_, c)| c)
            .collect())
        })
    }
    /// Caller verifies the live worktree under its leases before acceptance.
    /// This transaction rechecks source generation before storing the decision.
    pub fn decide_candidate(&self, id: ContentId, accept: bool) -> Result<CandidateReview> {
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let current = review(&tx, &id)?;
            if accept {
                validate_source(&tx, &current.candidate)?;
                if current.decision == "rejected" {
                    return Err(Error::Conflict);
                }
                let mut commands = BTreeSet::new();
                for check in &current.checks {
                    let command = check["command"].to_string();
                    if commands.insert(command)
                        && (check["status"] != "passed"
                            || check["resultCandidateId"] != id.as_str())
                    {
                        return Err(Error::Invalid(
                            "candidate checks failed, are unfinished or produced different bytes",
                        ));
                    }
                }
            }
            let decision = if accept { "accepted" } else { "rejected" };
            if current.decision != decision {
                tx.execute(
                    "UPDATE candidates SET decision=?2 WHERE id=?1",
                    params![id.as_str(), decision],
                )?;
                let session: Session = load_record(
                    &tx,
                    "SELECT record FROM sessions WHERE id=?1",
                    current.candidate.session_id.as_str(),
                )?;
                event(
                    &tx,
                    &session.workspace_id,
                    Some(&session.id),
                    "candidate.decided",
                    value!({"candidateId":id,"decision":decision}),
                )?;
            }
            tx.commit()?;
            review(&db.conn, &id)
        })
    }
    /// Explicit intent identity prevents re-running a check after a lost reply.
    pub fn begin_candidate_check(
        &self,
        id: String,
        candidate: ContentId,
        command: Vec<String>,
    ) -> Result<Value> {
        identifier(&id)?;
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let existing: Option<String> = tx.query_row("SELECT record FROM candidate_checks WHERE id=?1",[&id],|r|r.get(0)).optional()?;
            if let Some(raw) = existing {
                let record: Value = decode(&raw)?;
                if record["candidateId"] != candidate.as_str() || record["command"] != value!(command) { return Err(Error::Conflict); }
                return Ok(record);
            }
            let current=review(&tx,&candidate)?;
            if current.decision=="rejected" {return Err(Error::Conflict);}
            validate_source(&tx,&current.candidate)?;
            let count:u64 = tx.query_row("SELECT COUNT(*) FROM candidate_checks WHERE candidate_id=?1 OR json_extract(record,'$.resultCandidateId')=?1",[candidate.as_str()],|r|uint(r,0))?;
            if count >= 32 { return Err(Error::Invalid("candidate check limit reached")); }
            let record = value!({"id":id,"candidateId":candidate,"command":command,"status":"running"});
            tx.execute("INSERT INTO candidate_checks VALUES(?1,?2,?3)",params![id,candidate.as_str(),json(&record)?])?;
            tx.execute("UPDATE candidates SET decision='pending' WHERE id=?1",[candidate.as_str()])?;
            tx.commit()?;
            Ok(record)
        })
    }
    pub fn claim_candidate_check(&self, id: String) -> Result<bool> {
        self.call(move |db| {
            db.require_write(0)?;
            Ok(db.conn.execute("UPDATE candidate_checks SET record=json_set(record,'$.executionStarted',json('true')) WHERE id=?1 AND json_extract(record,'$.status')='running' AND json_extract(record,'$.executionStarted') IS NULL",[id])?==1)
        })
    }
    pub fn finish_candidate_check(&self, id: String, result: Value) -> Result<()> {
        self.call(move |db| {
            db.require_write(0)?;
            let tx = db
                .conn
                .transaction_with_behavior(TransactionBehavior::Immediate)?;
            let old: Value =
                load_record(&tx, "SELECT record FROM candidate_checks WHERE id=?1", &id)?;
            if old["status"] != "running"
                || result["id"] != old["id"]
                || result["candidateId"] != old["candidateId"]
                || result["command"] != old["command"]
            {
                return Err(Error::Conflict);
            }
            tx.execute(
                "UPDATE candidate_checks SET record=?2 WHERE id=?1",
                params![id, json(&result)?],
            )?;
            tx.commit()?;
            Ok(())
        })
    }
}
