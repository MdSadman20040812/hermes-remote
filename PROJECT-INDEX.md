# ✅ Hermes Mobile — Canonical Project Root

**You are in the right folder.** `D:\HermesMobile\` is the real, live, buildable
Hermes Mobile project. Everything else is a stale copy.

Verified 2026-08-16 by full file-level comparison.

---

## Which copy is which

| Path | Status | Why |
|---|---|---|
| **`D:\HermesMobile\`** | ✅ **CANONICAL — use this** | ~60 Kotlin files · gradle wrapper · `local.properties` · builds to a 70.8 MB `app-debug.apk` · newest edit 2026-08-08 17:02 UTC |
| `D:\Outputs\HermesMobile\` | ⛔ **STALE — ignore** | 38 Kotlin files · no wrapper · never built · frozen at 2026-08-08 03:17 UTC. Marked with `_STALE-DO-NOT-USE.md`. |
| `D:\HermesMobileWatcher\` | 🗄️ **Retiring** | PC-side Drive watcher for the v1 architecture. `workspace\` is empty. Obsolete under v2 — see the spec. |

> **Heads-up:** `AGENT_HANDBOOK.md` in this folder still says
> *"Source of truth: `D:\Outputs\HermesMobile\`"*. **That line is wrong** and is
> the sole cause of the two-copies confusion. The handbook is otherwise
> out of date too — it is superseded by `HERMES_MOBILE_V2_SPEC.md`.

---

## Read these, in this order

| File | What it is |
|---|---|
| **`HERMES-BUILD-BRIEF.md`** | 🎯 **The mission brief handed to Hermes Agent.** What to read, verify, and build, phase by phase. Point Hermes here. |
| **`HERMES_MOBILE_V2_SPEC.md`** | ⭐ **Current architecture + rebuild plan.** The living spec. The brief's primary reference. |
| `README.md` | v1 build/architecture notes. Historical. |
| `AGENT_HANDBOOK.md` | v1 agent handbook. **Superseded** — retained for history only. |
| `build_log.txt` | Last build output (2026-08-06 → 08-08). |

---

## Project at a glance

```
Package     com.hermes.mobile        (v2 proposes → com.hermes.remote)
Min SDK     26   ·  Target/Compile 35
Kotlin      2.0.21  ·  Compose BOM 2024.12.01  ·  Material 3
DI          Dagger Hilt 2.51        Local DB   Room 2.6.1
Build       Gradle 8.7 (KTS) + KSP  Output     app/build/outputs/apk/debug/
```

```
D:\HermesMobile\
├── HERMES-BUILD-BRIEF.md       🎯 the mission brief for Hermes Agent
├── HERMES_MOBILE_V2_SPEC.md    ⭐ the plan
├── PROJECT-INDEX.md            ← you are here
├── AGENT_HANDBOOK.md           (superseded)
├── README.md                   (v1)
├── build.gradle.kts · settings.gradle.kts · gradle.properties
├── gradlew · gradlew.bat · gradle/wrapper/     ← only the real copy has these
├── local.properties                            ← and this
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        └── java/com/hermes/mobile/
            ├── HermesApplication.kt
            ├── ui/        HermesApp.kt, MainActivity.kt, screens/, components/, theme/, auth/
            ├── data/      local/ (Room), remote/ (Drive + Telegram — being removed)
            ├── domain/    model/, repository/
            ├── di/        AppModule.kt
            ├── core/work/ WorkManager sync (being removed)
            └── service/   TransferForegroundService.kt
```

---

## Build

```powershell
cd D:\HermesMobile
.\gradlew assembleDebug      # → app\build\outputs\apk\debug\app-debug.apk
.\gradlew installDebug       # to a connected device
.\gradlew clean
```

Requires JDK 17 and the Android SDK (`local.properties` already points at it).

---

## Where the project is headed

v1 routes everything through Google Drive as a file-based message bus. v2 drops
that entirely and makes the app a native client of the Hermes JSON-RPC control
API (`ws://<tailscale-ip>:9119/api/ws`) — real-time streaming, interactive
approvals, embedded terminal, full session continuity with your desktop.

Full detail, including the audit of what to keep/rewrite/delete and a six-phase
build plan, is in **`HERMES_MOBILE_V2_SPEC.md`**.

---

*Index written 2026-08-16 by Claude (Cowork).*
