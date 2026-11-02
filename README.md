# Flow

A native agent workbench for understanding, reviewing, and applying AI code changes.

Flow is being built to bring coding agents such as Codex and Claude Code into
JetBrains IDEs, with a Rust engine that preserves change history and keeps the
developer in control. Existing agents handle coding; Flow provides workspace
isolation, graph-based project context, and native review tools around them. The
UI centers on conversation: choose an agent and send a task.
Cursor CLI can
also connect directly through its native ACP endpoint.

**Status: early development.** The repository currently contains the Rust
workspace, durable history storage, isolated clean-commit worktree preparation,
versioned stdio engine protocol, and a React chat hosted in a JetBrains JCEF tool
window connected to a project-scoped Rust engine. Native Swing review/settings
remain during the staged migration. B08 adds ACP agent sessions, streaming activity,
one-time permission controls and cancellation. B09 records verified filesystem
intervals into immutable history. B25 adds explicit recovery and retention; B14
adds retained-session browsing, bounded native historical diffs, archive controls,
and explicit new conversations in verified retained workspaces. B10 adds local
Codex launch preferences, advertised authentication retry, adapter compatibility
diagnostics and readable responses with recorded-operation counts. B11 adds
turn-by-turn history navigation, a native file tree, capture warnings and
content-identified read-only diffs. Native packaging builds a matching release
engine for Windows/Linux x86-64 and macOS Intel/Apple Silicon, with integrity
checks and a native CI acceptance matrix. Expanded-platform acceptance is pending.
B13/B15–B17/B20–B24 add selected local overlays, worktree code context over MCP,
a Claude ACP profile, reviewed prompt enhancement and native Commit message drafts.

## Features and roadmap

### Review every recorded change

Explore a session's operation history and open historical edits in JetBrains'
native diff viewer through **Flow → History**. Immutable before/after snapshots
preserve each recorded change, even when an agent edits the same file again later.
Chat shows your prompts, agent replies and compact tool calls. An **Edits** tray
above the input shows changed-file counts and additions/deletions. Click it for a
scrollable file list, then select a file to open its verified native diff.
The same strip includes **Plan**, with reported steps and completion counts, and
**Subagent**, with delegation status, tasks, results and reported child tools.
**Review & apply** opens the existing workflow for accepting and applying the
isolated workspace's result. Binary/large-file limitations stay explicit, and
counts distinguish loaded text files from the total. Session/tool diagnostics stay in Settings. Model selection uses a
searchable list anchored to the composer, with click or Enter selection. Text previews are bounded to 64 KiB per
side; the operation history retains earlier edits.

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

Flow opens directly into a conversation, with a session header and a bottom
composer that grows with your draft. The text box keeps a single **+** menu for
attachments, context and commands, with enhancement and send/stop actions on the
right. Agent, model and permission controls sit on a quiet row beneath it.
The agent menu lists **Claude Code**, **Codex**, **Cursor**, and
**Auto-detect**, with integration icons, a selected indicator and an **Agent settings**
shortcut. The model control shows the current model and effort together, with
a compact settings menu, an effort step control and switches for advertised
fast mode. **Model** opens the searchable model list; other provider settings
keep their native choices.
Choosing an agent keeps your draft and attachments; an active conversation
keeps its connected agent until you start a new conversation. The arrow sends and
the square stops a response. Starter tasks fill an editable draft
without sending it. Secondary message actions use small icons;
advanced connection settings stay collapsed. Header actions open **History** and
**Settings**; the recorded-changes
strip opens **Review → Apply**. Back returns to the same draft and scroll position.
Messages render Markdown and code blocks, with copy/quote actions, approvals and
reader-aware scrolling. Tool calls appear between response blocks as compact,
expandable cards with their reported status, inputs and results. Consecutive calls
of the same kind group together. Reported changed-file names link to the Edits tray and verified
recorded changes for review, and interrupted calls remain visibly incomplete.
A visible activity row distinguishes working, thinking,
tool use, responding and approval waits, with elapsed turn time.

History supports conversation search, titles, favorites and reading recorded
messages after restart. Export a fixed recorded snapshot as Markdown or JSON.
Chat adds Find, Quote, font scaling, agent-advertised slash commands and reported
context-token usage. Attach files or folders, paste screenshots, or drop documents,
source files, PDFs and other files into the composer. Queue follow-up
prompts while the agent works; Stop pauses the queue. Rewind a recorded message
into a new isolated workspace while preserving the original work and history.

