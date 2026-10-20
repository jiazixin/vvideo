<p align="center"><img src="docs/assets/logo.svg" width="96" height="96" alt="VVideo logo"></p>

# VVideo

The Android UI follows the [channel-style redesign](docs/plans/2026-10-10-android-ui-telegram-style.md): library home (recent folders are no longer listed), blue light/dark themes, media tabs, album date labels, player gestures and separate storage/connection screens. Room v2 preserves old anchors while adding recent visits. Bounded windows and the single-player architecture remain in place. See [acceptance](docs/ACCEPTANCE.md) for the build-specific device results and limitations.

English | [简体中文](README.zh-CN.md)

VVideo indexes an existing folder-organized media library with a Go service on Linux/NAS and browses it with a native Kotlin Android app. Originals are read-only; playback uses their original bytes without transcoding.

**Status: implemented; acceptance remains partial.** The 2026-10-10 redesign passed its 30-minute synthetic workload memory gates, but all three final warm-scroll frame rounds exceeded the 1% deadline-overrun target. Earlier APK measurements do not establish its performance. Full-library/NAS testing, universal codec support and matching-server deployment remain unverified. See [verification records](docs/ACCEPTANCE.md).

## Implemented scope

- Folder navigation, ten-item aspect-aware albums, global server sorting by capture time, modification time or name, with stable ID ties.
- Persistent per-folder sort and media anchors; bounded bidirectional metadata windows. Cache clearing preserves settings and browsing records.
- A vertical video feed using one active Media3 player, versioned byte caches and bounded cancellable next-video prefetch. Images support full-screen viewing, zoom and horizontal swiping between the photos of a folder; media details identify the sorting time source.
- SQLite WAL/FULL, one writer and four read connections, streaming scans with 128-entry batches, isolated disk staging and atomic directory publication. Failed/offline scans preserve the previous index; absence requires two consecutive complete healthy scans.
- Durable delayed metadata/cover jobs, bounded ffprobe/FFmpeg execution, one WebP cover per video, and disk-budgeted thumbnail LRU.
- Bearer-authenticated APIs, HTTP Range/ETag/version checks, optional original-offset MP4 index hints and scheduled scans. Scans, probes and covers keep running during playback, bounded by their worker counts.
- Shared Android media cache budgets of **1/5/10 GB** (decimal bytes); disk quotas are independent of RAM buffering.

The user's estimated 130,000 videos and 380,000 images are not verified filesystem counts. A 510,000-row synthetic database test is separate from real-media indexing. Public-network service, transcoding, iOS and recommendations are outside this release.

## Build and run the server

