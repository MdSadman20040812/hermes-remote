# Hermes Mobile — Agent Handbook

> [!WARNING]
> ## ⚠️ SUPERSEDED — read `HERMES_MOBILE_V2_SPEC.md` instead
>
> This handbook describes **Hermes Mobile v1**, whose Google-Drive-as-message-bus
> architecture is being replaced. It is retained for history only.
>
> **Two corrections you need before reading further:**
>
> 1. **The project root is `D:\HermesMobile\`, NOT `D:\Outputs\HermesMobile\`.**
>    The old path below is a stale scaffold copy (38 Kotlin files, never built,
>    frozen 2026-08-08 03:17 UTC). This single wrong line is why two copies of the
>    project looked equally plausible. All `D:\Outputs\HermesMobile\` references
>    in this document have been corrected to `D:\HermesMobile\`.
> 2. **Do NOT give the Android app the same Telegram bot token as the Hermes
>    gateway** (§12.2). Hermes long-polls `getUpdates`, which is exclusive per
>    token — a second poller causes 409 Conflict and stolen updates, breaking your
>    live gateway.
>
> Other known drift: the file count is ~60 (not 38); `HermesAppPlaceholder.kt` and
> `NavGraph.kt` no longer exist (replaced by `HermesApp.kt`); §8.2's "All
> Completed" entries terminate at Google Drive and are being removed.
>
> *Banner added 2026-08-16 by Claude (Cowork).*

---

> **Purpose:** Everything an AI coding agent needs to know to continue, refactor, beautify, test, ship, or extend the Hermes Mobile Android project.  
> **Audience:** Autonomous coding agent (Claude Code, Codex, OpenCode, etc.) or human developer resuming the work.  
> **Source of truth:** `D:\HermesMobile\`

---

## 1. Project Identity

| Field | Value |
|-------|-------|
| **Name** | Hermes Mobile |
| **Package** | `com.hermes.mobile` |
| **Min SDK** | 26 (Android 8.0 Oreo) |
| **Target SDK** | 35 (Android 15) |
| **Compile SDK** | 35 |
| **Language** | Kotlin 2.0.21 |
| **UI framework** | Jetpack Compose BOM 2024.12.01 + Material 3 |
| **DI** | Dagger Hilt 2.51 (scaffolded, partial wiring) |
| **Architecture** | Single Activity, Compose NavHost, Repository pattern, Room local cache, OkHttp + Google Drive REST |
| **Build system** | Gradle 8.x (KTS), KSP 2.0.21-1.0.27 |
| **Project root** | `D:\HermesMobile\` |

---

## 2. Complete File Map

### 2.1 Root Config
```
D:\HermesMobile\
├── settings.gradle.kts          # rootProject.name = "HermesMobile", include ":app"
├── build.gradle.kts             # plugins: com.android.application, org.jetbrains.kotlin.android, com.google.devtools.ksp, com.google.dagger.hilt.android
├── gradle.properties            # org.gradle.jvmargs=-Xmx4096m, android.useAndroidX=true, kotlin.code.style=official
└── README.md                    # User-facing build/architecture docs
```

### 2.2 App-Level Config
```
app/
├── build.gradle.kts              # COMPLETE — all dependencies listed below
├── proguard-rules.pro            # Room, serialization, Drive keep rules
└── src/main/
    ├── AndroidManifest.xml       # Permissions, Application, MainActivity, Service, Provider
    └── res/
        ├── values/strings.xml    # All user-facing strings
        ├── values/colors.xml     # Legacy colors (kept for system)
        ├── values/hermes_colors.xml  # Hermes brand palette
        ├── values/themes.xml     # Theme.HermesMobile, Theme.HermesMobile.Splash
        ├── drawable/             # ic_launcher_foreground.xml, ic_launcher_background.xml
        ├── mipmap-anydpi-v26/    # Adaptive launcher icons (ic_launcher.xml, ic_launcher_round.xml)
        └── xml/                  # file_paths.xml, data_extraction_rules.xml, backup_rules.xml
