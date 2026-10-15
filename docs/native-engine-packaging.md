# Native engine packaging (B28)

The build bundles the host's release Rust engine with the plugin compiled for
IntelliJ IDEA 2025.3.6.1 / IU-253.33813.55. Platform expansion adds four separate
packages; every filename includes its target to prevent accidental substitution:

| Platform | Package target | Native CI runner | Engine requirements |
| --- | --- | --- | --- |
| Windows Intel/AMD 64-bit | `x86_64-pc-windows-msvc` | `windows-2022` | Windows 10+, static MSVC runtime |
| Linux Intel/AMD 64-bit | `x86_64-unknown-linux-gnu` | `ubuntu-22.04` | glibc 2.35+, system libgcc |
| macOS Intel | `x86_64-apple-darwin` | `macos-15-intel` | macOS 13+ |
| macOS Apple Silicon | `aarch64-apple-darwin` | `macos-15` | macOS 13+ |

Windows x86 here means **x86-64**, not 32-bit Windows. Linux/Windows ARM and
musl-based Linux are outside this matrix. Install the ZIP matching the IDE
process's architecture (including when the IDE runs under Rosetta).
This is an internal installation smoke artifact. B12's real-Codex evidence and
B29's final release acceptance remain separate gates.

## Build and validate

Build prerequisites: Rust 1.99.0, Cargo's locked dependencies, system Git,
Python 3.11+, JDK 21, and the verified Gradle/IntelliJ dependency cache. Native
tools are Apple's command-line tools (`otool`, `codesign`) on macOS; Visual Studio
C++ Build Tools and `dumpbin` in an x64 developer shell on Windows; and a C
compiler, binutils (`readelf`) and development headers on Linux. Build Linux on
the Ubuntu 22.04/glibc 2.35 baseline or older. Installation requires the supported
IDE/OS, writable local project/data directories and system Git for coding
sessions. Users independently install/configure their ACP adapter and its login.

```sh
cd integrations/jetbrains
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew check buildPlugin checkPackagedPlugin \
  verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Append `--offline` when all dependencies are cached. The package appears under
`integrations/jetbrains/build/distributions/flow-jetbrains-0.1.0-<target>.zip`.
On Linux omit the macOS `JAVA_HOME` command; use `xvfb-run -a ./gradlew ...` for
headless IDE tests. On Windows use `gradlew.bat` from the x64 developer shell,
with JDK 21 selected and Python 3.11+ on PATH. `FLOW_PYTHON` can select the build
Python executable explicitly. No shell wrappers or Python are required at install.

`stageNativeEngine` invokes `scripts/package_native_engine.py` for a locked
native release build. It verifies the Mach-O, x86-64 PE or x86-64 ELF header,
refuses non-system dynamic dependencies, and rejects Linux binaries requiring
glibc newer than 2.35 or containing library search paths. Windows builds use a
static MSVC runtime and reject external runtime DLL dependencies. SQLite/zstd
remain built into the executable. On macOS the script ad-hoc signs the staged
binary for local execution, verifies that signature and hashes the signed bytes.
It stages only its fixed generated `build/native-engine` directory.

The package contains:

```text
Flow/
  lib/                         # plugin JAR; IDE supplies Kotlin/coroutines
  distribution.json           # versions, protocol, target, digest, linked libraries
  dependency-inventory.json   # engine normal/build closure and declared licenses
  licenses/                   # available LICENSE/COPYING/NOTICE files from crates
  bin/<target>/flow-engine     # flow-engine.exe on Windows
