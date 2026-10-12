# B01: architecture compatibility experiments

Branch deliverable: `chore/architecture-compatibility-spikes`.
Recorded 2026-10-12. Status: **all six real-adapter probes, native tests/build and manual diff/merge/project-close cleanup passed; plugin-unload cleanup and adapter release provenance remain pending before G0**.

These are development probes, separate from the production engine. They exercise
the selected SDK and native APIs before Flow implements sessions and persistence.
This evidence update does not commit the implementation plan.

The [current fixture evidence](b01-fixture-evidence.json) contains 17 checked
experiments, checkpoint contents and SHA-256 fingerprints of the probe sources.
The [earlier assistant-environment evidence](b01-real-evidence.json) records successful
initialization followed by an immediate `adapter_panic` failure and classified
adapter diagnostics. The probe now stops on Rust panic headers even when the
adapter process stays alive, instead of waiting for the request timeout.
The [host real-adapter evidence](b01-host-real-evidence.json) records the user's
later Terminal run: initialization, session creation and cancellation acknowledgement
pass. Edit, shell and background prompts fail with RPC code -32603 before any tool
call or completed turn is reported. Only the baseline checkpoint exists. The old
report discarded error details, so it cannot distinguish authentication, model,
HTTP or connection failures. It predates the new `errorCategory` diagnostic field;
its original source fingerprints are retained. Later diagnostics and a model
override resolved the prompt failure as described below.
The subsequent [successful host edit evidence](b01-host-edit-evidence.json) uses
the same adapter with the per-run override `-c 'model="gpt-6-astra"'`. Both turns
complete, one-time permissions are granted and all three exact checkpoints are
preserved. The earlier inherited `gpt-6.1-sol` request returned HTTP 400 with
`not supported` and `chatgpt` signals. Changing the model resolved this edit
failure on the tested host; it does not establish general model availability or
compatibility across accounts. Global Codex configuration was not changed.
The [complete successful host suite](b01-host-real-astra-evidence.json), recorded
at `2026-10-12T09:55:10.682915+00:00`, passes initialization, session creation,
edit, shell, background and cancellation. Its source fingerprints were verified
against the B01 probe, runner, fixture and Cargo.lock when saved. B02 subsequently
adds an engine test dependency to Cargo.lock; the B01 report remains immutable.
The user's command supplied
`-c 'model="gpt-6-astra"'`; the runner does not itself record adapter arguments.
Both edit and shell cases retain exact checkpoints 0, 1 and 2 and two one-time
permission grants. Background retains checkpoints 0 and 2 with
`changedAfterTurn=false`: final contents are proven, intermediate and post-turn
writes are not. Cancellation proves acknowledgement, not termination of an
already-running shell. Existing credentials successfully support real inference;
a fresh browser/device login has not been exercised.
The [earlier evidence bundle](compatibility-evidence.json) remains an archive of
the first nine reports; it is not the current implementation's acceptance result.

The [B01 architecture decision](adr/0001-b01-agent-and-host-boundaries.md) records
the target pins, SDK extension inventory, process-launch decision, supported file
shapes and explicit continue/narrow/stop decisions.

## Results

