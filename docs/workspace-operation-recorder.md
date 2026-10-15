# B09 — Workspace operation recorder

Branch: `feat/workspace-operation-recorder`.

Flow now compares actual worktree contents at explicit boundaries and publishes
immutable before/after snapshot pairs through the history store. The native
Session activity reports `workspace.recorded`; the Engine tab's operation count
updates through existing durable event replay. No new ACP transport, watcher,
database, or Kotlin history store is introduced.

## Capture and storage

- `runtime::worktrees::capture_all` includes tracked, untracked, hidden and ignored
  worktree files. Git control data named `.git` is outside the capture domain.
  Symlinks store their target bytes; discovery never deliberately follows them.
  Nested repositories and special files fail capture. This is observation inside
  a trusted worktree, not an OS sandbox against adversarial filesystem races.
- Each regular read checks device/inode, size, mode, modification and change
  timestamps before and after reading. Two consecutive complete scans must match;
  at most four scans are attempted. Renames, missing paths and concurrent writes
  that prevent stable reads fail closed. No partial scan implies file deletion.
- Existing limits apply: 4,096 paths, 64 MiB per blob and 128 MiB per capture,
  plus bounded history metadata. Ignored build output can exceed these limits;
  it is not silently omitted from a supposedly complete checkpoint.
- `history::Recorder` is independent of ACP, Git and IDE APIs. It accepts complete
  snapshot inputs, skips unchanged manifests, publishes immutable content, records
  an operation, then advances its in-memory checkpoint. Failure to publish an
  operation latches the journal closed. Snapshot publication and operation commit
  are separate durable transactions; an orphan snapshot is not an advanced
  checkpoint. [B25 recovery and retention](history-recovery-and-retention.md) now
  reconcile interrupted history explicitly. B14 reopens local history and can start
  a new conversation in a verified retained workspace; upstream resume is deferred.
- Create/delete, binary bytes, symlink changes and permission bits are preserved.
  A unique deleted/created pair with matching blob and file kind is presented as
  an inferred rename, including any mode change. Duplicate matches remain
  separate additions/deletions. Renames with changed content are not guessed.

## Boundaries and attribution

| Boundary | Recording behavior |
| --- | --- |
| Session setup | Reconcile prepared baseline before adapter launch |
| Before a prompt | Capture intervening outside-turn changes before advancing the turn grouping |
| First observed tool notification | Reconcile preceding changes as ambiguous; notification is not a pre-execution barrier |
| Tool terminal notification | Capture when the known active group empties; overlapping/missing starts are ambiguous |
| Turn completion/cancellation | Full reconciliation even if no file/tool notifications arrived |
| Idle session | Reconcile every two seconds for background or external changes |
| SDK teardown | Reconcile final partial effects before releasing the worktree lease |

Known overlapping tools share one observed interval. A failed tool marks the
group's captured effects failed. Cancellation and closure during a turn preserve
interrupted effects. Tool IDs are evidence, not proof of authorship. At most 16
distinct tool IDs of at most 128 bytes are retained in a group; exceeding those
bounds stops the session and attempts final reconciliation after SDK teardown.

Every operation carries its turn ID as a logical grouping hint and bounded
evidence describing the boundary, overlap, observed tool IDs and prior capture
gaps. Outside-turn observations use `External`, meaning the change was observed
outside a prompt; this does not identify a human rather than a background agent
as the writer. A synthetic observation turn covers changes before the first
prompt. No checkpoint observation claims `Exact` tool attribution.

Two matching scans are not an atomic filesystem snapshot. An edit then revert
between checkpoints is unobservable, and a sleeping child may write after a tool
or turn finishes. Evidence therefore always reports `writerQuiescenceProven: false`
and `safeToApply: false`. There is no apply action in B09. Capture errors emit
`workspace.captureIncomplete`, stop the live adapter and preserve the last
verified checkpoint. A subsequent successful teardown capture retains evidence
of the gap; it does not erase the failed observation.

## Validation

```sh
cargo test --workspace --all-targets --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo fmt --all --check
```

Regression cases cover three historical edits reopened from disk; matching
before/after chains; unobservable transient reverts; failed and overlapping tools;
missing events and external writes; ignored/binary files, mode changes, deletion
and rename inference; changing scans; capture overflow; and failure between
snapshot publication and operation recording. SDK integration tests exercise
the actual engine/adapter path and read persisted partial-write and repeated-edit
records after shutdown. Native bridge tests remain the existing Kotlin/JUnit suite.

For a manual smoke check, start a session in a disposable committed project and
ask for three separate edits in three turns. Check `workspace.recorded` activity
and the Engine operation count. Inspect the retained worktree; main project files
must remain untouched. Try cancellation and an agent command that fails after a
write. B14 provides bounded historical diffs in the History tab. B11 adds the full
timeline, richer file navigation and review controls.
No real provider run or process `SIGKILL` recovery is claimed by offline tests.

Local validation on 2026-10-14: **52 Rust tests and 11 Kotlin/JUnit tests passed**,
including the real engine → SDK → offline adapter path. Clippy with warnings
denied, Rust formatting, Kotlin compilation and lint also passed. Full Gradle
`check buildPlugin verifyPluginProjectConfiguration verifyPlugin` could not start
because the sandbox cannot write its wrapper lock under `~/.gradle`. Packaging,
Plugin Verifier and a real-agent native smoke run remain host checks.

## Reopening recorded operations

[B14 retained history](session-lifecycle-and-history.md) adds the **History** tab:
refresh sessions, select an operation and file, and open the immutable native
diff. B25 recovery and B14 explicit new-conversation controls preserve the
recorder's checkpoint boundaries; reopening history never starts an adapter.

## Native review

B11 exposes the recorded intervals through session/turn/operation navigation,
coverage indicators and content-identified native diffs. See
[Native operation history review](native-operation-history-review.md). Review
uses immutable content; navigating to a current workspace file is a separate action.
