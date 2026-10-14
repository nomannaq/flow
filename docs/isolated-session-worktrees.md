# B07 — Isolated session worktrees

Branch: `feat/isolated-session-worktrees`.

`runtime::worktrees::WorktreeManager` prepares an owned, detached Git worktree
at an explicitly selected full commit ID and publishes its immutable disk
baseline through the existing history store. B08 will connect this API to agent
sessions and native session controls. B07 does not add an agent, import local
changes, or change the JetBrains health/restart interface.

## Using the runtime

1. Open `HistoryStore` for the canonical project and an existing external data
   directory, then open `WorktreeManager` with `Recovery::RefuseStale`.
2. Present the clean-commit baseline choice. `repository().head` is the resolved
   commit observed at discovery. Pass that full ID in `BaselineChoice::CleanCommit`.
   A later branch movement does not change the selected baseline.
3. Call `prepare` on a bounded blocking worker. Keep `PreparedWorktree` alive
   for the entire writing session, including cancellation and child termination.
4. Use its workspace path, workspace ID and baseline ID when creating the B08
   session. Dropping the guard releases writer ownership but keeps the worktree
   and baseline. `acquire` explicitly obtains another writer guard for a ready
   worktree, preserving its current files.

All API calls are blocking. Cancelling an async waiter does not cancel an
admitted preparation; retain/join the blocking job and inspect its journal.
Preparation records, not a second Kotlin state store, determine readiness.
History's workspace registration has lifecycle `owned`; the preparation journal
tracks the more specific stages below.

## Manual exercise

Use a disposable local repository with an initial commit. The data directory
must exist outside every Git repository. From Flow's root:

```sh
mkdir -p /tmp/flow-b07-data
git -C /absolute/path/to/disposable-repo rev-parse HEAD

# Substitute that full commit ID and the repository's absolute path.
cargo run -p runtime --example worktree --locked -- \
  /absolute/path/to/disposable-repo /tmp/flow-b07-data prepare FULL_COMMIT_ID

cargo run -p runtime --example worktree --locked -- \
  /absolute/path/to/disposable-repo /tmp/flow-b07-data list
```

The JSON result includes the retained worktree path and immutable baseline ID.
The example releases the writer when it exits. `inspect WORKSPACE_ID` verifies
and reacquires a ready workspace briefly; it does not reset it. `cleanup
WORKSPACE_ID` is restricted to incomplete preparations. Ready worktrees are
intentionally retained; general session deletion belongs to later lifecycle work.

## Ownership and recovery

- A kernel advisory lock at the common Git directory's `flow-worktrees.lock`
  coordinates Flow engines, including engines opening another linked worktree
  or using another history data directory. It remains held while either the
  manager or any writer guard exists. It does not coordinate unrelated Git tools.
- A project writer lease enforces one active writer; each workspace also has
  its own lease. Metadata records a PID, creation time and random engine-instance
  token. The kernel lock determines liveness; a recycled PID does not authorize
  takeover. Normal release clears metadata without unlinking the lock inode.
- After a crash, opening a lease reports `Error::StaleOwner`. Explicit
  `Recovery::Acknowledge(previous_instance)` is required. The example accepts
  `--recover-repository TOKEN` and `--recover-writer TOKEN` independently. A
  live lock cannot be overridden, and a wrong token cannot clear stale metadata.
- `preparation.json` is written through a synced temporary file and atomic rename
  in a private history subdirectory. Stages are `Reserved`, `Created`, `Ready`,
  and `Removed`. `Created` means checkout and ownership checks finished; `Ready`
  means history publication finished. Abrupt exit can leave an earlier stage.
- Worktrees are created with Git's administrative lock and a random ownership
  marker to prevent pruning. Cleanup verifies the canonical path, common Git
  directory, per-worktree administration, backlink, lock marker and detached HEAD.
  It refuses ready worktrees, modified/ignored/untracked contents, redirected paths
  and changed ownership. It never runs force removal or repository-wide pruning.
- Missing paths with retained Git administration, corrupt metadata, partially
  checked-out trees or cleanup interrupted after unlocking require inspection.
  They are retained rather than guessed safe to delete. Empty reserved records
  without a worktree or Git registration can be marked removed.

## Baseline and repository envelope

Initial target: the validated macOS host and compatible Unix systems with
`/usr/bin/git` supporting `--path-format=absolute` and locked worktree creation.
Windows support remains deferred. Version and canonical common directory are
recorded in repository discovery. Bare/unborn repositories and project
subdirectories cannot be prepared; open a non-bare root with an initial commit.

Only the selected commit is checked out. The main index, HEAD and disk files
are never reset, stashed or cleaned. Main-project fingerprints before/after
preparation detect changes to HEAD, index bytes, tracked and eligible untracked
contents and modes. Concurrent external changes cause preparation to fail and
retain its worktree; they are never rolled back. Ignored main-project files and
unsaved editor buffers are not imported or monitored by this fingerprint.

Tracked paths are enumerated with NUL-delimited Git output separately from
`ignore` discovery, so tracked files matching ignore rules remain in snapshots.
Untracked discovery uses local ignore rules, includes dotfiles, disables global
and parent ignore files and never follows links. Baseline completeness covers
checkout content; `.git` administrative metadata is outside that scope. Symlink
snapshots store link-target bytes, not target contents. Binary bytes, executable
modes, spaces and newlines in file names are preserved. Non-UTF-8 paths,
unsupported history paths, special files, nested repositories and path escapes
fail explicitly. Existing history entry/blob/capture limits apply.

Unmerged indexes, skip-worktree/assume-unchanged entries, sparse/partial clones,
submodules, configured checkout filters and filter/LFS/working-tree-encoding
attributes are rejected. Filter checks run against the new index before checkout,
including when the selected commit differs from the main index. Local CRLF/EOL
checkout transformations can affect disk content; the persisted baseline always
records actual disk bytes with the selected commit as provenance.

Git runs with typed arguments, a fixed executable, controlled cwd/environment,
disabled hooks/fsmonitor/network/global config, no external diff command, a
30-second per-command deadline and an 8 MiB output limit. Raw Git stderr is not
logged. Large repositories may exceed these conservative bounds and fail with a
retained journal. A worktree is not a security sandbox: future agents and unrelated
processes can modify shared Git metadata or files outside it. Advisory locks and
path checks do not prevent a malicious same-user process from racing filesystem
operations. B08/B09 must supervise writers before recording or applying changes.

The approach uses Git's native [worktree administration](https://git-scm.com/docs/git-worktree)
and [tracked-path enumeration](https://git-scm.com/docs/git-ls-files), checked on
2026-10-14. Flow adds ownership policy, persistence and baseline verification.

## Validation

```sh
cargo test --workspace --all-targets --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo fmt --all --check
```

B07 tests use disposable B05 Git fixtures and an independent expected-content
oracle. They cover dirty main-index preservation, tracked ignored files, binary
and executable files, symlinks, fixed commits after branch movement, changes
during preparation, duplicate managers and linked roots, guard lifetime,
injected preparation failure, conservative cleanup, ownership tampering,
unsupported repository shapes and a real child-process crash requiring explicit
stale-owner recovery. No network, model credentials or IDE launch is required.

Validated on 2026-10-14: all 40 workspace tests passed, Clippy passed with warnings
denied, and formatting/diff checks passed. A separate command-line smoke test
prepared, listed and reacquired a persisted workspace while preserving dirty
source contents and the main index byte-for-byte. No Kotlin, Gradle or IPC
contract changes were required for this branch.
