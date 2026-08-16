# PHASE 0 — FINDINGS (COMPLETE on loopback; tailnet deployment pending)

**Date:** 2026-08-16 · **Executor:** Hermes Agent (kimi-k3)
**Verdict:** ✅ **Architecture VALID.** The phone can speak the native protocol.
Every critical interaction — streaming, interrupt, approvals, PTY — proven
empirically against the live server from a cold Python client (`pc/probe_ws.py`,
`pc/probe_approval.py`). Two environmental blockers remain (Tailscale absent,
`.env` token write unconsented) — both are deployment concerns, not protocol risks.

---

## 0.1 CLI surface — real flags (replaces spec guesses)

`hermes dashboard`: `--host` (default 127.0.0.1) · `--port` (default 9119, 0=auto)
· `--insecure` (**DEPRECATED NO-OP**) · `--skip-build` · `--isolated` · `--stop` ·
`--status` · `--no-open` · `register` (Nous Portal OAuth).
❗ `hermes web` **does not exist** (brief §0.1's second command errors).

## 0.2 Session token — ⚠ partially done

`web_server.py:331` confirmed: `HERMES_DASHBOARD_SESSION_TOKEN` env, else random
per-boot token. A strong token (`token_urlsafe(48)`) was generated and used for
all probes via process env. **Persistence to `D:\.hermes\.env` was blocked twice
by approval-prompt timeout — still not written.** The probe token lives at
`pc/.probe-token` (gitignored) until then.

## 0.3 Tailscale — ❌ NOT INSTALLED (deployment blocker, reported)

No binary, no service, no `100.x` interface on warnerbros-PC. Owner decision
pending: install Tailscale / LAN+basic-auth / loopback+SSH tunnel.

## 0.4 Dashboard start — ✅

`dashboard --host 127.0.0.1 --port 9119 --no-open --skip-build` → ready in ~4 s,
`HERMES_DASHBOARD_READY port=9119`. Auth mode: `auth_required=false` (loopback).
`--skip-build` reuses the desktop app's dist — no npm needed.

## 0.5 REST — ✅ (loopback)

`GET /api/status` → full JSON: version 0.20.0, gateway running (PID 11248,
telegram connected), `active_sessions`, profiles list, `auth_required:false`.
**Note:** loopback REST needs NO token. Off-box REST test deferred (needs
networking decision).

## 0.6 JSON-RPC socket — ✅ ALL PROVEN

Transport: NDJSON, one JSON-RPC 2.0 object per line, text frames. Responses
correlate by `id`; server pushes `{"method":"event","params":{"type":…}}`.

**`gateway.ready` (first frame, literal shape):**
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{
  "skin":{…full theme…},"branding":{…},"change_events":true, …}}}
```
(payload is large — skin/branding/config echo. `change_events: true` confirmed:
server pushes `sessions.changed`, `session.reclaimed` etc. without polling.)

**Streaming turn (literal frames, `prompt.submit` → done):**
```json
{"type":"session.info","payload":{"model":"kimi-k3","provider":"kimi-coding",
  "approval_mode":"manual","tools":{…},"skills":{…}}}     ← full capability echo
{"type":"message.start","session_id":"aa14aae1"}
{"type":"thinking.delta","payload":{"text":"( •_•)>⌐■-■ brainstorming..."}}
{"type":"reasoning.delta","payload":{"text":"We"}}         ← batched ~33ms
{"type":"message.delta","payload":{"text":"PROBE"}}        ← coalescing confirmed
{"type":"reasoning.available","payload":{"text":"…full reasoning…"}}
{"type":"message.complete","payload":{"text":"PROBE OK",
  "usage":{"model":"kimi-k3","input":1418,"output":35,"context_percent":3,…},
  "status":"complete","reasoning":"…"}}
```
Also seen: `session.title` (auto-titling), `sessions.changed` (global, `session_id:""`).

**Tool calls (literal):**
```json
{"type":"tool.start","payload":{"tool_id":"tool_…","name":"terminal",
  "context":"echo HERMES-APPROVAL-PROBE-7f3a"}}
{"type":"tool.complete","payload":{"tool_id":"tool_…","name":"terminal",
  "args":{"command":"…","timeout":30},"duration_s":0.184,
  "result":{"output":"…","exit_code":0,"error":null}}}
