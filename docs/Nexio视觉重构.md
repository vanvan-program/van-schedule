# 按 Nexio 重构课表 UI

参照对象是 NexioSchedule 的主课表屏（`docs/picture/主课程表.png`）与它的实现：
`ui-shared/src/commonMain/.../classschedule/ScheduleTopBar.kt`、`MainScheduleScreen.kt`（`WeekGridPage`、`BreakDivider`）、
`ui-shared/src/portable/.../classschedule/SectionColumn.kt`、`CourseCard.kt`、`ui/theme/ScheduleColors.kt`。

Nexio 的整体观感是「近白底 + 极浅的色块卡 + 同色系文字 + 大量留白」：没有卡片描边和投影，
没有实心色块，没有网格线；课表结构靠字号和留白区分，而不是靠线。这一版把 van课表 的周课表页对齐到这套视觉。

## 1. 规格对照

| 元素 | Nexio | van课表 改造前 | van课表 现在 |
| --- | --- | --- | --- |
| 页面底色 | Miuix `background`（近白） | `#F8F9FE` | 不变 |
| 顶栏 | 居中「第9周」约 20sp，右侧 2 个图标，无课表名 | 左侧「第N周 ▾」（17sp 粗）+ 课表名胶囊 + 3 个图标 | 居中「第N周」20sp SemiBold（点开周次直跳）+ 右侧 3 个图标；课表切换收进「更多」菜单 |
| 星期表头 | `周一` 15sp + `10/26` 12sp，两行居中，今日加粗并换主题色，整行 40dp | 12sp 周几 + 16sp 大号日号 + 左上角「10月」 | `周一` 15sp + `10/26` 12sp，两行居中，今日加粗 + 主题色，40dp |
| 时间列 | 固定 36dp，节次号居中，起止时间 10sp，当前节次整列换主题色 | 右对齐，节次号 11sp、时间 9sp，当前节次蓝色底胶囊 | 36dp 居中，节次号 14sp、时间 10sp，当前节次改蓝色文字（去掉胶囊底色） |
| 节次行高 | 54dp | 56dp（可调） | 保持 56dp 默认，沿用现有设置 |
| 午休 / 晚休 | 24dp 高、圆角 10dp、`#F0F0F0` 通栏，文字 footnote2 灰 | 无，只有被压扁的空档 | 24dp 圆角 10dp 通栏，只在「前有课、后有课」的空档出现，标「午休 / 晚休」 |
| 课程卡底色 | 课程色 12% 铺在页面底色上（浅色主题） | 课程色 78% 实色（`cardOpacity`） | 柔和风格：课程色 18% 铺底；实色风格保留原样 |
| 课程卡文字 | 就是课程色本身（同色系），不换黑/白 | 按亮度自动选深/浅对比色 | 保色相推导出的同色系文字，强制 ≥ 4.6:1 |
| 课程卡圆角 | `ContinuousRoundedRectangle(10.dp)` | 10dp（可调） | 不变，仍可调 |
| 课程卡内边距 | 卡外 2dp、卡内 4dp/6dp | 2dp / 2dp | 2dp / 2dp |
| 课名 | 12.7sp，行高 14.2sp，Bold，居中，按卡高算行数 | 12sp，居中，按卡高算行数 | 12.7sp，行高 14.18sp，其余算法不变 |
| 地点 / 教师 | 11sp，同色文字，地点带 `@` | 9sp，`place` 无 `@`，教师默认关 | 11sp，`@地点`，教师仍按设置显示 |
| 卡片间距 | 相邻卡 4dp（各留 2dp） | 4dp | 不变 |
| 网格线 | 默认不画 | 不画 | 不画 |
| 底部栏 | 悬浮胶囊（今日 / 课程表）+ 独立圆形按钮 | 现有底部栏 | **本轮未改**，见「还没做的」 |

## 2. 同色系文字怎么来的

色板本身是浅色填充（例如 `#91DEB2`），直接当文字色对比度只有 2:1 左右；换成品红、藏青之类固定文字色又会丢掉
「一门课一个颜色」的辨识度。所以改成「只动饱和度与明度、不动色相」：

```
CoursePalette.pastelTextArgb(fill, cardArgb, isDark)
  h, s = HSV(fill)
  s'  = isDark ? max(s*0.85, 0.45) : max(s*1.7, 0.62)
  v   = isDark ? 0.92 起，按 +0.03 提亮 : 0.52 起，按 -0.03 压暗
  直到 contrast(文字, cardArgb) ≥ 4.6，最多 20 步
```

`cardArgb` 是卡片真正的表面色（课程色按 18% 压在页面底色上的结果），所以深色主题也能算出正确方向。
由 `PastelCardTest` 的 7 项测试保证：12 个色板 × 明暗两套主题的对比度都 ≥ 4.5、色相漂移 < 2°、饱和度不塌成灰。

## 3. 新增的外观设置

外观设置 → 「课程卡片效果与排版」最上面多了 **卡片风格：柔和 / 实色**，默认「柔和」。

- **柔和**：本文件描述的新观感（浅底 + 同色系文字，忽略「卡片不透明度」）。
- **实色**：0.4.x 的观感（`cardOpacity` 决定不透明度，文字按亮度自动对比，玻璃材质照旧生效）。

这样老用户不会被强制换风格，新装用户默认就是 Nexio 那套。

## 4. 顺手对齐的其它位置

- 日列表的课程行（`CourseRow`）改成同一套浅底 + 同色系文字，地点同样带 `@`，圆角 14dp。
- 顶栏「更多」菜单新增「切换课表…」，原来点课表名胶囊的动作移到菜单里。
- `VanColors` 增加 `breakBand`（浅色 `#F0F0F0`、深色 `#323232`，莫奈档取 `surfaceContainerHigh`），供午休/晚休横带使用。

## 5. 还没做的

| 项 | 说明 |
| --- | --- |
| 底部悬浮胶囊导航 | Nexio 的「今日 / 课程表 + 圆形按钮」形态，涉及 `VanApp` 的整页骨架与返回栈，单列一轮 |
| 顶栏滚动收缩 | Nexio 用 `CollapsibleTopAppBar` + 渐进模糊，滚动时标题收拢 |
| 天气徽标 | Nexio 顶栏左侧的天气胶囊，van课表 没有天气数据源 |
| 玻璃材质联调 | 柔和风格刻意不走玻璃；壁纸 + 材质档下仍是实色风格的玻璃卡 |
| 真实观感核对 | **没有设备**：本机 `adb devices` 为空、没有 AVD，改动只做了编译级验证 |

## 6. 验证

| 项 | 结果 |
| --- | --- |
| `:core:domain:test` | 111 项通过（含新增 `PastelCardTest` 7 项） |
| `:app:testDebugUnitTest` | 27 项通过 |
| `:app:compileDebugKotlin` | 通过 |
| `:app:lintDebug` | 0 错误 |
| 真机 / 截图 | 未验证——没有连接设备与模拟器 |

改动文件：`WeekGrid.kt`、`TimetableHeader.kt`、`DayListScreen.kt`、`Components.kt`、`Theme.kt`、`AppearanceScreen.kt`、
`core/domain/{Models,GridTimeline,CoursePalette}.kt`，新增 `core/domain` 测试 `PastelCardTest.kt`。