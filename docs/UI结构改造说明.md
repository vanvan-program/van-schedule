# UI 结构改造说明（对照 NexioSchedule）

日期：2026-11-16。版本与包名不变（仍为 0.4.0 / `versionCode 40`），本次只重排 `app` 模块的 UI 层：
不改功能、不改数据库、不改 `testTag`，目的是把「上帝文件 + 字符串路由」换成按职责切分的目录结构。

参考对象：`06_项目实验试用/NexioSchedule`（AGPL-3.0，`ui-shared` 的 `navigation/`、`activities/`、`screens/`、`components/`、`effects/` 分层）。

---

## 1. 为什么改

先按 `竞品功能清单-Nexio-已勾选.md`（工作区 `D:\ai\项目\` 根目录，未随仓库分发）逐项核对过一遍，结论是**功能差距不是主要问题**：

| 核对结果 | 数量 | 说明 |
| --- | --- | --- |
| 已实现 | 34 | 含周视图、跨周拖拽、Squircle、莫奈、渐进模糊、预测性返回、小部件独立绑定课表等 |
| 部分实现 | 1 | 105「卡片与镂空洞共用轮廓」的专门视觉未确认 |
| 未实现 | 1 | 21「午休／晚休分界线」——原清单已注明「完全不需要」，代码里本来也没有 |

真正的问题在 UI 层结构：

| 问题 | 位置（0.4.0） |
| --- | --- |
| 一个 composable 同时管导航栈、4 个 Activity 结果回调、文件 IO、备份导出、分享、小组件说明与全部屏幕分发 | `VanApp.kt` 725 行，`VanApp()` 单函数 58–569 |
| 单个 `Timetable` composable 670 行：周/日双 Pager、锚点同步、模式切换、拖拽状态机、冲突弹窗、课表下拉、周次弹窗全内联 | `MainScreens.kt:81-752` |
| route 是字符串，参数靠 `route.split(':')` 手写下标 | `VanApp.kt:470,478,483,499` |
| 分发清单与标题清单是两套 `when`，容易漂移 | `VanApp.kt:382-542` 与 `571-599` |
| 文件级职责混杂：分享页与小组件说明塞在导航文件末尾 | `VanApp.kt:610-731` |

---

## 2. NexioSchedule 值得抄的三条结构决策

读 `ui-shared/src/commonMain/kotlin/com/haooz/chedule/ui/` 后的结论（与是否 KMP、是否玻璃材质无关）：

1. **主界面不进路由**：`AppRouter` 只是一个「底座之上的显式栈」，`hasOverlay` / `popBack(): Boolean` 明确返回键归属（`navigation/AppRouter.kt`）。
2. **转场收敛成单一进度值**：push/pop 共用同一公式，于是打断、跟手、预测性返回全都免费（`navigation/AppNavHost.kt`、`NavTransitionState.kt`）。
3. **页面状态集中在路由层**：`AppRoutes.kt` 持有跨页状态，Screen 只吃回调；重组面靠「非快照普通字段 + `derivedStateOf` + layout/draw 期读动画值」压住。

本次采用第 1、3 条的思想（类型化目的地 + 分发独立成文件 + 屏幕无副作用），**没有**重写转场：现有 `AnimatedContent` + `PredictiveBackHandler` 已满足需求，替换成「整栈常驻 + 单一进度」属于高风险低收益改动。

---

## 3. 改造后的结构

```
app/src/main/kotlin/app/van/timetable/ui/
├── VanApp.kt                474 行  外壳：主题/壁纸/玻璃/顶栏底栏/返回栈/结果回调
├── navigation/
│   ├── VanRoute.kt          201 行  密封目的地 + 解析 + 标题（唯一来源）
│   └── VanNavHost.kt        181 行  分发层：一行一个目的地，穷尽 when
├── timetable/
│   ├── TimetableScreen.kt   294 行  课表页：整页布局与拖拽状态机
│   ├── TimetablePagerState.kt 230 行  周/日双模式的 Pager 与选中状态持有者
│   ├── TimetablePaging.kt    48 行  页面换算纯函数（周↔页、日期↔页、边缘翻页判定）
│   ├── TimetableHeader.kt   177 行  顶栏 + 课表下拉 + 更多菜单
│   ├── TimetableChrome.kt   288 行  下一节提示、周备注条、回到本周、拖拽提示与卡片副本
│   ├── TimetableDialogs.kt   94 行  周次直跳、冲突互换/覆盖
│   ├── GlassDropdown.kt     127 行  玻璃下拉面板与条目（两处复用）
│   ├── WeekGrid.kt          476 行  周网格、拖拽落点计算、卡片
│   ├── TimetablePager.kt    127 行  分页容器、手势归属守卫
│   └── DayListScreen.kt      93 行  日列表（含上午/下午/晚上分组）
├── CourseDetailScreen.kt    263 行  课程详情（含关联课程分区）
├── CourseEditorScreen.kt    305 行  课程新建／编辑，含时段表单 SlotForm 与周次选择
├── ScheduleEditorScreen.kt   75 行  学期设置（新建与编辑共用）
├── AdjustmentScreens.kt     256 行  调课管理、单次调课与星期／节次输入
├── MineScreen.kt             68 行  「我的」设置分组入口
├── ScheduleListScreen.kt    124 行  课表管理
├── AppearanceScreen.kt      324 行  外观设置（材质、配色、壁纸、卡片参数）
├── SettingsInfoScreens.kt   164 行  提醒与手环、帮助与关于
├── MainScreens.kt           125 行  只剩 Welcome 与 Courses
├── BackupScreen.kt           35 行  备份与恢复
├── ShareScreen.kt            51 行  分享课表
├── WidgetGalleryScreen.kt   110 行  桌面小组件说明
```

| 文件 | 改造前 | 改造后 |
| --- | --- | --- |
| `VanApp.kt` | 725 行（含导航、IO、分享、小组件） | 474 行（只做外壳） |
| `MainScreens.kt` | 1020 行 | 125 行 |
| `Timetable` 单函数 | 670 行 | 294 行（其余拆到 7 个文件） |
| `EditorScreens.kt` | 844 行 | 拆成 4 个文件，最长 305 行 |
| `SettingsScreens.kt` | 575 行 | 拆成 4 个文件，最长 324 行 |
| 路由定义 | 字符串，参数手写解析 | 密封类型，解析集中在 `vanRouteOf` |
| 屏幕分发 | 与标题表两套 `when` | `VanNavHost` 一处穷尽 `when`，标题随目的地 |

### 3.1 第二轮：状态持有者与大文件拆分

`Timetable` 拆完后仍有 383 行，其中一半是两个 Pager 与「切模式该跳到哪一页」的换算。第二轮继续按 Nexio 的
`PageSwitchState` 思路处理：

- **`TimetablePaging`（48 行，纯函数）**：周次↔页面、日期↔日分页、某个星期几在指定周的日期、边缘翻页判定。
  不引用 Compose 与课表数据，`TimetablePagingTest` 的 12 项 JVM 测试覆盖首尾钳制、跨月、单周课表与零宽视口。
- **`TimetablePagerState`（230 行，`@Stable`）**：持有两个 Pager 与选中状态，把「切日列表 / 前后一天 / 指定周 / 回到今天 / 模式切换 / 拖拽边缘翻页」翻译成目标页，
  并且每次操作都取消上一次翻页任务（原先是内联的 `navigationJob`）。
  保存键沿用 0.4.0：`daily`、`selectedWeekday(scheduleId)`、`weekSelectedDate(scheduleId)`、`dayAnchor(scheduleId)`。
- **`TimetableScreen`（294 行）**：只剩整页布局与拖拽状态机，不再出现 `weekPager.currentPage` 之类的换算。

编辑与设置两个文件按屏幕切开，**包路径保持 `app.van.timetable.ui` 不变**，因此导航宿主、androidTest 与全部调用点零改动；
切分用脚本按行区间搬运，代码行与原文件逐行一致（811 行 / 524 行，已比对）。

---

## 4. 关键决策

### 4.1 `VanRoute.key` 与旧字符串完全一致

`VanRoute.key` 刻意复刻 0.4.0 的字符串 route（`detail:<课程>:<日期>:<时段>:<原定日期>`、`edit:<课程>:<一次性 id>` 等）。
原因：`rememberSaveable` 与 `SaveableStateHolder` 的键都由它构成，升级后用户停留的周次、编辑器草稿、滚动位置必须还能对上。

### 4.2 `nav(String)` 签名不变，解析在边界完成

50 多处 `nav("appearance")` / `nav("edit:${c.id}")` 调用点一行未改：`VanApp.nav()` 先按旧规则补一次性 id（`normalizeRoute`），再交给 `vanRouteOf` 解析成类型。
这样「字符串 → 类型」只有一处，调用点不需要知道路由模型的内部结构。

### 4.3 屏幕不再有副作用

`VanNavHostEnv` 显式传入 `state` / `vm` / `now` / 导入草稿 / 以及 5 个回调（选文件、教务入口、导出备份、恢复快照、导入落地）。
文件读写、`ActivityResultLauncher`、`Stack.clear()` 全部留在 `VanApp`。屏幕只发意图。

### 4.4 旧路径已清理

改造期间保留过 5 个过渡文件，0.4.2 发布后已全部删除：

| 已删除 | 原有作用 | 删除时的处理 |
| --- | --- | --- |
| `ui/TimetableEntry.kt` | `ui.Timetable` 转发到 `ui.timetable.Timetable` | `VanNavHost` 与 `Version023UiTest` 改为直接 import `app.van.timetable.ui.timetable.Timetable` |
| `ui/WeekGrid.kt` | 迁移说明（实现已在 `ui/timetable/WeekGrid.kt`） | 无声明、无引用，直接删除 |
| `ui/TimetablePager.kt` | 迁移说明（实现已在 `ui/timetable/TimetablePager.kt`） | 同上 |
| `ui/EditorScreens.kt` | 迁移索引（指向 4 个课程页面文件） | 同上 |
| `ui/SettingsScreens.kt` | 迁移索引（指向 4 个设置页面文件） | 同上 |

现在的 `ui/timetable/` 与 `ui/` 下都是真实实现，没有转发层；包路径仍是 `app.van.timetable.ui`，只有跨包调用（导航宿主与 instrumentation 测试）需要显式 import。

---

## 5. 顺手修的翻页性能问题（清单 01 的批注）

原批注：「周视图有了，只是动画不够流畅」。定位到 `WeekGrid` 的卡片修饰链：

```kotlin
// 改造前（每张卡都挂一层离屏图层，一屏 20+ 张）
.graphicsLayer { alpha = if (isBeingDragged) 0.25f else 1f }
```

改为只在被拖走的那张卡上挂层：

```kotlin
.then(if (isBeingDragged) Modifier.graphicsLayer { alpha = 0.25f } else Modifier)
```

理由：`graphicsLayer` 会为每个节点建立独立合成层；翻周时三页（当前 ±1）同时参与绘制，图层数量直接抬高每帧合成成本。静态卡片不需要透明度动画，删除后不影响观感。

`beyondViewportPageCount` 保持 1，并在注释里写明原因：相邻页是完整网格（含玻璃卡片），再往下预组合会变成翻页起手时的组合尖峰。

---

## 6. 行为不变清单

| 项 | 处理 |
| --- | --- |
| 返回栈语义 | `stack` 仍存 `route.key`，`back()` 空栈回首页 |
| 编辑器草稿隔离 | `edit:` / `quick:` 每次进入追加 `newId()`，与 0.4.0 一致 |
| 编辑器退出清状态 | `back()` 时对 `edit:/quick:` 调用 `removeState("<route.key>:<tab>:<scheduleId>:")` |
| 深链与小组件跳转 | 仍走 `nav("detail:<课程>[:<日期>]")`，`LaunchedEffect` 逻辑未改 |
| 导入成功后的落点 | `onImportApplied`：清文档草稿、清栈、直接切到「课表管理」 |
| 「还没有课表可备份」提示 | 由 `vm.messages` 弹出，文案与位置不变 |
| 顶栏标题 | 由 `VanRoute.title` 给出，逐条与旧 `title()` 对照 |
| 全部 `testTag` | `week-selector` / `next-class` / `week-notes` / `jump-today` / `week-grid-*` / `grid-course-*` / `day-list` 等保持原值 |

---

## 7. 验证

```powershell
cd .\van-timetable
.\build-local.ps1 -Tasks ':app:compileDebugKotlin'
```

本机实际执行（复用 `01_应用开发\.tools` 的 Gradle 9.3.1 与离线缓存）：

| 任务 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | 通过 |
| `:core:domain:test` | 通过（16 个测试类 / 104 项，无失败） |
| `:app:testDebugUnitTest` | 通过（5 个测试类 / 27 项，无失败；含 `TimetablePagingTest` 12 项与 `VanRouteTest` 5 项） |
| `:app:compileDebugAndroidTestKotlin` | 通过（31 个 instrumentation 类只编译不执行） |
| `:app:lintDebug` | 0 错误 / 87 警告 / 1 提示（两轮改造前后同基线） |
| 拆分保真 | 新文件去掉头部后按序拼接，与原文件代码行逐行一致（编辑器 811 行、设置 524 行） |

**未验证**：instrumentation 测试（`app/src/androidTest`，31 个类）只做了编译检查，没有在设备或模拟器上执行；真机上的转场、拖拽与玻璃表现需要人工过一遍。

---

## 8. 后续建议

1. ~~把 `TimetableScreen` 里的 Pager 状态抽成 `TimetablePagerState` 持有者~~ —— 已做（见 §3.1，381 → 294 行，换算逻辑由 12 项 JVM 测试覆盖）。
2. ~~拆分 `EditorScreens.kt`（844 行）与 `SettingsScreens.kt`（575 行）~~ —— 已做（同包拆成 4 + 4 个文件，调用点零改动）。
3. 把 `nav("...")` 调用点统一切到 `VanRoute` 常量（或 `VanRoute.CourseEdit(id)` 构造函数），这样 `vanRouteOf` 的旧字符串兼容层才能收掉。（过渡文件已在 §4.4 清理完毕。）
4. 转场若要继续对齐 Nexio，方向是「整栈常驻 + 单一进度 + `Modifier.layout` 视差」，而不是给 `AnimatedContent` 加更多动画参数。
5. `AppearanceScreen.kt`（324 行）仍是最大的单屏，内部按「材质／配色／壁纸／卡片」再拆成 4 个面板是下一步；它的状态都在 `Preferences` 上，拆分不需要动数据流。