use super::*;

fn active(status: &str) -> bool {
    matches!(
        status,
        "applying" | "rollingBack" | "recoveryRequired" | "recoveryConflict"
    )
}
impl HistoryStore {
    pub fn apply_journal(&self, id: String) -> Result<ApplyJournal> {
        identifier(&id)?;
        self.call(move |db| {
            load_record(
                &db.conn,
                "SELECT record FROM apply_journals WHERE id=?1",
                &id,
            )
        })
    }
    pub fn apply_journals(&self) -> Result<Vec<ApplyJournal>> {
        self.call(|db| db.conn.prepare("SELECT record FROM apply_journals ORDER BY json_extract(record,'$.status') IN ('applying','rollingBack','recoveryRequired','recoveryConflict') DESC, rowid DESC LIMIT 20")?
            .query_map([],|r|r.get::<_,String>(0))?.map(|r|decode(&r?)).collect())
    }
    pub fn save_apply_journal(&self, journal: ApplyJournal) -> Result<()> {
        identifier(&journal.id)?;
        let raw = json(&journal)?;
        self.call(move |db| {
            db.require_write(0)?;
            db.conn.execute(
                "INSERT INTO apply_journals VALUES(?1,?2,?3,?4)",
                params![
                    journal.id,
                    journal.candidate_id.as_str(),
                    journal.current_id.as_str(),
                    raw
                ],
            )?;
            Ok(())
        })
    }
    /// Only the application owner uses this recovery hook. Ordinary writes stay
    /// disabled by the active journal/hold, including after a process restart.
    pub fn update_apply_journal(&self, journal: ApplyJournal) -> Result<()> {
        let raw = json(&journal)?;
        self.call(move |db| {
            db.require_healthy()?;
            if journal.status=="applying" || journal.status=="rollingBack" { let prior:ApplyJournal=load_record(&db.conn,"SELECT record FROM apply_journals WHERE id=?1",&journal.id)?; if prior.status=="planned" || prior.status=="complete" {db.require_write(0)?;} }
            let tx=db.conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let old:ApplyJournal=load_record(&tx,"SELECT record FROM apply_journals WHERE id=?1",&journal.id)?;
            if old.current_id!=journal.current_id || old.candidate_id!=journal.candidate_id || old.root!=journal.root || old.head!=journal.head || old.index_digest!=journal.index_digest || old.files.len()!=journal.files.len() || old.files.iter().zip(&journal.files).any(|(a,b)|a.path!=b.path || a.before!=b.before || a.after!=b.after) {return Err(Error::Conflict);}
            let candidate=candidates::review(&tx,&journal.candidate_id)?;
            let session:Session=load_record(&tx,"SELECT record FROM sessions WHERE id=?1",candidate.candidate.session_id.as_str())?;
            let hold=format!("apply-{}",journal.id);
            if active(&journal.status) {
                let other:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM history_holds WHERE id<>?1)",[&hold],|r|r.get(0))?;
                if other {return Err(Error::ReadOnly);}
                tx.execute("INSERT OR IGNORE INTO history_holds VALUES(?1,?2,'Application outcome requires reconciliation')",params![hold,candidate.candidate.session_id.as_str()])?;
            } else {
                tx.execute("DELETE FROM history_holds WHERE id=?1",[hold])?;
            }
            tx.execute("UPDATE apply_journals SET record=?2 WHERE id=?1",params![journal.id,raw])?;
            if json(&old)?!=raw {
                let changes=old.files.iter().zip(&journal.files).enumerate().filter(|(_, (a,b))|a.status!=b.status).map(|(index,(_,f))|value!({"fileIndex":index,"status":f.status})).collect::<Vec<_>>();
                event(&tx,&session.workspace_id,Some(&session.id),"application.updated",value!({"planId":journal.id,"candidateId":journal.candidate_id,"status":journal.status,"files":changes,"rollbackFingerprint":journal.rollback_fingerprint}))?;
            }
            tx.commit()?;
            let report=db.storage_report()?;
            db.pending_at_open=report.pending_mutations>0 || report.holds>0 || report.pending_applications>0;
            Ok(())
        })
    }
}

pub(super) fn integrity(conn: &Connection) -> Result<()> {
    for raw in conn
        .prepare("SELECT record FROM apply_journals")?
        .query_map([], |r| r.get::<_, String>(0))?
    {
        let journal: ApplyJournal = decode(&raw?)?;
        identifier(&journal.id)?;
        if journal.files.len() > 128
            || ![
                "planned",
                "conflicted",
                "superseded",
                "applying",
                "rollingBack",
                "recoveryRequired",
                "recoveryConflict",
                "complete",
                "cancelled",
                "rolledBack",
            ]
            .contains(&journal.status.as_str())
        {
            return Err(Error::CorruptMetadata);
        }
        let current = load_snapshot(conn, &journal.current_id)?;
        let candidate = candidates::review(conn, &journal.candidate_id)?;
        let result = load_snapshot(conn, &candidate.candidate.result)?;
        let mut paths = BTreeSet::new();
        for file in &journal.files {
            path(&file.path)?;
            if ![
                "todo",
                "unchanged",
                "conflict",
                "writing",
                "applied",
                "writingRollback",
                "rolledBack",
                "recoveryConflict",
            ]
            .contains(&file.status.as_str())
                || (["writing", "applied", "writingRollback", "rolledBack"]
                    .contains(&file.status.as_str())
                    && !file.authorized)
            {
                return Err(Error::CorruptMetadata);
            }

            if !paths.insert(&file.path)
                || file.before.as_ref() != current.manifest.files.get(&file.path)
                || file.after.as_ref() != result.manifest.files.get(&file.path)
            {
                return Err(Error::CorruptMetadata);
            }
        }
    }
    Ok(())
}
