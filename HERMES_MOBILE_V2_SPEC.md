# Hermes Mobile v2 — Architecture Spec & Rebuild Plan

**Codename:** Hermes Remote
**Author:** Claude (Cowork), commissioned by Sadman
**Date:** 2026-08-16
**Status:** Spec for approval. No code written yet.
**Supersedes:** `D:\HermesMobile\AGENT_HANDBOOK.md` (2025-08-08)

---

## 0. TL;DR

The existing app was built against an assumption that turned out to be false: that
the phone and the PC have no way to talk, so they must pass notes through Google
Drive. That assumption cost the design real-time streaming, interactive approvals,
terminal access, and roughly 90% of what Hermes can actually do.

Your PC Hermes install ships a **complete remote-control API** — a JSON-RPC
WebSocket with 120+ methods, a PTY terminal socket, a 112-endpoint REST surface,
and an event bus. Its own source says it exists for "an iOS / web client over
WebSocket." Nobody has to build a protocol. One already exists, it is versioned,
and it is running on your machine right now.

**The rebuild:** Hermes Mobile becomes a first-class native Hermes client speaking
that protocol over Tailscale, with Telegram demoted from "the bus" to "the doorbell."
Google Drive is removed entirely.

---

# PART A — What Hermes Actually Exposes

## A.1 Process topology on your machine

```
D:\.hermes\                          HERMES_HOME (state, config, sessions, skills)
D:\.hermes\hermes-agent\             the install (Python 3.11, venv, git checkout)
D:\.hermes\hermes-agent\venv\Scripts\python.exe -m hermes_cli.main <cmd>
```

Three long-lived processes, independently startable:

| Process | Command | What it is |
|---|---|---|
| **Gateway** | `gateway run` | Messaging bridge. Owns Telegram/WhatsApp/Discord adapters. Currently PID 11248, telegram `connected`, whatsapp `disconnected`. |
| **Dashboard** | `dashboard` (port **9119**) | FastAPI + React SPA. The full control plane. **This is what the phone should talk to.** |
| **Agent turns** | spawned per session | The actual model loop. Both of the above drive it. |

Live evidence from `D:\.hermes\gateway_state.json`:

```json
{"pid":11248,"kind":"hermes-gateway","gateway_state":"running",
 "platforms":{"telegram":{"state":"connected"},"whatsapp":{"state":"disconnected"}}}
```

And `channel_directory.json` — one Telegram DM, `8681921945` / "Sadman Masud".

## A.2 The four control planes

### Plane 1 — JSON-RPC over WebSocket: `ws://<host>:9119/api/ws`

**This is the spine of the new app.** From `tui_gateway/ws.py`:

> "Reuses `tui_gateway.server.dispatch` verbatim so every RPC method, every slash
> command, every approval/clarify/sudo flow, and every agent event flows through
> the same handlers whether the client is Ink over stdio or **an iOS / web client
> over WebSocket**."

Wire format: **newline-delimited JSON-RPC 2.0**, bidirectional, no framing quirks.

Request:
```json
{"jsonrpc":"2.0","id":17,"method":"prompt.submit","params":{"session_id":"...","text":"..."}}
```

Server → client event:
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{...}}}
```

On connect the server immediately emits `gateway.ready` with
`payload.change_events: true` — meaning the backend pushes `pet.changed`,
`cron.changed`, `sessions.changed`, so a client can drop polling entirely.

Streaming events (`message.delta`, `reasoning.delta`, `thinking.delta`) are
coalesced server-side on a ~33ms timer (~30fps) so a mobile client is not woken
once per token. Non-streaming frames (tool calls, approvals, status, completion)
flush ahead of the buffer and preserve ordering. **The backend was already tuned
for a battery-powered client.**

**The method surface (120+, complete list extracted from `tui_gateway/methods_*.py`):**

| Group | Methods |
|---|---|
| **Prompting** | `prompt.submit`, `prompt.background`, `llm.oneshot` |
| **Session lifecycle** | `session.create`, `session.list`, `session.active_list`, `session.activate`, `session.resume`, `session.close`, `session.delete`, `session.save`, `session.branch`, `session.title`, `session.most_recent` |
| **Live turn control** | `session.interrupt`, `session.steer`, `session.redirect`, `session.undo`, `session.compress`, `subagent.interrupt`, `subagent.steer` |
| **Session introspection** | `session.history`, `session.status`, `session.usage`, `session.context_breakdown`, `session.cwd.set`, `session.workspace.move` |
| **Interactive prompts** | `approval.respond`, `clarify.respond`, `sudo.respond`, `secret.respond`, `terminal.read.respond`, `window.read.respond`, `preview.read.respond` |
| **Attachments** | `file.attach`, `image.attach`, `image.attach_bytes`, `image.detach`, `pdf.attach`, `clipboard.paste`, `input.detect_drop` |
| **Execution** | `shell.exec`, `cli.exec`, `terminal.resize`, `process.list`, `process.stop`, `process.kill` |
| **Tools & config** | `tools.list`, `tools.show`, `tools.configure`, `toolsets.list`, `config.get`, `config.show`, `reload.env`, `reload.mcp`, `plugins.list`, `plugins.manage` |
| **Skills** | `skills.manage`, `skills.reload` |
| **Slash commands** | `slash.exec`, `command.dispatch`, `command.resolve`, `commands.catalog`, `complete.slash`, `complete.path` |
| **Models** | `model.options`, `model.save_key`, `model.disconnect`, `usage.bars` |
| **Automation** | `cron.manage`, `agents.list`, `delegation.status`, `delegation.pause`, `spawn_tree.list/load/save`, `handoff.request/state/fail` |
| **Projects** | `projects.tree`, `project.facts`, `projects.discover_repos`, `projects.project_sessions` |
| **History/rollback** | `rollback.list`, `rollback.diff`, `rollback.restore`, `learning.frames/detail/edit/delete` |
| **Misc** | `browser.manage`, `message.react`, `insights.get`, `verification.status`, `system.battery`, `setup.status`, `pet.*` (15 methods — Hermes has virtual pets) |

Everything the desktop TUI can do, the phone can do. That is the whole point of
the design.

### Plane 2 — REST: `http://<host>:9119/api/...` (112 endpoints)

