# First vertical slice acceptance (B12)

Implemented on the existing `feat/workspace-operation-recorder` branch. This is
an acceptance gate for B05/B10/B11/B14/B25, not a new agent or review subsystem.
The original implementation plan remains uncommitted.

## Controlled native test

`FirstVerticalSliceTest` uses the existing IntelliJ Platform test framework,
real Swing panels/listeners, `EngineController`, the real Rust executable and
the existing Python ACP fixture. No model account or network is needed.

The only substituted boundary is display of the final `SimpleDiffRequest`:
the test captures the same native request that production sends to DiffManager.
It asserts native document text, read-only flags, historical titles and stability
across restart. It does not claim pixel rendering, keyboard accessibility or a
complete IDE process restart were automated. The B11 host checks still apply.
Input accessible names also make the task form identifiable without reflection
or assumptions about layout order.

Three scenarios:

1. Submit the first task using **Use current commit**, **Start Codex session**
   and **Once**. Wait for its completed turn, then submit **Send follow-up** and
   **Once**. Require exactly the probe-file pairs `absent → flow-probe-1\n` and
   `flow-probe-1\n → flow-probe-2\n`. Turn completion is the explicit observation
   barrier; the test does not rely on a timed pause between filesystem writes.
2. With fixture flag `--cancel-after-write`, approve the first write, wait until
   bytes exist, then click **Cancel turn** while the tool is still pending.
   Require the surviving first-version bytes and an interrupted history entry.
3. With `--fail-second-write`, complete the first turn normally. The approved
   second write changes bytes, emits a failed tool event, and fails its prompt.
   Require both exact historical pairs and a failed second observation.

Each scenario selects the session, operations and probe file through the History
panel and opens the native diff requests. It then closes/settles the session,
restarts the engine, deletes the live probe file, constructs fresh Swing panels,
and opens the same historical pairs again. No agent may be started by history
review. Titles and text must remain identical. The main project's file contents,
HEAD, staged index entries and Git status must be unchanged, including deliberately
staged, unstaged and untracked developer work.

Fixtures use new temporary repositories and data directories only. They never
use the user's existing Flow workspaces. Unknown process-group shutdown remains
failed/quarantined; tests do not clear owner records or reinterpret it as verified
shutdown. This gate therefore does not replace B25's host process-ownership check.

## Run

From `integrations/jetbrains`, with JDK 21 and the normal verified Gradle cache:

```sh
./gradlew test --tests dev.flow.ui.FirstVerticalSliceTest \
  --dependency-verification=strict --console=plain
```

The Gradle test task builds the Rust engine and supplies absolute fixture paths.
Results are in `build/reports/tests/test/index.html`. Run all checks after this:

