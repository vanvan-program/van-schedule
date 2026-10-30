# 发布包黑盒回归

该模块是独立测试器，不引用 `:app` 的类，不参与手机安装包打包。它携带自己的测试运行库，适用于 R8 裁剪后的真实发布包。

构建：`./gradlew :qa:release:assembleDebug`。测试器需与目标应用使用相同签名；本地构建沿用应用已有签名配置。

在专用模拟器上安装测试器后，以 `app.van.timetable.releaseqa/androidx.test.runner.AndroidJUnitRunner` 运行：

- 旧版安装后运行 `app.van.timetable.ReleaseSmokeTest#captureUpgradeState`，记录已有课表和设置摘要。
- 覆盖安装新版后运行 `app.van.timetable.ReleaseSmokeTest#optimizedUpgradeKeepsAllSchedulePayloadsAndSettingsAndWidgetProviders`。
- 运行 `app.van.timetable.ReleaseSmokeTest#optimizedApkRunsBundledChineseOcrThroughJni`，通过已安装 Release 中的 Java/JNI 桥接和内置字库识别合成中英文图片；完整导入编辑流程由应用界面回归覆盖。

旧版快照步骤要求模拟器已有合成课表。仅在专用测试设备执行，不对用户手机课表做自动替换。

`-PvanReleaseAbis=x86_64` 可构建供模拟器运行的发布包；手机交付使用默认 arm64 包。比较两者 DEX 哈希，可确认它们测试的是同一份裁剪代码。