Notable for mobile:

```
GET  /api/status                     agent/gateway health
GET  /api/sessions  /api/sessions/{id}  /api/sessions/search  /api/sessions/stats
GET  /api/files     POST /api/files/read  /api/files/mkdir  /api/files/upload-stream
GET  /api/system/stats               CPU/RAM/disk of the PC
GET  /api/analytics /api/analytics/usage /api/analytics/models
GET  /api/logs
GET/POST /api/cron/jobs              scheduled tasks
GET  /api/mcp/servers  /api/mcp/catalog
GET  /api/skills    POST /api/skills/toggle
GET  /api/model/options  POST /api/model/set
GET  /api/gateway   POST /api/gateway/{start,stop,restart}
GET  /api/env       POST /api/env/reveal
POST /api/auth/ws-ticket             mint single-use WS ticket
GET  /api/auth/me
```

`/api/files/upload-stream` is a streaming multipart endpoint — this is how large
attachments go phone → PC without base64-bloating the RPC socket.

### Plane 3 — PTY terminal: `ws://<host>:9119/api/pty`

A real pseudo-terminal streamed over WebSocket. Hermes' own web client ships
`lib/pty-mobile-input.ts`, `pty-reconnect.ts`, `pty-resume-sanitizer.ts` — **they
already solved mobile terminal input, reconnection, and scrollback sanitizing.**
Those files are a direct reference implementation for the Android terminal screen.

### Plane 4 — Event fan-out: `/api/pub` (publish) + `/api/events` (subscribe)

Channel-scoped broadcast, used by the dashboard sidebar for the live tool-call
feed. Also `/api/plugins/kanban/events` for the Kanban board and
`/api/audio/speak-stream` for streamed TTS.

## A.3 Auth model — and exactly how the phone gets in

> **⚠ 2026-08-16 empirical correction (Phase 0):** the "insecure" row below is
> **historical**. The June 2026 hardening removed unauthenticated non-loopback
> mode: `should_require_auth()` returns True for ANY non-loopback host,
> `--insecure` is now a NO-OP, and the server **refuses to start** on a
> non-loopback bind with no registered auth provider. In gated mode `?token=`
> is unconditionally rejected; the only WS credentials are single-use 30 s
> `?ticket=` (from `POST /api/auth/ws-ticket`) and the server-internal
> `?internal=` credential. Available providers: basic-auth password
> (`dashboard.basic_auth.username` + `password_hash` in config.yaml, hash via
> `plugins.dashboard_auth.basic.hash_password`) or OAuth (Nous Portal
> `hermes dashboard register`). **Phone flow is therefore: REST login → session
> → `/api/auth/ws-ticket` → `ws://…?ticket=` per connect.** The
> `CredentialStrategy` Ticket branch below is the primary path, not a future
> option. (Verified against `web_server.py` + live `dashboard --help`.)

From `web_server.py`, three modes (pre-hardening; see correction above):

| Mode | Trigger | WS credential | Peer restriction |
|---|---|---|---|
| **loopback** | bound to 127.0.0.1, no auth | `?token=<_SESSION_TOKEN>` | loopback peers only |
| **insecure** | bound to a non-loopback host, no auth | `?token=<_SESSION_TOKEN>` | **any peer** |
| **gated** | `auth_required=true` | `?ticket=<single-use, 30s TTL>` from `POST /api/auth/ws-ticket` | any peer |

The gated-mode docstring is explicit:

> "`?ticket=<single-use>` — a browser-minted, single-use, 30s-TTL ticket consumed
> against the dashboard-auth ticket store. **This is what the SPA (and native
> clients) use.**"

**Recommended setup for you (Tailscale):**

