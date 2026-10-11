# Android 频道式 UI 实施与验证记录

**Goal:** 按 2026-10-10 安卓 UI 规范及效果图实现 P0、P1 最近浏览/主题/媒体标签与查看器降级动画。

**Architecture:** 保留单播放器 Surface、三个媒体/子目录页窗口、原缓存预算与连接策略；展示按 feature 拆分，公共视觉组件放 ui/component。日期与布局计算后台执行，列表通过稳定 key 恢复。

**Tech Stack:** 使用当前 Kotlin 2.2.21、Compose BOM 2025.12.01、Media3 1.8.1、Room 2.8.4，不增改依赖。

用户已授权连续实现、真机调试和修复，不额外创建会话或 Git 仓库。

## 顺序与检查点

1. 主题：`ui/theme/VVideoTheme.kt`、SettingsRepository、MainActivity、VVideoRoot、styles.xml。完整阶段命令已通过。
2. 通用组件：LetterAvatar、VVButton、Bulletin、OfflineBanner、EmptyState、VVBottomSheet；头像算法测试。
3. 首页/文件夹：HomeScreen、FolderScreen、AlbumBubble、FolderSheets、TimeLabels、LibraryViewModel；日期分隔与 key/锚点测试；真机滚动自查。
4. 播放/照片：PlayerChrome、SeekBar、GestureLayer、MediaDetails；保留会话生命周期并验证翻页、速度、手势与缩放。
5. 最近浏览：Bookmarks v2 迁移、schema 导出、recent/hide，滚动只更新锚点字段。
6. 设置：SettingsScreen、StorageScreen、ConnectionForm，接入 storage/connection 返回链。
7. 资源与收尾：字符串、图标、动画、构建、真机检查与文档同步。

每阶段执行：

```sh
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :core:media:lintDebug :app:assembleDebug
```

最终执行：

```sh
android/gradlew -p android :app:assembleProfile :benchmark:assembleProfile
```

当前环境通过显式 JAVA_HOME 使用项目已有 JDK 21。阶段日志临时存于 `/tmp/vvideo-stageN.log`。

## 已核实的差异与限制

- 当前无 Git 仓库，不能运行基于真实 Git 范围的 OCR；不为此初始化仓库。
- 旧计划列出的 4 个 lint 错误在开工时的代码已修复（API 34 守卫、测试 ViewModel/Flow 移到 setContent 外）。阶段 1 完整 lint 成功；无需重复修复。
- 当前依赖没有 room-testing；不新增依赖。迁移通过平台 SQLite/Room 设备测试验证。
- 设备 ADB 已授权在线；编译通过不等于真机性能达标。


## 真机发现与修复

- 筛选切换前允许完成原“全部”视口的 flush；进入筛选后显式 save/flush 不改变锚点。DAO 访问字段与滚动字段分开写，隐藏记录不会被延迟滚动写回重新显示。
- 首页空态不遮住已经存在的最近浏览；相册异步计算完成后再读取最新 state，避免覆盖同期 Room recent 更新。
- 播放菜单展开时暂停 3 秒自动隐藏，关闭后恢复计时。播放器手势回归验证同一 session 上的速度循环、长按恢复、双击、横屏和正反翻页。
- 首项相同、组内数量改变（名称排序下“全部”→“视频”）时，`produceState` 曾保留旧几何，产生 `Index 4 out of bounds for length 4`。R8 映射定位到 AlbumBubble 原第 55 行。修复为按完整组媒体身份隔离布局状态，并将筛选真机用例改用名称排序覆盖该条件。
- 三页窗口恢复测试原本以固定 65 次反向手势结束。新版布局下次数不够，改为有界最多 140 次、回到实际首项即停止；同样的起点、深层恢复和 ≤300 项断言不变，复测回到首页通过。
- 日期/时间格式共享于 `ui/component/TimeLabels.kt`，library 文件仅保留日期分隔算法；播放器、详情和最近浏览均在 Default 调度器计算。封面 Retry-After 日期解析亦移出主线程。

## 设备与环境

本轮连接 iQOO 12 Pro（设备型号 V2329A），Android 15。既有 SSH 别名不可解析，故仅使用已有本机合成媒体，不修改真实媒体库。原 Homebrew FFmpeg 缺少 libwebp，使用本机现有 ClipGrab 附带的 FFmpeg 验证并生成合成封面。私有运行配置与缓存位于 Android build 输出，手机回环端口通过 ADB reverse 指向本机 19092。没有修改服务端代码、接口、configs、deploy 或 Gradle 依赖。

首个 profile 的暖相册 3 轮均未过 1% 帧截止时间门槛，随后筛选触发上述崩溃，内存只采到 110 秒；该数据保留为失败诊断，不计入 30 分钟通过结果。更改后必须按新 APK 摘要重新测量。


一次 1.3× 播放测试尚未完成时误启动 APK 安装，安装界面夺取前台，测试超时且安装 helper 的 UI dump 返回 137。该轮不能用于播放器大字体验收；保留 `font130-regression.log`，后续必须等待 instrumentation 完整结束再安装。其首项筛选用例在安装前已独立完成。


## 交付文件范围（按阶段）

| 阶段 | 文件与实现 |
| --- | --- |
| 1 主题 | `ui/theme/VVideoTheme.kt`、`AnimationSettings.kt`、`data/settings/SettingsRepository.kt`、`app/MainActivity.kt`、`app/navigation/VVideoRoot.kt`、values/values-night styles：Day/Night、系统/浅/深持久化、实际主题系统栏、动画开关。 |
| 2 组件 | `ui/component/LetterAvatar.kt`、`VVComponents.kt`：头像、三种按钮、Bulletin、离线横幅、空态、底部面板。 |
| 3 首页与目录 | `LibraryScreen.kt` 作为 route；`HomeScreen.kt`、`FolderScreen.kt`、`MediaTabs.kt`、`AlbumBubble.kt`、`FolderSheets.kt`、`TimeLabels.kt`、`LibraryKeys.kt`、`LibraryViewModel.kt`：固定子文件夹条、十媒体气泡、日期、稳定 key、筛选、排序、回顶、最近浏览。 |
| 4 查看器 | `PlayerScreen.kt`、`PlayerChrome.kt`、`SeekBar.kt`、`GestureLayer.kt`、`MediaDetails.kt`、`PlayerViewModel.kt`、Manifest：单 Surface、250ms 进度子树、tap/hold、倍速、横屏、详情、照片双击及共享淡入降级。 |
| 5 本地数据 | `data/library/local/Bookmarks.kt`、`app/AppGraph.kt`、`app/schemas/.../2.json`：四列与索引、v1→v2 迁移、recent/hide、分离访问与锚点更新。 |
| 6 设置 | `SettingsScreen.kt`、`SettingsViewModel.kt`、`StorageScreen.kt`、`ConnectionForm.kt` 与根路由：连接校验、分组、主题、用量环、三档预算、清理确认及结果。 |
| 7 资源 | `res/values/strings.xml`、线性 drawable 图标；旧计划已修复的 4 项 lint 不重复修改媒体引擎。 |
| 验证 | `LetterAvatarTest.kt`、`TimeLabelsTest.kt`、`TelegramUiDeviceTest.kt`、`BookmarkMigrationDeviceTest.kt`、已有 `DevicePlaybackTest.kt`/`PhotoDeviceTest.kt`/`UiRedesignDeviceTest.kt`；`android/benchmark/tools/profile-ui-workload.py` 适配新版导航；`measure-ui-frames.py` 添加前台保护；`clear-ui-cache.py` 验证可见清理确认；`LibraryScrollBenchmark.kt` 更新实际目录入口。 |
| 文档 | 中英文 README、android README、DESIGN 视觉引用、旧 UI 计划替代标记、本记录及 ACCEPTANCE。 |