```

The manifest records plugin/engine/protocol versions, the pinned IDE build,
minimum OS/runtime requirements, engine digest, Cargo.lock and inventory digests, linked
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

The project service maps the IDE's OS and CPU to a fixed target path and requires
the manifest to match. A wrong-platform ZIP names the required target in its
startup error. It validates macOS/Windows minimum version, plugin/engine/protocol identity, bounded manifest
and binary sizes, regular files, absence of symlink redirection and SHA-256.
All checks and process work run on the controller's IO dispatcher. If the ZIP
installer drops Unix execute bits, Flow restores only owner execute after digest
verification, preserving read/write permissions; Windows uses executable ACLs.
Paths with spaces pass directly
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
traversal, case-insensitive duplicates, Windows device/ambiguous paths, symlinks,
unsupported file types and excessive
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
  --plugin integrations/jetbrains/build/distributions/flow-jetbrains-0.1.0-<target>.zip
```

The [native CI workflow](../.github/workflows/native-packages.yml) builds and
checks on each actual OS/architecture. It retains a ZIP only after Rust checks,
Python regressions, the Kotlin/native suite, packaged startup, strict dependency
verification, Plugin Verifier and `checkPackagedAgentSession` pass. The last task
uses the installed release engine for two offline ACP edits, verifies immutable
historical transitions and unchanged developer files/index, closes the session,
checks a descendant heartbeat has stopped, then inspects persisted checkpoints.
It never invokes a paid model or substitutes for the B12 real-Codex check.

Windows enables the previously Unix-only agent/worktree path. The SDK launches
an engine supervisor which assigns the adapter to a kill-on-close Job Object
before resuming it. Closing/killing that supervisor closes its job handle and
stops descendants. A bounded, read-only `tasklist` probe verifies supervisor exit;
an uncertain result retains stale writer ownership for explicit recovery. Only
native `.exe` adapters are accepted on Windows; configure an executable such as
`node.exe` plus adapter arguments rather than a `.cmd` launcher. OS/login/temp
environment variables are allowlisted on Windows; Linux retains `XDG_DATA_HOME`.
Git is resolved from PATH and runs with hooks, filters and global config disabled.

Windows history uses native file locks, atomic replacement, file-content flushes
and SQLite FULL commits. Unix mode bits are represented as read-only/writable
regular-file modes on Windows. There is no unprivileged Windows directory-fsync
equivalent: directory durability uses NTFS journaling, and power-loss durability
still requires Windows host evidence. Local NTFS workspaces/data are the intended
Windows acceptance environment; network shares are not certified. Linux creates
the native data directory on first use if `~/.local/share` does not exist.

Required clean-machine/native installation acceptance (record observed results):

1. Transfer the matching ZIP and its recorded SHA-256 to each supported host with the
   pinned IDE, without this repository or Rust/build artifacts. Install via
   **Settings → Plugins → Install Plugin from Disk**, then restart the IDE.
2. Ensure `flow.engine.path` is absent. Open a local project with spaces in its
   path, open Flow, check health and record the actual engine executable path
   and PID. It must point inside the installed plugin, never `target/debug`.
3. Close/reopen the tool window, restart the engine, close the project, reopen
   the IDE, and unload the plugin. Record old/new PIDs and observed shutdown.
4. Check missing system Git and missing ACP adapter messages when trying a coding
   session. Engine health itself requires neither Git credentials nor a model.
5. Observe install warnings/OS security behavior (Gatekeeper, SmartScreen or Linux
   execution policy). Record OS, architecture, IDE
   build, archive/binary hashes, signing identity/state and results. Clean-machine
   install, plugin unload, OS approval/quarantine behavior and UI presentation are
   host evidence, not claims made by the headless smoke test.

## Signing and public distribution

macOS ad-hoc signing permits a local smoke build; it provides no publisher identity.
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
Windows Authenticode and publisher reputation/SmartScreen acceptance remain
pending; Windows/Linux smoke packages are explicitly labelled unsigned. JetBrains
ZIP signing applies separately to every platform package. Any later native
signature must be applied before hashing and packaging.

## Platform expansion validation — 2026-10-15

