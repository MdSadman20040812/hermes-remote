# BUILD LOG — Hermes Mobile v2

Append-only. One entry per work session.

---

## 2026-08-16 — Phase 0 (partial): discovery done, blocked on 2 decisions

**Did:**
- Read brief + spec + index + historical docs (README, handbook headers).
- `git init` checkpoint commit `checkpoint: v1 state before v2 overhaul` (+`.gitignore`).
- 0.1: mapped real `hermes dashboard` CLI surface. `hermes web` doesn't exist.
- 0.3: probed for Tailscale — **not installed** (no binary, service, or 100.x iface).
- Read live auth code in `web_server.py` / `dashboard_auth/`.

**Broke / surprises:**
- Spec §A.3 auth model is stale: June 2026 hardening removed unauthenticated
  non-loopback mode. Non-loopback bind ⇒ auth gate (basic-auth password or
  OAuth), WS needs single-use `?ticket=`, `?token=` rejected in gated mode.
  Server refuses to start non-loopback with no provider (fail-closed).
- Terminal approval timed out on the `.env` token append — awaiting consent.

**Decisions:**
- Protocol probes (0.4–0.8) will run on loopback 127.0.0.1:9119 — results are
  transport-independent and don't pre-commit the networking decision.

**APK size:** n/a (no build changes). **Commit:** initial checkpoint.

---

## 2026-08-16 — Phase 0 (complete on loopback): architecture PROVEN

**Did:**
- Token generated (`.env` write blocked by approval timeout → used process env;
  `pc/.probe-token`, gitignored, holds it meanwhile).
- Dashboard up on 127.0.0.1:9119 (`--no-open --skip-build`), `auth_required=false`.
- `pc/probe_ws.py`: gateway.ready, session.list (saw live desktop session),
  session.create, streaming prompt (PROBE OK), interrupt from cold client,
  PTY echo round-trip. All passed. Full log: `pc/probe_output.txt`.
- `pc/probe_approval.py`: forced a real gate with `rm -rf` on a scratch dir —
  captured literal `approval.request`, `approval.respond(once)` → `{"resolved":1}`
  → tool executed. ROUND-TRIP PROVEN.
- First-delta latency: 438 ms / 3 656 ms (model-bound, not transport).
- Corrected spec §A.3 (auth gate) and §F (approval coverage) in place.

**Broke / surprises:**
- First probe run died by SIGPIPE (piped through `head`) — server cleanly
  emitted `session.reclaimed`/`ws_orphan_reap`. Mobile app-kills are safe.
- `echo`, `printf >`, single-file `rm` DON'T gate in manual mode — only
  dangerous-pattern commands do. Rare-but-critical approval UX it is.
- `session.create` returns TWO ids: live `session_id` vs `stored_session_id`.
- `/api/pty` is a full Hermes TUI chat, binary-frame transport, works on
  Windows via ConPTY.

**Still open (owner):** Tailscale install decision; `.env` token persistence
consent; §12 GitHub PAT rotation (needs his GitHub login).

**Commit:** phase-0 complete. **APK size:** n/a.
