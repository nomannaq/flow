# Codex task workflow (B10)

Implemented on `feat/workspace-operation-recorder`, together with B09, B25 and
B14. Flow uses the existing ACP SDK host and immutable recorder; it does not run
an independent model execution loop or copy Codex credentials.

## Start a task

1. Open **Flow → Session**, use **Browse…** to select an installed `codex-acp`
   executable, and optionally enter a model supported by your native login.
   **Save adapter and model** remembers these two preferences on this machine.
   Advanced arguments, authentication method, prompts and permissions are not
   saved as settings. Do not place credentials in arguments or prompts: agent
   activity and prompts form part of local history.
2. Leave the model field empty to use the adapter configuration. If supplied,
   Flow appends `-c` and a quoted `model` value after advanced arguments, so this
   explicit field takes precedence over earlier overrides. No shell is involved.
3. **Use current commit** selects the baseline for a separate worktree. Local
   edits and unsaved editor buffers are excluded. Git, an existing commit and
   an executable adapter are required; setup failures appear in session activity.
4. Enter a task and **Start Codex session**. Existing native login is used first.
   If the adapter requires authentication, select an advertised method and
   **Authenticate and retry**. Complete any browser/native login owned by the
   adapter. Rejected authentication can be retried in the same session/worktree;
   no task is submitted before session creation succeeds. Authentication calls
   time out after two minutes; waiting for an explicit retry expires after five.
   Close remains available during setup/authentication. API-key entry and
   terminal-authentication methods are not supported by this UI.
5. Review each permission request. Flow exposes only the adapter's one-time
   allow/reject choices and Deny. Responses are tied to the live session and
   request; closing or restarting expires them.
6. Read the streamed response, send follow-ups or cancel a running turn. Adjacent
   text chunks are joined in Rust's bounded UI projection; durable individual
   events remain intact. The count reports committed filesystem operations in
   this conversation, not tool calls or merely reported edits. A failed capture
   retains the last verified checkpoint and displays a warning.
7. Use **History** to inspect immutable diffs, then **Close session** when done.
   Files remain in the session worktree until a later application feature is
   implemented. They do not automatically appear in the main project's Commit UI.

If login/model configuration fails during a prompt, Flow emits a static diagnostic,
reconciles partial effects and closes the connection. Fix the configuration and
start a new conversation using B14, after any required B25 recovery. It never
silently retries a prompt that could already have changed files. Authentication
retry applies to session setup only.

## Compatibility boundary

B01 tested an operator-owned **codex-acp 0.5.2** sample with ACP v1 and SDK 3.3.0.
The observed sample SHA-256 was
`027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524`.
A matching reported name/version is labelled as the B01 experiment, **not binary
provenance verification or an approved distribution**. Other releases display
an unverified-release diagnostic and need their own acceptance evidence. Flow
neither downloads nor upgrades the adapter. See the [B01 ADR](adr/0001-b01-agent-and-host-boundaries.md).

`gpt-6-astra` was the user's successful B01 model override; it is not a universal
subscription entitlement or a default imposed by Flow. Select a model available
to the adapter's authenticated account. Raw provider errors are excluded from
history diagnostics; only static authentication/model/connectivity guidance is
shown. Authentication tokens are never accepted by the Flow IPC auth method.

Flow currently exposes text tasks, one-time permissions and local history.
Upstream conversation resume, attachments and context MCP attachment are not
implemented, even if an adapter advertises those capabilities. Settings persist
locally; active conversations are never recreated automatically after restart.

## Protocol and projection

- `sessions.authenticate {sessionId, methodId}` queues one explicitly selected,
  advertised agent-managed method only while `authenticationRequired`. Duplicate,
  stale and invented methods are rejected. `authenticating` is an active durable
  state; a crash marks it interrupted on restart.
- `sessions.active` adds `compatibility` and `recordedOperations` (optional when
  empty/zero). `authMethods` lists supported agent-managed methods only.
- Permission options include `allowOnce: true` for actual SDK AllowOnce options;
  automation must not infer approval semantics from an option's label or ID.
- The projection keeps at most 100 activity entries and 96 KiB of text. Adjacent
  chunks join up to 8 KiB per entry. Older projected entries are evicted; durable
  history is retained. Kotlin renders these values and performs no diff/state
  reconstruction. Existing connection/frame/event limits continue to apply.

## Acceptance

The normal offline suite covers authentication rejection and retry, stale or
unsupported auth methods, permission denial, follow-ups, cancellation, adapter
crashes, operation counts, stream coalescing and historical filesystem effects.
Tests use an ACP fixture and do not require provider credentials or paid requests.

Build and run the full pipeline acceptance script in fixture mode:

```sh
cargo build -p engine --locked
python3 scripts/check_codex_session.py --output /tmp/flow-b10-fixture.json
```

For an explicitly opted-in real Codex task, provide your adapter's independently
verified checksum and account-compatible model. This permits one-time tool
approvals for the two disposable-repository tasks and may consume model quota:

```sh
python3 scripts/check_codex_session.py \
  --adapter "$HOME/Library/Application Support/Zed/external_agents/codex/v0.5.2/codex-acp" \
  --sha256 027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524 \
  --adapter-arg=-c \
  --adapter-arg='model="gpt-6-astra"' \
  --allow-once \
  --output /tmp/flow-b10-real.json
```

The script creates a temporary committed Git project, runs two turns through the
actual engine IPC and SDK, verifies exact successive immutable diffs against the
worktree, checks the operation count and unchanged main project, closes the
session, and checks the final filesystem manifest using offline inspection.
It retains scratch data on both success and failure. Evidence contains IDs,
counts and compatibility/check results, not transcripts or credentials. A
shutdown that cannot be verified fails acceptance and retains ownership for
explicit recovery; the script does not acknowledge stale ownership or delete
workspaces. Fixture evidence does not replace the opt-in real-adapter check.

Implementation validation: 74 Rust tests and 14 Kotlin/JUnit bridge tests passed;
Kotlin compiled against the cached target IDE/JDK 21, and Rust Clippy passed with
warnings denied. The offline B10 runner verified both historical transitions,
operation counts, main-project isolation and the final manifest. Its acceptance
result remains failed because this managed sandbox denies the process-group
inspection needed to certify shutdown. The recorder retained ownership as
intended. Run the command outside that restriction to complete the check.

Full Gradle checks/packaging remain unverified here because the managed sandbox
cannot write the Gradle wrapper/cache lock under `~/.gradle`. Run from
`integrations/jetbrains` on the host:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Real Codex acceptance and manual IDE verification of saved preferences, the
scrolled settings panel and native authentication handoff remain host checks.