## 实际命令与构建结果

所有 Gradle 命令使用当前工程 Wrapper、JDK 21；没有传 `-PlanHttpHost`，仅默认回环 HTTP。阶段命令在阶段 1/2/3/4/5/6/7 分别最终成功（46/12/8/10/11/8/8 秒）。阶段 3 中间编译及 lint 失败，经修复重跑成功，不建立 lint baseline、不压制错误。

```sh
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :core:media:lintDebug :app:assembleDebug
android/gradlew -p android :app:assembleProfile :benchmark:assembleProfile
android/gradlew -p android :app:assembleDebugAndroidTest
```

最后的布局修复执行了上述全部任务，`BUILD SUCCESSFUL in 54s`。新增边界测试再执行 `:app:testDebugUnitTest`，`BUILD SUCCESSFUL in 2s`。app 共 17 个、media 共 3 个 JVM 测试，0 失败。最终 app lint 0 error / 66 warning，media lint 0；警告主要为旧资源未使用、依赖更新提示及现有平台建议，遵守本次不升级依赖的边界。

```sh
python3 scripts/install-test-apk.py android/app/build/outputs/apk/debug/app-debug.apk --package dev.vvideo
python3 scripts/install-test-apk.py android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --package dev.vvideo.test
adb shell am instrument -w -r -e class dev.vvideo.TelegramUiDeviceTest,dev.vvideo.UiRedesignDeviceTest,dev.vvideo.BookmarkMigrationDeviceTest,dev.vvideo.PhotoDeviceTest dev.vvideo.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class 'dev.vvideo.DevicePlaybackTest#realUiVideoTransitions' dev.vvideo.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/install-test-apk.py android/app/build/outputs/apk/profile/app-profile.apk --package dev.vvideo
```

完整实际测试类组合、输出及中间失败日志保存在 `android/app/build/outputs/ui-redesign/`。这些设备测试安装在 Debug 构建；后续帧/内存采集独立使用 minified、non-debuggable profile。系统字号初值 1.0，测试 1.3 后恢复 1.0；不改全局高刷或省电策略。

## 新增测试与已完成行为检查

- 头像：中文首字、跳过前缀数字/符号、英文大小写、全数字、Unicode 和最小 Int 哈希。
- TimeLabels：6.2 表格各时间跨度、详情带秒、设备时区/负纳秒；日期跨天、同天、省略名称排序日期、前插重算、mtime 分支。
- key：日期/子目录条/相册组合、同相册内媒体锚点、缺失回退、前插后稳定 key、纯目录 key。
- Room 迁移：平台 SQLite 建 v1 后真实 Room v2 打开；保留锚点、默认列、访问字段不被滚动覆盖、隐藏不被滚动复活、connection 隔离。
- 真机 UI：筛选后媒体 kind 与书签、主题与存储返回；450 子目录前后分页 ≤300；照片原图缩放/详情/返回；深层目录恢复及返回起点。
- 单播放器：100 次快速切换、版本匹配、取消创建释放、route 退出前释放、真实 UI 正反翻页；速度 1→1.5→2→0.5→1、长按恢复原速度、双击暂停/快退、拖动与横竖屏后同一 session。
- 1.3×：名称排序筛选回归通过；播放器完整用例串行重跑 `OK (1 test)`（61.776 秒）。设置/存储截图已检查未见裁切。未把有限截图等同所有字号、所有长文本通过。


目录页补齐 `AnimatedContent`：以目录 ID 为 contentKey，进出各 260/220ms，旧页保留自己的 LazyListState，只有当前页参与书签、分页和滚动状态；页面内部数据更新不触发目录过渡。PredictiveBackHandler 处理目录返回，面板/菜单优先关闭。查看器关闭降级改为 200ms 淡出/缩小，打开保持 180ms。系统动画关闭时导航即时切换。

第二次候选采样目录虽以 `final-*` 命名，仍属于 `ab30e8...` 候选 APK。补齐目录动效前主动中止其内存采样，未满 30 分钟，不能用于长时通过结论。后续交付测量使用新的 `delivery-*` 目录及单独 APK 摘要。


## 与规范的保守差异

- 子文件夹面板使用当前 Material3 的原生 partial/expanded anchors，初始约半屏，展开高度上限约 90%；未复制内部实现去硬编码 52% anchor、250ms 和 30% 拖动关闭阈值。圆角、拖柄、颜色、标题及有界数据共用已落地，精确吸附比例/速度不是逐参数复刻。
- 查看器按已授权的降级方案淡入 180ms + 0.92→1；关闭 200ms 淡出/缩小，未做共享元素飞回某一格。
- 日期格式移到真实跨功能复用的 `ui/component/TimeLabels.kt`，而非让 player 直接依赖 library 功能包；目录文件保留相册日期分隔。
- 顶部轻阴影/分隔细节与系统 Material3 控件的个别动态尺寸仍取原生表现；长文本截断和平台字体渲染不会与静态画板逐像素相同。
- 按请求隐藏 P2 网格入口、默认网格设置、总数/新增计数。没有为了视觉图展示虚构库统计。

## OCR 范围与限制

已读取本机 open-code-review 技能。其规则是 “`ocr review` operates on the Git repo at the current directory”。实际检查：

```text
fatal: not a git repository (or any of the parent directories): .git
```

退出码 128。没有可供 OCR 审查的真实 Git 差异范围，遵照用户任务及 AGENTS 的无 Git 例外不初始化仓库、不运行伪差异审查，也不声称 OCR 通过。技能位置仅作本次环境记录：[open-code-review SKILL.md](/Users/k/.codex/skills/open-code-review/SKILL.md)，不写入构建脚本。

## 中间失败原文（均保留原日志）

编译作用域错误，提取尺寸后修复：

```text
e: file:///Users/k/Documents/vvideo/android/app/src/main/kotlin/dev/vvideo/feature/library/AlbumBubble.kt:80:13 'val maxWidth: Dp' cannot be called in this context with an implicit receiver. Use an explicit receiver if necessary.
e: file:///Users/k/Documents/vvideo/android/app/src/main/kotlin/dev/vvideo/feature/library/AlbumBubble.kt:80:22 'val maxHeight: Dp' cannot be called in this context with an implicit receiver. Use an explicit receiver if necessary.
Execution failed for task ':app:compileDebugKotlin'.
BUILD FAILED in 3s
```