```
(+ `tool.generating` while args stream.)

**Latency (prompt.submit → first delta):** 438 ms and 3 656 ms across runs —
dominated by model thinking, not transport. Deltas arrive pre-coalesced in
bursts (server-side ~33 ms confirmed by identical timestamps).

**`session.interrupt` from cold client:** ✅ `{"status":"interrupted"}` in
<300 ms; turn ends with empty `message.complete`.

**`session.create` result:** `{"session_id":"aa14aae1",
"stored_session_id":"20260816_162446_af183e","info":{…}}` — note the TWO ids:
`session_id` is the live handle (RPC param), `stored_session_id` is the
state.db id (what `session.list`/`session.resume` use). App must track both.

**`session.list`:** `{"sessions":[{"id","title","preview","started_at",
"message_count","source"}]}` — my live desktop session was visible and
identical. **One session universe confirmed.**

**Orphan hygiene:** when the probe died mid-turn (SIGPIPE), the server emitted
`session.reclaimed {reason:"ws_orphan_reap"}` and cleaned up. Good news for
mobile (app kills are graceful).

## 0.7 Approval flow — ✅ ROUND-TRIP PROVEN (with one big nuance)

Literal block frame:
```json
{"type":"approval.request","session_id":"c81c92be","payload":{
  "command":"rm -rf D:/HermesMobile/pc/scratch_dir",
  "pattern_key":"recursive delete","pattern_keys":["recursive delete"],
  "description":"recursive delete",
  "allow_permanent":true,"allow_session":true,
  "choices":["once","session","always","deny"]}}
```
`approval.respond {"session_id", "choice":"once"}` → `{"resolved":1}` → tool
executes; `tool.complete.result.approval` = *"Command required approval
(recursive delete) and was approved by the user."* Deny path also wired
(tested in code; choice `deny` resolves without executing).

**🔴 Nuance the spec misses:** `approvals.mode: manual` does NOT gate every
tool call. The terminal gate fires only for `DANGEROUS_PATTERNS` matches:
`echo`, `printf > file`, and even single-file `rm` all ran **without any
approval**. `rm -rf` gated. There is also a smart-approval LLM layer and a
fail-open default for non-interactive contexts. **App design consequence:**
`approval.request` is rare-but-critical — treat it as a high-priority
interrupt-style notification, not a routine dialog. Threat-model row "rogue
prompt → manual approval" in spec §F overstates coverage: only
dangerous-classified actions are gated.

## 0.8 `/api/pty` — ✅ WORKS ON WINDOWS

- Connects with `?token=` (loopback), auth/host/client gates share the
  `/api/ws` logic (close codes 4401/4403/4404/4408).
- **Framing: downstream BINARY frames** (`send_bytes`), upstream raw bytes
  written to the PTY. Not JSON. First bytes observed: `\x1b[?9001h\x1b[?1004h`.
- Echo round-trip verified (`echo PTY-PROBE-OK-9c1e` → marker received).
- Windows supported via `win_pty_bridge` (pywinpty/ConPTY) — the "POSIX only"
  close path was NOT hit.
- **It spawns a full Hermes TUI chat** (`_resolve_chat_argv_async`), not a bare
  shell. Keep-alive via `?attach=` token; `?resume=`, `?profile=`, `?fresh=`
  params; resize via `terminal.resize` RPC (bridge has `resize(cols,rows)`).
- Close codes: child EOF → 4410.

---

## Spec discrepancies found (corrected in spec where design-relevant)

| # | Spec claim | Live reality |
|---|---|---|
| 1 | §A.3 "insecure mode: non-loopback + `?token=`, any peer" | **Removed** June 2026. Non-loopback ⇒ auth gate (basic-auth password or OAuth), fail-closed without provider, `?token=` rejected in gated mode |
| 2 | `--insecure` flag works | No-op, kept for backward compat |
| 3 | Brief §0.1: `hermes web --help` | No such subcommand |
| 4 | §A.3 token is the phone credential | Token is loopback-only. Phone needs gated flow: login → `/api/auth/ws-ticket` → `?ticket=` (single-use, 30 s) per connect |
| 5 | §F "manual approval gates rogue prompts" | Only dangerous-pattern commands gate; benign commands never ask |
| 6 | §A.2 "PTY = terminal" | It's a full Hermes TUI chat session, not a bare shell |
| 7 | — (unmentioned) | `session.create` returns two ids (live handle vs stored id); clients must track both |
| 8 | — (unmentioned) | Dead WS clients get `session.reclaimed` — mobile app-kills are safe |

## What is NOT yet proven (deferred, owner-dependent)

- Off-box REST/WS from the phone (needs networking decision).
- `.env` token persistence (needs consent; command prepared).
- Gated-mode ticket flow end-to-end (needs a configured auth provider; will be
  exercised when the networking decision lands — the basic-auth path is
  documented in `web_server.py`'s startup hint).

## Phase 0 artefacts

`pc/probe_ws.py` (A–F + PTY), `pc/probe_approval.py` (forced-gate approval),
`pc/probe_output.txt` (full run log), `pc/.probe-token` (gitignored).
Probe dashboard still running: PID via `hermes dashboard --status`; stop with
`hermes dashboard --stop`.
