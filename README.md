# 📱 Hermes Remote

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![PowerShell](https://img.shields.io/badge/PC-PowerShell%20%2B%20Python-5391FE?style=for-the-badge&logo=powershell&logoColor=white)](https://docs.microsoft.com/powershell/)
[![Tailscale](https://img.shields.io/badge/Transport-LAN%20%2F%20Tailscale-6E5494?style=for-the-badge&logo=tailscale&logoColor=white)](https://tailscale.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

> **Drive a desktop AI coding agent from your phone — on any network, from anywhere.**

Hermes Remote is a two-part system that puts a long-running desktop AI agent in your pocket. Sessions live on the **PC** — they keep running when the screen locks, when the app is backgrounded, and when you walk out of the house. The phone is a thin, stateful client that reattaches to whatever is already running.

---

## 🎯 The Problem

Desktop AI coding agents (Claude Code, Copilot CLI, opencode, Gemini) are powerful — but they're tied to your desk. You can't check progress from the kitchen, approve a file write from the garage, or inspect a crash from another room.

Existing solutions either stream a full desktop (laggy, bandwidth-heavy) or require complex VPN setup. Hermes Remote is purpose-built: a native Android app paired with a lightweight PC backend.

---

## 📡 Architecture

```
┌──────────────────────────┐         WireGuard / LAN          ┌───────────────────────────────┐
│     Android Client       │                                  │        Windows PC             │
│                          │   1. GET  /api/status            │                               │
│   Cockpit  ─────────────►│   2. POST /auth/password-login   │   Hermes dashboard  :9119     │
│   Terminal ◄────────────►│   3. WebSocket /ws/sessions      │   (OpenAI-compatible API)     │
│   Sessions ◄────────────►│                                  │                               │
│   Files    ◄────────────►│   Bi-directional:               │   Hermes watchdog              │
│   Ops      ◄────────────►│   • terminal I/O                │   (self-healing, always-on)   │
│                          │   • file transfer                │                               │
│                          │   • cron/git/usage control       │   Python artifact server      │
└──────────────────────────┘                                  └───────────────────────────────┘
```

| Layer | Tech | Role |
|-------|------|------|
| **Android** | Kotlin 2.0 + Jetpack Compose M3 | Cockpit, terminal, sessions, files, ops |
| **PC Backend** | Python 3.11 + PowerShell 5.1 | Dashboard, watchdog, artifact server, QR pairing |
| **Transport** | WebSocket over LAN or WireGuard | Encrypted, persistent, reconnect-capable |
| **Watchdog** | PowerShell task + Python headless | Auto-restart, health-check, network-independent |
| **Pairing** | QR code scan | One-time setup, no manual IP entry |

---

## 🚀 Quick Start

### PC (Backend)

```powershell
# From D:\HermesMobile\pc\
pip install -r requirements.txt  # if needed
.\hermes-remote.ps1
# → Launches dashboard on http://192.168.x.x:9119
# → Shows QR code for pairing
```

### Phone (Android)

```bash
# Build APK
cd D:\HermesMobile
./gradlew assembleDebug

# Or install directly to connected device
./gradlew installDebug
```

1. Open Hermes Remote on phone
2. Scan QR code shown on PC dashboard
3. Authenticate with dashboard password
4. Sessions appear — tap to connect

---

## 📱 Android App Screens

| Screen | Purpose |
|--------|---------|
| **Cockpit** | Quick-glance status, connection health, agent activity |
| **Terminal** | Full WebSocket terminal with special keys (Ctrl-C, Tab, arrow keys) |
| **Sessions** | List all agent sessions, swipe to delete, tap to reattach |
| **Files** | Browse phone & PC files, upload/download, image preview |
| **Ops** | Dashboard controls: cron jobs, git status, model selector, usage stats |

---

## 🔒 Security

| Feature | Status |
|---------|--------|
| Password-gated dashboard | ✅ |
| Session-bound auth tokens | ✅ |
| LAN-only default | ✅ |
| WireGuard/Tailscale tunnel | ✅ |
| No cloud, no telemetry | ✅ |

---

## 🧪 Testing

The Android app includes comprehensive unit and device tests:

```bash
# Unit tests
./gradlew testDebugUnitTest

# Device tests (requires connected phone)
./gradlew connectedDebugAndroidTest

# Or run specific test class
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=comhermes.mobile.data.repo.TransferRepositoryDeviceTest
```

**Test coverage includes:**
- JSON serialization/deserialization
- WebSocket frame parsing
- Reconnection policy (exponential backoff with jitter)
- File transfer integrity
- Credential strategy (gated vs. open)
- Terminal input sanitization
- Domain model validation

---

## 📁 Project Structure

```
hermes-remote/
├── app/                        # Android app (Kotlin + Compose)
│   ├── src/main/
│   │   ├── java/com/hermes/mobile/
│   │   │   ├── core/           # Connection, transport, terminal, vault
│   │   │   ├── data/           # Repositories, local cache
│   │   │   ├── di/             # Hilt dependency injection
│   │   │   ├── domain/         # Models, enums, parsing
│   │   │   ├── service/        # Foreground services, quick-tile
│   │   │   ├── ui/             # Screens, components, theme
│   │   │   └── widget/         # Home-screen widget provider
│   │   └── res/                # Drawables, fonts, layouts
│   └── src/test/               # Unit tests
├── pc/                         # PC backend
│   ├── hermes-remote.ps1       # Main launcher
│   ├── hermes-dashboard.py     # HTTP dashboard server
│   ├── hermes-watchdog.ps1     # Health-check watchdog
│   ├── hermes-watchdog-headless.py  # Headless watchdog
│   ├── artifact_server.py      # File/image serving
│   ├── qr_server.py            # QR code generation
│   ├── render_qr.py            # QR rendering
│   ├── mobile_contract_probe.py # API testing
│   └── vendor/                 # Bundled dependencies (qrcode, colorama)
└── docs/                       # Architecture, build briefs, findings
```

---

## 🛠️ Build Environment

| Component | Version |
|-----------|---------|
| Android Gradle Plugin | 8.2 |
| Gradle | 8.9 |
| Kotlin | 2.0 |
| Compile SDK | 34 |
| Min SDK | 29 |
| JDK | 17 (Eclipse Adoptium) |

> **Note:** Android Studio's bundled JBR is incompatible with Gradle 8.9. Use JDK 17.

---

## 📊 Verified Behaviors

| Behavior | Status |
|----------|--------|
| Auto-reconnect on network change | ✅ |
| Watchdog self-heal after crash | ✅ |
| File transfer integrity (SHA-256) | ✅ |
| Terminal special keys (Ctrl-C, Tab) | ✅ |
| QR one-tap pairing | ✅ |
| Session persistence across app kills | ✅ |
| Gated auth (password + session token) | ✅ |

---

## 👥 Contributors

| Name | Role |
|------|------|
| **Md Sadman Bin Masud** | System architecture, Android, backend, testing |
| **Mayesha Muntaha** | UI/UX design, dashboard |
| **Shihab Uddin** | Security, protocol design |
| **Rafith** | DevOps, CI/CD |

---

## 📄 License

MIT — see [LICENSE](LICENSE)

---

<div align="center">

**📱 Your desktop agent. In your pocket. 📱**

[Report Bug](../../issues) • [Request Feature](../../issues) • [Docs](docs/)

</div>
