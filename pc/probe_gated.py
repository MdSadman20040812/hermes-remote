"""Gated-mode probe — the REAL phone path over the tailnet.

Against http://100.88.18.123:9119 (auth gate ON, provider: basic):
  1. /api/status reachable without auth (reachability probe stays viable)
  2. /api/ws?token=… must be REJECTED (close 4401) in gated mode
  3. POST /auth/password-login → session cookie
  4. POST /api/auth/ws-ticket → single-use 30s ticket
  5. /api/ws?ticket=… → gateway.ready + session.list round trip
  6. ticket reuse must FAIL (single-use)
"""
import asyncio
import json
from pathlib import Path

import httpx
import websockets

BASE = "http://100.88.18.123:9119"
WS = "ws://100.88.18.123:9119"
TOKEN = (Path(__file__).parent / ".probe-token").read_text().strip()
PASSWORD = (Path(__file__).parent / ".dashboard-password").read_text().strip()


async def main():
    print("=== 1. unauthenticated /api/status (public health probe)")
    async with httpx.AsyncClient(timeout=10) as http:
        r = await http.get(f"{BASE}/api/status")
        d = r.json()
        print(f"  HTTP {r.status_code} auth_required={d.get('auth_required')} providers={d.get('auth_providers')}")

        print("=== 2. ?token= on gated WS (expect rejection, close 4401)")
        try:
            async with websockets.connect(f"{WS}/api/ws?token={TOKEN}") as ws:
                msg = await asyncio.wait_for(ws.recv(), 5)
                print(f"  UNEXPECTED ACCEPT: {msg[:120]}")
        except websockets.exceptions.ConnectionClosedError as e:
            print(f"  rejected as expected: code={e.code} reason={e.reason}")
        except Exception as e:
            print(f"  rejected (connect-stage): {type(e).__name__} {e}")

        print("=== 3. password login")
        r = await http.post(f"{BASE}/auth/password-login", json={
            "provider": "basic", "username": "sadman", "password": PASSWORD,
        })
        print(f"  HTTP {r.status_code} body={r.text[:120]}")
        cookies = r.cookies
        print(f"  cookies set: {list(cookies.keys())}")

        print("=== 4. mint ws ticket")
        r = await http.post(f"{BASE}/api/auth/ws-ticket", cookies=cookies)
        print(f"  HTTP {r.status_code} body={r.text[:80]}")
        ticket = r.json()["ticket"]

        print("=== 5. WS connect with ?ticket= ")
        async with websockets.connect(f"{WS}/api/ws?ticket={ticket}", ping_interval=20) as ws:
            raw = await asyncio.wait_for(ws.recv(), 10)
            frame = json.loads(raw.splitlines()[0])
            ftype = frame.get("params", {}).get("type")
            print(f"  first frame type: {ftype} (expect gateway.ready)")
            await ws.send(json.dumps({
                "jsonrpc": "2.0", "id": 1, "method": "session.list", "params": {},
            }) + "\n")
            while True:
                raw = await asyncio.wait_for(ws.recv(), 10)
                for line in raw.splitlines():
                    f = json.loads(line)
                    if f.get("id") == 1:
                        n = len(f.get("result", {}).get("sessions", []))
                        print(f"  session.list over gated ticket: OK, {n} sessions")
                        break
                else:
                    continue
                break

        print("=== 6. ticket single-use check (reuse must fail)")
        try:
            async with websockets.connect(f"{WS}/api/ws?ticket={ticket}") as ws:
                msg = await asyncio.wait_for(ws.recv(), 5)
                print(f"  UNEXPECTED ACCEPT on reused ticket: {msg[:120]}")
        except websockets.exceptions.ConnectionClosedError as e:
            print(f"  reuse rejected as expected: code={e.code} reason={e.reason}")
        except Exception as e:
            print(f"  reuse rejected (connect-stage): {type(e).__name__} {e}")

    print("=== GATED PROBE COMPLETE")


asyncio.run(main())
