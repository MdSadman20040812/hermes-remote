# HERMES BUILD BRIEF — Hermes Mobile v2 ("Hermes Remote")

> **You are Hermes Agent, running on `warnerbros-PC` with full local authority.**
> This document is your mission brief. Read it completely before touching anything.
> It tells you what to read, what to verify, what to build, in what order, and how
> to prove each step worked.

**Owner:** Sadman (`mdsadmanbinmasudansarullah@gmail.com`)
**Brief written:** 2026-08-16
**Brief location:** `D:\HermesMobile\HERMES-BUILD-BRIEF.md`

---

## 0. How to start

```
Read D:\HermesMobile\HERMES-BUILD-BRIEF.md and execute it.
```

Before you write a single line of code, do this in order:

1. Read this entire brief.
2. Read the documents in §2, in the order listed.
3. Execute **Phase 0** (§5) and report the results to me.
4. **Stop.** Wait for my go-ahead before Phase 1.

Do not skip Phase 0. It is a 30-minute empirical check that decides whether the
entire architecture is valid. Everything after it assumes it passed.

---

## 1. Mission

Rebuild the Hermes Mobile Android app so that my phone becomes a **first-class
remote client of you** — the Hermes instance running on this PC.

Today the app talks to the PC by writing files into Google Drive and polling. That
gives ~15–30 minute round trips, no streaming, no interactive approvals, and it
runs a *second, divergent agent runtime* (`D:\HermesMobileWatcher\watcher.py`)
whose sessions, memory and skills are separate from yours.

After the rebuild, the phone will speak your **native JSON-RPC control protocol**
over a Tailscale link. Same sessions, same memory, same skills, same `state.db`.
A turn I start at my desk, I can watch, steer, interrupt, and answer approval
prompts for, from my phone — in real time, token by token.

**The one-sentence test of success:** I start a task on my desktop, walk away, and
on my phone I see it streaming, get a push when it needs my approval, tap Allow,
and watch it finish — without ever touching Google Drive.

### What this is not

- Not a chat wrapper around a Telegram bot.
- Not a new agent runtime. **You do all the thinking; the phone is a window.**
- Not a rewrite of Hermes. You are changing the Android project only, plus one
  small PC-side launcher script.

---

## 2. Read these first — in this order

| # | Path | Why | Weight |
|---|---|---|---|
| 1 | `D:\HermesMobile\HERMES_MOBILE_V2_SPEC.md` | ⭐ **The architecture spec.** Transport design, module layout, screen design, feature tiers, security model, six-phase plan. This is your primary reference. | **Authoritative for design intent** |
| 2 | `D:\HermesMobile\PROJECT-INDEX.md` | Which folder is the real project and which is a stale decoy. Read before you touch the filesystem. | Authoritative for paths |
| 3 | `D:\HermesMobile\AGENT_HANDBOOK.md` | v1 handbook. **SUPERSEDED** — carries a warning banner. Read for history and for the v1 file map only. Its claims are known-stale; do not act on them. | Historical only |
| 4 | `D:\HermesMobile\README.md` | v1 build/architecture notes. | Historical only |

### Source of truth for the protocol — read the code, not the prose

The spec in #1 was written by reading your source statically. **You can do better:
you can run it.** Where the spec and the live code disagree, **the live code wins**,
and you must correct the spec (§9).

| Path | What it defines |
|---|---|
| `D:\.hermes\hermes-agent\tui_gateway\ws.py` | The `/api/ws` WebSocket transport, framing, `gateway.ready`, delta coalescing |
| `D:\.hermes\hermes-agent\tui_gateway\methods_*.py` | Every RPC method + its exact params. `@method("…")` decorators are the index. |
| `D:\.hermes\hermes-agent\tui_gateway\server.py` | The dispatcher. Large (585 KB) — grep it, don't read it. |
| `D:\.hermes\hermes-agent\hermes_cli\web_server.py` | FastAPI app: 112 REST routes, 6 WS routes, and the auth gates (`_ws_auth_reason`, `_ws_host_origin_reason`, `_ws_client_reason`). 714 KB — grep it. |
| `D:\.hermes\hermes-agent\web\src\lib\api.ts` | The reference client. Every endpoint, typed. **Your Android client should mirror this file's shape.** |
| `D:\.hermes\hermes-agent\web\src\lib\pty-*.ts` | Already-solved mobile PTY input, reconnect, and scrollback sanitising. **Port this logic; do not reinvent it.** |
| `D:\.hermes\hermes-agent\docs\relay-connector-contract.md` | Background on the relay design. We are **not** using it (it needs the phone to run a server). Read §2 only, for the `CapabilityDescriptor` pattern we're borrowing. |
| `D:\.hermes\hermes-agent\AGENTS.md` | Your own repo's contributor guide. Useful context on conventions. |

