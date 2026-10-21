<p align="center"><img src="docs/assets/logo.svg" width="96" height="96" alt="VVideo logo"></p>

<h1 align="center">VVideo</h1>

<p align="center">
  A self-hosted media server for Linux/NAS and a native Android app for browsing and playing a folder-organized photo and video library.
</p>

<p align="center">
  <a href="https://github.com/jiazixin/vvideo/actions/workflows/ci.yml"><img src="https://github.com/jiazixin/vvideo/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <img src="https://img.shields.io/github/go-mod/go-version/jiazixin/vvideo?logo=go&logoColor=white" alt="Go version">
  <img src="https://img.shields.io/badge/platform-Linux%20%7C%20Android-4c8b3f" alt="Linux | Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue" alt="License: GPL-3.0-or-later"></a>
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

---

VVideo indexes an existing media library where it already lives. A Go service scans your folders, extracts metadata and covers, and serves the original bytes over HTTP. A Kotlin/Jetpack Compose app shows each folder as an album feed and plays videos full screen with a vertical swipe between them. Originals stay read-only: there is no transcoding, renaming or moving of files.

<p align="center">
  <img src="docs/assets/mockups/01-home-libraries.png" width="200" alt="Home screen mockup">
  <img src="docs/assets/mockups/02-folder-album-feed.png" width="200" alt="Folder album feed mockup">
  <img src="docs/assets/mockups/06-player-controls.png" width="200" alt="Player mockup">
</p>
<p align="center"><sub>Design mockups with placeholder images. See <a href="docs/assets/mockups/">all mockups</a>.</sub></p>

## Project status

**First release, partially accepted.** The server and app are implemented and tested on synthetic media and on a real phone (iQOO 12 Pro). Not yet verified: a full real library on a NAS, every codec/container combination, and a persistent production deployment. The latest warm-scroll frame measurements missed the 1% deadline-overrun target. The [acceptance record](docs/ACCEPTANCE.md) lists what was measured, on which build, and the known limitations.

Scope of this release: home network only, original-quality playback, Android only. Out of scope: public internet service, transcoding, iOS and recommendations.

## Features

**Server (Go, Linux/NAS)**

- Streaming, batched scans of large folder trees. A failed or offline scan keeps the previous index, and a file is only treated as deleted after two consecutive complete, healthy scans.
- SQLite in WAL mode with one writer and four read-only connections; the database lives on local disk while media may sit on a network mount.
- Durable background jobs for `ffprobe` metadata and one WebP cover per video, with bounded concurrency and a disk-budgeted LRU cover cache. Scans, probes and covers keep running during playback, limited by their worker counts.
- Global sorting by capture time, modification time or name, with cursor paging and stable anchors so clients can restore a position deep inside a folder. Name search covers a folder's whole subtree with bounded results.
- Bearer-token API with HTTP Range, ETag and per-version checks, so a client never mixes bytes from an old and a new version of a file. Optional index hints let the client prefetch the MP4 `moov`, the MKV/WebM Cues and the TS duration tail at their original offsets.
- Scheduled scans (cron), strict configuration validation, structured logs and graceful shutdown.

**Android app (Kotlin, Compose, Media3)**

- Folder navigation with ten-item albums laid out like Telegram channel albums (ported from Telegram for Android), and per-folder sort and position that survive app restarts.
- Name search across the open folder and all its subfolders, from a search box at the top of the folder page.
- A vertical video feed driven by a single reused Media3 player, a versioned byte cache and bounded, cancellable prefetch of the next video.
- Full-screen photos with zoom and horizontal paging; media details show where the sort time came from.
- Media cache budgets of 1, 5 or 10 GB (decimal), separate from RAM buffering. Clearing the cache keeps settings and browsing positions.
- Light and dark themes, high-refresh-rate scrolling and playback gestures: double-tap seek, hold for 2× speed, and vertical swipes on the left or right edge for brightness or volume.

The library size this was designed for (about 130,000 videos and 380,000 images) is the owner's estimate, not a measured count. Scale tests used a 510,000-row synthetic database, which is separate from indexing real media.

## Architecture