改用 LocalConfiguration.current 后修复：

```text
Execution failed for task ':app:lintDebug'.
> Lint found errors in the project; aborting build.
Lint found 1 error, 102 warnings. First failure:
/Users/k/Documents/vvideo/android/app/src/main/kotlin/dev/vvideo/app/navigation/VVideoRoot.kt:41: Error: Reading Configuration using LocalContext.current.resources.configuration [LocalContextConfigurationRead from androidx.compose.ui]
BUILD FAILED in 11s
```

早期行为测试的断言、菜单、固定次数超时与安装冲突记录：

```text
androidx.test.uiautomator.StaleObjectException
at dev.vvideo.DevicePlaybackTest$realUiVideoTransitions$1.invokeSuspend(DevicePlaybackTest.kt:184)
kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 10000 ms
FAILURES!!!
Tests run: 6,  Failures: 2
```

```text
subprocess.CalledProcessError: Command '['adb', 'shell', 'uiautomator', 'dump', '/sdcard/vvideo-installer-helper.xml']' returned non-zero exit status 137.
kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 15000 ms
FAILURES!!!
Tests run: 2,  Failures: 1
```

候选 profile 的真实崩溃：

```text
FATAL EXCEPTION: main
Process: dev.vvideo, PID: 17115
java.lang.IndexOutOfBoundsException: Index 4 out of bounds for length 4
```

运行夹具限制：

```text
Could not resolve hostname vvideo
Unknown encoder 'libwebp'
Error selecting an encoder
Encoder not found
```

后两项分别通过本机合成服务、现有支持 WebP 的 FFmpeg 继续验证，没有更改服务器源码或安装工具链。中断内存采样的进程消失、主动 KeyboardInterrupt，以及诊断 SQL 首次列名错误，均保留在 build 输出，不作为应用功能通过的证据。


## 用户真机手动复核清单

以下原样保留规范第 10 节清单，作为完整人工复核入口；自动用例覆盖不等于每项视觉体验已经逐一通过。


**视觉**（逐张对照画板，深浅两套）
- [ ] 首页置顶媒体库、最近浏览、离线库黄色副标题
- [ ] 文件夹顶栏头像/标题/路径，标签栏下划线，子文件夹条
- [ ] 相册外圆角 16、缝 2、时长左上、时间右下、播放钮阈值
- [ ] 日期胶囊与浮动日期；名称排序时不出现
- [ ] FAB 出现/隐藏条件
- [ ] 菜单、排序面板、子文件夹面板、详情面板样式一致（拖柄、圆角、标题）
- [ ] 播放器顶/底渐变、细进度条、倍速、横屏按钮、暂停大按钮、缓冲、失败态
- [ ] 设置分组、存储环形图、三档滑杆、首次连接表单错误位置
- [ ] 大字体（系统 1.3×）下没有文字被裁切，角标不换行

**行为**
- [ ] 返回链：面板 → 播放器 → 子目录 → 根目录 → 首页 → 退出
- [ ] 标签切换后分页、锚点、播放队列正确；回到“全部”恢复正常书签
- [ ] 双击 ±10 秒可累加；长按 2× 松手恢复；拖进度时不翻页；单击切换控件
- [ ] 横屏按钮只对横向视频出现，横屏时不能上下翻页
- [ ] 最近浏览：打开文件夹后出现在首页顶部；长按移除；升级安装后旧书签不崩溃（迁移）
- [ ] 主题切换立即生效，系统栏图标明暗正确
- [ ] 清除缓存确认、进行中、完成提示；播放中清理会先暂停

**性能**：按第 8 节在 profile 构建重测，结果写入 `docs/ACCEPTANCE.md`，未测项注明。


## 分页时序补充回归

最终目录回归曾出现深层恢复成功但反向到首页超时。带窗口诊断复跑成功（38.724 秒），说明不能只靠一次通过消除竞态。排查发现窗口公开之后，`open()` 仍在持久化访问记录；`page()/moreChildren()` 原先用整个 request Job 是否活跃拦截，会丢弃此时唯一的边缘可见事件。改为依据 `busy` 判断窗口是否可分页，不更改 PageWindow/ChildrenWindow 算法。新增设备用例故意挂起 `visit()`，验证媒体与子目录可以继续加载；测试保持前台，避免设备对后台进程的调度影响。

加大字体角标宽度计算同时覆盖组尾时间；播放器菜单在浅色模式下保持深色；面板标题统一为 18sp。最终全任务构建日志 `vvideo-pagination-ready.log`：`BUILD SUCCESSFUL in 51s`；追加 AndroidTest/Lint：`BUILD SUCCESSFUL in 5s`。设备回归结果以对应完整日志为准。

分页补充后的真机组合回归 `paging-fixed-regression.log`：`Time: 69.895`、`OK (4 tests)`，包含写入挂起分页、深层恢复/反向、450 子目录返回链、筛选主题存储。此前失败与诊断通过日志均保留。

Macrobenchmark 的 `LibraryScrollBenchmark.kt` 更新为新版首页 → 根目录 → 子文件夹面板 → large 的实际导航，缺控件即失败，避免旧脚本静默测错页面。`:benchmark:assembleProfile` 成功（913ms）；该修改仅影响测试 APK。最终 XML 再核对 app Lint 为 0 error / 66 warning（UnusedResources 34），media 为 0；以机器报告为准修正此前 67 的候选统计。

第一轮正式主机循环在 444.0 秒停止，错误原文：

```text
xml.etree.ElementTree.ParseError: syntax error: line 1, column 0
```

随后核实设备前台为其他应用，VVideo 进程仍在，未见其 crash/ANR/OOM；未捕获失败当刻原始 XML，不能把随后观察到的前台切换当作已证实的 XML 错误根因。该轮仅完成 4 个循环，不能作为连续 30 分钟验收。停止内存采样并保留部分数据。主机驱动新增 `require_foreground()`，每次 tap/swipe 前检查 resumed activity；切出即停止，不触控其他应用。修正脚本 py_compile 及真实 home 导航通过后启动独立第二轮，不改产品 APK。


最终原包（`6a9ef440...`）完成独立主机混合循环 18 轮、1844.0 秒（30.73 分钟），进程未变；内存 180 样本，前后窗口各 30 点，中位 168.645 / 171.651 MiB，增长 3.006 MiB，峰值 297.733 MiB，数值门槛通过。详细分项与限制见 ACCEPTANCE。独立暖相册三轮仍为 27.359% / 32.044% / 42.701% 超限，不能称流畅度达标。

帧采集新增 `android/benchmark/tools/measure-ui-frames.py`，仅包装既有 collector 并在触控前核对前台；不改门槛、公式或手势次数。同一原始 trace 重分析与原结果逐字段完全相等。安装 APK 已从手机读取并校验摘要一致。所有中间候选与失败数据保留。


