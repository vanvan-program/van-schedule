<div align="center">

[简体中文](README.md) | 繁體中文 | [English](README.en.md)

# van課表

**原生 Android 本機課表應用程式：支援多管道匯入、調課同步、柔光玻璃外觀、桌面小工具與手環伴侶**

[![Release](https://img.shields.io/github/v/release/vanvan-program/van-schedule?style=flat-square)](https://github.com/vanvan-program/van-schedule/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=flat-square)](#安裝與使用)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#技術棧與專案結構)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[下載最新版 APK](https://github.com/vanvan-program/van-schedule/releases/latest) · [完整更新日誌](CHANGELOG.md) · [手環端說明](wearable/README.md) · [SDK 相依說明](app/libs/README.md) · [使用與待驗證項](docs/使用与待验证项.md) · [問題回報](https://github.com/vanvan-program/van-schedule/issues)

</div>

---

## 專案定位

**van課表**是一款以 Kotlin 與 Jetpack Compose 打造的原生 Android 本機課表應用程式（另附實驗性 **Xiaomi Vela 手環伴侶端**）。堅持本機優先、無廣告、無帳號、無第三方分析後台，涵蓋教務系統匯入、日常查課、調課同步、課前提醒與桌面小工具等完整體驗。

> [!TIP]
> 0.1.x ～ 0.5.21 的完整版本更新紀錄與驗證細節已整理至 **[CHANGELOG.md](CHANGELOG.md)**。

## 核心功能

- **多管道本機匯入與更新**：
  - 支援自行輸入教務系統網址匯入（不內建真實學校清單，預設清除登入工作階段）。
  - 支援 **PDF、Excel（`.xlsx`/`.xls`）、課表圖片（內建離線 Tesseract 中英文 OCR）**、WakeUp 五行備份與七欄 CSV 本機匯入；重新匯入時可預覽新增/取消/異動課程，並保留相符的備註與調課紀錄。
- **週課表 / 日課表雙檢視與流暢操作**：
  - 頂部列支援一鍵切換週/日檢視、快速切換多份課表與回到本週/今日；底部支援週次進度條預覽跳轉。
  - 支援長按跨週拖曳調課（衝突覆蓋/互換）、緊湊/空檔時間軸自動切換、單次取消/移動/調休、整日假期與課程備註彙總。
- **高度自訂的外觀與材質**：
  - 整合 `:softglass` 材質模組，支援柔光玻璃 / 清透磨砂 / 無材質、自訂背景桌布、AGSL 漸進模糊頂欄、柔和/實色卡片風格與 Android 12+ 莫內動態取色。
  - 外觀設定劃分為主題與桌布、材質與卡片、字體與網格、檢視與動效四個子頁面，並提供即時預覽。
- **桌面小工具與課前提醒**：
  - 提供原生捲動雙日/今日小工具與「下一節」卡片，支援各實例獨立綁定課表與獨立主題/莫內取色。
  - 支援課前提醒與選用課程狀態通知；調課、更換教室或撤銷補課皆會即時同步至小工具與通知。
- **Xiaomi Vela 手環伴侶端**（實驗性）：支援同步 14 天課程至手環並於離線時讀取快取，詳見 [wearable/README.md](wearable/README.md)。
- **資料備份與應用程式內更新**：
  - 支援完整備份還原（覆寫前自動建立快照）、單表資料與週課表圖片分享。
  - 支援於「設定 → 關於版本」檢查 GitHub Releases 更新、斷點續傳下載與多鏡像來源自動切換，安裝前嚴格核對套件名稱、版本、簽章與 SHA-256 雜湊值。

## 安裝與使用

1. 前往 [GitHub Releases 最新版](https://github.com/vanvan-program/van-schedule/releases/latest) 下載 `van-schedule-<版本>-arm64.apk`（附原始碼壓縮包與 SHA-256 校驗檔）。
2. 支援 **Android 8.0 及以上（API 26+）**，可直接覆蓋同簽章舊版保留所有課表與設定。
3. 初次使用可從課表頁右上角選擇「匯入」或手動建立課表，並依需求授予通知與精確鬧鐘權限。

## 權限與隱私邊界

- 課程資料儲存於本機 **Room** 資料庫，偏好設定使用 **DataStore**；**不參與 Android 自動雲端備份**，無廣告亦無第三方分析 SDK。
- PDF、Excel 與圖片 OCR 均於**本機離線執行**，不上傳任何課表檔案或辨識結果；匯出備份不含登入 Cookie、帳號密碼或系統授權。
- 宣告權限：`INTERNET`（用於教務網頁匯入與手動檢查更新）、`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`。檔案存取透過 SAF / FileProvider 完成，不需廣泛儲存空間、相機、位置或藍牙權限。

## 技術棧與專案結構

- **技術棧**：Kotlin `2.2.10` · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room · DataStore · Kotlinx Serialization · Coroutines · Tesseract4Android `4.9.0` · JExcelAPI `2.6.12` · AGP `9.1.1` · Gradle `9.3.1`
- **多模組結構**：
  - `:app` — Android 主應用程式（介面、小工具、通知、匯入與更新）
  - `:core:domain` / `:core:data` — 領域模型、排課/時間軸演算法與資料庫層
  - `:softglass` — 柔光玻璃與光學材質模組（Apache-2.0）
  - `wearable/` — Xiaomi Vela 手環伴侶應用程式

## 從原始碼建置

> [!IMPORTANT]
> 公開原始碼儲存庫**不包含**官方通訊 SDK 原始 AAR 與發布簽章金鑰。建置前請先依 [app/libs/README.md](app/libs/README.md) 取得通訊 SDK。

- **環境需求**：JDK `21`（產物 JVM 17）· Gradle `9.3.1` · Android SDK `37.0` · Build Tools `36.0.0`

```powershell
# 設定 local.properties（SDK 路徑）後執行：
.\gradlew.bat :core:domain:test :app:assembleDebug :app:lintDebug
# 或使用本機腳本：
./build-local.ps1
```

- 預設發布建置 `./gradlew :app:assembleRelease` 啟用 R8 與資源裁切並預設僅打包 `arm64-v8a`；需多架構可指定 `-PvanReleaseAbis=armeabi-v7a,arm64-v8a,x86,x86_64`。
- Debug 產物位於 `app/build/outputs/apk/debug/app-debug.apk`；原版覆蓋升級需本機私有 `signing.properties` 設定。

## 開源授權與致謝

- 主專案採用 **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)**。
- 儲存庫包含源自 NexioSchedule 的 **AGPL-3.0** 移植元件，以及採用 **Apache-2.0** 授權之 `:softglass` 材質模組。
- 完整第三方相依套件與授權聲明見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
