# B05: deterministic agent and repository fixtures

Implementation branch: `feat/fake-agent-repository-fixtures` (the plan calls this
`test/fake-agent-and-repository-fixtures`). Depends on B03's protocol and B04's
history store. The fixtures are test-only; they do not launch from the plugin.

## Design

- `tests/support/agent.rs` uses the locked ACP 3.3.0 stable-v1 agent builder and
  SDK connection machinery. It handles initialization, a workspace-bound session,
  allow-once permission requests, messages, edits, cancellation and partial-write
  failure. Fixed fixture paths cannot be chosen by prompt text. Tokio notifications
  release delayed work deterministically; wall-clock timeouts only guard hangs.
- The upstream [shared test package manifest](https://github.com/agentclientprotocol/rust-sdk/blob/main/src/agent-client-protocol-test/Cargo.toml)
  sets `publish = false`. Instead of depending on repository HEAD or copying its
  helpers, Flow uses the released SDK directly. Its [connection API](https://docs.rs/agent-client-protocol/3.3.0/agent_client_protocol/trait.ConnectTo.html)
  supports connecting the fake agent to the client in memory. Request work runs
  outside the dispatch callback so permission responses and cancellations can
  continue to arrive.
- `tests/support/repository.rs` creates real Git repositories exclusively under
  an owned `TempDir`. Git runs without inherited Git overrides, user configuration,
  hooks, signing, credentials or network access. Author identity and commit dates
  are fixed. Fixture files have explicit modes, and capture times/mutation IDs
  come from an injected sequence, not wall-clock time or randomness.
- Explicit test captures traverse the disposable workspace without following
  symlinks and publish into B04 storage outside the repository. This helper is
  **not** the production recorder: it does not promise an atomic scan of a live
  workspace. Scenarios pause or finish writes before capturing.
- Expected manifests are authored separately from the scanner and contain raw
  bytes, file kind and mode. The oracle reads persisted blobs, including multiple
  chunks, and compares exact path sets and values. It does not use the recorder's
  hash or diff algorithm to decide correctness. Baseline, first edit and second
  edit remain readable after reopen; their operation links are checked too.
- The raw line injector is limited to malformed/truncated JSON and abrupt EOF.
  Normal ACP traffic uses the SDK. The pre-existing B01 Python process adapter
  remains separate for subprocess crash, descendant cleanup, timeout, ignored
  cancellation, authentication and redaction checks. B05 adds a process crash
  after writing `partial`, before any success notification/checkpoint.

## Coverage and acceptance

| Case | Observable assertion |
| --- | --- |
| Two sequential SDK edits | Three exact snapshots and two linked operations survive reopen; two independent runs have identical snapshot IDs and logical traces |
| Permission denied | Original file remains unchanged; prompt stops cancelled |
| Partial failure | An RPC error leaves the actual partial bytes available to capture |
| Delayed/cancel-resistant agent | Cancellation arrives while a prompt is pending; cooperative agent stops without writing, resistant agent writes only after explicit release |
| Process crash after write | Partial bytes remain; last verified checkpoint is intact; no phantom successful checkpoint; child processes are cleaned up |
| Unreported shell write | Disk scan includes a file without any ACP change report |
| Dirty index/worktree | Staged and unstaged versions differ; capture sees disk bytes and leaves the index byte-for-byte unchanged |
| Rename/delete/recreate | Each explicit checkpoint has the expected paths and content |
| Binary/large file | Non-UTF-8 bytes and a blob larger than one read chunk round-trip exactly |
| Symlink/executable/unusual path | Link target bytes, modes and Unicode/newline paths survive; symlink target outside the repository is untouched |
| Conflict | Real Git stages 1/2/3 contain base/developer/agent bytes; abort retains developer version |
| Incorrect snapshot expectation | Wrong bytes, path set, mode, kind or historical version fail the independent oracle |
| Ownership/cleanup | Dropping the fixture removes its directory; an unrelated temporary sentinel survives |
| Broken transport | Malformed JSON, truncated response and EOF fail within a bounded timeout |

The repository harness currently targets Unix, matching the initial macOS
release. Windows filesystem/process fixtures belong with the platform-expansion
work; the suite is explicitly gated rather than claiming cross-platform coverage.
The fake adapter proves deterministic behavior under its scripts, not compatibility
with real agents. Keep the separate real-adapter evidence in
[the compatibility runbook](compatibility-spikes.md); never substitute fixture
success for authentication, adapter provenance or real-agent evidence.

## Manual validation

GitHub workflow definitions were removed at the user's request. No hosted runs,
remote settings or branch protections were deleted or changed.

From the repository root:

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
cargo test -p runtime --test fixtures --locked
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b05-fixtures.json
```

The explicit fixture target is useful for local iteration; the workspace command
also includes it. The Python runner executes 18 experiments with expected failures
counted as successful assertions and saves redacted evidence outside the repository.
It retains disposable probe directories for inspection, whereas the Rust `TempDir`
fixtures remove their owned repositories on drop. Native validation commands remain
in [build foundation](build-foundation.md); B05 changes no Kotlin code or plugin APIs.

## Validation result

Validated on macOS on 2026-10-14:

- `cargo test --workspace --all-targets --locked --offline`: 29 tests passed,
  including all six B05 integration tests, with no failures or ignored tests.
- `cargo clippy --workspace --all-targets --locked --offline -- -D warnings`:
  passed.
- `cargo fmt --all --check` and `git diff --check`: passed.
- The subprocess runner passed all 18 experiment expectations; redacted local
  evidence is `/tmp/flow-b05-fixtures.json`.

Native Gradle checks and paid real-adapter checks were not rerun for these
test-only Rust/Python changes. Their earlier evidence remains separate. Next is
B06's project-scoped JetBrains engine bridge, using these deterministic fixtures.
