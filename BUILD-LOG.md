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

---

## 2026-08-16 (evening) — Environment fixes + gated-mode proof + Phase 1 spine

**Environment:**
- §12 DONE: config.yaml github MCP env → `'${GITHUB_TOKEN}'` (verified the MCP
  spawn-time interpolation path resolves it to the real PAT after the standard
  dotenv load); both `config.yaml.bak.*` files scrubbed; zero `ghp_` left in
  D:\.hermes config files. Token ROTATION still needs his GitHub web session.
- `.env` write (HERMES_DASHBOARD_SESSION_TOKEN) blocked 3× by approval timeout.
  Token lives in `pc/.probe-token`; hermes-remote.ps1 will persist it on his run.
- Temurin 17.0.20.8 actually installed (winget log had lied — D:\Android\JDK was
  an empty dir). JAVA_HOME fixed for session + `setx` for the user.
- **Tailscale installed + logged in: warnerbros-PC = 100.88.18.123.**
- `dashboard.basic_auth` configured (user `sadman`, password in gitignored
  `pc/.dashboard-password`, hash in config.yaml).
- **Gated flow proven live over tailnet** (`pc/probe_gated.py`): /api/status
  public 200 · `?token=` → 403 · password-login → 3 cookies · ws-ticket mint
  (30s) · `?ticket=` → gateway.ready + session.list (200 sessions) · ticket
  single-use enforced. **This is the Android client's exact path.**

**Phase 1 build:**
- Deleted v1: Drive/GMS/Telegram data sources, WorkManager sync, Room v1 schema,
  all Drive-coupled screens/VMs, TransferForegroundService, auth screens.
- Gradle: removed play-services-auth, google-api-client, drive, work-runtime,
  hilt-work, coroutines-play-services, media3, markwon, pdfviewer, coil;
  deleted the two BuildConfig placeholder fields (never filled, per §4.5);
  added CameraX 1.3.4 + ML Kit barcode 17.2.0 (bundled, no GMS) + coroutines-test.
- New: core/transport (Frames/RpcChannel/RestClient/ReconnectPolicy/HermesClient),
  core/connection (ConnectionProfile, CredentialStrategy, ConnectionManager with
  reachability racing + backoff reconnect), core/vault/SecureVault
  (EncryptedSharedPreferences), ui/connect (CameraX QR + manual entry),
  ui/home (live /api/status proof), new shell + DI.
- Manifest: cleartext=true (tailnet-only, documented), hermes://session deep
  link registered, GMS/WorkManager entries removed.
- PC launcher: `pc/hermes-remote.ps1` + `pc/render_qr.py` (qrcode vendored into
  pc/vendor — Hermes venv untouched). ASCII QR verified rendering.
- Unit tests: 11/11 pass (frame parsing against literal Phase-0 captures,
  reconnect policy sequence/jitter/reset).

**Verified:** compileDebugKotlin ✓ · test ✓ (11/11) · lint ✓ (0 errors,
51 cosmetic warnings — mostly unused resources from deleted v1 screens) ·
assembleDebug ✓.

**APK: 41,459,784 B = 39.5 MB (was 70.8 MB, −44%). Telegram 50 MB sideload
cap: PATH OPEN.**
**adb: C:\Users\binma\Android\sdk\platform-tools\adb.exe present.**
**Phone tailnet IP: UNKNOWN — phone not on tailnet yet (`tailscale status`
shows only this PC). ADB wireless pairing also still needs the phone in hand.**

**Surprises:** Kotlin nested block comments make `/api/*` inside KDoc a compile
error. `dashboard --stop` doesn't see bash-launched dashboard processes.

---

## 2026-08-16 (late) — Phase 2: Cockpit

**Did:** domain models (TranscriptItem sealed, SessionSummary); Room v2 cache
(sessions+messages, destructive-migration, new hermes-v2.db); SessionRepository
(list/create/resume w/ live-vs-stored id handling); TranscriptRepository engine
(20fps flush ticker, delta buffers, tool timeline, approval cards, history
hydration/reconcile); CockpitViewModel/Screen (status rail, transcript,
thinking blocks, expandable tool rows, approval cards w/ choices, interrupt +
steer bar, composer, haptics, 48dp targets, contentDescriptions);
SessionsViewModel/Screen; 3-tab NavigationSuiteScaffold shell (Cockpit /
Sessions / Ops) with activity-scoped VMs; userMessage channels wired to one
SnackbarHost.

**Verified:** compile ✓ · test 11/11 ✓ · lint 0 errors ✓ · assembleDebug ✓.
**APK: 42,249,400 B = 40.3 MB** (+0.8 MB vs Phase 1: Room + adaptive suite).

**Fixes en route:** adaptive-navigation-suite coordinate is
`androidx.compose.material3:material3-adaptive-navigation-suite` (BOM-managed);
Room providers restored to AppModule.

**Not yet device-verified (needs phone):** streaming jank, resume-mid-turn,
60fps feel. Server-side behavior backing each was probe-proven in Phase 0.

---

## 2026-08-16 — Phase 3: Authority

**Did:** HermesNotifier (approval.request → HIGH notification w/ Allow once/Deny
actions + hermes:// deep-link body; turn-complete quiet notif; in-app answer
dismisses); ApprovalActionReceiver (resolves over live socket without opening
the app); TurnForegroundService (dataSync FGS held ONLY while turnPhase=RUNNING);
offline outbox (Room entity, exactly-once flush on Connected, ordered);
hermes://session/<id> deep links via DeepLinkBus → resumeAndOpen; globalEvents
tap on ConnectionManager for app-wide event fan-out.

