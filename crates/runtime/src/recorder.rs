//! Blocking filesystem reconciliation; called under one async-owned recorder lock.
use crate::worktrees::{self, Error, Result};
use history::{
    Attribution, Coverage, HistoryStore, Observation, OperationStatus, Recorder, SnapshotInput,
    Turn,
};
use protocol::{MutationKey, SessionId};
use serde_json::json;
use std::{
    collections::BTreeSet,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

pub enum Boundary {
    Start,
    TurnStart(String),
    Tool {
        id: String,
        terminal: bool,
        failed: bool,
    },
    TurnEnd(OperationStatus),
    Idle,
    Closed(OperationStatus),
}
pub struct WorkspaceRecorder {
    journal: Recorder,
    root: PathBuf,
    turn: String,
    active: BTreeSet<String>,
    observed: BTreeSet<String>,
    overlap: bool,
    failed: bool,
    gap: bool,
}
impl WorkspaceRecorder {
    pub fn new(store: HistoryStore, session: SessionId) -> Result<Self> {
        let record = store.get_session(session.clone())?;
        let root = store.get_workspace(record.workspace_id)?.worktree_path;
        let turn = format!("{}-observations", session.as_str());
        store.create_turn(
            MutationKey::try_from(turn.clone())
                .map_err(|_| Error::Invalid("invalid observation turn"))?,
            Turn {
                id: turn.clone(),
                session_id: session.clone(),
                prompt_provenance: "Filesystem observations outside a prompt; not an agent turn"
                    .into(),
                upstream_id: None,
                status: "completed".into(),
                created_at_ms: now(),
            },
        )?;
        Ok(Self {
            journal: Recorder::new(store, session)?,
            root,
            turn,
            active: BTreeSet::new(),
            observed: BTreeSet::new(),
            overlap: false,
            failed: false,
            gap: false,
        })
    }

    pub fn reconcile(&mut self, boundary: Boundary) -> Result<Option<String>> {
        let result = self.reconcile_inner(boundary);
        if result.is_err() {
            self.gap = true;
        }
        result
    }

    fn reconcile_inner(&mut self, boundary: Boundary) -> Result<Option<String>> {
        use Attribution::{Ambiguous, CheckpointInterval, External};
        use OperationStatus::{Completed, Failed};
        match boundary {
            Boundary::Start => self.capture("session.start", External, Completed, None),
            Boundary::TurnStart(turn) => {
                let result = self.capture("turn.before", External, Completed, None)?;
                self.turn = turn;
                self.active.clear();
                self.observed.clear();
                self.overlap = false;
                self.failed = false;
                Ok(result)
            }
            Boundary::Tool {
                id,
                terminal,
                failed,
            } => {
                if id.len() > 128 || (!self.observed.contains(&id) && self.observed.len() >= 16) {
                    return Err(Error::Invalid("tool interval exceeds recorder limits"));
                }
                if !terminal {
                    if self.active.contains(&id) {
                        return Ok(None);
                    }
                    // SDK notifications are observations, not a pre-execution barrier.
                    let result = if self.active.is_empty() {
                        self.capture("tool.observed", Ambiguous, Completed, None)?
                    } else {
                        None
                    };
                    self.active.insert(id);
                    self.observed.extend(self.active.iter().cloned());
                    self.overlap |= self.active.len() > 1;
                    Ok(result)
                } else {
                    let known = self.active.remove(&id);
                    self.observed.insert(id.clone());
                    self.overlap |= !known;
                    self.failed |= failed;
                    if !self.active.is_empty() {
                        return Ok(None);
                    }
                    let result = self.capture(
                        "tool.terminal",
                        if self.overlap {
                            Ambiguous
                        } else {
                            CheckpointInterval
                        },
                        if self.failed { Failed } else { Completed },
                        if known && !self.overlap {
                            Some(id)
                        } else {
                            None
                        },
                    )?;
                    self.observed.clear();
                    self.overlap = false;
                    self.failed = false;
                    Ok(result)
                }
            }
            Boundary::TurnEnd(status) => {
                let result = self.capture(
                    "turn.end",
                    if self.overlap || !self.active.is_empty() {
                        Ambiguous
                    } else {
                        CheckpointInterval
                    },
                    if self.failed { Failed } else { status },
                    None,
                )?;
                self.active.clear();
                self.observed.clear();
                self.overlap = false;
                self.failed = false;
                Ok(result)
            }
            Boundary::Idle => self.capture("idle.reconciliation", External, Completed, None),
            Boundary::Closed(status) => self.capture("process.stopped", Ambiguous, status, None),
        }
    }

    fn capture(
        &mut self,
        boundary: &str,
        attribution: Attribution,
        status: OperationStatus,
        tool: Option<String>,
    ) -> Result<Option<String>> {
        let started = now();
        let files = worktrees::capture_all(&self.root)?;
        let evidence = json!({
            "boundary":boundary, "logicalGroup":self.turn, "observedTools":self.observed,
            "overlap":self.overlap, "priorCaptureGap":self.gap,
            "stableReads":true, "coverage":"all worktree files except .git control data",
            "writerQuiescenceProven":false, "safeToApply":false,
            "limitation":"Net observed interval only; transient write/revert and unreported/background writers may be unobservable. Renames are inferred from unique matching content."
        }).to_string();
        Ok(self.journal.observe(SnapshotInput {
            workspace_id:self.journal.checkpoint().manifest.workspace_id.clone(), files,
            coverage:Coverage {complete:true, excluded:vec![]}, started_at_ms:started, completed_at_ms:now().max(started),
            provenance:"B09 full reconciliation; two matching scans, not an atomic filesystem snapshot or writer quiescence proof".into(),
        }, Observation {turn_id:self.turn.clone(), status, attribution, tool_call_id:tool, evidence})?)
    }
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(all(test, unix))]
mod tests;
