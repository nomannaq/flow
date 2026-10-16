"""B06 app-IPC peer, deliberately malformed only in selected failure modes."""
import json
import sys

mode = sys.argv[1]
epoch = "fixture-epoch"
changed = False
for line in sys.stdin:
    request = json.loads(line)
    method = request["method"]
    if method == "engine.initialize":
        if mode == "broken":
            print("not json", flush=True)
            break
        result = {
            "protocolVersion": 1, "engineVersion": "fixture", "epoch": epoch,
            "projectId": request["params"]["projectId"],
            "capabilities": {"cancellation": True, "persistentEvents": True,
                             "idempotentMutations": False, "agentSessions": False},
            "maxFrameBytes": 1048576, "maxInFlight": 32, "maxConnectionRequests": 4096,
            "defaultTimeoutMs": 30000, "maxTimeoutMs": 30000,
        }
    elif method == "state.snapshot":
        result = {"highWater": {"storeId": "fixture-store", "sequence": "200" if changed else "0"},
                  "state": {"sessions": 0, "operations": 200 if changed else 0}}
    elif method == "events.replay":
        start = int(request["params"]["after"]["sequence"])
        changed = True
        if mode == "cursor-reset" and start == 0:
            print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "epoch": epoch,
                              "error": {"code": -32602, "message": "Invalid params"}}), flush=True)
            continue
        events = [{"cursor": {"storeId": "fixture-store", "sequence": str(i)}, "schemaVersion": 1,
                   "workspaceId": "fixture-workspace", "kind": "future.kind", "payload": {}}
                  for i in range(start + 1, min(start + 100, 200) + 1)]
        result = {"events": events, "highWater": {"storeId": "fixture-store", "sequence": "200"},
                  "hasMore": start + len(events) < 200}
    elif method == "engine.health":
        if mode == "hang":
            continue
        result = {"protocolVersion": 1, "engineVersion": "fixture", "projectId": "test"}
        # Stale replies must not complete the request; a bounded notification flood
        # must not create one Swing callback per message.
        print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "epoch": "old", "result": result}), flush=True)
        for _ in range(200):
            print(json.dumps({"jsonrpc": "2.0", "method": "events.available", "epoch": epoch, "params": {}}), flush=True)
    elif method == "requests.cancel":
        continue
    else:
        result = None
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "epoch": epoch, "result": result}), flush=True)

    if method == "engine.shutdown" and mode == "shutdown-receipt":
        from pathlib import Path
        Path(sys.argv[2]).write_text("shutdown acknowledged\n")
        break