| Experiment | Result | What it establishes |
| --- | --- | --- |
| ACP SDK client/process on Tokio | Pass with deterministic subprocess fixture | Stable v1 initialization, session creation and streamed notifications work without an I/O compatibility bridge |
| Permissions | Pass with fixture | Default cancellation; explicit opt-in selects `allow_once`, even when `allow_always` is offered first |
| Authentication | Pass with fixture | Explicit advertised method, rejected authentication and auth-required session behavior; no automatic browser login |
| Two consecutive edits | Pass with fixture | Three exact file snapshots and BLAKE3 fingerprints remain available after later edits |
| Incorrect content and denied edits | Expected failures | A successful prompt response alone cannot make the checkpoint experiment pass |
| Session cancellation | Pass with fixture | `session/cancel` reaches the agent and the prompt returns `cancelled` |
| Subprocess lifecycle | Pass with fixture | Direct child and a spawned descendant exit after success, error, cancellation, adapter crash and timeout on macOS |
| Working directory and environment | Pass with fixture | SDK-owned Unix launcher uses `exec`, preserves parent cwd, sets session cwd and removes an unapproved sentinel environment variable |
| Shell and overlapping writes | Pass with fixture | Actual shell writes produce historical checkpoints; overlapping writers can change the file after a completed turn |
| Invalid protocol, ignored cancellation and stderr | Pass with fixture | Wrong versions fail, ignored cancellation remains bounded, and a sentinel secret never enters the report |
| Worker panic without process exit | Pass with fixture | Reports `adapter_panic` before the timeout and cleans up the adapter and descendant; ordinary log messages mentioning a panic do not terminate the probe |
| Installed Codex adapter initialization | Pass | `codex-acp` reports version `0.5.2`, negotiates v1 and advertises authentication methods |
| Installed Codex adapter session creation | Pass on user's host | The assistant-environment panic is absent in the user's Terminal run |
| Real cancellation | Acknowledgement passed | Cancellation returns within approximately one second; no running shell tool was observed, so shell termination is not proven |
| Real two-edit prompt | Pass with gpt-6-astra override | Two completed turns, one-time permissions and exact checkpoints 0, 1 and 2 verified from host files |
| Real shell prompt | Pass with gpt-6-astra override | Two turns, one-time permissions and exact checkpoints 0, 1 and 2 |
| Real background prompt | Pass within the probe's observation envelope | Final checkpoint 2 captured; no intermediate checkpoint 1 or post-turn change observed |
| Existing agent authentication | Pass on user's host | Real inference and writing succeed; fresh login remains fixture-only coverage |
| Pinned Gradle/plugin build | Pass on user's host, configuration warning retained | Plugin ZIP produced; verification reports the intentional until-build=253.* limit. No cross-version Plugin Verifier pass is claimed |
| Native historical diff and merge assertions | Pass | Test XML confirms three tests, zero failures/errors/skips |
| Native UI and disposal | Manual diff, resolved/cancelled merge and project-close cleanup passed | Screenshots confirm diff and merge behavior; IDE log confirms cleanup before the fixture's natural exit. Plugin-unload cleanup remains pending |

### Native host evidence, 2026-10-12

The user launched IntelliJ IDEA 2025.3.6.1 with Flow and the compatibility flag.
Screenshots show both historical comparisons (0 → 1 and 1 → 2), resolved merge
text returned to Flow, and cancellation with no accepted result. These demonstrate
native fixture behavior, not production agent history or main-project application.

The local `integrations/jetbrains/.intellijPlatform/sandbox/flow-jetbrains/IU-2025.3.6.1/log_runIde/idea.log`
records IDEA **IU-253.33813.55**, running on JetBrains Runtime
**21.0.11+10-b1163.116**, aarch64. The build JDK reported by the user is
**Temurin 21.0.12.1+1-LTS**. Selected lifecycle evidence:

```text
2026-10-12 12:28:33,505 Flow lifecycle fixture started: pid=50559
2026-10-12 12:29:20,709 Project ... is disposed, removing entity
2026-10-12 12:29:20,714 Flow lifecycle fixture stopped: pid=50559, alive=false
```

Cleanup occurred after 47.209 seconds, before the 60-second natural exit.
Plugin-unload cleanup is a separate pending check. The installed JDK and IDE
remove the earlier missing-installation blocker; the assistant's socket restriction
persists. A subsequent host build independently produced the native test XML:
`build/test-results/test/TEST-dev.flow.spikes.NativeCompatibilityTest.xml`,
timestamp `2026-10-12T09:39:27.458Z`, three tests with zero failures, errors or skips.
All artifact paths here are relative to `integrations/jetbrains`.

| Artifact | SHA-256 |
| --- | --- |
| Native test XML | `8fc1855635233e5a696323d962d0d4889a51b5fd57507902780774fdc658e05b` |
| `build/distributions/flow-jetbrains-0.1.0.zip` | `3e1eecb52dd1b96c53ee7df2a5da423f2a0d215bd50311d2473e8703c5209af9` |

The user reported `BUILD SUCCESSFUL` for tests, `buildPlugin` and
`verifyPluginProjectConfiguration`. The latter warns about `until-build`; retain
the deliberately narrow `253.*` range until later IDE builds are validated.
Building the ZIP does not establish fresh-install acceptance or Plugin Verifier
compatibility across other builds.

The real suite establishes writing and existing-authentication behavior on the
tested macOS arm64 host. Neither it nor the native fixtures establish exact tool
attribution, durable production history or complete workspace capture. Release
support remains subject to the later packaged acceptance milestone.

## Run the ACP experiments

