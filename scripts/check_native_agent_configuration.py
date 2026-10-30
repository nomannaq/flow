#!/usr/bin/env python3
"""Opt-in native login/configuration probe. Never sends sessions.prompt or a task.

Creates a scratch Git repository, connects using the adapter's own credential
store, reads advertised settings, then closes the session and engine. It does
not read, copy, display or modify native credentials or global configuration.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from check_codex_session import CheckFailed, Engine, require


def run(args):
    with tempfile.TemporaryDirectory(prefix="flow native configuration ") as directory:
        root = Path(directory)
        project = root / "project"
        project.mkdir()
        git = ["git", "-c", "core.hooksPath=" + ("NUL" if os.name == "nt" else "/dev/null"), "-c", "commit.gpgSign=false", "-c", "user.name=Flow probe", "-c", "user.email=probe@example.invalid"]
        for command in [["init", "--initial-branch=main"], ["commit", "--allow-empty", "-m", "Scratch configuration probe"]]:
            subprocess.run(git + command, cwd=project, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        data = root / "history"
        data.mkdir()
        executable = Path(args.engine).resolve()
        with executable.open("rb") as binary:
            engine_sha256 = hashlib.file_digest(binary, "sha256").hexdigest()
        engine = Engine(executable, project, data)
        try:
            commit = engine.call("sessions.baseline")["commit"]
            started = time.monotonic()
            session = engine.call("sessions.start", {"executable": str(Path(args.adapter).resolve()), "arguments": args.argument, "adapterProfile": args.profile, "commit": commit, "prompt": "", "connectOnly": True})["sessionId"]
            deadline = time.monotonic() + 210
            while True:
                view = engine.call("sessions.active")
                if view["status"] in {"ready", "failed", "authenticationRequired", "closed"}:
                    break
                require(time.monotonic() < deadline, "Native connection timed out")
                time.sleep(0.1)
            require(not any(item["kind"] == "turn.started" for item in view["activity"]), "Connect unexpectedly started a turn")
            result = {
                "profile": args.profile,
                "connectWallMs": round((time.monotonic() - started) * 1000),
                "startupTimingsMs": view.get("startupTimingsMs", {}),
                "engineSha256": engine_sha256,
                "status": view["status"],
                "adapterVersion": view["agentInfo"].get("version"),
                "inferenceRequests": 0,
                "configuration": [{"id": item["id"], "name": item["name"], "kind": item["kind"], "category": item["category"], "choices": len(item["options"])} for item in view.get("configOptions", [])],
            }
            engine.call("sessions.close", {"sessionId": session})
            return result
        finally:
            engine.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", default="target/debug/flow-engine")
    parser.add_argument("--adapter", required=True)
    parser.add_argument("--argument", action="append", default=[])
    parser.add_argument("--profile", required=True, choices=["codex", "claude", "cursor"])
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        result = run(args)
        content = json.dumps(result, indent=2) + "\n"
        if args.output:
            Path(args.output).write_text(content)
        print(content, end="")
        raise SystemExit(0 if result["status"] == "ready" else 1)
    except (CheckFailed, subprocess.SubprocessError):
        raise SystemExit("Native configuration probe failed; check the installed adapter and native login.")
