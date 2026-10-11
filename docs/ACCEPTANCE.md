# 验证记录

当前结论（2026-10-09，UI 改版）：页面与配套接口已实现，构建和记录内真机行为检查通过；**整体验收尚未通过**，下载的 VP9/Vorbis WebM 有一轮 seek READY 1020ms，超过 1000ms 目标；最终UI包暖相册第一轮超deadline 4.23%，高于1%门槛。最新 UI 构建的测量记录见文末；下方旧版本结果仅作历史，不替代改版验收。真实大库、实际 NAS 挂载、OCR 与配套服务端部署仍未完成验收或执行。

## 2026-10-09：Android 开发环境与真机连接

历史环境记录（创建实现之前）。范围：按用户要求，在当前 Mac 上通过 Homebrew 安装 Android 开发工具，并使用已连接的 iQOO 12 Pro 验证编译、签名、安装、启动和卸载。**本记录不代表 VVideo 客户端、播放器、Gradle 工程或高刷性能验收。** 当时尚无 `android/` 或 Go 实现；下方新记录会列出后续实际产品验证。

### 实际环境

| 项目 | 本次核实的值 |
| --- | --- |
| 开发机 | macOS 15.8.1，Apple Silicon / arm64 |
| Homebrew | 7.0.7 |
| Android Studio | Homebrew 安装的 Quail 4 / 2026.1.4 Patch 1，cask 版本 `2026.1.4.8,quail4-patch1` |
| Java | Studio 自带 OpenJDK / JBR 25.0.3 |
| SDK Platform | Android API 36，包修订 2 |
| Build-Tools | 36.0.0 |
| Platform-Tools | 37.0.1；ADB 1.0.41 |
| SDK Command-line Tools | 23.0 |
| 手机 | USB 识别 iQOO 12 Pro；ADB 型号 V2329A，vivo |
| 手机系统 | Android 15 / API 35，arm64-v8a |

这些是已安装版本，不是项目最终 AGP / Gradle / Kotlin / Compose 兼容组合。Studio 欢迎页提示有 Rabbit 1 / 2026.2.1 更新可用；本次保留 Homebrew 安装的上述版本，不把它称为全网最新版本。

### 安装与本机配置

- Android Studio 位于 `/Applications/Android Studio.app`。
- SDK 位于 `$HOME/Library/Android/sdk`。Homebrew 的命令行工具用于初始安装，标准 SDK 内也安装了命令行工具；终端优先使用标准 SDK 内的版本。
- `~/.zprofile` 已加入 `ANDROID_HOME`、指向 Studio 自带 JBR 的 `JAVA_HOME`，以及 Java、SDK 命令行工具、Platform-Tools 的 PATH。原文件备份为 `~/.zprofile.vvideo-android-backup-20261009`；保留了原有配置。
- 新终端使用这些配置；已打开的终端或长期运行的代理进程可能保留旧环境，需显式加载或重启相关终端。不要因旧进程看不到变量就再安装一套 SDK。
- Studio 已启动到欢迎页，选择不发送可选使用统计。初始化向导检测到现有 SDK，其额外下载为 Android Emulator，已跳过；未安装模拟器、系统镜像或 NDK。
- ADB 仅使用本机调试服务及已连接 USB 手机，未开启手机无线调试、未修改系统安全校验设置。

本机重新检查入口（安装完成后实际验证过环境路径）：

```sh
source ~/.zprofile
java -version
adb version
adb devices -l
```

后续工程应通过自己的 Gradle Wrapper 固定版本，不依赖全局 Gradle。初始化时必须核实所选 Gradle/AGP 对 JDK 25 的兼容性；本次 SDK 命令行成功不等于任意 Gradle 版本都可用。

### 测试与结果

临时程序包名为 `dev.vvideo.sdkcheck`，只显示环境检查文本，不申请任何 Android 权限，不访问媒体或网络。源码、构建产物放系统临时目录，没有作为 VVideo 工程脚手架提交。

| 检查 | 方法与结果 |
| --- | --- |
| SDK 包 | 从正式渠道安装 Platform、Build-Tools、Platform-Tools、Command-line Tools；核对各包 `source.properties`，通过 |
| Java / 资源编译 | `javac --release 17` 编译临时 Activity；D8 生成 dex；AAPT2 链接 Manifest；通过 |
| APK 签名 | zipalign、临时调试证书、apksigner；签名验证通过，v1/v2/v3 验证为 true |
| ADB | 设备状态为 `device`；只读取型号、系统版本与 ABI，连接通过 |
| 安装 | `adb -d install --no-streaming` 返回 `Success`；**用户在手机上手动确认了安全安装提示** |
| 启动 | `adb -d shell am start -W -n dev.vvideo.sdkcheck/.MainActivity` 返回 `Status: ok`、`LaunchState: COLD`；应用进程存在且输出 `SDK_SMOKE_READY` |
| 界面 | 确认测试 Activity 在前台后截图，已查看，能显示“安装与启动成功”文本 |
| 清理 | `adb -d uninstall dev.vvideo.sdkcheck` 返回 `Success`；再次查询确认临时包不存在；临时签名私钥已删除 |

启动命令报告的时间只属于这个极小测试应用，不用于推断 VVideo 的启动或首帧性能。临时界面截图留在被忽略的 `test-results/android-setup/` 中，不是产品截图基线。

### 过程中遇到的情况

- 初始受限环境下 Homebrew 无法更新自己的缓存，随后在用户已授权的系统安装范围内完成安装。
- 首次 ADB 启动等待超时；重新正常启动服务后连接成功，之后安装、运行和卸载均完成。诊断期间的 ADB trace 已通过正常重启服务关闭。
- JDK 25 下 apksigner 提示未来版本的 native-access 限制；当前命令退出成功、签名验证通过。后续固定工具链时应重新检查，不把该提示当作签名失败。
- 手机安装确认由用户完成。没有验证自动点击该弹窗，不能承诺后续安装无需人工确认；密码、指纹或系统限制仍可能需要用户操作。

### 尚未验证

未创建或运行 VVideo 的 Gradle 构建、Compose/Media3 客户端、Go 服务、NAS 索引、真实视频播放、120/144 Hz 帧表现或持续资源测试。临时程序是环境诊断，不替代 [设计](DESIGN.md) 和 [实施计划](plans/2026-10-09-server-performance.md) 中的产品测试。


## 2026-10-09：第一版实现与产品验证

当前状态：Go 服务与 Android 首版已实现并在授权 Linux / iQOO 12 Pro 上运行。以下范围内有实际结果；**整体性能验收尚未全部通过**。长期最终构建、冷相册、全屏帧测量的后续结果追加于本节之后，不能由历史成功代替。原媒体只读；所有压力文件均为自建合成数据。

### 构建与实际运行边界

- Go 1.27.1；SQLite WAL/FULL，一个写者、四个只读连接。Linux 为 AlmaLinux 10.2 / x86_64；数据库在本地盘。Android 使用 JDK 21、Gradle 8.14.3、AGP 8.13.2、Kotlin 2.2.21、Media3 1.8.1、SDK 36 / min 28。
- `device` 为非调试/profileable、关闭裁剪的行为测试构建；`profile` 为非调试/profileable、已裁剪、开发签名的外部帧测量构建。两者不能混称正式签名 release。生产 APK 的摘要和原始 trace 位于被忽略的 `test-results/`。
- Linux 当前是临时 `vvideo-test.service`，监听回环 8080。手机通过 ADB reverse 与 SSH 隧道访问。**没有完成独立 Wi-Fi 验收或持久部署**；具体提案与自动审批限制见 [部署方案](DEPLOYMENT.md)。地址、令牌、设备原始记录不放入共享源码。
- 最初误把 `~/vvideo` 当工作目录；已纠正为该目录本身就是媒体根，运行状态移到独立 `~/vvideo-runtime`（见后面的根目录修正记录）。没有扫描或改写用户估计的 13 万视频 / 38 万图片真实库。
- 手机亮度由用户调低，接电保持唤醒经授权；实际 UI 为 120 Hz。支持模式中虽有 144 Hz，本次没有获得实际 144 Hz 数据。系统、网络和热条件不能外推到其他设备。

### 已完成的正确性验证

| 范围 | 实际结果与证据 |
| --- | --- |
| Go 单元/行为/竞态 | 全包 `go test -race ./... -timeout 3m`、`go vet ./...` 通过；后增流并发与版本中途变化、cron DST、超长配置、查询规模用例亦通过。日志 `go-final-race.log`、`go-final-expanded-tests.log`、`go-last-vet.log` |
| 版本与 Range | 条件/无效/多范围、原偏移 MP4 头尾索引、源变更拒绝旧版本；中途改变字节中止响应，不混合新旧数据；八个停滞 writer 占槽时第九个 503，取消后释放。自定义 writer 用例不等同于实际 TCP 60 秒超时证明 |
| 扫描与恢复 | 两次健康缺失后处理 tombstone；离线/失败不当删除；未变复扫不更新 entries、不升目录版本；候选发布、SIGKILL 回滚、SQLite 完整性及任务重启重试、锁释放、过期候选恢复通过 |
| Android 非调试行为 | 最终完整类运行 283.249 秒，八个功能用例通过；未显式指定时长的长期用例跳过，JUnit 汇总 `OK (9 tests)` 不表示九项全部执行。日志 `device-final-all-behavior.log` / `device-final-all-events.log` |
| UI 与窗口 | 真 UI 视频前后各 12 次翻页，当前标题/媒体 ID/版本一致、单播放器；420 视频双向分页与取消；2000 图片深滑、最多 300 项、退出 Activity 重开深锚点恢复、反向回首组通过 |
| 磁盘设置/清理 | 1/5/10 GB 设置、重建实例后读取已有缓存、调小与实际清理、连接及书签保留通过。未用这些结果声称已填满 1 GB 或验证手机真实低存储状态 |
| 网络策略 | 平台及应用均禁止全局远程明文 HTTP；仅回环及构建显式指定的一个 RFC1918 IPv4 例外通过。未改变系统全局网络安全策略 |

### 样本和播放测量

根目录 13 视频：H.264/H.265/AV1、MP4/MKV/TS/MOV、横竖、头尾 moov、普通和较高码率，以及官方 Blender Sintel 720p / 52 秒公开预告。AV1 不伪造为 TS/MOV 支持。另有 3 个 3840×2160 / 30 fps / 8 秒 / AAC 合成样本：H.264 约 20.26 Mbps；HEVC Main10 约 20.26 Mbps；AV1 10-bit 约 13.92 Mbps。**10-bit 不等于 HDR**；未含 HDR 元数据，真实用户音轨/profile/GOP/HDR 尚未验收。实际解码器记录为 Qualcomm `c2.qti.avc/hevc/av1.decoder`。

