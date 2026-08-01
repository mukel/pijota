#!/usr/bin/env python3
"""End-to-end smoke test for the ACP frontend: a scripted client playing the editor's
role (what Zed does), driving `pj acp` over line-delimited JSON-RPC on stdio.

Covers both directions of the protocol, which unit tests cannot: the client's requests
(initialize, session/new, session/prompt, session/cancel) AND the agent's requests back
(session/request_permission answered allow/reject, fs/read_text_file answered with a fake
editor buffer that must beat the on-disk content).

Usage: scripts/acp-smoke.py [path-to-pj [cwd]]   (or: make acp-smoke)
Needs a configured provider key; each check prints PASS/FAIL, exit 1 on any failure.
"""
import json, os, subprocess, sys, threading, queue, time

PJ = sys.argv[1] if len(sys.argv) > 1 else "target/pj"
CWD = sys.argv[2] if len(sys.argv) > 2 else None
made_tmp = CWD is None
if made_tmp:
    # A neutral temp cwd: a project directory would load context files into the system
    # prompt and make small models flake instruction-following (same lesson as native-smoke).
    import tempfile
    CWD = tempfile.mkdtemp(prefix="acp-smoke-")
    import atexit, shutil
    atexit.register(lambda: shutil.rmtree(CWD, ignore_errors=True))
PJ = os.path.abspath(PJ)

proc = subprocess.Popen([PJ, "acp"], cwd=CWD, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL, text=True, bufsize=1)

responses = {}          # id -> response message
notifications = queue.Queue()
server_requests = queue.Queue()
next_id = [0]
lock = threading.Lock()

def reader():
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            print(f"  [wire] unparseable: {line[:120]}"); continue
        if "id" in msg and ("result" in msg or "error" in msg):
            responses[msg["id"]] = msg
        elif "id" in msg and "method" in msg:
            server_requests.put(msg)            # agent -> client request (permission, fs)
        else:
            notifications.put(msg)              # session/update etc.

threading.Thread(target=reader, daemon=True).start()

def send(msg):
    with lock:
        proc.stdin.write(json.dumps(msg) + "\n"); proc.stdin.flush()

def request(method, params, timeout=120):
    next_id[0] += 1
    rid = next_id[0]
    send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    deadline = time.time() + timeout
    while time.time() < deadline:
        if rid in responses:
            return responses.pop(rid)
        drain_server_requests()
        time.sleep(0.05)
    raise TimeoutError(f"{method} timed out")

def respond(rid, result):
    send({"jsonrpc": "2.0", "id": rid, "result": result})

# What the "editor" answers when the agent asks it something.
policy = {"permission": "allow_once", "fs_read_content": None}
seen = {"permissions": [], "fs_reads": [], "updates": []}

def drain_server_requests():
    try:
        while True:
            req = server_requests.get_nowait()
            m = req["method"]
            if m == "session/request_permission":
                tool = req["params"]["toolCall"]["title"]
                seen["permissions"].append(tool)
                print(f"  [client] permission asked for '{tool}' -> {policy['permission']}")
                respond(req["id"], {"outcome": {"outcome": "selected", "optionId": policy["permission"]}})
            elif m == "fs/read_text_file":
                path = req["params"]["path"]
                seen["fs_reads"].append(path)
                content = policy["fs_read_content"]
                print(f"  [client] fs/read_text_file {path} -> {'editor buffer' if content else 'null'}")
                respond(req["id"], {"content": content if content is not None else ""})
            elif m == "fs/write_text_file":
                print(f"  [client] fs/write_text_file {req['params']['path']}")
                respond(req["id"], {})
            else:
                print(f"  [client] unexpected request {m}, answering empty")
                respond(req["id"], {})
    except queue.Empty:
        pass

def collect_updates():
    out = []
    try:
        while True:
            n = notifications.get_nowait()
            if n.get("method") == "session/update":
                out.append(n["params"]["update"])
    except queue.Empty:
        pass
    seen["updates"].extend(out)
    return out

def agent_text(updates):
    return "".join(u["content"]["text"] for u in updates
                   if u.get("sessionUpdate") == "agent_message_chunk")

