import json
import os
from pathlib import Path
import subprocess
import sys
import time


def send(message):
    print(json.dumps({"jsonrpc": "2.0", **message}), flush=True)


def respond(request_id, result):
    send({"id": request_id, "result": result})


def update(payload):
    send({"method": "session/update", "params": {"sessionId": "fixture", "update": payload}})


child_code = "import time; time.sleep(180)"
if "--heartbeat" in sys.argv:
    child_code = "import time,pathlib\nfor i in range(3600):\n pathlib.Path('fixture-heartbeat').write_text(str(i)); time.sleep(0.05)"
child = subprocess.Popen([sys.executable, "-c", child_code])
Path("fixture-pids.json").write_text(json.dumps([os.getpid(), child.pid]))
if "FLOW_PROBE_SECRET" in os.environ:
    raise RuntimeError("launcher inherited an unapproved environment variable")
workspace = None
pending_edit = None
pending_cancel = None
sequence = 0
pending_prompt = ""
authenticated = False

for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    request_id = message.get("id")
    if method == "initialize":
        if "--stderr" in sys.argv:
            print("Permission denied: private-fixture-token", file=sys.stderr, flush=True)
            print("Previous adapter panicked; this is an ordinary log message", file=sys.stderr, flush=True)
        if "--crash" in sys.argv:
            sys.exit(7)
        if "--hang" in sys.argv:
            time.sleep(180)
        respond(request_id, {
            "protocolVersion": 99 if "--wrong-version" in sys.argv else 1,
            "agentCapabilities": {},
            "agentInfo": {"name": "flow-fixture", "version": "1"},
            "authMethods": [{"id": "fixture", "name": "Fixture authentication"}],
        })
    elif method == "authenticate":
        if "--reject-auth" in sys.argv:
            send({"id": request_id, "error": {"code": -32000, "message": "Authentication required"}})
        else:
            authenticated = True
            respond(request_id, {})
    elif method == "session/new":
        if "--worker-panic" in sys.argv:
            print(f"thread 'tokio-runtime-worker' ({os.getpid()}) panicked at system-configuration/src/dynamic_store.rs:154:1:", file=sys.stderr, flush=True)
            print("Attempted to create a NULL object: private-fixture-token", file=sys.stderr, flush=True)
            time.sleep(180)
        if "--needs-auth" in sys.argv and not authenticated:
            send({"id": request_id, "error": {"code": -32000, "message": "Authentication required"}})
            continue
        workspace = Path(message["params"]["cwd"])
        if workspace.resolve() != Path.cwd().resolve():
            raise RuntimeError("session and process working directories differ")
        respond(request_id, {"sessionId": "fixture"})
    elif method == "session/prompt":
        if "--prompt-error" in sys.argv:
            send({"id": request_id, "error": {
                "code": -32603, "message": "Internal error", "data": {
                    "details": "stream disconnected: unexpected status 401 Unauthorized private-fixture-token",
                },
            }})
            continue
        prompt = message["params"]["prompt"][0]["text"]
        update({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "fixture output"}})
        if "waits 30 seconds" in prompt:
            pending_cancel = request_id
            continue
        sequence += 1
        pending_edit = request_id
        pending_prompt = prompt
        update({"sessionUpdate": "tool_call", "toolCallId": str(sequence), "title": "Fixture edit", "kind": "edit", "status": "pending"})
        send({"id": "permission", "method": "session/request_permission", "params": {
            "sessionId": "fixture",
            "toolCall": {"toolCallId": str(sequence), "title": "Fixture edit"},
            "options": [
                {"optionId": "always", "name": "Always", "kind": "allow_always"},
                {"optionId": "once", "name": "Once", "kind": "allow_once"},
            ],
        }})
    elif request_id == "permission" and pending_edit is not None:
        outcome = message["result"]["outcome"]
        if outcome.get("optionId") == "once":
            if "--partial-crash" in sys.argv:
                # B05: die after a real write, before the completion notification.
                (workspace / "probe.txt").write_bytes(b"partial")
                os._exit(7)
            value = "incorrect" if "--bad-checkpoint" in sys.argv else f"flow-probe-{sequence}"
            if "overlapping writers" in pending_prompt:
                for delay, number in [(1, 1), (2, 2)]:
                    subprocess.Popen(
                        ["/bin/sh", "-c", f"sleep {delay}; printf 'flow-probe-{number}\\n' > writer-{number}.tmp; mv writer-{number}.tmp probe.txt"],
                        cwd=workspace, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    )
            elif "Use a shell command" in pending_prompt:
                subprocess.run(["/bin/sh", "-c", 'printf "%s\\n" "$1" > probe.txt', "fixture", value], cwd=workspace, check=True)
            else:
                (workspace / "probe.txt").write_text(value + "\n")
            update({"sessionUpdate": "tool_call_update", "toolCallId": str(sequence), "status": "completed"})
        elif outcome["outcome"] != "cancelled":
            raise RuntimeError("probe must never approve allow_always")
        respond(pending_edit, {"stopReason": "end_turn"})
        pending_edit = None
    elif method == "session/cancel" and pending_cancel is not None:
        if "--never-cancel" in sys.argv:
            continue
        respond(pending_cancel, {"stopReason": "cancelled"})
        pending_cancel = None
    elif method and request_id is not None:
        send({"id": request_id, "error": {"code": -32601, "message": "Unknown fixture method"}})
