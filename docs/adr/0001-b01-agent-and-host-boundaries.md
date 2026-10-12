# ADR 0001: B01 agent and host boundaries

Date: 2026-10-12. Decision: continue the architecture with a narrow macOS target;
keep G0 blocked until real-agent and pinned native-platform checks pass.

## Selected compatibility target

| Component | Pin and evidence |
| --- | --- |
| Platform | macOS arm64 (`aarch64-apple-darwin`); no Windows/Linux support claim |
| Rust | 1.99.0, edition 2024; workspace MSRV 1.99 |
| ACP SDK | 3.3.0 with `process`, stable wire v1; Cargo.lock records the crate checksum |
| SDK source | Published crate's `.cargo_vcs_info.json`: `199532c7433f1aec207dd0cada299901a5621ece`, path `src/agent-client-protocol`; declared SDK MSRV 1.88 |
| Schema | 1.11.0, resolved in Cargo.lock |
| Codex adapter sample | Local Zed-managed `codex-acp` 0.5.2; SHA-256 `027e5c0646275ac6a52c09a8a51069f7b9ede69e96a99d4f5fd6eabbdb291524` |
| Native build | IntelliJ IDEA 2025.3.6.1, Kotlin 2.2.20, IntelliJ Platform Gradle plugin 2.19.0, Gradle 9.0.0 |
| Java / validated launch | Build JDK Temurin 21.0.12.1+1-LTS (user output); IDEA IU-253.33813.55 on JetBrains Runtime 21.0.11+10-b1163.116 aarch64 (local IDE log) |

The adapter is a pinned experiment sample, not an approved distribution dependency.
Its upstream release asset/provenance could not be verified through the available
network. Do not distribute it or declare it the supported production adapter yet.
The real-adapter runner requires its expected binary checksum and records the
advertised version. Choosing another released adapter requires a new checksum and
a complete rerun. Do not substitute an unpinned `npx ...@latest` invocation.

## ACP ownership and launch policy

Use the SDK client/process APIs directly. The SDK owns the subprocess and its
process group; Tokio drives Flow tasks. Fixture experiments prove initialization,
callbacks, cancellation and cleanup without a custom stream/executor bridge.

SDK 3.3.0 does not expose child cwd or `env_clear`. The spike now uses a small Unix
launcher in the same example executable: the SDK starts `acp_probe launch`, which
sets cwd and an environment allowlist on a `std::process::Command`, then calls
`exec`. This replaces the launcher process and preserves the SDK-owned process
group. Parent cwd stays unchanged. The fixture rejects an inherited sentinel
secret and checks the agent's cwd against `session/new.cwd`.

This proves a macOS implementation path for B08. It does not require a second
subprocess manager or a global cwd mutation. Windows needs a separate investigation
when that platform is added. The launcher is a spike, not yet a production API.

Preserve basic process environment, provider credentials explicitly needed for
existing authentication, proxy/certificate configuration, and inherited sandbox
markers. Do not import arbitrary environment variables or Flow/Codex session IDs.
Removing `CODEX_SANDBOX` caused the observed old adapter to panic during startup
in macOS `system-configuration`; preserving the marker restored initialization.
No sandbox setting is weakened or disabled. The allowlist is recorded in the
launcher source and must be reviewed when new adapters are added.

## SDK package inventory and dependency decision

Package versions do not advance in lockstep. The source shipped in SDK 3.3.0 and
the official package documentation were inspected; optional packages were not
added to Flow merely to complete this inventory.

| Surface | Observed support and release compatibility | Decision |
| --- | --- | --- |
| Core `Client`, `Proxy`, `Conductor` role | Exported by locked SDK 3.3.0. Stable v1 client/proxy builders exist; v2 is behind `unstable_protocol_v2`. The core Conductor role is not the separate orchestration executable. | Use Client only for V1; preserve future proxy boundary |
| `agent-client-protocol-conductor` | Official rendered docs show 3.1.0; the separately cached package metadata page shows 2.2.0 with SDK `^2.2.0`. The latter is not source-compatible evidence for SDK 3.x. | No adoption; verify a matching release manifest and cancellation/permission forwarding before any future proxy chain |
| `agent-client-protocol-cookbook` | Official rendered guide is 2.2.0; one-shot clients, ordered dispatch and proxy composition are useful reference patterns. No 3.3.0 package was verified. | Reference material only; compile examples against Flow's locked SDK instead of assuming identical version numbers |
| `agent-client-protocol-rmcp` | Package metadata shows 4.0.2 with SDK `^3.2.0`, RMCP `^3.4.0` and schemars `^1.0`. SDK 3.3.0 fits the declared SDK range; this was not compiled in Flow. | Candidate for B17 only; dependency compatibility must be built and locked then |
| MCP attachment | Standalone RMCP serving does not require native MCP-over-ACP; SDK attachment uses `unstable_mcp_over_acp`. Draft v2 and native MCP attachment are independent features. | Prefer stable session MCP stdio/HTTP configuration at B17; keep both unstable features off now |
| `agent-client-protocol-polyfill` | Core 3.3.0 README and RMCP docs describe adapting native MCP attachment to HTTP. An exact matching released manifest could not be retrieved. | Exclude; no V1 dependency or release gate |
| `agent-client-protocol-test` | Listed as a workspace dev dependency in the core's published original manifest. A matching independently released package was not verified. | Do not guess a version or add a Git dependency; keep Flow's small wire fixture for these experiments |