刷新率请求方式对照：仅把窗口 `preferredDisplayModeId` 请求换为 `preferredRefreshRate`，候选 APK `3ff2ab6d0dedcbcaf7ed6da58f365e16158b6d084ccefe2001c21bf7f112716b`，Lint/Profile 构建成功（39 秒）。暖相册三轮超限为 41.616% / 36.810% / 21.581%，均未过门槛，不能证明稳定收益。已恢复原源码及已完成长时验证的原 APK；该实验不作为已交付优化。未改系统设置、依赖、预加载、缓存或播放器生命周期。

恢复刷新率对照前的源码后再次执行全部 JVM、Lint、Debug、AndroidTest、Profile、Benchmark 构建，`BUILD SUCCESSFUL in 59s`；重新生成的 Profile APK 摘要与已测 `6a9ef440...` 完全一致。


### 最终原包清缓存后滚动

每轮通过可见存储页面与确认面板清理，确认图片、视频用量均为 0.0 KB，再进入合成 large 相册采集；未预热滚动。首屏进入时可见图片已可能开始加载，因此这是清磁盘缓存后的滚动，不等于冷启动或所有图片均未加载；服务端封面与 OS 缓存保持暖态。

| 轮次 | 有效帧 | 超限比例 | overrun p95 / p99 / max (ms) | UI 间隔 p50 / p95 / max (ms) |
| --- | --- | --- | --- | --- |
| 清缓存后 1 | 1321 | 33.989% | 4.673 / 12.614 / 21.166 | 8.343 / 8.797 / 27.072 |
| 清缓存后 2 | 1366 | 41.874% | 4.879 / 12.527 / 22.403 | 8.343 / 8.747 / 29.254 |
| 清缓存后 3 | 1364 | 55.865% | 4.881 / 15.866 / 20.956 | 8.343 / 8.652 / 30.081 |

清理与采集命令见 `android/README.md`；实际日志 `cold-2-clear.log`、`cold-3-clear.log` 与 `delivery-cold-{1,2,3}/` 保存原始证据。清理未操作源媒体、连接或浏览书签。


最终独立 Macrobenchmark 因 runner cgroup 确认 `frozen 1` 无进展而主动停止；ADB exit 0、`shortMsg=Process crashed.` 不算测试成功，也不是生产应用崩溃。完整命令、证据与限制见 ACCEPTANCE。脚本均通过 `python3 -m py_compile`；缓存 helper 改为 main 入口并通过相邻文件定位模块，导入不会触发清理，安全检查不受 Python assert 优化影响。


## 最终状态与未完成验收

- 已安装并复核的 minified profile 与最终重建包 SHA-256 一致（`6a9ef4404b1e8b77e4be9cfa1d6ef6617d0c88a065116a1218e7b132714fe02f`）。最后全任务构建 59 秒成功，app 17 / media 3 JVM 用例零失败、零跳过，Lint 0 error（app 66 warning）。
- 最终原包补采首页/相册深浅两套截图：`delivery-home-dark.png`、`delivery-album-dark.png`、`delivery-home-light.png`、`delivery-album-light.png`，逐张检查文字、主题、圆角、角标及系统栏。相册截图为已恢复的深层位置，不能用来证明首屏日期或所有状态均通过。
- 主题恢复跟随系统，系统字号 1.0、动画倍率 1.0。手机停在 VVideo 首页，19092 本机合成服务及对应 USB reverse 保留供继续查看；两个无用测试服务与本轮 logcat 采集已停止。没有改写源媒体。
- **流畅度未通过**：最终暖相册三轮超限 27.359% / 32.044% / 42.701%，均高于 1%；冷加载与视频分别报告。trace 保留，Buffer Stuffing 原因尚未解决。刷新率请求对照无稳定收益，已撤回，未用改变指标掩盖失败。
- **未完成**：独立 Macrobenchmark 被设备冻结；真实 NAS / Wi-Fi、4K 代表性视频、冷缓存/磁盘命中/预加载三类受控首帧及实际目标帧 seek、服务端扫描/抽帧竞争。混合日志首帧不能冒充三类受控统计，历史 APK 数据不能替代本轮。
- **仍需人工复核**：完整 TalkBack、预测返回跟手/取消、全部长文件名与大字号页面、强制主题冷启动的逐帧白闪、所有错误/离线/格式不支持状态。上文原样第 10 节清单保持待勾选，不把有限自动测试泛化为全面通过。
- **审查限制**：当前无 Git，OCR 未执行；没有初始化假仓库或伪造通过。纯文档链接检查通过，Python 主机工具 py_compile 通过，缓存 helper 导入无操作副作用。

## 2026-10-11 目录转场修复

用户反馈“点击文件夹时框提前出来、动画不对”。在 iQOO 12 Pro（V2329A，Android 15）用 `adb screenrecord` 逐帧复现，原包两处问题：

- `LibraryViewModel.open()` 一开始就把 `busy=true` 写到当前页，`FolderScreen` 的首次加载占位盖在旧页列表上（本机回环约 80 ms），加载完才开始滑动，旧页带着占位滑走；首页标题也因加载圈横移一次。
- 目录与根路由的 `AnimatedContent` 未设置层级，返回时目标页以 85% 透明度画在退出页上层。

改动（仅 `:app` 的 `feature/library/` 与 `app/navigation/`，接口、缓存、播放器、依赖未变）：

- 去往其它目录时当前页状态不变，私有 `loading` 标志继续阻止旧页分页与视口保存；120 ms 内加载完成直接滑入已加载内容，否则由 `PendingDirectory.kt` 用已知的子目录/路径/媒体库/最近浏览信息构造目标占位页先滑入。揭示占位页时清空旧目录的内存书签，`refresh` 仅在书签目录一致时复用，避免把旧目录书签写到新目录。
- 占位只在 `busy` 且页面无内容时显示；加载失败不显示“这里还没有媒体”。目录副标题改由状态里的 `pathLabel` 提供。加载完成时一并复位 `pageBusy`。
- 两处 `AnimatedContent` 设置 `targetContentZIndex`：目录页按进入方向相对递增/递减（只保留最近一对），根路由按固定层级（合并两项修复后改为相对层级，见下文“合并与复审修复”）。
- 目录页 `AnimatedContent` 的目标由整个 `LibraryUiState` 改为目录 ID：转场进行中同一目录的状态再次更新（如访问后“最近浏览”刷新）时，Compose 会丢弃正在退出的页面，表现为偶发的黑色退出页。现在当前页跟随实时状态，退出页保留它最后显示的状态；方向在目录 ID 变化时由上一页的 `parentId` 判定。该现象在第一版修复包的录屏中已出现过一次（当时未逐帧看到），原包未单独验证是否存在。
- 目标无法提前命名（如冷启动恢复的目录已从最近浏览移除）且加载超过 120 ms 时，把当前页标为 `busy`，首页恢复标题加载圈；这是托管审查发现的本次改动回退，已修正。

