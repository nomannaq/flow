# B04: immutable history storage

Branch: `feat/immutable-history-storage`. The `history` crate implements storage
behind B03's `ProtocolStore` interface. It depends on Flow DTOs, rusqlite, BLAKE3,
zstd and standard Rust concurrency; it has no ACP or IDE dependency.

## Ownership and threading

`HistoryStore::open(project_root, data_root)` requires existing directories. It
canonicalizes them, rejects data inside the project or a detected repository,
and creates a private `project-<BLAKE3 of canonical root>` directory. Each project
has `history.sqlite3`, `blobs/`, migration backups and an advisory `writer.lock`.
`open_default` uses the existing native application-data location (on the initial
macOS target, `~/Library/Application Support/Flow`). Relocating a project creates
a different store; automatic history migration is not implemented.

A bounded queue admits four jobs to a single named worker thread. Only that thread
opens or uses SQLite. Cloned handles share the worker. Calls block until their
result is available; B06 must call through bounded blocking tasks, never the
Swing event thread or an async executor worker. A full queue returns `Busy` before
admission. Dropping all handles drains admitted jobs and joins the worker. A
lost response or terminated worker is an unknown outcome; query the mutation key
before retrying. The OS releases the writer lock on process exit; there is no
stale lockfile deletion or force-unlock operation.

Workspace records contain the canonical worktree/common-Git paths, baseline
commit, owner identity and lifecycle. Sessions and turns retain provenance and
agent metadata. Registration records ownership; it does not create a worktree,
prove Git membership or launch an agent. Those checks belong to B07/B08. Lifecycle
transition policy belongs to B14; B04's domain records are immutable after creation.

## Publication and recovery

1. Validate bounded input, relative paths, types, modes and capture intervals.
2. Hash raw bytes, compress to a private temporary file, finish compression and
   sync it. Publish without replacing an existing object; verify existing objects
   before deduplication. Re-sync reused files and directory entries too, since
   an earlier attempt may have stopped between publication and directory sync.
3. Build the sorted manifest, retaining modes, file types, exclusions, completeness
   and capture provenance. Its content hash is its immutable snapshot identity.
4. In one SQLite transaction, store manifest references, domain metadata, a durable
   event and the successful mutation receipt. Commit before reporting success.

The database uses WAL, `synchronous=FULL`, foreign keys and macOS fullfsync where
supported. SQLite documents FULL-mode WAL commits as durable; physical guarantees
still depend on the local filesystem/device honoring sync. Network filesystems
are outside the initial support envelope. [SQLite synchronization rules](https://www.sqlite.org/pragma.html#pragma_synchronous)

A crash can leave unreferenced content or temporary files, but cannot legitimately
publish metadata before its objects. Startup runs SQLite integrity/foreign-key
checks, hashes referenced content and verifies manifest IDs and blob references.
Missing and corrupt objects return distinct typed errors; startup fails explicitly
rather than inventing empty content. Orphans are retained. No automatic cleanup,
repair, silent truncation or repository overwrite occurs in B04.

Schema versions 1 (history model) and 2 (events/receipts) apply transactionally.
An existing older database is backed up with `VACUUM INTO` before migration,
including committed WAL contents; backup bytes and the directory are synced.
A newer schema is rejected. Backups remain alongside the database for deliberate
recovery. [SQLite VACUUM INTO](https://www.sqlite.org/lang_vacuum.html#vacuuminto)

## Review data and retry semantics

`publish_snapshot` accepts already-observed bytes. It does not watch files or
attribute tool calls. `record_operation` stores exact before/after snapshot IDs
and derives file changes from those immutable manifests. Byte, mode and type
changes count; identical bytes share storage. Symlink targets are stored as bytes
and never followed. Renames currently appear as deletion/addition; inference is
B09. Absence in an incomplete capture is unknown, so it does not become a claimed
creation/deletion. Complete snapshot pairs support those deltas normally.

Workspace/session discovery and snapshot/operation lists use bounded keyset pages.
Session, snapshot and operation queries are scoped to their workspace/session.
Content reads return bounded byte chunks with total length and next offset. Each
chunk verifies the entire bounded object before returning any bytes; a verified
cache or seekable compression is deferred until benchmarks justify it.

Durable event cursors contain a persistent store ID and monotonic sequence. Replay
is ordered, exclusive of the supplied cursor, and includes a consistent high-water
mark. Foreign, future and unavailable cursors fail explicitly. There is no pruning
in B04. `ProtocolStore::snapshot` returns a consistent project/count overview and
high-water cursor; clients obtain detailed records through queries, rather than an
unbounded full-history response.

Domain mutations bind their key to BLAKE3(method + NUL + deterministic metadata
JSON). Same key/content returns the existing receipt without another domain event;
a different digest conflicts. Domain state, events and receipts commit together.
The lower-level `claim_mutation`/`finish_mutation` methods support receipt-only
work; they must not be used to split a domain publication across transactions.
Terminal receipts cannot regress or be rewritten to a different outcome.
Sequences use SQLite's positive signed 64-bit range, serialized by B03 as decimal
strings. Sequence exhaustion fails; it never wraps.

## Bounds and current integration

- Raw object and decompression window: 64 MiB; capture: 128 MiB; at most 4,096 entries.
- Serialized metadata: 512 KiB; content chunk: 64 KiB; page: at most 100 records.
- File paths: relative UTF-8, with no empty/dot/parent components or backslashes.
  Non-UTF-8 project roots/path names require a later explicit wire representation.
- The initial supported host is macOS arm64. Other native-data paths exist but
  have not been qualified for packaging or filesystem durability.

B03's engine still advertises durable events and idempotent mutations as false:
B04 supplies the store, not a project-binding RPC handler or Swing connection.
No new Kotlin dependency or UI is needed. B05 can build repository/agent fixtures
on this API; B06 and later handlers will connect it to the product lifecycle.

## Validation

Local validation on macOS arm64 (2026-10-14): the full Rust workspace suite passed
23 tests, including 11 history test entries (ten scenarios plus the crash-child
entry point). The subprocess recovery scenario exercised all three termination
boundaries. Formatting and Clippy with warnings denied passed. Kotlin/Gradle files
are unchanged in B04; native plugin validation was not rerun for this storage-only
change. A clean-checkout CI run remains separate evidence.

Reproduce with:

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
```

The history tests cover text/binary/empty objects, deduplication, immutable pairs,
mode/type differences, incomplete coverage, pagination, receipt/replay semantics,
project isolation, bounded queues, duplicate concurrent mutations, database
rollback, migration backups, newer schemas, missing/corrupt content and symlink
rejection. Subprocess tests terminate the writer after blob publication, before
commit and after commit, then reopen and retry to check for phantom success.
