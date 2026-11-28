<div align="center">

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | English

# van-schedule (van课表)

**A local-first native Android class schedule app with multi-format import, rescheduling sync, soft-glass styling, widgets, and an experimental Xiaomi Vela wearable companion.**

[![Release](https://img.shields.io/github/v/release/vanvan-program/van-schedule?style=flat-square)](https://github.com/vanvan-program/van-schedule/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=flat-square)](#installation--usage)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#tech-stack--project-structure)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[Latest Release APK](https://github.com/vanvan-program/van-schedule/releases/latest) · [Full Changelog](CHANGELOG.md) · [Wearable Guide](wearable/README.md) · [SDK Setup](app/libs/README.md) · [Usage & Validation (ZH)](docs/使用与待验证项.md) · [Issues](https://github.com/vanvan-program/van-schedule/issues)

</div>

---

## Overview

**van-schedule** is a native Android timetable app built with Kotlin, Jetpack Compose, Room, and DataStore, accompanied by an experimental **Xiaomi Vela wearable companion**. Designed to be local-first with zero ads, no account system, and no analytics, it covers everything from multi-format schedule import to drag-and-drop rescheduling, reminders, and home-screen widgets.

> [!TIP]
> The complete chronological version history (`0.1.5` – `0.5.23`) and validation logs have been moved to **[CHANGELOG.md](CHANGELOG.md)**.

## Key Features

- **Multi-Format Local Import & Smart Re-Import**:
  - Import via custom academic portal URLs (no hardcoded real school URLs; login sessions are cleared by default).
  - On-device import from **PDF, Excel (`.xlsx`/`.xls`), schedule images (bundled offline Tesseract OCR)**, WakeUp backups, and 7-column CSVs. Re-importing diffs added/removed/changed classes while preserving matching notes and reschedules.
- **Week & Day Views with Rich Interactions**:
  - Top bar single-tap Week/Day toggle, multi-schedule switcher, "Back to Today/This Week" pill, and bottom week scrubber.
  - Cross-week drag-and-drop rescheduling (overwrite or swap), automatic compact vs. gap-preserving timeline, single-session overrides, full-day holidays, and per-course notes.
- **Customizable Glass & Material You Appearance**:
  - Powered by the local `:softglass` module with Soft Glass / Frosted / Plain surfaces, custom wallpapers, AGSL progressive top-bar blur, soft/solid card styles, and Android 12+ Monet dynamic colors.
- **Home Screen Widgets & Class Reminders**:
  - Scrollable 2-day and Today widgets plus a "Next Class" widget—each instance independently binds a schedule and theme.
  - Pre-class reminders and optional ongoing notifications that automatically stay in sync when classes are rescheduled, moved, or canceled.
- **Xiaomi Vela Wearable Companion** *(experimental)*: Syncs 14 days of classes to the band with offline cache support; see [wearable/README.md](wearable/README.md).
- **Backup & Verified In-App Updates**:
  - Full backup/restore with pre-overwrite snapshots, single-schedule export, and weekly image sharing.
  - In-app update checker with resumable downloads, mirror fallback, and strict package/version/signature/SHA-256 verification before installation.

## Installation & Usage

1. Download `van-schedule-<version>-arm64.apk` (along with SHA-256 checksums) from [GitHub Releases](https://github.com/vanvan-program/van-schedule/releases/latest).
2. Requires **Android 8.0+ (API 26+)**. Signed releases upgrade in place over previous official builds while keeping all data.
3. Import an existing schedule or create one manually from the top bar, and optionally grant Notification & Exact Alarm permissions for timely reminders and widget refreshes.

## Permissions & Privacy

- All schedules are stored locally in **Room** and preferences in **DataStore**; Android cloud auto-backup is disabled.
- PDF, Excel, and OCR parsing run **100% offline on device**—no files or parsed schedules are ever uploaded. Exported backups exclude cookies, credentials, and widget IDs.
- Declared permissions: `INTERNET` (for web portal import and manual update checks), `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. Uses SAF and FileProvider without broad storage, camera, location, or Bluetooth permissions.

## Tech Stack & Project Structure

- **Stack**: Kotlin `2.2.10` · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room · DataStore · Kotlinx Serialization · Coroutines · Tesseract4Android `4.9.0` · JExcelAPI `2.6.12` · AGP `9.1.1` · Gradle `9.3.1`
- **Modules**:
  - `:app` — Android application (UI, widgets, notifications, importers, updater)
  - `:core:domain` / `:core:data` — Domain models, timetable/timeline logic, and persistence
  - `:softglass` — Soft glass & optical blur UI library (Apache-2.0)
  - `wearable/` — Xiaomi Vela wearable companion app

## Building from Source

> [!IMPORTANT]
> The public repository does **not** include the proprietary wearable communication SDK AAR or release signing keys. Follow [app/libs/README.md](app/libs/README.md) to obtain the SDK before building.

- **Requirements**: JDK `21` (JVM 17 target) · Gradle `9.3.1` · Android SDK `37.0` · Build Tools `36.0.0`

```powershell
# After configuring SDK path in local.properties:
.\gradlew.bat :core:domain:test :app:assembleDebug :app:lintDebug
# Or use the local helper script:
./build-local.ps1
```

- Release builds (`./gradlew :app:assembleRelease`) enable R8 and default to `arm64-v8a`; pass `-PvanReleaseAbis=armeabi-v7a,arm64-v8a,x86,x86_64` for additional ABIs.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. Official in-place upgrade signing requires a local private `signing.properties`.

## License & Attributions

- Main application: **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)**.
- Includes ported components derived from NexioSchedule under **AGPL-3.0**, and the `:softglass` module under **Apache-2.0**.
- See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for full third-party license notices.
