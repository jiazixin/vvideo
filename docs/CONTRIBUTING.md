# Contributing to VVideo

Thanks for helping. This guide covers the development setup, the conventions the code follows and the checks a change must pass. Product behavior and performance targets live in [DESIGN.md](DESIGN.md); please read the parts that touch your change first.

## Ground rules

- **Never commit private data.** No real media, media file names, NAS paths, server addresses, tokens or `configs/*.local.yaml`. Tests use synthetic media (`scripts/generate-media.py`) or temporary directories.
- **Originals are read-only.** Server code must never write, rename or delete files inside a media root.
- **Keep the existing layout.** Go follows [目录规范.md](../目录规范.md) and Android follows [安卓目录规范.md](../安卓目录规范.md). Add a directory only when it has real content; the [layout decision](decisions/2026-10-08-project-layout.md) records the project's exceptions.
- **One change per pull request.** Don't mix refactoring, dependency upgrades and features.
- **Performance is a requirement.** Server changes to scanning, the database, jobs, paging or caches must follow the PERF rules in [IMPLEMENTATION.md](IMPLEMENTATION.md). Android changes to lists, layout, images, playback or prefetch must be re-measured on a device as described in [android/README.md](../android/README.md); say in the pull request what you measured and what you could not.

## Development setup

You need Go (version from [go.mod](../go.mod)), FFmpeg with `libwebp`, JDK 21 and the Android SDK (Platform 36, Build-Tools 35.0.0). See the [README](../README.en.md#requirements).

Run a local server against synthetic media:

```sh
python3 scripts/generate-media.py /tmp/vvideo-media/clips
cp configs/config.example.yaml configs/config.local.yaml
# Point media[].path at /tmp/vvideo-media, set auth.token and server.listen: 127.0.0.1
go run ./cmd/vvideo --config configs/config.local.yaml
```

To reach a server on your computer from a USB-connected phone, forward the port and enter `http://127.0.0.1:8080` in the app's Settings:

```sh
adb reverse tcp:8080 tcp:8080
```

## Code style

**Go**

- Format with `gofmt` and group imports as standard library, third-party, then `vvideo/...` (`goimports` does this).
- Every package has a package comment and every exported identifier a doc comment that says what it does and any constraint the caller must respect.
- Use the idioms of the Go version in `go.mod` (for example `wg.Go`, `for range n`, `min`/`max`, `errors.Is`, `encoding/json/v2`).
- Name numbers that carry meaning (`storage.BatchSize`, timeouts, limits) instead of repeating literals.
- Keep filesystem and tool work (`ffprobe`, `ffmpeg`) outside database write transactions, and bound every queue, page and worker pool.
- Unit tests sit next to the code as `*_test.go`; cross-package tests go in `tests/`.

**Kotlin / Android**

- Kotlin official code style (`kotlin.code.style=official`), four-space indents, one statement per line, lines up to 120 columns. Android Studio's *Reformat Code* produces the expected layout.
- Feature code stays in its `feature/<name>` package; `data/`, `model/` and `ui/` never import a feature. `:core:media` never depends on `:app`.
- A `*Route` collects state and wires navigation; a `*Screen` takes immutable state and callbacks. No file, network or database work on the main thread or inside composables.
- User-visible text goes in `res/values/strings.xml`; remove strings that are no longer used.
- JVM tests go in `src/test/kotlin`, device tests in `src/androidTest/kotlin`.

## Checks

Run these before opening a pull request. All must pass.

```sh
gofmt -l .          # prints nothing
go vet ./...
go test ./...
android/gradlew -p android :app:testDebugUnitTest :core:media:testDebugUnitTest \
  :app:lintDebug :core:media:lintDebug :app:assembleDebug :app:assembleProfile
```

[CI](../.github/workflows/ci.yml) runs these checks, `go test -race` and a macOS cross-vet on every pull request. Run `go test -race ./...` locally for changes to concurrent server code. Device tests and frame or memory measurements need a phone and are described in [android/README.md](../android/README.md). If you could not run a check, say which one and why in the pull request rather than leaving it out.

## Commits and pull requests

- Write commit subjects in the imperative mood, in English, under about 70 characters (for example `Keep the album position when the viewer closes`), and explain the why in the body.
- Describe the behavior change, the checks you ran with their results, and anything not verified (devices, network shares, codecs).
- Update [README.md](../README.md) (Chinese) and [README.en.md](../README.en.md) (English) together when commands, configuration or scope change, and update [API.md](API.md) when the HTTP contract changes.

## Reporting bugs

Open an issue with the server version (`vvideo --version`), the Android device and OS version, the steps to reproduce and the relevant log lines. Remove tokens, addresses and media names first. Report security problems privately as described in [SECURITY.md](SECURITY.md).
