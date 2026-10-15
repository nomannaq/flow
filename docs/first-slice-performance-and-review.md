# First-slice acceptance, performance and whole-result review

The user combined B12, B27 and B18 on 2026-10-16. Development is on the existing
`feat/first-slice-performance` branch. This authorizes the combined implementation;
it does not declare G1 passed. B12's visible diff presentation, full IDE restart
and plugin-unload observations remain host acceptance work. B19 application and
B29 release acceptance are separate deliverables.

## B12 native acceptance

The existing native `FirstVerticalSliceTest` now has an explicit real-Codex mode.
It uses the same Swing controls/listeners, controller, Rust engine and immutable
diff requests as its offline cases. It verifies staged, unstaged and untracked
developer sentinels, two real turns, both historical pairs, writer shutdown,
engine restart, deletion of the owned live file and history reopening without
agent auto-resume. It retains scratch data and redacted result metadata. Normal
tests exclude this method; enabling it may consume model quota.

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# Set these to your independently verified adapter, digest and account model:
export FLOW_B12_ADAPTER=/absolute/path/to/codex-acp
export FLOW_B12_ADAPTER_SHA256='your-verified-executable-sha256'
export FLOW_B12_MODEL='your-account-compatible-model'
./gradlew test -PflowRealCodex=true -PflowRealAllowOnce=true \
  --dependency-verification=strict --console=plain
```

The method selects permissions using the advertised `allowOnce` flag and exact
request/option identities. It never infers permission semantics from labels.
The output is `build/b12-real-native-evidence.json`. This is native framework
evidence: the final diff request is captured for assertions, not pixel-rendered,
and an engine restart is not a full IDE process restart. The complete B12 gate
must remain false until those remaining host checks pass.

To inspect retained native-test history in a complete IDE process, quit the old
pinned sandbox and launch it with its explicit data directory:

```sh
./gradlew runIde -PflowBundledEngine=true \
  -PflowAcceptanceDataRoot=/absolute/retained-scratch/data \
  --args '"/absolute/retained-scratch/project"' \
  --dependency-verification=strict --console=plain
```

This selects acceptance storage, not a different engine executable. Normal
installation still uses default per-user storage. Open both recorded diffs,
verify their read-only presentation, quit/relaunch with the same arguments and
repeat. No model or session needs to be started to review retained history.

The historical page timeout was reproduced with an explicit gate on a real
`history.files` request and a tree-selection-clearing event. Tree model updates
could cancel page loading, leaving “Loading…” indefinitely. Only file actions
(diff/navigation) now cancel on file-selection changes; page requests survive
model selection clearing. Diff/navigation replies also recheck selection identity.
The regression is exercised by the existing native scenarios.
List selection callbacks also compare actual selected identities, and request
jobs acquire ownership before starting callbacks. This prevents duplicate/model
selection events from cancelling a newer query or publishing stale UI results.

## B27 reproducible baseline

```sh
# From repository root; the executable creates/removes only its own temp repos:
FLOW_BENCH_MACHINE='your CPU; RAM; OS' cargo run -p runtime \
  --example first_slice_bench --release --locked -- /tmp/flow-b27.json
# Native controller/Swing batches, diff requests and cancellation:
cd integrations/jetbrains
./gradlew test -PflowPerformance=true \
  --dependency-verification=strict --console=plain