---

## 3. Ground truth — environment

### Paths

```
D:\HermesMobile\               ✅ THE PROJECT. All work happens here.
D:\Outputs\HermesMobile\       ⛔ STALE DECOY. Never open. See its _STALE-DO-NOT-USE.md.
D:\HermesMobileWatcher\        🗄️ To be retired. workspace\ is empty — nothing to preserve.
D:\.hermes\                    Your HERMES_HOME.
D:\.hermes\hermes-agent\       Your install (live git checkout).
```

### Android toolchain (verified present)

```
Android SDK   C:\Users\binma\Android\sdk        (local.properties)
JDK           Eclipse Temurin 17.0.20+8         (winget, per winget_install.log)
Gradle        8.7  — wrapper distributionUrl points at mirrors.cloud.tencent.com
AGP           8.4.0
Kotlin        2.0.21   KSP 2.0.21-1.0.27   Hilt 2.51
Compose BOM   2024.12.01                        Material 3
namespace     com.hermes.mobile   minSdk 26   target/compile 35
debug variant applicationId com.hermes.mobile.debug
```

> ⚠️ The Gradle wrapper downloads from a **Tencent mirror**. If it stalls or fails,
> switch `gradle/wrapper/gradle-wrapper.properties` to
> `https\://services.gradle.org/distributions/gradle-8.7-bin.zip` and note it in the
> build log.

### Build commands

```powershell
cd D:\HermesMobile
.\gradlew assembleDebug        # → app\build\outputs\apk\debug\app-debug.apk
.\gradlew compileDebugKotlin   # fast compile-only check
.\gradlew test                 # unit tests
.\gradlew lint
.\gradlew installDebug         # to a connected device
.\gradlew clean
```

### Version control

`D:\HermesMobile\` is **not a git repository.** Before you change anything:

```powershell
cd D:\HermesMobile
git init
# add a .gitignore covering: build/, .gradle/, .kotlin/, local.properties, *.apk, *.log
git add -A
git commit -m "checkpoint: v1 state before v2 overhaul"
```

Commit at the end of every phase with a clear message. This is your undo button and
mine. **Never `git push`** — there is no remote and I have not asked for one.

---

## 4. Hard rules

**Do not:**

1. **Do not touch `D:\Outputs\HermesMobile\`.** It is a stale scaffold. Reading it
   will give you wrong answers about the project's state.
2. **Do not give the Android app the Hermes gateway's Telegram bot token for
   polling.** You long-poll `getUpdates`, which is exclusive per token. A second
   poller causes `409 Conflict` and steals your updates — it will break your own
   live gateway. Sending via `sendMessage` is fine; **`getUpdates` from the phone is
   forbidden.**
3. **Do not build a second agent runtime.** No prompt construction, no tool loop, no
   model calls on Android. The phone calls `prompt.submit` and renders events. That
   is all.
4. **Do not bind the dashboard to `0.0.0.0`.** Bind to the Tailscale IP explicitly.
   Binding to all interfaces exposes unsandboxed remote execution to every network
   the machine joins.
5. **Do not write secrets into source, `BuildConfig`, logs, or git.** The existing
   `buildConfigField` placeholders `TELEGRAM_BOT_TOKEN_PLACEHOLDER` and
   `GDRIVE_APP_ID_PLACEHOLDER` in `app/build.gradle.kts` must be **deleted**, not
   filled in.
6. **Do not delete anything outside `D:\HermesMobile\`** without asking. To retire
   the watcher, `Move-Item` it to `D:\Archives\`, don't `Remove-Item` it.
7. **Do not mark a phase complete if the build fails**, tests fail, or you couldn't
   verify the acceptance criteria. Report the blocker instead.

**Do:**

8. Verify empirically before you trust the spec. You can run the server; the spec's
   author could only read it.
9. Prefer editing existing files over creating parallel ones. The v1 codebase has
   good bones (theme, components, DI skeleton) — §B.4 of the spec says exactly what
   to keep, rewrite, and delete.
10. Use your `todo` toolset (or Kanban) to track the phase you're in. I want to be
    able to see progress without reading a wall of text.

---

## 5. PHASE 0 — Prove the architecture (do this first, then stop)

**Goal:** empirically confirm the phone *can* speak your protocol, before anyone
writes Kotlin. Budget: under an hour.

### 0.1 Discover the dashboard's real CLI surface

```powershell
D:\.hermes\hermes-agent\venv\Scripts\python.exe -m hermes_cli.main dashboard --help
D:\.hermes\hermes-agent\venv\Scripts\python.exe -m hermes_cli.main web --help
```

Record the exact flags for host, port, auth, and insecure mode. The spec (§A.3)
guesses at these; replace the guess with fact.

### 0.2 Establish a stable session token

`web_server.py:331` reads `HERMES_DASHBOARD_SESSION_TOKEN` and otherwise generates a
random token **per process start** — which would force re-pairing after every
reboot. Generate a strong token, persist it in `D:\.hermes\.env`, and confirm the
server picks it up.

### 0.3 Get the Tailscale address

```powershell
tailscale ip -4
tailscale status
```

If Tailscale is not installed or the phone is not on the tailnet, **stop and tell
me** — the whole networking model depends on it.

### 0.4 Start the dashboard bound to the tailnet IP

Bind to the `100.x.y.z` address, port 9119. **Not `0.0.0.0`, not `127.0.0.1`.**

Then confirm the auth mode is what we expect by reading the startup log and, if
needed, `_ws_auth_mode()` in `web_server.py`.

### 0.5 Prove REST from off-box

From the phone's browser (or another machine on the tailnet):

```
http://100.x.y.z:9119/api/status
```

Expect JSON, not a connection refusal and not a 403. If you get 403, check
`_ws_client_reason` / `_is_accepted_host` — the Host header must match the bound host.

### 0.6 Prove the JSON-RPC socket — the critical test

Write a throwaway Python client (`D:\HermesMobile\pc\probe_ws.py`) that:

1. Connects to `ws://100.x.y.z:9119/api/ws?token=<token>`
2. Waits for the `gateway.ready` frame and prints its payload
3. Calls `session.list`, prints the result
4. Calls `session.create`, then `prompt.submit` with a trivial prompt
   (e.g. `"reply with exactly: PROBE OK"`)