验证（合成服务经 USB reverse。逐帧核对的各路径在第一版修复包 `2409302b…3253` 上完成；最终包 SHA-256 `dc0544e000259b3cd9ba09987880b3dd20dd664e3647d2f267c861a5d1781c14`，安装后与设备上的 APK 摘要一致，在其上只重跑了下述压力录屏）：

- `./gradlew --offline :app:testDebugUnitTest :app:assembleProfile` 与 `:app:lintDebug :core:media:testDebugUnitTest` 成功；app 22（含新增 `PendingDirectoryTest` 5 项）/ media 3 JVM 用例零失败零跳过，Lint 0 error（66 warning）。最终源码重跑同一组任务，结果相同。
- 逐帧核对：首页→媒体库、媒体库→子目录、最近浏览→目录、逐级返回、设置返回、标签筛选、图片查看器开合；经每请求延迟 600 ms 的本机转发核对慢加载（占位随新页滑入、返回层级正确）；经“延迟后断开”的桩核对失败（目标页显示离线提示条、无空状态）及重试恢复；下滑后回首页再进入能回到原位置附近。logcat 无 FATAL。
- 最终包压力录屏：首页→目录后连续 10 组进入/返回子目录，再逐级返回首页，共 23 段转场 736 帧，按“内容区 10% 以上近黑”逐帧统计为 0 帧（修正前两轮录屏分别为 4 段中 3 段、4 段中 1 段出现）；抽帧目视前进/返回方向与层级正确，logcat 无 FATAL。慢加载、失败/重试、查看器开合未在最终包上重录。
- **未执行**：冷启动“目标无法命名”分支的真机复现、设备插桩测试（需换装 device 变体）、帧率/内存测量、真实 NAS/Wi-Fi、预测返回手势。本次不涉及滚动路径，既有“流畅度未通过”结论不变。
- **OCR**：外接模型模式 `ocr review` 对 5 个文件全部返回 HTTP 401（提供方拒绝密钥），退出码 1，未改动其配置。改用托管模式（`open-code-review-delegate`）：`ocr delegate preview` 选出 5 个可审文件（3 个文档按扩展名、1 个测试按默认路径排除），`ocr delegate rule` 给出 Kotlin 系统规则，由宿主代理逐文件审查，覆盖 5/5。发现并修复：中等 1 项（上述无法命名目标时无加载反馈）、低 1 项（`FolderScreen` 冗余条件）。保留未改：同目录刷新/排序在慢网络下没有进度提示（低，产品取舍）；`open()` 在发布前重置分页窗口的既有时序（低，非本次引入）。审查者与实现者是同一代理，不等同独立复审；黑色退出页是之后真机复测发现的，不是审查发现的。
- 录屏中另见关闭图片查看器时画面先变黑（`PlayerViewModel.leave()` 立即清空状态），属既有行为，本节未改；由下一节的查看器修复处理。
- 操作记录：验证中一次误把应用退到桌面，随后三次点击落在桌面笔记小组件上并打开了一条笔记；录屏核对其内容与修改时间未变。之后的输入均先检查前台为 VVideo。


## 查看器关闭动画黑屏修复（2026-10-11）

**现象与原因**：返回时 `VVideoRoot` 先调用 `PlayerViewModel.leave()` 立即清空状态，`AnimatedContent` 中正在退出的 photo / video 页在 200 ms 内按空状态重组。图片页整页变黑；视频页的画面由 Surface 保留，但标题、时间随状态清空，且播放器在整个退出期间继续出声，直到页面离开组合才释放。

**改动**（未改 `leave()`、`CacheCoordinator`、`:core:media`）：

- 新增 `feature/player/ExitSnapshot.kt`：退出中的页面（`displayedRoute != route`）使用自己保留的最后状态，不推迟 ViewModel 清理。
- `PlayerRoute` 增加 `leaving`：进入退出即 `pause()`，不再监听播放状态（控件保持最后的样子），不再向会话或 ViewModel 发 select / preload / page；释放仍在页面离开组合时经 `graph.cache::release` 执行，时机不变。`SeekBar` 增加 `frozen` 同步冻结播放图标。
- `VVideoRoot` 以选择时间戳为 key 包裹 `PlayerRoute`：退出动画期间再次点开视频时新建页面（新 Surface、pager、会话），不复用正在退出的页面；外层 `Box(fillMaxSize)` 在新会话创建前保持页面尺寸。方向恢复值改在 effect 中读取，避免新页面把上一页面的横屏锁定当作原始值。

**本机检查**（JDK 21，`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleProfile`）：`BUILD SUCCESSFUL`；app JVM 21 项（新增 `ExitSnapshotTest` 4 项）零失败、零跳过；Lint 0 error / 66 warning，与改动前数量相同。最终 profile APK SHA-256 `b6a2ce8c36645a75801417be6a30fa913e4d8abf8444278d062a2add8ec6d3b1`。

**真机逐帧**（iQOO 12 Pro / V2329A、Android 15、动画倍率 1.0；本机合成媒体经 ADB reverse；`adb screenrecord` 720×1600，对每一帧计算媒体带区域平均亮度，而不是抽样目测）。基线为同一提交未改动源码的 profile 包 `8e036858…`，与修复包交替安装对照；已从手机读回摘要确认。

| 场景 | 基线 `8e036858…` | 修复 `b6a2ce8c…` |
| --- | --- | --- |
| 图片关闭，媒体带最暗帧亮度 | 16.0（全黑），3/3 轮 | 99.6–99.7（与相册交叉淡化的正常低点），4/4 轮；返回键与左上角返回各 2 轮 |
| 视频关闭（播放中） | 画面保留，标题与时间先消失；媒体带最暗 124.7 | 画面保留，5 轮 + 控件已自动隐藏 1 轮，媒体带最暗 124.7；标题、时间、进度条保持到淡出结束（全分辨率逐帧目视核对了其中 1 轮和 2 次重开录像） |
| 返回后应用 AudioTrack 状态（设备端约 50 ms 一次采样） | 264 ms、288 ms 的采样仍是 `started`，随后释放；2 次 | 59–109 ms 的采样已是 `paused`，304–366 ms 起无播放器；4 次（其中 2 次在较早的修复包 `b32b6c50…` 上） |
| 退出动画内点开另一个视频 | 旧视频画面留在新页面中部，直到新视频出画 | 无旧画面；重开后仅 1 个播放器，再返回后为 0；2 次 |

修复过程中的一次失败也保留：第一版只加 key，重开时新页面在会话创建前尺寸为 0，`AnimatedContent` 的尺寸动画把画面从左上角裁切着展开（包 `b32b6c50…`）；加外层 `Box` 后复测消失。证据与采集脚本在被忽略的 `test-results/viewer-exit-2026-10-11/`。

**限制与未验证**：

