# PHASE 0 — FINDINGS (IN PROGRESS — blocked at 0.2, awaiting owner decisions)

**Date:** 2026-08-16 · **Executor:** Hermes Agent (kimi-k3)
**Status:** ⏸ Paused — two blockers need Sadman (see bottom). Protocol probes (0.4–0.8)
are staged and ready to run the moment the `.env` write is consented.

---

## 0.1 CLI surface — DONE (real flags, replacing spec §A.3 guesses)

`hermes dashboard --help` (verbatim facts):

| Flag | Reality |
|---|---|
| `--port PORT` | default **9119**, `0` = OS auto-assign |
| `--host HOST` | default **127.0.0.1** |
| `--insecure` | **DEPRECATED / NO-OP.** "As of the June 2026 hardening it no longer disables authentication — a public bind always requires an auth provider (password or OAuth)." |
| `--skip-build` | serve existing web `dist` (no npm needed) |
| `--isolated` | per-profile server instead of machine-level unified server |
| `--stop` / `--status` | manage running server processes |
| `--no-open` | don't auto-open browser |
| `register` | subcommand: Nous Portal OAuth registration |

- ❗ **`hermes web` does not exist.** The brief's 0.1 second command errors
  (`invalid choice: 'web'`). The dashboard is the only web server command.
- `dashboard --status` at probe time: **no dashboard processes running.** Port 9119 free.

## 0.3 Tailscale — ❌ NOT INSTALLED (brief-mandated STOP condition)

- `tailscale` not on PATH; no `C:\Program Files\Tailscale\`; no Tailscale Windows
  service (`OpenService FAILED 1060`); `ipconfig` shows no `100.x` interface.
- The brief: *"If Tailscale is not installed or the phone is not on the tailnet,
  stop and tell me — the whole networking model depends on it."* → **Told.**

## 🔴 ARCHITECTURE-RELEVANT DISCOVERY — spec §A.3 auth model is stale

Live code (`web_server.py`, `should_require_auth()`, `start_server()`) says:

1. **Any non-loopback bind now ALWAYS engages the auth gate.** `--insecure` is a
   no-op kept for backward compat. There is no "insecure-over-Tailscale, `?token=`
   only" mode anymore — the spec's recommended setup (§A.3 steps 1–3) **cannot be
   built as written**.
2. The gate fails **closed**: non-loopback bind with zero registered auth
   providers → `SystemExit` (server refuses to start).
3. Available providers: **basic password auth** (`dashboard.basic_auth.username` +
   `password_hash` in `config.yaml`, hash via
   `plugins.dashboard_auth.basic.hash_password`) or **OAuth** (Nous Portal
   `hermes dashboard register`, or a DashboardAuthProvider plugin).
4. In gated mode the WS credential is a **single-use 30 s `?ticket=`** minted by
   `POST /api/auth/ws-ticket`; legacy `?token=` is *unconditionally rejected*
   in gated mode (`_ws_auth_reason`). A bearer-token seam (`token_auth.py`)
   exists for machine callers, per-route opt-in — to be mapped in Phase 1.
5. `HERMES_DASHBOARD_SESSION_TOKEN` (`web_server.py:331`) still governs loopback
   mode exactly as the spec says — so the token work is not wasted: loopback +
   tunnel remains a valid deployment shape.

**Consequence for the app design:** the spec's `CredentialStrategy` (Token |
Ticket) was right, but **Ticket becomes the primary strategy**, not the future
option. QR pairing payload must carry dashboard *login* credentials (or an
OAuth/session artifact), and the client must mint a ticket per WS connect.
Phone flow: `POST /auth/password-login` → session → `POST /api/auth/ws-ticket`
→ `ws://…?ticket=` per connection. (REST bearer mapping TBD Phase 1.)

## 0.2 Stable session token — ⏸ BLOCKED ON CONSENT

- `D:\.hermes\.env` exists (984 B, Telegram/WhatsApp/API keys), has **no**
  `HERMES_DASHBOARD_SESSION_TOKEN`.
- Prepared: append `HERMES_DASHBOARD_SESSION_TOKEN=<token_urlsafe(48)>`.
  **Terminal approval prompt timed out — not consented. Not retried.**

## 0.4–0.8 Protocol probes — STAGED, not yet run

Plan (all on **127.0.0.1:9119 loopback** — frame shapes, latency, approval flow
and PTY framing are transport-independent; results remain valid for any final
network topology; zero new network exposure):

1. Start: `python -m hermes_cli.main dashboard --host 127.0.0.1 --port 9119 --no-open --skip-build`
2. `GET /api/status` sanity
3. `pc/probe_ws.py`: `gateway.ready` → `session.list` → `session.create` →
   `prompt.submit` (trivial) → capture literal `message.delta`/tool/turn frames →
   `session.interrupt` mid-turn test → latency measurement
4. Approval round-trip: trigger manual-approval turn, capture block frame,
   `approval.respond` unblock
5. `/api/pty`: framing, echo test, resize signalling

## Git checkpoint — DONE

`D:\HermesMobile` is now a git repo (`main`), initial commit
`checkpoint: v1 state before v2 overhaul`, `.gitignore` covers
build/.gradle/.kotlin/local.properties/*.apk/*.log. Never pushing (no remote).

---

## ⛔ WAITING ON SADMAN

1. **Consent** to append `HERMES_DASHBOARD_SESSION_TOKEN` to `D:\.hermes\.env`
   (the timed-out approval), so probes 0.4–0.8 can run.
2. **Networking decision** (Tailscale missing): install Tailscale / LAN+basic-auth
   / loopback+SSH tunnel / defer.
3. §12 housekeeping (GitHub PAT rotation) still pending — rotation needs your
   GitHub account; I can scrub `.bak` copies and rewire `config.yaml` to
   `key_env` once the new token exists.