5. Prints every inbound frame with its `type`, so we can see
   `message.delta` arriving token by token
6. Calls `session.interrupt` mid-turn on a longer prompt and confirms it stops

**Report to me:**

- The exact `gateway.ready` payload
- The real frame shapes for `message.delta`, tool start/end, and turn completion
  (the spec describes these generically — I want the literal JSON)
- Whether `session.interrupt` works from a cold client
- Measured latency from `prompt.submit` to first `message.delta`
- Any discrepancy between what you observed and `HERMES_MOBILE_V2_SPEC.md`

### 0.7 Probe the approval flow

Trigger a turn that requires approval (`approvals.mode` is `manual` in your
`config.yaml`). Capture the **exact** frame the server emits when it blocks, and
confirm `approval.respond` unblocks it from the probe client. This is the single
most important interaction in the whole app — get its wire format exactly right.

### 0.8 Probe `/api/pty`

Connect, send a command, confirm output streams back. Note the framing (raw bytes
vs JSON) and how resize is signalled.

**→ Write findings to `D:\HermesMobile\PHASE0-FINDINGS.md` and stop. Wait for me.**

---

## 6. The build — phases 1 to 6

Full detail for each is in `HERMES_MOBILE_V2_SPEC.md`. Below is the contract:
what each phase must deliver and how you prove it.

Every phase ends with: **build passes → tests pass → git commit → short report.**

### Phase 1 — Spine

**Do:** Strip Google Drive and GMS entirely (spec §B.4 delete list). Build
`core/transport/` — `HermesClient`, `RpcChannel` (JSON-RPC 2.0 over NDJSON on
OkHttp), `RestClient`, `ReconnectPolicy`. Build `core/connection/` — profiles,
credential strategy, reachability racing. Implement QR pairing (spec §C.3) with
CameraX + ML Kit. Build the connection-state UI.

**Prove:**
- `assembleDebug` succeeds; APK size drops substantially (Drive+GMS removal should
  roughly halve the 70.8 MB debug APK — report the actual number)
- App scans the QR from the PC script and pairs in one shot
- App shows live `/api/status` from the PC
- Socket survives airplane-mode-on/off and Wi-Fi↔mobile handoff, reconnecting
  with backoff