```

### 2.3 Source Tree (38 Kotlin files)
```
app/src/main/java/com/hermes/mobile/
│
├── HermesApplication.kt               # @HiltAndroidApp, Notification channels, WorkManager config
│
├── ui/
│   ├── MainActivity.kt                # @AndroidEntryPoint, thin: sets content → HermesAppPlaceholder
│   ├── HermesAppPlaceholder.kt        # Bottom nav shell (5 tabs), routes to each screen
│   ├── WelcomeScreen.kt               # Onboarding splash: gradient, feature chips, CTA
│   │
│   ├── auth/
│   │   └── AuthViewModel.kt           # DataStore-backed: theme, onboarded, tg_token, gdrive_account
│   │
│   ├── navigation/
│   │   └── NavGraph.kt                # Compose NavHost scaffold (currently unused, kept for future wiring)
│   │
│   ├── theme/
│   │   ├── Color.kt                   # Hermes brand palette: Deep, Surface, Primary #7C5CFC, Accent #39D353
│   │   ├── Type.kt                    # HermesTypography: display/headline/title/body/label scales
│   │   └── Shape.kt                   # HermesShapes: extraSmall 4dp → extraLarge 24dp
│   │
│   ├── components/
│   │   ├── StatusChip.kt              # Color-coded pill: PENDING=amber, QUEUED=blue, RUNNING=purple, COMPLETED=green, FAILED=red
│   │   ├── TransferCard.kt            # File transfer progress card with LinearProgressIndicator
│   │   ├── SettingsBar.kt             # Reusable settings row with icon + Switch/Chevron
│   │   └── ConnectionStatusBar.kt     # Top status bar: 3 dots (Telegram/Drive/PC) + counts
│   │
│   └── screens/
│       ├── TasksScreen.kt             # Task list, filter chips (All/Running/Completed/Failed), FAB
│       ├── FilesScreen.kt             # Drive file browser, MIME-type icons, empty state
│       ├── MessagesScreen.kt          # Message bubbles (reverse LazyColumn), empty state
│       ├── SubmitTaskScreen.kt        # Prompt text field, quick-prompt chips, priority, attachments
│       ├── SettingsScreen.kt          # Sectioned settings: Appearance, Connections, Data, About
│       └── AuthScreen.kt              # Setup flow: Step 1 Telegram bot token, Step 2 Google sign-in
│
├── data/
│   ├── local/
│   │   ├── entities.kt               # 5 Room entities: AgentTaskEntity, DriveFileEntity, PendingTransferEntity, CachedMessageEntity, SettingEntity
│   │   ├── daos.kt                    # 5 DAOs: AgentTaskDao, DriveFileDao, PendingTransferDao, SettingsDao, CachedMessageDao
│   │   ├── HermesDatabase.kt         # @Database(version=1, exportSchema=false), abstract RoomDatabase
│   │   ├── Converters.kt             # TypeConverters placeholder (empty, @Singleton)
│   │   ├── entity_extensions.kt      # AgentTaskEntity.toDomain()
│   │   └── drive_extensions.kt       # DriveFileEntity ↔ DriveFileInfo mappers
│   │
│   └── remote/
│       ├── dto/
│       │   ├── TelegramDtos.kt       # TgUser, TgChat, TgMessage, TgUpdate, TgSendMessageRequest, etc.
│       │   └── DriveDtos.kt          # DriveFile, DriveFileList, DrivePermission
│       ├── TelegramRemoteDataSource.kt  # OkHttp client, POST to api.telegram.org/bot<token>/<method>
│       └── DriveRemoteDataSource.kt      # Google Drive REST v3 via google-api-client
│
├── domain/
│   ├── model/
│   │   └── Models.kt                 # Domain types: AgentTask, DriveFileInfo, TransferProgress, ConnectionState, NotificationItem
│   └── repository/
│       ├── SettingsRepository.kt     # Telegram token, Drive account, theme, onboarding
│       ├── TaskRepository.kt         # Submit/list/cancel tasks, enqueue to Drive, connection state
│       └── DriveRepository.kt         # Refresh index, upload, download, sync count
│
├── di/
│   └── AppModule.kt                  # Dagger-Hilt @Module: Room, OkHttp, Drive credential/service, DataStore
│
├── core/
│   └── work/
│       ├── HermesSyncWorker.kt       # @HiltWorker CoroutineWorker: enqueue pending task + refresh Drive index
│       ├── HermesSyncWorkerFactory.kt  # Wraps HiltWorkerFactory for custom WorkerFactory
│       └── WorkModule.kt             # Provides WorkManager configuration
│
└── service/
    └── TransferForegroundService.kt  # Foreground service for long-running transfers