1. Set a stable token instead of the random per-boot one:
   `HERMES_DASHBOARD_SESSION_TOKEN=<64 random chars>`
   (read at `web_server.py:331` — otherwise it regenerates every restart and the
   phone has to re-pair after every reboot.)
2. Bind the dashboard to the Tailscale interface IP (`100.x.y.z`), **not** `0.0.0.0`.
   Non-loopback bind ⇒ the peer-IP gate passes; the token is the auth boundary;
   Tailscale is the network boundary. Nothing is exposed to the public internet.
3. REST: `Authorization: Bearer <token>`. WS: `?token=<token>`.
4. **Host/Origin gate:** `_ws_host_origin_reason()` rejects a WS whose `Host`
   header doesn't match the bound host. The Android client must dial the exact
   bound host:port (`100.x.y.z:9119`), not a rewritten one. OkHttp does this
   correctly by default — just don't add a custom `Host` header.

Later, if you ever want to reach it without Tailscale, flip to gated mode and the
same client code adds one REST call (`/api/auth/ws-ticket`) before each connect.
Design the transport layer with a `CredentialStrategy` interface from day one so
this is a one-class change.

Verify the exact flag names with `hermes dashboard --help` before wiring the
launcher — `--stop` and `--status` are confirmed in `_parser.py`; host/port/auth
flags live in `main.py` and I did not read all 509KB of it.

## A.4 The Telegram gateway — and why the current app's plan cannot work

Hermes' Telegram adapter uses **python-telegram-bot long polling** (`get_updates`
with a persisted offset; `base.py` even documents advancing the offset across a
`/restart` so a command isn't processed twice).

Telegram's `getUpdates` is **exclusive per bot token.** Two long-pollers on one
token means each steals updates from the other and Telegram starts returning
`409 Conflict`. The AGENT_HANDBOOK's §12.2 plan — "the Android app needs the
**same bot token** so it can send messages to the same bot" — would have
intermittently broken your live gateway. Sending (`sendMessage`) is safe; it's
`getUpdates` that collides. Worth knowing before someone tries it.

Also: a bot cannot DM another bot, so the phone app can't "be a Telegram user"
talking to Hermes without either a second bot or a full MTProto user-client.

**Verdict:** Telegram stays, but only in the role it's uniquely good at — see §C.4.

## A.5 Bonus: the relay contract

`docs/relay-connector-contract.md` (48KB) specifies a WebSocket relay where the
gateway **dials out** to a connector that fronts a chat platform. Elegant, but
it's the wrong shape here: it would require the phone to run a reachable
WebSocket *server*. Noted and rejected. Its `CapabilityDescriptor` /
`supported_ops` idea is worth stealing for the app's own feature-negotiation
though (§C.7).

---

# PART B — Audit of the Current App

## B.1 What's actually there

`com.hermes.mobile`, ~60 Kotlin files, Compose + Material 3 + Hilt + Room +
WorkManager. Debug APK builds (70.8 MB, `app-debug.apk`, 2026-08-06). Structure
is clean — package layout, DI, repository pattern, theme system are all sound.

## B.2 The handbook is out of date in ways that matter

