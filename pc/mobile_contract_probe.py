#!/usr/bin/env python3
"""Replay, against the LIVE dashboard, exactly the RPCs the Android client now calls.

A green Gradle build proves the Kotlin compiles, not that the server accepts
the frames it sends. Every bug this run fixed was a contract mismatch that
compiled perfectly — the history reader looked for the wrong field name, and
the attachments were never sent at all. So the contract gets exercised here,
on the real server, before the APK is trusted.

Checks, in the order the app performs them:
  1. session.create                      -> live handle
  2. image.attach_bytes                  -> the bug-1 fix (PNG staged)
  3. file.attach                          -> the bug-5 fix (@file: ref back)
  4. prompt.submit                        -> must ACCEPT the staged turn
  5. session.history                      -> the bug-3 fix (rows carry "text")
  6. config.get/set approvals.mode        -> the bug-4 autonomy control
"""
import base64
import json
import sys
import urllib.request
import zlib
from http.cookiejar import CookieJar
from pathlib import Path

import websockets.sync.client as wsc  # type: ignore

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2] if len(sys.argv) > 2 else 9119)
BASE = f"http://{HOST}:{PORT}"

# The dashboard here is GATED, so the probe must walk the same credential
# chain the app does (CredentialStrategy.Gated): password-login sets the
# session cookies, and the WS upgrade needs a single-use ticket minted through
# that SAME cookie jar. A probe that skips this gets a 403 on the upgrade and
# proves nothing about the RPC contract.
JAR = CookieJar()
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(JAR))

PASS, FAIL = "PASS", "FAIL"
results = []