```

---

## 3. Dependencies (Complete List from `app/build.gradle.kts`)

```kotlin
// Compose BOM
val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
implementation(composeBom)
androidTestImplementation(composeBom)
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-graphics")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material3:material3-window-size-class")
implementation("androidx.activity:activity-compose:1.9.0")
implementation("androidx.navigation:navigation-compose:2.7.6")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
implementation("androidx.compose.material:material-icons-extended")

// Accompanist
implementation("com.google.accompanist:accompanist-permissions:0.36.0")
implementation("com.google.accompanist:accompanist-swiperefresh:0.36.0")
implementation("com.google.accompanist:accompanist-systemuicontroller:0.36.0")

// Networking
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
implementation("io.coil-kt:coil-compose:2.6.0")

// Google Drive (GMS)
implementation("com.google.android.gms:play-services-auth:21.2.0")
implementation("com.google.api-client:google-api-client-android:2.2.0")
implementation("com.google.apis:google-api-services-drive:v3-rev20241028-2.0.0")
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Room
implementation("androidx.room:room-runtime:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.1.1")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

// Hilt DI
implementation("com.google.dagger:hilt-android:2.51")
ksp("com.google.dagger:hilt-android-compiler:2.51")
implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

// Security
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// Media
implementation("androidx.media3:media3-exoplayer:1.3.1")
implementation("androidx.media3:media3-ui:1.3.1")
implementation("androidx.media3:media3-common:1.3.1")

// Markdown
implementation("io.noties:markwon:4.6.2")

// PDF viewer
implementation("com.github.barteksc:android-pdf-viewer:3.2.0-beta.1")

// Tests
testImplementation("junit:junit:4.13.2")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
debugImplementation("androidx.compose.ui:ui-tooling")
```

---

## 4. Hermes System Connection — The Full Picture

### 4.1 Communication Topology

```
┌──────────────────────────────────────────────────────────────────────┐
│                         INTERNET                                     │
│                                                                      │
│   ┌──────────────┐     HTTPS       ┌──────────────┐                │
│   │ Hermes Mobile │◄──────────────►│ Telegram Bot │                │
│   │   (Phone)    │                 │   API        │                │
│   └──────┬───────┘                 └──────────────┘                │
│          │                                                          │
│          │  HTTPS (OAuth2)                                          │
│          │                                                          │
│   ┌──────▼───────────────────────────────────────────┐              │
│   │              Google Drive (app-only)              │              │
│   │  ┌──────────────┬──────────────┬──────────────┐ │              │
│   │  │ HermesInbox  │ HermesOutbox │ HermesShared │ │              │
│   │  │ (phone→PC)   │  (PC→phone)  │ (bidir)      │ │              │
│   │  └──────────────┴──────────────┴──────────────┘ │              │
│   └──────────────────────────────────────────────────┘              │
│                                                                      │
│   ┌──────────────────────────────────────────────────┐              │
│   │   YOUR PC (LAN/WAN, no port forwarding needed)    │              │
│   │                                                    │              │
│   │   ┌────────────────────────────────────────┐      │              │
│   │   │  Hermes Agent CLI / Python watcher     │      │              │
│   │   │  1. Polls Drive HermesInbox            │      │              │
│   │   │  2. Reads prompt + attachments         │      │              │
│   │   │  3. Runs via Hermes agentic system     │      │              │
│   │   │  4. Writes result → HermesOutbox       │      │              │
│   │   │  5. (optional) Telegram notification   │      │              │
│   │   └────────────────────────────────────────┘      │              │
│   └──────────────────────────────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────┘