| Handbook claim | Reality |
|---|---|
| "Source of truth: `D:\Outputs\HermesMobile\`" | Project is at `D:\HermesMobile\` |
| "38 Kotlin files" | ~60 |
| "`HermesAppPlaceholder.kt` is the active shell, `NavGraph.kt` unused" | Both are gone; `HermesApp.kt` is the shell |
| §8.2 "All Completed" | ViewModels exist and are wired, but everything terminates at Drive. No file named `HermesSyncWorkerFactory.kt` or `SettingsScreen` stub as described |
| §12.2 "app needs the same bot token" | Would break the live gateway (§A.4) |

Treat the handbook as a historical artifact. This document replaces it.

## B.3 The structural problems

1. **Google Drive as an RPC bus.** Round-trip latency: WorkManager periodic
   minimum is 15 minutes, plus Drive propagation, plus PC watcher poll (15s).
   Worst case ~30 minutes to see a reply. Streaming is impossible. Interactive
   approvals are impossible.
2. **A second, divergent agent runtime.** `D:\HermesMobileWatcher\watcher.py` is
   a 15KB parallel implementation of "run the agent" that will drift from Hermes
   proper on every Hermes update. Its `workspace/` is separate from your real
   session state, memory, skills, and projects. Tasks dispatched from the phone
   land in a different universe than tasks dispatched from your desk.
3. **Two sources of truth for identity.** Bot token in the app's DataStore, bot
   token in Hermes config, Drive OAuth in a third place, folder IDs converged by
   name-matching heuristic.
4. **No live state.** The app cannot show a running turn, cannot interrupt one,
   cannot answer a question the agent asks.
5. **Security posture.** Bot token in plaintext DataStore, Room unencrypted,
   `security-crypto` is on the dependency list but `SecureTokenStore.kt` should
   be audited for whether it's actually on the write path.
6. **APK weight.** 70.8 MB debug. `google-api-services-drive` + `play-services-auth`
   + ExoPlayer + PdfViewer + Markwon. Dropping Drive/GMS should roughly halve it.

## B.4 Keep / rewrite / delete

**Keep as-is (good work, still correct):**
- `ui/theme/*` — Color.kt, Type.kt, Shape.kt. Palette (`#7C5CFC` primary,
  `#39D353` accent) is solid; extend rather than replace.
- `ui/components/StatusChip.kt`, `SettingsBar.kt` — reusable.
- `HermesApplication.kt`, `di/AppModule.kt` — skeleton is fine, contents change.
- Gradle/manifest/proguard scaffolding, launcher icons.

**Rewrite (concept survives, implementation doesn't):**
- Every ViewModel (`Tasks`, `Files`, `Messages`, `Submit`, `Settings`) — same
  screens, entirely new data source.
- `data/local/*` — Room schema changes from "task queue" to "session/message/event cache."
- `ui/screens/FilesScreen.kt`, `FilePreviewDialog.kt` — repoint from Drive to `/api/files`.
- `ui/HermesApp.kt` — new navigation model (§D.1).
- `SecureTokenStore.kt` — becomes the credential vault for connection profiles.

**Delete:**
- `data/remote/DriveRemoteDataSource.kt`, `dto/DriveDtos.kt`,
  `domain/repository/DriveRepository.kt`, `data/local/drive_extensions.kt`
- `ui/auth/GoogleSignInHelper.kt`, `ui/screens/AuthScreen.kt` (Google half)
- `core/work/HermesSyncWorker.kt` + `WorkModule.kt` (polling model is obsolete)
- `data/remote/TelegramRemoteDataSource.kt` — the `getUpdates` half at minimum
- All GMS/Drive Gradle dependencies
- `D:\HermesMobileWatcher\` — retire entirely once v2 lands

---

# PART C — Hermes Remote: New Architecture

## C.1 Design principles

1. **The phone is a thin, beautiful client. Hermes is the brain.** Zero agent
   logic on Android. No second runtime to maintain.
2. **One session universe.** A session started on the phone is the same session,
   in the same `state.db`, with the same memory and skills, as one started at
   your desk. Walk away mid-turn and pick it up on the couch.
3. **Streaming or nothing.** If the desktop sees it token-by-token, so does the phone.
4. **Fail visibly, degrade gracefully.** Connection state is always on screen.
   When the socket is down, the app says so and queues.
5. **The network is the security boundary.** Tailscale + token. No public exposure.

## C.2 Transport stack

```
┌─────────────────────── ANDROID ────────────────────────┐
│                                                        │
│  UI (Compose)                                          │
│      ↕ StateFlow                                       │
│  Repositories  ── SessionRepo, EventRepo, FileRepo,    │
│                   ApprovalRepo, TerminalRepo, OpsRepo  │
│      ↕                                                 │
│  ┌──────────────────────────────────────────────────┐  │
│  │ HermesClient (the one thing that talks to Hermes)│  │
│  │  • RpcChannel      → ws://host:9119/api/ws       │  │
│  │  • RestClient      → http://host:9119/api/*      │  │
│  │  • PtyChannel      → ws://host:9119/api/pty      │  │
│  │  • EventChannel    → ws://host:9119/api/events   │  │
│  │  • CredentialStrategy (Token | Ticket)           │  │
│  │  • ReconnectPolicy (exp. backoff + jitter)       │  │
│  └──────────────────────────────────────────────────┘  │
│      ↕                                                 │
│  ConnectionManager — profile selection, reachability   │
└────────────────────────────────────────────────────────┘
                          │ Tailscale (WireGuard)
                          ▼
┌─────────────────── WINDOWS PC ─────────────────────────┐
│  hermes dashboard  →  100.x.y.z:9119                   │
│  hermes gateway run →  Telegram (doorbell only)        │
│  Hermes agent core: sessions, skills, memory, tools    │
└────────────────────────────────────────────────────────┘
```

**`RpcChannel` contract:**

```kotlin
interface RpcChannel {
    val state: StateFlow<ChannelState>          // Disconnected/Connecting/Ready/Degraded
    val events: SharedFlow<HermesEvent>          // server-pushed events
    suspend fun <T> call(method: String, params: JsonObject, timeout: Duration = 30.s): Result<T>
    fun callStreaming(method: String, params: JsonObject): Flow<HermesEvent>
}
```

Implementation notes:
- OkHttp `WebSocket`, newline-delimited JSON, `kotlinx.serialization`.
- Correlate responses by `id` via a `ConcurrentHashMap<Int, CompletableDeferred>`.
- Events without an `id` fan out to `events`.
- Coalesce `*.delta` on the UI side too — batch into the transcript at ~60fps
  rather than recomposing per frame.
- Heartbeat: OkHttp `pingInterval(20s)`. Detect half-open sockets on mobile
  networks where TCP lingers after a handoff.

**Reconnect policy:** 0.5s → 1s → 2s → 4s → 8s → 15s cap, ±20% jitter, reset on
`gateway.ready`. On reconnect: re-`session.activate` the current session and
`session.history` from the last known message ordinal to backfill anything missed.

## C.3 Connection profiles — and QR pairing

**The problem:** typing a 64-char token and a `100.x.y.z` address on a phone
keyboard is miserable and you'd do it once per PC per reinstall.

**The solution — QR pairing.** A tiny PC-side helper (§G) prints a QR encoding:

```json
{"v":1,"name":"warnerbros-PC","host":"100.92.14.7","port":9119,
 "token":"<session token>","fingerprint":"<sha256 of token>","tailnet":"sadman.ts.net"}
```

Point the phone at the screen. Paired. Under 5 seconds, nothing typed, no token
ever in a clipboard or a chat log. This is the single highest-leverage UX decision
in the whole app.

The app stores an ordered list of `ConnectionProfile`s (you may add a laptop
later). On resume, `ConnectionManager` races reachability probes
(`GET /api/status`, 1.5s timeout) across profiles and binds the first that answers.

```kotlin
data class ConnectionProfile(
    val id: String,
    val label: String,           // "Desktop", "Laptop"
    val host: String,            // 100.92.14.7
    val port: Int = 9119,
    val credential: Credential,  // Token | DashboardLogin
    val transport: Transport,    // Tailscale | Lan | Tunnel
    val lastSeenAt: Instant?,
    val isDefault: Boolean,
)
```

## C.4 The wake problem — where Telegram earns its keep

A WebSocket dies when Android freezes the app. So: **how does the phone learn
that a 40-minute background task finished, or that the agent is blocked on an
approval, when the app isn't running?**

Options considered:

| Approach | Verdict |
|---|---|
| Foreground service holding the WS 24/7 | Battery hostile, Android 14 restrictions, will get killed |
| Firebase Cloud Messaging | Correct but needs a Firebase project, a server key on the PC, and routes your agent's notifications through Google |
| **Telegram bot as push transport** | ✅ **Chosen** |
| Periodic WorkManager poll | 15-min floor — same problem we're escaping |

**Telegram-as-doorbell.** Your gateway is *already* connected to your Telegram DM
and Telegram's own app already has a battle-tested push channel to your phone.
Hermes sends you a Telegram message on task completion / approval-needed. Two ways
to consume it, in order of elegance:

1. **Deep links (recommended).** Hermes' notification includes a
   `hermes://session/<id>` link. Tap the Telegram notification → Hermes Remote
   opens straight to that session and reconnects. Zero extra permissions, zero
   extra services, no polling. Telegram does the push; your app does the UI.
2. **Notification listener (optional power-user mode).** With
   `BIND_NOTIFICATION_LISTENER_SERVICE`, the app reads incoming Telegram
   notifications from your Hermes bot, matches a signed prefix, and silently
   wakes its own socket to sync — surfacing a *native* Hermes notification with
   Allow/Deny actions instead of a plain Telegram one. Intrusive permission;
   make it opt-in, off by default, clearly explained.

This is the creative middle path you asked for: **Telegram carries the doorbell,
the JSON-RPC socket carries the conversation.** No second bot token needed —
Hermes' existing gateway sends; the phone never calls `getUpdates`, so no conflict.

Keep FCM as a documented Phase 4 upgrade if you ever want native push without
Telegram in the loop.

## C.5 Module structure

```
com.hermes.remote/
├── HermesRemoteApp.kt
│
├── core/
│   ├── transport/
│   │   ├── HermesClient.kt          facade
│   │   ├── RpcChannel.kt            /api/ws — JSON-RPC 2.0 NDJSON
│   │   ├── RestClient.kt            /api/* — OkHttp + Retrofit-style
│   │   ├── PtyChannel.kt            /api/pty
│   │   ├── EventChannel.kt          /api/events
│   │   ├── ReconnectPolicy.kt
│   │   └── Frames.kt                serialization for every RPC + event
│   ├── connection/
│   │   ├── ConnectionManager.kt     profile racing, reachability
│   │   ├── ConnectionProfile.kt
│   │   ├── CredentialStrategy.kt    Token | Ticket
│   │   └── QrPairing.kt             CameraX + MLKit barcode
│   ├── vault/SecureVault.kt         EncryptedSharedPreferences + StrongBox
│   └── notify/
│       ├── HermesNotifier.kt        channels, approval actions
│       └── TelegramDoorbell.kt      deep-link handler (+ optional listener)
│
├── data/
│   ├── cache/                       Room: sessions, messages, events, files
│   ├── outbox/                      offline prompt queue
│   └── repo/
│       ├── SessionRepository.kt     session.* + prompt.*
│       ├── TranscriptRepository.kt  delta assembly, tool timeline
│       ├── ApprovalRepository.kt    approval/clarify/sudo/secret
│       ├── FileRepository.kt        /api/files + upload-stream
│       ├── OpsRepository.kt         status, system.stats, process.*, gateway.*
│       ├── AutomationRepository.kt  cron.manage, agents.list, kanban
│       └── ConfigRepository.kt      models, skills, tools, MCP
│
├── domain/model/                    Session, Turn, ToolCall, ApprovalRequest, …
│
├── ui/
│   ├── shell/                       adaptive nav, connection banner
│   ├── cockpit/                     THE main screen (§D.2)
│   ├── sessions/                    list, search, resume, branch
│   ├── terminal/                    PTY view
│   ├── files/                       PC file browser
│   ├── automation/                  cron, kanban, agents
│   ├── ops/                         system stats, processes, usage/cost
│   ├── settings/                    profiles, models, skills, tools, MCP
│   └── theme/                       (extended from v1)
│
└── service/
    ├── TurnForegroundService.kt     holds WS only while a turn is live
    └── QuickTileService.kt          quick-settings "Dispatch to Hermes"
```

## C.6 Room cache schema (replaces the task-queue schema)

| Entity | Purpose |
|---|---|
| `SessionEntity` | id, title, cwd, model, source, createdAt, lastActiveAt, tokenUsage, status |
| `MessageEntity` | sessionId, ordinal, role, content, isComplete, createdAt |
| `ToolCallEntity` | sessionId, messageOrdinal, tool, args, result, status, durationMs |
| `ApprovalEntity` | id, sessionId, kind, prompt, options, state, respondedAt |
| `OutboxEntity` | pending prompts composed offline, with attachments |
| `FileCacheEntity` | remote path, local path, mtime, size, mime |
| `ProfileEntity` | connection profiles (credentials in the vault, not here) |

Cache is **derived state, never authoritative.** Hermes' `state.db` is the truth.
On reconnect, reconcile from `session.history`. This is what kills the whole class
of sync bugs the Drive design had.

## C.7 Capability negotiation

Borrowed from the relay contract. On `gateway.ready`, call `commands.catalog` +
`tools.list` + `config.get` and build a `ServerCapabilities` object. Screens that
depend on a missing capability render a tasteful "not available on this Hermes
build" state rather than crashing. This matters because Hermes updates fast — your
install is a live git checkout.

---

# PART D — UI / UX Design

You asked for sleek, smooth, minimal. Here's what that means concretely.

## D.1 Navigation

Drop the 5-tab bottom bar. It flattens a hierarchy that isn't flat.

```
Cockpit  ·  Sessions  ·  Ops        ← bottom bar, 3 items
                                       (thumb-reachable, no crowding)

Everything else lives one level down:
  Cockpit  → attachments, terminal (peer sheet), approvals (inline)
  Sessions → search, branch, history, project tree
  Ops      → files, processes, cron, kanban, usage, settings
```

Use `NavigationSuiteScaffold` (Material 3 adaptive) so a tablet or a folded-open
device gets a rail + two-pane layout for free.

## D.2 The Cockpit — the screen the app is really about

Single scrolling column, dense but calm:

```
┌────────────────────────────────────────┐
│ ● Desktop · kimi-k3 · 34% ctx      ⋮  │  ← status rail, always visible
├────────────────────────────────────────┤
│                                        │
│  ┌── you ─────────────────────────┐    │
│  │ refactor the auth module       │    │
│  └────────────────────────────────┘    │
│                                        │
│  ╭─ thinking ──────────────╮           │  ← collapsible, dimmed, monospace
│  │ The auth module has …   │           │
│  ╰─────────────────────────╯           │
│                                        │
│  ⚙ read_file  auth.py         120ms ✓  │  ← tool timeline: one line each,
│  ⚙ edit_file  auth.py          89ms ✓  │    expandable to full args/result
│  ⚙ terminal   pytest…        running ⟳ │
│                                        │
│  Hermes                                │
│  I've split the token refresh into…    │  ← streams token by token
│                                        │
├────────────────────────────────────────┤
│ ⏸ interrupt   ↪ steer          [====] │  ← live-turn controls, only while running
├────────────────────────────────────────┤
│ 📎  [ message Hermes…          ]  🎙 ➤ │
└────────────────────────────────────────┘
```

Details that make it feel good:
- **Streaming without jank.** Buffer deltas, apply at 60fps, `AnimatedContent`
  on the trailing word only. Never recompose the whole transcript per token.
- **Tool calls are first-class, not log spam.** One dense line, tap to expand.
  Status dot animates. This is the thing that makes remote agent work legible.
- **Interrupt is always one tap away** while a turn runs. `session.interrupt`.
- **Steer** (`session.steer`) is the sleeper feature: redirect a running turn
  without killing it. Almost no agent client exposes this. Yours will.
- **Approvals appear inline** as a card in the transcript *and* as a notification
  with Allow/Deny actions. Answering either resolves both.
- **Haptics:** light tick on turn start, double on completion, sharp on approval
  request. You'll learn to read the phone in your pocket.

## D.3 Visual language

Extend the existing palette rather than replacing it.

- **Base:** near-black `#0B0B0F`, elevated surfaces `#141419`, hairline borders at
  8% white. OLED-friendly — real battery savings on an always-glanceable app.
- **Primary** `#7C5CFC` (keep), **accent** `#39D353` (keep) for healthy/connected,
  amber `#FFB020` waiting, red `#FF4D4D` failed/disconnected.
- **Type:** JetBrains Mono for tool output/terminal/paths; a clean geometric sans
  (Inter or Space Grotesk) for chat. Hermes' own dashboard has strict contrast
  rules — mirror the spirit: no text below 12sp, no text opacity below 0.7.
- **Motion:** Material 3 expressive springs. Shared-element transition from a
  session row into the cockpit. Nothing longer than 300ms.
- **Dynamic color:** support Material You on Android 12+ as an *option*, with the
  Hermes brand palette as default. Brand identity first, personalization second.
- **Splash:** `androidx.core.splashscreen`, logo → connection dot animation.

## D.4 Accessibility & polish (the v1 handbook's §8.3 list, actually done)

`contentDescription` on every interactive element · skeleton shimmer instead of
spinners · `SnackbarHost` wired to a `userMessage` channel in every ViewModel
(no more silent `catch {}`) · predictive back · edge-to-edge · 48dp touch targets ·
TalkBack pass on the cockpit.

---

# PART E — Feature Set

## E.1 Tier 1 — the core (defines the product)

1. **Live agent cockpit** — streaming, tool timeline, thinking, interrupt, steer.
2. **Session continuity** — list/search/resume/branch. Your desktop sessions,
   on your phone, mid-turn.
3. **Interactive approvals** — approval/clarify/sudo/secret, in-app and as
   notification actions. This is what makes unsandboxed automation safe to run
   while you're away from the keyboard.
4. **QR pairing** (§C.3).
5. **Connection intelligence** — profile racing, honest state banner, offline outbox.

## E.2 Tier 2 — the leverage

6. **Embedded terminal** (`/api/pty`) with a mobile key row (Esc Tab Ctrl ↑↓←→ |
   ~ /). Port the logic from Hermes' own `pty-mobile-input.ts`.
7. **PC file browser** (`/api/files`) — browse, preview (image/PDF/text/markdown —
   the v1 `FilePreviewDialog` largely survives), download, upload via
   `upload-stream`. Your whole PC, from the couch.
8. **Android share-sheet target.** Share a URL from Chrome, a photo from Gallery,
   a PDF from Drive → "Send to Hermes" → attaches and prompts. This turns the app
   from a destination into a system-wide capability.
9. **Voice.** Hold-to-talk → Android STT (or upload to Hermes' `stt`, which is
   already enabled with Whisper) → `prompt.submit`. Replies read back via
   `/api/audio/speak-stream`. Your config already has `tts.provider: openai` and
   `stt.enabled: true`. Hands-free agent dispatch while driving or walking.
10. **Ops dashboard** — `system.stats` (PC CPU/RAM/disk), `process.list` +
    `process.kill`, `gateway.start/stop/restart`, `/api/logs` tail.
11. **Cost & usage** — `/api/analytics/usage`, `usage.bars`, per-session token
    burn. You're running 20+ models across 4 providers; seeing spend on the phone
    is genuinely useful.

## E.3 Tier 3 — the delightful

12. **Home-screen widget + Quick Settings tile** — "Dispatch to Hermes" from
    anywhere, one tap, no app launch. Shows a live dot when a turn is running.
13. **Cron manager** (`cron.manage`, `/api/cron/jobs`) — see and edit your
    scheduled Hermes tasks from the phone.
14. **Kanban board** (`/api/plugins/kanban/events`) — your `kanban.db` has live
    dispatch enabled; a mobile board view of what the agent is working through.
15. **Model switcher** (`model.options` / `/api/model/set`) — swap kimi-k3 →
    GLM-5.2 → MiniMax-M3 mid-conversation from a bottom sheet.
16. **Skills browser** (`/api/skills`, `skills.manage`) — toggle skills remotely.
17. **Multi-agent view** (`agents.list`, `spawn_tree.list`, `delegation.status`) —
    when Hermes spawns subagents, watch the tree live and `subagent.steer` one.
18. **Panic button** — interrupt every running session + optionally stop the
    gateway. One long-press. For when an unsandboxed agent goes somewhere you
    didn't intend.
19. **Wear OS companion** (stretch) — approve/deny from the wrist. The approval
    payload is small and the interaction is binary; it's a near-perfect watch use case.
20. **Pets** 🥚 — Hermes has 15 `pet.*` RPC methods. Render them. It costs a day
    and it's the kind of thing that makes an app yours.

---

# PART F — Security

Since this is **unsandboxed, full-authority remote execution**, the threat model
deserves a straight look.

| Threat | Mitigation |
|---|---|
| Phone lost/stolen | Biometric gate (`BiometricPrompt`) on app open and again before any destructive op. Token in `EncryptedSharedPreferences` with StrongBox when available. Remote revoke = rotate `HERMES_DASHBOARD_SESSION_TOKEN` and restart the dashboard. |
| Network interception | Tailscale = WireGuard, E2E encrypted. Nothing on the public internet. No port forwarding. |
| Token leakage | Never in logs, never in the clipboard, never in a chat message. QR pairing means it's never typed. Store a fingerprint for display; show only last 4 chars in the UI. |
| Dashboard exposed by accident | Bind to the tailnet IP explicitly, never `0.0.0.0`. Add a startup assertion in the launcher script. (Post-hardening bonus: any non-loopback bind force-enables the auth gate — fail-closed.) |
| Rogue prompt / prompt injection | Keep `approvals.mode: manual` for `shell`/`file`-write toolsets even with the phone in the loop. **Phase 0 caveat:** manual mode gates only dangerous-pattern commands — benign commands (`echo`, single-file `rm`, file writes) run ungated. The phone's approval UI must treat `approval.request` as rare-but-critical. |
| Agent runs away while you're asleep | Panic button (§E.3 #18) + `agent.max_turns: 150` already set + turn-count and cost alerts pushed to the phone. |

**⚠️ Immediate finding, unrelated to the app:** `D:\.hermes\config.yaml` contains a
**GitHub Personal Access Token in plaintext** under `mcp_servers.github.env.GITHUB_PERSONAL_ACCESS_TOKEN`
(starts `ghp_FoO3X8…`). That file is world-readable on your disk, is backed up in
three `.bak` copies alongside it, and I was able to read it through a folder share.
**Rotate that token and move it to an env var / your `.env`.** Several other
services in that file read from `key_env` correctly — the GitHub one is the outlier.

---

# PART G — PC-Side Companion

The v1 design needed a 15KB Python watcher. v2 needs about 80 lines, and it runs
nothing — it only makes the dashboard easy to start and pair.

`D:\HermesMobile\pc\hermes-remote.ps1`:

1. Reads/creates a stable `HERMES_DASHBOARD_SESSION_TOKEN` in `D:\.hermes\.env`.
2. Detects the Tailscale IPv4 (`tailscale ip -4`).
3. Starts `hermes dashboard` bound to that IP on 9119.
4. Renders a QR of the pairing payload (§C.3) in the terminal.
5. Optionally registers a Windows scheduled task so it survives reboot, matching
   the pattern of your existing `Hermes_Gateway.cmd` / `.vbs`.

`D:\HermesMobileWatcher\` gets archived. Its `workspace/` should be checked for
anything you want to keep first.

---

# PART H — Build Plan

| Phase | Scope | Output |
|---|---|---|
| **0 · Proving** | Start dashboard on tailnet IP, connect from phone browser, hand-drive `/api/ws` with a scratch WS client, confirm `prompt.submit` streams | Go/no-go on the whole design, in an afternoon |
| **1 · Spine** | Strip Drive/GMS. `HermesClient` + `RpcChannel` + `RestClient`. Connection profiles + QR pairing. Connection state UI | App connects, shows PC status, survives reconnects |
| **2 · Cockpit** | Session list, transcript with streaming deltas, tool timeline, prompt submit, interrupt/steer, Room cache | **The app is useful.** Ship to your own phone here |
| **3 · Authority** | Approvals (in-app + notification actions), Telegram doorbell deep links, foreground service for live turns, offline outbox | Safe unattended operation |
| **4 · Reach** | Terminal (PTY), file browser, share-sheet target, attachments via upload-stream | Full remote control |
| **5 · Ops** | System stats, processes, cron, usage/cost, model switcher, skills | Admin surface |
| **6 · Delight** | Widget, quick tile, voice, kanban, multi-agent tree, pets, Wear | Polish |

Phases 1–3 are the real project. 4–6 are additive and can land in any order.

---

# PART I — Open Questions

1. **Tailscale on the phone** — installed and on the same tailnet? (Everything
   assumes yes.)
2. **Dashboard flags** — I confirmed `--stop`/`--status` in `_parser.py` but not
   the host/port/auth flag names (they're in the 509KB `main.py`). Run
   `hermes dashboard --help` and paste the output; it may change §A.3 slightly.
3. **Package rename** — `com.hermes.mobile` → `com.hermes.remote`? Cleaner, but
   it's a fresh install (no data migration concern since the Room schema is being
   replaced anyway). The v1 handbook warns against renaming; that warning applied
   to a Drive-scoped app and no longer holds. My recommendation: rename.
4. **Min SDK** — currently 26. Raising to 29 or 31 unlocks better crypto
   (StrongBox), splash API, and predictive back without compat shims. Any device
   you care about below Android 10?
5. **`D:\HermesMobileWatcher\workspace\`** — anything in there worth preserving
   before the watcher is retired?
6. **The `.hermes` install is a live git checkout** — meaning Hermes updates can
   change the RPC surface. Capability negotiation (§C.7) covers most of it, but
   expect occasional breakage. Worth pinning a known-good commit? (Trade-off:
   you lose upstream fixes.)

---

*This document replaces `AGENT_HANDBOOK.md` as the living spec. Once you approve
the direction, the next session implements Phase 1.*
