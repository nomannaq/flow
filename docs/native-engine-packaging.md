# Native engine packaging (B28)

This milestone bundles Flow's release Rust engine for **macOS Apple Silicon**
(`aarch64-apple-darwin`, deployment target macOS 13) with the plugin compiled for
IntelliJ IDEA 2025.3.6.1 / IU-253.33813.55. Other OS/architecture builds are refused.
This is an internal installation smoke artifact. B12's real-Codex evidence and
B29's final release acceptance remain separate gates.

## Build and validate

Build prerequisites: Rust 1.99.0, Cargo's locked dependencies, system Git,
Python 3.11+, Apple's command-line tools (`otool`, `codesign`), JDK 21, and the
verified Gradle/IntelliJ dependency cache. Installation requires only the supported
IDE/macOS, writable local project/data directories and system Git for coding
sessions. Users independently install/configure their ACP adapter and its login.

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew check buildPlugin checkPackagedPlugin \
  verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Append `--offline` when all dependencies are cached. The package appears under
`integrations/jetbrains/build/distributions/`. `stageNativeEngine` invokes
`scripts/package_native_engine.py` for a locked release build, verifies arm64
Mach-O format and refuses non-system dynamic library references. SQLite/zstd
remain built into the executable. The script ad-hoc signs the staged binary for
local execution, verifies that signature and hashes the final signed bytes.
It stages only its fixed generated `build/native-engine` directory.

The package contains:

```text
Flow/
  lib/                         # plugin JAR; IDE supplies Kotlin/coroutines
  distribution.json           # versions, protocol, target, digest, linked libraries
  dependency-inventory.json   # engine normal/build closure and declared licenses
  licenses/                   # available LICENSE/COPYING/NOTICE files from crates
  bin/aarch64-apple-darwin/flow-engine
```

The manifest records plugin/engine/protocol versions, the pinned IDE build,
minimum macOS version, engine digest, Cargo.lock and inventory digests, linked
system libraries and signing state. The inventory follows Cargo's target-filtered
normal/build dependency closure and excludes development-only branches. It records
workspace packages, missing declared licenses and host-provided components
explicitly. This is an inventory plus available notices, not a legal approval.
Flow's own redistribution license is not yet declared, so public redistribution
remains pending license review.

