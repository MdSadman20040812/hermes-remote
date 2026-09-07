# Hermes Mobile — status

**Last verified:** 2026-09-07 — **on real hardware** (Galaxy A07, `R87YA00YSAD`)
**Project root:** `D:\HermesMobile`
**Desktop backend:** `D:\.hermes\hermes-agent`

---

## Verified working, end to end

Not "compiles" — actually exercised on the phone against the live dashboard:

| Step | Evidence |
|---|---|
| PC discovered on Wi-Fi | sweep of the phone's /24 found `192.168.1.111:9119` in 2.6 s |
| Gated handshake | `/api/status` 200 → `/auth/password-login` 200 → `/api/auth/ws-ticket` 200 → **`101 Switching Protocols`** |
| Prompt round trip | sent from the phone, ran on the PC, `PHONE_LINK_OK` streamed back into the transcript |
| Auto-reconnect | cold app start attached to the saved profile with no QR and no typing |
| Wi-Fi drop recovery | `svc wifi disable` → `enable` → back to a green cockpit unaided |
| Credential redaction | `?ticket=•••` in logcat; the real value never printed |

Unit tests: **57 passing**.

---

## Transport: LAN, not VPN

The phone and the PC are on the same home router, so the router *is* the
private network. Tailscale is still supported (`hermes-remote.ps1 -UseTailscale`)
for reaching the PC from outside the house, but it is no longer the default and
never a requirement — one fewer identity system that can be "not logged in".

`PrivateHosts` refuses to send a credential in cleartext to anything outside
RFC1918 / loopback / CGNAT. Android's `network-security-config` cannot express
that (its `<domain>` entries are hostnames, not CIDR), so the check is in code
and is unit-tested at the range boundaries.

---

## Fixed 2026-09-07

| Where | Was | Now |
|---|---|---|
| `ServerStatus.gatewayPlatforms` | typed `List<String>`; the server sends an **object**, so **every** `/api/status` decode threw. Surfaced as "Can't reach your PC" against a dashboard that was answering fine — a wrong type on a field nobody renders made the app unusable | `JsonElement` + `platformNames`, pinned by `ServerStatusTest` against a literal 0.21.0 capture |
| Windows network profile | the Wi-Fi was classified **Public**, so Windows dropped all inbound before any rule was consulted | `setup-lan.ps1` sets it Private and opens TCP 9119 for `LocalSubnet` on Private/Domain only |
| Firewall | nothing allowed inbound 9119; identical symptom to "app can't find PC" | created by `setup-lan.ps1`, re-checked on every `hermes-remote.ps1` run |
| DHCP drift | a moved lease meant the saved address was dead forever; only a re-pair recovered it | `LanDiscovery` sweeps the /24 and re-binds the profile (auto when exactly one Hermes answers) |
| Reconnect after Wi-Fi loss | a failed connect was **terminal** — the retry loop only ran off a socket that had already opened, so a link bounce left "Couldn't connect" next to a discovery card listing that same PC | every retryable failure goes through `failAndRetry`, which re-arms the loop |
| Link changes | reconnect was purely time-based; the app slept through Wi-Fi coming back | `NetworkMonitor` wakes the loop and resets the backoff on every transition |
| Half-open sockets | a silent socket looked healthy — connected, receiving nothing, not even retrying | 25 s application-level `ping`; failure calls `RpcChannel.markDegraded` |
| `connectTo` re-entrancy | the reconnect loop's own `connectTo` cancelled the coroutine it was running in, mid-connect | `connectLock` + a `reconnecting` flag; only outside callers cancel the loop |
| Camera preview | `PreviewView` defaults to a SurfaceView, a separate hardware layer that composites **above** Compose — it sliced the discovery card in half on device | `ImplementationMode.COMPATIBLE`, and pairing is now one mode at a time (Discover / Scan / Manual) |

---

## Pairing, as it actually goes now

1. PC: `powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\setup-lan.ps1` — **once, as admin**
2. PC: `powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\hermes-remote.ps1`
3. Phone: open Hermes → it lists the PC under **On this Wi-Fi** → tap it
4. First time only: paste `user:password` (or scan the QR, which carries it)

After that the app reconnects on its own — new IP, dropped Wi-Fi, cold start.

---

## Known gaps

- **Git panel is read-only.** Stage/commit/push exist server-side, but committing
  from a phone without a readable diff is how a bad turn becomes a bad commit.
- **Cron creation** stays on the desktop; the phone can pause, resume, run now.
- **Widget** opens the app but does not push live state.
- **Discovery assumes a /24.** Fine for a home router; a wider LAN needs the
  manual form.
- **Away from home**, the PC is unreachable without `-UseTailscale` or a tunnel.

---

## Verifying

```bash
cd D:\HermesMobile
./gradlew assembleDebug testDebugUnitTest
D:\Studio\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
D:\Studio\platform-tools\adb.exe shell am start -n com.hermes.mobile.debug/com.hermes.mobile.ui.MainActivity
D:\Studio\platform-tools\adb.exe logcat -d -s HermesLan HermesNet
```

`dumpsys activity activities | findstr com.hermes.mobile.debug` is the reliable
launch signal on this device; `dumpsys window` lies.