From the repository root, with Rust, Python 3 and system Git available:

```sh
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b01-fixtures.json
```

This builds the Cargo example and runs 17 experiments against a local fixture.
It requires no model credentials. Use Python 3.11+ for the evidence runner.
The launcher and lifecycle checks target POSIX, initially
macOS. Every invocation prints a path to `report.json` in a retained temporary
directory. Edit experiments also produce `checkpoint-0.txt` through
`checkpoint-2.txt`. Negative cases intentionally return failure; the runner checks
that failure is detected and that the fixture processes exit.

Use an explicitly selected adapter executable for real-agent investigation:

```sh
cargo run -p runtime --example acp_probe --locked -- inspect -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- session -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- edit -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- shell -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- background -- /absolute/path/to/codex-acp
cargo run -p runtime --example acp_probe --locked -- cancel -- /absolute/path/to/codex-acp
```

`inspect` only initializes the connection. `session` additionally creates a session
without sending a prompt. `auth --auth-method ID` exercises only explicit
authentication. `edit` requests two sequential changes to `probe.txt`, while
`shell` requires shell commands. `background` starts two overlapping shell writers
and samples the known file for three seconds after the turn, recording whether
later writes were observed. An adapter that waits for those writers before ending
the turn can report `changedAfterTurn=false`; this is an observation, not proof
that all background effects are absent. `cancel` requests a waiting shell command
and then sends `session/cancel`. Each invocation creates a new disposable Git repository, never
using Flow's source checkout as the requested workspace. The process timeout is
120 seconds by default; `--timeout-seconds N` accepts 1–120.

Permission requests are cancelled by default. Passing `--allow-once` before `--`
automatically approves each requested one-time permission for that experiment;
it never selects an always-allow option. This option is explicit because agent
commands can have effects outside the disposable directory. It does not enable
an OS sandbox. The adapter retains its own permissions and existing configuration.

The complete real-adapter runner checks the executable's SHA-256 before launch,
retains partial evidence on failure and skips dependent cases if initialization
or session creation fails:

```sh
python3 scripts/spikes/check_acp_probe.py \
  --adapter /absolute/path/to/codex-acp \
  --sha256 027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524 \
  --adapter-arg=-c \
  --adapter-arg='model="gpt-6-astra"' \
  --allow-once --timeout-seconds 120 --output /tmp/flow-b01-real-astra.json
```

That digest identifies the observed 0.5.2 sample only. A different verified release
needs its own expected digest. Adapter flags can be supplied with
`--adapter-arg=VALUE`. No installed agent, configuration or authentication files
are modified by the runner itself.

The probe uses existing agent authentication unless `--auth-method ID` is supplied.
Only an ID advertised by initialization is accepted. The adapter performs the
authentication; Flow does not collect tokens. Fresh browser/device login has
not been validated here; model calls using existing credentials
have passed. Executable arguments follow the
executable after `--`; file arguments should be absolute because the adapter
starts in the scratch directory.

Reports retain event categories, selected capability booleans, hashed tool-call
IDs, permission decisions and checkpoint fingerprints. They omit prompt/response bodies, tool payloads, raw
adapter stderr and detailed remote errors. Agent identity and advertised auth IDs
are retained; review these adapter-supplied strings before sharing reports. The
trace is bounded to 1,024 events and reports possible truncation. Stderr is reduced
to fixed diagnostic categories and panic/null-object booleans, never raw text. Keep the
checkpoint files with their report when preserving evidence; remove the printed
temporary directory when it is no longer needed.

Failed reports now include `errorCategory`, derived from both the RPC error message
and data using the same fixed categories as stderr. It is a diagnostic hint, not
a confirmed root cause. Raw error data, credentials, URLs and response bodies are
not persisted. The fixture suite checks classification of a nested RPC error and
redaction of a sentinel secret even when no stderr is emitted.

