"""Phase 0 probe — drive the Hermes dashboard JSON-RPC WS + PTY from a cold client.

Usage: python probe_ws.py            (reads token from .probe-token beside this file)

Probes (brief §5.0.6 / 0.7 / 0.8), all against 127.0.0.1:9119 (loopback mode):
  A  connect, capture gateway.ready (full payload)
  B  session.list
  C  session.create
  D  prompt.submit trivial prompt, stream to completion, first-delta latency
  E  session.interrupt mid-turn from this cold client
  F  approval round-trip: capture literal approval.request, approval.respond unblocks
  G  /api/pty: framing check, echo round-trip

Output: every frame's type; FULL literal JSON for one exemplar of each key frame
(gateway.ready, message.start, message.delta, tool.start, tool.complete,
message.complete, approval.request). Secrets are never printed.
"""
import asyncio
import json
import sys
import time
from pathlib import Path

import websockets

HOST = "127.0.0.1"
PORT = 9119
TOKEN = (Path(__file__).parent / ".probe-token").read_text().strip()

_printed_full = set()
t0_global = time.monotonic()


def ts() -> str:
    return f"{time.monotonic() - t0_global:8.3f}s"


def show_frame(frame: dict, *, force_full: bool = False) -> None:
    """Print one line per frame; full JSON once per key event type."""
    if "id" in frame and "method" not in frame:
        kind = "RESPONSE"
        label = f"id={frame.get('id')}"
    else:
        kind = "EVENT"
        label = frame.get("params", {}).get("type", frame.get("method", "?"))
    key = label if kind == "EVENT" else None
    full = force_full or (key in {
        "gateway.ready", "message.start", "message.delta", "tool.start",
        "tool.complete", "message.complete", "approval.request", "session.info",
        "status.update", "error",
    } and key not in _printed_full)
    if full and key:
        _printed_full.add(key)
    raw = json.dumps(frame, ensure_ascii=False)
    if full:
        print(f"[{ts()}] {kind} {label} FULL: {raw[:2000]}")
    else:
        # abbreviated: type + tiny hint
        p = frame.get("params", {}).get("payload", {}) if kind == "EVENT" else frame.get("result", frame.get("error", {}))
        hint = ""
        if isinstance(p, dict):
            for k in ("delta", "text", "status", "tool", "title"):
                if k in p:
                    hint = f"{k}={str(p[k])[:60]!r}"
                    break
        print(f"[{ts()}] {kind} {label} {hint} ({len(raw)}B)")


class Rpc:
    def __init__(self, ws):
        self.ws = ws
        self._next = 1
        self.pending: dict[int, asyncio.Future] = {}
        self.events: asyncio.Queue = asyncio.Queue()

    async def reader(self):
        async for raw in self.ws:
            if isinstance(raw, bytes):
                raw = raw.decode("utf-8", "replace")
            for line in raw.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    frame = json.loads(line)
                except json.JSONDecodeError:
                    print(f"[{ts()}] NON-JSON LINE: {line[:200]!r}")
                    continue
                if "id" in frame and "method" not in frame and frame.get("id") in self.pending:
                    fut = self.pending.pop(frame["id"])
                    if not fut.done():
                        fut.set_result(frame)
                else:
                    await self.events.put(frame)

    async def call(self, method: str, params: dict | None = None, timeout: float = 30.0):
        rid = self._next
        self._next += 1
        fut = asyncio.get_running_loop().create_future()
        self.pending[rid] = fut
        await self.ws.send(json.dumps({
            "jsonrpc": "2.0", "id": rid, "method": method, "params": params or {},
        }) + "\n")
        frame = await asyncio.wait_for(fut, timeout)
        if "error" in frame:
            raise RuntimeError(f"{method} -> error: {json.dumps(frame['error'])[:400]}")
        return frame.get("result")

    async def next_event(self, timeout: float):
        return await asyncio.wait_for(self.events.get(), timeout)


END_TYPES = {"message.complete", "turn.complete", "error"}


async def run_turn(rpc: Rpc, sid: str, text: str, *, on_event=None, hard_cap=180.0,
                   silence=25.0):
    """Submit a prompt, pump events until completion. Returns stats dict."""
    t0 = time.monotonic()
    await rpc.call("prompt.submit", {"session_id": sid, "text": text})
    t_first_delta = None
    types_seen = {}
    last = time.monotonic()
    completed = False
    while time.monotonic() - t0 < hard_cap:
        try:
            ev = await rpc.next_event(timeout=silence)
        except asyncio.TimeoutError:
            print(f"[{ts()}] .. {silence}s silence, ending collection")
            break
        last = time.monotonic()
        etype = ev.get("params", {}).get("type", "?")
        types_seen[etype] = types_seen.get(etype, 0) + 1
        show_frame(ev)
        if etype in ("message.delta", "reasoning.delta", "thinking.delta") and t_first_delta is None:
            t_first_delta = time.monotonic() - t0
            print(f"[{ts()}] >>> FIRST DELTA at {t_first_delta*1000:.0f}ms after submit")
        if on_event is not None:
            await on_event(ev)
        if etype in END_TYPES:
            completed = True
            break
    return {
        "first_delta_ms": (t_first_delta or -1) * 1000,
        "wall_s": time.monotonic() - t0,
        "types": types_seen,
        "completed": completed,
    }


