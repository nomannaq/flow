#!/usr/bin/env python3
"""Build an unchanged committed host ZIP outside the checkout for upgrade tests.

Reads git archive; never checks out, commits, pushes or edits tracked files.
The archived Gradle build uses the same strict dependency verification as HEAD.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

from check_codex_session import require
from check_release_acceptance import digest
from native_platform import host_target

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "e2735abdaf08d013a91b131077d8153daecfad5a"


def main(args):
    require(re.fullmatch(r"[a-f0-9]{40}", args.revision) is not None, "Use an exact committed baseline revision")
    scratch = Path(tempfile.mkdtemp(prefix="Flow previous release "))
    source = scratch / "source"
    source.mkdir()
    archive = scratch / "source.tar"
    subprocess.run(["git", "archive", "--format=tar", "--output", str(archive), args.revision], cwd=ROOT, check=True, timeout=60)
    with tarfile.open(archive) as tar:
        tar.extractall(source, filter="data")
    ide = source / "integrations/jetbrains"
    if args.offline:
        # Gradle's IDE catalog is a project-local cache, separate from downloaded
        # dependency bytes. Reuse metadata only, never the IDE's sandbox/profile.
        for name in ("productReleases", "localPlatformArtifacts"):
            cached = ROOT / "integrations/jetbrains/.intellijPlatform" / name
            if cached.is_dir():
                shutil.copytree(cached, ide / ".intellijPlatform" / name, ignore=shutil.ignore_patterns("*.lock"))
    command = [str(ide / ("gradlew.bat" if os.name == "nt" else "gradlew")), "buildPlugin",
               "--dependency-verification=strict", "--console=plain", "--no-watch-fs"]
    if args.offline:
        command.append("--offline")
    with (scratch / "build.log").open("wb") as log:
        subprocess.run(command, cwd=ide, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
    version = next(line.split("=", 1)[1].strip() for line in (ide / "gradle.properties").read_text().splitlines() if line.startswith("version="))
    package = ide / "build/distributions" / f"flow-jetbrains-{version}-{host_target()}.zip"
    require(package.is_file(), "Committed baseline did not produce the native host ZIP")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / package.name
    require(not destination.exists(), "Choose a fresh baseline output directory")
    shutil.copy2(package, destination)
    (args.output / "baseline.json").write_text(json.dumps({"revision": args.revision, "pluginVersion": version,
        "archive": destination.name, "sha256": digest(destination), "buildLog": str(scratch / "build.log")}, indent=2) + "\n")
    print(destination, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", default=BASELINE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    main(parser.parse_args())