```text
Android app ──HTTP (Bearer token)──▶ Go service ──▶ SQLite (index, jobs)
  Compose UI                           │  scanner ─▶ read-only media folders
  Media3 player ◀── Range bytes ───────┤  ffprobe / FFmpeg ─▶ cover cache
  disk cache (1/5/10 GB)               └  REST API /v1
```

[docs/DESIGN.md](docs/DESIGN.md) describes the product behavior and performance targets, and [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) the server's scan, storage and performance rules.

## Repository layout

```text
.
├── .github/workflows/  CI and release workflows
├── cmd/vvideo/         server entry point (flags, logging, startup, shutdown)
├── internal/           server packages
│   ├── app/            wiring of storage, workers and the HTTP API
│   ├── config/         strict configuration loader
│   ├── jobs/           durable job queue and playback-activity tracker
│   ├── library/        published index: directories, media, paging, album anchors
│   ├── mediafs/        descriptor-relative, read-only file access and mount identity
│   ├── probe/          ffprobe and EXIF metadata
│   ├── scanner/        two-pass directory observation and publication
│   ├── storage/        SQLite ownership, connections and schema
│   ├── streaming/      Range responses and container index hints
│   └── thumbnail/      cover generation and LRU cache
├── tests/              cross-package Go integration tests
├── configs/            configuration example (local copies are git-ignored)
├── deploy/             systemd unit
├── scripts/            device measurement and synthetic-media tools
├── android/            Android Gradle project (:app, :core:media, :benchmark)
└── docs/               design, API, deployment, decisions, plans and acceptance records
```

Directory rules are in [目录规范.md](目录规范.md) (Go), [安卓目录规范.md](安卓目录规范.md) (Android) and the [project layout decision](docs/decisions/2026-10-08-project-layout.md), which also lists the project's documented exceptions.

## Getting started

### Requirements

| Component | Version |
| --- | --- |
| Go | **1.27.3** or newer, as declared in [go.mod](go.mod) |
| FFmpeg | `ffmpeg` and `ffprobe` on `PATH`; `ffmpeg` must include the `libwebp` encoder |
| JDK (Android) | 21 |
| Android SDK | Platform 36 and Build-Tools 35.0.0; devices running Android 9 (API 28) or later |

Check FFmpeg with `ffmpeg -hide_banner -encoders | grep libwebp`. Debian and Ubuntu's `ffmpeg` package includes it; the current Homebrew `ffmpeg` does not.

For HEIC/HEIF photos (such as tiled iPhone photos) the server reads size, rotation and capture time from the file's own metadata, whatever the FFmpeg version; making their covers needs FFmpeg 7.1 or later (earlier versions either read only the first 512×512 tile, so the cover shows one corner, or cannot decode the file, and the cover is marked failed).

### Download a release

