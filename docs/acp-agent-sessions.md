# B08 — ACP agent sessions

Branch: `feat/acp-sdk-integration`. The pinned ACP Rust SDK is Flow's client and
process integration. Flow adds a B07 workspace lease, session/turn persistence,
permission decisions, a bounded activity projection and native Swing controls.

These are development integration controls. Product writing-session readiness
still depends on B09 verified recording, B25 recovery and the B10 real-adapter
acceptance gate. Retained worktree files are the available result at this stage;
Flow cannot yet provide historical edit review or apply them to the main project.

## Run a session

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew runIde
```

Open a local Git repository with an initial commit in the development IDE, then
open **Flow → Session**. The repository must meet the B07 clean-baseline envelope.

1. Enter the absolute path of an installed, trusted ACP adapter executable.
   Flow does not download adapters or execute repository-provided launch settings.
   Codex ACP is the first validated adapter family; a normal `codex` executable
   is not automatically an ACP adapter.
2. Enter adapter arguments as a JSON array, or leave `[]`. For the adapter/model
   combination already exercised in this project's B01 host evidence, use
   `["-c", "model=\"gpt-6-astra\""]`. Choose a model supported by your own login.
3. Leave authentication blank to use the adapter's existing native login. An
   explicit advertised ACP authentication-method ID can be supplied when needed.
   Credentials are neither entered into Flow's prompt nor stored in session records.
4. Click **Use current commit**, review its full commit ID, and enter the task.
   **Start Codex session** creates a detached worktree at that commit. Staged,
   unstaged, untracked and unsaved editor changes are excluded from this baseline.
5. Review activity and any permission request. Flow offers only the adapter's
   one-time allow/reject options and **Deny**. It never silently grants persistent
   approval. The adapter retains its own authentication, tools and policy.
6. When ready, **Send follow-up** reuses the same live agent session/worktree.
   **Cancel turn** asks ACP to cancel and waits up to five seconds for acknowledgement.
   Failure to acknowledge ends the connection and records failure. **Close session**
   ends the connection, stops the SDK-owned process group and retains the worktree.

The native panel shows the last 100 activity items and pending approvals from Rust.
It is a projection, not a second history database. Configuration fields currently
belong to the open panel; reopen/restart does not silently recreate an agent.
The Engine tab retains health/counts and restart controls. Recorded operation
counts remain zero for B08 agent edits: B09 will verify and record filesystem
changes. Do not use B08 tool notifications as proof of an atomic file delta.

If **Use current commit** cannot fill the baseline field, run `git rev-parse HEAD`
in the open project's terminal. A newly initialized repository needs an initial
commit; review and commit the files you want in the baseline, then click the button
again. Open the repository root rather than a subdirectory. The button displays
lookup progress and errors, and reselects the current hash even if you edited or
cleared the field. Starting also requires a nonempty task in the prompt box.

## Runtime and persistence

- One writing session per project. `PreparedWorktree` stays owned across setup,
  every turn, cancellation and SDK teardown. Main project files and index are
  untouched. A worktree shares Git metadata and is not a security sandbox.
- The SDK launches the same engine executable in a private `--agent-launch`
  mode. Unix `exec` supplies the owned cwd and an environment allowlist while
  preserving SDK process-group ownership. No shell interpolation or global cwd
  changes occur. Native auth/proxy/certificate variables and existing Codex sandbox
  flags are forwarded; arbitrary IDE environment variables are not.
- Initialize uses stable ACP v1. No Flow filesystem, terminal, MCP, proxy or
  Conductor capabilities are advertised. Unsupported callbacks fail through the
  SDK. The adapter uses its own tools. Native upstream session/tool IDs, agent
  metadata, authentication-method IDs and declared capabilities are retained.
- Session creation, upstream binding, turns, normalized activity and terminal
  statuses are persisted through the existing SQLite writer. Message/tool events
  are independent from immutable filesystem operations. `workspace.changeReported`
  explicitly labels agent-supplied diff content as unverified.
- Raw adapter stderr and ACP errors are not returned or logged by Flow. Activity
  text is bounded, with visible truncation markers; session events may contain
  user prompts and agent output and use the history store's private data directory.
- Permission IDs are random, session-bound and connection-epoch checked at IPC.
  Unknown, stale, duplicate and unoffered decisions fail. Up to eight permissions
  can wait; each expires after five minutes. Cancellation/closure invalidates all
  pending decisions. A restored history event cannot recreate an approval.
- Setup is limited to 30 seconds, turns to 30 minutes, and a live session to 8,192
  activity events. Prompts are limited to 32 KiB. Output/projection bounds prevent
  unlimited Swing queues; overflow fails the session rather than granting access.
- Engine shutdown and IPC EOF join session teardown before normal exit; the shutdown
  response is sent after teardown. Startup marks persisted live sessions/turns
  interrupted. It never resumes an upstream conversation or replays a prompt.
  B07 stale workspace ownership still requires explicit recovery.

The SDK kills its owned Unix process group when the connection drops. Tests
verify a real fixture descendant stops writing its heartbeat on normal close,
permission-pending shutdown and cancellation timeout. This does not prove cleanup
after an OS-level `SIGKILL` of the engine, or of an agent deliberately escaping
its process group. Such hard-crash recovery and additional OS guarantees remain
release/lifecycle work; inspect surviving processes before stale-lock recovery.

## IPC additions

All methods require the current engine epoch and project-bound startup. Unix
engines advertise `agentSessions`; bare protocol/test launches do not.

| Method | Meaning |
| --- | --- |
| `sessions.baseline` | Read and validate the current repository's full commit ID |
| `sessions.start` | Accept executable, argv, full commit, prompt and optional auth method; return a new session ID immediately |
| `sessions.active` | Authoritative bounded live view, including workspace, adapter metadata, status and pending permissions |
| `sessions.prompt` | Accept a follow-up for the matching ready session |
| `sessions.cancel` | Request cancellation of the matching running turn |
| `sessions.close` | End the matching session and retain its worktree |
| `permissions.respond` | Resolve the exact session/request with an offered option ID, or null to deny |

Start/turn/cancel replies acknowledge admission, not successful completion. Poll
`sessions.active` and durable replay to observe outcomes. These commands do not
advertise mutation idempotency and must not be retried automatically after a lost
response. The Kotlin queue discards stale commands when a connection ends.
The lifetime IPC request budget is now 65,536 so normal session polling does not
rotate the engine during a 30-minute turn. Eventual connection rotation closes
the current agent; it does not pretend to resume it.

## Validation

```sh
cargo test --workspace --all-targets --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo fmt --all --check

cd integrations/jetbrains
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Offline integration tests launch the actual engine, its exec launcher and a
scripted ACP adapter through the real SDK. They cover streaming, offered-option
filtering, stale/duplicate decisions, native authentication handoff, follow-ups,
cancel acknowledgement/timeout, partial-write crashes, incompatible ACP versions,
redacted failures, durable records, unchanged main files/index and child cleanup.
History tests verify restart interruption without approval replay. Kotlin tests
exercise controller → engine → adapter → permission → follow-up-ready/close,
alongside the existing protocol, reconnect and disposal checks.

A real authenticated Codex run in the native IDE and full Gradle/plugin verification
remain host acceptance checks. Offline fixtures do not establish provider billing,
current model availability or native adapter login compatibility.

Local validation on 2026-10-14: all 44 Rust tests, Clippy with warnings denied,
Rust formatting, Kotlin compilation against the pinned IDEA libraries, Kotlin
lint and 11 standalone JUnit protocol/bridge tests passed. The full Gradle command
could not acquire its wrapper lock because this environment cannot write to
`~/.gradle`; standalone checks do not replace packaging or Plugin Verifier.