Use the embedded React chat for prompts, streamed replies and permissions;
Kotlin bridges it to the IDE and Rust. Open diffs in the IDE's editor area and use
JetBrains' existing navigation, merge, and commit workflows.

The experimental ACP session controls support Codex and Claude ACP profiles. Flow retains adapter capabilities and uses the agent's own
authentication and permission mechanisms.

### Give agents useful project context

Opening a trusted project automatically creates a local `.flow/` code
map and refreshes it after saved changes. Each agent prompt can receive up to
4 KiB of relevant code and reviewed learnings; identical consecutive bundles are not resent.
This uses the bundled engine without a separate install or model call. Actual
provider token savings have not yet been measured.

The composer's **+ → Project context** menu attaches project files or editor selections, accepts
`@path` references and previews the exact context before sending. Typing `@` opens
project-file suggestions; `@path#L2-8` selects a line range. The composer also
suggests adapter-advertised `/commands`, local Codex `$skills`, and subagent roles
with `#`. Role selection inserts a delegation request for the native agent; it
does not start a separate Flow agent. Native `@agent-name` annotations remain
intact. File links and source references in replies open in the IDE, within the
project or active isolated workspace. Explicit editor
snapshots and isolated-worktree hints are labelled separately. Automatic prompt
hints can be disabled while keeping the read-only MCP tools available.

Open **+ → Code map & learnings** to search by task, find text patterns, inspect a
file API, trace callers/dependencies, or browse the local graph. Select the current
project or the isolated agent worktree; file links verify the source hash before
opening the IDE editor. Tree-sitter parsers cover 24 languages plus TSX and Vue
script blocks. Relationships carry inference confidence; dynamic and ambiguous
targets remain unresolved. Saved edits refresh the index; unsaved buffers are
excluded.

Reviewed learnings live in immutable `.flow-notes/*.md` files or personal Flow
storage. Search can include relevant decisions, and **Review changes** finds
constraints and local branch overlaps. Replacements preserve earlier versions;
path overlap is a review hint, not proof of a conflict. Agents receive read-only
MCP tools through Flow's existing session connection. No external service,
transcript mining or global agent configuration changes are required.

### Improve prompts and commit messages

Use the **sparkle button beside Send** to improve a draft with editor context,
then review the streaming proposal inside Flow before replacing your text. **Auto** follows
the chat model; the review also offers a remembered Claude Code or Codex model override.
Stop or keep the original at any time. The enhancer
preserves your intent, language and constraints; uncertainties are listed
separately. In JetBrains' native **Commit** window, the Flow toolbar button generates an
editable message from checked changes using an installed Codex or Claude Code
login. Multi-part changes get a subject and grouped body, with available streamed
text shown directly in the commit editor. It supports the standard and staging
views and honors native excluded hunks. Cancelling restores the original draft
if you have not edited the preview. Generation never commits or pushes.

Prompt enhancement uses the same installed native login. Its separate text request uses
low reasoning on Codex and disables Claude thinking without changing chat settings.
Commit drafts follow the project's provider and a ready session's confirmed model and
standard effort. Both utilities have tools disabled.

## Roadmap

| Stage | Outcome |
| --- | --- |
| Foundation — in progress | Rust workspace, native plugin scaffold, and framework compatibility checks |
| First working slice | Start Codex in a clean worktree, record consecutive edits, and reopen each historical diff in JetBrains |
| First usable release | Review and safely apply a complete session result, resolve conflicts, recover interrupted sessions, and package one validated IDE/OS target |
| Code intelligence and agent expansion | Multilingual code context and inferred graphs through MCP, Claude Code integration, and explicit import of existing local changes |
| Productivity | Reviewed prompt enhancement and native Commit drafts from checked files and hunks |
| Advanced review | Select logical change groups, check dependencies, and compose a candidate for validation before application |
| Broader support | Additional languages and platforms, concurrent sessions, partial commit selections, and future VS Code/Zed integrations |

