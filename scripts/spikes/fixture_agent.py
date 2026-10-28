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
    # Liveness evidence belongs outside the captured worktree. Otherwise this
    # 20 Hz diagnostic writer can intentionally defeat stable-read capture under
    # load, obscuring the separate background-write test's actual assertions.
    heartbeat = str(Path.cwd().parent / "fixture-heartbeat")
    child_code = f"import time,pathlib\nfor i in range(3600):\n pathlib.Path({heartbeat!r}).write_text(str(i)); time.sleep(0.05)"
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
auth_attempts = 0

configuration = [
    {"id": "model", "name": "Model", "category": "model", "type": "select", "currentValue": "fixture-model", "options": [
        {"value": "fixture-model", "name": "Fixture model", "description": "Offline model for connection tests"},
        {"value": "fixture-fast", "name": "Fixture fast", "description": "Alternate offline model"},
    ]},
    {"id": "effort", "name": "Effort", "category": "thought_level", "type": "select", "currentValue": "high", "options": [
        {"value": "low", "name": "Low"}, {"value": "high", "name": "High"},
    ]},
    {"id": "speed", "name": "Speed", "type": "select", "currentValue": "standard", "options": [
        {"value": "standard", "name": "Standard"}, {"value": "fast", "name": "Fast"},
    ]},
    {"id": "mode", "name": "Permissions", "category": "mode", "type": "select", "currentValue": "ask", "options": [
        {"value": "ask", "name": "Ask for approval", "description": "Ask before commands and writes"},
        {"value": "auto", "name": "Full Auto", "description": "Adapter handles commands and writes automatically"},
    ]},
    {"id": "verbose", "name": "Verbose output", "type": "boolean", "currentValue": False},
]
if "--collaboration-modes" in sys.argv:
    configuration.append({"id": "collaboration_mode", "name": "Collaboration mode", "category": "collaboration_mode", "type": "select", "currentValue": "default", "options": [
        {"value": "default", "name": "Default"}, {"value": "plan", "name": "Plan"},
    ]})

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
            "agentCapabilities": {"promptCapabilities": {"image": True,"audio": True,"embeddedContext": True}} if "--attachments" in sys.argv else ({"promptCapabilities": {"image": True}} if "--images" in sys.argv else {}),
            "agentInfo": {"name": "cursor-agent" if "--cursor" in sys.argv else ("claude-agent-acp" if "--claude" in sys.argv else "flow-fixture"), "version": "1"},
            "authMethods": [{"id": "fixture", "name": "Fixture authentication"}],
        })
    elif method == "authenticate":
        auth_attempts += 1
        if "--reject-auth" in sys.argv or ("--reject-auth-once" in sys.argv and auth_attempts == 1):
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
        if "--reject-mcp" in sys.argv and message["params"].get("mcpServers"):
            send({"id": request_id, "error": {"code": -32602, "message": "fixture refuses context attachment"}})
            continue
        if "--context-tools" in sys.argv:
            from context_probe import probe
            config = message["params"]["mcpServers"][0]
            proof = probe(config)
            proof.update(probe(config, wrong_token=True))
            Path("context-proof.json").write_text(json.dumps(proof))
        workspace = Path(message["params"]["cwd"])
        if not workspace.samefile(Path.cwd()):
            raise RuntimeError("session and process working directories differ")
        if "--conversation-features" in sys.argv:
            update({"sessionUpdate": "available_commands_update", "availableCommands": [
                {"name": "review", "description": "Review the current workspace", "input": {"hint": "Optional scope"}},
                {"name": "compact", "description": "Compact the agent context"},
            ]})
        result = {"sessionId": "fixture"}
        if "--configuration" in sys.argv:
            result["configOptions"] = configuration
        if "--legacy-modes" in sys.argv:
            result["modes"] = {"currentModeId": "ask", "availableModes": [{"id": "ask", "name": "Ask"}, {"id": "plan", "name": "Plan"}]}
        respond(request_id, result)
    elif method == "session/set_config_option":
        if "--drop-config" in sys.argv:
            os._exit(7)
        option = next((item for item in configuration if item["id"] == message["params"]["configId"]), None)
        if option is None or "--reject-config" in sys.argv:
            send({"id": request_id, "error": {"code": -32602, "message": "Rejected configuration private-fixture-token"}})
            continue
        option["currentValue"] = message["params"]["value"]
        if option["id"] == "model":
            effort = next(item for item in configuration if item["id"] == "effort")
            effort["currentValue"] = "low"
            effort["options"] = [{"value": "low", "name": "Low"}]
        update({"sessionUpdate": "config_option_update", "configOptions": configuration})
        respond(request_id, {"configOptions": configuration})
    elif method == "session/set_mode":
        update({"sessionUpdate": "current_mode_update", "currentModeId": message["params"]["modeId"]})
        respond(request_id, {})
    elif method == "session/prompt":
        if "--prompt-error" in sys.argv:
            send({"id": request_id, "error": {
                "code": -32603, "message": "Internal error", "data": {
                    "details": "stream disconnected: unexpected status 401 Unauthorized private-fixture-token",
                },
            }})
            continue
        prompt = message["params"]["prompt"][0]["text"]
        if "--attachments" in sys.argv:
            (workspace / "attachment-proof.json").write_text(json.dumps(message["params"]["prompt"]))
        if "--images" in sys.argv:
            import hashlib
            import base64
            received = [{"mimeType": block["mimeType"], "hash": hashlib.sha256(base64.b64decode(block["data"])).hexdigest()} for block in message["params"]["prompt"] if block["type"] == "image"]
            (workspace / "image-proof.json").write_text(json.dumps(received))
        if "--configuration" in sys.argv:
            (workspace / "configuration-proof.json").write_text(json.dumps(configuration))
        if "--context-tools" in sys.argv:
            (workspace / "prompt-proof.json").write_text(json.dumps({"text": prompt}))
        if "--conversation-features" in sys.argv:
            update({"sessionUpdate": "usage_update", "used": 12345, "size": 200000})
            update({"sessionUpdate": "plan", "entries": [
                {"content": "Read the project", "priority": "high", "status": "completed"},
                {"content": "Review changes", "priority": "medium", "status": "in_progress"},
            ]})
        if "--delegation-tools" in sys.argv:
            delegation = "delegate-" + str(sequence)
            update({"sessionUpdate": "tool_call", "toolCallId": delegation, "title": "Review the implementation", "kind": "think", "status": "pending", "rawInput": {"subagent_type": "explorer", "description": "Inspect project", "prompt": "Read the source"}, "_meta": {"claudeCode": {"toolName": "Agent"}}})
            update({"sessionUpdate": "tool_call", "toolCallId": "child-" + str(sequence), "title": "Read source", "kind": "read", "status": "in_progress", "rawInput": {"file_path": "probe.txt"}, "_meta": {"claudeCode": {"toolName": "Read", "parentToolUseId": delegation}}})
            update({"sessionUpdate": "tool_call_update", "toolCallId": "child-" + str(sequence), "status": "completed", "rawOutput": "Read the fixture source"})
            update({"sessionUpdate": "tool_call_update", "toolCallId": delegation, "status": "completed", "rawOutput": {"status": "completed", "summary": "Review complete"}})
        if "--long-response" in sys.argv:
            update({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "line 👩🏽‍💻中\n" * 2000}})
            respond(request_id, {"stopReason": "end_turn"})
            continue
        for chunk in ["fixture ", "output"]:
            update({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": chunk}})
        if "--unreported-write" in sys.argv:
            (workspace / "unreported.txt").write_text("no tool notifications\n")
            respond(request_id, {"stopReason": "end_turn"})
            continue
        if "--capture-overflow" in sys.argv:
            with (workspace / "too-large.bin").open("wb") as output:
                output.truncate(64 * 1024 * 1024 + 1)
            respond(request_id, {"stopReason": "end_turn"})
            continue
        if "waits 30 seconds" in prompt:
            pending_cancel = request_id
            continue
        sequence += 1
        pending_edit = request_id
        pending_prompt = prompt
        update({"sessionUpdate": "tool_call", "toolCallId": str(sequence), "title": "Fixture edit", "name": "edit_file", "kind": "edit", "status": "pending", "rawInput": {"path": "probe.txt"}, "locations": [{"path": str(workspace / "probe.txt"), "line": 0}]})
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
                (workspace / "probe.txt").write_bytes((value + "\n").encode())
            if "--cancel-after-write" in sys.argv:
                # Leave the tool pending so cancellation must reconcile actual bytes.
                pending_cancel = pending_edit
                pending_edit = None
                continue
            if "--fail-second-write" in sys.argv and sequence == 2:
                # Failure does not undo the bytes already written.
                update({"sessionUpdate": "tool_call_update", "toolCallId": str(sequence), "status": "failed"})
                send({"id": pending_edit, "error": {"code": -32603, "message": "Fixture failed after second write"}})
                pending_edit = None
                continue
            update({"sessionUpdate": "tool_call_update", "toolCallId": str(sequence), "status": "completed", "content": [{"type": "content", "content": {"type": "text", "text": "Fixture file updated"}}, {"type": "diff", "path": str(workspace / "probe.txt"), "oldText": "", "newText": value + "\n"}]})
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