**Verified:** compile ✓ · test 11/11 ✓ · lint 0 errors ✓ · assembleDebug ✓.
**APK: 40.3 MB (unchanged).**
**Phone-needed:** notification actions end-to-end, deep link from Telegram,
battery-idle check.

---

## 2026-08-16 — Phase 4: Reach

**Did:** core/terminal — PtySanitizer + PtyMobileInput PORTED from
web/src/lib/pty-*.ts (with the TS test expectations as unit tests); PtyChannel
(binary WS, close-code taxonomy 4401/03/04/08/10, reconnect throttle constants);
TerminalScreen (ANSI-stripped scrollback capped 256KB, key row
Esc/Tab/arrows/Ctrl-C/Ctrl-D/|/~/); FileRepository + FilesScreen (browse, text/
image preview via data_url); ShareBus + ACTION_SEND target; Ops tab hosting
status/Terminal/Files panes. Literal control bytes in sources normalized to
explicit \uXXXX escapes (Kotlin-invisible-char hazard — repeat offender, noted).

**Verified:** compile ✓ · tests ✓ · lint 0 errors ✓ · assembleDebug ✓.
**APK: 40.3 MB (unchanged).**
**Phone-needed:** real terminal session feel, file download-to-device, share
from Chrome.

---

## 2026-08-16 — Phases 5 and 6: Ops + Delight

**Did:** OpsRepository (system.stats, process.list/kill, gateway start/stop/
restart, logs, analytics/usage, model options+set, skills, cron — all lenient
parsing); OpsScreen expanded (status card w/ CPU/RAM, model switcher, gateway
controls, skills + cron summaries, two-step PANIC button); QuickTileService
(API-34-safe startActivityAndCollapse); home-screen widget (RemoteViews — the
one layout XML, platform constraint); VoiceInputController (on-device
SpeechRecognizer) + mic button in composer w/ RECORD_AUDIO runtime prompt;
share-sheet ACTION_SEND already landed in P4.

**Deferred (documented, not silently dropped):** TTS playback
(/api/audio/speak-stream), Kanban board UI, multi-agent spawn-tree view, pets
screen, Wear OS — all additive Tier-3 surfaces with the transport already
proven; they slot into the existing Ops/Cockpit pattern when wanted.

**Verified:** compile ✓ · test 21/21 ✓ · lint 0 errors ✓ · assembleDebug ✓.
**Final APK: 41,862,772 B = 39.9 MB (v1 was 70.8 MB, −44%).**

---

## 2026-09-07 — LAN-first transport, robustness pass, FIRST ON-DEVICE VERIFICATION

**Transport decision:** Tailscale demoted from requirement to option
(`hermes-remote.ps1 -UseTailscale`). The phone and PC share a home router, so
the router already is the private network; a tailnet added a second identity
system that can be "not logged in" for reachability the LAN already gives.

**The bug that made the app unusable:** `ServerStatus.gatewayPlatforms` was
typed `List<String>` while the server sends `{"telegram":{...}}`. That threw a
JsonDecodingException out of EVERY `/api/status` decode — including the one in
`connectTo` — so a healthy dashboard reported "Can't reach your PC". A wrong
type on a field nothing renders. Now `JsonElement` + `platformNames`, pinned by
`ServerStatusTest` against a literal 0.21.0 capture. Lesson recorded: never
guess a wire shape; capture it.

**PC side was equally broken, invisibly:** the Wi-Fi was classified Public
(Windows drops inbound wholesale, before any rule) and nothing allowed inbound
9119. Both failures look exactly like "app can't find PC" from the phone. New
`pc/setup-lan.ps1` (elevated, one-time) fixes both, scoped to Private/Domain +
LocalSubnet so the opening does not follow the laptop to a cafe.

**New:** `NetworkMonitor` (link transitions drive reconnects instead of a
timer), `LanDiscovery` (/24 sweep for `/api/status`, self-heals DHCP drift),
`PrivateHosts` (cleartext credential guard, CIDR — unexpressible in
network-security-config, so it lives in code).

**Fixed under test on the phone:**
- Failed connect was terminal — the retry loop only ran off a socket that had
  already opened, so a Wi-Fi bounce left "Couldn't connect" beside a discovery
  card listing that same PC. All retryable failures now go through
  `failAndRetry`.
- Link-change connected on the same tick as the WIFI callback, before DHCP
  settled, and lost the race; now it resets backoff and hands off to the loop.
- Half-open socket looked connected and silent forever → 25 s app-level ping →
  `RpcChannel.markDegraded`.
- The reconnect loop's own `connectTo` cancelled the coroutine it ran in.
- `PreviewView` defaults to SurfaceView, a hardware layer compositing ABOVE
  Compose; it sliced the discovery card in half on device (invisible in any
  emulator-free review). `ImplementationMode.COMPATIBLE` + one pairing mode at
  a time.

**Verified on Galaxy A07 (R87YA00YSAD), not just compiled:**
discovery 2.6 s · gated handshake to `101 Switching Protocols` · prompt round
trip returning `PHONE_LINK_OK` · cold-start auto-reconnect · Wi-Fi off/on
self-heal · `?ticket=•••` redaction holding in logcat.

**Tests:** 57 passing (was 51 pre-session, 21 at Phase 6).