TS 预加载复测曾失败：完整字节已缓存，但受限 CacheWriter 未发现 EOF，提取器读不到长度，seek map 不可用。已用版本键对应的服务端实际长度写入缓存 metadata，未更改原文件、字节偏移或拖动断言。复测三轮全部完成；旧失败日志保留为 `device-cache-three-rounds.log`。依据 Media3 的 ContentMetadataMutations 与 TsExtractor 原码，不把时长 JSON 当播放索引。

每轮每种场景均 16 样本；p95 用 nearest-rank（16 个样本即最大值），144 次正式首帧、96 次拖动，warmup 不混入。冷条件仅清除手机媒体字节缓存，手机、网络和服务端 OS 页缓存仍为运行态。磁盘场景先读取再释放/重建播放器；预取场景确认**目标版本键**缓存字节 >0，但不承诺完整离线。Surface 已挂载、元信息已取齐的 select→首帧与真实点击分开报告。

| 指标 ms（p50 / p95） | 第 1 轮 | 第 2 轮 | 第 3 轮 |
| --- | --- | --- | --- |
| 冷首帧 | 141 / 210 | 134 / 220 | 131 / 227 |
| 磁盘首帧 | 85 / 99 | 86 / 122 | 81 / 119 |
| 预取首帧 | 151 / 272 | 161 / 221 | 139 / 219 |
| 冷 seek→READY | 64 / 990 | 74 / 842 | 87 / 931 |
| 预取 seek→READY | 76 / 952 | 74 / 982 | 55 / **1019** |

首帧各轮通过 [冻结基线](decisions/2026-10-09-device-baseline.md) 的 750/500/500 ms 门槛；真实 UI 低层点击→首帧 337 ms 单次，不能代替三轮分布。**4K HEVC Main10 预取第三轮 1019 ms 超过 seek p95 1000 ms 门槛，拖动性能未全部通过**；READY 不等于精确位置首帧可见。小样本 p95 对单次波动敏感，不能删除失败或事后提高阈值。结果 `device-cache-final-summary.json`。

### 服务端规模与成本

| 场景 | 实测与范围 |
| --- | --- |
| 51 万索引 | 流式播种、六排序、每轮 20 次首屏和深锚点，共 720 查询；最大逐轮 p95 2.021472 ms。总 197.82 秒，最大 RSS 30588 KiB（29.87 MiB）；含生成/锚点建立。日志 `linux/query-scale-results.log` |
| 1 万文件 | P1/P2/P4 扫描：首次约 2.13–2.32 秒、未变约 1.00–1.05 秒、单变约 3.06–3.40 秒；RSS 约 32.3–32.7 MiB。合成七字节文件，无媒体探测/封面 |
| 10 万文件 | P2 首次 26.464、未变 11.387、单变 45.099 秒；RSS 35764 KiB（34.9 MiB），最慢写事务 76.67 ms；版本记录写入 100000/0/1。日志 `linux/scan-100000-p2.log` |
| 10 万待发布任务 | 暖查找 210.236 ms，无写事务；最大 RSS 约 27.6 MiB；发布后尝试次数为 1，隐藏祖先不提前执行，恢复按 128 个短事务批次 |

这些为本地暖存储合成结果，不能称真实 NAS 建库速度。未变扫描仍 O(n) 枚举；单变仍需 O(n) 候选成员复制/清理，本次确实比首次更慢。`version_records_written` 不是所有 SQL 写行数；累计并行阶段纳秒不是墙钟。先前无界测试器 RSS 约 742 MiB 已改为流式测试器，并保留记录，不作为服务进程结果。

### 相册暖高刷 trace

最终裁剪 profile 构建，2000 张合成缩略图、预热后每轮前后各 12 次 300 ms 手势；三轮分别报告，不挑最好轮。Macrobenchmark runner 被 OEM 冻结/杀死，多次尝试（包括测试专属前台服务）失败；随后用宿主 ADB Perfetto 采集生产进程 UI / RenderThread / SurfaceFrameTimeline，按 AndroidX FrameTimingQuery 匹配定义计算 `max(actualEnd, renderEnd) - expectedEnd`。trace/解析/构建摘要在 `test-results/perfetto/`。

| 每轮指标 | 1 | 2 | 3 |
| --- | --- | --- | --- |
| 有效匹配帧 | 1212 | 1171 | 1196 |
| 正 frameOverrun 比例 | 0.1650% | 0.1708% | 0.0836% |
| overrun p95 / p99 / 最差 ms | -4.1875 / -3.1714 / 12.6504 | -4.6865 / -3.4070 / 11.4482 | -5.9721 / -4.9288 / 6.3861 |
| UI 提交间隔 p50 / p95 / 最差 ms | 8.3407 / 8.5696 / 19.5281 | 8.3453 / 8.5927 / 18.2700 | 8.3469 / 8.5574 / 28.1006 |

暖相册该条件三轮通过 ≤1% 门槛，UI 实际约 120 Hz，非仅屏幕设置截图。另按直接 SurfaceFrameTimeline ID 审计：1215/1171/1197 有效帧，结果一致；少量 actual 开始偏移 >50 微秒使 AndroidX 匹配排除。UI/RT 无 Surface timeline 的帧不能算有效截止时间样本，保留全部 UI 间隔与匹配数；不能只保留流畅帧。解析有 2 个负时间戳丢弃及 34 项 vendor ftrace 设置提示，无最终 buffer 覆写/丢帧计数；初次 ring buffer 丢起始标记的采集移入 `partial-ring/`，不用于验收。冷加载与全屏翻页必须另报。

### 历史长期记录、失败及未执行项

此前 13 样本连续引擎轮播 30 分钟通过（1804.414 秒）：0–29 分钟最大 PSS 187.71 MiB，最后五分钟对预热后五分钟中位数增长 2.04 MiB；温度 27.3–28.2°C。这是旧构建、PlayerView 覆盖层的引擎轮播，不等于当前 16 样本或真实界面浏览；当前构建需另测。第 31 分钟 runner 已退出，`No process` 不能当持续使用后的内存数据。

修复了分页 EOF 后反向无法加载、离页请求写回、播放器交接 Surface 生命周期及 cache EOF metadata；旧失败均留日志。真实 UI 测试曾因跨线程比较旧状态与新媒体、计时包含 UiObject 查询、书签 savedAt 正常更新而误失败，已修正测试观察方式，不放宽业务断言。

- 本目录尚未初始化 Git，`git status` 实际失败；按 AGENTS 不能为掩盖审查限制初始化仓库。**open-code-review 未执行**，Go/Gradle/行为检查不冒充 OCR 审查。
- 真正低手机空间、缓存满额淘汰、真实用户长 GOP/HDR/音轨、真实挂载故障、Wi-Fi 吞吐、手机重启恢复、144 Hz、扫描/抽帧竞争下的完整三轮性能仍需相应证明；已有单元/合成结果不代替这些场景。
- 持久服务与精准 LAN 防火墙的自动审批被拒绝，待用户批准具体范围；当前临时服务可用于已授权测试。测试辅助 runner 前台权限/临时白名单在尝试后已撤销，未改手机全局安全或冻结策略。


### 实际 TCP 停滞与独立全屏 trace 补充

真实 Linux TCP 验证：8 个客户端缩小接收缓冲并停止读取 20 MB 原视频响应，第 9 个返回 503 / Retry-After:1。保持 64.007 秒后，在旧客户端尚未主动关闭的情况下，新 8 个均获 200，证明无进展写超时释放服务槽位；随后全部测试连接清理。证据 `linux/stalled-stream-results.log`。一次手机首帧测量错误地与此占满测试重叠，收到 503 后被停止，保留为 `device-decode-only-controlled.log`，不能用于无竞争性能判断；之后独立复跑。

全屏翻页首轮采集使用 TS 修复后的裁剪 profile 构建（在后续 decode-only 参数和 provider 显式关闭之前）。根目录 13 视频、预热、每轮前后各 12 次 300 ms 手势；UI layer 与视频 Surface 分开审计。三轮有效帧 1225/1223/1230，正 overrun 比例 3.0204% / 10.6296% / 3.9837%；p95 -4.8443 / 3.1672 / -4.2244 ms，p99 10.0208 / 12.3579 / 10.0367 ms，最差 16.9934 / 28.8093 / 28.0162 ms。UI 提交中位间隔 8.3461 / 8.3470 / 8.3446 ms，最差间隔 28.5950 / 36.2401 / 29.2359 ms；实际约 120 Hz。全屏负载高于暖相册，不能把暖相册 ≤1% 成绩套在全屏。记录在 `perfetto-video/`，每轮 vendor 负时间戳诊断保留。最终构建补测另列。


### 最终候选的工程修正

