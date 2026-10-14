# B06: native JetBrains engine bridge

Branch: `feat/jetbrains-engine-bridge`. Depends on B03–B05.

This document records the B06 bridge milestone. B08 extends it with
[ACP sessions](acp-agent-sessions.md), a Session tab, a bounded command queue,
additional native-auth environment forwarding and agent teardown before the
engine acknowledges shutdown.

## Implemented behavior

The Flow tool window gets a project-level `EngineService`. Its injected coroutine
scope owns one `EngineController`; repeated opens or health clicks cannot create
parallel engine instances. Construction does not launch anything. Creating the
tool-window content starts the service lazily. All process/file I/O runs off the
EDT, and Swing updates run on the EDT with a conflated state stream limited to ten
refreshes per second. Tool-window content disposal cancels its observer; closing
the project or unloading the plugin cancels the service and terminates its engine.
This follows the platform's documented [service coroutine scopes](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html).

The native ActionSystem toolbar exposes **Check Engine Health** and **Restart
Engine**, including during IDE indexing. Health is displayed only after a matching
project/epoch response. Disconnects clear prior success and counts and show an
unknown request outcome. No agent session, mutation retry or commit action exists
in B06.

`EngineConnection` uses the existing explicit DTOs and `RequestTracker` with bounded
UTF-8 frames, eight-entry pipe queues, strict JSON, epoch validation and deadlines.
Requests are serialized by the controller. Stderr is drained into a fixed buffer
and discarded, avoiding unbounded output and sensitive error text in the UI.
Timeouts enqueue cancellation best-effort, then the supervisor closes the connection.
Shutdown owns stream readers/writers and waits for process exit; observed descendants
are terminated too. B06 itself launches no agents; B08 delegates adapter process
groups to the ACP SDK. This is not an
OS sandbox or a general guarantee against arbitrary detached processes.

After a failure, reconnect waits 250, 500 and 1,000 ms, then stops after three
retries. Explicit restart or retry begins a new budget. Planned rotation before
the negotiated connection-request limit does not consume the failure budget.
The old process is closed before another starts. A new connection gets a new
epoch, handshake and authoritative snapshot.

## Rust state integration

`flow-engine --project-root PATH` opens B04's canonical project history using its
existing platform data directory. On macOS this is under
`~/Library/Application Support/Flow/project-<hash>/`. `--data-root PATH` selects an
existing external directory for tests; the history store validates containment
and owns its writer lock. Bare `flow-engine` retains B03's side-effect-free mode.
Opening a second engine against the same history is refused by B04's lock.

Project-bound initialization advertises persistent events. `state.snapshot` returns
the consistent summary plus cursor; `events.replay` provides pages of at most 100
events after that cursor. These reads run through blocking workers, never on the
RPC executor threads. They honor cancellation/deadlines and never mutate history.
Foreign/out-of-range cursors return invalid-params so the host can resnapshot.

The controller polls replay once per second. It validates store IDs, strictly
contiguous decimal sequences and page boundaries without converting them to floating
point. The current UI displays counts only: after observing events it fetches a
fresh consistent summary and advances to that summary's high-water mark. That
snapshot covers any remaining pages, so Kotlin does not retain or duplicate Rust's
event history. Unknown event kinds are harmless. Future operation lists will need
their own paginated queries; this milestone does not fabricate them from events.

## Executable resolution and development

1. An explicit absolute `-Dflow.engine.path=/path/to/flow-engine` selects a development
   executable. Paths are passed as arguments, never through a shell. No repository
   configuration or PATH search selects the executable.
2. Otherwise resolve `bin/flow-engine` relative to the installed plugin and require
   its adjacent `flow-engine.sha256` to match. Cross-platform binary bundling and
   signing remain the packaging milestone; B06's ZIP does not bundle an engine.

`./gradlew runIde` builds the Rust engine and passes the absolute debug executable
path automatically. The engine runs in the canonical open project directory with
an explicit environment allowlist. B06 needed only HOME, PATH, TMPDIR, LANG and
LC_ALL; B08 additionally forwards native-auth, proxy, certificate and existing
Codex sandbox variables. Engine health checks require no model credentials.

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew ktlintFormat check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
./gradlew runIde
```

## Validation and host acceptance

Automated coverage includes real Rust snapshot/replay/restart, two independent
project processes, repeated-start deduplication, health, manual restart, crash
reconnect, service-scope cancellation, bounded retry exhaustion, stale replies,
notification floods, invalid cursors, replay gaps and oversized/invalid UTF-8.
The process factory is injected into the controller for fake-engine tests; no
separate plugin test mode or paid agent is required.

Manual host checks still required for IDE-specific lifecycle behavior:

1. Open two local projects, open Flow in each, and check health. Each has a distinct
   PID. Confirm opening a tool window does not block editing/indexing.
2. Close/reopen Flow's tool window. Confirm it reuses the existing PID. Restart
   one engine and confirm its old PID exits while the other project remains live.
3. Terminate one engine externally. Confirm bounded reconnect, a new PID, and no
   stale successful health message. Retry after an unavailable executable.
4. Close a project, then unload the plugin in the other. Confirm both engine PIDs
   exit and no background readers or repeated reconnects remain.

Local validation on 2026-10-14:

- Rust workspace: **30 tests passed**, including the new durable RPC read test.
- Rust formatting, Clippy with warnings denied, and whitespace checks: passed.
- Kotlin 2.2.20 compilation against the pinned IDEA libraries: passed.
- Standalone JUnit: **nine protocol/bridge tests passed**, including real engine
  processes and five B06 tests. Kotlin formatting checks also passed.
- Gradle could not acquire its wrapper lock because this environment denies writes
  to `~/.gradle`. Full Gradle packaging, native platform tests, Plugin Verifier and
  the manual IDE checks above remain host validation steps.

The lifecycle tests exposed restricted-host process enumeration as a cleanup
failure. Enumeration is now best-effort: inability to list descendants never
prevents terminating and joining the directly owned engine. The corrected tests
verify that all launched engine handles have exited.

The full strict Gradle and Plugin Verifier run is a host acceptance gate; standalone
Kotlin compilation/tests cannot prove sandbox IDE UI or plugin-unload behavior.
