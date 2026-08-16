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