| Target | Observed checks in this session | Still required |
| --- | --- | --- |
| macOS arm64 | 75 Rust tests; 26 Kotlin/native tests; 7 Python regressions; formatting/Clippy; strict Gradle build, ZIP startup/restart/shutdown and packaged ACP/history/descendant-shutdown acceptance; Plugin Verifier compatibility with the pinned IDE (existing API warnings) | Clean-machine installation and release signing; monitor the historical UI test timeout |
| Linux x86-64 | Full Rust suite in an isolated Debian 12 x86-64 container, plus first-start XDG test (75 total); 7 Python regressions; release ELF/dependency/glibc inspection; offline two-edit/history/checkpoint/descendant-shutdown acceptance | Native Ubuntu CI plugin build and IDE installation; container evidence is not desktop IDE evidence |
| Windows x86-64 | All Rust targets compile and pass Clippy with warnings denied using `x86_64-pc-windows-gnu`; platform/path/PE validation fixtures pass | Native MSVC release packaging, Windows lock regression execution, Job Object lifecycle, Kotlin/native suite, and clean-machine installation |
| macOS Intel | Target selection, package layout and Mach-O validation fixtures | Native Intel runner build, full suite and clean-machine installation |

The workflow runs on pushes to `main` and `staging`, pull requests and manual
dispatch. The first staging push was attempted, but GitHub returned
`startup_failure` with zero jobs under a synthetic `BuildFailed` workflow whose
record is deleted. The committed YAML passes actionlint 1.7.12; repository Actions
are enabled and allow all actions. No Windows or Intel Mac runner was allocated.
See the [run](https://github.com/nomannaq/flow/actions/runs/38747513905) and
[captured API evidence](native-ci-startup-failure.json). Repository-owner/GitHub
workflow registration or dispatch recovery is still required; its backend root
cause is unconfirmed.

Cross-compilation checks Windows APIs and test compilation; it does not verify
the MSVC payload or Windows execution. The native matrix must pass before claiming those platforms
are accepted. Gradle tracks the engine payload and external fixtures as test
inputs so rebuilt binaries cannot reuse a stale test result. The historical B01
`acp_probe` example remains explicitly Unix-only;
Windows uses the production engine and `checkPackagedAgentSession` instead.

Linux parallel tests exposed transient `Busy` results from lock descriptors
briefly inherited between another thread's fork and exec. Worktree and history
writer locks now share a retry bounded to 100 ms; persistent competing writers
remain rejected.
The final Linux suite and existing ownership/recovery tests pass with this bound.
Windows recovery reads ownership through its locked handle, because Windows file
locks prohibit reading the same bytes through a separate handle.

The macOS `EPERM` failure was a teardown race, not proof of an inaccessible
installation. [Apple's process-group implementation](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_sig.c)
filters zombies from signalable processes and can return `EPERM` while the killed
group is waiting to be reaped. The engine now retries that result within the
existing three-second bound and still requires `ESRCH` to prove group exit;
persistent permission failures retain writer ownership. A regression holds a
killed group unreaped before releasing it. The signed packaged engine now passes
the full offline ACP/history/checkpoint and descendant-heartbeat acceptance.

Two intermediate full Kotlin runs hit the previously recorded history UI timeout
(waiting for turns or a file page). The focused run and final complete 26-test
suite passed. Timeout diagnostics now include list selections/counts and panel
messages. That intermittent UI issue is still open; the shutdown fix does not
claim to resolve it.

A fresh Linux Gradle cache also exposed 11 missing parent-POM/BOM metadata
checksums. Each downloaded file was independently compared with its Maven Central
copy before adding its exact SHA-256 and origin URL. Strict metadata verification
and the dependency lockfile remain in force; no broad trust exception was added.

The current Mac archive is
`build/distributions/flow-jetbrains-0.1.0-aarch64-apple-darwin.zip` with SHA-256
`05028e583c5193689044b642b9307495c2114ce00d27d51f8b6eb5896c2e94f2`.
Its engine SHA-256 is
`accbeaaaa741e39020f75820be178eba4c20b72ac1e0182151b5c0b84a57bdf3`.
These identify this local build only. B12's real-Codex acceptance stays open.

## Historical Apple Silicon B28 validation — 2026-10-15

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
