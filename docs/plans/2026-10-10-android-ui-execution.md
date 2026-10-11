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
