# Flow

A native agent workbench for understanding, reviewing, and applying AI code changes.

Flow is being built to bring coding agents such as Codex and Claude Code into
JetBrains IDEs, with a Rust engine that preserves change history and keeps the
developer in control. Existing agents handle coding; Flow provides workspace
isolation, project context, and native review tools around them.

**Status: early development.** The repository currently contains the Rust
workspace, durable history storage, versioned stdio engine protocol, and a
native Swing tool window connected to a project-scoped Rust engine. Agent
execution and the review features below remain planned.

## Planned features

### Review every recorded change

Explore a session's operation history and open historical edits in JetBrains'
native diff viewer. Immutable before/after snapshots will preserve each recorded
change, even when an agent edits the same file again later.

History will reflect verified filesystem contents, with attribution to agent
operations when reliable. A recorded operation represents an observed change
between checkpoints; it does not promise to capture every transient write or to
be independently reversible.

### Keep agent work in its own workspace

Run each writing session in a separate Git worktree. The first release will start
from a clean commit baseline and support one active writing session per IDE
project, while retaining previous sessions for review.

Review the complete result before applying it to the main project. Planned
three-way comparison and native conflict resolution will account for edits made
since the session started. A worktree provides workspace separation, not a
security sandbox.

### Work inside the IDE

Use a native Kotlin/Swing tool window for prompts, streamed activity, permission
requests, and session navigation. Open diffs in the IDE's editor area and use
JetBrains' existing navigation, merge, and commit workflows.

Codex is the first planned agent integration through ACP; Claude Code support
will follow. Flow will preserve each adapter's capabilities and use the agent's
own authentication and permission mechanisms.

### Give agents useful project context

A planned Rust context engine will index the agent's worktree and expose symbol
search, code context, known references, and impact analysis through automatically
attached MCP tools. Retrieval will include source locations, freshness, and
confidence information.

The first context milestone targets Rust with Tree-sitter and SQLite search.
Additional languages and more precise semantic relationships will follow as
the retrieval approach is validated.

### Improve prompts and commit messages

Later productivity features will let developers review and edit a context-aware
prompt enhancement before sending it. Commit-message generation will use the
exact intended selection and populate the native commit editor for review.

The first commit integration will target staged Git changes. JetBrains partial
chunk and line selections require a separate compatibility milestone. Direct
model utilities may require their own provider credentials and billing.

## Roadmap

| Stage | Outcome |
| --- | --- |
| Foundation — in progress | Rust workspace, native plugin scaffold, and framework compatibility checks |
| First working slice | Start Codex in a clean worktree, record consecutive edits, and reopen each historical diff in JetBrains |
| First usable release | Review and safely apply a complete session result, resolve conflicts, recover interrupted sessions, and package one validated IDE/OS target |
| Code intelligence and agent expansion | Built-in Rust context through MCP, Claude Code integration, and explicit import of existing local changes |
| Productivity | Reviewed prompt enhancement and commit-message drafts from exact staged changes |
| Advanced review | Select logical change groups, check dependencies, and compose a candidate for validation before application |
| Broader support | Additional languages and platforms, concurrent sessions, partial commit selections, and future VS Code/Zed integrations |

Scope expands only after the preceding correctness and compatibility checks
pass. In particular, historical operation review comes before selective
application: keeping one change while rejecting a prerequisite can break code.

The [implementation plan](docs/native-agent-workbench-implementation-plan.md)
tracks feature branches, dependencies, validation gates, and release scope.

## Architecture

```text
JetBrains IDE · Kotlin/Swing
    Native tool window, editor context, diff/merge, commit UI
                         │
                Versioned JSON-RPC over stdio
                         │
Flow Engine · Rust
    Sessions · Worktrees · Immutable history · Review and application
    Planned context retrieval and model utilities
                         │
                 ACP agents + MCP tools
```

Rust owns durable state and reusable behavior. Kotlin owns the IDE experience.
That boundary is intended to let future editor clients reuse the same history
and orchestration engine.

