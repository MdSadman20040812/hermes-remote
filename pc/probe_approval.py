"""Phase 0.7 focused probe: force a REAL approval gate and round-trip it.

The full probe's `echo` was auto-approved (risk engine passes no-op commands even
in approvals.mode=manual). This probe asks for a file-writing terminal command,
which must block, then answers with approval.respond choice='once'.
"""
import asyncio
import json
import time
from pathlib import Path

import websockets

HOST, PORT = "127.0.0.1", 9119
TOKEN = (Path(__file__).parent / ".probe-token").read_text().strip()
t0 = time.monotonic()


def ts():
    return f"{time.monotonic() - t0:8.3f}s"


async def main():
    url = f"ws://{HOST}:{PORT}/api/ws?token={TOKEN}"
    async with websockets.connect(url, ping_interval=20, max_size=8 * 1024 * 1024) as ws:
        pending = {}
        events = asyncio.Queue()
        next_id = [1]

        async def reader():
            async for raw in ws:
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8", "replace")
                for line in raw.splitlines():
                    if not line.strip():
                        continue
                    f = json.loads(line)
                    if "id" in f and "method" not in f and f["id"] in pending:
                        pending.pop(f["id"]).set_result(f)
                    else:
                        await events.put(f)

        async def call(method, params=None, timeout=30):
            rid = next_id[0]
            next_id[0] += 1
            fut = asyncio.get_running_loop().create_future()
            pending[rid] = fut
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}) + "\n")
            f = await asyncio.wait_for(fut, timeout)
            if "error" in f:
                raise RuntimeError(json.dumps(f["error"])[:300])
            return f.get("result")

        rt = asyncio.create_task(reader())
        await events.get()  # gateway.ready
        res = await call("session.create", {"title": "phase0-approval-force"})
        sid = res["session_id"]
        print(f"[{ts()}] session {sid}")

        await call("prompt.submit", {"session_id": sid, "text": (
            "Call the terminal tool to run exactly this command: "
            "rm -rf D:/HermesMobile/pc/scratch_dir "
            "— you MUST use the terminal tool, do not answer directly."
        )})

        approved = False
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            ev = await asyncio.wait_for(events.get(), timeout=30)
            et = ev.get("params", {}).get("type")
            if et == "approval.request":
                print(f"[{ts()}] approval.request FULL: {json.dumps(ev, ensure_ascii=False)[:2500]}")
                r = await call("approval.respond", {"session_id": sid, "choice": "once"}, timeout=15)
                print(f"[{ts()}] approval.respond(once) -> {json.dumps(r)[:300]}")
                approved = True
            elif et in ("tool.start", "tool.complete", "message.complete"):
                print(f"[{ts()}] {et}: {json.dumps(ev.get('params', {}).get('payload', {}), ensure_ascii=False)[:500]}")
                if et == "message.complete":
                    break
            elif et == "error":
                print(f"[{ts()}] ERROR FRAME: {json.dumps(ev)[:500]}")
                break
        print(f"[{ts()}] APPROVAL_ROUND_TRIP={'YES' if approved else 'NO'}")
        rt.cancel()

    gate = Path(r"D:\HermesMobile\pc\approval_gate.txt")
    print(f"gate file exists={gate.exists()} content={gate.read_text().strip() if gate.exists() else None!r}")


asyncio.run(main())
