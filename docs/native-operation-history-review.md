# Native operation history review (B11)

Implemented on `feat/workspace-operation-recorder`, extending B14's history
browser. Rust owns queries and immutable content; Kotlin renders native lists,
a file tree and JetBrains diff requests. No additional dependencies or database
migration are required.

## Review recorded changes

1. Open **Flow → History → Refresh sessions**, then select a session.
2. Select a turn in creation order, or **All operations** for the entire session.
   Turns with no observed changes remain visible. Operations appear in observation
   order with status, attribution, timestamp and an `INCOMPLETE` marker when either
   snapshot has incomplete coverage.
3. Select an operation. Files are grouped by directory within the current page.
   Rows identify additions, modifications, deletions and renames inferred from a
   unique content match. Copy/delete ambiguity is not labelled a definite rename.
4. Select a file and press **Enter**, double-click, or **Open historical diff**.
   The native viewer shows immutable before/after text, historical paths and
   abbreviated content IDs. Both sides are forced read-only. The request identity
   includes the session, operation, file index and both full content IDs; mismatched
   responses are rejected. JetBrains' default diff presentation is used, including
   the editor area when enabled by the IDE.
5. **Open CURRENT workspace file** is a separate action for navigation to live
   content. Its message explicitly distinguishes that content from history. A
   missing/moved path or redirected workspace is reported; Flow does not guess a
   replacement file. Symlink paths are not followed. A recreated file at the same
   location may contain different bytes and is explicitly a current-file view.

Every list/page is bounded to 20 items. **Next** replaces the current page;
**First** (or Refresh sessions) returns to its beginning. Child selections are
cleared on parent/page changes, and pending reads are cancelled so stale replies
cannot open another selection's diff. The standard list/tree arrow keys and Tab
navigation remain available. Lists, the tree, coverage warnings and action results
have accessible names; actions use explicit text labels.

## Coverage, encodings and missing content

The coverage warning remains visible independently of the action result. If a
session ever reported an incomplete capture, later successful checkpoints do not
remove that warning: they cannot reconstruct unobserved writes. Snapshot exclusions
are counted in the operation details alongside the recorded attribution evidence.
Complete snapshots still do not prove every transient write was observed or that
background writers have stopped.

Text previews remain limited to 64 KiB per side and UTF-8. CRLF is identified in
notes and retained in the RPC content; native IDE rendering may normalize visual
line endings. Binary/non-UTF-8 or oversized content receives an explicit unsupported
preview message, not an empty diff. Absent files use an empty side with an
`absent` title and null content ID. Symlink history displays target text without
following it. Modes are included in the action result. Missing or corrupt immutable
blobs fail the request; live files are never substituted.

Current navigation validates the recorded workspace and each path component in
Rust, then rechecks the real path in Kotlin off the EDT before opening through
native navigation. This is a best-effort live-file lookup, not an immutable view
or a filesystem sandbox; another process can still move a file after validation.

## IPC additions

- `history.turns`: session ID and optional previous turn ID cursor; ordered by
  creation time and ID. Cursor lookup is scoped to the same session.
- `history.operations`: optional `turnId` filter, coverage/exclusion metadata,
  bounded attribution evidence and snapshot IDs; existing session-wide queries
  and sequence cursors continue to work.
- `history.files`: change kind and `contentIds {before, after}` per file.
- `history.diff`: accepts optional `contentIds` to verify selection identity;
  returns those IDs plus file index and the existing immutable preview fields.
- `history.location`: the same selection parameters; returns a validated current
  path or an explicit unavailable/moved/redirected note. It never supplies diff
  contents. No other history query reads live workspace files.

Reads use the existing bounded blocking-worker route, including when storage
admission is disabled. Session, turn and operation membership are checked before
any contents or current paths are returned. No read starts an agent, restores an
approval, applies changes or alters the repository's index.

## Validation

Automated checks cover bounded turn pagination, foreign cursors/turns, operation
filtering, durable capture warnings, mismatched content IDs, renames/deletions,
UTF-8 CRLF and unsupported encodings, missing blobs, changed/deleted current files,
store restart, and refusal of symlink or redirected navigation. Kotlin tests
verify native diff input identities, titles and the distinction between absent
and unsupported content.

Host smoke test after rebuilding the plugin:

1. Record two successive edits, then inspect each turn and historical file pair.
2. Close the session; modify/delete the current worktree file and reopen the
   earlier diff. Its contents and IDs must be unchanged. Current-file navigation
   must report missing/moved when appropriate.
3. Restart the engine and then the development IDE. Refresh history and reopen
   the same diff; verify titles and bytes. No agent should start.
4. Traverse session, turn, operation and file controls using Tab/arrows; press
   Enter to open a file diff. Verify both diff sides are read-only, screen-reader
   labels are meaningful, and First/Next controls handle more than 20 rows.

B12's controlled Swing-to-agent scenarios are now implemented; see
[First vertical slice acceptance](first-vertical-slice.md) for results and the
remaining real-Codex host gate.

Validation in this workspace: **76 Rust tests and 15 Kotlin/JUnit bridge tests
passed**, including a real engine/controller round trip through turns, filtered
operations, content identities, current-file lookup, live-file deletion and
engine restart. Kotlin compilation, formatting checks and Clippy with warnings
denied passed. Full Gradle packaging/verification remains blocked by the managed
sandbox's inability to write the wrapper lock under `~/.gradle`; run the host
Gradle command from the [Codex workflow guide](codex-task-workflow.md). The manual
IDE restart, keyboard/accessibility and diff presentation checks above remain
host verification, not claims made by the headless test suite.