async def main() -> None:
    url = f"ws://{HOST}:{PORT}/api/ws?token={TOKEN}"
    print(f"=== A. CONNECT {url.split('?')[0]}?token=<redacted>")
    async with websockets.connect(url, ping_interval=20, max_size=8 * 1024 * 1024) as ws:
        rpc = Rpc(ws)
        reader = asyncio.create_task(rpc.reader())

        # gateway.ready — first frame expected
        ready = await rpc.next_event(timeout=10)
        show_frame(ready, force_full=True)

        print("\n=== B. session.list")
        res = await rpc.call("session.list", {})
        print(f"[{ts()}] session.list result keys: {list(res) if isinstance(res, dict) else type(res)}")
        print(json.dumps(res, ensure_ascii=False)[:1200])

        print("\n=== C. session.create")
        res = await rpc.call("session.create", {"title": "phase0-probe"})
        print(f"[{ts()}] session.create FULL: {json.dumps(res, ensure_ascii=False)[:1200]}")
        sid = res.get("session_id") or res.get("id") or (res.get("session") or {}).get("session_id")
        print(f"[{ts()}] session id = {sid!r}")

        print("\n=== D. trivial prompt, streaming + latency")
        stats = await run_turn(rpc, sid, "Reply with exactly: PROBE OK")
        print(f"[{ts()}] TURN STATS: {json.dumps(stats)}")

        print("\n=== E. interrupt mid-turn (cold client)")
        interrupted = {"done": False}

        async def interrupt_after_first_delta(ev):
            if interrupted["done"]:
                return
            if ev.get("params", {}).get("type") in ("message.delta", "reasoning.delta", "thinking.delta"):
                interrupted["done"] = True
                print(f"[{ts()}] >>> calling session.interrupt")
                try:
                    r = await rpc.call("session.interrupt", {"session_id": sid}, timeout=15)
                    print(f"[{ts()}] session.interrupt RESULT: {json.dumps(r)[:400]}")
                except Exception as e:
                    print(f"[{ts()}] session.interrupt FAILED: {e}")

        stats = await run_turn(
            rpc, sid,
            "Count from 1 to 60, one number per line, adding one short fun fact per number.",
            on_event=interrupt_after_first_delta,
        )
        print(f"[{ts()}] INTERRUPT TURN STATS: {json.dumps(stats)}")

        print("\n=== F. approval round-trip (fresh session — no distracting history)")
        res = await rpc.call("session.create", {"title": "phase0-approval-probe"})
        sid_f = res.get("session_id")
        print(f"[{ts()}] approval session id = {sid_f!r}")
        approval_state = {"responded": False}

        async def respond_to_approval(ev):
            if ev.get("params", {}).get("type") == "approval.request" and not approval_state["responded"]:
                approval_state["responded"] = True
                print(f"[{ts()}] >>> approval.request captured; responding choice='once'")
                try:
                    r = await rpc.call("approval.respond", {"session_id": sid_f, "choice": "once"}, timeout=15)
                    print(f"[{ts()}] approval.respond RESULT: {json.dumps(r)[:400]}")
                except Exception as e:
                    print(f"[{ts()}] approval.respond FAILED: {e}")

        stats = await run_turn(
            rpc, sid_f,
            "Call the terminal tool to run exactly this command and nothing else: "
            "echo HERMES-APPROVAL-PROBE-7f3a — you MUST use the terminal tool; "
            "do not answer from your own knowledge.",
            on_event=respond_to_approval, hard_cap=240.0,
        )
        stats["approval_seen"] = approval_state["responded"]
        print(f"[{ts()}] APPROVAL TURN STATS: {json.dumps(stats)}")

        reader.cancel()

    print("\n=== G. /api/pty probe")
    pty_url = f"ws://{HOST}:{PORT}/api/pty?token={TOKEN}"
    try:
        async with websockets.connect(pty_url, ping_interval=20, max_size=8 * 1024 * 1024) as pws:
            binary_seen = text_seen = 0
            got_marker = asyncio.Event()
            sample = []

            async def pty_reader():
                nonlocal binary_seen, text_seen
                try:
                    async for msg in pws:
                        if isinstance(msg, bytes):
                            binary_seen += 1
                            sample.append(msg)
                        else:
                            text_seen += 1
                            sample.append(msg.encode())
                        if b"PTY-PROBE-OK-9c1e" in b"".join(sample):
                            got_marker.set()
                except websockets.ConnectionClosed as e:
                    print(f"[{ts()}] PTY closed: code={e.code} reason={e.reason}")

            rt = asyncio.create_task(pty_reader())
            # wait for banner / shell prompt
            try:
                await asyncio.wait_for(asyncio.shield(_wait_bytes(sample)), timeout=20)
            except asyncio.TimeoutError:
                pass
            print(f"[{ts()}] PTY initial: binary_frames={binary_seen} text_frames={text_seen}")
            if sample:
                first = sample[0]
                print(f"[{ts()}] PTY first frame ({'bytes' if isinstance(first, bytes) else 'text'}, {len(first)}B): {first[:300]!r}")
            # send a command as raw bytes (terminal-style input)
            await pws.send(b"echo PTY-PROBE-OK-9c1e\r")
            try:
                await asyncio.wait_for(got_marker.wait(), timeout=20)
                print(f"[{ts()}] PTY ECHO MARKER RECEIVED — round trip works")
            except asyncio.TimeoutError:
                print(f"[{ts()}] PTY marker NOT seen within 20s")
            rt.cancel()
    except websockets.exceptions.InvalidStatus as e:
        print(f"[{ts()}] PTY CONNECT REJECTED: {e}")
    except Exception as e:
        print(f"[{ts()}] PTY PROBE ERROR: {type(e).__name__}: {e}")

    print("\n=== PROBE COMPLETE")


async def _wait_bytes(sample, min_bytes=64):
    while sum(len(s) for s in sample) < min_bytes:
        await asyncio.sleep(0.1)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(130)
