<div align="center">

# Hermes Remote

**Drive an AI coding agent on your PC from your phone — on any network, from anywhere.**

[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3ddc84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?logo=kotlin&logoColor=white)](#)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285f4?logo=jetpackcompose&logoColor=white)](#)
[![Server](https://img.shields.io/badge/PC-Python%20%2B%20PowerShell-3776ab?logo=python&logoColor=white)](#)
[![Transport](https://img.shields.io/badge/transport-LAN%20%2F%20Tailscale-6e5494)](#)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

*Your desktop keeps the context, the tools and the filesystem. Your phone is the terminal.*

</div>

---

## What this is

Hermes Remote is a two-part system that puts a long-running desktop AI agent in your
pocket. Sessions live on the **PC** — they keep running when the screen locks, when
the app is backgrounded, and when you walk out of the house. The phone is a thin,
stateful client that reattaches to whatever is already running.

```
┌──────────────────────┐         WireGuard / LAN          ┌───────────────────────────┐
│   Android client     │                                  │      Windows PC           │
│                      │  1. GET  /api/status             │                           │
│  Cockpit  ──────────▶│  2. POST /auth/password-login    │   Hermes dashboard  :9119 │
│  Sessions            │  3. POST /api/auth/ws-ticket     │   bound 0.0.0.0           │
│  Terminal            │  4. WS   /api/ws?ticket=•••      │                           │
│  Ops                 │◀────── 101 Switching Protocols ──│   Agent + tools + FS      │
└──────────────────────┘                                  └───────────────────────────┘
         │                                                             │
         └──────────── same tailnet, stable 100.x address ─────────────┘
```

The client never holds the work. Kill the app mid-generation, reopen it, and the
session is still there — because it never left the desktop.

---

## Why it exists

Phone-to-desktop agent bridges usually break in the same three places. This one is
built around those failures rather than around the happy path.

| Failure | What actually happens | How this handles it |
|---|---|---|
| **DHCP moves the PC** | Saved profile points at an IP the router reassigned; app says "can't find your PC" | Pair to the **tailnet address**, which never changes |
| **Dashboard dies at 3am** | Phone shows "paired" forever and never reconnects | Stateless watchdog re-runs every 2 min, reclaims a half-dead port |
| **Wi-Fi is classified Public** | Windows drops inbound before any rule is read | Preflight asserts `NetworkCategory=Private` + a scoped firewall rule |

---

## Feature tour

**Cockpit** — the live session. Streaming output, collapsible reasoning blocks,
per-message context usage, `Interrupt` and `Steer` while the agent is mid-thought.

**Sessions** — every session on the desktop, resumable. Start something on the PC,
pick it up on the phone.

**Terminal** — the desktop's real shell, not a simulation.

**Ops** — health, transport state, and the paired profile.

**File transfer, both directions** — a system share-target accepts `*/*` from any
app on the phone; a per-row download button pulls files back. Uploads stream
multipart (constant memory, no base64 inflation) against the server's own
`100 MB` ceiling.

---

## Repository layout

```
app/                        Android client (Kotlin, Compose, Material 3)
  core/connection/            ConnectionManager, CredentialStrategy, profiles
  core/net/                   PrivateHosts — the cleartext-egress guard
  core/transport/             REST + WebSocket clients
  ui/                         Cockpit · Sessions · Terminal · Ops

pc/                         Windows host side (Python + PowerShell)
  hermes-dashboard.py         Idempotent launcher; reuse-or-start, always prints the QR
  qr_server.py                Login-free pairing page on localhost:9120
  hermes-watchdog.ps1         Stateless revival loop for the dashboard
  install-always-on.ps1       Registers the SYSTEM scheduled task
  fix_dashboard_password.py   Re-syncs the credential across both stores
  fw-fix.ps1                  Firewall scope incl. the tailnet CGNAT range
  permanence.ps1              9-point audit: does this survive a reboot?
  phone_sim.py                Replays the app's exact 4-step handshake from the PC

docs/screenshots/           Device captures
```

---

## Quick start

### 1. PC side

```powershell
# one-time, elevated — dashboard survives reboot, logout and crashes
powershell -ExecutionPolicy Bypass -File pc\install-always-on.ps1

# one-time, elevated — open 9119 to the LAN and the tailnet
powershell -ExecutionPolicy Bypass -File pc\fw-fix.ps1
```

```powershell
# any time — start (or reuse) the dashboard and show the pairing QR
python pc\hermes-dashboard.py
```

### 2. Pairing page

```powershell
python pc\qr_server.py     # → http://localhost:9120
```

Bound to `127.0.0.1` only, deliberately: the page renders the dashboard password in
plaintext, so it must never be reachable off-box. It verifies the credential against
`config.yaml` **before** drawing a QR, and shows a fix hint instead of emitting a code
that cannot work.

### 3. Phone

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Scan the QR. Install first, pair second — `adb install -r` can drop the stored profile.

### 4. Prove it

```powershell
powershell -ExecutionPolicy Bypass -File pc\permanence.ps1
```

Checks the Tailscale service, both scheduled tasks, the firewall scope, the `0.0.0.0`
bind, and live reachability on loopback + LAN + tailnet.

---

## Making it network-independent

A LAN IP is a lease, not an address. Pair to the tailnet instead:

```powershell
tailscale up --unattended     # ForceDaemon=True — reconnects at boot with no login
```

Install Tailscale on the phone and sign in with the **same account**, then pair to the
PC's `100.x` address. Verified by disabling Wi-Fi entirely and retesting:

```
LAN     192.168.1.102:9119  → exit=1   (unreachable, as expected)
Tailnet 100.88.18.123:9119  → exit=0   (reachable over cellular)
```

`PrivateHosts.isPrivate()` explicitly allows `100.64.0.0/10` — CGNAT, where tailnet
addresses live — alongside RFC1918 and loopback. Anything else over plain HTTP is
refused rather than silently sending your password to a public host.

---

## Security model

- **Non-loopback binds are always gated.** The server refuses to bind off-loopback
  without an auth provider, and rejects `?token=` in that mode. The QR carries a
  basic-auth credential; session tokens only mean something on a loopback bind.
- **WS tickets are single-use with a 30-second TTL.** One is minted per socket.
- **Cleartext egress is guarded in code**, not config. Android's
  `network-security-config` takes hostnames, not CIDR ranges, so the private-range
  check lives at the one place a profile is created.
- **Credentials are redacted in logs** — `?ticket=•••` even at debug level.
- **Never committed:** `pc/.dashboard-password`, `known-devices.json` (MAC
  addresses, DHCP leases), `local.properties`. Full history was scanned for
  tokens, scrypt hashes, AWS keys and private keys before this repo was published.

---

## Diagnosing a failed pairing

Order matters. Replay the app's real handshake **from the PC** first:

```bash
python pc/phone_sim.py 100.88.18.123
```

```
OK  1 GET  /api/status            200
OK  2 POST /auth/password-login   200   → hermes_session_* cookies
OK  3 POST /api/auth/ws-ticket    200   → {"ticket", "ttl_seconds": 30}
```

**If all three pass from the PC but the phone still fails, the server is fine — the
problem is the network path, not auth.** That single distinction separates a firewall
scope bug from a credential bug, and they look identical from the phone.

Then, in order:

```powershell
Get-NetConnectionProfile | Select Name,NetworkCategory        # must be Private
(Get-NetFirewallRule -DisplayName 'Hermes Remote dashboard' `
  | Get-NetFirewallAddressFilter).RemoteAddress               # must include 100.64.0.0/10
Get-Content pc\watchdog.log -Tail 5                           # server-side cause in one line
```

```bash
adb shell 'toybox nc -w 5 <pc-ip> 9119 </dev/null; echo exit=$?'   # 0 = reachable
adb shell 'toybox nc -w 5 <pc-ip> 9999 </dev/null; echo exit=$?'   # must be 1, or the test proves nothing
```

### Known error states

| Symptom | Cause | Fix |
|---|---|---|
| `Outbox flush paused: RPC 4001: session not found` | Stale session id after a force-stop or dashboard restart. Transport is fine — the dot stays green. | Tap **+** for a new session. Do not re-pair. |
| App connects, then "can't reach your PC" | A JSON shape changed and every `/api/status` decode threw | Capture the real payload with `curl`, pin it in a test |
| `Port 9119 held by PID N but not answering` | Half-dead dashboard squatting the port | The watchdog reclaims its own dashboard; foreign processes are left alone |

---

## Engineering notes

Lessons that cost real debugging time, kept so they are not relearned:

- **A rule existing is not a rule working.** The firewall rule was `Enabled=True`
  on the right port, and still dropped every packet — its `RemoteAddress` was
  `LocalSubnet`, and a tailnet is not the local subnet. Always read the scope.
- **Never guess a server JSON shape.** `gateway_platforms` was typed
  `List<String>` while the server sends an object. That threw out of *every*
  status decode and surfaced as "Can't reach your PC" against a healthy server.
- **A green build says nothing about layout.** `PreviewView` defaults to a
  SurfaceView, which composites above Compose and sliced a card in half on device.
- **"It launched" is not a test.** The proof is a prompt sent from the phone that
  comes back answered, with `101 Switching Protocols` in the logs.
- **A watchdog that refuses to touch a port squatter never recovers.** The common
  failure is not a dead process; it is a half-dead one still holding the port.

---

## Requirements

| | |
|---|---|
| Phone | Android 10+ (API 29), ~45 MB |
| PC | Windows 10/11, Python 3.11+, Hermes dashboard on `:9119` |
| Build | JDK 17 (**not** 21+ — Gradle 8.9 rejects it), Gradle 8.9, Android SDK 34 |
| Network | Same Wi-Fi, or Tailscale on both devices |

---

## License

MIT — see [LICENSE](LICENSE).
