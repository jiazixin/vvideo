# VVideo Android

Native Kotlin/Compose UI with Media3 playback. `:app` owns navigation, data access and settings (ViewModels read and write through `data/library/LibraryRepository`, which wraps the HTTP API and the Room bookmarks; server values such as media kind and sort order become enums at that boundary); `:core:media` owns the player, versioned video cache and bounded prefetch; `:benchmark` is a separate self-instrumenting Macrobenchmark APK.

Use JDK 21, SDK Platform 36 and Build-Tools 35.0.0. Set `ANDROID_HOME` or a private ignored `local.properties`. Dependencies and Wrapper checksum are pinned in `gradle/`.

```sh
./gradlew :app:testDebugUnitTest :core:media:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleProfile :benchmark:assembleProfile
./gradlew -PdeviceTestBuildType=device :app:assembleDevice :app:assembleDeviceAndroidTest
```

`debug` supports development; `release` is minified and requires distribution signing. `profile` is minified, non-debuggable, development-signed and profileable for external frame measurements. `device` is non-debuggable and profileable with shrinking disabled **only for in-process behavior tests**: AndroidJUnitRunner shares target dependencies whose unused facades R8 otherwise removes. Device-variant results do not establish minified release performance. The independent benchmark measures `profile` and attempts to keep its temporary runner alive with a foreground service; the tested OEM still freezes it. Host Perfetto measurements and this limitation are recorded in acceptance; this service is absent from the production app and stops after the test.

Device behavior tests require a private `files/validation.json` containing `address` and `token`, provisioned in a debug build's sandbox before installing the matching device build. Never commit this file. Tests cover real codec/container first frames, seeking, rapid switching and cache clearing. Controlled measurements return their complete bounded JSON as the instrumentation result `vvideo_measurements`; logcat remains diagnostic and cannot establish completeness. `PhotoDeviceTest` additionally verifies rendered original-image zoom, horizontal paging between photos (including a vertical swipe that must not page, a pinch along the paging axis and a drag on an enlarged photo), media details and return to the synthetic album. Device tests that connect save the `validation.json` address as the app's server address, so an installed app pointed elsewhere stops connecting afterwards; `DevicePlaybackTest` and `PhotoDeviceTest` accept `-e serverAddress <url>` to use the address the app already has. Long-run playback and real UI browsing (`sustainedUiBrowsing`) require explicit runner argument `soakMinutes=30`; without it the soak test is skipped. Build completion must precede APK installation.

```sh
adb shell am instrument -w -r -e class dev.vvideo.PhotoDeviceTest dev.vvideo.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.vvideo.DevicePlaybackTest -e soakMinutes 30 dev.vvideo.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.vvideo.benchmark.LibraryScrollBenchmark dev.vvideo.benchmark/androidx.test.runner.AndroidJUnitRunner
```

Install the correct target APK and its matching test APK first; don't mix the `device` instrumentation with `profile`. Benchmark setup requires an already configured connection, a root named `media`, and a synthetic image directory named `large`. The recorded development network uses loopback via SSH/ADB reverse. HTTP and HTTPS are accepted for any host; `res/xml/network_security_config.xml` permits cleartext traffic and the app only checks that the URL is an http/https server root.

Media metadata is limited to three pages (at most 300 items at the default page size). Image RAM cache is 32 MiB, player buffering targets 32 MiB with 2–10 second duration bounds, and prefetch has an 8 MiB total plan with at most 4 MiB of opening bytes plus bounded original-offset initialization ranges. Disk content uses a shared selected 1/5/10 GB budget (90% video, 10% images). Management overhead and process PSS are measured separately. These are implementation budgets, not a claim that total process memory equals their sum.

