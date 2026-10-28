CREATE TABLE session_labels (
    session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT '',
    favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN (0,1))
);
CREATE INDEX events_session_sequence ON events(session_id,sequence);
