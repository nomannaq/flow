-- Candidates retain immutable baseline/result snapshots. Decisions never write a worktree.
CREATE TABLE candidates (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), baseline_id TEXT NOT NULL REFERENCES snapshots(id), source_id TEXT NOT NULL REFERENCES snapshots(id), result_id TEXT NOT NULL REFERENCES snapshots(id), record TEXT NOT NULL, decision TEXT NOT NULL DEFAULT 'pending');
CREATE INDEX candidates_session ON candidates(session_id,id);
CREATE TABLE candidate_checks (id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL REFERENCES candidates(id), record TEXT NOT NULL);
