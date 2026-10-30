#!/usr/bin/env python3
"""Offline connect-only benchmark; uses a temporary repository and no model calls."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import time

from check_codex_session import Engine, require


def run(engine_path, iterations, files):
    binary = Path(engine_path).resolve()
    samples = []
    with tempfile.TemporaryDirectory(prefix="flow connection latency ") as directory:
        root = Path(directory)
        project = root / "project"
        project.mkdir()
        for number in range(files):
            (project / f"module_{number}.rs").write_text("\n".join(f"pub fn symbol_{number}_{item}() -> usize {{ {item} }}" for item in range(32)))
        git = ["git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-c", "user.name=Flow fixture", "-c", "user.email=fixture@example.invalid"]
        for command in [["init", "--initial-branch=main"], ["add", "."], ["commit", "-m", "Fixture baseline"]]:
            subprocess.run(git + command, cwd=project, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        data = root / "history"
        data.mkdir()
        fixture = Path(__file__).resolve().parent / "spikes/fixture_agent.py"
        engine = Engine(binary, project, data)
        try:
            commit = engine.call("sessions.baseline")["commit"]
            for iteration in range(iterations):
                started = time.monotonic()
                session = engine.call("sessions.start", {"executable": sys.executable, "arguments": [str(fixture), "--claude", "--reject-mcp", "--configuration"], "adapterProfile": "claude", "commit": commit, "prompt": "", "connectOnly": True})["sessionId"]
                while True:
                    view = engine.call("sessions.active")
                    require(view["status"] not in {"failed", "authenticationRequired"}, "Fixture connection failed")
                    require(time.monotonic() - started < 30, "Connection deadline exceeded")
                    if view["status"] == "ready":
                        break
                    time.sleep(0.01)
                require(not any(event["kind"] == "turn.started" for event in view["activity"]), "Connect sent inference")
                samples.append({"connectMs": round((time.monotonic() - started) * 1000), "startupTimingsMs": view.get("startupTimingsMs", {})})
                print(f"sample {iteration+1}: {samples[-1]['connectMs']} ms", file=sys.stderr)
                engine.call("sessions.close", {"sessionId": session})
                deadline = time.monotonic() + 15
                while engine.call("sessions.active")["status"] != "closed":
                    require(time.monotonic() < deadline, "Fixture shutdown deadline exceeded")
                    time.sleep(0.01)
                # Closed is published immediately before the task releases its lease.
                time.sleep(0.05)
        finally:
            engine.shutdown()
    return {"engineSha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "files": files, "inferenceRequests": 0, "samples": samples, "medianConnectMs": statistics.median(sample["connectMs"] for sample in samples)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", default="target/debug/flow-engine")
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--files", type=int, default=256)
    parser.add_argument("--output")
    args = parser.parse_args()
    require(1 <= args.iterations <= 20 and 1 <= args.files <= 1024, "Benchmark bounds exceeded")
    content = json.dumps(run(args.engine, args.iterations, args.files), indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(content)
    print(content, end="")
