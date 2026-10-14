import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time


parser = argparse.ArgumentParser(description="Run B01 ACP experiments and retain redacted evidence.")
parser.add_argument("--adapter", type=Path, help="Run real-adapter checks instead of the fixture")
parser.add_argument("--sha256", help="Required expected binary digest with --adapter")
parser.add_argument("--adapter-arg", action="append", default=[])
parser.add_argument("--allow-once", action="store_true")
parser.add_argument("--auth-method", help="Explicit authentication method; may require interactive login")
parser.add_argument("--timeout-seconds", type=int, default=30)
parser.add_argument("--output", type=Path, help="Write reports and snapshots to this JSON file")
options = parser.parse_args()
if not 1 <= options.timeout_seconds <= 120:
    parser.error("timeout must be between 1 and 120 seconds")
if options.adapter and not options.sha256:
    parser.error("--adapter requires --sha256")
root = Path(__file__).resolve().parents[2]
fixture = Path(__file__).with_name("fixture_agent.py")
subprocess.run(["cargo", "build", "-p", "runtime", "--example", "acp_probe", "--locked"], cwd=root, check=True)
metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--no-deps", "--format-version", "1"], cwd=root))
binary = Path(metadata["target_directory"]) / "debug/examples/acp_probe"
evidence = {"platform": platform.system() + "-" + platform.machine(), "suite": "real" if options.adapter else "fixture", "experiments": {}}
evidence["recordedAt"] = datetime.now(timezone.utc).isoformat()
evidence["sourceSha256"] = {
    name: hashlib.sha256((root / name).read_bytes()).hexdigest()
    for name in ["Cargo.lock", "crates/runtime/examples/acp_probe.rs", "scripts/spikes/fixture_agent.py", "scripts/spikes/check_acp_probe.py"]
}
if options.adapter:
    adapter = options.adapter.resolve(strict=True)
    with adapter.open("rb") as executable:
        digest = hashlib.file_digest(executable, "sha256").hexdigest()
    if digest != options.sha256.lower():
        parser.error("adapter SHA-256 does not match; no adapter was launched")
    evidence["adapterSha256"] = digest
    command = [str(adapter), *options.adapter_arg]
else:
    command = [sys.executable, str(fixture)]


def run(label, mode, flags=(), fixture_flags=(), expected="passed"):
    environment = os.environ.copy()
    environment["FLOW_PROBE_SECRET"] = "private-fixture-token"
    completed = subprocess.run(
        [str(binary), mode, "--timeout-seconds", str(options.timeout_seconds), *flags, "--", *command, *fixture_flags],
        capture_output=True, text=True, timeout=options.timeout_seconds + 15, check=False, env=environment,
    )
    if not completed.stdout.strip():
        raise AssertionError(f"{label}: no report (exit {completed.returncode})")
    report_path = Path(completed.stdout.strip())
    report_text = report_path.read_text()
    assert "private-fixture-token" not in report_text
    report = json.loads(report_text)
    evidence["experiments"][label] = {
        "report": report,
        "checkpointContents": {path.name: path.read_text() for path in sorted(report_path.parent.glob("checkpoint-*.txt"))},
    }
    assert (completed.returncode == 0) == (report["status"] == "passed")
    assert report["parentDirectoryUnchanged"]
    if not options.adapter:
        pids = json.loads((report_path.parent / "workspace/fixture-pids.json").read_text())
        for process_id in pids:
            deadline = time.monotonic() + 3
            while True:
                try:
                    os.kill(process_id, 0)
                except ProcessLookupError:
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError(f"SDK left fixture process {process_id} running")
                time.sleep(0.05)
        evidence["experiments"][label]["processCleanup"] = "verified"
    reason = f" ({report['failureReason']})" if report.get("failureReason") else ""
    if report.get("errorCategory"):
        reason += f" [{report['errorCategory']}]"
    if http_status := (report.get("httpError") or {}).get("status"):
        reason += f" HTTP {http_status}"
    print(f"{label}: {report['status']}{reason}; {report_path}", flush=True)
    if expected is not None:
        assert report["status"] == expected, label
    return report_path, report


