-- An apply journal retains all inputs before the host is authorized to write.
CREATE TABLE apply_journals (id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL REFERENCES candidates(id), current_id TEXT NOT NULL REFERENCES snapshots(id), record TEXT NOT NULL);
