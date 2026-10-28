CREATE TABLE turn_images (
    turn_id TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    blob_id TEXT NOT NULL REFERENCES blobs(id),
    name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    PRIMARY KEY(turn_id,ordinal)
);
CREATE TABLE turn_checkpoints (
    turn_id TEXT PRIMARY KEY REFERENCES turns(id) ON DELETE CASCADE,
    snapshot_id TEXT NOT NULL REFERENCES snapshots(id)
);
CREATE TABLE prompt_queue (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','dispatching','uncertain')),
    record TEXT NOT NULL
);
CREATE INDEX prompt_queue_session ON prompt_queue(session_id,position);
CREATE TABLE queue_policy (
    session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    paused INTEGER NOT NULL DEFAULT 0 CHECK(paused IN (0,1))
);
CREATE TABLE queue_receipts (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    record_hash TEXT NOT NULL
);
