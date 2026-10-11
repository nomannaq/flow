import json
import os
from pathlib import Path
import subprocess
import sys
import time


root = Path(__file__).resolve().parents[2]
binary = root / "target/debug/examples/acp_probe"
fixture = Path(__file__).with_name("fixture_agent.py")
subprocess.run(["cargo", "build", "-p", "runtime", "--example", "acp_probe", "--locked"], cwd=root, check=True)


def run(mode, options=(), fixture_options=(), status="passed"):
    completed = subprocess.run(
        [str(binary), mode, *options, "--", sys.executable, str(fixture), *fixture_options],
        capture_output=True, text=True, timeout=30, check=False,
    )
    if (completed.returncode == 0) != (status == "passed"):
        raise AssertionError(f"Unexpected exit: {completed.returncode}: {completed.stderr}")
    report_path = Path(completed.stdout.strip())
    report = json.loads(report_path.read_text())
    assert report["status"] == status
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
    print(f"{mode}: {report['status']}; child and descendant stopped; {report_path}")
    return report_path, report["events"]


run("inspect")
report_path, events = run("edit", ("--allow-once", "--auth-method", "fixture"))
assert sum(event["event"] == "permission.requested" for event in events) == 2
assert all(event["decision"] == "allow_once" for event in events if event["event"] == "permission.requested")
assert sum(event["event"] == "checkpoint" for event in events) == 3
for sequence in range(3):
    assert (report_path.parent / f"checkpoint-{sequence}.txt").read_bytes() == f"flow-probe-{sequence}\n".encode()
assert any(event.get("kind") == "agent_message_chunk" for event in events)
assert any(event.get("kind") == "tool_call_update" for event in events)
_, events = run("edit", status="failed")
assert any(event.get("decision") == "cancelled" for event in events)
run("edit", ("--allow-once",), ("--bad-checkpoint",), status="failed")
_, events = run("cancel")
assert any(event["event"] == "session.cancel.acknowledged" for event in events)
run("inspect", fixture_options=("--crash",), status="failed")
run("inspect", ("--timeout-seconds", "2"), ("--hang",), status="timed_out")
print("All ACP fixture experiments passed. Real-agent behavior remains a separate gate.")
