# Hermes Mobile — Complete Android Project

**Location:** `D:\Outputs\HermesMobile`

## What This Is

A full, production-scaffolded Android app that gives you Hermes-agentic control from your phone:

| Feature | Implementation |
|---------|---------------|
| Submit agentic tasks from phone | Telegram Bot API → Drive `HermesInbox` |
| Browse & manage PC files | Drive REST API v3 via Google account |
| Receive task results/notifications | Drive `HermesOutbox` + WorkManager sync |
| Secure, zero-hosting | No app server; Drive IS the transport |
| Beautiful Material 3 UI | Dark GitHub-inspired theme, Jetpack Compose |

## Architecture

```
┌────────────────────┐   HTTPS (Telegram)    ┌──────────────────┐
│   Hermes Mobile    │◄────────────────────►│  Telegram Bot    │
│   (this app)       │                       └──────────────────┘
│                    │   HTTPS (Drive API)
│  ┌──────────────┐  │◄─────────────────────────────────────────┐
│  │ App Layer    │  │              Google Drive                 │
│  │ Compose UI   │  │  ┌──────────────┬──────────────┐         │
│  │ Bottom Nav   │  │  │ HermesInbox  │ HermesOutbox │         │
│  └──────┬───────┘  │  │ (phone→PC)   │  (PC→phone)  │         │
│         │          │  └──────────────┴──────────────┘         │
│  ┌──────▼───────┐  │                                         │
│  │ Domain Layer │  │   PC (your machine)                      │
│  │ Repositories │  │   ┌──────────────────┐                   │
│  └──────┬───────┘  │   │ Hermes agent     │                   │
│         │          │   │ watches Drive     │                   │
│  ┌──────▼───────┐  │   └──────────────────┘                   │
│  │ Data Layer   │  │                                         │
│  │ Room (local) │  │                                         │
│  │ OkHttp/TG API│  │                                         │
│  │ Google Drive │  │                                         │
│  └──────────────┘  │                                         │
└────────────────────┘                                         └─ No hosting!
```

## How the Phone ↔ PC Communication Works

**No server. No hosting. No cloud app.**

1. Phone app posts a task prompt (and optional file attachments) to **HermesInbox** folder on Drive.
2. Hermes agent on your PC has a lightweight watcher script that polls that folder.
3. PC reads the prompt, executes it via Hermes agentic system, writes result to **HermesOutbox**.
4. Phone app syncs HermesOutbox and shows results + notifications.

For real-time push, the optional Telegram Bot can also relay messages — but **Drive is the persistent shared-state layer**.

## Build Instructions

### Prerequisites
- Android Studio Giraffe / Hedgehog (2023.1+)
- Android SDK 35
- JDK 17

### Steps
1. Open Android Studio → **Open** → select `D:\Outputs\HermesMobile`
2. Wait for Gradle sync (first build will download dependencies — ~15 min)
3. Connect your Android phone via USB with USB debugging enabled
4. Press **Run** ▶ (or `./gradlew assembleDebug`)

### Before running
- In `app/src/main/AndroidManifest.xml` set your real bot token (or configure it at runtime in Settings)
- For Drive: enable Google Drive API in Google Cloud Console, add SHA-1 + package name

## Project Structure

