"""Replicate EXACTLY what the phone app does, to find which step fails.

App handshake (from CredentialStrategy.kt):
  1. GET  /api/status            -> read auth_required
  2. POST /auth/password-login   -> sets hermes_session_* cookies
  3. POST /api/auth/ws-ticket    -> {"ticket", "ttl_seconds"}
  4. open WS with ?ticket=<ticket>
"""
import json
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.102"
PORT = 9119
BASE = f"http://{HOST}:{PORT}"

pw = Path(r"D:\HermesMobile\pc\.dashboard-password").read_text(encoding="utf-8").strip()
jar = CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def step(name, req):
    try:
        with opener.open(req, timeout=10) as r:
            body = r.read().decode()[:200]
            print(f"  OK   {name}: {r.status} {body}")
            return body
    except urllib.error.HTTPError as e:
        print(f"  FAIL {name}: {e.code} {e.read().decode()[:200]}")
    except Exception as e:
        print(f"  FAIL {name}: {type(e).__name__} {e}")
    return None


print(f"=== {BASE} ===")

step("1 GET /api/status", urllib.request.Request(f"{BASE}/api/status"))

body = json.dumps({"provider": "basic", "username": "sadman", "password": pw}).encode()
step("2 POST /auth/password-login",
     urllib.request.Request(f"{BASE}/auth/password-login", data=body,
                            headers={"Content-Type": "application/json"}))

print(f"  cookies: {[c.name for c in jar]}")

t = step("3 POST /api/auth/ws-ticket",
         urllib.request.Request(f"{BASE}/api/auth/ws-ticket", data=b"{}",
                                headers={"Content-Type": "application/json"}))

if t:
    try:
        print(f"  ticket ok -> ws://{HOST}:{PORT}/ws?ticket={json.loads(t).get('ticket','')[:12]}...")
    except Exception:
        pass