- Unit tests for frame serialisation and the reconnect policy
- **The deploy loop from §8.1 works**: you can run `.\gradlew installDebug` and the
  build lands on my phone without me touching a cable. Record the phone's tailnet
  IP and the post-strip APK size in `BUILD-LOG.md`.

### Phase 2 — Cockpit

**Do:** Session list (`session.list`, `session.active_list`, `session.resume`).
The Cockpit screen (spec §D.2): streaming transcript, tool-call timeline, thinking
blocks, `prompt.submit`, `session.interrupt`, `session.steer`. Room cache as
*derived* state, reconciled from `session.history` on reconnect.

**Prove:**
- A prompt sent from the phone streams back token by token with no visible jank
  (buffer deltas, apply at ~60fps — do not recompose per token)
- A session started on the desktop appears on the phone and can be resumed
- Interrupt stops a running turn from the phone
- Tool calls render as a legible timeline, expandable to full args/result
- **This is the ship-to-my-phone milestone.** After Phase 2 I should be using it daily.

### Phase 3 — Authority

**Do:** Approvals — `approval.respond`, `clarify.respond`, `sudo.respond`,
`secret.respond` — rendered inline in the transcript *and* as notification actions.
Telegram doorbell: gateway sends a notification containing a `hermes://session/<id>`
deep link; tapping it opens the app to that session and reconnects (spec §C.4).
`TurnForegroundService` holding the socket only while a turn is live. Offline outbox.

**Prove:**
- Agent blocks on approval → phone notification with Allow/Deny → tapping either
  resolves the turn, and answering in-app dismisses the notification
- Deep link from a Telegram notification opens the correct session
- Battery: no persistent foreground service when idle
- Prompt composed offline flushes on reconnect, exactly once

### Phase 4 — Reach

Terminal (`/api/pty`) with a mobile key row — **port the logic from
`web/src/lib/pty-mobile-input.ts`, `pty-reconnect.ts`, `pty-resume-sanitizer.ts`.**
PC file browser (`/api/files`, `/api/files/read`, `/api/files/upload-stream`) reusing
v1's `FilePreviewDialog`. Android share-sheet target ("Send to Hermes").
Attachments via `file.attach` / `image.attach_bytes` / `pdf.attach`.

**Prove:** A real command run in the phone terminal; a file browsed, previewed,
downloaded and uploaded; a URL shared from Chrome arriving as a prompt attachment.

### Phase 5 — Ops

`system.stats`, `process.list`/`process.kill`, `gateway.start/stop/restart`,
`/api/logs` tail, `/api/analytics/usage` + `usage.bars` cost view, model switcher
(`model.options` → `/api/model/set`), skills browser, cron manager (`cron.manage`).

**Prove:** Each surface reads live data and every mutating action round-trips.

### Phase 6 — Delight

Home-screen widget + Quick Settings tile, voice (STT in → `prompt.submit`,
`/api/audio/speak-stream` out — your config already has `stt.enabled: true` and
`tts.provider: openai`), Kanban board, multi-agent/spawn-tree view, panic button,
pets (`pet.*`, 15 methods). Optional: Wear OS approvals.

---

## 7. Engineering standards

- **Kotlin + Compose + Material 3.** Single activity, `NavigationSuiteScaffold` for
  adaptive layout. No XML layouts.
- **Architecture:** UI → ViewModel (`StateFlow`) → Repository → `HermesClient`.
  Nothing above the transport layer knows the wire format.
- **Concurrency:** structured. Every socket lives in a scope tied to a lifecycle.
  No `GlobalScope`.
- **Serialisation:** `kotlinx.serialization`, explicit `@Serializable` DTOs per
  frame. No `Map<String, Any?>` leaking above the transport.
- **Errors are visible.** Every ViewModel exposes a `userMessage` channel wired to a
  `SnackbarHost`. **The v1 codebase has silent `catch {}` blocks — eliminate every
  one you touch.**
- **No secrets in logs.** Redact tokens in the OkHttp logging interceptor. Ship
  `HttpLoggingInterceptor.Level.NONE` in release.
- **Accessibility:** `contentDescription` on every interactive element, 48dp touch
  targets, TalkBack pass on the Cockpit before Phase 2 is called done.
- **Naming:** the spec proposes renaming the package `com.hermes.mobile` →
  `com.hermes.remote`. **Ask me before doing this** — it's cosmetic and it churns
  every file.
- **Tests:** unit tests for the transport layer, frame parsing, reconnect policy, and
  outbox semantics are mandatory. Compose UI tests for the Cockpit are expected.
  Do not chase coverage on screens.