Use Go **1.27.1**, matching [go.mod](go.mod), with `ffmpeg` and `ffprobe` on PATH. The FFmpeg build must support your inputs and include the `libwebp` encoder used for covers; check with `ffmpeg -hide_banner -encoders | grep libwebp` (Debian/Ubuntu's `ffmpeg` package has it; current Homebrew `ffmpeg` does not). Store the SQLite database on the server's local filesystem, not SMB/NFS; media may reside on a mount.

```sh
go mod download
go test ./...
go vet ./...
go build -trimpath -o bin/vvideo ./cmd/vvideo
cp configs/config.example.yaml configs/config.local.yaml
# Edit the media/ignore paths and set a private nonempty token.
chmod 600 configs/config.local.yaml
bin/vvideo --config configs/config.local.yaml
```

For a release build, stamp the version with `-ldflags "-X main.version=v1.0.0"`; otherwise `--version` reports the version Go derives from Git.

```text
Usage:
  vvideo [options]

Options:
  -c, --config <file>   Configuration file (default: ./configs/config.local.yaml)
  -v, --version         Print version information and exit
  -h, --help            Print this help and exit
```

Exit status is 0 on success, `--help` or `--version`; 2 for a command-line error (printed to stderr); 1 when the service cannot start or fails. Configuration errors are logged as JSON lines on stdout; once the configuration loads, logs follow the `log` settings (default: info-level JSON lines on stdout). A failed start logs one `service_failed` event whose `error` names the cause, such as a missing `ffmpeg`/`ffprobe`, an FFmpeg without `libwebp`, a configuration file and line, a database already locked by another process, or a port already in use.

The example paths are relative to `configs/`. Keep configuration private and mount originals read-only. The service writes only its database/staging and cover cache. SIGTERM cancels work and permits up to 30 seconds for graceful HTTP shutdown. A storage ownership lock rejects a second process using the same database.

[deploy/vvideo.service](deploy/vvideo.service) is an actual systemd definition for `/opt/vvideo` and an existing `vvideo` service account. Before installing, create the account and writable `data/` and `cache/` directories, install the binary/config, and adapt the paths/account to your deployment. Its sandbox permits writes only to those directories and private temporary storage; with `log.output: file`, add the log directory to `ReadWritePaths` (or keep the default stdout, which goes to journald). Preserve read access to your media mounts. Then install it as `/etc/systemd/system/vvideo.service`, reload systemd and enable/start it. Do not copy a unit whose paths have not been configured.

## Android

See [android/README.md](android/README.md). The real Wrapper pins Gradle **8.14.3** and its checksum; AGP **8.13.2**, Kotlin **2.2.21**, Media3 **1.8.1** and other dependencies are pinned in the version catalog. Build with **JDK 21**, Android SDK Platform 36 and Build-Tools 35.0.0:

```sh
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :core:media:lintDebug :app:assembleDebug
android/gradlew -p android :app:assembleProfile :benchmark:assembleProfile
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Enter the server root URL and token in Settings. Both HTTP and HTTPS are accepted for any host, for example `http://192.168.1.10:8080`; no special build property is needed. HTTP does not encrypt the link, so the token and media bytes are readable by anyone on the same network.

On the supplied test machine, the requested `~/vvideo` is the media root; runtime files, SQLite and caches are separate in `~/vvideo-runtime`. The recorded device tests use an SSH tunnel plus ADB reverse. Persistent service activation and firewall changes are separate deployment actions and have not been completed on the supplied machine.
```sh
ssh -N -L 19323:127.0.0.1:8080 vvideo
# Run in another terminal while the tunnel is alive:
adb reverse tcp:19323 tcp:19323
# Android URL: http://127.0.0.1:19323
```

These ports are the development setup, not hidden application defaults. Phone disconnects or tunnel termination interrupt this connection. Profile builds are minified, non-debuggable, profileable and signed with the development key; release distribution requires your own signing process.

## Configuration

[configs/config.example.yaml](configs/config.example.yaml) contains the supported fields. All but nonempty `auth.token` and at least one `media[].path` have defaults.

- File selection: `-c`/`--config` (also `--config=<file>`; the last one wins) > `CONFIG_FILE` > `configs/config.local.yaml` in the working directory. Relative selections resolve against the working directory. An explicitly selected file that is empty, missing, unreadable or invalid fails; only the implicit default may be absent, in which case the environment must supply the required settings.
- Field precedence: `VVIDEO_*` environment variables > selected YAML > defaults, however the file was selected.
- `server.listen` is a host name or IP address without a port (default `0.0.0.0`; write IPv6 as `::`), and `server.port` is an integer from 1 to 65535 (default `8080`). A combined `host:port` in `server.listen` is rejected.
- `storage` is a data directory, not a file: the database is `data.sqlite` inside it, beside the `.scan-tmp/` scan staging area. An existing file at that path is rejected.
- File paths resolve against the configuration directory; environment/default paths against the working directory. Defaults are `data` (database `data/data.sqlite`) and `cache` (thumbnails). Media roots must not overlap, and writable data/cache must remain outside originals. `ignore` specifies exact files or directory subtrees, not globs.
- Environment names are `VVIDEO_` plus uppercase paths separated by `__`, e.g. `VVIDEO_AUTH__TOKEN` or `VVIDEO_CACHE__PATH`. `VVIDEO_MEDIA`/`VVIDEO_IGNORE` replace complete lists using JSON arrays of `{"path":"..."}`. Unknown/legacy fields, duplicate keys, aliases, tags, wrong types and empty required values fail. No `.env` loading or YAML variable interpolation.
- `scan.cron` is five-field cron, default `*/30 * * * *`, with optional `CRON_TZ=...`. Empty disables periodic scans, retaining the startup scan. Running scans do not accumulate cron triggers. `scan.parallel` bounds file attribute reads and `scan.probe_parallel` bounds concurrent ffprobe metadata jobs; both default to 2. Positive integer concurrency/thread values have a defensive upper bound of 1024; they are not process-wide thread limits.
- Cover settings: `cache.path`, positive `cache.max_size` (`MB/GB/MiB/GiB`), `cache.parallel`, `cache.threads`, and finite single `cache.percent` in `[0,100)`. Defaults: `30GB`, 2 workers, 2 decode threads, 10%. Capacity is a content budget, not preallocated storage.
- Logging: `log.level` is `debug`, `info` (default), `warn` or `error`; `log.format` is `json` (default) or `text`; `log.output` is `stdout` (default), `stderr` or `file`, and `log.file` is required for, and only accepted with, `file`. The file and its directory are created if missing and appended to; there is no built-in rotation, so use logrotate's `copytruncate`. While covers are being generated, a `thumbnail_progress` info event every 5 minutes reports covers published and failed attempts in the window, queue totals (`total`/`done`/`failed`/`pending`/`percent`), media still `waiting_probe`, average per-cover time `avg_ms`, throughput `per_minute` and an `eta` estimated from that window; an idle queue logs nothing.
- Configuration changes require restart. Libraries retain IDs across reordering/restarts; changing a root path creates a different library. Removing a configured root stops management, without deleting originals.

Timeouts, retry limits, page sizes and resource policies are built in; see [design](docs/DESIGN.md) and [implementation requirements](docs/IMPLEMENTATION.md). Source format compatibility depends on FFmpeg, Media3, the device and the actual streams.

## Layout, verification and maintenance

The Go module is at the repository root: `cmd/vvideo/`, `internal/` and `tests/`; schema SQL is built into `internal/storage/schema.go`. Android has one tool root `android/`, two production modules `:app`/`:core:media`, and the actual `:benchmark` test module. `configs/`, `deploy/`, `scripts/` and `docs/` have distinct responsibilities. Runtime secrets, SDK paths, build products and test reports are ignored. Back up private configuration and a consistent SQLite snapshot; covers and disposable staging are rebuildable.

Read [AGENTS.md](AGENTS.md), [Go directory baseline](目录规范.md), [Android conventions](安卓目录规范.md), [layout decision](docs/decisions/2026-10-08-project-layout.md), [design](docs/DESIGN.md), [PERF rules](docs/IMPLEMENTATION.md), and the [server test plan](docs/plans/2026-10-09-server-performance.md). Apply installed `use-modern-go` before Go changes and `open-code-review` after applicable validation. Do not manufacture a Git repository or successful OCR result to conceal an unavailable review environment.

The temporary `video-rename/` reference copy is excluded from VVideo builds; its extracted mechanisms and historical limits are documented in the [performance decision](docs/decisions/2026-10-09-scan-storage-performance.md).