KEY PROPERTY: Phone and PC never talk directly. Drive is the message bus.
              Internet is the only requirement. No port forwarding. No VPS.
```

### 4.2 Drive Folder Layout (Created Automatically)

All folders are created under a root folder named `HermesMobile` in the user's Drive.

```
HermesMobile/                          ← root folder (created by DriveRemoteDataSource.ensureHermesFolder)
├── HermesInbox/                       ← Phone writes task prompts + attachments here
│   ├── task_<id>_prompt.txt           ← Text prompt for the task
│   ├── task_<id>_attachment_<n>.bin   ← Binary attachments
│   └── task_<id>_meta.json            ← { id, prompt, attachments[], priority, tags[], created_at }
│
├── HermesOutbox/                      ← PC writes results here
│   ├── task_<id>_result.txt           ← Text result / summary
│   ├── task_<id>_artifact_<n>.bin     ← Generated artifacts (PDFs, images, etc.)
│   └── task_<id>_meta.json            ← { id, status, completed_at, error? }
│
└── HermesShared/                      ← Bidirectional shared storage
    ├── <any file from phone>
    └── <any file from PC>
```

### 4.3 Message Formats

#### Task Meta JSON (`task_<id>_meta.json` in HermesInbox)
```json
{
  "id": 42,
  "prompt": "Summarise the Collatz paper and save as PDF",
  "attachments": ["task_42_attachment_1.pdf"],
  "priority": 1,
  "tags": ["research", "pdf"],
  "notify_on_complete": true,
  "created_at": "2025-01-15T10:30:00Z",
  "phone_user": "Md Sadman Bin Masud"
}
```

#### Result Meta JSON (`task_<id>_meta.json` in HermesOutbox)
```json
{
  "id": 42,
  "status": "COMPLETED",
  "result_summary": "Paper summarised. 3 key findings extracted.",
  "artifacts": ["task_42_artifact_1.pdf"],
  "error": null,
  "started_at": "2025-01-15T10:30:05Z",
  "completed_at": "2025-01-15T10:32:18Z",
  "pc_host": "warnerbros-PC"
}
```

---

## 5. PC-Side Companion Script (REQUIRED — not included in repo)

The Android app is half the system. The other half is a lightweight watcher running on your PC.

### 5.1 What the Watcher Does
1. Authenticates to the same Google Drive account as the phone app
2. Polls `HermesInbox` every N seconds for new files
3. For each new task:
   - Reads `task_<id>_meta.json`
   - Reads any attachment files
   - Invokes the Hermes agent CLI with the prompt
   - Captures stdout/stderr + generated artifacts
   - Writes results + artifacts to `HermesOutbox`
   - Updates task status in local DB (optional, if PC also runs Room)
4. Optionally sends a Telegram notification via the same bot token

### 5.2 Recommended Watcher Spec
- **Language:** Python 3.10+ (matches your existing Hermes setup)
- **Dependencies:** `google-api-python-client`, `oauth2client`, `watchdog` (for efficient polling)
- **Location:** `D:\HermesMobileWatcher\` or anywhere on PC
- **Config:** Reads from same DataStore/JSON as Hermes gateway for bot token
- **Trigger:** Also listens for Telegram messages from your user ID as an alternative trigger path

### 5.3 Watcher Pseudocode
```python
# watcher.py — sketch, not production code
import drive_client, hermes_cli, tg_notifier

INBOX = "HermesInbox"
OUTBOX = "HermesOutbox"
POLL_INTERVAL = 15  # seconds

