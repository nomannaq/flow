# B25 — History recovery and retention

Implemented on `feat/workspace-operation-recorder`, alongside B09. Rust owns
recovery, storage policy and retention; Kotlin displays the engine's storage
status. This milestone does not resume an ACP conversation or apply changes to
the main project.

## Recovery guarantees

- On startup, SQLite verifies metadata and all referenced compressed blobs.
  Missing/truncated content switches the opened store to read-only mode. Intact
  records and content remain readable; damaged content reads fail explicitly.
  Database corruption that prevents opening the schema produces an explicit open
  failure. There is no automatic database replacement or content repair.
- Previously live session/turn records become interrupted when storage permits.
  Startup inspects preparation journals, owned process records, the last durable
  operation's manifest and stable reads of the current worktree. It reports
  missing, redirected, changed or unstable worktrees without deleting them.
- A published snapshot without a committed operation does not advance a session's
  checkpoint. Unlinked snapshots and unpublished files remain retained. Explicit
  recovery walks the operation chain from the original baseline and uses a fresh
  mutation namespace, so abandoned publication keys cannot become fake success.
- Recovery compares current bytes with that checkpoint. Differences become a new
  **Interrupted / Ambiguous** operation. Recovery does not overwrite files,
  restart agents, reuse approvals, replay shell commands or invent intermediate
  states. Only successful capture closes the session.
- Before adapter launch, Flow persists its engine/ownership record. Its private
  launcher records the actual adapter PID before exec; normal SDK teardown records
  release. These are diagnostics, not proof that all descendants stopped. PIDs
  never authorize killing or adoption, and stale OS leases still require the
  exact previous owner instance token.

## Inspect and recover

Run these commands from the repository root, replacing `/path/to/project` with
**the project used by the sandbox IDE**, which can differ from Flow's source
repository. Close that project's sandbox IDE/Flow engine first: maintenance uses
its exclusive history writer lock and refuses a running engine.

```sh
cargo run -p engine --locked --offline -- \
  --history inspect --project-root /path/to/project
```

The JSON report lists session IDs, checkpoints, worktree comparison results,
preparation stages, process records, storage usage, unknown mutation counts and
pending retention manifests. Inspection does not acknowledge stale owners.

After inspecting and stopping all agent/background writers for the selected
session, explicitly reconcile it:

```sh
cargo run -p engine --locked --offline -- \
  --history recover --project-root /path/to/project \
  --session SESSION_ID --writers-stopped
```

If a crash left a stale ownership record, this refuses and prints its exact
instance token. After checking its processes, repeat with `--owner INSTANCE_TOKEN`.
The flag acknowledges that specific owner; it does not terminate anything. An
active kernel lease cannot be overridden. Different stale leases can require
separate inspection; never infer quiescence from an absent engine PID.

If a worktree is missing, redirected, has changed Git ownership/HEAD, or cannot be
captured within limits, recovery stops and leaves retained history available.
Restore/inspect ownership manually; no Git prune/reset/force-removal is performed.
Incomplete B07 preparations are reported, not silently continued. Their existing
`WorktreeManager::cleanup_incomplete` API still permits only proven owned, clean,
incomplete worktrees; dirty or uncertain preparations remain for inspection.

A healthy restart can reopen repaired content after verification. Recovery never
bypasses content hashes. Unreadable SQLite metadata requires restoring a trusted
backup rather than guessing repairs.

## Storage budget

The default is **10 GiB per canonical project**. Schema v3 persists the policy;
upgrade from an older schema first creates the existing consistent SQLite backup.
The budget counts history files, compressed blobs (including unpublished ones),
SQLite/WAL/SHM and migration backups. It **excludes live worktrees** because agents
can write to them independently. It is application write admission, not an OS disk
quota or a total disk-usage limit.

Before publication, Flow reserves the uncompressed input size plus conservative
compression overhead and 16 MiB for metadata/WAL. Other writes also require that
reserve. Consequently a write can be rejected before the displayed byte limit is
reached. Real `SQLITE_FULL` and I/O errors preserve the previous transaction and
latch history writes off until restart/recovery; no cleanup runs to make room.
Storage reads remain available. Large external writes can still fill the disk.

