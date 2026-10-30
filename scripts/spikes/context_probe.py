"""Offline fixture client for the production RMCP stdio bridge."""
import json
import os
import subprocess


def probe(config, wrong_token=False):
    env = os.environ.copy()
    env.update({entry["name"]: entry["value"] for entry in config["env"]})
    if wrong_token:
        env["FLOW_CONTEXT_TOKEN"] = "0" * 64
    child = subprocess.Popen([config["command"], *config["args"]], env=env,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True)
    serial = 0

    def call(method, params):
        nonlocal serial
        serial += 1
        child.stdin.write(json.dumps({"jsonrpc": "2.0", "id": serial,
                                     "method": method, "params": params}) + "\n")
        child.stdin.flush()
        for _ in range(20):
            line = child.stdout.readline()
            if not line:
                raise RuntimeError("MCP bridge disconnected")
            response = json.loads(line)
            if response.get("id") == serial:
                return response["result"]
        raise RuntimeError("MCP response missing")

    try:
        call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                            "clientInfo": {"name": "flow-contract", "version": "1"}})
        child.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
        child.stdin.flush()
        tools = call("tools/list", {})["tools"]
        assert {tool["name"] for tool in tools} == {"repo_overview", "search_symbols", "get_symbol_context", "check_context_freshness", "find_code", "find_all", "get_file", "file_api", "trace_calls", "repo_map", "check_index", "list_learnings", "review_learnings", "branch_overlap"}
        response = call("tools/call", {"name": "search_symbols", "arguments": {"query": "probe_symbol"}})
        text = response["content"][0]["text"]
        if wrong_token:
            assert "unavailable" in text.lower()
            return {"wrongCredentialRejected": True}
        result = json.loads(text)
        assert result["results"][0]["symbol"]["name"] == "probe_symbol"
        path = result["results"][0]["path"]
        content_hash = result["results"][0]["contentHash"]
        fresh = call("tools/call", {"name": "check_context_freshness", "arguments": {"path": path, "contentHash": content_hash}})
        assert json.loads(fresh["content"][0]["text"])["freshness"] == "verifiedAtRead"
        context = call("tools/call", {"name": "get_symbol_context", "arguments": {"path": path, "contentHash": content_hash, "line": 1}})
        assert "probe_symbol" in context["content"][0]["text"]
        def read(name, arguments):
            return json.loads(call("tools/call", {"name": name, "arguments": arguments})["content"][0]["text"])
        ranked = read("find_code", {"query": "probe_symbol"})
        assert ranked["results"][0]["name"] == "probe_symbol"
        assert "probe_symbol" in read("get_file", {"path": path})["results"][0]["text"]
        assert read("file_api", {"path": path})["results"][0]["name"] == "probe_symbol"
        assert read("find_all", {"pattern": "probe_symbol", "fixed": True})["results"][0]["path"] == path
        assert read("trace_calls", {"symbol": "probe_symbol"})["freshness"] == "verifiedAtRead"
        assert read("repo_map", {})["results"]
        assert read("check_index", {})["freshness"] == "verifiedAtRead"
        assert read("list_learnings", {})["results"] == []
        assert read("review_learnings", {"paths": [path]})["conflictDetection"].startswith("path overlap")
        traversal = call("tools/call", {"name": "get_symbol_context", "arguments": {"path": "../outside.rs"}})
        assert "unavailable" in traversal["content"][0]["text"].lower()
        wrong_session = call("tools/call", {"name": "repo_overview", "arguments": {"sessionId": "different-session"}})
        assert "unavailable" in wrong_session["content"][0]["text"].lower()
        return {"tools": [tool["name"] for tool in tools], "generation": result["generation"], "traversalRejected": True, "wrongSessionRejected": True}
    finally:
        child.stdin.close()
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