- 采样分辨率约 50 ms，不能证明暂停发生在哪一帧；按实现是返回后的下一次重组。未用麦克风或音频抓取确认可闻声音，只核对了 AudioTrack 状态。
- 未实测“首帧前（仍显示封面）返回”和“翻页滑动中返回”：ADB 输入延迟大于首帧时间，无法稳定命中；这两种情况只有代码推断。
- 重开场景用 `input keyevent` 与 `input tap` 间隔 50–100 ms 触发，从录像确认落在退出动画内；未覆盖横屏、预测性返回手势中途、照片与视频交替重开。
- 第一次基线录像期间另一会话正在同一手机上安装 APK，系统安装页盖住了画面，该段作废；之后在确认手机空闲后重做。
- **打开方向的既有问题（基线同样存在，本次未改）**：视频页进入时整页立即变黑而不是 180 ms 淡入（PlayerView 的 Surface 挖孔不参与透明度动画）；打开非首项视频时标题有一帧显示成列表第一项。
- OCR：`ocr review --audience agent` 退出码 1，4/4 文件 `authentication rejected (HTTP 401)`，0 条发现；交付前 `ocr llm test` 仍为 `401 invalid x-api-key`。**未完成代码审查**，不能称审查通过。
- 未运行 `connectedAndroidTest`、帧率或内存采集；本次不涉及列表、布局或预加载策略，流畅度结论不变。

## 合并与复审修复（2026-10-11）

上面两节分别在主目录和 worktree `claude/fervent-burnell-4f037c` 完成，各自的 APK（`dc0544e0…`、`b6a2ce8c…`）都只含一项修复，两节记录的真机结果只对各自的包成立。本节把两项修复合到一起，处理整体复审发现的问题，并在合并包上重新验证。

**合并**：`VVideoRoot.kt` 两边改动相邻（转场层级与 `leaving` / `ExitSnapshot`），手工合并；`android/README.md` 自动合并；本文件两节按顺序保留。

**复审后的改动**：

- **未加载成功的目标页返回不再发请求。** 原实现在慢失败（超过 120 ms 才失败，Wi-Fi 断开超时是典型）时已把用户带到空白目标页，原页内容被占位状态替换；返回要重新请求父目录，断网时一路是空白页。现在占位页揭示时由 `LibraryViewModel` 保留被替换的已加载页（状态、内存书签、视口位置）；分页窗口本来就只在目标加载成功时才替换，所以目标页加载中或失败后返回，直接恢复原页与滚动位置，不请求网络。目标一旦加载成功、回到首页或重新连接，保留页即丢弃。只保留“拥有分页窗口且不在重载中”的页面，其余情况仍走网络。
- **窗口、书签、页面状态一起切换。** `open()` 原先在请求 `path` 和计算相册布局之前就重置子目录窗口，期间被取消会让仍显示的旧页窗口与状态不一致（上一节记为“既有时序”）。现在相册布局先由新页数据算好，两个窗口的重置、书签和状态发布之间不再有挂起点。
- **占位页不覆盖期间更新的列表。** 占位状态由点击时的快照构造，揭示时改为沿用当时最新的“最近浏览”和媒体库列表。
- **折返时列表状态不再错位。** 目录转场未结束就返回同一目录时，仍在组合中的页面继续使用旧 `LazyListState`，而恢复、分页、视口保存观察的是新建的那个，表现为该次停留滚到底不翻页（HEAD 即如此）。现在当前页始终使用路由层的列表状态，退出页保留它最后使用的那个。
- **根路由层级改为相对值。** 固定层级让正在退出的查看器盖在相册之上，退出动画内的点击被旧播放页吃掉，上一节验证过的“退出动画内点开另一个视频”在第一版合并包 `ec057fdc…` 上失效（点击后无播放器、停在相册）。现在只有滑动返回到的页面压在退出页之下；查看器进入和退出时目标页都在上层，与两项修复各自验证时一致。

**本机检查**（JDK 21，`./gradlew --offline`）：`:app:testDebugUnitTest :app:lintDebug :app:assembleProfile :app:compileDebugAndroidTestKotlin :core:media:testDebugUnitTest` 成功；app 27 项（`PendingDirectoryTest` 6、`ExitSnapshotTest` 4）/ media 3 项零失败零跳过；Lint 0 error / 66 warning。最终 profile 包 SHA-256 `7d2625664adf8ea72f6b3a17d1ccba748d086e5cbe9ba5f8e750b98f659811dc`，安装后从手机读回一致。

**真机**（iQOO 12 Pro / V2329A、Android 15；本机合成服务经 ADB reverse；`adb screenrecord` 720×1600，逐帧统计；每次输入前检查前台为 VVideo）。除标明外均在最终包 `7d262566…` 上：

| 场景 | 结果 |
| --- | --- |
| 目录压力：`wide` 内连续 10 组进入/返回子目录，再逐级返回首页，随后设置→存储→返回→返回 | 805 帧，内容区 10% 分位亮度低于 40 的帧为 0（最低 40，出现在返回 `media` 时露出的深色合成图，抽帧确认不是黑页）；抽帧核对设置/存储的进入与返回层级正确 |
| 图片关闭，媒体带最暗帧亮度 | 99.5–99.7，4/4 轮（返回键与左上角返回各 2 轮），与查看器修复单独验证时一致 |
| 视频关闭（播放中） | 媒体带最暗 124.6–124.7，3/3 轮；设备端采样 51–70 ms 仍为 `started`，112–121 ms 起 `paused`，329–346 ms 起无播放器，3 次 |
| 退出动画内点开另一个视频（间隔 50 / 100 ms） | 重开后 1 个播放器、标题为新视频，再返回后为 0；2 次；其中 1 次逐帧核对无旧画面 |
| 慢失败后返回（本机转发器对每个请求延迟 600 ms 后断开） | 目标页显示离线提示条、无空状态；转发器仍在断开时按返回，原页内容与位置立即恢复，录屏 71 帧无暗帧；恢复转发后“重试”清除提示，再次进入目标目录正常。第一版合并包上也做过 1 次，结果相同 |
| 占位页滑入途中返回（转发器延迟 800 ms，点击后约 220 ms 按返回） | 录屏确认占位页滑入一部分后折回；随后连续下滑 `wide` 到 `folder-0402`，子目录翻页正常 |

logcat 全程无 `FATAL EXCEPTION`。证据与脚本在被忽略的 `test-results/combined-2026-10-11/`（转发器 `tools/proxy.py` 只在本机回环转发，不含令牌）。

**限制与未验证**：

