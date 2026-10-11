# B01: architecture compatibility experiments

Branch deliverable: `chore/architecture-compatibility-spikes`.
Recorded 2026-10-12. Status: **in progress; G0 has not passed**.

These are development probes, separate from the production engine. They exercise
the selected SDK and native APIs before Flow implements sessions and persistence.
The implementation plan remains uncommitted.

The [retained evidence bundle](compatibility-evidence.json) contains nine redacted
reports and the fixture checkpoint contents: seven deterministic cases and two
real-adapter attempts. Earlier real-adapter reports predate the configurable
timeout field; those attempts used the fixed 120-second limit.

## Results

| Experiment | Result | What it establishes |
| --- | --- | --- |
| ACP SDK client/process on Tokio | Pass with deterministic subprocess fixture | Stable v1 initialization, session creation and streamed notifications work without an I/O compatibility bridge |
| Permissions | Pass with fixture | Default cancellation; explicit opt-in selects `allow_once`, even when `allow_always` is offered first |
| Two consecutive edits | Pass with fixture | Three exact file snapshots and BLAKE3 fingerprints remain available after later edits |
| Incorrect content and denied edits | Expected failures | A successful prompt response alone cannot make the checkpoint experiment pass |
| Session cancellation | Pass with fixture | `session/cancel` reaches the agent and the prompt returns `cancelled` |
| Subprocess lifecycle | Pass with fixture | Direct child and a spawned descendant exit after success, error, cancellation, adapter crash and timeout on macOS |
| Installed Codex adapter initialization | Pass | `codex-acp` reports version `0.5.2`, negotiates v1 and advertises authentication methods |
| Installed Codex adapter session creation | Timed out | Initialization passes, but `session/new` does not respond within the 120-second limit in this environment; cause remains unproven |
| Pinned Gradle/plugin build | Blocked by environment | Gradle cannot create its lock-coordination socket in the sandbox; a writable temporary Gradle home does not resolve that restriction |
| Native historical diff, merge and disposal | Implemented; manual validation pending | Development-only actions are available once the pinned plugin can be built and launched |

Fixture success does not establish real-agent authentication, writing, cancellation,
or precise tool attribution. No real edit was observed during this run. The
development target is macOS arm64 and IntelliJ IDEA 2025.3.6.1; release support
remains conditional on actually building and exercising that combination.

## Run the ACP experiments

From the repository root, with Rust, Python 3 and system Git available:

```sh
python3 scripts/spikes/check_acp_probe.py
```

This builds the Cargo example and runs seven experiments against a local fixture.
It requires no model credentials. The lifecycle checks target POSIX, initially
macOS. Every invocation prints a path to `report.json` in a retained temporary
directory. Edit experiments also produce `checkpoint-0.txt` through
`checkpoint-2.txt`. Negative cases intentionally return failure; the runner checks
that failure is detected and that the fixture processes exit.

Use an explicitly selected adapter executable for real-agent investigation:

```sh
cargo run -p runtime --example acp_probe --locked -- inspect -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- edit -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- cancel -- /absolute/path/to/codex-acp
```

`inspect` only initializes the connection. `edit` requests two small sequential
changes to `probe.txt`; `cancel` requests a waiting shell command and then sends
`session/cancel`. Each invocation creates a new disposable Git repository, never
using Flow's source checkout as the requested workspace. The process timeout is
120 seconds by default; `--timeout-seconds N` accepts 1–120.

Permission requests are cancelled by default. Passing `--allow-once` before `--`
automatically approves each requested one-time permission for that experiment;
it never selects an always-allow option. This option is explicit because agent
commands can have effects outside the disposable directory. It does not enable
an OS sandbox. The adapter retains its own permissions and existing configuration.

The probe uses existing agent authentication unless `--auth-method ID` is supplied.
Only an ID advertised by initialization is accepted. The adapter performs the
authentication; Flow does not collect tokens. Browser/device authentication and
real model calls have not been validated here. Executable arguments follow the
executable after `--`; file arguments should be absolute because the adapter
starts in the scratch directory.

Reports retain event categories, capability identity, permission decisions and
checkpoint fingerprints. They omit prompt/response bodies, tool payloads, raw
adapter stderr and detailed remote errors. Agent identity and advertised auth IDs
are retained; review these adapter-supplied strings before sharing reports. The
trace is bounded to 1,024 events and reports possible truncation. Keep the
checkpoint files with their report when preserving evidence; remove the printed
temporary directory when it is no longer needed.

## Run the native IDE experiments