fails = []
def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f"  ({detail})" if detail and not cond else ""))
    if not cond:
        fails.append(name)

# ---- 1. initialize ----
init = request("initialize", {"protocolVersion": 1,
                              "clientCapabilities": {"fs": {"readTextFile": True, "writeTextFile": True}}})
r = init.get("result", {})
check("initialize: protocolVersion 1", r.get("protocolVersion") == 1, json.dumps(init)[:200])
check("initialize: prompt image capability", r.get("agentCapabilities", {}).get("promptCapabilities", {}).get("image") is True)

# ---- 2. session/new ----
sess = request("session/new", {"cwd": CWD, "mcpServers": []})
sid = sess.get("result", {}).get("sessionId")
check("session/new: sessionId minted", bool(sid))
check("session/new: thinking modes advertised", "modes" in sess.get("result", {}))

def prompt(text, timeout=120):
    collect_updates()
    res = request("session/prompt",
                  {"sessionId": sid, "prompt": [{"type": "text", "text": text}]}, timeout)
    time.sleep(0.3)
    drain_server_requests()
    return res.get("result", {}), collect_updates()

# ---- 3. plain streamed answer ----
res, updates = prompt("Reply with exactly: ACP-OK")
check("prompt: stopReason end_turn", res.get("stopReason") == "end_turn", str(res))
check("prompt: streamed text arrived", "ACP-OK" in agent_text(updates), agent_text(updates)[:120])

# ---- 4. gated tool with allow_once ----
policy["permission"] = "allow_once"
res, updates = prompt("Use the bash tool to run exactly: echo acp-bash-ok")
kinds = [u.get("sessionUpdate") for u in updates]
check("bash: permission was requested", "bash" in seen["permissions"], str(seen["permissions"]))
check("bash: tool_call update emitted", "tool_call" in kinds, str(kinds))
done = [u for u in updates if u.get("sessionUpdate") == "tool_call_update"]
check("bash: tool_call_update completed", any(u.get("status") == "completed" for u in done), str(done)[:200])

# ---- 5. gated tool with reject ----
policy["permission"] = "reject_once"
denied = os.path.join(CWD, "denied.txt")
res, updates = prompt("Create a file named denied.txt containing 'x' using the write tool. If the tool is rejected, just say REJECTED.")
check("reject: file was NOT created", not os.path.exists(denied))
policy["permission"] = "allow_once"

# ---- 6. client-side fs read (editor buffer beats disk) ----
buf = os.path.join(CWD, "buffer.txt")
with open(buf, "w") as f:
    f.write("stale-disk-content")
policy["fs_read_content"] = "EDITOR-BUFFER-999"
res, updates = prompt(f"Read the file {buf} and tell me exactly what it contains.")
check("fs: read routed through the client", any(p.endswith("buffer.txt") for p in seen["fs_reads"]), str(seen["fs_reads"]))
check("fs: editor buffer beats disk", "EDITOR-BUFFER-999" in agent_text(updates), agent_text(updates)[:160])

# ---- 7. cancel mid-turn ----
collect_updates()
next_id[0] += 1
rid = next_id[0]
send({"jsonrpc": "2.0", "id": rid, "method": "session/prompt",
      "params": {"sessionId": sid, "prompt": [{"type": "text", "text": "Write a 2000-word essay about oceans. No tools."}]}})
deadline = time.time() + 30
while time.time() < deadline:          # wait for streaming to begin, then cancel
    if collect_updates():
        break
    drain_server_requests(); time.sleep(0.1)
send({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": sid}})
deadline = time.time() + 30
res = None
while time.time() < deadline:
    if rid in responses:
        res = responses.pop(rid); break
    drain_server_requests(); time.sleep(0.1)
check("cancel: stopReason cancelled", res and res.get("result", {}).get("stopReason") == "cancelled", str(res))

proc.stdin.close(); proc.terminate()
print()
print("ACP client run: " + ("ALL PASS" if not fails else f"FAILURES: {fails}"))
sys.exit(1 if fails else 0)