while True:
    new_tasks = drive_client.list_new(INBOX, since=last_seen)
    for task in new_tasks:
        meta = drive_client.read_json(f"{INBOX}/task_{task['id']}_meta.json")
        attachments = drive_client.download_all(INBOX, meta["attachments"])
        
        result = hermes_cli.run(meta["prompt"], attachments)
        
        drive_client.upload_text(OUTBOX, f"task_{task['id']}_result.txt", result["summary"])
        for art in result["artifacts"]:
            drive_client.upload_bytes(OUTBOX, art["filename"], art["bytes"])
        drive_client.upload_json(OUTBOX, f"task_{task['id']}_meta.json", {
            "id": task["id"],
            "status": "COMPLETED",
            "result_summary": result["summary"],
            "artifacts": [a["filename"] for a in result["artifacts"]],
            "completed_at": now_iso()
        })
        
        if meta.get("notify_on_complete"):
            tg_notifier.send(meta["tg_chat_id"], f"✅ Task #{task['id']} completed")
    
    sleep(POLL_INTERVAL)
```

---

## 6. Data Flow Reference

### 6.1 Task Submission Flow (Phone → PC)

```
User taps "Send" in SubmitTaskScreen
        │
        ▼
TaskRepository.submitTask(prompt, attachments, priority)
        │
        ├──► Room: INSERT AgentTaskEntity (status=PENDING)
        │
        ├──► HermesSyncWorker (WorkManager, periodic 15 min)
        │       │
        │       ├──► DriveRemoteDataSource.uploadToInbox(task_<id>_meta.json, ...)
        │       │
        │       └──► TelegramRemoteDataSource.sendMessage(chatId, "New task queued")
        │
        └──► (optional) Telegram push notification to user
```

### 6.2 Task Result Flow (PC → Phone)

```
PC watcher detects new file in HermesInbox
        │
        ▼
Hermes agent processes task
        │
        ▼
PC watcher writes to HermesOutbox:
  - task_<id>_result.txt
  - task_<id>_meta.json (status=COMPLETED)
        │
        ▼
Phone: HermesSyncWorker refreshes Drive index
        │
        ▼
DriveRepository.refreshDriveIndex()
        │
        ▼
Room: UPSERT DriveFileEntity (isSyncedLocally=true)
        │
        ▼
UI observes DriveFileDao.observeAll() → FilesScreen updates
```

### 6.3 File Sync Flow (Bidirectional)

```
Phone: User selects file in FilesRoute
        │
        ▼
DriveRepository.uploadFile(fileName, bytes, mimeType)
        │
        ├──► DriveRemoteDataSource.uploadToInbox(...)
        │       │
        │       └──► Google Drive API: Files.create → HermesInbox/<fileName>
        │
        └──► Room: UPSERT DriveFileEntity

PC watcher sees new file in HermesInbox
        │
        ▼
PC processes / moves file as needed
        │
        ▼
