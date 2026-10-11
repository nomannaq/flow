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


child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(180)"])
Path("fixture-pids.json").write_text(json.dumps([os.getpid(), child.pid]))
workspace = None
pending_edit = None
pending_cancel = None
sequence = 0

for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    request_id = message.get("id")
    if method == "initialize":
        if "--crash" in sys.argv:
            sys.exit(7)
        if "--hang" in sys.argv:
            time.sleep(180)
        respond(request_id, {
            "protocolVersion": 1,
            "agentCapabilities": {},
            "agentInfo": {"name": "flow-fixture", "version": "1"},
            "authMethods": [{"id": "fixture", "name": "Fixture authentication"}],
        })
    elif method == "authenticate":
        respond(request_id, {})
    elif method == "session/new":
        workspace = Path(message["params"]["cwd"])
        if workspace.resolve() != Path.cwd().resolve():
            raise RuntimeError("session and process working directories differ")
        respond(request_id, {"sessionId": "fixture"})
    elif method == "session/prompt":
        prompt = message["params"]["prompt"][0]["text"]
        update({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "fixture output"}})
        if "waits 30 seconds" in prompt:
            pending_cancel = request_id
            continue
        sequence += 1
        pending_edit = request_id
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
            value = "incorrect" if "--bad-checkpoint" in sys.argv else f"flow-probe-{sequence}"
            (workspace / "probe.txt").write_text(value + "\n")
            update({"sessionUpdate": "tool_call_update", "toolCallId": str(sequence), "status": "completed"})
        elif outcome["outcome"] != "cancelled":
            raise RuntimeError("probe must never approve allow_always")
        respond(pending_edit, {"stopReason": "end_turn"})
        pending_edit = None
    elif method == "session/cancel" and pending_cancel is not None:
        respond(pending_cancel, {"stopReason": "cancelled"})
        pending_cancel = None
    elif method and request_id is not None:
        send({"id": request_id, "error": {"code": -32601, "message": "Unknown fixture method"}})