| Area | Stack |
| --- | --- |
| Native UI | IntelliJ Platform SDK, Kotlin, Swing |
| Agent integration and async execution | ACP Rust SDK, Tokio |
| History and persistence | SQLite via rusqlite, BLAKE3, zstd |
| Repository operations | System Git |
| Application protocol | Serde, JSON Schema, framed JSON-RPC |
| Planned code intelligence | Tree-sitter, SQLite FTS5, RMCP |
| Planned model utilities | genai |

## Repository structure

| Path | Responsibility |
| --- | --- |
| `crates/protocol` | Typed Flow IPC contract, storage interfaces and JSON Schema export |
| `crates/runtime` | ACP SDK integration, session policy and Git worktrees |
| `crates/history` | SQLite and immutable BLAKE3/zstd history |
| `crates/engine` | Tokio executable and application wiring |
| `integrations/jetbrains` | IntelliJ Platform plugin and native Swing UI |
| `docs/` | Architecture, implementation phases, and validation plans |

Core versions/features live in the root Cargo workspace. The context crate,
Tree-sitter/RMCP/FTS5, and genai utilities are introduced in their planned later
milestones.

## Development

### Native engine bridge (B06)

Opening Flow starts one Rust engine for that project and shows its connection
status, PID and durable history counts. **Check Engine Health** requests a real
Rust response; **Restart Engine** closes the old process before reconnecting.
The project service handles bounded reconnects, snapshot/replay refresh, and
process cleanup on project close or plugin unload. Closing and reopening the
tool window reuses the same service.

```sh
cd integrations/jetbrains
./gradlew runIde
```

`runIde` builds and selects `target/debug/flow-engine` automatically. Open a local
project in the sandbox IDE, then **View → Tool Windows → Flow**. No coding agent
is started. The [bridge guide](docs/jetbrains-engine-bridge.md) covers lifecycle
checks, executable resolution, data locations and validation status.

### Deterministic agent and repository fixtures (B05)

B05 adds an offline ACP SDK fake agent and owned temporary Git repositories.
Tests cover sequential historical edits, permissions, delayed/cancel-resistant
agents, failed partial writes, malformed output, shell writes, dirty index and
worktree states, conflicts, renames, binary files, symlinks and unusual paths.
Independent byte/type/mode oracles verify snapshots after storage restart and
reject deliberately incorrect expectations. No model credentials are needed.

```sh
cargo test -p runtime --test fixtures --locked
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b05-fixtures.json
```

The process suite now has 18 experiments, including a crash after a partial
write. See the [fixture guide](docs/testing-fixtures.md) for scope and commands.
This is test infrastructure used by B06's native engine bridge.

GitHub Actions workflows have been removed. Run the validation commands locally;
removing the workflow files does not delete historical GitHub run records.

### Immutable history storage (B04)

The `history` crate now stores immutable manifests and BLAKE3/zstd content,
with SQLite metadata, durable events and mutation receipts on one bounded writer
thread. It preserves file modes/types, detects damaged content and backs up older
schemas before migration. Storage is outside the repository; capturing agent
edits and connecting the store to the IDE remain later milestones.

See the [storage reference](docs/history-storage.md) for the publication order,
API boundaries, retry rules, limits and crash-recovery tests.
Local Rust workspace tests, formatting and Clippy pass, including the new history
restart, corruption and crash-boundary checks.

### Engine protocol (B03)

The Rust executable now serves Flow JSON-RPC v1 over bounded newline-delimited
stdio. Initialize the connection, use its returned epoch for health/shutdown
requests, and send `requests.cancel` notifications to cancel pending requests.
Project-bound launches also expose state snapshots and durable event replay;
agent sessions remain later milestones. The [protocol reference](docs/engine-protocol.md) defines
the wire format, limits, errors, cancellation and mutation retry rules.

