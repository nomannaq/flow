# B02: workspace and build foundation

Planned branch: `chore/workspace-and-build-foundation`.

The user authorized proceeding to B02 after the six real ACP experiments passed.
B01's adapter release provenance and plugin-unload checks remain open. This scope
is build infrastructure; it does not approve production agent sessions or mark G0
complete. The editing environment cannot write Git metadata, so branch creation
must be done in the developer's Terminal. Preserve the existing B01 changes when
organizing the branches. No commit is made by this work, including the plan.

## Implemented foundation

- Existing four-crate Cargo workspace and `flow-engine` executable are retained.
- Rust 1.99.0, Gradle 9.0.0, Kotlin 2.2.20, IDEA 2025.3.6.1 and the Gradle plugin
  2.19.0 remain pinned. `Cargo.lock` remains the Rust dependency record.
- `tests/integration/bootstrap.rs` is registered in the engine manifest. It runs
  the actual executable, checks successful exit, stdout reserved for IPC,
  diagnostic output on stderr and no files created in its temporary workspace.
- Kotlin formatting uses ktlint 1.7.1 with the shared `.editorconfig`. Its tool
  dependencies are separate from the plugin runtime. `check` includes
  `ktlintCheck`; `ktlintFormat` applies formatting explicitly.
- Gradle uses strict dependency locking. Plugin Verifier 1.410 targets the
  current pinned IDEA build, not a moving recommended-build list.
- GitHub Actions has Rust/ACP and native-plugin jobs on `macos-15`, with an arm64
  assertion. Native validation builds a development ZIP, runs the platform tests
  and Plugin Verifier, and rejects missing, failed or skipped test reports.
  It uploads validation reports only; publishing remains B28.
- CI runs existing ACP protocol fixtures without credentials. Flow IPC fixtures
  will arrive with the actual versioned contract in B03; the protocol crate is
  still empty and no protocol test coverage is claimed for it.

The IDE supplies Kotlin stdlib and coroutines. Do not add another runtime copy
when implementing the bridge. In repository settings, require both build jobs
before merging; adding workflow YAML alone cannot change branch protection.

## First native dependency baseline

Run on the selected macOS arm64 host with JDK 21, Python 3.11+ and network access:

```sh
cd /Users/nomanqureshi/Desktop/dev-noman/flow/integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./gradlew ktlintFormat check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --write-locks --write-verification-metadata sha256 --console=plain
```

This generates `gradle.lockfile` and `gradle/verification-metadata.xml` from the
resolved dependencies and formats the Kotlin source. Review the generated
dependency versions and checksum origins before including those two files with
the B02 changes. Generated checksums pin downloaded bytes; they do not independently
prove upstream provenance. Do not add broad trusted-artifact exclusions to get a
verification failure to pass. See Gradle's
[dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
and [verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
documentation.

Then run verification without generation flags:

```sh
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
./gradlew runIde
```

CI requires both generated files and never generates or updates them itself.
If the first baseline command fails partway through, resolve the reported issue
and rerun it; partial generated files are not a completed baseline. Native tests
and Plugin Verifier must pass before B02 closes. The retained `until-build=253.*`
configuration warning expresses the deliberately narrow compatibility range.

Future dependency updates use the same generation/review/strict-validation cycle
on the supported host. Recheck [Plugin Verifier configuration](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html)
when changing the target build. This repository has no automated release or
Marketplace publishing step.

## Rust checks

From the repository root:

```sh
cargo build --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
cargo test -p engine --test bootstrap --locked
python3 scripts/spikes/check_acp_probe.py --output /tmp/flow-b01-fixtures.json
```

The explicit integration-target invocation catches an unregistered or missing
target. Empty placeholder crates may still print `running 0 tests`; they do not
replace the required bootstrap and ACP checks.

## Reserved data locations

The bootstrap does not create persistent files. Reserve
`~/Library/Application Support/Flow/` for the initial macOS engine's durable
data, outside the user's repository. B04 will place project-scoped databases and
content storage under `projects/<project-id>/`; B07 will manage worktrees with
ownership and cleanup metadata. Project IDs must derive from canonical workspace
identity rather than a display name. Derived caches can use
`~/Library/Caches/Flow/`; neither location is an IDE installation directory.

When persistence is implemented, its constructors must accept an explicit data
root so tests supply a `tempfile::TempDir` instead of accessing these defaults.
Reserve that override for test/development wiring; no environment override or
directory creation is introduced before a storage consumer exists. The bootstrap
test already uses an isolated temporary working directory.

## Validation status

Rust formatting, Clippy, the registered integration target, the existing two
probe unit tests and all 17 ACP fixture expectations pass locally. The new fixture
run is saved at `/tmp/flow-b02-fixtures.json`; archived B01 reports are unchanged.

The user's B02 baseline build passed on 2026-10-12 in 3m 25s. The
[retained evidence](b02-native-baseline-evidence.json) records artifact hashes,
three native tests with zero failures/errors/skips, and Plugin Verifier's
**Compatible** verdict for IDEA **IU-253.33813.55**. The formatter changed only
the multiline superclass declaration in `CompatibilityProbeActions.kt`.

Plugin Verifier also reports four deprecated and six experimental API usages in
`WorkbenchToolWindowFactory`'s inherited/default-method bridges. These are
compatibility observations to revisit when changing the platform, not a zero-warning
result. The plugin ZIP contains no bundled Kotlin or coroutine classes.

The generated verification metadata contains 763 artifacts, each with a SHA-256,
metadata verification enabled, signatures disabled, and no trusted-artifact
exclusions at generation time. These checks establish the baseline's structure; they do not replace
a strict Gradle verification run. Keep the generated dependency files with B02.

The first strict run exposed missing checksums for the synthetic `localIde:IU`
directory created by `pluginVerification.ides.current()`. Its generated Ivy
descriptor also embeds the host's absolute Gradle cache path, so its checksum
cannot be shared across machines. The verification configuration now trusts only
that directory reference and descriptor for **IU-253.33813.55**, with the directory
rule restricted to the **2025.3.6.1 aarch64** artifact name. It does not exempt IDE
downloads, bundled JARs, Maven dependencies or other local IDE builds. All 763
existing artifact checksum records are retained. The earlier evidence file is an
immutable record of baseline generation, before these two scoped rules.

This follows the distinction between local generated artifacts and downloaded
dependencies described in the [upstream verification issue](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1779).
The exception assumes the local Gradle extraction/cache is trusted, like other
local build outputs. Revisit both rules when updating the pinned IDE.

The user's subsequent strict run **passed in 47 seconds**, without `--write-locks`
or `--write-verification-metadata` (19 actionable tasks: 9 executed, 10 up-to-date).
The [strict-run evidence](b02-native-strict-evidence.json) records the command,
current artifact fingerprints and scoped trust rules. The inspected native report
has three tests with no failures/errors/skips, and Plugin Verifier still reports
compatibility with four deprecated and six experimental API usages. Up-to-date
tasks reuse their prior outputs; this is not a clean-checkout validation.

Remaining B02 build acceptance: validate a clean checkout in the required
CI jobs. This editing environment still cannot write the Gradle cache or resolve
Maven downloads. B01's two open checks remain separate follow-ups.