```
HermesMobile/
├── app/
│   ├── build.gradle.kts          ← app-level build config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/hermes/mobile/
│       │   ├── HermesApplication.kt          ← @HiltAndroidApp + WorkManager
│       │   ├── ui/
│       │   │   ├── MainActivity.kt           ← @AndroidEntryPoint
│       │   │   ├── HermesAppPlaceholder.kt   ← Bottom nav + 5-tab shell
│       │   │   ├── WelcomeScreen.kt          ← Onboarding splash
│       │   │   ├── auth/AuthViewModel.kt     ← Theme, onboarding, bot/Drive state
│       │   │   ├── navigation/NavGraph.kt    ← Compose NavHost scaffold
│       │   │   ├── theme/Color.kt, Type.kt, Shape.kt  ← Hermes-brand M3 theme
│       │   │   ├── components/StatusChip.kt, TransferCard.kt, SettingsBar.kt
│       │   │   └── screens/
│       │   │       ├── TasksScreen.kt        ← Task list with filter chips
│       │   │       ├── FilesScreen.kt        ← Drive file browser
│       │   │       ├── MessagesScreen.kt     ← Telegram + Drive message feed
│       │   │       ├── SubmitTaskScreen.kt   ← Prompt, attachments, priority
│       │   │       └── SettingsScreen.kt     ← Theme, connections, data
│       │   ├── data/
│       │   │   ├── local/                    ← Room entities, DAOs, DB
│       │   │   └── remote/                   ← TelegramRemoteDataSource, DriveRemoteDataSource
│       │   ├── domain/
│       │   │   ├── model/Models.kt
│       │   │   └── repository/               ← SettingsRepo, TaskRepo, DriveRepo
│       │   ├── core/work/                    ← HermesSyncWorker, WorkManager
│       │   └── service/                      ← TransferForegroundService
│       └── res/
│           ├── values/strings.xml, colors.xml, hermes_colors.xml, themes.xml
│           ├── mipmap-*/                     ← Adaptive icons
│           └── xml/file_paths.xml
├── build.gradle.kts            ← root build config
├── settings.gradle.kts
└── gradle.properties
```

## Telegram Gateway Alternatives Research

| Option | Hosted? | Free | Best For | Verdict |
|--------|---------|------|----------|---------|
| **Telegram Bot API (official)** | No | Yes | What this app uses | **Primary choice** |
| **Gotify** | No (self-host) | Yes | Push notifications, no chat | Good backup for notifications only |
| **ntfy.sh** | Optional | Yes | Minimal pub/sub | Too simple for bidirectional |
| **Self-hosted Telegram Bot API (tdlib)** | No | Yes | Higher rate limits | Overkill for single-user |
| **Discord Bot** | No | Yes | Rich embeds, threads | Possible alternative channel |
| **Matrix (Element)** | No | Yes | E2E encrypted | Best privacy, more infra |

### Recommendation
**Stay with Telegram Bot API.** It is the only option that:
- Requires zero hosting
- Supports bidirectional messaging (phone ↔ PC)
- Has a mature, well-documented REST API
- Works behind NAT/firewalls without port forwarding
- Has official Android libraries

Gotify is a useful **secondary channel** for real-time push notifications when Drive sync is 15-min periodic.

## PC-Side Companion Watcher Script

A robust companion script is available at `D:\HermesMobileWatcher\watcher.py`. It runs on your PC to poll Drive and execute task requests:

1. **Authentication:** The script automatically uses your existing Google Drive token at `D:\.hermes\google_token.json`.
2. **Notifications:** It reads your bot token and user IDs from `D:\.hermes\.env` to send real-time completion summaries to Telegram.
3. **Execution:** It runs tasks inside isolated temporary directories using the `hermes` CLI with the fast and free `kilo-auto/free` model via `kilocode`.
4. **Result Reporting:** It uploads results and any generated files (artifacts) back to `HermesOutbox` on Drive.

### Running the Watcher
To start the watcher script:
```bash
# Run using the Hermes agent virtual environment Python interpreter
D:\.hermes\hermes-agent\venv\Scripts\python.exe D:\HermesMobileWatcher\watcher.py
```

## Security Notes
- Bot token stored in DataStore (plaintext in this scaffold → use EncryptedSharedPreferences in production)
- Drive access via OAuth2 (Google handles token refresh)
- All Drive files created with `drive.file` scope (app-only, not full Drive access)
- Backup/DataExtraction rules exclude the local DB and preferences from cloud backup

## Next Steps After Building
1. Create bot via @BotFather → paste token in app
2. Enable Drive API in Google Cloud Console
3. Add your PC's Hermes watcher script
4. Test round-trip: submit task on phone → see result on phone
