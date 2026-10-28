#!/usr/bin/env python3
"""B28 offline package smoke: validate ZIP, install in an owned scratch directory,
then run the bundled engine's health/restart/shutdown IPC. An explicit flag also
exercises the offline ACP fixture; no real model or adapter is launched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
import zipfile

from check_codex_session import Engine, require, run as check_session
from native_platform import host_target, engine_name

MAX_BYTES = 128 * 1024 * 1024
WINDOWS_DEVICES = {"CON", "PRN", "AUX", "NUL", *(f"{prefix}{number}" for prefix in ("COM", "LPT") for number in "123456789¹²³")}


def install(archive, destination, target=None):
    target = target or host_target()
    with zipfile.ZipFile(archive) as bundle:
        members = bundle.infolist()
        require(0 < len(members) <= 4096, "Invalid package entry count")
        total = 0
        names = set()
        roots = set()
        for member in members:
            path = PurePosixPath(member.filename)
            parts = member.filename.rstrip("/").split("/")
            require(not path.is_absolute() and all(p not in ("", ".", "..") for p in parts)
                    and "\\" not in member.filename and ":" not in member.filename, "Unsafe ZIP path")
            require(all(p == p.rstrip(" .") and p.split(".", 1)[0].upper() not in WINDOWS_DEVICES for p in parts), "Unsafe Windows ZIP path")
            require(member.filename.casefold() not in names, "Duplicate ZIP entry")
            names.add(member.filename.casefold())
            roots.add(parts[0])
            kind = stat.S_IFMT(member.external_attr >> 16)
            require(kind in (0, stat.S_IFREG, stat.S_IFDIR), "ZIP contains a symlink or unsupported file type")
            total += member.file_size
            require(0 <= member.file_size <= MAX_BYTES and total <= MAX_BYTES, "Package exceeds extraction budget")
        require(len(roots) == 1, "Package must contain exactly one plugin root")
        for member in members:
            extracted = destination.joinpath(*PurePosixPath(member.filename).parts)
            if member.is_dir():
                extracted.mkdir(parents=True, exist_ok=True)
            else:
                extracted.parent.mkdir(parents=True, exist_ok=True)
                with extracted.open("xb") as output, bundle.open(member) as source:
                    remaining = member.file_size
                    while remaining:
                        block = source.read(min(remaining, 1024 * 1024))
                        require(block, "Truncated ZIP entry")
                        output.write(block)
                        remaining -= len(block)
                    require(not source.read(1), "ZIP entry exceeds declared size")
        root = destination / next(iter(roots))
    manifest_path = root / "distribution.json"
    require(manifest_path.is_file() and manifest_path.stat().st_size <= 65536, "Missing/bounded manifest required")
    manifest = json.loads(manifest_path.read_text())
    require(manifest["schemaVersion"] == 1 and manifest["protocolVersion"] == 1 and manifest["target"] == target, "Unsupported package manifest")
    require(manifest["enginePath"] == f"bin/{target}/{engine_name(target)}", "Unexpected executable selection")
    require(manifest["engineVersion"] == manifest["pluginVersion"], "Version mismatch")
    binary = root / manifest["enginePath"]
    require(binary.is_file() and hashlib.sha256(binary.read_bytes()).hexdigest() == manifest["sha256"], "Engine digest mismatch")
    inventory = root / "dependency-inventory.json"
    require(inventory.is_file() and hashlib.sha256(inventory.read_bytes()).hexdigest() == manifest["inventorySha256"], "Inventory digest mismatch")
    jars = list((root / "lib").glob("*.jar"))
    require(jars, "Plugin JAR missing")
    descriptor = False
    react_assets = False
    for jar in jars:
        with zipfile.ZipFile(jar) as bundle:
            descriptor |= "META-INF/plugin.xml" in bundle.namelist()
            if "webview/chat.js" in bundle.namelist():
                require(len(bundle.read("webview/chat.js")) > 1000, "React bundle is empty")
                require("webview/chat.css" in bundle.namelist(), "React styles missing")
                require("webview/THIRD_PARTY_NOTICES.txt" in bundle.namelist(), "Frontend license notices missing")
                require("prompts/enhance-task.txt" in bundle.namelist(), "Prompt enhancer instructions missing")
                require("prompts/commit-message.txt" in bundle.namelist(), "Commit message instructions missing")
                react_assets = True
    require(descriptor, "Plugin descriptor missing")
    require(react_assets, "Bundled React chat missing")
    if not target.endswith("windows-msvc"):
        binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
    return binary, manifest


def smoke(archive, session_fixture=False):
    with tempfile.TemporaryDirectory(prefix="Flow packaged smoke ") as temporary:
        scratch = Path(temporary)
        installed = scratch / "Installed plugins with spaces"
        installed.mkdir()
        binary, manifest = install(archive, installed)
        project = scratch / "Project outside checkout"
        project.mkdir()
        data = scratch / "Private engine data"
        data.mkdir()
        sentinel = project / "sentinel.txt"
        sentinel.write_text("developer content\n")
        source = project / "lib.rs"
        source.write_text("pub fn packaged_context_probe() {}\n")
        previous = Path.cwd()
        try:
            os.chdir(project)
            for _ in range(2):
                engine = Engine(binary, project, data)
                try:
                    health = engine.call("engine.health")
                    require(health["engineVersion"] == manifest["engineVersion"] and health["protocolVersion"] == manifest["protocolVersion"], "Bundled engine handshake differs from manifest")
                    snapshot = engine.call("state.snapshot")
                    require(snapshot["state"]["sessions"] == 0 and snapshot["state"]["operations"] == 0, "Fresh package smoke must have no agent effects")
                    require(engine.call("sessions.active")["sessionId"] is None, "Smoke must not launch an agent")
                    context = engine.call("context.initialize")
                    require(context["files"] == 1 and context["symbols"] == 1, "Bundled engine could not initialize project context")
                    require("packaged_context_probe" in (project / ".flow/map.md").read_text(), "Bundled context map lacks source symbols")
                finally:
                    engine.shutdown()
            require(sentinel.read_text() == "developer content\n", "Project content changed")
            require(source.read_text() == "pub fn packaged_context_probe() {}\n", "Context initialization changed source")
        finally:
            os.chdir(previous)
        evidence = {"target": manifest["target"], "engineVersion": manifest["engineVersion"],
                "engineSha256": manifest["sha256"], "freshStartup": True, "restart": True,
                "shutdown": True, "outsideCheckout": True, "pathsWithSpaces": True, "projectContextInitialized": True,
                "signing": manifest["signing"], "nativeIDEInstall": "pending host check"}
        if session_fixture:
            session = {}
            check_session(argparse.Namespace(engine=str(binary), adapter=None, adapter_arg=[],
                                             sha256=None, allow_once=False, timeout_seconds=120), session)
            evidence["fixtureSession"] = session
        return evidence


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", required=True, type=Path)
    parser.add_argument("--session-fixture", action="store_true", help="Also verify two offline ACP edits, historical diffs and descendant cleanup with the installed engine")
    args = parser.parse_args()
    print(json.dumps(smoke(args.plugin.resolve(), args.session_fixture), indent=2))
