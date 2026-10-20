<p align="center"><img src="docs/assets/logo.svg" width="96" height="96" alt="VVideo logo"></p>

# VVideo

安卓 UI 已按 [频道式重设计规范](docs/plans/2026-10-10-android-ui-telegram-style.md) 实现媒体库首页（不再列出最近浏览）、深浅蓝主题、媒体标签、相册日期、播放器手势及独立存储/连接页。Room v2 保留旧锚点并增加最近访问记录；有界窗口和单播放器架构继续沿用。各构建的真机结果与限制见 [验证记录](docs/ACCEPTANCE.md)。

[English](README.md) | 简体中文

VVideo 用 Linux/NAS 上的 Go 服务索引已有目录媒体库，以原生 Kotlin 安卓客户端浏览和播放。源媒体只读，播放使用原字节，第一版不转码。

**当前状态：已实现，验收尚未全部通过。** 2026-10-10 重设计的 30 分钟合成工作负载内存门槛通过，但最终三轮暖滚动均超过 1% 帧截止时间超限目标；历史 APK 测量不能证明新版性能。真实完整媒体库及 NAS、所有编码兼容性、配套服务端部署仍未验证。详见 [验证记录](docs/ACCEPTANCE.md)。

## 已实现范围

- 目录导航、十媒体宽高比拼接相册、服务端全目录拍摄时间/修改时间/名称排序，以稳定 ID 打破并列。
- 持久保存各目录排序和媒体锚点，双向有界元信息窗口；清理缓存保留设置与浏览记录。
- 纵向视频流复用一个活动 Media3 播放器，版本化字节缓存与有界可取消的下一条预取；图片全屏浏览、缩放和左右滑动切换同目录图片；媒体详情说明实际排序时间来源。
- SQLite WAL/FULL，单写与四个读连接；128 项流式扫描、独立磁盘暂存和目录原子发布。失败/离线保留旧索引，连续两轮完整健康扫描才确认缺失。
- 持久延迟的探测/封面任务、受控 ffprobe/FFmpeg、每视频一张 WebP 封面、按磁盘预算回收的缩略图 LRU。
- Bearer 认证 API、Range/ETag/版本检查、普通 MP4 原字节偏移提示与定时扫描。播放期间扫描、探测和抽帧照常进行，由各自并发上限控制。
- 安卓共享媒体缓存预算 **1/5/10 GB**，按十进制字节计算；磁盘配额与 RAM 缓冲独立。

用户估计的 13 万视频与 38 万图片尚未在文件系统核实。51 万条合成数据库测试与真实媒体建库分开记录。公网、转码、iOS 和推荐系统不在本版范围。

## 构建与启动服务端

使用与 [go.mod](go.mod) 一致的 **Go 1.27.1**，PATH 中需有 `ffmpeg` 与 `ffprobe`。FFmpeg 须支持实际输入，并包含封面所用的 `libwebp` 编码器，可用 `ffmpeg -hide_banner -encoders | grep libwebp` 检查（Debian/Ubuntu 的 `ffmpeg` 包包含；当前 Homebrew 的 `ffmpeg` 不包含）。SQLite 放服务端本地磁盘，不能放 SMB/NFS；媒体可在挂载盘。

```sh
go mod download
go test ./...
go vet ./...
go build -trimpath -o bin/vvideo ./cmd/vvideo
cp configs/config.example.yaml configs/config.local.yaml
# 修改媒体/排除路径，设置私有的非空令牌。
chmod 600 configs/config.local.yaml
bin/vvideo --config configs/config.local.yaml
```

发布构建用 `-ldflags "-X main.version=v1.0.0"` 写入版本号；否则 `--version` 显示 Go 根据 Git 生成的版本。

```text
Usage:
  vvideo [options]

Options:
  -c, --config <file>   Configuration file (default: ./configs/config.local.yaml)
  -v, --version         Print version information and exit
  -h, --help            Print this help and exit
```

退出码：正常结束、`--help`、`--version` 为 0；命令行参数错误为 2（输出到 stderr）；服务无法启动或运行失败为 1。启动后日志为 stdout 上的 JSON 行。启动失败只记录一条 `service_failed`，其 `error` 写明具体原因，例如缺少 `ffmpeg`/`ffprobe`、FFmpeg 缺少 `libwebp`、配置文件及行号、数据库已被另一进程锁定、端口已被占用。

示例相对路径以 `configs/` 为基准。保护私有配置，将源媒体以只读方式挂载。服务写入数据库/暂存与封面缓存；SIGTERM 取消任务，HTTP 优雅退出最多等待 30 秒。数据库所有权锁拒绝第二个进程同时使用该库。

[deploy/vvideo.service](deploy/vvideo.service) 是实际 systemd 定义，约定 `/opt/vvideo` 和已存在的 `vvideo` 服务账户。安装前创建账户及可写的 `data/`、`cache/`，安装二进制/配置，并按部署环境调整路径和账户。沙箱只允许写入上述目录与私有临时空间，媒体挂载需保留读取权限。配置正确后安装到 `/etc/systemd/system/vvideo.service`，执行 systemd reload 与 enable/start；不能直接使用尚未适配路径的 unit。

## 安卓

详见 [android/README.md](android/README.md)。真实 Wrapper 固定 **Gradle 8.14.3** 和校验和；版本目录固定 **AGP 8.13.2、Kotlin 2.2.21、Media3 1.8.1** 等依赖。构建使用 **JDK 21**、Android SDK Platform 36 和 Build-Tools 35.0.0：