```sh
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
# From integrations/jetbrains:
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Do not bypass dependency verification or treat an unavailable native test runtime
as a passing result.

## Pinned Codex host gate — manual and still required

Use the adapter binary and checksum recorded in the B01 evidence; the observed
version alone does not verify provenance. The B10 [workflow guide](codex-task-workflow.md)
explains executable/model configuration and the opt-in CLI acceptance runner.
That runner complements this gate; it does not replace native UI evidence.

1. Open a disposable committed Git project in the sandbox IDE. Record IDE build,
   OS/architecture, Flow revision/dirty state, adapter version/SHA-256 and model
   override (no credentials). Keep an uncommitted sentinel edit and record HEAD,
   staged entries and working-tree state.
2. In Flow's Session tab use the current commit and prompt:
   `Create flow-b12.txt containing exactly "version 1" followed by a newline.`
   Approve only that write. Wait for the completed turn. In History record the
   operation ID, before/after content IDs, status, attribution and evidence.
   Open its native diff; verify absent → version 1 against actual workspace bytes.
3. Send a follow-up changing the same file to `version 2` followed by a newline.
   Wait for completion and inspect the second historical pair. Reopen the first.
   Record the actual observation boundaries: do not relabel a checkpoint interval
   as precise tool attribution. Extra observations must be explained, not hidden.
4. Close the session, confirm writers have stopped, then remove the disposable
   live file. Restart the engine and reopen both historical diffs. Restart the
   sandbox IDE and repeat. Check read-only presentation and no agent auto-resume.
5. Confirm the main project's sentinel, files, HEAD and index are unchanged.
   Record the evidence below, with paths/transcripts redacted where necessary.

Evidence record (fill with observed facts, never defaults):

| Field | Observation |
| --- | --- |
| Date, OS/architecture, IDE build | Pending |
| Flow revision and dirty state | Pending |
| Adapter version and verified SHA-256 | Pending |
| Model override | Pending |
| Session and turn IDs | Pending |
| Pair 1 operation/content IDs, bytes, status/attribution/evidence | Pending |
| Pair 2 operation/content IDs, bytes, status/attribution/evidence | Pending |
| Reopened after engine and IDE restart | Pending |
| Main project files/HEAD/index unchanged | Pending |
| Writer shutdown verified | Pending |
| Result / unresolved limitations | Pending |

**Release gate:** controlled tests and the real-agent host scenario must both
pass. If only one checkpoint is observable, or snapshots differ from disk, leave
the real two-edit gate open. Do not proceed to advanced selective composition on
the strength of fixture results alone. Cancellation/failure determinism comes
from the controlled fixture; do not deliberately corrupt a real user's workspace
to force those cases.

## Validation recorded for this implementation

On 2026-10-14, all **3 native Swing scenarios**, **15 Kotlin bridge tests** and
**76 Rust tests** passed. Kotlin compilation/formatting, Rust formatting and
Clippy with warnings denied passed. The native scenarios ran against the cached
IntelliJ 253.33813.55 platform test framework through a local standalone JUnit
launcher. Its temporary test descriptor used the core classloader; the shipped
plugin descriptor was unchanged. This validates the test logic and native request
construction, not the packaged plugin's classloader or installation.

The normal Gradle test command was attempted but could not write the wrapper's
`~/.gradle` lock under the managed sandbox. Full Gradle checks, plugin packaging,
IDE process restart/presentation checks and the real Codex evidence table remain
host acceptance work. No real model session was launched by this implementation.

Update on 2026-10-15: B28 ran the normal strict Gradle suite successfully,
including all three native B12 scenarios, plugin packaging and Plugin Verifier.
The earlier wrapper-cache restriction was overcome through authorized local
build access. The real-Codex and clean-machine/native host evidence above was
still pending at that point; subsequent observations are recorded below.

## Real-engine acceptance — 2026-10-15

The user authorized the real-agent check. The engine extracted from the arm64
plugin ZIP passed the existing B10 acceptance runner using `codex-acp` 0.5.2
and the explicit `gpt-6-astra` model override. The original installed adapter
was absent, so the matching [upstream release asset](https://github.com/zed-industries/codex-acp/releases/tag/v0.5.2)
was downloaded to temporary storage. Its archive matched GitHub's published
SHA-256 and its executable exactly matched B01's recorded binary checksum.
No adapter was bundled into Flow or substituted with a newer release.

The retained [real-engine evidence](b12-real-engine-evidence.json) records the
Flow revision, archive/engine/adapter digests, actual session/turn/operation and
content IDs, and exact pairs `absent → version 1\n` and
`version 1\n → version 2\n`. Actual worktree bytes matched both requested states.
The main project files, HEAD and index were unchanged, the final manifest matched
the checkpoint before deliberate deletion, and session writer shutdown was
verified. Two subsequent release-engine processes reopened identical historical
pairs, including after deleting the owned live probe file; review started no
agent. Scratch history and local paths remain in
`integrations/jetbrains/build/b12-real-engine-evidence.json`.

Both real operations report **ambiguous attribution** with `tool.observed`
boundaries, stable reads and complete coverage. Their evidence explicitly retains
`writerQuiescenceProven: false` and `safeToApply: false`. These are two observed
intervals, not claims of atomic tool effects or permission to apply changes.

**B12 remains open.** This real run submitted tasks over engine IPC. Native Swing
submission, rendered/read-only diff presentation, a full IDE process restart,
and real-agent preservation of dirty developer sentinels still need observed
host evidence. Controlled native tests already exercise those sentinels; that
does not turn them into real-agent host observations. Project disposal and plugin
unload remain native lifecycle checks.

A fresh committed project with staged, unstaged and untracked sentinels is ready
for that check. Its project path, baseline evidence and verified adapter path are
in `integrations/jetbrains/build/b12-native-host-session.json`. The pinned
IU-253.33813.55 sandbox was launched with `-PflowBundledEngine=true`, without an
engine executable override. The sandbox's adapter/model preferences were filled
only where its settings file did not already exist. Follow steps 1–5 above with
that project, then compare its developer files/HEAD/staged entries with the saved
`before.json`. macOS reported Accessibility UI scripting disabled, so no visible
UI actions or full IDE restart are recorded as having been performed by the
assistant. This development sandbox does not replace clean-machine installation.

## Combined-branch native update — 2026-10-16

The [opt-in real Swing scenario](../integrations/jetbrains/src/test/kotlin/dev/flow/ui/FirstVerticalSliceTest.kt)
now passes with the same checksum-pinned adapter and explicit model. It submitted
both tasks through the actual native controls, verified the two immutable
read-only diff requests, preserved staged/unstaged/untracked developer work and
the index, verified writer shutdown, restarted the release engine, deleted the
owned live probe and reopened identical historical pairs without an agent.
[Retained native evidence](b12-real-native-evidence.json) records the observed
result and the remaining host checks. It supersedes the pending Swing-submission
and dirty-sentinel observations in the earlier engine-only report; that report
remains an accurate record of its own narrower run.

The first native attempt timed out because the harness used a fixture-specific
permission label; it now uses only explicit `allowOnce` metadata and exact option
identity. The next attempt completed both real turns but exposed the historical
file-page cancellation race. A deterministic native regression failed against
the original behavior and passed with the fix. Later Linux checks also exercised
list selection deduplication and publishing request ownership before starting
coroutine callbacks. Failure scratch data was retained, not declared accepted.

The final diff request is still intercepted by the native test framework. Full
rendered presentation, full IDE-process restart and project/plugin disposal are
not asserted by that test; **G1/B12 remain open** for those host observations.
Use the retained session descriptor described below to review real history in
the pinned sandbox without another model run.

## Packaged desktop acceptance — 2026-10-16

`scripts/check_desktop_acceptance.py` installs the unchanged host ZIP into an
isolated plugin directory and opens two separate production IDE processes. Its
auxiliary test plugin is compiled against the pinned SDK and is never included
in the distributed Flow ZIP. This is independent of the native unit-test runtime;
there is no development executable override or diff-display interception.

The runner opens both historical transitions through Flow's actual History
controls and checks the visible native editors' exact text and read-only flags.
It retains painted-window screenshots, checks compact Review/Apply controls and
the file viewport, then starts an offline adapter with a background child. One
process closes the project; the other dynamically unloads Flow. Both must stop
the installed engine, adapter and child. History reopening must start no agent.
Developer files, HEAD and index, the ZIP, engine digest and source inventory must
remain unchanged. Startup/import dialogs are suppressed only in the disposable
profile; the user's IDE profile and accessibility settings are untouched.

This exposed the ownership failure shown in the Session screenshot: service
cancellation stopped the IPC reader/writer before graceful engine shutdown,
forcing termination and leaving workspace ownership quarantined. The transport
now survives service cancellation until bounded shutdown completes, then closes.
A receipt-based regression failed before this fix and passed after it. No owner
record is cleared and recovery confirmation is never inferred from dead PIDs.
Review/Apply controls now keep their preferred heights instead of inheriting the
summary area's height. A bounded journal viewport leaves room for files and all
Apply actions. The desktop check caught the hidden viewport before its fix.

With JDK 21 in `JAVA_HOME`, after `buildPlugin`, run from the repository root:

```sh
python3 scripts/check_desktop_acceptance.py \
  --plugin integrations/jetbrains/build/distributions/flow-jetbrains-0.1.0-aarch64-apple-darwin.zip \
  --output integrations/jetbrains/build/desktop-acceptance/evidence.json