The Gradle integration uses JetBrains' documented
[prepareSandbox extra-file support](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-recipes.html#bundle-additional-files-with-the-plugin).
The same payload is copied into build, test and development sandboxes with Unix
executable permissions. IntelliJ owns plugin ZIP installation; the runtime adds
no archive extractor or separate download/update service.

## Installed executable selection

The project service resolves only the manifest's fixed macOS arm64 path. It
validates platform, minimum OS, plugin/engine/protocol identity, bounded manifest
and binary sizes, regular files, absence of symlink redirection and SHA-256.
All checks and process work run on the controller's IO dispatcher. If the ZIP
installer drops execute bits, Flow restores only owner execute after digest
verification, preserving read/write permissions. Paths with spaces pass directly
to ProcessBuilder; no shell expansion or quoting is involved.

The adjacent manifest is trusted only as part of a trusted installed distribution.
Its checksum detects corruption/mismatch; it does not authenticate a malicious
replacement of both manifest and binary, or sandbox a writable installation.
Installation permissions and distribution signatures provide that boundary.

A missing, corrupt, inaccessible or incompatible payload shows a fixed reinstall
message and stops automatic retries until an explicit restart. A verified binary
that the OS refuses to execute shows a permissions/security-approval diagnostic.
No raw subprocess/adapter error is displayed. Do not remove quarantine attributes
or bypass Gatekeeper as a troubleshooting shortcut.

`-Dflow.engine.path=/absolute/executable` remains an explicit development override.
Normal `runIde` uses the debug engine. To exercise bundled resolution:

```sh
./gradlew runIde -PflowBundledEngine=true --dependency-verification=strict
```

This avoids the override but still runs in a development sandbox. For installation
acceptance, install the ZIP in a separate IDE as below.

## Smoke and host installation

`checkPackagedPlugin` validates the ZIP and extracts it into an owned temporary
installation outside the checkout, including paths with spaces. It rejects
traversal, duplicate entries, symlinks, unsupported file types and excessive
extraction size. It verifies binary/inventory hashes and presence of a plugin
JAR descriptor. It starts the copied release engine twice through versioned IPC,
checks health, empty persistent state, no agent session and clean shutdown, then
checks that project content stayed unchanged. No debug engine is used.

The Kotlin executable tests also resolve/copy the staged payload outside the
checkout and run the real bridge twice, plus integrity, permission restoration,
platform and fixed-diagnostic regressions. Python archive/inventory regressions:

```sh
# From repository root:
python3 scripts/test_packaged_plugin.py
python3 scripts/check_packaged_plugin.py \
  --plugin integrations/jetbrains/build/distributions/flow-jetbrains-0.1.0.zip
```

Required clean-machine/native installation acceptance (record observed results):

1. Transfer the built ZIP and its recorded SHA-256 to a supported Mac with the
   pinned IDE, without this repository or Rust/build artifacts. Install via
   **Settings → Plugins → Install Plugin from Disk**, then restart the IDE.
2. Ensure `flow.engine.path` is absent. Open a local project with spaces in its
   path, open Flow, check health and record the actual engine executable path
   and PID. It must point inside the installed plugin, never `target/debug`.
3. Close/reopen the tool window, restart the engine, close the project, reopen
   the IDE, and unload the plugin. Record old/new PIDs and observed shutdown.
4. Check missing system Git and missing ACP adapter messages when trying a coding
   session. Engine health itself requires neither Git credentials nor a model.
5. Observe install warnings/macOS security behavior. Record OS, architecture, IDE
   build, archive/binary hashes, signing identity/state and results. Clean-machine
   install, plugin unload, Gatekeeper/quarantine behavior and UI presentation are
   host evidence, not claims made by the headless smoke test.

## Signing and public distribution

Ad-hoc signing permits a local smoke build; it provides no publisher identity.
For a distribution candidate, explicitly sign the native executable with a chosen
Developer ID and hardened runtime **before** manifest/digest generation. Set `FLOW_NATIVE_SIGNING_IDENTITY` when invoking Gradle so staging receives
its explicit `--signing-identity` argument on every build; notarization is
still pending. Re-signing or replacing bytes after staging requires re-staging
and all checks again. Do not attach a stale digest to a signed binary.

Apple's [notarization documentation](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
and JetBrains' [plugin signing documentation](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
cover the respective distribution trust mechanisms. Native Developer ID signing/
notarization and plugin ZIP signing are distinct. A future approved release needs
both mechanisms evaluated against actual downloaded/installed artifacts, with
credentials provided through external release configuration. No signing keys,
publishing credentials, automatic certificate selection or publishing are added.
B29 rebuilds the final feature set and records those release checks; this early
smoke does not certify later candidate/apply behavior or upgrades.

## Recorded local validation — 2026-10-15

On macOS 27.0.1 / arm64, the complete strict, offline Gradle command above
finished successfully. **25 Kotlin/native tests**, **76 Rust tests** and **4
Python packaging/inventory tests** passed, alongside formatting, Clippy with
warnings denied, ZIP smoke, plugin build and project configuration verification.
Plugin Verifier 1.410 reports compatibility with IU-253.33813.55, with four
existing deprecated and six experimental API usages. The intentional 253-only
`until-build` restriction still produces a configuration warning.

The ZIP is `build/distributions/flow-jetbrains-0.1.0.zip` (5.1 MB); the engine's
ZIP entry has mode 0755. This recorded build's archive SHA-256 is
`4fd27e5077c954059e8234db7eaa714ee8d3205cc5164754e0d1d473ab74dc77`.
The engine SHA-256 is
`159500a519a4c1ae424736a979ec2e7b559e6c4391c8bb60caaa8691e84c8866`.
Rebuilding/signing changes artifacts: record new archive/digests rather than
using these as permanent version identifiers.

The installed-copy test exposed a graceful-shutdown race: the bridge previously
terminated the engine immediately after its shutdown reply, before normal exit.
It now waits up to one second for acknowledged shutdown before retaining the
existing termination fallback. The packaged bridge test confirms zero exit status
and no surviving engine process on both starts.

One B12 historical-diff wait timed out in an intermediate full run. Added test
selection/action diagnostics; the focused B12 run and final complete native suite
passed. No claim is made that the intermittent timeout's cause is resolved;
monitor this in repeated host acceptance. Package tests and tests of the corrected
shutdown behavior passed in the final complete run.

The inventory includes 155 packages from the target-filtered metadata closure;
Cargo feature unification can conservatively include more packages than the final
binary uses. Some packages provide a declared license without notice files, and
Flow workspace packages declare no redistribution license. Inventory/notice review,
Developer ID signing, notarization, clean-machine IDE installation, plugin-unload
observation and the B12 real-Codex scenario remain unproven release gates.