Phone refreshes → sees file in HermesShared or HermesOutbox
```

---

## 7. UI Navigation Map

### 7.1 Current Shell (`HermesAppPlaceholder.kt`)

| Tab Index | Route | Screen Composable | Status |
|-----------|-------|-------------------|--------|
| 0 | `TasksRoute()` | TasksScreen.kt | ✅ Demo data, wired |
| 1 | `FilesRoute()` | FilesScreen.kt | ✅ Demo data, wired |
| 2 | `SubmitRoute()` | SubmitTaskScreen.kt | ✅ UI only, submit is TODO |
| 3 | `MessagesRoute()` | MessagesScreen.kt | ✅ Demo data, wired |
| 4 | `SettingsRouteStub()` | Inline in HermesAppPlaceholder.kt | ✅ Card stub |

### 7.2 Target Navigation (Future)

The `NavGraph.kt` has a proper `NavHost` scaffold but is **currently unused**. The active shell is `HermesAppPlaceholder.kt`.

**Agent migration plan:**
1. Replace `HermesAppPlaceholder` content with `HermesApp(authViewModel)` from `NavGraph.kt`
2. Wire `AuthViewModel` via `hiltViewModel()` in `MainActivity`
3. Add proper `NavController` to each screen's FAB/actions
4. Remove `HermesAppPlaceholder.kt` after migration

### 7.3 Screen Responsibilities

| Screen | What It Does | What It Needs From Repo |
|--------|-------------|------------------------|
| TasksScreen | Lists tasks, filters by status, FAB → Submit | `TaskRepository.observeTasks()`, `TaskRepository.submitTask()` |
| FilesScreen | Lists Drive files, upload FAB, tap to preview | `DriveRepository.observeFiles()`, `DriveRepository.uploadFile()` |
| MessagesScreen | Shows message bubbles (Hermes vs PC) | `CachedMessageDao.observeRecent()`, real-time Drive outbox polling |
| SubmitTaskScreen | Prompt input, quick chips, priority, attachments | `TaskRepository.submitTask()` |
| SettingsScreen | Theme toggle, connection status, cache clear | `SettingsRepository`, `DriveRepository.getSyncedCount()` |

---

## 8. Critical Implementation Gaps (Agent Must Address)

### 8.1 Compile-Blocking Issues (All Resolved)

| Issue | File | Status |
|-------|------|--------|
| Hilt `@HiltWorker` needs `kapt`/`ksp` path | `build.gradle.kts` | Resolved — Correct dependencies configured |
| `HermesApplication.kt` references `Configuration.Provider` | `HermesApplication.kt` | Resolved — Proper imports |
| `AuthViewModel` uses `@Inject` constructor context | `AuthViewModel.kt` | Resolved — Correctly injected parameters |
| `DriveRemoteDataSource.toAbstractInputContent` hack | `DriveRemoteDataSource.kt` | Resolved — Proper ByteArrayContent used |
| `coil-compose` imported twice in `build.gradle.kts` | `build.gradle.kts` | Resolved — Cleaned up build dependencies |
| `SettingsRepository.kt` uses `Flow.mapToList` | `SettingsRepository.kt` | Resolved — Replaced with standard map flow |

### 8.2 Missing Features (All Completed)

| Feature | Status | Notes |
|---------|--------|-------|
| Real Google Sign-In flow | COMPLETED | `GoogleSignInHelper.kt` + `AuthScreen.kt` fully wire authentication |
| Drive file preview (PDF, images, video) | COMPLETED | `FilePreviewDialog.kt` handles images, PDFs, text, and markdown previews |
| Real task submission end-to-end | COMPLETED | Wired `SubmitTaskScreen` to `TaskRepository.submitTask()` |
| PC watcher script | COMPLETED | Implemented at `D:\HermesMobileWatcher\watcher.py` |
| Background Drive sync via WorkManager | COMPLETED | `HermesSyncWorker` runs periodically |
| Push notifications for task completion | COMPLETED | Handled inside `HermesSyncWorker` on task finish |
| File download to local cache | COMPLETED | Implemented via `DriveRepository` and caching mechanisms |
| Search in Files screen | COMPLETED | Implemented in `FilesScreen` via `setQuery` filter |
| Authentication state persistence | COMPLETED | Restores session on startup via `restoreDriveSession()` |

### 8.3 Design & Polish (Agent Can Improve)

| Area | Current State | Suggested Improvement |
|------|---------------|----------------------|
| Theme | Static dark theme, no dynamic color | Add dynamic color on Android 12+ |
| Animations | None | Add `androidx.compose.animation` for screen transitions |
| Empty states | Simple icon + text | Add Lottie animations or animated illustrations |
| Typography | Default system font | Add `google.fonts` or custom `FontFamily` |
| Icons | Material Icons Extended | Add brand-specific Hermes icons (SVG) |
| Splash screen | None | Add `androidx.core.splashscreen.SplashScreen` API |
| Haptic feedback | None | Add `HapticFeedback` on FAB, card taps |
| Accessibility | None | Add `contentDescription`, `semantics` to all interactive elements |
| Error handling | Silent `catch {}` blocks | Add `SnackbarHost`, `userMessage` state in ViewModels |
| Loading states | CircularProgressIndicator only | Add skeleton screens, shimmer effects |

---

## 9. Hermes-Specific Design Decisions

### 9.1 Why Drive Instead of a Custom Server

| Requirement | Drive Solution |
|-------------|----------------|
| File storage | Native file hosting with 15GB free |
| Authentication | OAuth2 via Google (no custom auth server) |
| File sharing | Built-in ACLs (app-only `drive.file` scope) |
| Offline access | Drive app cache + Room local DB |
| History/versioning | Native Drive version history |
| No hosting | Zero infrastructure cost |
| Cross-device | Works on phone, PC, web automatically |

### 9.2 Why Telegram Bot Instead of Custom Socket

| Requirement | Telegram Solution |
|-------------|-------------------|
| Push notifications | Native Telegram push to phone |
| Bidirectional chat | Bot API sendMessage + getUpdates |
| No port forwarding | Telegram cloud relays messages |
| User identity | Telegram user ID is the auth token |
| Group chat support | Multiple users can DM the same bot |
| Rate limits | Generous for single-user use |
| Fallback | If Telegram is blocked, Drive polling still works |

### 9.3 Security Model

```
THREAT MODEL:
- Phone stolen → Data in Room is unencrypted (TODO: EncryptedSharedPreferences + EncryptedFile)
- Drive token leaked → Scope is drive.file (app-only, cannot see other Drive files)
- Bot token leaked → Attacker can read/send messages to your bot → mitigate with TELEGRAM_ALLOWED_USER_IDS
- Man-in-the-middle → All API calls use HTTPS (api.telegram.org, drive.google.com)