- 没有在旧包上复现“折返后不翻页”，该问题来自代码推断；上表只说明修复后的包在该路径下翻页正常。
- 保留页与恢复逻辑在 `LibraryViewModel` 内，工程没有协程测试依赖，JVM 测试只覆盖纯函数 `restoredPage` / `pendingDirectory`；其余靠上表的真机路径。
- 仍未执行：冷启动“目标无法命名”分支、预测性返回手势、设备插桩测试、帧率/内存测量、真实 NAS / Wi-Fi。120 ms 仍是调优值。本次不涉及滚动路径，既有“流畅度未通过”结论不变。
- 同目录刷新、排序、筛选在慢网络下仍没有进度提示（保留的产品取舍）。
- 复测中发现一个既有问题，未在本次修改：冷启动停在首页时，“最近浏览”先渲染、媒体库行后插入到列表上方，首屏看不到媒体库行，需要下拉（`HomeScreen.kt` 自 HEAD 未变）。已在下一节“首页冷启动媒体库行”修复。
- **OCR**：`ocr review` 仍为 5/5 文件 HTTP 401、退出码 1（输出在 `test-results/ocr/2026-10-11-overall-review.txt`）。按用户要求改用托管模式：`ocr delegate preview` 选出 8 个可审文件（3 个文档按扩展名、2 个测试按默认路径排除），`ocr delegate rule` 给出 Kotlin 系统规则，宿主代理逐文件审查，覆盖 8/8，无 critical / high / medium。保留的低级别项：组合期间修改普通持有对象（`shown`、`layers`、`ExitSnapshot`）依赖幂等；被打断的转场复用页面后，层级记账可能高于该页实际层级（推演的各路径顺序仍正确）；`PendingDirectory.kt` 的排版与同包紧凑风格不一致。审查者同时是合并与修复的实现者，不等同独立复审。

## 首页冷启动媒体库行（2026-10-11）

**问题**：冷启动停在首页（上次目录为 0）时，Room 中的“最近浏览”先于 `api.libraries()` 发布，带 key 的“最近浏览”标题成为首个可见项；媒体库行随后插入到它之前，`LazyColumn` 按首个可见 key 保持位置，媒体库行落在视口上方，需要下拉才能看到。

**改动**（仅 `feature/library/HomeScreen.kt`，无新依赖）：首页列表改用显式 `rememberLazyListState()`；媒体库列表与上次组合不同时，如果列表仍在最顶端（`canScrollBackward` 为 false），在 `SideEffect` 中调用 `requestScrollToItem(0)`。该请求在本次组合应用后、变化后的行测量之前登记，只对下一次测量生效，因此没有“先错位再跳回”的一帧；用户已经滚动过的列表不处理，仍按首个可见 key 保持位置。

**本机检查**（JDK 21，`./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleProfile`，在 worktree `claude/competent-swartz-8858c9` 的 `android/` 下）：`BUILD SUCCESSFUL`；app JVM 27 项零失败零跳过；Lint 0 error / 41 warning，报告中没有 `HomeScreen.kt` 条目。前文记录的 66 warning 来自另一个检出，本次没有在同一检出上跑改动前的 Lint，所以不比较两个数字。profile 包 SHA-256 `02a6dc458e9fe7cd565bd6b80b91636b37c9136cc8e8b2a01446f6a03018ed63`，安装后从手机读回一致。

**真机**（iQOO 12 Pro / V2329A、Android 15；本机合成服务经 ADB reverse；`uiautomator dump` 读取屏上文字；操作前确认没有其他 adb 客户端、应用上次安装时间不是近期，每次输入前检查前台为 VVideo；手机由用户解锁）。先用返回键回到首页使上次目录为 0，之后每轮 `am force-stop` 再 `am start`。

| 场景 | 基线 `7d262566…` | 修复 `02a6dc45…` |
| --- | --- | --- |
| 直连冷启动 | 3/3 轮媒体库行在屏上（**未复现**） | 2/2 轮媒体库行在屏上 |
| 经本机转发器对每个请求延迟 800 ms 后冷启动 | 3/3 轮首项为“最近浏览”标题（y=432），屏上没有 `media` /“在线”行，标题栏已是 `VVideo`（媒体库已返回） | 3/3 轮 `media` /“在线”行在最上方（y=444–597），“最近浏览”标题在其下（y=724） |
| 延迟 4500 ms，媒体库返回前上滑“最近浏览” | 未测 | 上滑后 +2.2 s 截图标题栏仍为“连接中…”；媒体库返回后首项仍是 `large`（未被拉回顶部），屏上没有媒体库行；1 轮 |
| 点媒体库行进入 `media` 再按返回（延迟 800 ms） | 未测 | 回到首页，`media` /“在线”行与“最近浏览”位置同冷启动；1 轮 |
| 点标题栏重新连接（延迟 800 ms，列表在顶端） | 未测 | 重连后 `media` /“在线”行仍在最上方；1 轮 |

logcat 最近 3000 行无 `FATAL EXCEPTION`。屏幕文字导出与截图在被忽略的 `test-results/home-top-2026-10-11/`；测完已恢复 `adb reverse tcp:19090 tcp:19092` 并停止转发器。手机上留下的是修复包 `02a6dc45…`。

**限制与未验证**：

- 该问题取决于时序：直连时基线 3 轮都没有复现，只有把服务端响应延迟后才稳定出现。上表的对照因此是“延迟 800 ms”条件下的对照，不代表真实 NAS / Wi-Fi 下的出现频率。
- “已滚动不拉回”只做了 1 轮；截图取在上滑后的惯性阶段，与最终导出的行位置相差约 80 px，按惯性滚动解释，没有逐帧核对。延迟 6500 / 9000 ms 的两次尝试请求失败（“等待网络…”），不计入结果。
- 没有逐帧录屏，“没有错位的一帧”来自 `SideEffect` 与测量的执行顺序，不是实测。
- 用户只滚动了不足一行（首个可见项仍是媒体库行）时重新连接，媒体库行重新出现后可能在视口上方；按“已滚动不处理”的要求保留，未实测。
- 没有新增自动化测试：改动只在 Composable 内，工程没有可在 JVM 上运行的 Compose 测试设施，本次也按要求把改动限制在 `HomeScreen.kt`。未运行 `connectedAndroidTest`、帧率或内存采集；改动不在滚动路径上，既有“流畅度未通过”结论不变。
- **OCR**：按用户要求使用托管模式（`ocr review` 此前为 HTTP 401，本次未重试）。`ocr delegate preview` 工作区模式选出 1 个可审文件、0 个排除，`ocr delegate rule` 给出 Kotlin 系统规则，宿主代理审查 1/1，无 critical / high / medium。保留的低级别项：在 `SideEffect` 中修改普通持有对象 `shown`，与同包 `LibraryScreen.kt` 的做法一致；列表在顶端且媒体库变化时，`requestScrollToItem` 会取消正在进行的拖动。审查者同时是实现者，不等同独立复审。

## 查看器翻页：末尾视频与图片上下翻页（2026-10-11）

**问题一（视频）**：直接点开目录里的最后一个视频（合成库 `media` 的 `nile-falls.webm`）后无法上下滑动；从它前一个视频进入则可以。服务端按“锚点所在十媒体组”起始返回视频页，对该视频只返回它自己 1 条（用同一请求核对：`anchored page items: ['nile-falls.webm']`，向前页 13 条，向后页 0 条）。查看器在停稳的页上同一轮先后上报“需要后一页”“需要前一页”，`PlayerViewModel.page()` 在一次请求进行中直接丢弃另一方向；后一页返回空、列表长度不变，上报不会重发，前一页永远不再请求。服务端每个非空页都带 `next` 游标，`endReached=window.next.isEmpty()` 因此从不为真，“已经是最后一个视频”提示实际不会出现。

