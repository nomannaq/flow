CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, workspace_id TEXT NOT NULL REFERENCES workspaces(id), session_id TEXT REFERENCES sessions(id), kind TEXT NOT NULL, payload TEXT NOT NULL, created_at_ms INTEGER NOT NULL);
CREATE TABLE mutations (key TEXT PRIMARY KEY, receipt TEXT NOT NULL);