def fixture_suite():
    run("initialize", "inspect")
    run("authenticate", "auth", ("--auth-method", "fixture"))
    run("authentication-rejected", "auth", ("--auth-method", "fixture"), ("--reject-auth",), "failed")
    run("session-needs-auth", "session", fixture_flags=("--needs-auth",), expected="failed")
    _, report = run("prompt-error-redaction", "edit", fixture_flags=("--prompt-error",), expected="failed")
    assert report["errorCategory"] == "authentication"
    assert report["httpError"]["status"] == 401
    assert report["httpError"]["signals"] == ["unauthorized"]
    report_path, report = run("two-edits", "edit", ("--allow-once", "--auth-method", "fixture"), ("--needs-auth",))
    events = report["events"]
    assert sum(event["event"] == "permission.requested" for event in events) == 2
    assert all(event["decision"] == "allow_once" for event in events if event["event"] == "permission.requested")
    assert sum(event["event"] == "checkpoint" for event in events) == 3
    for sequence in range(3):
        assert (report_path.parent / f"checkpoint-{sequence}.txt").read_bytes() == f"flow-probe-{sequence}\n".encode()
    assert any(event.get("kind") == "agent_message_chunk" for event in events)
    assert any(event.get("kind") == "tool_call_update" for event in events)
    _, report = run("permission-denied", "edit", expected="failed")
    assert any(event.get("decision") == "cancelled" for event in report["events"])
    run("incorrect-content", "edit", ("--allow-once",), ("--bad-checkpoint",), "failed")
    run("shell-edits", "shell", ("--allow-once",))
    _, report = run("background-writes", "background", ("--allow-once",))
    assert any(event.get("changedAfterTurn") is True for event in report["events"])
    assert [event["snapshot"]["sequence"] for event in report["events"] if event["event"] == "checkpoint"] == [0, 1, 2]
    _, report = run("session-cancel", "cancel")
    assert any(event["event"] == "session.cancel.acknowledged" for event in report["events"])
    run("cancel-ignored", "cancel", ("--timeout-seconds", "3"), ("--never-cancel",), "timed_out")
    run("crash", "inspect", fixture_flags=("--crash",), expected="failed")
    report_path, report = run("partial-write-crash", "edit", ("--allow-once",), ("--partial-crash",), "failed")
    assert (report_path.parent / "workspace/probe.txt").read_bytes() == b"partial"
    assert (report_path.parent / "checkpoint-0.txt").read_bytes() == b"flow-probe-0\n"
    assert not (report_path.parent / "checkpoint-1.txt").exists()
    _, report = run("worker-panic", "session", ("--timeout-seconds", "20"), ("--worker-panic",), "failed")
    assert report["failureReason"] == "adapter_panic"
    assert report["elapsedMs"] < 10000, "worker panic must fail before the request timeout"
    run("timeout", "inspect", ("--timeout-seconds", "2"), ("--hang",), "timed_out")
    run("wrong-version", "inspect", fixture_flags=("--wrong-version",), expected="failed")
    _, report = run("stderr-redaction", "inspect", fixture_flags=("--stderr",))
    assert any(event.get("category") == "permission_denied" for event in report["events"])


def real_suite():
    _, report = run("initialize", "inspect", expected=None)
    assert report["status"] == "passed", "Real initialization failed; remaining cases not run"
    flags = ["--allow-once"] if options.allow_once else []
    if options.auth_method:
        flags.extend(["--auth-method", options.auth_method])
        _, report = run("authenticate", "auth", flags, expected=None)
        assert report["status"] == "passed", "Real authentication failed; remaining cases not run"
    _, report = run("session", "session", flags, expected=None)
    assert report["status"] == "passed", "Real session creation failed; remaining cases not run"
    failures = []
    for mode in ["edit", "shell", "background", "cancel"]:
        _, report = run(mode, mode, flags, expected=None)
        if report["status"] != "passed":
            failures.append(mode)
    assert not failures, f"Unproven real-adapter cases: {', '.join(failures)}"


try:
    if options.adapter:
        real_suite()
    else:
        fixture_suite()
    evidence["suitePassed"] = True
finally:
    evidence.setdefault("suitePassed", False)
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(evidence, indent=2) + "\n")
        print(f"Evidence: {options.output}")
print("All selected experiments passed; fixture results do not substitute for real-adapter evidence.")
