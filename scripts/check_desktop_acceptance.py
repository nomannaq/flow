#!/usr/bin/env python3
"""B12/B29 full IDE-process checks in an owned profile, against an unchanged ZIP.

Uses an auxiliary test-only plugin; never installs into the user's IDE profile.
Default input is an offline session. --reuse-session reopens retained real-agent
history without making a model call. Human accessibility and clean-machine
observations remain pending regardless of this harness's result.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

from check_codex_session import CheckFailed, require, run as seed_session
from check_packaged_plugin import install
from check_release_acceptance import digest, build_inputs
from native_platform import host_target

ROOT = Path(__file__).resolve().parents[1]


def ide_metadata(home):
    candidates = [home / "product-info.json", home / "Resources/product-info.json", home / "Contents/Resources/product-info.json"]
    path = next((p for p in candidates if p.is_file()), None)
    require(path is not None, "IDE product-info.json missing")
    data = json.loads(path.read_text())
    require(data.get("buildNumber") == "253.33813.55" and data.get("productCode") == "IU", "Use pinned IU-253.33813.55")
    root = path.parent.parent if path.parent.name == "Resources" else path.parent
    system = {"darwin": "macOS", "win32": "Windows", "linux": "Linux"}[sys.platform]
    architecture = "aarch64" if host_target().startswith("aarch64") else "amd64"
    launches = [v for v in data["launch"] if v["os"] == system and v.get("arch") in (architecture, "x86_64" if architecture == "amd64" else architecture)]
    require(len(launches) == 1, "IDE launch architecture does not match the current host")
    return root, data, launches[0]


def cached_ide(cache):
    for home in sorted(cache.glob("caches/*/transforms/*/transformed/*")):
        try:
            return ide_metadata(home)[0]
        except CheckFailed:
            continue
    raise CheckFailed("Pinned host IDE missing from Gradle cache; buildPlugin first or supply --ide-home")


def build_harness(java_home, ide, plugins, logs):
    classes = logs / "harness-classes"
    classes.mkdir()
    suffix = ".exe" if os.name == "nt" else ""
    with (logs / "javac.log").open("wb") as output:
        subprocess.run([str(java_home / ("bin/javac" + suffix)), "-cp", str(ide / "lib/*"), "-d", str(classes),
                        str(ROOT / "scripts/desktop/AcceptanceStarter.java")], stdout=output, stderr=subprocess.STDOUT, check=True, timeout=90)
    descriptor = ('<idea-plugin><id>dev.flow.acceptance-harness</id><name>Flow isolated acceptance harness</name>'
                  '<version>1</version><idea-version since-build="253" until-build="253.*"/>'
                  '<depends>com.intellij.modules.platform</depends><extensions defaultExtensionNs="com.intellij">'
                  '<appStarter id="flow-desktop-acceptance" implementation="dev.flow.acceptance.AcceptanceStarter"/>'
                  '</extensions></idea-plugin>')
    jar = plugins / "acceptance-harness/lib/harness.jar"
    jar.parent.mkdir(parents=True)
    with zipfile.ZipFile(jar, "w") as output:
        output.writestr("META-INF/plugin.xml", descriptor)
        for path in sorted(classes.rglob("*.class")):
            output.write(path, path.relative_to(classes).as_posix())
    return digest(jar)


def java_command(java_home, ide, launch, scratch, phase, project, data, seed):
    suffix = ".exe" if os.name == "nt" else ""
    replace = lambda value: value.replace("$APP_PACKAGE/Contents", str(ide)).replace("$IDE_HOME", str(ide))
    # The product's launcher arguments include its required module/native access.
    args = [replace(v) for v in launch["additionalJvmArguments"]]
    properties = {
        "idea.home.path": str(ide), "idea.platform.prefix": "Idea", "java.awt.headless": "false",
        "idea.config.path": str(scratch / "config"), "idea.system.path": str(scratch / "system"),
        "idea.plugins.path": str(scratch / "plugins with spaces"), "idea.log.path": str(scratch / ("ide-log-" + phase)),
        "idea.initially.ask.config": "never", "idea.skip.initial.config": "true",
        "ide.experimental.ui.onboarding": "false",
        "jb.consents.confirmation.enabled": "false", "idea.trust.all.projects": "true",
        "flow.engine.dataRoot": str(data), "flow.acceptance.project": str(project),
        "flow.acceptance.output": str(scratch / (phase + ".json")), "flow.acceptance.phase": phase,
        "flow.acceptance.filename": seed["filename"], "flow.acceptance.sessionId": seed["sessionId"],
        "flow.acceptance.version1": seed["versions"][0], "flow.acceptance.version2": seed["versions"][1],
        "flow.acceptance.python": str(Path(sys.executable).resolve()),
        "flow.acceptance.fixture": str(scratch / "fixture_agent.py"),
        "flow.acceptance.engine": seed["installedEngine"],
    }
    return [str(java_home / ("bin/java" + suffix)), "-Xmx1536m", *args,
            *(f"-D{k}={v}" for k, v in properties.items()), "-cp", os.pathsep.join(str(ide / "lib" / name) for name in launch["bootClassPathJarNames"]),
            "com.intellij.idea.Main", "flow-desktop-acceptance"]


def project_bytes(project):
    # Real IDE metadata is expected; all developer file bytes remain protected.
    files = {}
    for path in project.rglob("*"):
        if {".git", ".idea"}.intersection(path.relative_to(project).parts):
            continue
        if path.is_symlink():
            files[path.relative_to(project).as_posix()] = {"symlink": os.readlink(path)}
        elif path.is_file():
            files[path.relative_to(project).as_posix()] = digest(path)
    files[".git/index"] = digest(project / ".git/index")
    return files


def main(args):
    args.output.parent.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix="Flow desktop acceptance ")).resolve()
    # An existing empty config skips the IDE's first-run import wizard, which
    # would otherwise offer to import the user's unrelated IDE settings.
    (scratch / "config").mkdir()
    report = {"schemaVersion": 1, "passed": False, "releaseAccepted": False, "scratch": str(scratch),
              "startedAt": datetime.now(timezone.utc).isoformat(),
              "baseRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "target": host_target(), "phases": [], "humanChecks": {"cleanMachineInstall": "pending", "keyboardAndScreenReader": "pending"}}
    step = "prepare pinned IDE and ZIP"
    try:
        home = args.ide_home or cached_ide(Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))))
        java_home = args.java_home or Path(os.environ["JAVA_HOME"])
        ide, metadata, launch = ide_metadata(home.resolve())
        report["ideBuild"] = metadata["productCode"] + "-" + metadata["buildNumber"]
        report["buildInputsSha256"] = build_inputs()["sha256"]
        archive = args.plugin.resolve()
        checksum = digest(archive)
        plugins = scratch / "plugins with spaces"
        binary, manifest = install(archive, plugins)
        report["artifact"] = {"sha256": checksum, "engineSha256": manifest["sha256"]}
        report["harnessSha256"] = build_harness(java_home.resolve(), ide, plugins, scratch)
        step = "prepare offline or retained history"
        if args.reuse_session:
            seed = json.loads(args.reuse_session.read_text())
            project = Path(seed["project"]).resolve()
            source_data = Path(seed["dataRoot"]).resolve()
            require(project.is_dir() and source_data.is_dir(), "Retained owned history is unavailable")
            require(all(k in seed for k in ("sessionId", "filename", "versions")), "Retained session descriptor needs sessionId, filename and two versions")
            require(isinstance(seed["versions"], list) and len(seed["versions"]) == 2 and
                    all(isinstance(v, str) for v in seed["versions"]) and seed["versions"][0] != seed["versions"][1],
                    "Retained descriptor must name two distinct text versions")
            report["historyMode"] = "retained; no real adapter or model invoked"
        else:
            seed = {}
            seed_session(argparse.Namespace(engine=str(binary), adapter=None, adapter_arg=[], sha256=None,
                allow_once=False, timeout_seconds=120), seed)
            project = Path(seed["scratch"]) / "project"
            source_data = Path(seed["scratch"]) / "data"
            seed.update(filename="probe.txt", versions=["flow-probe-1\n", "flow-probe-2\n"])
            report["historyMode"] = "offline fixture"
        data = scratch / "private copied history"
        shutil.copytree(source_data, data)
        before = project_bytes(project)
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=project)
        seed["installedEngine"] = str(binary)
        shutil.copy2(ROOT / "scripts/spikes/fixture_agent.py", scratch / "fixture_agent.py")
        for phase in ("close", "unload"):
            step = phase + " production IDE process"
            output = scratch / (phase + ".json")
            command = java_command(java_home.resolve(), ide, launch, scratch, phase, project, data, seed)
            print(step, flush=True)
            with (scratch / (phase + ".log")).open("wb") as log:
                process = subprocess.Popen(command, cwd=scratch, stdout=log, stderr=subprocess.STDOUT)
                try:
                    status = process.wait(timeout=300)
                except subprocess.TimeoutExpired:
                    # Only the JVM launched above is terminated; never a user's IDE.
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                    raise CheckFailed("Owned IDE process timed out")
            require(status == 0 and output.is_file(), "Owned IDE did not produce acceptance evidence")
            result = json.loads(output.read_text())
            report["phases"].append(result)
            require(result.get("passed") is True, "Native desktop acceptance phase failed")
            require(project_bytes(project) == before, "Developer file/index bytes changed during IDE acceptance")
            require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=project) == head, "Developer HEAD changed during IDE acceptance")
        require(report["phases"][0]["idePid"] != report["phases"][1]["idePid"], "Full IDE restart was not established")
        require(digest(archive) == checksum and digest(binary) == manifest["sha256"], "Acceptance payload changed")
        require(build_inputs()["sha256"] == report["buildInputsSha256"], "Source changed during desktop acceptance")
        report["renderedImages"] = {path.name: digest(path) for path in sorted(scratch.glob("*.png"))}
        report.update(passed=True, fullIdeProcessRestart=True, projectAndPluginDisposal=True,
                      cleanProfileInstall=True, realAdapterInvoked=False, developerFilesHeadAndIndexUnchanged=True)
    except CheckFailed as failure:
        report["failure"] = str(failure)
        report["failedStep"] = step
    except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError):
        report["failure"] = "Desktop acceptance incomplete; inspect local phase logs"
        report["failedStep"] = step
    # Keep reviewable evidence alongside the report for CI artifact retention.
    # The copied history/profile and compiler output are local scratch only.
    evidence = args.output.parent / scratch.name
    evidence.mkdir()
    for path in scratch.iterdir():
        if path.is_file() and path.suffix in (".json", ".png", ".log"):
            shutil.copy2(path, evidence / path.name)
    report["evidenceDirectory"] = str(evidence)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("Evidence:", args.output, "Passed:", report["passed"], flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", required=True, type=Path)
    parser.add_argument("--ide-home", type=Path, help="Defaults to the pinned host IDE in the Gradle cache")
    parser.add_argument("--java-home", type=Path, help="Defaults to JAVA_HOME (JDK 21)")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--reuse-session", type=Path)
    sys.exit(main(parser.parse_args()))