To change the limit explicitly (example: 20 GiB):

```sh
cargo run -p engine --locked --offline -- \
  --history budget --project-root /path/to/project --bytes 21474836480
```

Raising the limit does not clear damaged-content or unknown-mutation gates. Native
engine health indicates process responsiveness separately from history health.
The Engine tab displays current usage, limit and any write-blocking reason.

## Explicit retention

First inspect and recover/close the selected session as above. Then:

```sh
cargo run -p engine --locked --offline -- \
  --history delete-session --project-root /path/to/project \
  --session SESSION_ID --writers-stopped
```

This permanently removes that session's turns, operations and exclusively owned
non-baseline snapshots. It requires proven worktree ownership, exclusive leases,
status `closed`, no active history holds, no unknown mutation outcomes and no
other session using the same workspace. Shared blob references are checked in the
same database transaction. Only blobs made unreachable by **this explicit
selection** are eligible for physical removal.

The worktree, its Git registration/ownership files, original preparation baseline,
shared blobs, unrelated sessions and unrelated orphan files remain. This is not a
worktree removal command, automatic orphan collector or secure erasure facility.
Existing migration backups may still contain historical metadata. SQLite freed
pages are reusable and need not shrink the database file immediately.

Session event payloads are redacted into tombstones without gaps in event sequence
numbers. Store identity rotates so old event cursors require a fresh snapshot.
Mutation receipts remain as historical idempotency records, and a permanent
session-ID tombstone prevents recreating a deleted session under a fresh key.

The committed deletion manifest lists eligible blobs. Physical unlinking follows
commit and rechecks reachability, so a crash cannot leave live snapshots pointing
to removed content. A subsequent publication that reuses a candidate protects it.
To retry only previously committed deletions:

```sh
cargo run -p engine --locked --offline -- \
  --history finish-retention --project-root /path/to/project
```

There is no automatic age policy, global mark-and-sweep, removal of backups, or
Git worktree pruning. Retention may run above the configured budget, but still
needs enough physical space for its small transactional metadata changes. An
unknown or damaged state never triggers data deletion as a recovery strategy.

All commands accept `--data-root /existing/external/directory` for isolated fixtures
or custom engine storage. Use the same data root as the engine.

## Recovery hooks for future mutations

`HistoryStore::hold_history(id, session, reason)` persists an active reference
before a future review/application starts. Retention refuses referenced sessions;
leftover holds at restart disable ordinary writes until their owner handles them.
`release_history` is called only after that feature verifies its outcome.

Pending/unknown mutation receipts at startup also disable ordinary writes.
`resolve_interrupted_mutation` accepts a matching-digest, terminal receipt supplied
by a **mutation-specific recovery handler** after examining its external journal.
Generic startup and maintenance commands never guess the outcome. B19 must persist
a hold and application journal before mutating files and implement that handler
before enabling apply. Releasing a hold is not sufficient to resolve a pending
receipt. There is deliberately no generic CLI “mark successful” or “clear holds”.

## Validation

Owned temporary repositories cover budget exhaustion before publication, actual
SQLite `SQLITE_FULL` via page limits, database locks, missing/truncated blobs,
pending mutations/holds, interrupted publication, recovery from the last committed
operation, exit after operation commit before in-memory checkpoint advancement,
retention exit after metadata commit, idempotent cleanup, republished blob
protection, missing/redirected worktrees, stale/active lease refusal and preservation
of newer workspace bytes. Kotlin protocol tests cover visible damaged-state status.

```sh
cargo test --workspace --all-targets --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo fmt --all --check
cd integrations/jetbrains
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Validation in this environment: all 68 Rust tests and 12 Kotlin/JUnit tests passed,
as did Clippy with warnings denied, Rust formatting, Kotlin compilation and lint.
The full Gradle command could not start because the sandbox cannot write the
wrapper lock under `~/.gradle`; packaging and Plugin Verifier remain host checks.
No existing user session was recovered or deleted during these tests.