```

The reference machine is Apple M4 Pro, 24 GiB RAM, macOS 27.0.1 arm64. The
[retained baseline](first-slice-performance-baseline.json) includes raw samples,
medians/p95s, fixture sizes, cache assumptions, history growth, 220-operation
pagination/replay and integrity-audit reopen time. Fixture files are 255 bytes;
twenty measured captures change ten files each, followed by 200 further observed
captures. No model is involved. Native timing reports live in
`integrations/jetbrains/build/b27-native-*.json`.

Observed final measurements on that reference machine:

| Measurement | Observed value |
| --- | --- |
| Ten-file reconciliation + durable capture, 1k files | 292.3 ms p95 (20 samples; target <500 ms) |
| Native fixture engine ready | 53.5–58.8 ms (agent startup excluded) |
| Native historical diff request | 30.9 ms maximum observed |
| Session/History EDT update batch | 2.25 ms maximum observed |
| Fixture cancellation to ready | 87.9 ms |

All measured core targets in the retained JSON passed for these fixtures. This
does not turn unmeasured desktop presentation or larger repositories into passed
release gates.

The measured support envelope is the named **1,000-file fixture**, not a general
10k/100k repository claim. Existing hard bounds remain: 4,096 paths, 64 MiB per
blob, 128 MiB captured bytes and 512 KiB encoded metadata. Paths/metadata can
reach another bound before the path count. The 10k/100k fixtures deliberately
record the existing path-limit rejection; those times are rejected discovery
attempts, not complete scans. Ignored/generated files still count; only `.git`
control data is excluded. OS caches are not flushed, so first-pass measurements
are not labelled cold-cache results.

Recorded UI timings measure controller readiness, native diff requests and EDT
update batches on the small integration fixture. They do not measure complete
IDE startup, indexing, visible paint latency or accessibility. Runtime checks
still enforce bounded history queues (4), IPC in-flight requests (32), pages
and replay limits; existing saturation/cancellation regressions must pass. The
10 GiB history budget is explicit admission control; no automatic GC is added.

## B18 complete-result review

Close the session, select it in **History**, then choose **Review complete result**.
In **Review result**, explicitly confirm that all session writers have stopped,
then prepare the result. The engine reacquires repository/worktree leases,
checks the clean-commit baseline and durable checkpoint, requires complete
coverage and regular files, rejects capture gaps/active or uncertain sessions,
and compares live source bytes/modes/types with the recorded checkpoint.

The candidate hash binds its session, baseline, source checkpoint, result snapshot
and source generation. Retries preserve identity. The file list and read-only
native diffs compare the clean baseline with the entire result, including files
outside an individually selected historical operation. Accept/reject affects the
whole candidate; historical operations have no inclusion/application controls.
The baseline commit, old/new paths and permission modes remain visible, including
changes whose file bytes are identical.
An empty delta is explicit. Binary/large previews retain content IDs and byte
counts; symlinks and special permission bits cannot become candidates.

Optional validation takes one explicit executable/argument array per action,
for example `["python3", "-m", "unittest"]`. No command is discovered or run
from repository instructions. It executes as the user's account in an owned
temporary copy, without inheriting provider-key environment variables. Commands
are not a security sandbox. Arguments, status and exit code are retained;
stdout/stderr and credentials are not copied into validation records.

Check commands have a 15-second execution bound, up to 32 arguments/8 KiB total,
and at most 32 retained attempts per candidate. This fits the existing 30-second
IPC contract; longer checks need a separately polled persisted-job feature.
Unix process groups or Windows Job Objects handle descendant cleanup. Unverified
cleanup retains scratch data and stale writer ownership for explicit recovery.
A failed launch with no child does not invent a stale writer.
Windows native execution is still pending the platform acceptance matrix; this
implementation and cross-platform source are not a substitute for that evidence.

If validation changes supported files, it publishes a new immutable result and
returns a new candidate hash for renewed review. The live agent worktree and main
project are not used as the validation directory. Source changes detected during
validation fail the check. A newer check invalidates an earlier decision; a
failed/unfinished check blocks acceptance unless a later successful attempt of
the same command supersedes it. A changed result cannot inherit approval for the
old hash. A rejected candidate cannot be made accepted by running a check.

Each explicit check has a durable intent ID. A lost reply does not replay the
command. On engine restart, running intents become interrupted and block
acceptance; a new attempt needs a new explicit intent. A candidate's immutable
identity/content and its historical decisions survive restart. New session/turn
events invalidate earlier source generations, even when bytes later match again.
Acceptance always rechecks the live source and the generation in a transaction.

Schema 4 adds candidate/check tables using the existing migration backup and
SQLite FULL durability policy. Candidate references protect sessions/snapshots
from explicit retention; they do not create unresolved recovery holds that
disable unrelated writes on restart. Source history and prepared worktrees remain
unchanged. Accepted review is not a safe-to-apply claim, application plan, staging
change or commit; B19 must implement target preconditions and apply recovery.

## Protocol

All methods use the existing epoch/framing/timeout rules. Parameters are in the
shared [schema](../tests/fixtures/protocol/schema.json).

| Method | Behavior |
| --- | --- |
| `candidates.prepare` | Closed session + explicit `writersStopped`; verify and freeze the complete result |
| `candidates.get` | Candidate identity, decision, bounded check history and source-generation freshness |
| `candidates.files` | At most 20 complete-result file entries per page |
| `candidates.diff` | Immutable before/after preview, content IDs and file index |
| `candidates.decide` | Whole-result accept/reject bound to the exact hash; acceptance requires writer confirmation and revalidation |
| `candidates.check` | Explicit command + intent ID + 1–15s bound; return outcome/new candidate identity without replay |

Normal checks never invoke real Codex. Rust regressions cover stale sources,
new turns, unsupported/incomplete results, rejection, changed validation output,
failed/timed-out checks, lost-reply identity, restart interruption and retention
references. The native offline scenario exercises complete-result diff,
accept/reject and a check-produced version requiring renewed acceptance.

## Final validation — 2026-10-16

Both macOS arm64 and the isolated Linux x86-64 container passed **83 Rust tests**
and **27 Kotlin/native tests**. The explicit real-Codex native case passed on
macOS in its separate invocation. Seven Python packaging regressions passed.
Strict Gradle verification, packaged startup/session/history/descendant cleanup
and Plugin Verifier passed; the verifier reports compatibility with the pinned
IDE and the existing four deprecated/six experimental API usages. Linux used
Xvfb and disabled file watching only for the emulated environment; its native
tests/package checks passed, while Plugin Verifier was rerun on macOS.

Current archive/engine digests and remaining installation/platform gates are in
[artifact evidence](combined-native-artifacts.json). The ZIPs are local internal
smoke artifacts, not signed public releases. The B12
[native evidence](b12-real-native-evidence.json) explicitly keeps the full host
gate false. Windows/Intel Mac native execution, clean-machine installations,
signing and B29 remain unproven. No main-project application feature was added.
