#!/usr/bin/env python3
"""stdio <-> HTTP bridge so stdio-only MCP clients (e.g. Claude Desktop) can use Hydrogen.
Env: HYDROGEN_URL (default http://127.0.0.1:8765/mcp), HYDROGEN_TOKEN (from Hydrogen -> Settings -> Agents)."""
import os, sys, json, urllib.request, urllib.error

URL = os.environ.get("HYDROGEN_URL", "http://127.0.0.1:8765/mcp")
TOKEN = os.environ.get("HYDROGEN_TOKEN", "")

def post(body: bytes):
    req = urllib.request.Request(URL, data=body, method="POST", headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
            return data.decode() if r.status == 200 and data else None
    except urllib.error.HTTPError as e:
        return json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32000, "message": f"Hydrogen refused the request (HTTP {e.code})"}})
    except Exception as e:
        return json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32000, "message": f"Can't reach Hydrogen: {e}"}})

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    out = post(line.encode())
    if out:
        sys.stdout.write(out.replace("\n", " ") + "\n"); sys.stdout.flush()