```

Use the matching host ZIP on Windows x86-64, Linux x86-64 or Intel macOS, and
`python` where appropriate. Linux requires a desktop display or `xvfb-run -a`.
The pinned IU-253.33813.55 SDK is located in the Gradle cache by default;
`--ide-home` and `--java-home` support explicit local installations. The existing
four-host CI matrix runs this check after combined release acceptance and retains
phase reports, screenshots and logs, including on failure. These CI additions
have not been pushed or executed on Windows/Intel Mac by this local change.

The offline fixture is the default. To reopen retained real-agent history without
a model call, provide `--reuse-session path/to/descriptor.json`. The descriptor
must name a stopped, owned acceptance fixture's absolute `project` and `dataRoot`,
its `sessionId`, probe `filename`, and two exact text strings in `versions`.
The data directory is copied before the IDE starts; the original history is
preserved. The project receives ordinary `.idea` metadata, while developer file
bytes, HEAD and index remain protected. This option is for disposable acceptance
projects, not an active developer workspace.

On macOS Apple Silicon, the installed package reopened the retained real-Codex
session's exact `absent → version 1\n` and `version 1\n → version 2\n` transitions
in both IDE processes, without auto-resuming an agent. Project disposal and
dynamic plugin unload stopped all owned processes, and developer work was
unchanged. The compact layouts were inspected at the tool window's actual
456-pixel width. The [supplemental desktop evidence](b12-desktop-evidence.json)
binds these observations to the ZIP, source inventory, IDE and harness hashes.
Full local logs/screenshots are retained beside
`integrations/jetbrains/build/b12-desktop-acceptance/evidence.json`.

This supplies the desktop observations left pending in the earlier
[real native report](b12-real-native-evidence.json); its original results and
limitations remain recorded. No new paid model session was launched. This run
does not establish the separate B29 requirement for a new real-adapter session
against this exact artifact. Clean-machine installation, a true plugin upgrade,
rendered merge Apply/Cancel, keyboard/screen-reader checks, signing/notarization
and native Windows/Intel Mac host observations remain open. An isolated profile
is not a clean machine, and `releaseAccepted` remains false.

For the complete fixture, integrity, verifier and performance suite:

```sh
python3 scripts/check_release_acceptance.py --offline \
  --output integrations/jetbrains/build/release-acceptance/evidence.json
```

Omit `--offline` when dependencies need downloading. Neither command commits,
pushes, signs for public distribution or publishes anything.

The final strict Gradle run passed all 30 Kotlin/native tests. All 105 Rust tests
(including examples), 13 Python tests, formatting, Clippy and packaged smoke
checks passed. Plugin Verifier reported compatibility with the pinned IDE,
retaining 4 deprecated-API and 6 experimental-API warnings.
The complete release runner finished with `automatedPassed: true` and
`releaseAccepted: false`. The 20-sample capture benchmark passed at p95
241.46 ms against the 500 ms target. Its ZIP and source inventory hashes match
the desktop report exactly.

One earlier complete run timed out after 15 seconds awaiting native application
completion in `SafeApplicationTest`. That scenario passed in isolation with the
identical installed release binary, then passed in the complete rerun. Its cause
is unconfirmed; no timeout or assertion was weakened. The failed report and logs
are retained under
`integrations/jetbrains/build/release-acceptance/acceptance-1ra09ctg/`.
