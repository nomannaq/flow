# Session lifecycle and retained history (B14)

Implemented on `feat/workspace-operation-recorder`, alongside B09 and B25.
Rust remains authoritative for sessions, ownership and immutable history. Swing
holds only a view of that state. This feature does not load upstream conversations
or schedule concurrent agents.

## Use it

1. Run the development IDE and complete a task in **Flow → Session**.
2. Choose **Close session** before starting another writing session. Closing
   expires outstanding approvals, tears down the ACP connection and reconciles
   the workspace. A partial running turn remains interrupted, not successful.
3. Open **History → Refresh sessions**. Select a session, a turn (or All operations), an operation and a
   file, then **Open historical diff**. The native viewer reads immutable stored
   bytes, never substitutes current worktree content, and does not start an agent.
4. **Archive** a closed session to mark it retained for later review. This is
   idempotent and does not delete its workspace, operations or blobs. Archived
   sessions remain listed; the existing B25 retention command can delete them
   under its normal ownership and reference checks.
5. To continue work, select a closed/archived session and **New conversation…**.
   In Session, supply adapter settings and a new prompt, verify that all previous
   session writers have stopped, and tick the confirmation. Start the session.
   Flow acquires the repository/workspace leases and verifies the workspace still
   matches the selected session's final checkpoint. It creates a **new local
   session and a new upstream conversation**, using that checkpoint as baseline.
   Old approvals, prompts and upstream IDs are never reused. **Use a clean commit
   instead** returns to the ordinary new-worktree path.

A changed or interrupted retained workspace must first go through the explicit
[B25 recovery procedure](history-recovery-and-retention.md). Merely selecting a
session, reconnecting, or reopening the tool window never acknowledges a stale
owner. A live OS lease cannot be overridden. No action here applies edits to the
main project or changes its Git index.

## Ownership and restart guarantees

There is one active agent task per engine, a repository writer lease shared
across workspaces/data roots, and a project history-store lock. A second session
start is rejected while setup, a prompt, permission wait, cancellation, idle
conversation or teardown owns the task. Close immediately disables follow-ups
and approvals; repeated close after teardown is harmless.

The ACP SDK owns process creation and group termination. After SDK teardown,
Flow checks the process group recorded by this engine until it disappears, with
a bounded wait. If launch/shutdown ownership is unknown, Flow preserves lease
owner records for explicit recovery instead of silently making the project
writable again. A missing group does not prove escaped descendants or external
writers stopped; retained-workspace continuation still requires operator
verification and a stable checkpoint match. Flow does not kill or adopt a stale
PID. Worktrees are not process sandboxes.

Engine startup marks previously active durable sessions/turns interrupted and
starts no adapter. Pending approvals are in memory only and cannot authorize
work in the replacement engine. Unknown mutation outcomes are not replayed.
History browsing remains available when storage admission is blocked; missing
or corrupt content yields an error, not a fabricated diff.

## IPC and limits

All calls use the existing versioned, epoch-bound JSON-RPC connection:

| Method | Parameters | Result |
| --- | --- | --- |
| `sessions.list` | optional `after` session ID | Up to 20 summaries, opaque next session ID |
| `sessions.archive` | `sessionId` | Idempotent archive of a closed session |
| `history.operations` | `sessionId`, optional decimal-string `after` | Up to 20 ordered summaries and next sequence |
| `history.files` | `sessionId`, `operationId`, optional decimal-string `after` | Up to 20 changed paths and next file offset |
| `history.diff` | `sessionId`, `operationId`, `fileIndex` | Immutable before/after text, paths, mode and preview limitations |
| `sessions.start` | Existing adapter/prompt settings plus `sourceSessionId`, empty/omitted `commit`, `writersStopped: true` | Fresh session ID; asynchronous setup/errors through `sessions.active` |

Operation queries enforce session membership. Numeric history cursors use strings
for future hosts that cannot represent all SQLite sequences exactly. Requests
run on the bounded blocking-worker path, away from the EDT and async workers.
Lists replace one page at a time; Refresh returns to the first session page.

Text previews are limited to 64 KiB **per side** to fit the bounded IPC frame even
with JSON escaping. Binary/non-UTF-8 or larger contents remain stored, with an
explicit unsupported-preview message; they are not truncated into a misleading
diff. Symlinks display recorded target text without following the link. File
mode metadata is displayed separately. Full B11 timeline, richer filters,
large/binary-file review and navigation remain separate work.

## Validation

Automated coverage includes the real engine with the offline ACP fixture:
recorded-file diff across restart; no automatic agent/approval restoration;
archive retries; second-writer refusal; new conversation on the same workspace
with a fresh baseline/session ID; stale-session approval rejection; changed
workspace refusal; preserved stale leases on unknown shutdown; and Kotlin
controller history/archive calls. Existing recorder, crash/retention, cancellation,
process-descendant and repository-lock suites remain regression gates.

Manual smoke test: create `flow-recorder-test.txt` with `version 1`, change it to
`version 2`, close, and refresh History. Open both diffs, restart the engine,
refresh and reopen them. Archive the session, then explicitly start a new
conversation from it and verify the workspace still contains `version 2`.
The original project should remain untouched throughout.

Validation on this implementation: 71 Rust tests and 13 Kotlin/JUnit tests passed,
as did compilation, Clippy and formatting/lint checks. The managed sandbox denies
process-group signal-0 inspection, so integration tests assert recovery locks
remain in place and explicitly recover only their own stopped fixtures before
continuation. Normal host shutdown verification remains a manual smoke check.
Full Gradle packaging/Plugin Verifier could not start because the sandbox cannot
write the wrapper lock under `~/.gradle`. No user sessions were modified.

B11 extends this browser with turn navigation, file-tree grouping, capture warnings
and separate current-file navigation. See [Native history review](native-operation-history-review.md).