def record(name, ok, detail=""):
    results.append((PASS if ok else FAIL, name, detail))
    print(f"[{PASS if ok else FAIL}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def http_json(path):
    with OPENER.open(BASE + path, timeout=10) as r:
        return json.loads(r.read())


def post_json(path, payload):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with OPENER.open(req, timeout=15) as r:
        body = r.read().decode()
        return json.loads(body) if body.strip() else {}


def ws_url():
    """Authenticate exactly as the app does, returning a ticketed WS URL."""
    status = http_json("/api/status")
    if not status.get("auth_required"):
        return f"ws://{HOST}:{PORT}/api/ws", status
    pw = Path(__file__).with_name(".dashboard-password").read_text(encoding="utf-8").strip()
    post_json("/auth/password-login",
              {"provider": "basic", "username": "sadman", "password": pw})
    ticket = post_json("/api/auth/ws-ticket", {}).get("ticket")
    if not ticket:
        raise RuntimeError("ws-ticket mint returned no ticket")
    return f"ws://{HOST}:{PORT}/api/ws?ticket={ticket}", status


def tiny_png() -> bytes:
    """A real 1x1 PNG built byte-for-byte — the server sniffs magic bytes."""
    def chunk(tag, data):
        c = tag + data
        return len(data).to_bytes(4, "big") + c + zlib.crc32(c).to_bytes(4, "big")
    ihdr = (1).to_bytes(4, "big") + (1).to_bytes(4, "big") + bytes([8, 2, 0, 0, 0])
    idat = zlib.compress(b"\x00\xff\x00\x00")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


class Rpc:
    def __init__(self, ws):
        self.ws, self.n = ws, 0

    def await_turn_end(self, sid, timeout=240):
        """Block until the turn actually completes.

        `prompt.submit` answering {"status":"streaming"} means ACCEPTED, not
        finished — the user row is persisted by the turn, so querying history
        on the next line races the write and reads an empty session. That race
        is exactly what a phone does on a fast reply, so the probe has to model
        it rather than paper over it with a sleep.
        """
        import time
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                raw = self.ws.recv(timeout=max(1, deadline - time.time()))
            except Exception:
                return False
            for line in raw.split("\n"):
                if not line.strip():
                    continue
                msg = json.loads(line)
                params = msg.get("params") or {}
                if msg.get("method") == "event" and params.get("session_id") == sid:
                    if params.get("type") in ("message.complete", "error"):
                        return params.get("type") == "message.complete"
        return False

    def call(self, method, params=None, timeout=90):
        self.n += 1
        rid = self.n
        self.ws.send(json.dumps(
            {"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}) + "\n")
        deadline_msgs = 400
        while deadline_msgs:
            deadline_msgs -= 1
            for line in self.ws.recv(timeout=timeout).split("\n"):
                if not line.strip():
                    continue
                msg = json.loads(line)
                if msg.get("id") == rid and "method" not in msg:
                    if msg.get("error"):
                        raise RuntimeError(f"{method}: {msg['error']}")
                    return msg.get("result")
        raise TimeoutError(method)


def main():
    url, status = ws_url()
    print(f"server {status.get('version')} auth_required={status.get('auth_required')}\n")
    with wsc.connect(url, open_timeout=15, max_size=64 * 1024 * 1024) as ws:
        # Drain gateway.ready
        ws.recv(timeout=15)
        rpc = Rpc(ws)

        sid = rpc.call("session.create", {"title": "mobile-contract-probe"})["session_id"]
        record("session.create", bool(sid), sid)

        # --- bug 1: an image actually staged against the session ---
        png = base64.b64encode(tiny_png()).decode()
        try:
            res = rpc.call("image.attach_bytes", {
                "session_id": sid, "content_base64": png, "filename": "probe.png"})
            record("image.attach_bytes (bug 1)",
                   res.get("attached") is True and res.get("count", 0) >= 1,
                   f"count={res.get('count')} path={Path(res.get('path','')).name}")
        except Exception as e:
            record("image.attach_bytes (bug 1)", False, str(e)[:160])

        # --- bug 5: a document staged and handed back as an openable ref ---
        doc = base64.b64encode(b"probe document body\n").decode()
        try:
            res = rpc.call("file.attach", {
                "session_id": sid, "data_url": f"data:text/plain;base64,{doc}",
                "name": "probe.txt"})
            ref = res.get("ref_text", "")
            record("file.attach (bug 5)",
                   res.get("attached") is True and ref.startswith("@file:"),
                   f"ref={ref}")
        except Exception as e:
            record("file.attach (bug 5)", False, str(e)[:160])

        # --- the ordering that was actually broken: submit AFTER staging ---
        try:
            res = rpc.call("prompt.submit", {
                "session_id": sid,
                "text": "Reply with exactly: CONTRACT_OK"}, timeout=120)
            record("prompt.submit accepts the staged turn",
                   res.get("status") == "streaming", f"status={res.get('status')}")
            done = rpc.await_turn_end(sid)
            record("turn runs to completion with the attachments", done,
                   "message.complete" if done else "timed out / errored")
        except Exception as e:
            record("prompt.submit accepts the staged turn", False, str(e)[:160])

        # --- bug 3: history rows must carry "text", not "content" ---
        try:
            hist = rpc.call("session.history", {"session_id": sid}, timeout=60)
            msgs = hist.get("messages", [])
            users = [m for m in msgs if m.get("role") == "user"]
            # Only conversational rows carry `text`; a tool row is projected as
            # {role,name,context,args} by design, so asserting `text` on those
            # would fail against a correct server.
            said = [m for m in msgs if m.get("role") in ("user", "assistant")]
            keyed_text = bool(said) and all("text" in m for m in said)
            record("session.history projects `text` (bug 3)",
                   keyed_text,
                   f"{len(msgs)} rows ({len(said)} conversational), "
                   f"keys={sorted(set(k for m in said for k in m))[:6]}")
            # The exact marker the phone must turn back into an image card.
            record("attached image is referenced in the persisted turn (bug 1/5)",
                   any("@image:" in (m.get("text") or "") for m in users),
                   "user turn carries @image: ref")
            record("history user turn is non-empty (bug 3)",
                   bool(users) and any((m.get("text") or "").strip() for m in users),
                   repr((users[0].get("text") if users else "")[:60]))
        except Exception as e:
            record("session.history projects `text` (bug 3)", False, str(e)[:160])

        # --- bug 4: the autonomy control the app now exposes ---
        try:
            cur = rpc.call("config.get", {"key": "approvals.mode"}).get("value")
            record("config.get approvals.mode (bug 4)", cur in {"manual", "smart", "off"}, cur)
            rpc.call("config.set", {"key": "approvals.mode", "value": "smart"})
            now = rpc.call("config.get", {"key": "approvals.mode"}).get("value")
            ok = now == "smart"
            rpc.call("config.set", {"key": "approvals.mode", "value": cur})  # restore
            back = rpc.call("config.get", {"key": "approvals.mode"}).get("value")
            record("config.set approvals.mode round-trips (bug 4)",
                   ok and back == cur, f"{cur} -> smart -> {back}")
        except Exception as e:
            record("config.get/set approvals.mode (bug 4)", False, str(e)[:160])

        # --- session-scoped autonomy, the per-chat switch ---
        try:
            res = rpc.call("config.set", {
                "key": "yolo", "value": "off", "scope": "session", "session_id": sid})
            record("config.set yolo scope=session (bug 4)",
                   res.get("scope") == "session", json.dumps(res)[:120])
        except Exception as e:
            record("config.set yolo scope=session (bug 4)", False, str(e)[:160])

        try:
            rpc.call("session.interrupt", {"session_id": sid})
        except Exception:
            pass

    print()
    bad = [r for r in results if r[0] == FAIL]
    print(f"{len(results) - len(bad)}/{len(results)} contract checks passed")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
