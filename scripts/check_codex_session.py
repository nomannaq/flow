#!/usr/bin/env python3
"""Opt-in B10 engine/worktree/ACP/history acceptance, offline fixture by default.

Scratch repos are deliberately retained, including on failure. No user repo or
agent configuration is changed. Evidence excludes transcripts and credentials.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


class Engine:
    def __init__(self, executable, project, data):
        self.epoch = None
        self.serial = 0
        self.lines = queue.Queue(maxsize=64)
        self.process = subprocess.Popen(
            [str(executable), "--project-root", str(project), "--data-root", str(data)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        threading.Thread(target=self.read, daemon=True).start()
        try:
            hello = self.call("engine.initialize", {"protocolVersion": 1, "clientName": "B10 acceptance", "clientVersion": "1", "projectId": "acceptance"}, envelope=True)
            self.epoch = hello["epoch"]
        except Exception:
            self.process.stdin.close()
            self.process.wait(timeout=12)
            raise

    def read(self):
        try:
            while True:
                line = self.process.stdout.readline(1024 * 1024 + 1)
                if not line or len(line) > 1024 * 1024:
                    break
                self.lines.put(json.loads(line), timeout=1)
        except Exception:
            pass
        finally:
            try:
                self.lines.put(None, timeout=1)
            except queue.Full:
                pass

    def call(self, method, params=None, envelope=False):
        self.serial += 1
        self.process.stdin.write((json.dumps({"jsonrpc": "2.0", "id": self.serial, "epoch": self.epoch, "method": method, "params": params or {}}) + "\n").encode())
        self.process.stdin.flush()
        reply = self.lines.get(timeout=35)
        require(reply is not None and reply.get("id") == self.serial and "error" not in reply, "Engine request failed: " + method)
        return reply if envelope else reply["result"]

    def wait_turn(self, session, seconds):
        deadline = time.monotonic() + seconds
        answered = set()
        while time.monotonic() < deadline:
            view = self.call("sessions.active")
            require(view["status"] not in ("failed", "closed", "authenticationRequired"), "Task could not complete; check native login/model using the IDE")
            for permission in view["permissions"]:
                if permission["requestId"] in answered:
                    continue
                option = next((o["id"] for o in permission["options"] if o.get("allowOnce") is True), None)
                require(option is not None, "No one-time approval option advertised")
                self.call("permissions.respond", {"sessionId": session, "requestId": permission["requestId"], "optionId": option})
                answered.add(permission["requestId"])
            if view["status"] == "ready":
                return view
            time.sleep(0.1)
        raise CheckFailed("Turn timed out; retained workspace needs inspection")

    def pages(self, method, params):
        after = None
        while True:
            page = self.call(method, {**params, "after": after})
            yield from page["items"]
            after = page["next"]
            if after is None:
                break

    def shutdown(self):
        if self.process.poll() is None:
            try:
                self.call("engine.shutdown")
            finally:
                self.process.stdin.close()
                self.process.wait(timeout=12)
        require(self.process.poll() == 0, "Engine shutdown unverified; scratch data retained")


def run(args, evidence):
    executable = Path(args.engine).resolve()
    require(executable.is_file(), "Build flow-engine first")
    if args.adapter:
        adapter = Path(args.adapter)
        require(adapter.is_absolute() and adapter.is_file() and os.access(adapter, os.X_OK), "Select an absolute executable adapter path")
        require(args.allow_once and args.sha256, "Real mode requires --allow-once and --sha256")
        checksum = hashlib.sha256()
        with adapter.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                checksum.update(block)
        digest = checksum.hexdigest()
        require(digest == args.sha256.lower(), "Adapter checksum mismatch")
        arguments = args.adapter_arg
        evidence.update(mode="real", adapterSha256=digest)
    else:
        require(not args.adapter_arg and not args.sha256, "Adapter arguments require --adapter")
        adapter = Path(sys.executable).resolve()
        arguments = [str(ROOT / "scripts/spikes/fixture_agent.py"), "--heartbeat"]
        evidence["mode"] = "fixture"
    scratch = Path(tempfile.mkdtemp(prefix="flow-b10-"))
    evidence["scratch"] = str(scratch)
    project, data = scratch / "project", scratch / "data"
    project.mkdir()
    data.mkdir()
    (project / "README.md").write_text("Disposable Flow acceptance repository.\n")
    for command in (["init", "--quiet"], ["add", "README.md"], ["-c", "user.name=Flow Acceptance", "-c", "user.email=flow@example.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Baseline"]):
        git_env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        git_env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null", GIT_TERMINAL_PROMPT="0")
        subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "-c", "init.templateDir=", "-c", "core.autocrlf=false", "-C", str(project), *command], check=True, env=git_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    main_files = {p.relative_to(project): p.read_bytes() for p in project.rglob("*") if p.is_file() and ".git" not in p.relative_to(project).parts}
    main_index = (project / ".git/index").read_bytes()
    engine = Engine(executable, project, data)
    try:
        commit = engine.call("sessions.baseline")["commit"]
        filename = "flow-b10-test.txt" if args.adapter else "probe.txt"
        expected = ["version 1\n", "version 2\n"] if args.adapter else ["flow-probe-1\n", "flow-probe-2\n"]
        session = engine.call("sessions.start", {"executable": str(adapter), "arguments": arguments, "commit": commit, "prompt": 'Create flow-b10-test.txt containing exactly "version 1" followed by a newline. Do not edit other files.'})["sessionId"]
        view = engine.wait_turn(session, args.timeout_seconds)
        workspace = Path(view["workspace"])
        require((workspace / filename).read_bytes() == expected[0].encode(), "First workspace effect differs from requested bytes")
        engine.call("sessions.prompt", {"sessionId": session, "prompt": 'Change flow-b10-test.txt to contain exactly "version 2" followed by a newline. Do not edit other files.'})
        view = engine.wait_turn(session, args.timeout_seconds)
        require((workspace / filename).read_bytes() == expected[1].encode(), "Second workspace effect differs from requested bytes")
        current_files = {p.relative_to(project): p.read_bytes() for p in project.rglob("*") if p.is_file() and ".git" not in p.relative_to(project).parts}
        require(main_files == current_files and (project / ".git/index").read_bytes() == main_index and engine.call("sessions.baseline")["commit"] == commit, "Main project, index or HEAD was modified")
        diffs = []
        operations = list(engine.pages("history.operations", {"sessionId": session}))
        for operation in operations:
            params = {"sessionId": session, "operationId": operation["id"]}
            for file in engine.pages("history.files", params):
                if file["newPath"] == filename:
                    diff = engine.call("history.diff", {**params, "fileIndex": file["index"]})
                    diffs.append((diff["before"], diff["after"]))
        require(("", expected[0]) in diffs and (expected[0], expected[1]) in diffs, "Historical transitions missing or incorrect")
        require(view.get("recordedOperations") == len(operations), "Operation count disagrees with durable history")
        evidence.update(sessionId=session, operations=len(operations), historicalTransitions=True, mainProjectUnchanged=True, reportedAgent=view["agentInfo"].get("name"), reportedVersion=view["agentInfo"].get("version"))
        engine.call("sessions.close", {"sessionId": session})
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            closed = engine.call("sessions.active")
            if closed["status"] in ("closed", "failed", "interrupted"):
                break
            time.sleep(0.1)
        evidence["shutdownVerified"] = closed["status"] == "closed"
    finally:
        engine.shutdown()
    inspect = subprocess.run([str(executable), "--history", "inspect", "--project-root", str(project), "--data-root", str(data)], check=True, capture_output=True, timeout=30)
    report = json.loads(inspect.stdout)
    require(all(s["worktree"] == "matches_checkpoint" for s in report["sessions"]), "Worktree differs from recorded checkpoint after shutdown")
    evidence["manifestMatches"] = True
    require(evidence["shutdownVerified"], "Adapter shutdown unverified; explicit history recovery required")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", default=str(ROOT / "target/debug/flow-engine"))
    parser.add_argument("--adapter")
    parser.add_argument("--adapter-arg", action="append", default=[])
    parser.add_argument("--sha256")
    parser.add_argument("--allow-once", action="store_true")
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    evidence = {"passed": False}
    try:
        require(1 <= args.timeout_seconds <= 1800, "Timeout must be between 1 and 1800 seconds")
        run(args, evidence)
        evidence["passed"] = True
    except CheckFailed as failure:
        evidence["failure"] = str(failure)
    except Exception:
        # Never print adapter output, raw RPC data, arguments or exception payloads.
        evidence["failure"] = "Acceptance check failed; scratch repository retained for local inspection"
    args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    print("Evidence:", args.output)
    print("Passed" if evidence["passed"] else evidence["failure"])
    sys.exit(0 if evidence["passed"] else 1)
