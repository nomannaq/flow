CREATE TABLE storage_policy (singleton INTEGER PRIMARY KEY CHECK(singleton=1), budget_bytes INTEGER NOT NULL CHECK(budget_bytes>0));
INSERT INTO storage_policy VALUES(1,10737418240);
-- Holds are the recovery/active-reference gate for future review and apply journals.
CREATE TABLE history_holds (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), reason TEXT NOT NULL);
-- Explicit deletion manifests, also permanent tombstones against session ID reuse.
CREATE TABLE deletions (session_id TEXT PRIMARY KEY, blobs TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0);
