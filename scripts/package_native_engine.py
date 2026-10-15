#!/usr/bin/env python3
"""Stage the one supported native payload for IntelliJ's prepareSandbox task.

Python 3.11+, Cargo and Apple's command-line tools are build prerequisites only.
This creates an internal smoke distribution, not a signed/notarized release.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]
TARGET = "aarch64-apple-darwin"
OUTPUT = ROOT / "integrations/jetbrains/build/native-engine"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(*command, capture=False, env=None):
    return subprocess.run(command, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, env=env).stdout


def dependency_inventory(metadata, output):
    """Inventory the engine's target-filtered normal/build closure, not test tools."""
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    pending = [p["id"] for p in packages.values() if p["name"] == "engine" and p["id"] in metadata["workspace_members"]]
    selected = set()
    while pending:
        package = pending.pop()
        if package in selected:
            continue
        selected.add(package)
        for dependency in nodes[package]["deps"]:
            if any(kind["kind"] != "dev" for kind in dependency["dep_kinds"]):
                pending.append(dependency["pkg"])
    inventory = []
    for package in sorted((packages[p] for p in selected), key=lambda p: (p["name"], p["version"])):
        notices = []
        directory = Path(package["manifest_path"]).parent
        candidates = [p for p in directory.iterdir() if p.name.upper().startswith(("LICENSE", "COPYING", "NOTICE", "UNLICENSE")) and p.is_file()]
        if package.get("license_file"):
            candidates.append(directory / package["license_file"])
        for source in sorted(set(candidates)):
            if not source.is_file() or source.is_symlink():
                raise ValueError("Missing or redirected dependency license file")
            name = f"licenses/{package['name']}-{package['version']}/{source.name}"
            destination = output / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            notices.append(name)
        inventory.append({"name": package["name"], "version": package["version"],
                          "license": package["license"], "source": package["source"] or "Flow workspace",
                          "noticeFiles": notices})
    return {"schemaVersion": 1, "target": TARGET, "scope": "Engine normal/build dependencies; declared licenses and available notice files. Manual redistribution review still required.",
            "flowLicense": "Not declared; internal smoke distribution only", "packages": inventory,
            "hostProvided": ["IntelliJ Platform 253.33813.55", "Kotlin and kotlinx.coroutines supplied by IntelliJ", "macOS system libraries/frameworks", "System Git (not bundled)"],
            "buildTools": {"Rust": run("rustc", "--version", capture=True).strip(), "Gradle": "9.0.0", "IntelliJPlatformGradlePlugin": "2.19.0", "KotlinCompiler": "2.2.20"}}


def stage(version, offline=False, signing_identity=None):
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise ValueError("B28 builds only on macOS Apple Silicon; other targets need platform acceptance")
    cargo = tomllib.loads((ROOT / "Cargo.toml").read_text())
    if version != cargo["workspace"]["package"]["version"]:
        raise ValueError("Plugin and engine versions must match")
    flags = ["--locked"] + (["--offline"] if offline else [])
    environment = os.environ.copy()
    environment["MACOSX_DEPLOYMENT_TARGET"] = "13.0"
    run("cargo", "build", "-p", "engine", "--bin", "flow-engine", "--release", "--target", TARGET,
        "--target-dir", str(ROOT / "target"), *flags, env=environment)
    metadata = json.loads(run("cargo", "metadata", "--format-version", "1", "--filter-platform", TARGET, *flags, capture=True))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-engine-", dir=OUTPUT.parent) as temporary:
        payload = Path(temporary)
        binary = payload / "bin" / TARGET / "flow-engine"
        binary.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "target" / TARGET / "release/flow-engine", binary)
        binary.chmod(0o755)
        with binary.open("rb") as source:
            if struct.unpack("<II", source.read(8)) != (0xFEEDFACF, 0x0100000C):
                raise ValueError("Engine is not an arm64 Mach-O executable")
        linked = run("/usr/bin/otool", "-L", str(binary), capture=True).splitlines()[1:]
        libraries = [line.strip().split(" (", 1)[0] for line in linked]
        if not libraries or any(not p.startswith(("/usr/lib/", "/System/Library/")) for p in libraries):
            raise ValueError("Engine depends on a non-system dynamic library")
        # Hash only after signing: changing a Mach-O signature changes its bytes.
        if signing_identity:
            run("/usr/bin/codesign", "--force", "--options", "runtime", "--timestamp", "--sign", signing_identity, str(binary))
        else:
            run("/usr/bin/codesign", "--force", "--sign", "-", str(binary))
        run("/usr/bin/codesign", "--verify", "--strict", str(binary))
        inventory = dependency_inventory(metadata, payload)
        (payload / "dependency-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
        protocol = (ROOT / "crates/protocol/src/lib.rs").read_text()
        if "pub const PROTOCOL_VERSION: u32 = 1;" not in protocol:
            raise ValueError("Review the packaged protocol manifest after a protocol change")
        manifest = {"schemaVersion": 1, "engineVersion": version, "pluginVersion": version, "protocolVersion": 1,
                    "target": TARGET, "ideBuild": "253.33813.55", "minimumMacOS": "13.0",
                    "enginePath": f"bin/{TARGET}/flow-engine", "sha256": digest(binary),
                    "cargoLockSha256": digest(ROOT / "Cargo.lock"), "inventorySha256": digest(payload / "dependency-inventory.json"),
                    "dynamicLibraries": libraries, "signing": "developer-id-notarization-pending" if signing_identity else "ad-hoc-internal-smoke"}
        (payload / "distribution.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # Only replace this script's fixed generated directory, never caller-supplied paths.
        if OUTPUT.is_symlink():
            raise ValueError("Refusing a redirected staging directory")
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        shutil.copytree(payload, OUTPUT)
    print(f"Staged {TARGET} engine {version}: {OUTPUT}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--signing-identity", help="Explicit Apple Developer ID identity; no identity is selected automatically")
    args = parser.parse_args()
    stage(args.version, args.offline, args.signing_identity)