Kotlin has explicit protocol DTOs and connection-local reply correlation. Shared
golden fixtures and schema checks keep both languages aligned. A Kotlin test
launches the real Rust engine and performs initialization, health, timeout and
shutdown. B06 adds the Swing project-service transport described above.

B03's local validation passed, including the strict Gradle build, all seven
Kotlin tests and Plugin Verifier on IDEA IU-253.33813.55. See the protocol
reference for test evidence and remaining platform API notices.

```sh
cargo run -p protocol --example export_schema --locked -- tests/fixtures/protocol/schema.json
cargo test --workspace --all-targets --locked
```

### Build foundation (B02)

B02 adds a registered engine integration test, Kotlin formatting,
strict Gradle dependency locking and Plugin Verifier against the pinned IDE.
The host baseline build passed: Kotlin formatting, three native tests, plugin
packaging and Plugin Verifier against IDEA IU-253.33813.55. Gradle's lockfile and
checksum metadata are generated, and strict verification without generation
flags now passes. A clean-checkout validation remains pending; see the
[build foundation setup](docs/build-foundation.md) for commands and evidence.
The two outstanding B01 checks remain tracked separately; proceeding with build
infrastructure does not declare G0 complete.

### Compatibility experiments

B01 adds an ACP SDK probe, 17 fixture experiments, native diff/merge assertions,
and optional IDE lifecycle actions. Run the local adapter fixture without model credentials:

```sh
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b01-fixtures.json
```

See the [compatibility runbook](docs/compatibility-spikes.md) for real-adapter
commands, IDE actions, observed results, and remaining feasibility checks. These
experiments are separate from the production engine; G0 is still pending.

### Rust engine

Install Rust through rustup and a native C/C++ toolchain for bundled SQLite,
BLAKE3 and zstd. `rust-toolchain.toml` selects Rust 1.99.0 with rustfmt and Clippy.

```sh
cargo build --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
cargo run -p engine --locked
```

The engine logs only to stderr and waits for JSON-RPC messages on stdin. It exits
on EOF or a negotiated shutdown. Run the example in the protocol reference for
an interactive smoke check. `Cargo.lock` records resolved dependencies; a fresh
machine needs crates.io access to download them.

### JetBrains plugin

Install JDK 21 and set `JAVA_HOME` to it. The build uses Gradle 9.0.0, Kotlin 2.2.20,
IntelliJ Platform Gradle plugin 2.19.0, and IntelliJ IDEA 2025.3.6.1. The IDE provides
Kotlin stdlib and coroutines; the plugin does not bundle another copy.
Gradle tests now also require Cargo on PATH: they build the Rust engine for the
Kotlin-to-Rust protocol integration test. New protocol fixtures are shared test
resources, not plugin runtime assets.

```sh
cd integrations/jetbrains
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin
./gradlew runIde
```

Use `gradlew.bat` on Windows. The wrapper downloads a checksum-verified Gradle
distribution. The first build also downloads the pinned IDE and Maven artifacts.
Open **View → Tool Windows → Flow** in the development IDE to launch the project
engine lazily, then select **Check Engine Health**.

The 253 build range is an initial development target, not a claim of validated
release compatibility. SDK integration probes and packaged acceptance from the
plan remain required. No agents or credentials are installed by these commands.

Rust workspace compilation, formatting, Clippy, and bootstrap execution have been
checked. The development plugin has launched in IDEA 2025.3.6.1; manual historical
diff, merge Apply/Cancel and project-close cleanup checks passed. All three native
tests and the plugin ZIP build passed on the user's host; configuration verification
completed with an intentional 253-only compatibility warning. Real Codex session
creation, cancellation acknowledgement, sequential edits and shell/background probes
all passed with a per-run `gpt-6-astra` override. The
[six-case report](docs/b01-host-real-astra-evidence.json) preserves the results;
the background case verifies final contents, not every intermediate write.
Plugin-unload cleanup and adapter release provenance remain pending before G0.
Gradle's local lock-coordination socket is blocked in the assistant's
execution environment; run native validation commands in your Mac's Terminal.