---

## 8. Verification protocol

A phase is **not done** until all of these pass. Run them yourself; don't ask me to.

```powershell
cd D:\HermesMobile
.\gradlew clean assembleDebug     # must succeed
.\gradlew test                    # must pass
.\gradlew lint                    # review new warnings; don't ignore them silently
```

Plus, per phase:

- The acceptance bullets in §6 for that phase, actually exercised — not assumed.
- APK size recorded and compared to the previous phase.
- A skim of `git diff --stat` to confirm you didn't leave dead v1 code lying around.

If you cannot verify something because it needs my phone in hand, say so
explicitly and list what I need to test. Don't quietly mark it done.

### 8.1 Deployment — getting the APK onto my phone

**Android Studio is not required.** `gradlew assembleDebug` produces a
debug-signed APK (the debug keystore is auto-generated) that installs directly.
The debug variant uses `applicationIdSuffix = ".debug"`, so it coexists with any
future release build.

**You are expected to deploy it yourself.** Set this up during Phase 1 and use it
for every phase thereafter — the loop should be: I ask for a change, you build,
you install, I open the app.

**Preferred: ADB over the tailnet.** Both devices are already on Tailscale.

```powershell
# one-time, both devices on the same Wi-Fi, phone in Developer Options →
# Wireless debugging → Pair device with pairing code:
adb pair <phone-ip>:<pair-port> <code>

# thereafter, from anywhere on the tailnet:
adb connect <phone-tailnet-ip>:5555
adb devices                     # confirm "device", not "unauthorized"
cd D:\HermesMobile
.\gradlew installDebug
adb logcat -s HermesRemote:V    # for crash triage
```

`adb.exe` is at `C:\Users\binma\Android\sdk\platform-tools\adb.exe`. Confirm it
exists in Phase 1 and record the phone's tailnet IP in `BUILD-LOG.md`.

**Fallback A — USB.** Same commands, cable instead of `adb connect`.

**Fallback B — Telegram sideload.** Send the APK as a document over the existing
gateway bot; I tap to install. **The Bot API caps `sendDocument` at 50 MB.** The
current debug APK is 70.8 MB, so this only becomes available once the Drive/GMS
strip brings it under the cap. Report the post-strip size in Phase 1 and tell me
whether this path is open.

**Do not** set up a release keystore, Play Store publishing, or app signing config
unless I ask. Debug builds are correct for personal use.

---

## 9. What you must write back

Maintain these, in `D:\HermesMobile\`:

| File | Contents |
|---|---|
| `PHASE0-FINDINGS.md` | Phase 0 results: real CLI flags, real frame shapes, real latency, every place the spec was wrong |
| `BUILD-LOG.md` | Append-only. One entry per work session: what you did, what broke, what you decided and why, APK size, commit hash |
| `HERMES_MOBILE_V2_SPEC.md` | **Correct it as you learn.** It is a living document, not scripture. When the live code contradicts it, fix the spec and note the change in `BUILD-LOG.md` |
| `PROJECT-INDEX.md` | Keep the file tree and "read these in order" list current |
| `pc\hermes-remote.ps1` | The PC-side launcher: stable token, tailnet bind, dashboard start, QR render, optional scheduled task (spec §G) |

When you finish a phase, report to me in **under 200 words**: what shipped, what you
verified, what surprised you, what's next, and anything you need from me.

---

## 10. When to stop and ask me

Stop and ask — don't guess — if:

- **Phase 0 fails at any step.** Especially: Tailscale missing, dashboard won't bind
  off-loopback, `/api/ws` rejects the token, or approvals don't round-trip.
- The live protocol differs from the spec in a way that **changes the architecture**
  (small differences: just fix the spec and carry on).
- You'd need to modify anything under `D:\.hermes\hermes-agent\` — that's a live git
  checkout of your own runtime. Patching it is a different conversation.
- A dependency needs a paid service, a Google/Firebase project, or an account signup.
- You want to rename the package, change `minSdk`, or drop a screen from the spec.
- Any action would touch files outside `D:\HermesMobile\` other than the ones this
  brief names.

---

## 11. Definition of done (v2.0)

- [ ] Google Drive and GMS removed from the codebase entirely; no `com.google.api`
      or `play-services` in the dependency tree
- [ ] `D:\HermesMobileWatcher\` archived; no second agent runtime exists
- [ ] Phone pairs with the PC by scanning a QR — nothing typed
- [ ] Phone streams a live turn token by token, with a legible tool timeline
- [ ] Phone can interrupt and steer a running turn
- [ ] Approval requests reach the phone as actionable notifications and resolve correctly
- [ ] Desktop-started sessions resume on the phone and vice versa, against the same
      `state.db`
- [ ] Terminal and file browser work over the tailnet
- [ ] Nothing is exposed to the public internet; token is in encrypted storage;
      biometric gate on app open
- [ ] `assembleDebug` and `test` green; `BUILD-LOG.md` and the spec current
- [ ] I've used it for a week without opening the desktop for a task I could do
      from the couch

---

## 12. One housekeeping item, unrelated to the app

`D:\.hermes\config.yaml` contains a **GitHub Personal Access Token in plaintext** at
`mcp_servers.github.env.GITHUB_PERSONAL_ACCESS_TOKEN` (begins `ghp_FoO3X8…`), and
it's duplicated in three `.bak` files beside it. Every other provider in that file
uses `key_env` correctly; GitHub is the outlier.

**Rotate that token, move it to an env var, and scrub the `.bak` copies.** Do this
before Phase 1 — it takes five minutes and it's a live credential in a file that
gets shared with folder-access tools.

---

## Appendix A — Protocol quick reference

**Transport:** `ws://<tailnet-ip>:9119/api/ws?token=<token>` — newline-delimited
JSON-RPC 2.0, bidirectional. Server emits `gateway.ready` on accept. Deltas are
coalesced server-side at ~33 ms.

