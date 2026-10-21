package dev.vvideo

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.vvideo.app.MainActivity
import dev.vvideo.app.VVideoApplication
import dev.vvideo.core.media.playback.PlaybackSession
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.player.playback
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DevicePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as VVideoApplication
    private suspend fun connection(): Connection {
        withTimeout(15000) { application.ready.first { it } }
        val j = withContext(Dispatchers.IO) {
            JSONObject(
                File(
                    application.filesDir,
                    "validation.json"
                ).readText()
            )
        }
        application.graph.settings.connect(
            InstrumentationRegistry.getArguments().getString("serverAddress")
                ?: j.getString("address"), j.getString("token")
        )
        return application.graph.settings.connection.first()
    }

    private class Scenario(private val activity: MainActivity) : java.io.Closeable {
        fun onActivity(action: (MainActivity) -> Unit) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { action(activity) }
        }

        override fun close() {
            onActivity { it.finish() }
        }
    }

    // Some OEM Android builds drop Instrumentation.startActivitySync launches.
    // Shell launch plus the real lifecycle monitor keeps a bounded, observable wait.
    private suspend fun launchActivity(): Scenario {
        withContext(Dispatchers.IO) {
            instrumentation.uiAutomation.executeShellCommand("am start -n dev.vvideo/dev.vvideo.app.MainActivity")
                .use { fd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                }
        }
        val activity = withTimeout(10000) {
            var found: MainActivity? = null
            while (found == null) {
                withContext(Dispatchers.Main) {
                    found = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                        .firstOrNull()
                }
                if (found == null) delay(100)
            }
            checkNotNull(found)
        }
        return Scenario(activity)
    }

    @Test
    fun codecContainerMatrixSeekAndCacheClear() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val libs = graph.api.libraries(c)
        assertTrue(libs.isNotEmpty())
        val root = libs.first().directoryId
        val page = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        )
        assertTrue("fixtures missing", page.items.size >= 12)
        launchActivity().use { scenario ->
            val session = graph.cache.session(c.token)
            val decoder = AtomicReference("")
            withContext(Dispatchers.Main) {
                session.player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        decoder.set(decoderName)
                    }
                })
            }
            scenario.onActivity { activity ->
                activity.addContentView(
                    PlayerView(activity).apply {
                        player = session.player
                        keepScreenOn = true
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            try {
                for (item in page.items) {
                    val began = SystemClock.elapsedRealtime()
                    decoder.set("")
                    withContext(Dispatchers.Main) { session.select(item.playback(c)) }
                    val frame =
                        withTimeout(15000) { session.state.first { it.firstFrame || it.error } }
                    assertFalse("fixture ${item.name} playback failed", frame.error)
                    assertEquals(item.playback(c).cacheKey, frame.id)
                    Log.i(
                        "VVideoValidation",
                        "matrix=${item.name} first_frame_ms=${SystemClock.elapsedRealtime() - began} decoder=${decoder.get()}"
                    )
                    val seekBegan = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) { session.player.seekTo(item.durationMs * 3 / 4) }
                    delay(800)
                    val readyAt800 = withContext(Dispatchers.Main) {
                        assertNull("seek failed ${item.name}", session.player.playerError)
                        assertTrue(session.player.currentPosition >= item.durationMs / 2)
                        session.player.playbackState == Player.STATE_READY
                    }
                    if (!readyAt800) {
                        // Diagnostic only: how long the seek really took. The 800 ms check stands.
                        val readyMs = withTimeoutOrNull(10000) {
                            while (withContext(Dispatchers.Main) { session.player.playbackState } != Player.STATE_READY) {
                                delay(20)
                            }
                            SystemClock.elapsedRealtime() - seekBegan
                        }
                        fail("not ready 800 ms after seeking ${item.name}; ready after ${readyMs ?: ">10000"} ms")
                    }
                }
            } finally {
                graph.cache.release(session)
            }
        }
        val marker = BookmarkEntity(
            c.id,
            root,
            libs.first().id,
            anchor = page.items.first().id,
            pixelOffset = 123,
            width = 1440
        )
        graph.database.bookmarks().put(marker)
        graph.cache.configure(1_000_000_000L, true)
        val usage = graph.cache.usage()
        assertEquals(0, usage.video)
        assertEquals(0, usage.images)
        assertEquals(marker, graph.database.bookmarks().get(c.id, root))
        assertEquals(c.address, graph.settings.connection.first().address)
    }

    @Test
    fun downloadedVideoColdSeekMeasurements() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val items = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items.filter { it.name.endsWith("falls.webm") }
        assertEquals(2, items.size)
        val measurements = JSONArray()
        launchActivity().use { scenario ->
            repeat(3) { round ->
                for (item in items) {
                    graph.cache.configure(c.budget, true)
                    val session = graph.cache.session(c.token)
                    lateinit var view: PlayerView
                    scenario.onActivity {
                        view = PlayerView(it).apply { player = session.player }
                        it.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                    }
                    try {
                        val began = SystemClock.elapsedRealtime()
                        withContext(Dispatchers.Main) { session.select(item.playback(c)) }
                        assertFalse(withTimeout(15000) { session.state.first { it.firstFrame || it.error } }.error)
                        val frame = SystemClock.elapsedRealtime() - began
                        val target = item.durationMs * 3 / 4
                        val seekBegan = SystemClock.elapsedRealtime()
                        withContext(Dispatchers.Main) { session.player.seekTo(target) }
                        withTimeout(15000) {
                            while (!withContext(Dispatchers.Main) { session.player.playbackState == Player.STATE_READY && session.player.currentPosition >= target - 100 }) delay(
                                10
                            )
                        }
                        val seek = SystemClock.elapsedRealtime() - seekBegan
                        measurements.put(
                            JSONObject().put("file", item.name).put("round", round)
                                .put("cold_first_frame_ms", frame).put("cold_seek_ready_ms", seek)
                        )
                    } finally {
                        scenario.onActivity { (view.parent as? ViewGroup)?.removeView(view) }
                        graph.cache.release(session)
                    }
                }
            }
        }
        instrumentation.sendStatus(
            0,
            android.os.Bundle()
                .apply { putString("vvideo_downloaded_measurements", measurements.toString()) })
    }

    @Test
    fun rapidSwitchUsesOnePlayerAndCurrentVersion() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val items = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items
        launchActivity().use { scenario ->
            val session = graph.cache.session(c.token)
            scenario.onActivity {
                it.addContentView(PlayerView(it).apply {
                    player = session.player
                }, ViewGroup.LayoutParams(-1, -1))
            }
            try {
                for (i in 0..99) {
                    val item =
                        items[if (i % 2 == 0) i % items.size else items.lastIndex - i % items.size]
                    withContext(Dispatchers.Main) {
                        session.select(item.playback(c))
                        session.preload(items[(i + 1) % items.size].playback(c))
                    }
                    delay(25)
                }
                val last = items.first()
                withContext(Dispatchers.Main) { session.select(last.playback(c)) }
                val frame = withTimeout(15000) { session.state.first { it.firstFrame || it.error } }
                assertFalse(frame.error)
                assertEquals(last.playback(c).cacheKey, frame.id)
                withContext(Dispatchers.Main) {
                    assertEquals(1, session.player.mediaItemCount)
                    assertEquals(
                        last.playback(c).cacheKey,
                        session.player.currentMediaItem?.mediaId
                    )
                }
            } finally {
                graph.cache.release(session)
            }
        }
    }

    @Test
    fun platformConnectionPolicyAllowsHttp() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted)
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("192.168.1.10"))
    }

    @Test
    fun publishedWindowCanPageWhileVisitPersists() = runBlocking<Unit> {
        launchActivity().use {
            val c = connection().copy(lastDirectory = 0)
            val graph = application.graph
            val root = graph.api.libraries(c).first().directoryId
            val directories = graph.api.children(c, root).items
            for (name in listOf("large", "wide")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val dao = object :
                    dev.vvideo.data.library.local.BookmarkDao by graph.database.bookmarks() {
                    override suspend fun visit(bookmark: BookmarkEntity) {
                        entered.complete(Unit)
                        release.await()
                    }

                    override suspend fun put(bookmark: BookmarkEntity) {}
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                val store = androidx.lifecycle.ViewModelStore()
                val vm = dev.vvideo.feature.library.LibraryViewModel(
                    dev.vvideo.data.library.LibraryRepository(graph.api, dao, graph.settings),
                    scope
                )
                try {
                    withContext(Dispatchers.Main) {
                        store.put("pagination", vm)
                        vm.connect(c)
                    }
                    withTimeout(10000) { vm.state.first { it.libraries.isNotEmpty() && !it.busy } }
                    withContext(Dispatchers.Main) {
                        vm.open(
                            directories.first { it.name == name }.id,
                            Sort(SortField.Name, SortDirection.Ascending)
                        )
                    }
                    withTimeout(10000) { entered.await() }
                    assertFalse(vm.state.value.busy)
                    val before =
                        if (name == "large") vm.state.value.albums.sumOf { it.items.size } else vm.state.value.subfolders.items.size
                    withContext(Dispatchers.Main) {
                        if (name == "large") vm.page(PageEdge.Next) else vm.moreSubfolders(PageEdge.Next)
                    }
                    withTimeout(10000) { vm.state.first { if (name == "large") it.albums.sumOf { a -> a.items.size } > before else it.subfolders.items.size > before } }
                    assertFalse(
                        "Visit must still be pending when paging succeeds",
                        release.isCompleted
                    )
                    Log.i("VVideoValidation", "paging_during_visit=$name")
                } finally {
                    release.complete(Unit)
                    withContext(Dispatchers.Main) { store.clear() }
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun realUiBidirectionalWindowAndRestoration() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val large = graph.api.children(c, root).items.first { it.name == "large" }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        var saved: BookmarkEntity? = null
        var deepWindowIds = emptySet<String>()
        launchActivity().use { scenario ->
            delay(400)
            lateinit var vm: dev.vvideo.feature.library.LibraryViewModel
            scenario.onActivity {
                vm =
                    androidx.lifecycle.ViewModelProvider(it)[dev.vvideo.feature.library.LibraryViewModel::class.java]
                vm.open(large.id, Sort(SortField.Name, SortDirection.Ascending))
            }
            withTimeout(15000) { vm.state.first { it.directory?.id == large.id && !it.busy && it.albums.isNotEmpty() } }
            val first = vm.state.value.albums.first().items.first().id
            // Albums are as tall as the device's grouped layout makes them, so swipe until
            // the window has dropped its first page rather than a fixed number of times.
            var swipes = 0
            while (swipes < 200 && vm.state.value.albums.first().items.first().id == first) {
                swipes++
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    12
                )
                delay(80)
                val items = vm.state.value.albums.flatMap { it.items }
                assertTrue(items.size <= 300)
                assertEquals(items.size, items.map { it.id }.toSet().size)
            }
            val shown = vm.state.value
            assertNotEquals(
                "did not cross retained window after $swipes swipes: items=${shown.albums.sumOf { it.items.size }} " +
                    "hasPrevious=${shown.hasPrevious} pageBusy=${shown.pageBusy} error=${shown.error}",
                first,
                vm.state.value.albums.first().items.first().id
            )
            Log.i("VVideoValidation", "ui_window_crossed_after_swipes=$swipes")
            delay(800)
            saved = graph.database.bookmarks().get(c.id, large.id)
            assertTrue(saved?.anchor?.isNotEmpty() == true)
            deepWindowIds = vm.state.value.albums.flatMap { it.items }.map { it.id }.toSet()

        }
        delay(700) // Allow the Activity close to persist its last visible anchor.
        val beforeStop = saved?.anchor
        saved = graph.database.bookmarks().get(c.id, large.id)
        assertTrue(
            "ON_STOP anchor must remain inside the deep window",
            saved?.anchor in deepWindowIds
        )
        Log.i("VVideoValidation", "stop_flushed_newer_anchor=${beforeStop != saved?.anchor}")
        launchActivity().use { scenario ->
            delay(500)
            lateinit var vm: dev.vvideo.feature.library.LibraryViewModel
            scenario.onActivity {
                vm =
                    androidx.lifecycle.ViewModelProvider(it)[dev.vvideo.feature.library.LibraryViewModel::class.java]
            }
            val restored =
                withTimeout(15000) { vm.state.first { it.directory?.id == large.id && !it.busy && it.albums.isNotEmpty() } }
            assertTrue(
                "deep bookmark not restored",
                restored.albums.any { a -> a.items.any { it.id == saved?.anchor } })
            assertTrue(restored.albums.sumOf { it.items.size } <= 300)
            Log.i(
                "VVideoValidation",
                "ui_window_restored=true retained_items=${restored.albums.sumOf { it.items.size }}"
            )
            val firstPage = graph.api.page(
                c,
                large.id,
                Sort(SortField.Name, SortDirection.Ascending)
            ).items.first().id
            repeat(140) { step ->
                if (vm.state.value.albums.first().items.first().id != firstPage) {
                    device.swipe(
                        device.displayWidth / 2,
                        device.displayHeight / 3,
                        device.displayWidth / 2,
                        device.displayHeight * 3 / 4,
                        12
                    )
                    delay(80)
                }
                if (step % 20 == 0) {
                    val s = vm.state.value
                    Log.i(
                        "VVideoValidation",
                        "reverse_step=$step first=${s.albums.first().items.first().name} last=${s.albums.last().items.last().name} previous=${s.hasPrevious} busy=${s.pageBusy} error=${s.error}"
                    )
                }
            }
            device.takeScreenshot(
                java.io.File(
                    instrumentation.targetContext.getExternalFilesDir(null), "reverse-window.png"
                )
            )
            device.dumpWindowHierarchy(
                java.io.File(
                    instrumentation.targetContext.getExternalFilesDir(null), "reverse-window.xml"
                )
            )
            withTimeout(10000) { vm.state.first { it.albums.firstOrNull()?.items?.firstOrNull()?.id == firstPage } }
            assertTrue(vm.state.value.albums.sumOf { it.items.size } <= 300)
            Log.i("VVideoValidation", "ui_window_reverse_to_start=true")
        }
    }

    // Opened on the last video, the anchored page can hold that video alone. The
    // viewer then reports both edges in one pass and repeats neither, so the
    // previous edge must survive the empty answer for the next one.
    @Test
    fun bothEdgesAskedAtOnceStillLoadPrevious() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val videos = graph.api.page(c, root, Sort(), kind = MediaKind.Video).items
        org.junit.Assume.assumeTrue("needs a root holding one page of videos", videos.size in 2..99)
        val vm = dev.vvideo.feature.player.PlayerViewModel(graph.library)
        try {
            withContext(Dispatchers.Main) {
                vm.open(
                    c,
                    root,
                    Sort(),
                    videos.last().id,
                    MediaKind.Video
                )
            }
            val opened = withTimeout(10000) { vm.state.first { it.items.isNotEmpty() } }.items.size
            withContext(Dispatchers.Main) {
                vm.page(PageEdge.Next)
                vm.page(PageEdge.Previous)
            }
            withTimeout(10000) { vm.state.first { it.items.firstOrNull()?.id == videos.first().id && it.endReached } }
            assertEquals(videos.map { it.id }, vm.state.value.items.map { it.id })
            Log.i(
                "VVideoValidation",
                "both_edges_previous_loaded=true opened_items=$opened total=${videos.size}"
            )
        } finally {
            withContext(Dispatchers.Main) { vm.leave() }
        }
    }

    @Test
    fun feedWindowCancellationAndReverse() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val feed = graph.api.children(c, root).items.first { it.name == "feed" }
        val firstPage = graph.api.page(
            c,
            feed.id,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        )
        val vm = dev.vvideo.feature.player.PlayerViewModel(graph.library)
        try {
            withContext(Dispatchers.Main) {
                vm.open(
                    c,
                    feed.id,
                    Sort(SortField.Name, SortDirection.Ascending),
                    firstPage.items.first().id,
                    MediaKind.Video
                )
            }
            withTimeout(10000) { vm.state.first { it.items.size == 100 } }
            repeat(4) {
                val last = vm.state.value.items.last().id
                withContext(Dispatchers.Main) { vm.page(PageEdge.Next) }
                withTimeout(10000) { vm.state.first { it.items.lastOrNull()?.id != last } }
                assertTrue(vm.state.value.items.size <= 300)
            }
            withContext(Dispatchers.Main) { vm.page(PageEdge.Next) }
            delay(300)
            assertNotEquals(firstPage.items.first().id, vm.state.value.items.first().id)
            repeat(3) {
                if (vm.state.value.items.first().id != firstPage.items.first().id) {
                    val first = vm.state.value.items.first().id
                    withContext(Dispatchers.Main) { vm.page(PageEdge.Previous) }
                    withTimeout(10000) { vm.state.first { it.items.firstOrNull()?.id != first } }
                    assertTrue(vm.state.value.items.size <= 300)
                }
            }
            assertEquals(firstPage.items.first().id, vm.state.value.items.first().id)
            withContext(Dispatchers.Main) {
                vm.page(PageEdge.Next)
                vm.leave()
            }
            delay(500)
            assertTrue(vm.state.value.items.isEmpty())
            assertFalse(vm.state.value.error)
            withContext(Dispatchers.Main) {
                vm.open(
                    c,
                    feed.id,
                    Sort(SortField.Name, SortDirection.Ascending),
                    firstPage.items.first().id,
                    MediaKind.Video
                )
                vm.open(c, root, Sort(SortField.Name, SortDirection.Ascending), "", MediaKind.Video)
            }
            val current = withTimeout(10000) { vm.state.first { it.items.isNotEmpty() } }
            assertTrue(current.items.all { it.directoryId == root })
            Log.i("VVideoValidation", "feed_window_reverse_and_cancel=true")
        } finally {
            withContext(Dispatchers.Main) { vm.leave() }
        }
    }

    @Test
    fun diskReuseAcrossBudgetsAndClear() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val item = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items.first()
        val originalBudget = graph.settings.connection.first().budget
        try {
            graph.cache.configure(1_000_000_000L, true)
            launchActivity().use { scenario ->
                val session = graph.cache.session(c.token)
                scenario.onActivity {
                    it.addContentView(PlayerView(it).apply {
                        player = session.player
                    }, ViewGroup.LayoutParams(-1, -1))
                }
                try {
                    withContext(Dispatchers.Main) { session.select(item.playback(c)) }
                    assertFalse(withTimeout(15000) { session.state.first { it.firstFrame || it.error } }.error)
                    delay(500)
                } finally {
                    graph.cache.release(session)
                }
            }
            val cached = graph.cache.cachedVideoBytes(item.playback(c).cacheKey)
            assertTrue(cached > 0)
            for (budget in listOf(5_000_000_000L, 10_000_000_000L, 1_000_000_000L)) {
                graph.cache.configure(budget)
                graph.settings.setBudget(budget)
                assertEquals(budget, graph.settings.connection.first().budget)
                assertTrue(
                    "cache not reused after reopen/resize",
                    graph.cache.cachedVideoBytes(item.playback(c).cacheKey) >= cached
                )
            }
            val bookmark = graph.database.bookmarks().get(c.id, root)
            graph.cache.configure(1_000_000_000L, true)
            assertEquals(0, graph.cache.usage().video)
            assertEquals(0, graph.cache.usage().images)
            val after = graph.database.bookmarks().get(c.id, root)
            assertEquals(bookmark?.copy(savedAt = after?.savedAt ?: 0), after)
            assertEquals(c.address, graph.settings.connection.first().address)
            Log.i("VVideoValidation", "disk_reopen_budget_1_5_10_clear=true")
        } finally {
            graph.cache.configure(originalBudget)
            graph.settings.setBudget(originalBudget)
        }
    }

    @Test
    fun realUiVideoTransitions() = runBlocking<Unit> {
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout = 250
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val videos = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        launchActivity().use { scenario ->
            delay(400)
            lateinit var library: dev.vvideo.feature.library.LibraryViewModel
            scenario.onActivity {
                library =
                    androidx.lifecycle.ViewModelProvider(it)[dev.vvideo.feature.library.LibraryViewModel::class.java]
                library.open(root, Sort(SortField.Name, SortDirection.Ascending))
            }
            withTimeout(10000) { library.state.first { !it.busy && it.directory?.id == root && it.albums.isNotEmpty() } }
            delay(500)
            val selected = videos.first()
            val target = checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.descContains(selected.name)
                    ), 10000
                )
            ).visibleBounds
            val began = SystemClock.elapsedRealtime()
            assertTrue(device.click(target.centerX(), target.centerY()))
            suspend fun active(): PlaybackSession = withTimeout(15000) {
                var found: PlaybackSession? = null
                while (found == null) {
                    withContext(Dispatchers.Main) {
                        val field = graph.cache.javaClass.getDeclaredField("sessions")
                            .apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST") val sessions =
                            field.get(graph.cache) as Set<PlaybackSession>
                        assertTrue(sessions.size <= 1)
                        found = sessions.singleOrNull()
                    }
                    if (found == null) delay(30)
                }
                checkNotNull(found)
            }

            val session = active()
            assertFalse(withTimeout(15000) { session.state.first { it.firstFrame || it.error } }.error)
            Log.i(
                "VVideoValidation",
                "ui_click_first_frame_ms=${SystemClock.elapsedRealtime() - began}"
            )
            checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc("媒体详情")
                    ), 5000
                )
            ).click()
            assertTrue(
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.text("所在文件夹")
                    ), 5000
                )
            )
            assertTrue(device.hasObject(androidx.test.uiautomator.By.text(selected.name)))
            checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc("关闭")
                    ), 5000
                )
            ).click()
            Log.i("VVideoValidation", "real_ui_time_source_details=true")
            checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc("暂停")
                    ), 5000
                )
            ).click()
            withTimeout(3000) {
                while (withContext(Dispatchers.Main) { session.player.isPlaying }) delay(20)
            }
            checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc("播放")
                    ), 5000
                )
            ).click()
            withTimeout(3000) {
                while (!withContext(Dispatchers.Main) { session.player.isPlaying }) delay(20)
            }
            val slider = checkNotNull(
                device.wait(
                    androidx.test.uiautomator.Until.findObject(
                        androidx.test.uiautomator.By.desc("播放进度")
                    ), 5000
                )
            ).visibleBounds
            device.swipe(
                slider.left + slider.width() / 4,
                slider.centerY(),
                slider.left + slider.width() * 3 / 4,
                slider.centerY(),
                20
            )
            withTimeout(5000) {
                while (!withContext(Dispatchers.Main) { session.player.currentPosition >= selected.durationMs / 2 && session.player.playbackState == Player.STATE_READY }) delay(
                    30
                )
            }
            Log.i("VVideoValidation", "real_ui_pause_resume_seek=true")
            // Exercise the UI controls and tap gestures against the same session.
            val speedSelector = androidx.test.uiautomator.By.desc("播放速度")
            for (expected in listOf(1.5f, 2f, .5f, 1f, 1.5f)) {
                checkNotNull(
                    device.wait(
                        androidx.test.uiautomator.Until.findObject(speedSelector),
                        3000
                    )
                ).click()
                checkNotNull(
                    device.wait(
                        androidx.test.uiautomator.Until.findObject(
                            androidx.test.uiautomator.By.text(
                                dev.vvideo.feature.player.speedLabel(expected) + "×"
                            )
                        ), 3000
                    )
                ).click()
                withTimeout(2000) {
                    while (withContext(Dispatchers.Main) { session.player.playbackParameters.speed != expected }) delay(
                        20
                    )
                }
            }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = android.view.MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    device.displayWidth * .5f,
                    device.displayHeight * .4f,
                    0
                )
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN)
            try {
                delay(700)
                assertEquals(
                    2f,
                    withContext(Dispatchers.Main) { session.player.playbackParameters.speed },
                    0f
                )
            } finally {
                touch(android.view.MotionEvent.ACTION_UP)
            }
            withTimeout(2000) {
                while (withContext(Dispatchers.Main) { session.player.playbackParameters.speed != 1.5f }) delay(
                    20
                )
            }
            // Pause through the middle double-tap, away from the centered pause overlay.
            val gestureY = device.displayHeight * 2 / 5
            device.click(device.displayWidth / 2, gestureY)
            delay(70)
            device.click(device.displayWidth / 2, gestureY)
            withTimeout(3000) {
                while (withContext(Dispatchers.Main) { session.player.isPlaying }) delay(20)
            }
            val beforeSeek = withContext(Dispatchers.Main) { session.player.currentPosition }
            device.click(device.displayWidth / 6, gestureY)
            delay(70)
            device.click(device.displayWidth / 6, gestureY)
            withTimeout(3000) {
                while (withContext(Dispatchers.Main) {
                        session.player.currentPosition > (beforeSeek - 10000).coerceAtLeast(0) + 300
                    }) delay(20)
            }
            if (selected.displayRatio > 1f) {
                checkNotNull(
                    device.wait(
                        androidx.test.uiautomator.Until.findObject(
                            androidx.test.uiautomator.By.desc("切换横屏")
                        ), 3000
                    )
                ).click()
                withTimeout(5000) {
                    while (instrumentation.targetContext.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) delay(
                        50
                    )
                }
                assertSame(session, active())
                device.pressBack()
                withTimeout(5000) {
                    while (instrumentation.targetContext.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) delay(
                        50
                    )
                }
                assertSame(session, active())
            }
            withContext(Dispatchers.Main) { session.player.play() }
            Log.i("VVideoValidation", "ui_speed_hold_double_tap_orientation=true")
            for (reverse in listOf(false, true)) repeat(12) {
                device.swipe(
                    device.displayWidth / 2,
                    if (reverse) device.displayHeight / 3 else device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    if (reverse) device.displayHeight * 3 / 4 else device.displayHeight / 3,
                    20
                )
                delay(500)
                withTimeout(15000) {
                    while (!withContext(Dispatchers.Main) { session.state.value.error || session.state.value.firstFrame && !session.state.value.buffering && session.player.isPlaying }) delay(
                        30
                    )
                }
                delay(300)
                val current = withContext(Dispatchers.Main) {
                    val snapshot = session.state.value
                    assertFalse(snapshot.error)
                    assertEquals(1, session.player.mediaItemCount)
                    assertEquals(snapshot.id, session.player.currentMediaItem?.mediaId)
                    snapshot
                }
                val media = videos.firstOrNull { it.playback(c).cacheKey == current.id }
                assertNotNull("unknown or wrong-version active media", media)
                if (!device.hasObject(androidx.test.uiautomator.By.desc("媒体详情"))) {
                    device.click(device.displayWidth / 2, device.displayHeight / 2)
                    delay(150)
                }
                withContext(Dispatchers.Main) {
                    assertEquals(
                        1f,
                        session.player.playbackParameters.speed,
                        0f
                    )
                }
                assertTrue(
                    "visible title disagrees with active media",
                    device.hasObject(androidx.test.uiautomator.By.text(checkNotNull(media).name))
                )
            }
            device.pressBack()
            delay(800)
            assertTrue(library.state.value.directory?.id == root)
            Log.i("VVideoValidation", "ui_video_forward_reverse_version_match=true")
        }
    }

    @Test
    fun controlledFirstFrameCacheScenarios() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val rootItems = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items
        val extra = graph.api.children(c, root).items.firstOrNull { it.name == "representative" }
        val items = rootItems + (extra?.let {
            graph.api.page(
                c,
                it.id,
                Sort(SortField.Name, SortDirection.Ascending),
                kind = MediaKind.Video
            ).items
        } ?: emptyList())
        assertTrue(items.isNotEmpty())
        assertTrue("fixtures still awaiting probe", items.all { it.durationMs > 0 })
        // Explicit bounded diagnostics reuse the same timing path; normal acceptance
        // always retains all fixtures, three rounds and all cache phases.
        val arguments = InstrumentationRegistry.getArguments()
        val names = arguments.getString("diagnosticFixtures")?.split(",")
        val selected = if (names == null) items else {
            require(names.size in 1..2 && names.toSet().size == names.size)
            items.filter { it.name in names }
                .also { require(it.size == names.size) { "Unknown diagnostic fixture" } }
        }
        val rounds =
            if (names == null) 3 else (arguments.getString("diagnosticRounds") ?: "30").toInt()
                .also { require(it in 1..30) }
        // "switch" repeats the prefetch procedure without preloading. Cold and disk start
        // a fresh player, while prefetch first tears down the playing video, so only
        // switch isolates what the preload saves.
        val phases = if (names == null) listOf(
            "cold",
            "disk",
            "prefetch",
            "switch"
        ) else listOf(
            arguments.getString("diagnosticPhase") ?: "cold"
        ).also { require(it.single() in listOf("cold", "disk", "prefetch", "switch")) }
        val measurements = JSONArray()
        fun record(
            phase: String,
            round: Int,
            item: dev.vvideo.model.Media,
            metric: String,
            elapsed: Long
        ) {
            if (phase != "warmup") measurements.put(
                JSONObject().put("phase", phase).put("round", round).put("fixture", item.name)
                    .put("metric", metric).put("ms", elapsed)
            )
        }
        launchActivity().use { scenario ->
            lateinit var surface: PlayerView
            scenario.onActivity { activity ->
                surface = PlayerView(activity).apply { keepScreenOn = true }
                activity.addContentView(surface, ViewGroup.LayoutParams(-1, -1))
            }
            suspend fun select(
                session: PlaybackSession,
                item: dev.vvideo.model.Media,
                phase: String,
                round: Int
            ): Long {
                val began = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) { session.select(item.playback(c)) }
                val frame = withTimeout(15000) { session.state.first { it.firstFrame || it.error } }
                assertFalse("$phase failed ${item.name}", frame.error)
                val elapsed = SystemClock.elapsedRealtime() - began
                record(phase, round, item, "first_frame_ms", elapsed)
                Log.i(
                    "VVideoValidation",
                    "cache_phase=$phase round=$round fixture=${item.name} first_frame_ms=$elapsed"
                )
                return elapsed
            }

            suspend fun seek(
                session: PlaybackSession,
                item: dev.vvideo.model.Media,
                phase: String,
                round: Int
            ) {
                val cached =
                    if (phase == "disk") graph.cache.cachedVideoBytes(item.playback(c).cacheKey) else null
                if (cached != null) assertTrue(
                    "Disk diagnostic is not fully cached",
                    cached >= item.size
                )
                val target = item.durationMs * 3 / 4
                val began = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) { session.player.seekTo(target) }
                withTimeout(2000) {
                    while (!withContext(Dispatchers.Main) { session.player.playbackState == Player.STATE_READY && session.player.currentPosition >= target - 100 }) delay(
                        10
                    )
                }
                val elapsed = SystemClock.elapsedRealtime() - began
                withContext(Dispatchers.Main) {
                    assertEquals(
                        "seek changed playback speed",
                        1f,
                        session.player.playbackParameters.speed,
                        0f
                    )
                    assertEquals(
                        "seek changed audio pitch",
                        1f,
                        session.player.playbackParameters.pitch,
                        0f
                    )
                }
                record(phase, round, item, "seek_ready_ms", elapsed)
                if (cached != null) measurements.getJSONObject(measurements.length() - 1)
                    .put("cached_bytes_before_seek", cached).put("source_size", item.size)
                Log.i(
                    "VVideoValidation",
                    "cache_phase=$phase round=$round fixture=${item.name} seek_ready_ms=$elapsed"
                )
            }
            for (round in 1..rounds) for (phase in phases) for (item in selected) {
                graph.cache.configure(1_000_000_000L, true)
                val session = graph.cache.session(c.token)
                withContext(Dispatchers.Main) { surface.player = session.player }
                try {
                    if (phase == "disk") {
                        select(session, item, "warmup", round)
                        delay(350)
                        if (names != null) withTimeout(15000) {
                            while (graph.cache.cachedVideoBytes(
                                    item.playback(c).cacheKey
                                ) < item.size
                            ) delay(50)
                        }
                        withContext(Dispatchers.Main) {
                            session.player.stop()
                            session.player.clearMediaItems()
                        }
                        graph.cache.release(session)
                        val warmed = graph.cache.session(c.token)
                        withContext(Dispatchers.Main) { surface.player = warmed.player }
                        try {
                            select(warmed, item, phase, round)
                            if (names != null) seek(warmed, item, phase, round)
                        } finally {
                            graph.cache.release(warmed)
                        }
                    } else if (phase == "prefetch" || phase == "switch") {
                        val preceding = items.first { it.id != item.id }
                        select(session, preceding, "warmup", round)
                        if (phase == "prefetch") withContext(Dispatchers.Main) { session.preload(item.playback(c)) }
                        delay(2000)
                        val cached = graph.cache.cachedVideoBytes(item.playback(c).cacheKey)
                        if (phase == "prefetch") assertTrue("prefetch produced no cache", cached > 0)
                        else assertEquals("switch target was cached", 0L, cached)
                        select(session, item, phase, round)
                    } else select(session, item, phase, round)
                    val active = if (phase == "disk") null else session
                    if (active != null) seek(active, item, phase, round)
                } finally {
                    withContext(Dispatchers.Main) { surface.player = null }
                    graph.cache.release(session)
                }
            }
        }
        // Logcat may prune or drop rows on the OEM. Return the bounded, complete
        // measurement set through instrumentation IPC after timing has finished.
        val report = measurements.toString()
        require(report.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Measurement report exceeds bounded IPC budget" }
        instrumentation.addResults(
            android.os.Bundle().apply { putString("vvideo_measurements", report) })
    }

    @Test
    fun isolatedCacheEvictionAndVersionKeys() = runBlocking<Unit> {
        val directory = withContext(Dispatchers.IO) {
            File(
                application.cacheDir,
                "eviction-fixture"
            ).apply { mkdirs() }
        }
        val context = object : android.content.ContextWrapper(application) {
            override fun getCacheDir(): File = directory
        }
        var cache = dev.vvideo.core.media.cache.VideoCache.open(context, 64 * 1024L)
        try {
            suspend fun fill(key: String, value: Byte) = withContext(Dispatchers.IO) {
                val span = checkNotNull(cache.cache.startReadWrite(key, 0, 40 * 1024L))
                assertTrue(span.isHoleSpan)
                try {
                    val file = cache.cache.startFile(key, 0, 40 * 1024L)
                    file.writeBytes(ByteArray(40 * 1024) { value })
                    cache.cache.commitFile(file, 40 * 1024L)
                } finally {
                    cache.cache.releaseHoleSpan(span)
                }
            }
            fill("fixture:old-version", 1)
            assertEquals(40 * 1024L, cache.bytesFor("fixture:old-version"))
            fill("fixture:new-version", 2)
            assertEquals(0L, cache.bytesFor("fixture:old-version"))
            assertEquals(40 * 1024L, cache.bytesFor("fixture:new-version"))
            assertTrue(cache.bytes() <= 64 * 1024L)
            cache.close(false)
            cache = dev.vvideo.core.media.cache.VideoCache.open(context, 64 * 1024L)
            assertEquals(40 * 1024L, cache.bytesFor("fixture:new-version"))
            assertEquals(0L, cache.bytesFor("fixture:old-version"))
            fill("fixture:third", 3)
            assertEquals(0L, cache.bytesFor("fixture:new-version"))
            assertTrue(cache.bytes() <= 64 * 1024L)
            Log.i("VVideoValidation", "isolated_lru_version_reopen=true")
        } finally {
            cache.close(true)
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    @Test
    fun sessionCreationCancellationReleasesPlayer() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val callerStarted = java.util.concurrent.CountDownLatch(1)
        val workerBlocked = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        var caller: Job? = null
        try {
            // Hold the caller's executor after it suspends to Main. This makes the
            // created player's return pending while cancellation arrives; no sleeps
            // or probabilistic rapid taps are needed to expose abandoned ownership.
            withContext(Dispatchers.Main) {
                caller = scope.launch {
                    callerStarted.countDown()
                    graph.cache.session(c.token)
                }
                assertTrue(callerStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
                executor.submit {
                    workerBlocked.countDown()
                    unblock.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                assertTrue(workerBlocked.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
            withTimeout(5000) {
                while (true) {
                    val created = withContext(Dispatchers.Main) {
                        val field = graph.cache.javaClass.getDeclaredField("sessions")
                            .apply { isAccessible = true }
                        (field.get(graph.cache) as Set<*>).isNotEmpty()
                    }
                    if (created) break
                    delay(20)
                }
            }
            caller!!.cancel()
            unblock.countDown()
            caller!!.join()
            withContext(Dispatchers.Main) {
                val field =
                    graph.cache.javaClass.getDeclaredField("sessions").apply { isAccessible = true }
                assertTrue(
                    "cancelled creation retained a player",
                    (field.get(graph.cache) as Set<*>).isEmpty()
                )
            }
            Log.i("VVideoValidation", "session_creation_cancel_released=true")
        } finally {
            unblock.countDown()
            scope.cancel()
            withContext(NonCancellable) {
                caller?.join()
                graph.cache.configure(c.budget)
            }
            dispatcher.close()
        }
    }

    @Test
    fun invalidConnectionReportsConnectionError() = runBlocking<Unit> {
        withTimeout(15000) { application.ready.first { it } }
        val graph = application.graph
        val before = graph.settings.connection.first()
        lateinit var vm: dev.vvideo.feature.settings.SettingsViewModel
        withContext(Dispatchers.Main) {
            vm = dev.vvideo.feature.settings.SettingsViewModel(
                graph.settings,
                graph.cache,
                graph.library
            )
            // A server root only: a path makes the address invalid before any request.
            vm.connect("http://example.org/library", "synthetic-unused")
        }
        val result = withTimeout(5000) { vm.state.first { it.message != null && !it.busy } }
        assertEquals(dev.vvideo.feature.settings.SettingsMessage.InvalidAddress, result.message)
        assertEquals(before, graph.settings.connection.first())
        Log.i("VVideoValidation", "invalid_connection_preserved_and_correct_error=true")
    }

    @Test
    fun routeDisposalBeforeSessionRecompositionReleasesPlayer() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        graph.cache.configure(c.budget)
        val visible = mutableStateOf(true)
        try {
            launchActivity().use { scenario ->
                delay(500)
                scenario.onActivity { activity ->
                    val testVm = dev.vvideo.feature.player.PlayerViewModel(graph.library)
                    val testLoader = checkNotNull(graph.cache.loader.value)
                    activity.setContent {
                        if (visible.value) dev.vvideo.feature.player.PlayerRoute(
                            testVm, c, testLoader,
                            create = { token ->
                                graph.cache.session(token).also {
                                    Log.i("VVideoValidation", "route_test_created=true")
                                    visible.value = false
                                }
                            },
                            release = graph.cache::release,
                            onBack = { visible.value = false }, onInteraction = {})
                        else androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) { androidx.compose.material3.Text("route removed") }
                    }
                }
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                withTimeout(5000) { while (visible.value) delay(20) }
                assertTrue(
                    device.wait(
                        androidx.test.uiautomator.Until.hasObject(
                            androidx.test.uiautomator.By.text(
                                "route removed"
                            )
                        ), 5000
                    )
                )
                val released = withTimeoutOrNull(3000) {
                    while (true) {
                        val empty = withContext(Dispatchers.Main) {
                            val field = graph.cache.javaClass.getDeclaredField("sessions")
                                .apply { isAccessible = true }
                            (field.get(graph.cache) as Set<*>).isEmpty()
                        }
                        if (empty) break
                        delay(20)
                    }
                    true
                } ?: false
                assertTrue(
                    "route disposed before session recomposition retained a player",
                    released
                )
                Log.i("VVideoValidation", "route_dispose_before_recomposition_released=true")
            }
        } finally {
            withContext(NonCancellable) { graph.cache.configure(c.budget) }
        }
    }

    @Test
    fun unsupportedVideoShowsErrorAndNextRecovers() = runBlocking<Unit> {
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val negative = graph.api.children(c, root).items.first { it.name == "negative" }
        val bad = graph.api.page(
            c,
            negative.id,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items.single()
        val good = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items.first()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        launchActivity().use { scenario ->
            delay(400)
            lateinit var library: dev.vvideo.feature.library.LibraryViewModel
            scenario.onActivity {
                library =
                    androidx.lifecycle.ViewModelProvider(it)[dev.vvideo.feature.library.LibraryViewModel::class.java]
            }
            suspend fun select(directory: Long, item: dev.vvideo.model.Media) {
                scenario.onActivity {
                    library.open(
                        directory,
                        Sort(SortField.Name, SortDirection.Ascending)
                    )
                }
                withTimeout(15000) { library.state.first { it.directory?.id == directory && !it.busy && it.albums.isNotEmpty() } }
                delay(500)
                checkNotNull(
                    device.wait(
                        androidx.test.uiautomator.Until.findObject(
                            androidx.test.uiautomator.By.descContains(item.name)
                        ), 10000
                    )
                ).click()
            }
            select(negative.id, bad)
            assertTrue(
                "unsupported codec lacks visible error",
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.textStartsWith("无法播放这个媒体")
                    ), 15000
                )
            )
            device.pressBack()
            delay(700)
            select(root, good)
            withTimeout(15000) {
                while (true) {
                    val recovered = withContext(Dispatchers.Main) {
                        val field = graph.cache.javaClass.getDeclaredField("sessions")
                            .apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST") val sessions =
                            field.get(graph.cache) as Set<PlaybackSession>
                        assertTrue(sessions.size <= 1)
                        sessions.singleOrNull()?.let {
                            !it.state.value.error && it.state.value.firstFrame && it.state.value.id == good.playback(
                                c
                            ).cacheKey && it.player.isPlaying
                        } == true
                    }
                    if (recovered) break
                    delay(30)
                }
            }
            Log.i("VVideoValidation", "unsupported_ui_error_and_recovery=true")
            device.pressBack()
        }
    }

    @Test
    fun sustainedUiBrowsing() = runBlocking<Unit> {
        val minutes =
            InstrumentationRegistry.getArguments().getString("soakMinutes")?.toIntOrNull() ?: 0
        org.junit.Assume.assumeTrue("Explicit long-run duration required", minutes in 1..60)
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val children = graph.api.children(c, root).items
        val large = children.first { it.name == "large" }
        val representative = children.first { it.name == "representative" }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        launchActivity().use { scenario ->
            delay(400)
            lateinit var library: dev.vvideo.feature.library.LibraryViewModel
            scenario.onActivity {
                library =
                    androidx.lifecycle.ViewModelProvider(it)[dev.vvideo.feature.library.LibraryViewModel::class.java]
            }
            suspend fun open(id: Long) {
                scenario.onActivity {
                    library.open(
                        id,
                        Sort(SortField.Name, SortDirection.Ascending)
                    )
                }
                withTimeout(15000) { library.state.first { it.directory?.id == id && !it.busy && it.albums.isNotEmpty() } }
                delay(500)
            }

            suspend fun playing(): PlaybackSession = withTimeout(15000) {
                var found: PlaybackSession? = null
                while (found == null) {
                    withContext(Dispatchers.Main) {
                        val field = graph.cache.javaClass.getDeclaredField("sessions")
                            .apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST") val sessions =
                            field.get(graph.cache) as Set<PlaybackSession>
                        assertTrue("more than one live player", sessions.size <= 1)
                        found = sessions.singleOrNull()
                            ?.takeIf { it.state.value.firstFrame && !it.state.value.error && it.player.isPlaying }
                    }
                    if (found == null) delay(50)
                }
                checkNotNull(found)
            }

            val until = SystemClock.elapsedRealtime() + minutes * 60000
            var cycles = 0
            while (SystemClock.elapsedRealtime() < until) {
                open(large.id)
                for (reverse in listOf(false, true)) repeat(12) {
                    device.swipe(
                        device.displayWidth / 2,
                        if (reverse) device.displayHeight / 3 else device.displayHeight * 3 / 4,
                        device.displayWidth / 2,
                        if (reverse) device.displayHeight * 3 / 4 else device.displayHeight / 3,
                        20
                    )
                    delay(100)
                    assertTrue(library.state.value.albums.sumOf { it.items.size } <= 300)
                    assertFalse(library.state.value.error)
                }
                for (directory in listOf(root, representative.id)) {
                    open(directory)
                    val video =
                        library.state.value.albums.flatMap { it.items }
                            .first { it.kind == MediaKind.Video }
                    val target = checkNotNull(
                        device.wait(
                            androidx.test.uiautomator.Until.findObject(
                                androidx.test.uiautomator.By.descContains(video.name)
                            ), 10000
                        )
                    ).visibleBounds
                    assertTrue(device.click(target.centerX(), target.centerY()))
                    playing()
                    delay(2000)
                    val expected =
                        graph.api.page(
                            c,
                            directory,
                            Sort(SortField.Name, SortDirection.Ascending),
                            kind = MediaKind.Video
                        ).items
                    for (reverse in listOf(false, true)) repeat(if (directory == root) 12 else 2) {
                        device.swipe(
                            device.displayWidth / 2,
                            if (reverse) device.displayHeight / 3 else device.displayHeight * 3 / 4,
                            device.displayWidth / 2,
                            if (reverse) device.displayHeight * 3 / 4 else device.displayHeight / 3,
                            20
                        )
                        delay(700)
                        val session = playing()
                        delay(700)
                        withContext(Dispatchers.Main) {
                            assertNull(session.player.playerError)
                            assertEquals(1, session.player.mediaItemCount)
                            assertEquals(
                                session.state.value.id,
                                session.player.currentMediaItem?.mediaId
                            )
                            assertTrue(expected.any { it.playback(c).cacheKey == session.state.value.id })
                        }
                    }
                    device.pressBack()
                    delay(700)
                    withContext(Dispatchers.Main) {
                        val field = graph.cache.javaClass.getDeclaredField("sessions")
                            .apply { isAccessible = true }
                        assertTrue(
                            "player retained after leaving route",
                            (field.get(graph.cache) as Set<*>).isEmpty()
                        )
                    }
                }
                cycles++
                Log.i(
                    "VVideoValidation",
                    "ui_soak_cycle=$cycles cache_bytes=${graph.cache.usage().video}"
                )
            }
        }
    }

    @Test
    fun sustainedPlayback() = runBlocking<Unit> {
        val minutes =
            InstrumentationRegistry.getArguments().getString("soakMinutes")?.toIntOrNull() ?: 0
        org.junit.Assume.assumeTrue("Explicit long-run duration required", minutes in 1..60)
        val c = connection()
        val graph = application.graph
        val root = graph.api.libraries(c).first().directoryId
        val extra = graph.api.children(c, root).items.firstOrNull { it.name == "representative" }
        val items = graph.api.page(
            c,
            root,
            Sort(SortField.Name, SortDirection.Ascending),
            kind = MediaKind.Video
        ).items + (extra?.let {
            graph.api.page(
                c,
                it.id,
                Sort(SortField.Name, SortDirection.Ascending),
                kind = MediaKind.Video
            ).items
        } ?: emptyList())
        launchActivity().use { scenario ->
            val session = graph.cache.session(c.token)
            scenario.onActivity {
                it.addContentView(PlayerView(it).apply {
                    player = session.player
                    keepScreenOn = true
                }, ViewGroup.LayoutParams(-1, -1))
            }
            try {
                val until = SystemClock.elapsedRealtime() + minutes * 60000
                var index = 0
                while (SystemClock.elapsedRealtime() < until) {
                    val item = items[index % items.size]
                    withContext(Dispatchers.Main) { session.select(item.playback(c)) }
                    val frame =
                        withTimeout(15000) { session.state.first { it.firstFrame || it.error } }
                    assertFalse("soak failed", frame.error)
                    withContext(Dispatchers.Main) {
                        session.preload(
                            items[(index + 1) % items.size].playback(c)
                        )
                    }
                    delay(5000)
                    index++
                    if (index % 12 == 0) Log.i(
                        "VVideoValidation",
                        "soak_cycle=$index cache_bytes=${graph.cache.usage().video}"
                    )
                }
            } finally {
                graph.cache.release(session)
            }
        }
    }
}