Scope expands only after the preceding correctness and compatibility checks
pass. In particular, historical operation review comes before selective
application: keeping one change while rejecting a prerequisite can break code.

Historical numbered requirements remain in Git history before commit `e19d351`.

## Architecture

```text
JetBrains IDE · Kotlin/Swing
    Native tool window, editor context, diff/merge, commit UI
                         │
                Versioned JSON-RPC over stdio
                         │
Flow Engine · Rust
    Sessions · Worktrees · Immutable history · Review and application
    Bounded context retrieval and reviewed text utilities
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
| Code intelligence | Tree-sitter, SQLite FTS5, RMCP |
| Text utilities | genai |

## Repository structure

| Path | Responsibility |
| --- | --- |
| `crates/protocol` | Typed Flow IPC contract, storage interfaces and JSON Schema export |
| `crates/runtime` | ACP SDK integration, session policy and Git worktrees |
| `crates/history` | SQLite and immutable BLAKE3/zstd history |
| `crates/engine` | Tokio executable and application wiring |
| `integrations/jetbrains` | IntelliJ Platform plugin and native Swing UI |

Core versions/features live in the root Cargo workspace. The context crate,
Tree-sitter/RMCP/FTS5, and genai utilities are introduced in their planned later
milestones.

## Development

### Agent connection speed

Installed agents and cached pinned adapters launch directly. Flow keeps the ACP
connection alive for follow-ups, bounds optional prompt hints, and shows startup
phases with timing diagnostics. Cursor uses its installed CLI with no npm wrapper.

### ACP agent sessions (B08)

Run the development IDE below and configure an installed ACP adapter in
**Flow → Settings**, or let Flow detect it automatically. Existing Zed-installed
ACP adapters are detected too. Enter a task and press **Send** or Enter;
Shift+Enter adds a new line, and ⌘/Ctrl+Enter also sends. Flow reads the current Git commit automatically and creates
an isolated worktree. The **Context** menu lets you choose a different baseline or
explicitly select local changes. The project must already have a Git commit.
Permission controls appear only when the agent requests a decision. Follow-ups reuse that session; cancellation and closure
retain partial work. Operation recording reconciles actual file contents; agent
tool events alone do not increment recorded-operation counts.

### Codex task workflow (B10)

Save your adapter path and model locally, choose a committed baseline, and submit
text tasks in an isolated worktree. If native login is required, choose an
advertised method and **Authenticate and retry**. Streamed replies are joined
into readable text; the operation count reflects committed history. Fixture tests
do not certify arbitrary adapters or account settings.

### Retained sessions and local history (B14)

Use **Flow → History → Refresh sessions** to select an earlier session, operation
and changed file, then open its native diff. Close the active agent before
archiving its history or starting another conversation. **New conversation…**
uses a retained workspace only after explicit stopped-writer confirmation and
checkpoint verification. Engine restart never resumes an agent automatically.

### Native history review (B11)

In **Flow → History**, select a session, turn, operation and file. Inspect status,
attribution and capture warnings, then press Enter to open the immutable diff.
**Open CURRENT workspace file** navigates separately to live content and reports
missing/moved locations.

### First vertical slice acceptance (B12)

The native platform test drives the actual Swing task, permission and history
controls through the Rust engine and controlled ACP fixture. It checks consecutive
edits, cancellation after a write, failure after changed bytes, immutable native
diff requests after restart, and preservation of developer changes.

### Complete-result review and performance (B18/B27)

Closed sessions can prepare an immutable complete-result candidate in **Review
result**. Review its baseline/result diffs, explicitly run optional checks in a
temporary copy, and accept/reject the entire candidate. Changed check output
requires review of a new hash. These decisions do not apply, stage or commit
files.

### Safe native application (B19)

Accepted complete results can be planned against the current project in **Apply
result**. Native text conflicts create a new candidate for review. Application
journals retain preimages and receipts; explicit conditional rollback preserves
newer edits and staging. Affected unsaved buffers and unsupported paths/types
block writes.

### Bundled native engine (B28)

`buildPlugin` includes the host's release engine, version/protocol manifest and
dependency notices in an OS/architecture-labelled ZIP. Windows packages target
64-bit Intel/AMD laptops. `checkPackagedPlugin` smoke-tests the ZIP's engine
outside the checkout. Use `runIde -PflowBundledEngine=true` to exercise installed
payload resolution in the development sandbox. `checkPackagedAgentSession` adds
offline edits, immutable history and descendant-shutdown acceptance.

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
is started.

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
write.
This is test infrastructure used by B06's native engine bridge.

GitHub Actions workflows have been removed. Run the validation commands locally;
removing the workflow files does not delete historical GitHub run records.

### Immutable history storage (B04)

The `history` crate now stores immutable manifests and BLAKE3/zstd content,
with SQLite metadata, durable events and mutation receipts on one bounded writer
thread. It preserves file modes/types, detects damaged content and backs up older
schemas before migration. Storage is outside the repository and connected to the
IDE. B09 now reconciles agent worktree changes into immutable operation records.

Local Rust workspace tests, formatting and Clippy pass, including the new history
restart, corruption and crash-boundary checks.

### Engine protocol (B03)

The Rust executable now serves Flow JSON-RPC v1 over bounded newline-delimited
stdio. Initialize the connection, use its returned epoch for health/shutdown
requests, and send `requests.cancel` notifications to cancel pending requests.
Project-bound launches also expose state snapshots and durable event replay;
B08 also exposes session and permission methods.

Kotlin has explicit protocol DTOs and connection-local reply correlation. Shared
golden fixtures and schema checks keep both languages aligned. A Kotlin test
launches the real Rust engine and performs initialization, health, timeout and
shutdown. B06 adds the Swing project-service transport described above.

B03's local validation passed, including the strict Gradle build, all seven
Kotlin tests and Plugin Verifier on IDEA IU-253.33813.55.

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
flags now passes. A clean-checkout validation remains pending.
The two outstanding B01 checks remain tracked separately; proceeding with build
infrastructure does not declare G0 complete.

### Compatibility experiments

B01 adds an ACP SDK probe, 17 fixture experiments, native diff/merge assertions,
and optional IDE lifecycle actions. Run the local adapter fixture without model credentials:

```sh
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b01-fixtures.json
```

These experiments are separate from the production engine; G0 is still pending.

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
on EOF or a negotiated shutdown. `Cargo.lock` records resolved dependencies; a fresh
machine needs crates.io access to download them.

### JetBrains plugin

Install JDK 21 and set `JAVA_HOME` to it. Install Node.js 22.12+ and npm for the
bundled React UI build. Gradle installs the locked frontend dependencies and
builds the assets automatically; plugin users need no frontend development server.
The build uses Gradle 9.0.0, Kotlin 2.2.20,
IntelliJ Platform Gradle plugin 2.19.0, and IntelliJ IDEA 2025.3.6.1. The IDE provides
Kotlin stdlib, coroutines and JCEF; the plugin does not bundle another JVM runtime.
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
release compatibility. SDK integration probes and packaged acceptance remain
required. No agents or credentials are installed by these commands.

Rust workspace compilation, formatting, Clippy, and bootstrap execution have been
checked. The development plugin has launched in IDEA 2025.3.6.1; manual historical
diff, merge Apply/Cancel and project-close cleanup checks passed. All three native
tests and the plugin ZIP build passed on the user's host; configuration verification
completed with an intentional 253-only compatibility warning. Real Codex session
creation, cancellation acknowledgement, sequential edits and shell/background probes
all passed with a per-run `gpt-6-astra` override. The background case verifies
final contents, not every intermediate write.
Plugin-unload cleanup and adapter release provenance remain pending before G0.
Gradle's local lock-coordination socket is blocked in the assistant's
execution environment; run native validation commands in your Mac's Terminal.

### History recovery and retention

B25 is implemented on the existing `feat/workspace-operation-recorder` branch.
Flow checks retained history on startup, preserves readable data when content is
missing or damaged, and stops new history writes at a configurable storage budget
(default 10 GiB). **Settings → Engine & context** displays storage usage and recovery errors.

Use the offline maintenance commands to inspect interrupted sessions, reconcile
current worktree contents without replaying agent actions, or explicitly delete a
closed session's history. Close the project's Flow engine first. Recovery and
retention require verified writer shutdown; an isolated worktree does not stop
background processes. Worktree files and baseline snapshots are retained.

B26/B29 add seeded integrity regressions and package-bound release acceptance.