CURRENT STATE:
- Bot token: stored in plaintext DataStore (upgrade to EncryptedSharedPreferences)
- Drive OAuth: handled by Google Play Services (secure)
- Local DB: plaintext SQLite (upgrade to SQLCipher if needed)
- Network: HTTPS only (cleartext traffic disabled in manifest)
```

---

## 10. Testing Strategy

### 10.1 Unit Tests (Agent Should Add)
- `TelegramRemoteDataSource` — mock OkHttp, test JSON serialization
- `DriveRemoteDataSource` — mock Drive service, test folder creation + upload
- `TaskRepository` — test submit/cancel/observe flows
- `SettingsRepository` — test DataStore read/write
- `AuthViewModel` — test theme toggle, onboarded state

### 10.2 Instrumentation Tests
- Compose UI tests for each screen (`createComposeRule`)
- Navigation test: bottom nav switches screens
- Drive sign-in flow (use `ActivityResultRegistry` fake)

### 10.3 Manual QA Checklist
- [ ] Bot token saved → app remembers after restart
- [ ] Google sign-in → folders created on Drive
- [ ] Submit task → file appears in Drive HermesInbox
- [ ] PC processes task → result appears in Files screen
- [ ] Dark theme toggle persists
- [ ] App survives configuration change (rotation)
- [ ] App survives process death (Room + DataStore restore state)

---

## 11. Common Pitfalls for Agents Working on This Project

1. **Do NOT create a new `build.gradle`** — the project uses `build.gradle.kts` (Kotlin DSL). Creating a Groovy `build.gradle` will break the build.
2. **Do NOT rename the package** — `com.hermes.mobile` is hardcoded in the manifest, Drive scope, and everywhere. Renaming requires updating all 38 Kotlin files + manifest + Drive API config.
3. **Do NOT remove the `@AndroidEntryPoint` annotation** from `MainActivity` — it's needed for any `@HiltViewModel` injection.
4. **The `AuthViewModel` currently uses a non-Hilt constructor** — it takes `appContext` as a plain parameter. If you add `@HiltViewModel`, change the constructor to use `@ApplicationContext Context` and remove manual injection.
5. **Drive API scope is `drive.file`** — this is intentional (least privilege). Do NOT change to `drive` unless the user explicitly asks.
6. **Room `fallbackToDestructiveMigration()`** is set — for production, add proper migrations.
7. **The `HermesDatabase.kt` file was overwritten twice** — verify the current version has all 5 entities and 5 DAO methods.
8. **`TelegramRemoteDataSource` serializes maps manually** — it does NOT use `kotlinx.serialization` for the request body (uses raw JSON string). Do NOT refactor to `JsonObject` without testing.
9. **`DriveRemoteDataSource.uploadToInbox` has a hack for `AbstractInputStreamContent` length** — this is a known workaround. The proper fix is to use `ByteArrayContent` from the Drive library.
10. **WorkManager initialization is done twice** — once in `HermesApplication.getWorkManagerConfiguration()` and once in `WorkModule`. Remove one.

---

## 12. Reference: Hermes Agent Integration Points

### 12.1 Hermes Gateway Configuration
The Telegram gateway in Hermes uses environment variables or config:
```yaml
# ~/.hermes/config.yaml (hypothetical)
telegram:
  bot_token: "123456:ABC-DEF..."
  allowed_users: ["6995160255"]
  home_channel: "6995160255"