**问题二（图片）**：图片查看页只加载被点开的一张，没有翻页；[DESIGN](../DESIGN.md) 此前写明“不增加跨照片翻页”。按用户 2026-10-11 的要求改为可滑动切换，方向取与视频一致的上下滑动（用户未指定方向，这是实现时的取舍）。

**改动**（无新依赖、无 API / 服务端变更）：

- `data/library/PagingGate.kt`（新增）：同一时间只跑一个边缘请求；进行中被问到的相反方向保留到该请求结束后再发；空页关闭该方向，另一侧装入数据后重新打开。`PlayerViewModel` 用它替换原来的三个布尔量，并把“后一页为空”记为 `endReached`。
- `PlayerViewModel`：图片与视频共用按 `kind` 取页的队列（图片 `kind=image`）；图片队列取不到（409 / 网络失败 / 该图已不在目录页内）时退回只打开这一张，与改动前一致；`leave()` 清空窗口。元信息窗口仍是三页上限。
- `PlayerScreen.kt` 的 `PhotoScreen`：改为 `VerticalPager`。放大时停用翻页、单指平移；松手时缩放不足 1.1 倍回到原始大小（否则肉眼看不出放大却翻不了页，设备测试实际撞到 1.02 倍）；相邻页只预先合成缩略图，原图在该页成为当前页时才取，翻页停稳后释放非当前页原图。
- 测试：`PagingGateTest`（JVM，6 项）；`DevicePlaybackTest.bothEdgesAskedAtOnceStillLoadPrevious`；`PhotoDeviceTest` 增加上下翻页、沿翻页方向的双指缩放、放大后单指拖动不翻页，并支持 `-e serverAddress`。

**本机检查**（JDK 21，`android/` 下 `./gradlew --offline :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleProfile`）：`BUILD SUCCESSFUL`；JVM 36 项零失败；Lint 0 error / 66 warning，同一检出上改动前也是 66。profile 包 SHA-256 `18c1a343c47a6e816c3b2b98423bf395969584dac5494b2fda177596dc879dc5`；基线（`git archive HEAD` 另行构建）`9eb9bcd24e9392c4f3a30e7fd9fd0b5c8a72ec69424e6a59f77b27a50d7b3835`。

**真机**（iQOO 12 Pro / V2329A、Android 15；本机合成服务经 `adb reverse tcp:19090 tcp:19092`；每次输入前检查前台为 VVideo；用 `uiautomator dump` 读标题）：

| 场景 | 基线 `9eb9bcd2…` | 修复 `18c1a343…` |
| --- | --- | --- |
| 直接点开 `nile-falls.webm`，下滑、上滑 | 仍是 `nile-falls.webm`（用户原现场，1 轮） | 下滑到 `murchison-falls.webm`，再上滑回到 `nile-falls.webm`；继续上滑出现“已经是最后一个视频”（1 轮；此前两个中间构建各 1 轮，其中一轮继续下滑到 `12-h264-high-bitrate.mp4`） |
| 点开图片后上下滑 | 无翻页（未操作，代码只加载一张） | `photo-00→01→02→01→00`，第一张再下滑仍是 `photo-00`（1 轮）；中间构建：直接点开最后一张 `photo-19` 上滑不动、下滑到 `18→17` |
| 双击放大后单指滑动 / 再双击还原后滑动 | 未测 | 放大时仍是同一张，还原后翻到相邻一张（1 轮） |
| 1,000 图目录连续翻页 | 未测 | 中间构建：前进 308 次、后退 225 次，在第 105 / 210 / 320 / 95 张处与服务端顺序逐一核对一致（跨过 100 条分页边界、三页窗口淘汰与回填） |
| 翻页帧数据（adb 注入滑动，`dumpsys gfxinfo`） | 未测 | 10 次滑动 401 帧，jank 0，p99 7 ms；录屏 405 帧逐帧平均亮度最低 44.5 / 中位 50.7，没有整页空白帧 |

设备插桩（`device` 变体，`-e serverAddress http://127.0.0.1:19090`，最终代码）：`PhotoDeviceTest`、`DevicePlaybackTest#bothEdgesAskedAtOnceStillLoadPrevious`（日志 `opened_items=1 total=14`）、`#realUiVideoTransitions`、`#rapidSwitchUsesOnePlayerAndCurrentVersion` 各 `OK (1 test)`。`#unsupportedVideoShowsErrorAndNextRecovers` 在取夹具目录时 `NoSuchElementException`（当前合成库没有它要的目录），未执行到被测行为。logcat 无 `FATAL EXCEPTION`。测完手机上是 profile 修复包 `18c1a343…`。

**过程中由我造成并已恢复的状态变化**：

- 第一次运行 `PhotoDeviceTest` 没有传地址，测试把应用保存的服务地址改成了 `validation.json` 里的旧地址 `127.0.0.1:19323`，首页显示“等待网络…”。已在应用的“修改连接”页把端口改回 `19090`（令牌未动，页面显示“已连接 · 1 个媒体库”），临时加的 `adb reverse tcp:19323` 已移除。地址改变时应用按设计清掉了“上次目录”，按连接保存的书签未受影响。
- 插桩测试会在 `19090` 连接下把 `media`、`large` 两个合成目录以名称升序打开，这两个目录保存的排序和位置因此不是测试前的状态。
- vivo 安装确认页由我按元素坐标勾选并继续（用户当次明确允许自行安装），核对过页面上的包名与大小。

**限制与未验证**：

- 帧数据来自 adb 注入的滑动和 `gfxinfo`，不是按设计文档性能计划做的 Perfetto 三轮采集；既有“流畅度未通过”结论不变。翻页后的一次 `dumpsys meminfo` 为 PSS 145 MB / Graphics 100 MB，只是单点读数，没有与基线对比，也没有长时间内存曲线。
- 没有在慢网络或真实 NAS / Wi-Fi 下测；“前一页在后一页空响应之后补发”在真机上只经过本机回环验证，时序覆盖靠 `PagingGateTest`。
- 图片队列取不到而退回单张时，本次打开期间不能翻页；没有在真机上制造 409 来验证这条路径。
- 复测中发现一个既有问题，未在本次修改：`media` 相册滚到最底部时点开图片再返回，相册位置上移约 836 px（基线 `9eb9bcd2…` 同样出现，1 轮；中间位置返回则保持不变）。
- **OCR**：按用户此前要求使用托管模式。`ocr delegate preview` 工作区模式选出 7 个可审文件、排除 6 个（4 个文档、2 个 JVM 测试），`ocr delegate rule` 给出 Kotlin 系统规则，宿主代理审查 7/7。审查中发现并已修复三项 medium：翻页上报协程读到过期列表（窗口淘汰后列表长度不变）、双指缩放期间每帧重组翻页器、访问过的相邻页长期持有原图。保留的低级别项：分页请求失败后 `error` 标志不自动清除（既有行为）。审查者同时是实现者，不等同独立复审。