Pushing a `vX.Y.Z` tag makes the [release workflow](.github/workflows/release.yml) publish to [Releases](https://github.com/jiazixin/vvideo/releases):

- `vvideo_<version>_linux_amd64.tar.gz` and `vvideo_<version>_linux_arm64.tar.gz`: the server binary with LICENSE, NOTICE, the READMEs, the example configuration and the systemd unit. It still needs FFmpeg as listed above.
- `vvideo_<version>_android.apk`: the release-signed Android app. Its signature differs from local debug, profile and device builds, so a phone with a development build installed must uninstall it first, which deletes the app's bookmarks and settings.
- `checksums.txt`: SHA-256 checksums of the files above.

### Run the server

```sh
go mod download
go build -trimpath -o bin/vvideo ./cmd/vvideo
cp configs/config.example.yaml configs/config.local.yaml
# Edit media paths and set a private, non-empty auth.token.
chmod 600 configs/config.local.yaml
bin/vvideo --config configs/config.local.yaml
```

Put the SQLite data directory on the server's local disk, not on SMB/NFS; media folders may be network mounts. For a release build, stamp the version with `-ldflags "-X main.version=v1.0.0"`; otherwise `--version` reports the version Go derives from Git.

```text
Usage:
  vvideo [options]

Options:
  -c, --config <file>   Configuration file (default: ./configs/config.local.yaml)
  -v, --version         Print version information and exit
  -h, --help            Print this help and exit
```

The exit status is 0 on success, `--help` or `--version`; 2 for a command-line error; and 1 when the service cannot start or fails. A failed start logs one `service_failed` event naming the cause, for example a missing `ffmpeg`/`ffprobe`, an FFmpeg build without `libwebp`, a configuration file and line, a database already locked by another process, or a port already in use. SIGTERM cancels background work: in-flight HTTP requests get up to 10 more seconds, after which connections still open (such as a stream to a client that stopped reading) are closed; the whole stop takes at most 30 seconds.

### Build and install the Android app

The Gradle Wrapper pins Gradle 8.14.3; AGP 8.13.2, Kotlin 2.2.21, Media3 1.8.1 and the other dependencies are pinned in [android/gradle/libs.versions.toml](android/gradle/libs.versions.toml). Set `ANDROID_HOME` or an ignored `android/local.properties`.

```sh
android/gradlew -p android :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Open Settings in the app and enter the server root URL (for example `http://192.168.1.10:8080`) and the token. HTTP and HTTPS are both accepted for any host. Plain HTTP does not encrypt the connection: anyone on the same network can read the token and the media.

Build variants, device tests and performance measurement are described in [android/README.md](android/README.md).

## Configuration

[configs/config.example.yaml](configs/config.example.yaml) lists every supported field. Only `auth.token` and at least one `media[].path` are required; everything else has a default. Configuration is read once at startup, so changes need a restart.

**Choosing the file.** `-c`/`--config` (also `--config=<file>`; the last one wins) takes precedence over `CONFIG_FILE`, which takes precedence over `configs/config.local.yaml` in the working directory. Relative selections resolve against the working directory. An explicitly selected file that is empty, missing, unreadable or invalid is an error; only the implicit default may be absent, in which case environment variables must supply the required settings.

**Precedence and environment variables.** `VVIDEO_*` environment variables override the YAML file, which overrides the defaults. A variable name is `VVIDEO_` plus the uppercase field path with `__` between levels, for example `VVIDEO_AUTH__TOKEN` or `VVIDEO_CACHE__PATH`. `VVIDEO_MEDIA` and `VVIDEO_IGNORE` replace the whole list with a JSON array of `{"path": "..."}` objects. Unknown or legacy fields, duplicate keys, YAML aliases and tags, wrong types and empty required values are rejected. There is no `.env` loading and no variable interpolation in YAML.

| Setting | Meaning | Default |
| --- | --- | --- |
| `server.listen` | Host name or IP address without a port; write IPv6 as `::` | `0.0.0.0` |
| `server.port` | TCP port, 1–65535 | `8080` |
| `auth.token` | Bearer token clients must send | required |
| `storage` | Data directory holding `data.sqlite` and the `.scan-tmp/` staging area | `data` |
| `media[].path` | Media roots; they must not overlap | required |
| `ignore[].path` | Exact files or directory subtrees inside a media root, not globs | none |
| `scan.cron` | Five-field cron with optional `CRON_TZ=`; empty disables periodic scans (the startup scan still runs) | `*/30 * * * *` |
| `scan.parallel` | Concurrent file attribute reads during a scan | `2` |
| `scan.probe_parallel` | Concurrent `ffprobe` jobs | `2` |
| `cache.path` | Cover cache directory | `cache` |
| `cache.max_size` | Cover cache budget, with unit `MB`, `GB`, `MiB` or `GiB` | `30GB` |
| `cache.parallel` / `cache.threads` | Concurrent cover jobs, and decode threads per job | `2` / `2` |
| `cache.percent` | Position of the cover frame as a percentage of the duration, in `[0, 100)` | `10` |
| `log.level` / `log.format` | `debug`, `info`, `warn` or `error` / `json` or `text` | `info` / `json` |
| `log.output` / `log.file` | `stdout`, `stderr` or `file`; `log.file` is required for, and only accepted with, `file` | `stdout` |

Paths in the YAML file resolve against the file's directory; paths from environment variables and defaults resolve against the working directory. Writable directories (`storage`, `cache.path`, `log.file`) must stay outside the media roots. Concurrency values accept 1–1024; they bound VVideo's own workers, not the process's thread count. Log files are appended to and never rotated, so use logrotate with `copytruncate`. While covers are being generated, a `thumbnail_progress` event every five minutes reports progress, throughput and an ETA; an idle queue logs nothing.

Libraries keep their IDs across restarts and reordering. Changing a root's path creates a new library, and removing a root stops managing it without touching the files. Timeouts, retry limits, page sizes and other resource policies are built in; see the [design](docs/DESIGN.md) and the [HTTP API](docs/API.md).

## Deployment

[deploy/vvideo.service](deploy/vvideo.service) is a hardened systemd unit for a binary in `/opt/vvideo` running as a `vvideo` service account. Before installing it:

1. Create the account and the writable `/opt/vvideo/data` and `/opt/vvideo/cache` directories, and install the binary and `configs/config.local.yaml`.
2. Adjust paths and the account to your system. The unit only allows writes to `data/` and `cache/`; with `log.output: file`, add the log directory to `ReadWritePaths` (or keep the default stdout, which goes to journald). Keep read access to the media mounts.
3. Install it as `/etc/systemd/system/vvideo.service`, then run `systemctl daemon-reload` and `systemctl enable --now vvideo`.

Mount media read-only where possible. The service writes only its data directory and cover cache, and a lock file stops a second process from opening the same database.

**Backups and state.** Back up the private configuration and a consistent snapshot of `data/data.sqlite` (stop the service, or use SQLite's online backup). The cover cache and scan staging area can be rebuilt. Before the first stable release there are no schema migrations: if a new build reports a schema version mismatch, stop the service, delete the data directory and let it reindex. Media files are never affected.

## Development

```sh
gofmt -l . && go vet ./...
go test ./...
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :core:media:lintDebug
```

[GitHub Actions CI](.github/workflows/ci.yml) runs on every push to `main` and every pull request: `gofmt`, `go vet` (plus a macOS cross-check), `go test -race` and Linux amd64/arm64 cross-builds for the server; unit tests, lint, and the debug and profile builds for Android. CI also checks the release configuration with a GoReleaser snapshot. Pushing a `vX.Y.Z` tag runs the [release workflow](.github/workflows/release.yml), which signs the APK with the key stored in the repository secrets and publishes the server archives and the APK with GoReleaser; see [release signing](android/README.md#release-signing). Device tests and performance measurements need a phone and are not run in CI.

`scripts/generate-media.py <dir>` creates a small synthetic library (H.264, HEVC and AV1 in MP4, MKV, TS and MOV, plus photos) for local testing. Never commit real media, tokens or server addresses.

See [CONTRIBUTING](docs/CONTRIBUTING.md) for the workflow, code style and checks expected in a pull request. Contributors using AI agents should also follow [AGENTS.md](AGENTS.md).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/DESIGN.md](docs/DESIGN.md) | Product behavior and performance targets (Chinese) |
| [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) | Server scan, storage and performance rules PERF-01 to PERF-12 (Chinese) |
| [docs/API.md](docs/API.md) | HTTP API and error codes (Chinese) |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Deployment record of the test machine (Chinese) |
| [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) | What was measured and verified, with limitations (Chinese) |
| [docs/decisions/](docs/decisions/) and [docs/plans/](docs/plans/) | Design decisions and implementation plans |
| [android/README.md](android/README.md) | Android build variants, device tests and measurement |

## Security

The API is designed for a trusted home network. Use a long random token, restrict the listening address and firewall to your LAN, and use HTTPS (for example behind a reverse proxy) if the network is not trusted. To report a vulnerability, follow [docs/SECURITY.md](docs/SECURITY.md); please do not open a public issue.

## License

Copyright 2026 jzx. Licensed under the [GNU General Public License v3.0 or later](LICENSE).

The Android album layout is ported from [Telegram for Android](https://github.com/DrKLO/Telegram) (GPL-2.0-or-later); see [NOTICE](NOTICE). VVideo is not affiliated with Telegram.