This is a completed bounded inventory with explicit exclusions, not a claim that
all optional packages have passed compatibility tests. Proxies do not replace
filesystem verification. RMCP builds the tool interface, not Flow's code graph.

Sources: [SDK 3.3.0](https://docs.rs/agent-client-protocol/3.3.0/agent_client_protocol/),
[Conductor documentation](https://docs.rs/agent-client-protocol-conductor/latest/agent_client_protocol_conductor/),
[Conductor package metadata](https://docs.rs/crate/agent-client-protocol-conductor/latest),
[cookbook](https://docs.rs/agent-client-protocol-cookbook/latest/agent_client_protocol_cookbook/),
[RMCP bridge metadata](https://docs.rs/crate/agent-client-protocol-rmcp/latest).
The observed versions above freeze this investigation's findings; mutable `latest`
documentation links are not version pins.

## Capture and supported shapes

The fixture proves three exact historical contents for two sequential edits,
including edits performed through an actual shell. A separate experiment starts
two overlapping shell writers and observes a file changing after `end_turn`.
Therefore a completed prompt is not proof of workspace quiescence.

Use checkpoint-interval attribution initially. Do not assert that a tool event
brackets every write, that simultaneous tools have separable deltas, or that
each recorded change can be independently undone. Preserve changes after failed
or cancelled operations when the production recorder is implemented in B09.

The B01 experiment envelope is deliberately small: a fresh ordinary local Git
repository and one known regular UTF-8 file under 64 KiB. It is not a worktree
implementation. Production M1 should begin with a clean committed baseline,
one writing session, local filesystem and explicitly supported regular files.

The probe does not establish production support for symlinks, non-UTF-8 paths,
binary/large files, case-only renames, file modes, sparse checkouts, submodules,
LFS/filters, unmerged indexes, dirty-baseline import, network filesystems or
background writes that outlive the process group. B07/B09 must detect these
conditions and either record them correctly or return a clear unsupported result.
Do not silently omit them from a supposedly complete snapshot. Process-group
cleanup is not an OS security sandbox.

## Native integration and exit decision

Native diff and merge actions share their fixture construction with three platform
tests. Those tests assert historical contents, exact resolved merge output and no
accepted content on cancellation. The process action uses a project-service
coroutine scope and bounded cleanup. Project close and plugin unload still need
interactive observation, including confirming the process exits before its
natural 60-second timeout.

**Continue** the direct SDK/native UI architecture. **Narrow** support to the
target and capture envelope above, with interval attribution. **Stop claiming G0
completion** until the real adapter performs two observed edits and the pinned
native build demonstrates diff, merge and lifecycle behavior.

The real adapter's handshake succeeds, but its worker reports a
`system-configuration` panic while creating a session. The probe now detects the
Rust panic header, cancels the pending probe and invokes SDK process cleanup;
the report classifies this as `adapter_panic` instead of a timeout. This does not
repair the external binary. A similar upstream failure was diagnosed in
[Codex issue #8912](https://github.com/openai/codex/issues/8912); its linked fix
is not evidence that a particular ACP adapter release fixes this installation.
The sandbox is a plausible cause, not a proven general defect in the adapter.
Gradle independently fails at its lock-coordination socket before project
configuration in assistant execution. The user has now installed JDK 21 and
launched the pinned IDEA build. Manual historical diff and merge Apply/Cancel
checks passed; the IDE log confirms project-close process cleanup before the
fixture's natural exit. The user's subsequent native run passed all three tests
and produced the plugin ZIP. Configuration verification completed with a warning
about our deliberate `253.*` compatibility limit; retain that limit. Plugin-unload
cleanup remains pending; see the compatibility runbook for artifact fingerprints.

The same adapter creates sessions and acknowledges cancellation in the user's
Terminal, but edit/shell/background prompts fail with RPC -32603 before reported
tool execution. Session creation alone does not verify model authentication.
Existing redacted reports lack the error detail required to establish the cause;
the probe now classifies RPC message/data without persisting raw payloads.

Next validation must run on the intended host with that JDK/IDE and an authenticated
released adapter. Retain real reports, the plugin test XML, exact runtime/build
identifiers, and native action observations. Each remaining external investigation
keeps the plan's maximum of two engineering days before a continue/narrow/stop
decision. This environment's reproducible blockers do not justify replacing
real-agent or native evidence with fixture results.