```

### 12.2 What the Android App Needs from Hermes Config
The Android app needs:
1. **Same bot token** — so it can send messages to the same bot
2. **Allowed user ID** — so the app knows which Telegram user is authorized
3. **Drive folder IDs** — so PC and phone read/write the same folders

**Sync strategy:** When the user configures the bot token in the Android app, they should also paste it into Hermes gateway config. The app can optionally read the Hermes config file if it's on the same device, but typically the user enters it manually in both places.

### 12.3 Drive Folder ID Sync
The app creates `HermesMobile` root folder + subfolders. The PC watcher should:
- Look for folder named `HermesMobile` in the user's Drive
- If not found, create it with the same structure
- Cache folder IDs in a local JSON config

This ensures both sides converge on the same folders without manual ID sharing.

---

## 13. Extensibility Guide for Agents

### 13.1 Adding a New Screen
1. Create `ui/screens/NewScreen.kt` with a `@Composable` function
2. Add a `Screen` object in `ui/navigation/NavGraph.kt`
3. Add a `composable(Screen.X.route) { NewScreen() }` in the NavHost
4. Add a tab in `HermesAppPlaceholder.kt` or use NavGraph directly

### 13.2 Adding a New Repository Method
1. Add DAO method in `data/local/daos.kt` (if local)
2. Add remote method in `*RemoteDataSource.kt` (if remote)
3. Add `suspend` method in `domain/repository/XRepository.kt`
4. Implement in the concrete repository
5. Call from ViewModel or `SubmitTaskScreen`

### 13.3 Adding a New Drive Folder Type
1. Add constant in `DriveRemoteDataSource.Companion`
2. Call `ensureHermesFolder("NewFolderName")` 
3. Use the returned folder ID for file operations

### 13.4 Adding a New Telegram Feature
1. Add DTO in `data/remote/dto/TelegramDtos.kt`
2. Add method in `TelegramRemoteDataSource`
3. Call from repository or ViewModel

---

## 14. Build Commands Reference

```bash
# From D:\HermesMobile\

# Debug build (requires Android SDK + JDK 17)
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest

# Clean
./gradlew clean

# View dependency tree
./gradlew dependencies

# Lint
./gradlew lint
```

---

## 15. Signing Configuration (Add Before Release Build)

In `app/build.gradle.kts`, inside `android { buildTypes { release { ... } } }`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("D:/path/to/keystore.jks")
        storePassword = "<from env or gradle.properties>"
        keyAlias = "<key alias>"
        keyPassword = "<from env or gradle.properties>"
    }
}
```

Then apply:
```kotlin
release {
    signingConfig = signingConfigs.getByName("release")
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

---

## 16. Final Agent Checklist

Before declaring "done", an agent working on this project should:

- [ ] All Kotlin files compile without errors (`./gradlew compileDebugKotlin`)
- [ ] All resource references resolve (no missing `@drawable` or `@string`)
- [ ] `HermesApplication` is the single `Application` class (no duplicates)
- [ ] `MainActivity` is the single launcher activity
- [ ] All `import` statements are used (no unused imports causing warnings)
- [ ] ProGuard rules cover Room entities and serialization classes
- [ ] All screens have at least one test (even basic smoke test)
- [ ] README.md is updated with current build status
- [ ] AGENT_HANDBOARD.md (this file) is updated with new findings
- [ ] No plaintext tokens in committed code (use `local.properties` or env vars)

---

*Last updated: 2025-08-08. This file should be treated as the living spec for the Hermes Mobile project.*
