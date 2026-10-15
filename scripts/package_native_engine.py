#!/usr/bin/env python3
"""Stage the host's native payload for IntelliJ's prepareSandbox task.

Python 3.11+, Cargo and a native C toolchain are build prerequisites only.
This creates an internal smoke distribution, not a signed/notarized release.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import tomllib
from native_platform import host_target, engine_name

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "integrations/jetbrains/build/native-engine"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(*command, capture=False, env=None):
    return subprocess.run(command, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, env=env).stdout


def inspect_binary(binary, target):
    with binary.open("rb") as source:
        header = source.read(64)
        if target.endswith("apple-darwin"):
            cpu = 0x0100000C if target.startswith("aarch64") else 0x01000007
            if struct.unpack("<II", header[:8]) != (0xFEEDFACF, cpu):
                raise ValueError("Engine Mach-O architecture differs from target")
            linked = run("/usr/bin/otool", "-L", str(binary), capture=True).splitlines()[1:]
            libraries = [line.strip().split(" (", 1)[0] for line in linked]
            if not libraries or any(not p.startswith(("/usr/lib/", "/System/Library/")) for p in libraries):
                raise ValueError("Engine depends on a non-system dynamic library")
            return libraries, {"minimumMacOS": "13.0"}
        if target.endswith("windows-msvc"):
            if header[:2] != b"MZ":
                raise ValueError("Engine is not a PE executable")
            source.seek(struct.unpack_from("<I", header, 60)[0])
            if source.read(6) != b"PE\0\0\x64\x86":
                raise ValueError("Engine is not an x86-64 PE executable")
            linked = run("dumpbin", "/nologo", "/dependents", str(binary), capture=True)
            libraries = re.findall(r"^\s+([\w.-]+\.dll)\s*$", linked, re.MULTILINE | re.IGNORECASE)
            allowed = {"kernel32.dll", "ntdll.dll", "userenv.dll", "ws2_32.dll", "bcrypt.dll", "bcryptprimitives.dll", "advapi32.dll", "shell32.dll", "ole32.dll", "oleaut32.dll", "secur32.dll", "crypt32.dll", "rpcrt4.dll", "psapi.dll", "synchronization.dll"}
            if not libraries or any(p.lower() not in allowed and not p.lower().startswith("api-ms-win-") for p in libraries):
                raise ValueError("Engine depends on a non-system DLL (build with the static MSVC runtime)")
            return libraries, {"minimumWindows": "10", "msvcRuntime": "static"}
        if header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 62:
            raise ValueError("Engine is not a little-endian x86-64 ELF executable")
        linked = run("readelf", "-d", str(binary), capture=True)
        libraries = re.findall(r"\(NEEDED\).*\[([^]]+)\]", linked)
        allowed = {"libc.so.6", "libm.so.6", "libgcc_s.so.1", "libpthread.so.0", "libdl.so.2", "librt.so.1", "ld-linux-x86-64.so.2"}
        if not libraries or any(p not in allowed for p in libraries) or re.search(r"\((?:RPATH|RUNPATH)\)", linked):
            raise ValueError("Engine depends on a non-system shared library or library search path")
        versions = run("readelf", "--version-info", str(binary), capture=True)
        glibc = [tuple(map(int, v.split('.'))) for v in re.findall(r"\bGLIBC_(\d+\.\d+(?:\.\d+)?)\b", versions)]
        if not glibc or max(glibc) > (2, 35):
            raise ValueError("Build Linux packages on the Ubuntu 22.04/glibc 2.35 baseline or older")
        return libraries, {"minimumGlibc": "2.35"}


def dependency_inventory(metadata, output, target):
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
    return {"schemaVersion": 1, "target": target, "scope": "Engine normal/build dependencies; declared licenses and available notice files. Manual redistribution review still required.",
            "flowLicense": "Not declared; internal smoke distribution only", "packages": inventory,
            "hostProvided": ["IntelliJ Platform 253.33813.55", "Kotlin and kotlinx.coroutines supplied by IntelliJ", "Operating system libraries (see distribution.json)", "System Git (not bundled)"],
            "buildTools": {"Rust": run("rustc", "--version", capture=True).strip(), "Gradle": "9.0.0", "IntelliJPlatformGradlePlugin": "2.19.0", "KotlinCompiler": "2.2.20"}}


def stage(version, offline=False, signing_identity=None):
    target = host_target()
    macos = target.endswith("apple-darwin")
    windows = target.endswith("windows-msvc")
    if signing_identity and not macos:
        raise ValueError("--signing-identity is only supported for macOS packages")
    cargo = tomllib.loads((ROOT / "Cargo.toml").read_text())
    if version != cargo["workspace"]["package"]["version"]:
        raise ValueError("Plugin and engine versions must match")
    flags = ["--locked"] + (["--offline"] if offline else [])
    environment = os.environ.copy()
    if macos:
        environment["MACOSX_DEPLOYMENT_TARGET"] = "13.0"
    if windows:
        # Target-specific flags leave host proc macros dynamically linked.
        environment["CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS"] = "-C target-feature=+crt-static"
    run("cargo", "build", "-p", "engine", "--bin", "flow-engine", "--release", "--target", target,
        "--target-dir", str(ROOT / "target"), *flags, env=environment)
    metadata = json.loads(run("cargo", "metadata", "--format-version", "1", "--filter-platform", target, *flags, capture=True))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-engine-", dir=OUTPUT.parent) as temporary:
        payload = Path(temporary)
        binary = payload / "bin" / target / engine_name(target)
        binary.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "target" / target / "release" / engine_name(target), binary)
        binary.chmod(0o755)
        libraries, requirements = inspect_binary(binary, target)
        # Hash only after signing: changing a Mach-O signature changes its bytes.
        if signing_identity:
            run("/usr/bin/codesign", "--force", "--options", "runtime", "--timestamp", "--sign", signing_identity, str(binary))
        elif macos:
            run("/usr/bin/codesign", "--force", "--sign", "-", str(binary))
        if macos:
            run("/usr/bin/codesign", "--verify", "--strict", str(binary))
        inventory = dependency_inventory(metadata, payload, target)
        (payload / "dependency-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")
        protocol = (ROOT / "crates/protocol/src/lib.rs").read_text()
        if "pub const PROTOCOL_VERSION: u32 = 1;" not in protocol:
            raise ValueError("Review the packaged protocol manifest after a protocol change")
        manifest = {"schemaVersion": 1, "engineVersion": version, "pluginVersion": version, "protocolVersion": 1,
                    "target": target, "ideBuild": "253.33813.55", **requirements,
                    "enginePath": f"bin/{target}/{engine_name(target)}", "sha256": digest(binary),
                    "cargoLockSha256": digest(ROOT / "Cargo.lock"), "inventorySha256": digest(payload / "dependency-inventory.json"),
                    "dynamicLibraries": libraries, "signing": "developer-id-notarization-pending" if signing_identity else "ad-hoc-internal-smoke" if macos else "unsigned-internal-smoke"}
        (payload / "distribution.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # Only replace this script's fixed generated directory, never caller-supplied paths.
        if OUTPUT.is_symlink():
            raise ValueError("Refusing a redirected staging directory")
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        shutil.copytree(payload, OUTPUT)
    print(f"Staged {target} engine {version}: {OUTPUT}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--signing-identity", help="Explicit Apple Developer ID identity; no identity is selected automatically")
    args = parser.parse_args()
    stage(args.version, args.offline, args.signing_identity)