Android 14+ 在 Media3 1.8.1 的 DefaultRenderersFactory 开启 decode-only 输入标志，目标前的依赖样本仍正常解码，但不要求输出；Android 13 及以下保留默认路径，未改成关键帧近似拖动、未转码或增加第二个解码器。该 API 是实验参数，版本已固定，未来升级需重新核对。依据：[1.8.1 factory](https://raw.githubusercontent.com/androidx/media/1.8.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)、[同版本 renderer](https://raw.githubusercontent.com/androidx/media/1.8.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java)。独立三轮 HEVC 冷 seek 924/928/939 ms，预取 seek 945/939/949 ms；这一结果不能证明全部差异都来自该开关，旧失败继续保留。

VideoCache 明确在 release/delete 后关闭自己创建的 StandaloneDatabaseProvider，避免将 SQLite 连接关闭依赖 GC。独立 64 KiB 测试配额写入 40 KiB 版本片段，实际触发旧项淘汰，验证版本键隔离及关闭重开保留新项，不填满手机存储、不触碰原媒体。真实低存储仍未测。

全屏固定一个 PlayerView/Surface，分页层只移动封面与标题，继续在最终选定后切换同一个播放器。独立 Compose 暂停/继续/进度控件位于分页上方，进度仅刷新小控件子树；不会让整页列表随播放进度重组。此修正针对 trace 显示的重复 Surface 交易成本，实际收益以最终 trace 为准。先前 provider 回归调度器在长测开始前终止，避免继续测旧构建；该次整体日志未得到完整 runner 结尾，不能声称完整类通过。最终同源回归和长测使用单独文件名。

当时运行中 Go 服务 RSS 42080 KiB（41.09 MiB）、25 个 OS 线程、19 FD；DB 7086080 字节、WAL 4161232 字节、封面目录 du 29788 KiB。cgroup MemoryCurrent 约 338 MiB 含页缓存等，不能混称 Go live heap 或进程 RSS；单次快照不是长期稳定性证明。记录 `linux/final-live-resources.txt`。


### 最终 Android 功能与数值回归

固定 Surface / 独立控件 / decode-only / provider 显式关闭的同源构建，完整类 286.092 秒，`OK (11 tests)`：9 个功能用例成功，2 个需显式时长的长测跳过。真 UI 暂停、继续、滑条拖动、前后视频/版本匹配、单播放器及独立 LRU 都通过。最终首帧真实点击单次 365 ms；日志 `device-final-single-surface-all.log` / `-events.log`，APK SHA-256 在 `final-single-surface-apk-sha256.json`。

| 最终每轮 p50 / p95（ms），16 样本/场景 | 1 | 2 | 3 |
| --- | --- | --- | --- |
| 冷首帧 | 138 / 212 | 147 / 234 | 134 / 237 |
| 磁盘首帧 | 80 / 105 | 80 / 95 | 79 / 132 |
| 预取首帧 | 153 / 233 | 169 / 215 | 159 / 246 |
| 冷 seek→READY | 73 / 909 | 97 / 926 | 75 / 947 |
| 预取 seek→READY | 94 / 926 | 64 / 866 | 85 / 956 |

这一最终条件的三轮数值均通过冻结门槛；旧 1019 ms 失败保留在历史表，不能因此宣称所有未来媒体都在一秒内。16 样本尾部统计仍有限。完整类之后开始显式 `sustainedUiBrowsing -e soakMinutes 30`；它在真实相册/视频路由中往返，覆盖根 13 个视频与 3 个 4K 视频，不用引擎覆盖层代替浏览。长测的完成结果另列。

### 挂载身份修复与迁移边界

Linux 当前 mount ID、parent ID、匿名设备编号、可变挂载选项不再参与持久挂载键。先固定媒体根描述符，以 statx 的当前 mount ID 选择准确 mountinfo 记录，再由来源、文件系统类型、描述符的 statfs fsid 和媒体根 inode 构成 v2 身份。挂载根/点不参与持久键，避免只读 systemd 私有 bind 布局产生误失配；文件字节指纹仍包含设备/文件 inode、size、mtime。老内核没有 mount ID 时只接受不歧义的最长路径分量匹配。重叠挂载不猜来源，fsid 全零等不能建立身份时保守保留旧索引。

旧 v1 身份只在当前**完整旧观察**仍精确匹配时迁移为 v2，不新增库 ID、不删原文件、不中途混字节；未知变化（包括 force 扫描）不能自动收养另一文件系统。旧身份已在另一轮重挂载后失配的库无法凭空恢复历史证明，保留离线并需管理员确认来源。fsid 与来源的稳定性由驱动/挂载配置决定，不是内容哈希或所有 NAS 故障的证明。

合成编号/选项变化、来源/类型/fsid 改变及真实媒体根替换、当前 kernel ID 的叠加挂载、路径分量与空格转义，以及旧身份精确迁移、替换根拒绝的行为测试，在 Mac race 和 Linux 本地临时目录均通过。最终日志 `go-final-all-stable-identity-race.log` / `linux/stable-mount-final-results.log`。实际 Linux 主机与 ProtectHome=read-only、ProtectSystem=strict、PrivateTmp=true 的只读临时单元观察相同媒体根身份，比较测试退出 0；日志 `linux/stable-mount-sandbox-results.log` / `linux/stable-mount-observations.log`。更新回环临时服务后启动扫描成功，库 ID 保留、healthy=true，数据库已为 v2；未启用正式开机服务。未真正卸载用户挂载或重启 NAS，不能把模拟观察变更说成实际 NFS 重挂载。

Modern Go CLI 依据实际 go.mod 1.27.1 完整读取 list；mountinfo 的字段索引/变长分隔符需要随机访问，阅读 `strings_split_seq` explain 后保留 `strings.Fields`，不是单纯遍历分词，故不换成 FieldsSeq。协议依据：[Linux mountinfo](https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html)、[statfs](https://man7.org/linux/man-pages/man2/statfs.2.html)。


Perfetto 复测已提供 `scripts/measure-device-frames.py`，使用宿主采集避开 OEM 对独立 runner 的冻结，保留原始 trace 与解析诊断。UI actual layer 与视频 layer 区分，expected 按同一交易 token 关联：本 OEM 的 expected 层名可能归到同交易的 SurfaceView，不能再用 UI layer 名过滤 expected。首次辅助解析因此只有 2/1225 匹配帧，判为无效、保留在 `perfetto-video-repro-analysis-invalid-expected-layer/`，不作为 0% 卡顿成绩；已修正并增加匹配覆盖不足时失败的保护。此保护属于测量完整性，不改变 1% 性能门槛。


### 最终真实 UI 长测：首轮内存门槛失败

同一 device APK 的 `sustainedUiBrowsing` 完成 1850.019 秒、27 个完整相册/根视频/4K 视频循环，JUnit `OK (1 test)`。没有播放器错误，多播放器及离页保留断言通过，视频缓存每轮 79,820,604 字节。每分钟 PSS 采样 1–30 有效，0 为 instrumentation 启动交接时 No process，不能作为零内存或崩溃样本。

首轮最大 PSS 331.49 MiB，未超过 512 MiB；5–9 分钟中位数 223.50 MiB，25–29 分钟 293.12 MiB，增长 **69.62 MiB，超过 25 MiB 门槛**。功能通过不能替代内存验收。Java 区有大幅周期波动，native/graphics 与 dumpsys Heap Alloc 未同步单调增长，这只是定位线索，不能据此否认泄漏。原始数据在 `soak-final/`，runner 日志为 `device-final-single-surface-ui-soak-30min.log`。

随后用同一构建、同一 UI 场景和不变门槛重复 30 分钟，将采样提高至每 10 秒，在 300–600 秒与 1500–1800 秒完整窗口比较所有有效样本，避免约 68 秒业务循环与 60 秒采样产生相位偏差。不强制 GC、不删异常样本；首轮失败继续保留。密集采样结果另列，不把测量变化说成代码修复。


### Linux 最终回归与真实批量成本

当前稳定身份源码的 config/storage/scanner/tests 二进制在 Linux 实际运行通过，包括进程 SIGKILL 恢复、Range 中途源替换、优先级暂停、候选清理及锚点查询。显式规模环境未指定时的 filesystem-scale 用例跳过，不能当规模测试执行。日志 `linux/all-stable-source-results.log`；Mac 全包 race 与 vet 结果另保留。

新增 `BenchmarkBatchWrite` 在实际迁移 schema 和 Store.Write 上比较 1/32/128/512 提交批次。每操作固定更新 1024 条已索引 entries 的两个时间键，校验最终行数/写入次数与提交数；保持 WAL/FULL、外键及全部索引。夹具建立排除在计时外，重复操作不扩大数据库，不运行外部媒体工具。Linux 各 5 次、中位吞吐与所有运行中最慢事务如下：

| 批次 | 提交/1024 项 | 中位项/秒 | 最慢事务 ms |
| --- | --- | --- | --- |
| 1 | 1024 | 1594 | 9.086 |
| 32 | 32 | 26565 | 7.519 |
| 128 | 8 | 52726 | 5.696 |
| 512 | 2 | 73718 | 11.220 |

这是暖本地 SQLite 索引更新基准，不是完整扫描的最佳批次证明。128 相对逐行减少提交并控制单事务时长；512 吞吐更高也持锁更久，生产常量仍为 128，没有为跑分改变 FULL 或批次。原始 20 个结果保留 `linux/batch-write-five-runs.log`；受影响 storage race/vet 通过。Modern Go 新文件定位调用因文件尚不存在失败，随后按实际 go.mod 1.27.1 参数完整读取 list，采用 b.Context/b.Loop。


### 密集采样重复、取消边界与时间键修复

同一固定 Surface device 构建、同一 UI 场景第二轮 1850.715 秒，27 个完整循环，功能 `OK (1 test)`。10 秒采样保留 185 个有效 PSS 点；5–10 与 25–30 分钟各 30 点，中位数 224.60 / 228.91 MiB，增长 4.31 MiB，峰值 348.88 MiB；Java PSS 13.95–147.32、native 15.69–21.12、graphics 99.79–220.56 MiB，电池温度 30.7–30.8°C。数值门槛通过，完整窗口中位数无持续增长。原始 `soak-final-dense/`、分析 `soak-final-dense-analysis/`。这是提高采样密度后的重复结果，首轮稀疏窗口 +69.62 MiB 失败仍保留，不能把测量变化称为代码修复。

随后新增确定性创建取消用例：阻塞调用者 executor，使播放器在 Main 创建成功、返回尚未恢复时取消调用。原 APK 真机出现 `cancelled creation retained a player`，runner 失败日志 `session-cancel-before.log`。协调器现在在取消返回时收回尚未交付的播放器，旧实例释放与会话销毁在 Main 的 NonCancellable 清理边界完成；不增加播放器或依赖 GC。最终构建需复跑该用例与原完整类。

实际 probe.Run 的 EXIF 文件读取/工具解析回归复现四种失配：原始时间无时区、无效偏移、无效日期、越界年份；来源已标 mtime，effective_time 却仍是容器时间。现在读取原始 EXIF 后先同时回退键与来源，再仅在原始时间/时区有效时采用 EXIF。五种用例（含已知时区）通过 Mac race 与 Linux 实际执行；失败前日志 `probe-exif-precedence-before.log`，通过日志 `go-final-exif-precedence-race.log` / `linux/final-exif-and-root-results.log`。该小夹具是 JPEG/TIFF 元数据和可控工具输出，不是可播放图片样本。

媒体详情使用现有 DTO 显示时间来源与 UTC 排序键、保留原始字段，说明容器时间可能来自导出；未知来源不伪称 mtime。连接地址被策略拒绝时显示连接错误，保留已有连接，不再显示缓存操作错误。不可变 Connection 的服务命名空间只计算一次，避免每次 UI 重组创建 digest/Formatter；值及版本缓存契约不变，具体性能以最终测量为准。

### 媒体根路径纠正

初始测试误将用户指定的 `~/vvideo` 当运行工作目录。已停止临时服务，把**本次创建**的运行文件整体移到 `~/vvideo-runtime`，将原合成 `media/` 目录移回 `~/vvideo` 本身，数据库、暂存、缓存、配置和二进制均在根外。没有改写合成源字节，也没有用户真实媒体参与。配置及待审批 unit 同步路径，回环临时服务重新启动、扫描成功、healthy=true。

按现有契约，配置根路径变化创建新库 ID；旧索引保留为 inactive，新目录需要重新探测/封面。不能声称原手机缓存仍为新库命中。最终播放、帧与内存验收将在新根与最终构建上执行。路径调整未启用正式服务或更改防火墙。另在 `negative/` 加入 320×180 / 2 秒 FFV1 合成 MKV，用于设备不支持编码的可见错误及后续正常播放恢复验证，不混入根目录 13 样本或首帧统计。


### Compose 会话所有权交接补充

创建协程返回后的取消修复还不足以覆盖 UI 重组间隙：若会话已返回并写入 Compose 状态，但路由在下一次重组安装 DisposableEffect(session) 前被移除，仍会保留播放器。新增可控真实 Compose 用例在 create 返回时移除父路由，资源断言复现失败：`route-ownership-before-3.log`。前两次测试把销毁标记画到状态栏下，未到业务断言；保留日志、不能算缺陷证明。将标记放到屏幕中央后才确认实际保留。

现在创建会话的 LaunchedEffect 立即拥有该会话，在 awaitCancellation 的 finally 中通过 Main 的 NonCancellable 清理释放；清空 UI 状态时检查对象身份，避免旧协程覆盖新会话。无需等待重组后另装 Dispose，也不再让应用 scope 延后接管路由释放。最终构建回归需同时通过 dispatcher 取消与重组前销毁两项。


最终所有权构建 focused 真机回归 2.602 秒、`OK (2 tests)`，dispatcher 取消和重组前路由销毁都通过；对应 `final-route-focused.log` / `-events.log`。完整类和新根的 16 样本三轮统计已开始，最终构建摘要在 `final-correct-root-builds.json`；旧生命周期构建摘要不可替代它。


## 最终源码完整真机回归（根目录纠正后）

非调试 `device` 构建与最终源码对应，APK SHA-256 为 `d3eed92f152bd37bfaaaff45d363e02b16294cca68b08c41c1b23121c2396f3c`。AndroidJUnit 完整入口 296.954 秒，15 个条目中 13 个实际执行通过、2 个长时入口因未提供时长而 Assume 跳过。原始输出 `test-results/final-correct-root-all.log` 与 events 完整保留。不是 15 项实际功能均执行。

通过范围包含 13 种合成/公开视频、三个 4K 代表样本、TS 缓存后 seek、真实 UI 暂停/恢复/seek、详情时间来源、FFV1 不支持错误与下一页恢复、420 条双向窗口恢复、单播放器/版本一致、缓存预算/清理、无效连接保留旧连接、创建取消和重组前退出两个资源释放边界。媒体根现在是用户指定的 `~/vvideo`，新库 ID 与旧缓存隔离。

以下是三个独立轮次，各场景每轮 16 样本的 nearest-rank p95，单位毫秒；n=16 的 p95 正好是该轮最大值。seek 测的是 75% 定位至 READY，并非可见精确帧时间。冷缓存仅指手机媒体字节缓存，Linux 文件系统缓存未清空。

| 场景 | 轮次 1 | 轮次 2 | 轮次 3 | 原门槛 |
| --- | ---: | ---: | ---: | ---: |
| 冷首帧 | 209 | 223 | 251 | ≤750 |
| 磁盘复用首帧 | 123 | 121 | 119 | ≤500 |
| 邻居预取首帧 | 266 | 250 | 219 | ≤500 |
| 冷 seek READY | 951 | 923 | 950 | ≤1000 |
| 预取 seek READY | 930 | 953 | 933 | ≤1000 |

144 首帧与 96 seek 原始样本及轮次汇总位于 `test-results/final-correct-root-controlled-summary.json`。本轮传输为 USB/SSH 回环，不能代替独立 Wi-Fi 测量；后续临时内网测试单独记录。


## 临时 Wi-Fi 直连（最终 device 构建）

自动审批允许重新提交的**缩小范围**方案：仅手机已核实 IPv4 `/32` → NAS 单个内网地址 TCP8080，runtime firewalld rich rule 设置 `--timeout=1200`；临时 systemd 单元 `RuntimeMaxSec=1200`，监听仅用进程环境覆盖，不改持久配置，不开机启用，不写 permanent 防火墙。原持久部署拒绝没有被绕过。

两个真机入口共 189.814 秒通过：13 文件的编码/容器矩阵与 16 样本 × 三轮受控缓存测量。这是手机 Wi-Fi 直接访问 NAS，USB 仅传递测试控制；媒体字节不走 ADB reverse / SSH 转发。Android 非调试 device APK 和 Linux 二进制与最终回环回归一致。

| Wi-Fi 场景 p95（毫秒，每轮 n=16） | 轮次 1 | 轮次 2 | 轮次 3 |
| --- | ---: | ---: | ---: |
| 冷首帧 | 143 | 165 | 162 |
| 磁盘复用首帧 | 143 | 101 | 102 |
| 邻居预取首帧 | 219 | 238 | 230 |
| 冷 seek READY | 936 | 895 | 905 |
| 预取 seek READY | 990 | 984 | 962 |

所有轮次在冻结门槛内。缓存范围与回环测量一致，源文件短片、Linux 页缓存已暖；不代表真实大库 I/O、HDR 或更长 GOP。Wi-Fi 与回环测试是顺序执行且网络不同，不能用首帧差值推断代码优化收益。

原始事件同时含前一个 runner 的日志。分析明确只取本轮 PID 的记录，每组必须恰好 16 个，否则拒绝汇总；首次未隔离 PID 的分析因 32 个样本断言失败，没有产生冒充通过的报告。有效结果为 `test-results/final-temporary-lan-controlled-summary.json`，原始 events 不改写。

finally 已停止自己的 LAN 单元、移除自己添加的 runtime 规则、重新启动回环服务，另外 14.238 秒矩阵测试验证恢复通过。随后只读检查：loopback active、LAN inactive、runtime/permanent 精准规则均为 no。操作日志与核对结果位于 `test-results/linux/temporary-lan-control.log`、`final-lan-cleanup.txt`。没有留下长期网络暴露。


## 最终裁剪 profile：相册帧测量

使用最终 profile APK（SHA-256 `1baca86830e4c63c183197e9fe7ad8948b58d201efea5f1dd6fb782575aec9d0`，5,325,476 字节）。生产进程内不加载 AndroidJUnit。主机调用 Perfetto，以官方 v58.2 trace processor 提取 MainActivity UI layer；预期帧按 token 关联以适配 OEM 的 SurfaceView 标签。原始 trace、查询、匹配样本和解析日志均保存。匹配率每轮超过 95%，缺失匹配另报，不当作零超时帧。

相册为 2,000 张合成 JPEG，服务器缩略图已就绪，Linux OS 缓存已暖；客户端冷场景每轮在设置中清理媒体缓存，回到相册后等待 3 秒再滚动，主要测新进入视口的冷缩略图，不包含最初进入相册的加载时间。暖场景先执行同一组 12 正向 + 12 反向手势，再各测三轮。操作脚本为 `scripts/measure-device-frames.py`，冷清理过程保留连接与浏览锚点。

| 条件 / 轮次 | 有效匹配帧 | 正超时比例 | 超时 p95 / p99 / max ms | UI 提交间隔 p50 / p95 / max ms |
| --- | ---: | ---: | --- | --- |
| 暖 / 1 | 1221 | 0.0819% | -5.102 / -4.290 / 12.511 | 8.344 / 8.586 / 39.045 |
| 暖 / 2 | 1233 | 0.0811% | -4.526 / -3.315 / 11.098 | 8.343 / 8.602 / 35.977 |
| 暖 / 3 | 1243 | 0.0000% | -5.388 / -4.535 / -2.086 | 8.344 / 8.596 / 20.361 |
| 冷 / 1 | 1244 | 0.3215% | -4.573 / -3.549 / 29.042 | 8.346 / 8.610 / 30.918 |
| 冷 / 2 | 1213 | 0.0000% | -4.756 / -3.884 / -1.172 | 8.349 / 8.621 / 25.190 |
| 冷 / 3 | 1248 | 0.0000% | -4.921 / -3.965 / -2.231 | 8.342 / 8.614 / 25.136 |

UI 提交间隔中位数约 8.34 ms，对应实际 120 Hz；不能称 144 Hz。暖相册三轮均满足原 ≤1% 门槛，也未见 >100 ms 的 UI 提交间隔。冷相册单独报告，最差一轮 0.3215%，没有混入暖门槛通过率。第三轮冷 trace 有一个不完整 actual slice，明确计入缺失项，1248 有效匹配帧中零正超时；原始 trace 不丢弃。


## 最终裁剪 profile：全屏视频 UI 翻页

同一最终裁剪 APK，13 个根目录视频，先做 12 正向 / 12 反向预热，再测三轮同组手势。不混合视频源帧率与 UI 提交帧率，不把封面出现当首帧。PlayerView/Surface 属于一次播放会话，分页只切当前媒体。

| 轮次 | 有效匹配帧 | 正超时比例 | 超时 p95 / p99 / max ms | UI 提交间隔 p50 / p95 / max ms |
| --- | ---: | ---: | --- | --- |
| 1 | 1155 | 0.6061% | -4.971 / -4.306 / 22.674 | 8.344 / 8.652 / 25.210 |
| 2 | 1159 | 0.6040% | -4.974 / -4.536 / 24.140 | 8.354 / 8.659 / 25.391 |
| 3 | 1233 | 0.3244% | -5.283 / -5.037 / 13.034 | 8.351 / 8.621 / 25.214 |

三轮实际 UI 约 120 Hz，最差提交间隔不到 26 ms，没有 >100 ms UI 停顿。原暖相册 ≤1% 门槛没有扩大套用到全屏；此处逐轮报告独立结果。第一轮一条不完整 actual slice 单独标记。旧全屏构建 3.02% / 10.63% / 3.98% 的原始结果仍保留，不能以测试缓存/构建不同的百分比差断言某一单项改动的因果收益。原始记录 `test-results/final-profile-fullscreen/`。

帧数据有效性补充：九份最终 trace 的 expected token 对应时间戳/截止时长均无冲突，记录 `test-results/final-frame-expected-deadline-check.json`。trace processor stats 未报 ring overwrite 或 lost 计数；其中暖 1、冷 1/2/3、全屏 1 仍分别报告 1/2/1/4/1 个负时间戳丢弃错误，其他四份无该错误。不能称所有原始 trace 零解析异常。交互 marker 完整，报告只统计可有效关联的 UI 帧，缺失数量已保留；各 trace 的 `.stats.csv` / `.stats.parse.txt` 可复查。


## ctime 版本边界回归（源码修复，运行服务尚未切换）

独立合成文件确实复现保留 inode/尺寸/mtime 的原地改写：旧版本 Range URL 返回 206 与新 B 字节；在途 128 KiB 响应未中止。两个失败原样保存在 `test-results/preserved-mtime-before.log`。这不是抽象风险。

指纹新增 ctime 秒/纳秒与 `f2` 格式标识；Linux/Darwin 使用各自实际 stat 字段。ctime 只用于检测源状态变化，**不是拍摄时间，也不参与时间排序**。现有读取前后 stat 版本检查无需另开文件或读全文件；每次属性处理仍 O(1)，无额外文件系统调用，字符串仅增加固定长度。

修复后的两个 Mac 行为测试通过：旧 URL 即使保留 mtime 也立即 409；健康复扫保留媒体 ID、产生新版本，新 Range/ETag 只返回新字节，未变复扫不再造新版本；在途改写只保留原 A 前缀并 Abort，不返回新 B 块。通过日志 `preserved-mtime-after.log`。正在运行的 Android 30 分钟 profile 测试不切换二进制，Linux 与全量检查、实际切换和受影响播放回归完成后另记。

升级旧指纹不猜测原字节是否未变：旧格式在新程序中先拒绝字节读取，启动健康扫描为新格式创建版本，再重新探测/封面。会有一次版本缓存失效和后台重建成本；库 ID、浏览锚点和原文件不改写。这里是文件系统元数据版本守卫，依赖其正确提供变化时间，不能冒充内容哈希。

ctime 修复的适用检查已完成：Mac 全量 race / vet 通过；Linux mediafs、probe、scanner、端到端行为四组均通过，两项保留 mtime 覆盖测试在实际 Linux 上通过。普通调用因缺少显式条件跳过 configured-root / 大规模用例，不把它们算通过；随后另行核实实际媒体根在普通环境和临时 read-only systemd 沙箱下身份一致，两个 configured-root 用例通过，`ctime-readonly-root-identity.log`。

Linux 10,000 个独立小文件、并发2：首次 1.492315497秒、未变 1.016384545秒、单文件变化 2.086714940秒；新版本数 10000/0/1，最大事务 17.630592ms，RSS峰值33244KiB（32.46MiB）。这是本次ctime指纹受影响的扫描回归；文件是合成小载荷、不含探测/抽帧，不能代替真实媒体建库。旧10万扫描/51万SQL结果保留其原构建和范围，不偷换为此二进制重新执行。


## 最终裁剪 profile 长期真实 UI / 内存

最终安卓 profile APK SHA-256 `1baca86830e4c63c183197e9fe7ad8948b58d201efea5f1dd6fb782575aec9d0`，通过主机操作真实应用，生产进程内不挂 AndroidJUnit。先做147.7秒、一个完整周期的脚本验证，再执行正式混合场景：2,000图片相册12次正反滑动、根13视频12次正反切换、3个4K视频2次正反切换，反复往返。脚本每次界面读取用唯一临时XML并清理，失败不能复用旧屏幕。

正式驱动1947.9秒（32.46分钟）、13个完整周期通过。内存采样为其中前1800秒，10秒一次，共180个有效样本；两比较窗口各30个。额外收尾周期不冒充有完整内存采样。进程始终一致，390次匹配媒体首帧日志来自同一PID，原始事件未见播放错误、AndroidRuntime/ANR/OOM；界面每个 settled 转换亦观察播放错误，全部周期通过。单播放器/版本/释放仍由独立功能测试确认，不把外部脚本冒充相同断言。

| 最终 profile 内存项 | 实测 | 原门槛 |
| --- | ---: | ---: |
| PSS峰值 | 318.453125 MiB | ≤512 MiB |
| 5–10分钟中位数 | 181.88330078125 MiB | 基线 |
| 25–30分钟中位数 | 184.47314453125 MiB | 比较 |
| 两窗口增长 | 2.58984375 MiB | ≤25 MiB |
| Java PSS范围 | 8.64–62.19 MiB | 单独报告 |
| Native PSS范围 | 21.27–23.77 MiB | 单独报告 |
| Graphics PSS范围 | 92.24–239.00 MiB | 单独报告 |
| 电池温度 | 30.1–30.5°C | 环境条件 |

每5分钟30点的PSS中位数依次182.01/181.88/181.10/182.48/182.71/184.47 MiB，原始轨迹随阶段回落，未呈持续单调上涨。通过现有数值门槛不等于证明绝无泄漏或全天使用已验收；不隐藏此前旧构建的稀疏采样69.62MiB失败及同构建密集重复通过。没有强制GC、没有改变系统冻结/省电策略，没有放宽门槛。

本轮媒体是只读合成与公开视频，USB/SSH回环；服务器在整个工作负载期间仍是`b867067...`，ctime修复只在结束后切换。因此此记录证明最终安卓资源与UI生命周期的30分钟条件，**不是新ctime服务器的长时数据**。新服务器的原字节/版本/扫描与三轮受影响播放另行复测。原始数据、脚本/构建摘要、周期、退出码位于 `test-results/final-profile-soak/`。

完整周期和内存组件均exit0后才一致备份测试SQLite和旧二进制、切换新Go程序。新Linux SHA-256 `26b8b36bd25ef9a2c8e10dd52546dfea460f9f50ab2a1513a23782b728a228bd`，私有配置、监听与防火墙未改；已有库ID保留。升级后的实际元数据/封面就绪和真机回归结果待下条记录，不将程序启动成功当完整重建完成。


## 最终照片原图缩放与返回

非调试 device 目标与匹配测试 APK，在实际 iQOO 上执行 `PhotoDeviceTest`，13.337秒、1项通过。900×600的独立合成横图，截图采样的彩色像素从86,365增加到192,240，再缩回110,484；同时验证媒体详情的文件名、排序时间来源及返回原相册。测试只读取截图和授权合成源。

保留两个失败记录：第一轮600×900竖图已填满统计区域，面积增长判据不适用；第二轮横图放大通过，但按节点比例生成的缩小手势未使可见面积缩小。该结果不能单独证明产品缩放失败，触点/注入差异的根因没有独立测定。固定两指触点均在屏内后，放大与缩小均通过，门槛未放宽，没有因此修改产品照片页面。日志分别为 `photo-zoom-invalid-portrait.log`、`photo-zoom-node-bounds-failure.log` 和 `final-photo-device.log` / `final-photo-device-events.log`。产品 APK 未变，最终 profile SHA-256仍为`1baca86830e4c63c183197e9fe7ad8948b58d201efea5f1dd6fb782575aec9d0`。


## 最终 ctime 服务完整真机功能

最终Go二进制`26b8b36...`、device APK`d3eed92...`及匹配测试 APK上，完整`DevicePlaybackTest`297.733秒通过：13项实际执行，2项长时入口因未传显式时长而跳过。含编码矩阵/seek/缓存清理、420项双向翻页、单播放器/版本、取消和重组前路由销毁、无效连接及不支持编码后恢复。原始完整状态`final-ctime-all.log`；JUnit报告15入口，不把两跳过项当通过。

本次事后筛选日志为空，不能凭JUnit成功虚构首帧分布。只读诊断确认主缓冲也没有对应指标，准确原因没有独立测定；不改变手机日志/安全策略。诊断不保留其他应用的原始日志。接下来独立三轮用开始前开启、结束后停止的实时应用日志采集补齐性能数据，完整功能结果无需重跑冒充性能测量。空日志和诊断摘要保留，新的独立测量范围另记。


## 最终版本日志补测中的超门槛与采集修正

实时日志版回环补测`final-ctime-controlled-live`与第一次最终ctime Wi-Fi补测`final-ctime-temporary-lan`各只保留239/240条指标，完整性检查失败，不能将缺行组记为验收通过。可完整核实的失败也保留：回环第二轮HEVC/MOV冷首帧1122ms（门槛750）；Wi-Fi第三轮HEVC Main10 4K冷seek1016ms、预取seek1065ms（门槛1000）。JUnit成功仅证明用例行为断言，不能代替这些数值门槛。

测量改为测试结束后通过标准`Instrumentation.addResults`返回完整有界JSON；240条默认数据在计时之后一次序列化并通过IPC回传，不落入可能漏行的Logcat。仅修改androidTest，产品代码、媒体、缓存/缓冲预算、精确seek和原阈值不变。默认仍16样本×3轮×3缓存场景；额外诊断须明确指定最多2样本、最多30轮、单个缓存场景，不把诊断替换默认验收。

只读ffprobe核实代表性样本：H.264 4K/HEVC Main10 4K的8秒文件均只有0秒关键帧；AV1关键帧在0/5.366667秒。HEVC含B帧。长依赖链是精确seek的重要变量，不能仅凭Range索引承诺任意源1秒以内，也不能在未做阶段追踪时认定全部耗时来自解码。已开启API34+ decode-only输出提示，它仍需要更新解码依赖状态（[Android MediaCodec参考](https://developer.android.com/reference/android/media/MediaCodec)）。没有改成关键帧跳转、重编码夹具或放宽门槛。原始关键帧数据`linux/representative-keyframes.json`。


## 长GOP拖动定位与候选修复

完整IPC回传的基线：回环三轮240条齐全，各原门槛通过；Wi-Fi三轮也240条齐全，预取第二轮HEVC 4K seek为1005ms，因此不能算全通过。原始日志/完整汇总`final-ctime-durable-controlled`与`final-ctime-durable-lan`。额外30次Wi-Fi HEVC 4K冷seek p50/p95/max=956/1065/1074ms，8/30超1000；不是孤立异常。MOV编码往返各30次，H.264/HEVC冷首帧p95=127/135ms、最大132/166，未复现此前1122ms，该单次仍保留且原因未确定。

同一Go/安卓基线的回环对照：HEVC 4K冷seek30次p50/p95/max=936/970/979ms；完整磁盘缓存30次=839/934/994ms，每次有`cached_bytes_before_seek>=source_size`证明，不用固定等待冒充全缓存。先后执行并非随机交错实验，不能据这些差异把全部耗时归因于网络或解码。原始完整数据`final-ctime-full-cache-comparison.json`。

读取调度4MiB检查点实验30次Wi-Fi seek=935/1051/1103ms，2/30超门槛，仍未达标，**已恢复原DefaultMediaSourceFactory与原加载检查策略**；未把这一候选留入产品。源码、构建和结果保存在`apk-loading-checkpoint-candidate/`与`loading-checkpoint-experiment.json`。

接着测试单播放器内的解码资源提示：仅硬件HEVC、至少4K像素数、Media3已知正常处理率且解码器声明支持更高速率时，启动/位置重置时请求不超过120fps的处理资源提示；未知率保持原默认。首个目标输出、暂停/停用或渲染检查达到2秒边界后恢复正常处理率。该数值是处理资源提示，实际播放速度、音调、原字节、精确seek、RAM/磁盘预算不改；不设置手机全局性能/省电政策。用途与维度经[Android MediaFormat官方说明](https://developer.android.com/reference/android/media/MediaFormat#KEY_OPERATING_RATE)核实：operating-rate用于资源规划和工作点，frame-rate在未提供operating-rate且实时优先级时也用于默认处理需求。这里只请求暂时处理余量，不改变媒体时间戳。

初始候选30次同条件Wi-Fi：HEVC 4K冷seek p50/p95/max=832/866/874ms，0/30超1000；冷首帧=123/160/165ms。实际日志确认正常处理率30、请求120。SDK/Media3接口及声明能力来自安装的1.8.1库和平台API；请求不等于保证实际处理达到120fps。初始候选已有单元/lint/构建、30次与恢复后编码矩阵，**最终候选的完整功能、默认三轮、裁剪profile帧/长期资源验证正在进行**。不以这一局部成功代替整体验收。


## codec候选最终功能及完整回环三轮

最终device APK SHA-256 `165ea4d6c602d5367127c5428d08fbf7ff10862cf9cc4ce8375de3d25ababf6b`，完整15入口报告成功，实际13项功能执行、2项长时入口按显式时长契约跳过。照片另1项执行成功，可见彩色像素86365→192240→110847，并核实详情与返回。完整默认IPC JSON共240项，15组各16个不同样本，原门槛全部通过；冷首帧三轮p95=212/234/228ms，磁盘114/107/137ms，预取231/277/268ms；冷seek=886/876/874ms，预取seek=874/891/842ms。每次seek计时后断言速度和音调均1.0。原始与统计为`final-codec-burst-all.log`、`-events.log`、`final-codec-burst-controlled-summary.json`、`-gate-status.json`及`final-codec-burst-photo*.log`。

对应裁剪profile SHA-256 `f6710bbf56805a5c97f6cc9c6ce3476180f31e39186e7e1c77f6b711486c0c76`，构建摘要`final-codec-burst-builds.json`。Wi-Fi默认三轮、额外30次HEVC 4K和每种MOV30次、该profile帧与30分钟资源测试尚待完成。旧profile `1baca868...`的成功只作为历史数据。


同一最终device/Go的临时Wi-Fi默认矩阵与三轮已完成，完整IPC JSON240项、各组16不同样本全部通过。冷首帧p95=203/145/157ms，磁盘109/121/105ms，预取259/218/251ms；冷seek=833/841/793ms，预取seek=860/817/868ms。媒体字节通过手机Wi-Fi直连NAS，USB仅控制；`final-codec-burst-lan-tests.log`及`-controlled-summary.json`、`-gate-status.json`保存全部结果。额外HEVC4K/MOV各30次和临时网络清理尚在进行；裁剪profile资源验收待后续记录。


最终额外Wi-Fi诊断各30次：HEVC Main10 4K冷首帧p50/p95/max=115/149/150ms，精确seek=824/870/883ms，0/30超1000；H264/MOV首帧106/145/154ms、seek62/94/159ms，HEVC/MOV首帧112/148/151ms、seek64/94/98ms，没有复现旧1122ms首帧。保留所有样本，p95按nearest-rank第29项。诊断不替代默认三轮。结束后临时LAN单元与本次规则移除，恢复回环编码矩阵成功；`final-codec-burst-loopback-restore.log`、`linux/final-codec-burst-lan-control.log`与`final-codec-burst-lan-diagnostics-summary.json`记录对应范围。


## codec最终裁剪profile帧与采集健康

APK `f6710bbf...`、最终Go `26b8b36...`，USB/SSH回环。暖相册先24次手势预热，随后三轮；冷相册每轮通过设置实际清理媒体缓存，服务器OS与封面已暖，回到相册等待3秒后测新进入视口内容。普通全屏13根视频每轮12正/12反手势；4K三个视频使用`--bounce-size 2`反复2正/2反，总数仍24，不在列表末端空划。每条件三轮保留全部数据。

| 条件 / 轮 | 有效帧 | 超deadline % | overrun p95 / max ms | UI间隔中位 / max ms |
| --- | ---: | ---: | --- | --- |
| cold-album-1/round-1 | 1185 | 0.000000 | -5.875 / -3.608 | 8.347 / 24.896 |
| cold-album-2/round-1 | 1209 | 0.000000 | -5.758 / -3.636 | 8.348 / 24.952 |
| cold-album-3/round-1 | 1243 | 0.080451 | -5.569 / 0.428 | 8.346 / 24.625 |
| fullscreen/round-1 | 1237 | 0.404204 | -7.881 / 25.341 | 8.345 / 24.928 |
| fullscreen/round-2 | 1203 | 0.581879 | -5.237 / 24.829 | 8.346 / 25.009 |
| fullscreen/round-3 | 1259 | 0.000000 | -5.284 / -1.084 | 8.346 / 25.241 |
| fullscreen-4k/round-1 | 1267 | 0.000000 | -5.355 / -4.713 | 8.343 / 24.923 |
| fullscreen-4k/round-2 | 1240 | 0.000000 | -8.044 / -0.396 | 8.345 / 25.043 |
| fullscreen-4k/round-3 | 1251 | 0.000000 | -8.103 / -7.142 | 8.341 / 25.049 |
| warm-album/round-1 | 1197 | 0.083542 | -5.706 / 4.228 | 8.344 / 24.866 |
| warm-album/round-2 | 1203 | 0.000000 | -5.635 / -3.173 | 8.342 / 33.981 |
| warm-album/round-3 | 1195 | 0.083682 | -5.232 / 4.401 | 8.345 / 24.902 |

暖相册三轮均≤1%，无超过100ms的UI间隔，原门槛通过；约8.34ms实际UI提交节奏对应120Hz，不宣称144Hz。其他场景单列不套暖相册门槛。全部12份expected token的timestamp/deadline无冲突，匹配覆盖率均高于97%；丢失匹配保留而不当零超时。trace stats未报覆盖/lost计数，仍有负时间戳丢弃：暖1=1；冷1/2/3=1/2/1；普通全屏2=1；4K全屏1=2。普通全屏2有1个未完成actual slice，4K全屏1有2个；不声称原始trace全无解析异常。完整trace、SQL、CSV、stats和解析日志位于`test-results/final-codec-burst-profile-{warm-album,cold-album-1,cold-album-2,cold-album-3,fullscreen,fullscreen-4k}/`，汇总`final-codec-burst-profile-frame-analysis.json`。

随后开始同一最终Go/裁剪profile至少30分钟真实混合浏览，稳定进程内每10秒测量，不使用in-process JUnit、不强制GC；完整结果待下条记录。


## codec最终裁剪profile与最终Go：长期交付验收

同一最终APK `f6710bbf56805a5c97f6cc9c6ce3476180f31e39186e7e1c77f6b711486c0c76` 与Go `26b8b36bd25ef9a2c8e10dd52546dfea460f9f50ab2a1513a23782b728a228bd`，USB/SSH回环、合成目录，整个工作负载未换二进制。真实界面循环1943.826秒（32.40分钟）、13个完整周期；相册/普通视频/三个4K视频往返，浏览与内存两个组件均exit0。生产进程内无JUnit，未强制GC，未改变系统冻结/省电/高刷策略。

首1800秒每10秒采样，180个有效点、固定PID25997；5–10与25–30分钟均完整30点。PSS采样峰值295.315MiB≤512；两个窗口中位181.805/181.428MiB，增长−0.377MiB≤25。Java PSS8.28–61.51、native20.28–21.90、graphics93.10–224.20MiB；电池温度31.0–31.7°C。六个5分钟窗口中位依次183.270/181.805/180.898/181.299/180.194/181.428MiB，97次相邻采样回落，没有持续单调上涨。不能把采样峰值视为每一瞬间的内存上限，也不承诺全天使用或绝无泄漏。

连续过滤日志共390次首帧、26次4K HEVC处理率请求，无崩溃/ANR/OOM条目；驱动器在观察点未见播放错误，不能等同监听每个解码回调。最后约2.4分钟完成当前循环但不在1800秒内存窗口中。完整原始转储、周期、连续日志、APK/脚本摘要、退出状态与分析保存在`test-results/final-codec-burst-profile-soak/`。

最终核实2457项当前媒体均metadata/cover就绪、f2版本，任务0，SQLite quick_check=ok，库healthy。临时LAN单元inactive，精确本次runtime/permanent规则均不存在，回环测试服务active；没有启用开机服务。手机安装APK哈希与上述profile一致，非调试；OEM package dump未输出profileable字段，因此另从哈希对应APK直接用aapt2核实`profileable android:shell=true`。应用前台、PID仍25997。用户最新授权要求保持开启，实际接电唤醒值15保留；安装SDK常量核实AC=1、USB=2、无线=4、dock=8，不擅自改回早期计划中的值。

交付汇总脚本原先误把周期日志最后的完成摘要行当作含PID的周期，以及假定OEM dump一定列出profileable标志，已依据真实格式修正；产品代码、构建和原始测量未因此变化。设备/服务核验分别为`final-codec-burst-device-verification.json`、`final-codec-burst-profile-apk-manifest.txt`、`linux/final-ctime-runtime-verification.json`。

本次限定条件通过仍不替代用户约13万视频/38万图片的实际文件库、真实SMB/NFS故障/重挂载、144Hz、HDR及所有编码profile/音轨验证。Go全量Mac race/vet、Linux受影响行为回归及早先合成规模结果的具体范围保留在上文。OCR因工作区非Git仓库未执行；常驻局域网/开机部署被自动审批拒绝，待具体持久开放范围确认，见[部署说明](DEPLOYMENT.md)。

拖动指标的解释保持冻结基线：虽然调用精确seek，计时终点是READY且当前位置达到目标附近，并非目标视频帧真正可见；首帧则由onRenderedFirstFrame记录。不得把这两个终点混为同一可见画面指标。


## 2026-10-09：初始化SQL内置于storage

按用户要求删除独立的迁移目录及资源包；初始化SQL的唯一源码移至`internal/storage/schema.go`，`Store.Open`直接执行包内常量。SQL原字节SHA-256仍为`4f11478f1c6e4fc46d117c523fa595c1152691461dc82eaca0044f83fd200497`，schema版本1、全部表/索引、初始化事务、版本拒绝及已有库重开逻辑未变。没有更改扫描/查询/任务算法、事务边界或安卓路径，不因此重复无关的帧与30分钟测试。README、开发说明、项目目录例外和计划引用同步；通用目录基线保留。

Go1.27.1：全量`go test -race -count=1 ./...`、`go vet ./...`、gofmt检查通过；无迁移资源、构建产物、配置实例或数据库的干净源码副本交叉构建Linux/amd64成功；最终源码的Linux存储5项行为测试通过，含初始化、WAL/FULL/只读池、事务回滚、取消、快照及数据库重开/所有权。SQL内容逐字比较相同，运行路径不再引用独立迁移包。日志及新二进制摘要位于`test-results/builtin-schema/verification.json`与该目录日志。Linux正在运行的临时服务未更换，数据库及用户媒体未改动；新二进制仅构建验证，旧运行版本仍不依赖外部SQL。

首次race/vet被本次源码备份目录干扰：Go将备份识别成包，其旧import失败；已将备份改名为Go跳过的`_before`，保留首次失败日志并重新完整检查，最终exit0。重新读取open-code-review技能并准备本次背景；工作区仍非Git仓库（exit128），按项目约定OCR未执行，不将行为验证冒充审查。


## 2026-10-09：安卓 UI 改版交付

按 [UI 方案](plans/2026-10-09-android-ui-redesign.md) 实施文件夹优先的纵向页面、完整浅深绿色主题、稳定媒体锚点、时长标签、精简播放器与照片错误/重试、真实连接验证及缓存用量状态。两倍字号、横屏和深色界面已检查；系统字体、旋转与主题测试后恢复原值。冷启动用 `am force-stop` 确认进程消失后重新启动，恢复源目录且不自动播放；OEM 上 `am kill` 未成功终止，不能把此结果当作系统内存回收场景已验收。

子目录客户端最多保留三页/300项，服务端以已有 `(parent_id,name,id)` 索引做前后 keyset、每次最多101行探测边界，无 OFFSET/全量加载/写事务。新增目录祖先路径 API 为 O(深度)、最多1024层，并检查可见性与授权，不返回私有路径。旧服务器缺少反向游标时显示更新提示，不静默丢入口。新接口源码和 Linux/amd64 构建已完成；远端尚未更新。

交付 profile APK SHA-256：`f1e9cd367818ccb3200b660d19a9b68e0cf4eea7307ae32e4c6335586664e9ca`；已安装并从设备校验一致。匹配 device APK 为 `c0896a006173a1e13b9032e96d7cd7a8e448f9c151d60b046bebedd207c8b310`。profile 为开发签名、非调试且裁剪；行为测试在匹配 device/test APK 上执行，不混称正式签名 release。

- Android app 8项、media 3项 JVM 测试通过；最终构建/应用 lint 通过（0 errors、32 warnings，未升级依赖或压制基线）。
- 最终 device 串行5项通过：450子目录有界前后导航及系统返回、实际视频UI拖动/正反翻页、照片缩放/详情/返回、无效连接保留、深层相册恢复。更早资源用例另覆盖单播放器释放、快速切换、三个缓存配额与清理；不把早期包结果冒充最终包重跑。
- Go 本次 children/path/分组恢复行为测试、app/library vet 及 Linux交叉构建通过。101/200/450子目录前后遍历无丢失；HTTP路径验证认证、不可见目录和隐私；正反向查询计划均使用现有覆盖索引，无临时排序。执行环境为 Mac开发适配，不声称新接口已在 Linux/NAS 跑过。
- 先前一次仪器测试因主机错误地重叠启动测试而被中断，随后串行重跑通过；一次长测预跑仍按根目录寻找视频，实际夹具已在 `feed`，修正脚本参数后重跑。原日志保留。

### 下载的真实国外视频

两个文件均来自 Wikimedia Commons，保持原字节、仅改本地文件名，不写入用户媒体库：

| 文件 | 作者与来源 | 许可 | 实际编码 |
| --- | --- | --- | --- |
| murchison-falls.webm | BMKME：[V8.webm](https://commons.wikimedia.org/wiki/File:V8.webm) | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) | 1080p VP9/Opus |
| nile-falls.webm | Chapelle musa：[The Murchision Falls on River Nile](https://commons.wikimedia.org/wiki/File:The_Murchision_Falls_on_River_Nile.webm) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | 1080p VP9/Vorbis |

用户提供代理的 CONNECT 中止，随后直连 HTTPS 下载成功。来源、许可、摘要与 ffprobe 原始结果保存在被忽略的 `test-results/ui-redesign/media-sources.json`，文件位于其 `media/`。两片在连接手机上实际播放；三次冷首帧分别183/138/203ms与183/210/217ms；seek READY分别126/148/126ms与987/1020/983ms。第二片原编码矩阵的800ms等待断言失败，诊断也有一次超过产品1000ms目标，**不算全部性能通过**，没有放宽阈值、重编码样本或改成关键帧跳转。READY不等同目标帧实际可见。这些诊断属于早于最终视觉微调的device构建，最终包另有真实UI行为回归，不能将六条诊断冒充最终包完整编码性能矩阵。

### 条件、审查与未完成项

使用已连接 iQOO 12 Pro / Android15；帧采集实际120Hz，未验证144Hz。新接口夹具和下载视频通过 Mac本地19090/USB reverse，既有相册/4K性能夹具经既有18080/SSH回环Linux服务；不是最终配套服务端的直接Wi-Fi/NAS验收。未扫描用户约13万视频/38万图片真实库。

按 open-code-review 技能实际执行 OCR，exit1：工作区不是Git仓库，`ocr.log`保留完整原因。没有初始化Git掩盖限制，测试和人工检查不能代替OCR。远程新二进制上传被自动审批拒绝，原因是未获远端上传/部署的明确授权；已提出具体范围授权，尚未执行上传或改动远端服务。

原始记录位于被忽略的 `test-results/ui-redesign/`，含构建、设备测试、截图、媒体与摘要。`delivery-*`用于最终包；其他候选帧测量仅供历史对照，早期误安装旧包的记录被排除，不用于当前验收。


### UI改版最终 profile：持续浏览与内存

上述 `f1e9cd36...` 已安装摘要核实一致，真实界面反复浏览既有 `large` 相册、`feed` 普通视频及 `representative` 三个4K视频。主机驱动1898.086秒（31.63分钟）、12个完整周期，driver/memory均exit0，所有观察点未见播放错误，整个测试进程一致。生产进程未挂JUnit、未强制GC，没有更改省电/高刷政策。每周期相册12正/12反手势、普通视频12正/12反、4K视频2正/2反；最后收尾98秒不计入内存窗口。

30分钟每10秒一次，共180个有效样本，5–10与25–30分钟各30点。PSS采样峰值280.351MiB≤512；基线中位168.036MiB、后段171.704MiB，增长3.668MiB≤25，数值门槛通过。六个5分钟中位依次168.794/168.036/170.411/170.910/169.921/171.704MiB，104次相邻采样回落，未见持续单调上涨。Java PSS7.49–87.60、native21.90–23.57、graphics88.40–200.30MiB；电池温度33.7–34.2°C。

这不是每一瞬间的峰值，也不证明绝无泄漏或全天使用已验收。没有本轮全程独立Logcat采集，因此不声称日志中零ANR/解码错误；进程稳定和UI观察结论只覆盖脚本检查点。原始meminfo/battery、样本、趋势、循环与退出状态在 `test-results/ui-redesign/delivery-memory/`、`delivery-cycles.jsonl`、`delivery-soak-status.json`。旧Linux服务及USB/SSH回环条件保持不变，未在运行中切换服务端。


### UI改版最终 profile：帧表现仍未通过

紧接上述31.63分钟长测，在同一已安装APK及进程上采集。暖相册预热24次手势后测三轮；冷相册每轮通过设置实际清缓存，服务端封面/OS仍暖；三个4K视频每轮2正/2反反复，总24手势。以下为全部结果，不挑选最好轮次。

| 条件/轮 | 有效帧 | 超deadline % | overrun p95/max ms | UI间隔中位/max ms |
| --- | ---: | ---: | --- | --- |
| delivery-frames-cold-1/1 | 1246 | 8.427 | 3.828/7.347 | 8.351/24.272 |
| delivery-frames-cold-2/1 | 1259 | 5.242 | 3.603/4.926 | 8.342/24.852 |
| delivery-frames-cold-3/1 | 1239 | 12.187 | 3.913/5.032 | 8.344/24.861 |
| delivery-frames-video/1 | 1255 | 8.606 | 2.874/11.247 | 8.348/24.811 |
| delivery-frames-video/2 | 1265 | 59.051 | 3.207/11.468 | 8.345/24.949 |
| delivery-frames-video/3 | 1264 | 83.070 | 3.193/11.336 | 8.346/25.048 |
| delivery-frames-warm/1 | 1229 | 4.231 | -3.668/6.402 | 8.343/24.884 |
| delivery-frames-warm/2 | 1248 | 0.080 | -4.124/2.371 | 8.340/24.838 |
| delivery-frames-warm/3 | 1218 | 0.328 | -4.194/4.259 | 8.346/25.039 |

暖相册第一轮4.231%高于1%门槛，**本轮流畅度验收不通过**；冷加载和全屏另外报告，不能用其约120Hz提交节奏或约25ms最大UI间隔掩盖deadline超限。4K第二轮trace中大量Buffer Stuffing，诊断未发现expected token起点/截止时间冲突；尚未证实具体根因，不能直接归因于应用计算、网络或热节流。只读thermal快照系统状态0，当前HAL与历史cached读数不同，不将历史缓存温度当当前实测。没有改变性能策略或放宽指标。

全部trace、解析CSV/SQL、匹配覆盖、未完成slice与健康诊断保留在 `delivery-frames-*` / `delivery-frame-health.json`；4K第三轮有1个不完整actual slice，未当成功帧。最终包未另做普通视频三轮帧采集，普通视频已覆盖31.63分钟浏览。当前功能与内存结果不替代流畅度通过，仍需针对帧队列超限及真实WebM seek继续定位。

帧健康诊断：九份trace的expected token均无起点/截止时间冲突；负时间戳丢弃为冷2=2、4K1=2、4K3=1、暖3=1，其余0。未把这些解析异常写成零异常。源码备份保留在 `test-results/ui-redesign/_before/`，下划线避免被后续Go全包命令当源码。

收尾核实：手机安装包仍与交付profile摘要一致；字号1.0、旋转0，回到可见文件夹入口。仅移除本次19090 ADB reverse，既有18080/19323转发保留，本次Mac临时服务已结束。下载样本和报告保留在本地；远端原服务及用户媒体未修改。


## 2026-10-10 频道式 Android UI 重设计（实现完成，性能部分未通过）

本轮按 `docs/plans/2026-10-10-android-ui-telegram-style.md` 实施 P0、P1 最近浏览/主题/标签和查看器降级动画，过程、文件、失败原文与完整手动清单见 [实施记录](plans/2026-10-10-android-ui-execution.md)。服务端、API、缓存/预取算法、依赖版本不变。

7 个阶段的 app/media 单元测试、Lint 和 Debug 构建均完成；最终 app 17 / media 3 JVM 测试通过，app Lint 0 error / 66 warning、media 0。Debug 真机覆盖 Room v1→v2、筛选书签、深层双向窗口、450 子目录、照片缩放、单播放器与速度/长按/双击/横屏；最后分页补充组合 `OK (4 tests)`，69.895 秒。完整失败/重试日志均保留，设备测试通过不等于性能通过。旧计划列出的 4 个 Lint 错误在开工时已修复，不计为本轮新增修复。

设备为 iQOO 12 Pro / V2329A、Android 15。仅使用已有本机合成媒体（1,020 张图片、15 个短视频，非完整 NAS / 4K 场景），经 ADB reverse 访问本机服务；封面已就绪。无真实媒体改写。字号 1.3× 的筛选、播放器、设置/存储检查后恢复 1.0。浅色与深色截图保留；完整 TalkBack、长文本与预测返回手势仍需补充人工验收。

Profile APK SHA-256：`6a9ef4404b1e8b77e4be9cfa1d6ef6617d0c88a065116a1218e7b132714fe02f`，5,597,988 字节，minified、非 debuggable、`profileable shell=true`。原始证据位于 `android/app/build/outputs/ui-redesign/`。89.9 秒冒烟循环完成后，于 20:54:47 开始第一轮主机混合运行；444.0 秒时 XML 读取失败导致驱动退出；随后核实时前台已为其他应用，现有日志无法证明切换与 XML 错误的因果关系，主动停止余下内存采样。这轮不计连续 30 分钟通过。已在每次触控前增加前台归属检查，恢复 VVideo 后以 `delivery-workload-2` / `delivery-memory-2` 重新计时并完成，完整结果见下文，不合并第一次中断的样本。

候选包此前发现并修复名称排序筛选导致的相册旧几何越界；旧 profile 内存仅采到 110 秒。另一个候选包暖相册三轮 21.038% / 15.130% / 59.737% 均超过 1% 门槛，数据保留；其未满 30 分钟内存不能用于长时通过。UI 提交约 120Hz 不代表帧截止时间通过。trace 中存在 Buffer Stuffing，部分伴随刷新周期变化，尚未证实根因；不借此排除失败样本或归咎厂商。定义核对 [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) 与 [AOSP Buffer Stuffing](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/gui/BufferStuffing.md)，当前 AOSP 描述不能证明目标 OEM 已启用同一恢复实现。

当前目录不是 Git 仓库（`fatal: not a git repository (or any of the parent directories): .git`，exit 128），按用户和 AGENTS 的例外没有初始化仓库，OCR 未执行，不能称审查通过。


### 本轮 profile 30 分钟内存

`delivery-memory-2` 完整采集 180 点，5–10 和 25–30 分钟均为 30 点；collector exit 0。相同 APK、相同进程，主机操作 1,000 图片相册 12 次正反滑动、根目录视频 6 次正反翻页并重复往返。未注入 AndroidJUnit，未强制 GC、未更改刷新率/省电策略。其前一次中断的数据独立保留，不合并。

| 指标 | 实测 | 既有门槛 |
| --- | --- | --- |
| PSS 采样峰值 | 297.733 MiB | ≤512 MiB |
| 5–10 分钟中位数 | 168.645 MiB | 基线 |
| 25–30 分钟中位数 | 171.651 MiB | 比较 |
| 增长 | 3.006 MiB | ≤25 MiB |
| Java / native / graphics PSS | 6.01–37.77 / 6.49–18.41 / 44.57–212.87 MiB | 分项观察 |
| 电池温度 | 33.5–33.7°C | 记录条件，不代表 SoC 温度 |

数值门槛通过。六个 5 分钟中位数依次为 168.57、168.65、171.43、171.16、171.37、171.65 MiB，95 次相邻采样回落，未见持续单调上升。采样峰值不是每一瞬间的硬上限，有限合成工作负载也不能证明长期绝无泄漏。帧表现与首帧场景另行报告。

配套主机混合循环完成 18 轮、1844.0 秒（30.73 分钟），driver exit 0，全部使用同一 PID。该时间段日志未见 VVideo crash/ANR/OOM；测试进程与主机状态均完成后再切换帧采集。


### 本轮独立帧采集

长时采样结束后采集，避免同时运行 meminfo。使用 `android/benchmark/tools/measure-ui-frames.py`，其前台保护在每次注入滑动前检查当前应用；帧公式、24 次滑动及门槛沿用原 collector。同一旧 trace 经包装入口重新分析后全部指标完全一致。手机所装原包摘要与 `6a9ef440...` 核对一致。

| 轮次 | 有效帧 | 超限比例 | overrun p95 / p99 / max (ms) | UI 间隔 p50 / p95 / max (ms) |
| --- | --- | --- | --- | --- |
| 暖相册 1 | 1367 | 27.359% | 4.295 / 12.368 / 20.876 | 8.345 / 8.655 / 25.646 |
| 暖相册 2 | 1370 | 32.044% | 12.353 / 12.722 / 23.660 | 8.343 / 8.951 / 37.832 |
| 暖相册 3 | 1281 | 42.701% | 12.252 / 14.882 / 29.078 | 8.346 / 8.906 / 28.764 |

三轮暖相册均超过 1% 门槛，**本轮暖滚动验收不通过**。约 8.34ms 的 UI 提交节奏不能替代 deadline 通过。继续保留 trace 并进行刷新率请求方式的小范围对照，不改系统高刷/省电设置。

视频使用根目录中的相邻三项反复翻页（bounce-size=2，共24次/轮），预热后三轮，不包含4K；与长时全目录往返覆盖范围分开。

| 轮次 | 有效帧 | 超限比例 | overrun p95 / p99 / max (ms) | UI 间隔 p50 / p95 / max (ms) |
| --- | --- | --- | --- | --- |
| 视频 1 | 1343 | 44.825% | 3.856 / 11.834 / 20.139 | 8.345 / 8.714 / 24.944 |
| 视频 2 | 1357 | 33.309% | 7.000 / 11.747 / 28.239 | 8.345 / 8.721 / 25.220 |
| 视频 3 | 1351 | 38.490% | 4.872 / 11.791 / 28.283 | 8.350 / 8.691 / 25.238 |


刷新率请求方式对照：仅把窗口 `preferredDisplayModeId` 请求换为 `preferredRefreshRate`，候选 APK `3ff2ab6d0dedcbcaf7ed6da58f365e16158b6d084ccefe2001c21bf7f112716b`，Lint/Profile 构建成功（39 秒）。暖相册三轮超限为 41.616% / 36.810% / 21.581%，均未过门槛，不能证明稳定收益。已恢复原源码及已完成长时验证的原 APK；该实验不作为已交付优化。未改系统设置、依赖、预加载、缓存或播放器生命周期。


### 最终原包清缓存后滚动

每轮通过可见存储页面与确认面板清理，确认图片、视频用量均为 0.0 KB，再进入合成 large 相册采集；未预热滚动。首屏进入时可见图片已可能开始加载，因此这是清磁盘缓存后的滚动，不等于冷启动或所有图片均未加载；服务端封面与 OS 缓存保持暖态。

| 轮次 | 有效帧 | 超限比例 | overrun p95 / p99 / max (ms) | UI 间隔 p50 / p95 / max (ms) |
| --- | --- | --- | --- | --- |
| 清缓存后 1 | 1321 | 33.989% | 4.673 / 12.614 / 21.166 | 8.343 / 8.797 / 27.072 |
| 清缓存后 2 | 1366 | 41.874% | 4.879 / 12.527 / 22.403 | 8.343 / 8.747 / 29.254 |
| 清缓存后 3 | 1364 | 55.865% | 4.881 / 15.866 / 20.956 | 8.343 / 8.652 / 30.081 |

清理与采集命令见 `android/README.md`；实际日志 `cold-2-clear.log`、`cold-3-clear.log` 与 `delivery-cold-{1,2,3}/` 保存原始证据。清理未操作源媒体、连接或浏览书签。


### 帧数据健康与 Macrobenchmark 限制

正式九份 trace 的 expected token 起止时间冲突数均为 0。负时间戳丢弃依次为暖 1/2/3：1/1/0，视频 1/2/3：2/1/0，清缓存后 1/2/3：2/2/2；未完成 slice 和未匹配 RenderThread 帧均在每份 summary 报告，不将其算为成功帧。原始健康 SQL/结果保存于 `delivery-frame-health.*`。

最终独立 Macrobenchmark APK 安装成功，运行命令：

```sh
adb shell am instrument -w -r -e class dev.vvideo.benchmark.LibraryScrollBenchmark dev.vvideo.benchmark/androidx.test.runner.AndroidJUnitRunner
```

超过两分钟仍停在开始状态，runner 前台服务仍在，但 `/sys/fs/cgroup/uid_10356/pid_20064/cgroup.freeze` 为 `1`、`cgroup.events` 为 `frozen 1`。只停止本次 runner，没有修改全局冻结/省电策略。停止后输出如下：

```text
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
```

ADB 主机命令 exit 0 不代表测试成功：这是主动停止冻结 runner 的输出，未得到测试通过/帧结果，也不是生产 VVideo 崩溃。日志另有 profileinstaller 缺失/过旧警告；按任务约束没有新增或升级依赖。`final-macrobenchmark.log`、`final-benchmark-freezer.txt`、runner 专属 logcat 保留。以主机 Perfetto 三轮结果报告本轮性能，并明确独立 Macrobenchmark 未完成。


### 最终界面复核与保留条件

最终 APK 深浅首页/相册截图在 `delivery-home-{dark,light}.png`、`delivery-album-{dark,light}.png`，已查看确认主题、可见文字/角标与系统栏；仅覆盖截图状态。手机已恢复跟随系统、字号 1.0、动画倍率 1.0，安装摘要再次核对一致，停在首页。保留合成服务 19092 和对应 USB reverse 供继续查看，停止本轮无用服务与日志采集。没有真机性能全通过结论。

未完成的受控首帧/seek、真实 NAS/4K/竞争、完整 TalkBack/预测返回/全部长文本与冷启动白闪，以及原样人工清单，集中见 [实施记录](plans/2026-10-10-android-ui-execution.md)。此处数值与历史其他 APK 验收严格分开。