```sh
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :core:media:lintDebug :app:assembleDebug
android/gradlew -p android :app:assembleProfile :benchmark:assembleProfile
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

设置中填写服务根 URL 与令牌。任意主机均可使用 HTTP 或 HTTPS，例如 `http://192.168.1.10:8080`，构建时无需额外参数。HTTP 不加密链路，同一网络内可以读取令牌和媒体内容。

提供的测试机上，用户指定的 `~/vvideo` 是媒体根；运行文件、SQLite 和缓存独立放在 `~/vvideo-runtime`。本次真机测试通过 SSH 隧道和 ADB reverse 连接。开机服务及防火墙属于部署操作，尚未在提供的机器上完成。
```sh
ssh -N -L 19323:127.0.0.1:8080 vvideo
# 隧道保持运行，在另一终端执行：
adb reverse tcp:19323 tcp:19323
# 安卓 URL：http://127.0.0.1:19323
```

这些端口是开发测试配置，不是隐藏的应用默认值。USB 断开或隧道退出会中断连接。profile 为混淆、非调试、可采样、开发签名构建；正式 release 分发需自己的签名流程。

## 配置

支持字段见 [configs/config.example.yaml](configs/config.example.yaml)。除非空 `auth.token` 与至少一个 `media[].path` 外均有默认值。

- 文件选择：`-c`/`--config`（也支持 `--config=<文件>`，重复时取最后一个）> `CONFIG_FILE` > 工作目录下的 `configs/config.local.yaml`。相对路径以工作目录为基准。显式选择的文件为空、缺失、不可读或无效时直接失败；只有隐式默认文件可以不存在，此时须由环境变量提供必填项。
- 配置项优先级：`VVIDEO_*` 环境变量 > 选定 YAML > 默认值，与文件的选择方式无关。
- `server.listen` 是主机名或 IP，不含端口（默认 `0.0.0.0`，IPv6 写作 `::`）；`server.port` 是 1–65535 的整数（默认 `8080`）。在 `server.listen` 中写 `host:port` 会被拒绝。
- `storage` 是数据目录而不是文件：数据库为目录内的 `data.sqlite`，扫描暂存 `.scan-tmp/` 也在其中。该路径已是普通文件时报错。
- 文件内路径相对配置目录，环境/默认路径相对工作目录。默认 `data`（数据库 `data/data.sqlite`）、`cache`（缩略图）。媒体根不能重叠，可写数据库/缓存不得进入原件树。`ignore` 指定精确文件或目录子树，不支持通配符。
- 环境变量为 `VVIDEO_` 加大写字段路径，以 `__` 分隔，例如 `VVIDEO_AUTH__TOKEN`、`VVIDEO_CACHE__PATH`。`VVIDEO_MEDIA`/`VVIDEO_IGNORE` 以 JSON `[{"path":"..."}]` 整体替换列表。未知/旧字段、重复键、别名、标签、类型错误及必填空值失败；不加载 `.env` 或插值 YAML 变量。
- `scan.cron` 为五段 cron，默认 `*/30 * * * *`，可带 `CRON_TZ=...`。空值只关闭周期扫描，仍保留启动扫描；正在运行时不积压 cron 触发。`scan.parallel` 限制文件属性读取并发，`scan.probe_parallel` 限制同时运行的 ffprobe 元信息探测任务，默认均为 2。并发/线程是正整数，防御性上限 1024，不是进程总线程限制。
- 封面配置包括 `cache.path`、正数 `cache.max_size`（`MB/GB/MiB/GiB`）、`cache.parallel`、`cache.threads` 与 `[0,100)` 内有限单值 `cache.percent`。默认容量 `30GB`、2 工作者、2 解码线程、10% 取图；容量是内容预算，不预占空间。
- 修改配置后重启。重排/重启保留库 ID，改根路径创建另一媒体库；移除根配置停止管理，不删除原件。

超时、重试、页大小与资源策略内置，见 [设计](docs/DESIGN.md) 和 [开发说明](docs/IMPLEMENTATION.md)。格式兼容性取决于 FFmpeg、Media3、设备及实际媒体流。

## 目录、验证与维护

Go 模块在仓库根：`cmd/vvideo/`、`internal/`、`tests/`；schema SQL 内置于 `internal/storage/schema.go`。安卓唯一工具根为 `android/`，生产模块 `:app`/`:core:media` 与真实测试模块 `:benchmark`。`configs/`、`deploy/`、`scripts/`、`docs/` 各有职责；私有令牌、SDK 路径、构建输出和测试报告被忽略。备份配置与一致的 SQLite 快照；封面和暂存可重建。

施工需读取 [AGENTS.md](AGENTS.md)、[Go 基线](目录规范.md)、[安卓规范](安卓目录规范.md)、[目录决策](docs/decisions/2026-10-08-project-layout.md)、[设计](docs/DESIGN.md)、[PERF 规则](docs/IMPLEMENTATION.md) 和 [服务端测试计划](docs/plans/2026-10-09-server-performance.md)。修改 Go 前应用本机 `use-modern-go`，适用验证后应用 `open-code-review`；不能为掩盖审查环境缺失而伪造 Git 仓库或 OCR 成功。

临时 `video-rename/` 参考副本不参与构建；已提取机制与历史证据限制见 [性能决策](docs/decisions/2026-10-09-scan-storage-performance.md)。