With JDK 21 and network access for the pinned build dependencies:

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew buildPlugin verifyPluginProjectConfiguration
./gradlew runIde -PflowCompatibilitySpikes=true
```

Open a project in the development IDE. Use **Find Action** to run:

1. **Flow Compatibility: Historical Diffs** — opens comparisons for `0 → 1`
   and `1 → 2` from immutable in-memory text. Verify the first comparison still
   shows the first edit after viewing the second.
2. **Flow Compatibility: Native Merge** — opens conflicting developer/base/agent
   strings. Resolve the conflict and apply; the callback displays the resolved
   document. Repeat with Cancel and verify no result is accepted. No project
   files are written.
3. **Flow Compatibility: Process Lifecycle** — starts `/bin/sleep 60` on a
   project-service coroutine. Note the displayed PID, close the dialog, then
   close the project before the process's natural exit. Verify the process has
   stopped and `idea.log` records `alive=false`. Repeat with plugin unloading.
   Confirm the IDE remains responsive while the process runs.

These actions are hidden unless the development JVM property is enabled. The
lifecycle probe owns only its fixture process; production engine startup remains
B06. Capture IDE build, OS version, screenshots or observations, merge output,
and lifecycle log lines when recording a pass.

A supplementary source compilation succeeded against installed RustRover
RR-262.10968.211 using cached Kotlin 2.1.20 with metadata-version checking disabled.
This checks basic API call shapes only. It is **not** pinned Kotlin 2.2.20/IDEA 253
compilation, Gradle configuration validation, Plugin Verifier evidence, or a UI
test. The installed runtime is JBR 25; no registered JDK 21 was found.

## Decisions and remaining risks

- **One agent process owner:** use `AcpAgent` from SDK 3.3.0. Its process transport
  works under Tokio in these experiments and cleans up the fixture process group.
  Flow must not launch the same agent with a second process manager.
- **Working directory is an unresolved production requirement:** SDK 3.3.0's
  `AcpAgentConfig` exposes command, arguments and environment overrides, but no
  child working directory or environment clearing. The standalone probe changes
  its own cwd once, before starting Tokio, and also sets `session/new.cwd`.
  A project engine must not change global cwd per session. Before B08, select a
  released SDK with these controls, contribute support, or prove a small portable
  launcher that preserves process-group cleanup. Session cwd alone is not proof
  of process cwd or environment isolation.
- **Capture confidence:** these are completed-turn checkpoints of one known UTF-8
  file. They do not prove exact tool boundaries, complete-workspace reconciliation,
  detection of transient writes, or quiescence after background commands.
  The files are retained evidence, not the durable immutable history store from
  B04/B09. Limit initial claims accordingly.
- **Native APIs:** use the platform's immutable diff inputs, merge callback and
  injected project-service coroutine scope. Finish the manual checks before
  considering those APIs proven for the pinned target.
- **Adapter provenance:** the existing local Zed-managed binary reports `0.5.2`;
  SHA-256 is `027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524`.
  It is an observed compatibility sample, not yet the approved release adapter.
  Verify its upstream release provenance or select a maintained release before G0.
- **SDK extensions:** `Proxy` and `Conductor` are exported by the locked SDK;
  native MCP-over-ACP is behind `unstable_mcp_over_acp`, and protocol v2 is separately
  gated. Neither feature is enabled. Keep the direct v1 client for V1. Cookbook,
  separate test packages, RMCP bridges and polyfills still need a bounded released
  package inventory; they do not add a production dependency here.

Continue the bounded B01 investigation: resolve the real `session/new` failure,
verify agent-managed authentication and cancellation, exercise real shell and
overlapping writes, and run the native checks with the pinned build. Time-box each
remaining spike to the plan's two engineering days before recording a scope
decision. Do not mark G0 complete based on the fixture alone.

## Dependency evidence

The workspace lockfile is the exact Rust dependency record. No new production
dependency was introduced for these probes; the runtime example reuses existing
workspace libraries as dev dependencies. Relevant versions and license metadata
were read from the locked macOS dependency graph:

| Dependency | Version | Declared license |
| --- | --- | --- |
| ACP SDK / schema | 3.3.0 / 1.11.0 | Apache-2.0 |
| Tokio / tokio-util | 1.53.2 / 0.7.20 | MIT |
| serde / serde_json | 1.0.229 / 1.0.154 | MIT OR Apache-2.0 |
| schemars | 1.2.2; transitive 0.9.0 | MIT |
| rusqlite | 0.40.2 | MIT |
| blake3 | 1.8.7 | CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception |
| zstd | 0.13.3 | MIT |
| ignore | 0.4.33 | Unlicense OR MIT |
| tempfile | 3.27.0 | MIT OR Apache-2.0 |
| tracing / tracing-subscriber | 0.1.44 / 0.3.23 | MIT |

The ACP crate declares MSRV 1.88; Flow pins Rust 1.99.0. These declarations are an
inventory, not a complete release notice audit of transitive or native components.

Reference surfaces: [ACP SDK 3.3.0](https://docs.rs/agent-client-protocol/3.3.0/agent_client_protocol/),
[ACP process implementation](https://docs.rs/crate/agent-client-protocol/3.3.0/source/src/acp_agent.rs),
[maintained Codex adapter](https://github.com/agentclientprotocol/codex-acp),
[JetBrains merge request API](https://github.com/JetBrains/intellij-community/blob/master/platform/diff-api/src/com/intellij/diff/DiffRequestFactory.java),
and [JetBrains service coroutine scopes](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html).