**Auth modes** (`web_server.py`): `loopback` (127.0.0.1, `?token=`) ·
`insecure` (non-loopback bind, `?token=`) · `gated` (`POST /api/auth/ws-ticket` →
single-use 30 s `?ticket=`). We use **insecure-over-Tailscale**: the tailnet is the
network boundary, the token is the auth boundary. Build `CredentialStrategy` so
switching to gated is a one-class change.

**Methods you will use most** — full list via
`grep -o '@method("[^"]*"' D:\.hermes\hermes-agent\tui_gateway\methods_*.py`:

```
prompt.submit  prompt.background
session.create  session.list  session.active_list  session.activate  session.resume
session.history  session.status  session.usage  session.title  session.close
session.interrupt  session.steer  session.redirect  session.undo  session.branch
approval.respond  clarify.respond  sudo.respond  secret.respond
file.attach  image.attach_bytes  pdf.attach
shell.exec  process.list  process.kill  terminal.resize
tools.list  toolsets.list  config.get  skills.manage  cron.manage
model.options  usage.bars  agents.list  spawn_tree.list  subagent.steer
projects.tree  slash.exec  commands.catalog  system.battery
```

**REST you will use most:**

```
GET  /api/status  /api/system/stats  /api/logs
GET  /api/sessions  /api/sessions/{id}  /api/sessions/search
GET  /api/files    POST /api/files/read  /api/files/upload-stream
GET  /api/analytics/usage   GET /api/model/options   POST /api/model/set
GET  /api/cron/jobs   GET /api/skills   POST /api/skills/toggle
POST /api/gateway/{start,stop,restart}
POST /api/auth/ws-ticket
```

**Other sockets:** `/api/pty` (terminal) · `/api/events` + `/api/pub` (channel
broadcast) · `/api/audio/speak-stream` (TTS) · `/api/plugins/kanban/events`.

---

## Appendix B — Document map

```
D:\HermesMobile\
├── HERMES-BUILD-BRIEF.md        ← you are here; the mission
├── HERMES_MOBILE_V2_SPEC.md     ← the architecture. Your primary reference.
├── PROJECT-INDEX.md             ← which folder is real; file tree; build commands
├── PHASE0-FINDINGS.md           ← you will create this
├── BUILD-LOG.md                 ← you will create this
├── AGENT_HANDBOOK.md            ← v1, superseded, history only
└── README.md                    ← v1, history only

D:\.hermes\hermes-agent\
├── tui_gateway\ws.py            ← protocol source of truth
├── tui_gateway\methods_*.py     ← RPC method source of truth
├── hermes_cli\web_server.py     ← REST + auth source of truth
├── web\src\lib\api.ts           ← reference client to mirror
├── web\src\lib\pty-*.ts         ← mobile terminal logic to port
└── docs\relay-connector-contract.md  ← background only; not our transport
```

---

*Begin with §2 (read), then §5 (Phase 0), then stop and report.*