The subsequent host edit probe `flow-acp-probe-QKdm70` narrowed the failure to
`http_error`, still without a reported tool call. This is not evidence of a bad
login or unavailable model. Reports now also include `httpError.status` and fixed
`httpError.signals`, printed on failure, so a retry can distinguish the HTTP status
and recognized rejection terms without retaining arbitrary response text. The
previous reports cannot be retroactively enriched because raw errors were omitted.
HTTP statuses alone can have multiple causes; interpret them alongside the response
signals using the [official OpenAI error guidance](https://developers.openai.com/api/docs/guides/error-codes).

## Run the native IDE experiments

With JDK 21 and network access for the pinned build dependencies:

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew test --tests dev.flow.spikes.NativeCompatibilityTest
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

The three automated platform tests assert exact historical inputs, resolved merge
content and no accepted content on cancellation. They do not substitute for visual
review, EDT responsiveness or actual project-close/plugin-unload observations.
Keep `build/test-results/test/` with the manual results and exact IDE/JDK versions.

A supplementary source compilation succeeded against installed RustRover
RR-262.10968.211 using cached Kotlin 2.1.20 with metadata-version checking disabled.
This checks basic API call shapes only. It is **not** pinned Kotlin 2.2.20/IDEA 253
compilation, Gradle configuration validation, Plugin Verifier evidence, or a UI
test. This was an earlier bootstrap check, superseded by the pinned native build
and JDK 21 evidence above.

## Decisions and remaining risks

- **One agent process owner:** use `AcpAgent` from SDK 3.3.0. Its process transport
  works under Tokio in these experiments and cleans up the fixture process group.
  Flow must not launch the same agent with a second process manager.
- **Working directory has a tested macOS path:** SDK 3.3.0 lacks child cwd and
  environment-clearing controls. The new Unix `exec` launcher sets both without
  changing parent cwd or creating a second agent process owner. Cleanup and
  environment exclusion pass in fixtures. B08 can adopt this bounded approach
  for the selected platform; additional OS support remains a separate task.
- **Capture confidence:** these are completed-turn checkpoints of one known UTF-8
  file. They do not prove exact tool boundaries, complete-workspace reconciliation,
  detection of transient writes, or quiescence after background commands. The
  fixture background experiment demonstrates why turn completion is insufficient;
  the real background run observed only the final state at turn completion.
  The files are retained evidence, not the durable immutable history store from
  B04/B09. Limit initial claims accordingly.
- **Native APIs:** use the platform's immutable diff inputs, merge callback and
  injected project-service coroutine scope. Diff/merge and project-close behavior
  are proven on the pinned target; plugin-unload cleanup remains to be observed.
- **Adapter provenance:** the existing local Zed-managed binary reports `0.5.2`;
  SHA-256 is `027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524`.
  It is an observed compatibility sample, not yet the approved release adapter.
  Verify its upstream release provenance or select a maintained release before G0.
- **SDK extensions:** the bounded inventory is complete in ADR 0001. Optional
  package versions differ, and some matching release manifests could not be
  verified. Exclude those packages rather than assume compatibility. Keep direct
  v1 Client for V1, stable MCP transport for B17, and both unstable features off.

Remaining acceptance work is adapter release provenance and plugin-unload cleanup
on the pinned IDE. The tested workflow uses an already-authenticated adapter;
fresh browser/device login remains owned by the external agent and is not claimed
as a completed Flow experiment. Active-shell cancellation and post-turn background
capture remain limitations of the real evidence, with deterministic fixture
coverage and production follow-up in B08/B09. Time-box each remaining investigation
to the plan's two engineering days before a scope decision. G0 remains open.

## B01 acceptance checklist

| Plan requirement | Implemented or investigated | Required before closure |
| --- | --- | --- |
| Version pins, SDK/Tokio example, lifecycle and licenses | Locked Rust graph, SDK revision, native pins, exact JDK/IDE builds and adapter sample digest recorded | Verify adapter release provenance |
| Proxy/Conductor/cookbook/RMCP/polyfill/test inventory | ADR records verified surfaces, version mismatches and explicit exclusions | No new B01 production dependency; matching optional manifests are rechecked only if later adopted |
| One IDE/OS target and launch procedure | macOS arm64 / IDEA 2025.3.6.1 launched; repeatable commands documented | Complete for the selected target |
| Historical diff, merge-result retrieval, disposal and background process behavior | Three platform tests, manual diff/merge and project-close cleanup passed | Observe plugin-unload cleanup |
| Codex auth, prompts, permissions, edits, shell, overlapping writes and cancellation | 17 fixture cases and six real probes pass; existing authentication and real permission grants verified | Complete within the stated capture and cancellation limits; no fresh-login claim |
| Time box, capture envelope and risk decision | ADR records continue/narrow/stop decisions and two-day maximum per remaining investigation | Close provenance and unload checks before G0 |

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
