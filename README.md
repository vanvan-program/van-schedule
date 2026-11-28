<div align="center">

简体中文 | [繁體中文](README.zh-TW.md) | [English](README.en.md)

# van课表

**原生 Android 本地课表应用：支持多渠道导入、调课同步、柔光玻璃外观、桌面小组件与手环伴侣**

[![Release](https://img.shields.io/github/v/release/vanvan-program/van-schedule?style=flat-square)](https://github.com/vanvan-program/van-schedule/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=flat-square)](#安装与使用)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10%20%7C%20Compose%20%7C%20Room-7F52FF?style=flat-square)](#技术栈与项目结构)
[![License](https://img.shields.io/badge/License-GPL--3.0--only-blue?style=flat-square)](LICENSE)

[下载最新版 APK](https://github.com/vanvan-program/van-schedule/releases/latest) · [完整更新日志](CHANGELOG.md) · [手环端说明](wearable/README.md) · [SDK 依赖说明](app/libs/README.md) · [使用与待验证项](docs/使用与待验证项.md) · [问题反馈](https://github.com/vanvan-program/van-schedule/issues)

</div>

---

## 项目定位

**van课表**是一款基于 Kotlin 与 Jetpack Compose 构建的原生 Android 本地课表应用（附带实验性 **Xiaomi Vela 手环伴侣端**）。应用坚持本地优先、无广告、无账号与无第三方统计后台，覆盖从教务网页/文件导入到日常查课、调课、提醒与桌面小组件的完整使用体验。

> [!TIP]
> 0.1.x ～ 0.5.23 的完整版本更新记录与验证明细已整理至 **[CHANGELOG.md](CHANGELOG.md)**。

## 0.5.23 课表性能与提醒刷新优化

- 加快含自定义时间课程的周课表计算和连续翻周。首页「下一节」按课程时间更新，提醒与桌面小组件合并重复刷新。[更新日志](CHANGELOG.md)

## 核心功能

- **多渠道本地导入与更新**：
  - 支持自行输入教务系统网址导入（不内置真实学校名单，默认清理登录会话）。
  - 支持 **PDF、Excel（`.xlsx`/`.xls`）、课表图片（内置离线 Tesseract 中英文 OCR）**、WakeUp 五行备份与七列 CSV 本机导入；重新导入支持对比新增/取消/变更课程并保留匹配备注与调课。
- **周课表 / 日课表双视图与流畅交互**：
  - 顶栏支持一键切换周/日视图、快速切换多份课表与回到本周/今日；底部支持周次进度条预览跳转。
  - 支持长按跨周拖拽调课（冲突覆盖/互换）、紧凑/空档时间轴自动切换、单次取消/移动/调休、整日假期与课程备注汇总。
- **高度可定制的外观材质**：
  - 集成 `:softglass` 材质模块，支持柔光玻璃 / 清透磨砂 / 无材质、自定义背景壁纸、AGSL 渐进模糊顶栏、卡片柔和/实色风格与 Android 12+ 莫奈动态取色。
  - 外观设置细分为主题与壁纸、材质与卡片、字体与网格、视图与动效四个三级页，并带实时预览。
- **桌面小组件与课前提醒**：
  - 提供原生滚动双日/今日小组件与「下一节」卡片，支持实例独立绑定课表与独立主题/莫奈取色。
  - 支持课前提醒与可选课程状态通知；调课、改教室或撤销补课会实时同步到小组件与已发通知。
- **Xiaomi Vela 手环伴侣端**（实验性）：支持向手环同步 14 天课程并在断连时读取缓存，详见 [wearable/README.md](wearable/README.md)。
- **数据备份与应用内更新**：
  - 支持完整备份恢复（覆盖前自动快照）、单表数据与周课表图片分享。
  - 支持在「设置 → 关于版本」检查 GitHub Releases 更新、应用内断点续传下载与多源镜像自动切换，安装前严格校验包名、版本、签名与 SHA-256 摘要。

## 安装与使用

1. 前往 [GitHub Releases 最新版](https://github.com/vanvan-program/van-schedule/releases/latest) 下载 `van-schedule-<版本>-arm64.apk`（附源码包与 SHA-256 校验文件）。
2. 支持 **Android 8.0 及以上（API 26+）**，可直接覆盖同签名旧版保留全部课表与设置。
3. 首次使用建议在课表页右上角选择「导入」或手动新建课表，并按需在设置中授予通知与精确闹钟权限以启用课前提醒与小组件准时刷新。

## 权限与隐私边界

- 课程数据保存于本机 **Room** 数据库，偏好设置使用 **DataStore**；**不参与 Android 自动云备份**，无广告、无分析 SDK。
- PDF、Excel、图片 OCR 均在**本机离线完成**，不上传任何课表文件或识别结果；导出备份不含登录 Cookie、账号密码或系统授权。
- 显式声明权限：`INTERNET`（用于教务网页导入与手动检查更新）、`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`。文件读写通过 SAF / FileProvider 完成，无需广泛存储、相机、位置或蓝牙权限。

## 技术栈与项目结构

- **技术栈**：Kotlin `2.2.10` · Jetpack Compose (BOM `2026.03.00`) · Material 3 · Room · DataStore · Kotlinx Serialization · Coroutines · Tesseract4Android `4.9.0` · JExcelAPI `2.6.12` · AGP `9.1.1` · Gradle `9.3.1`
- **多模块结构**：
  - `:app` — Android 主应用（界面、小组件、通知、导入与更新）
  - `:core:domain` / `:core:data` — 领域模型、排课/时间轴算法与持久化层
  - `:softglass` — 柔光玻璃与光学材质模块（Apache-2.0）
  - `wearable/` — Xiaomi Vela 手环伴侣应用

## 从源码构建

> [!IMPORTANT]
> 公开源码仓库**不包含**官方通信 SDK 原始 AAR 与发布签名密钥。从源码构建前，请先按 [app/libs/README.md](app/libs/README.md) 获取通信 SDK。

- **环境要求**：JDK `21`（产物 JVM 17）· Gradle `9.3.1` · Android SDK `37.0` · Build Tools `36.0.0`

```powershell
# 配置 local.properties（SDK 路径）后运行：
.\gradlew.bat :core:domain:test :app:assembleDebug :app:lintDebug
# 或使用本机脚本：
./build-local.ps1
```

- 默认发布构建 `./gradlew :app:assembleRelease` 启用 R8 与资源裁剪并默认仅打包 `arm64-v8a`；需要多架构可附加 `-PvanReleaseAbis=armeabi-v7a,arm64-v8a,x86,x86_64`。
- Debug 产物位于 `app/build/outputs/apk/debug/app-debug.apk`；原版覆盖升级需本机私有 `signing.properties` 配置。

## 开源协议与致谢

- 主项目采用 **[GNU General Public License v3.0 (GPL-3.0-only)](LICENSE)**。
- 仓库内包含源自 NexioSchedule 的 **AGPL-3.0** 移植组件，以及采用 **Apache-2.0** 协议的 `:softglass` 材质模块。
- 完整第三方依赖与许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
