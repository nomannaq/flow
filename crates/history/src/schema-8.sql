CREATE TABLE attachments (
    id TEXT PRIMARY KEY,
    blob_id TEXT NOT NULL REFERENCES blobs(id),
    record TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE turn_attachments (
    turn_id TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    attachment_id TEXT NOT NULL REFERENCES attachments(id),
    PRIMARY KEY(turn_id,ordinal)
);
CREATE TABLE queue_attachments (
    prompt_id TEXT NOT NULL REFERENCES prompt_queue(id) ON DELETE CASCADE,
    attachment_id TEXT NOT NULL REFERENCES attachments(id),
    PRIMARY KEY(prompt_id,attachment_id)
);
ALTER TABLE deletions ADD COLUMN attachment_files TEXT NOT NULL DEFAULT '[]';