For hardware-decoded HEVC at 4K or higher, seeking can briefly request a codec processing rate bounded by the declared capability and 120 fps. The hint ends after the first presented buffer, after two seconds, or when stopped. Unknown frame rates, software decoders and unavailable capabilities keep Media3 defaults. This affects decoder resource planning, not playback speed or timestamp accuracy; the declared capability does not guarantee measured throughput. Android 14+ also uses Media3's decode-only buffer flag for skipped pre-target frames. Both policies retain exact seeking and the existing RAM/cache budgets. See [Android's operating-rate definition](https://developer.android.com/reference/android/media/MediaFormat#KEY_OPERATING_RATE).

See the [root README](../README.md), [Android conventions](../安卓目录规范.md) and [acceptance record](../docs/ACCEPTANCE.md).


If the OEM freezes the separate benchmark runner, use the host capture tool with
an already open album/player screen and the minified `profile` APK:

```sh
python3 scripts/measure-device-frames.py --output test-results/frames-warm --trace-processor /path/to/trace_processor_shell --prewarm
```

Run from the repository root. Obtain `trace_processor_shell` from official
Perfetto distribution and verify its checksum. Use separate fresh output
folders for cold loading, prewarmed albums and full-screen video; record the
installed APK SHA-256 and network/cache condition. The tool preserves every
round, trace health diagnostics, matching counts and UI frame intervals; it
separates MainActivity's UI layer from video Surface timelines. It fails on
ambiguous RenderThreads or missing interaction frames instead of claiming a
pass. For a three-video 4K folder use `--bounce-size 2` to alternate two forward and two reverse swipes; the default remains 12. Each round still contains 24 measured swipes. Its default layer pattern is verified on this iQOO; other OEM traces may
require an explicitly verified `--ui-layer-glob`. Cold rounds require clearing
media cache through Settings beforehand; do not use `pm clear` (which removes
connection/bookmarks). No global refresh/battery policy is changed.

For a separately driven 30-minute workload, collect full-process memory without
forcing GC or changing the UI:

```sh
python3 scripts/measure-device-memory.py --output test-results/memory-ui --interval 10
```

Start with VVideo running and drive the documented browsing/playback workload
throughout. Record the build and conditions alongside the output. The collector
requires a stable process, preserves raw PSS/Java/native/graphics/battery dumps,
and compares complete 5–10 and 25–30 minute windows. Missing or incomplete data
fails; numerical gate failure exits nonzero. Trend, crashes and frame acceptance
still require their own review. Existing samples can be reanalyzed using
`--analyze /path/to/samples.json` with the original `--interval`.

The current channel-layout host workload is documented below. The historical
`scripts/drive-device-soak.py` targets the previous UI and its Linux 4K fixture;
its navigation assumptions do not apply to the redesigned app. The new driver
reuses only its ADB/XML safety helpers. A short run checks the driver, not
long-run acceptance; instrumentation separately verifies player ownership,
version identity and teardown.

The 2026-10-10 channel UI supersedes the previous green visual design. It adds library home rows (since 2026-10-20 the home page no longer lists recently visited folders; visits are still recorded for the cold-start placeholder), blue Day/Night themes (system/light/dark persisted in DataStore), all/video/photo tabs, date-separated albums, player tap/hold/speed/orientation controls and storage/connection routes. Room keeps no migrations before the first release: a schema version change drops and recreates the local bookmarks. Visits and viewport updates write separate columns. Shared time formatting lives in `ui/component/TimeLabels.kt`, with album date grouping in `feature/library/DatedAlbums.kt`. Both child and media windows remain bounded to three pages. The video pager owns covers while one PlayerView/Surface owns playback. A viewer page that is animating out draws from its own last-state snapshot (`ExitSnapshot`), pauses at once and sends no further session or ViewModel commands; `PlayerViewModel.leave()` still clears immediately and the session is released when the page leaves composition. Each selection gets its own `PlayerRoute`, so reopening during the exit never reuses the leaving Surface. No API, media engine, caching or dependency baseline changed.

The redesign adds `UiRedesignDeviceTest` for the explicit local test fixture service on loopback port 19090 (`wide` with 450 folders). `downloadedVideoColdSeekMeasurements` records three cold runs for two licensed WebM samples; it is a diagnostic, not a performance pass. The original codec matrix's 800 ms READY assertion is retained. Tests use the private validation token and never put it in reports. See root acceptance for actual results, public sample attribution, and the distinction between local macOS test service and Linux/NAS verification.


The redesign adds `TelegramUiDeviceTest`, `BookmarkDaoDeviceTest`, avatar/time/key unit tests (later joined by `PendingDirectoryTest` for the destination placeholder shown during slow folder loads and the page restored when that destination is left before it loads, and by `ExitSnapshotTest` for the leaving viewer page), and extends the actual player UI test with speed, hold, double-tap and orientation assertions. There is no room-testing dependency; bookmark DAO behavior is checked with Room on the connected phone. Debug behavior tests are not release performance measurements.

For the current synthetic `media`/`large` channel layout, the Android-owned host driver replaces the old visual assumptions in `scripts/drive-device-soak.py`:

```sh
python3 android/benchmark/tools/profile-ui-workload.py --screen album
python3 android/benchmark/tools/profile-ui-workload.py --minutes 30 --output android/app/build/outputs/ui-redesign/cycles.jsonl
```

The guarded frame entry point uses the existing Perfetto calculation without changing its thresholds:

```sh
python3 android/benchmark/tools/measure-ui-frames.py --output android/app/build/outputs/ui-redesign/frames --trace-processor test-results/toolchains/trace_processor_shell --prewarm --rounds 3
```

Run from the repository root, with ADB on PATH and the configured app open. The driver uses the existing repository's ADB/XML helpers, operates only observed VVideo controls, and checks the foreground before each touch. It stops if the process changes or the UI cannot be identified. Run the existing frame/memory collectors separately against the installed profile APK. This local synthetic workload does not include the previous Linux 4K fixture or establish full NAS performance. See the [implementation and verification record](../docs/plans/2026-10-10-android-ui-execution.md).

To prepare an intentionally cold local-cache run, use the app confirmation flow:

```sh
python3 android/benchmark/tools/profile-ui-workload.py --screen storage
python3 android/benchmark/tools/clear-ui-cache.py
python3 android/benchmark/tools/profile-ui-workload.py --screen album
```

The clear helper requires the visible storage page, checks its confirmation text and then checks zero video/image usage. It does not access cache, source-media or bookmark files directly. Omit `--prewarm` for the following frame capture; entering the album has already loaded its first visible covers, so label this as cache-cleared scrolling rather than a cold application launch.
