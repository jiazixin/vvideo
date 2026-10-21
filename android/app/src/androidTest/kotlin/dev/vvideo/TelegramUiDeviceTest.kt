package dev.vvideo

import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.vvideo.app.*
import dev.vvideo.data.library.PageEdge
import dev.vvideo.feature.library.LibraryViewModel
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import dev.vvideo.model.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class TelegramUiDeviceTest {
    @Test
    fun filtersPreserveBookmarksAndHomeThemeStorageReturn() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VVideoApplication
        withTimeout(15000) { app.ready.first { it } }
        val c = app.graph.settings.connection.first()
        val root = app.graph.api.libraries(c).first().directoryId
        instrumentation.uiAutomation.executeShellCommand("am start -n dev.vvideo/dev.vvideo.app.MainActivity")
            .use { fd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
            }
        val activity = withTimeout(10000) {
            var a: MainActivity? = null
            while (a == null) {
                withContext(Dispatchers.Main) {
                    a = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
                        .firstOrNull()
                }
                delay(20)
            }
            checkNotNull(a)
        }
        val device = UiDevice.getInstance(instrumentation)
        fun click(label: String) {
            checkNotNull(device.wait(Until.findObject(By.text(label)), 5000)) { label }.click()
        }

        val originalTheme = app.graph.settings.theme.first()
        try {
            lateinit var vm: LibraryViewModel
            withContext(Dispatchers.Main) {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
            }
            withTimeout(15000) { vm.state.first { it.libraries.isNotEmpty() && !it.busy } }
            withContext(Dispatchers.Main) {
                vm.open(
                    root,
                    Sort(SortField.Name, SortDirection.Ascending)
                )
            }
            withTimeout(15000) { vm.state.first { it.directory?.id == root && !it.busy && it.albums.isNotEmpty() } }
            delay(800)
            click("视频")
            val videos =
                withTimeout(10000) { vm.state.first { !it.busy && it.filter == MediaKind.Video } }
            assertTrue(videos.albums.flatMap { it.items }.all { it.kind == MediaKind.Video })
            // Entering a filter flushes the latest unfiltered viewport. Once that
            // flush completes, filtered viewport/save/stop must not alter the bookmark.
            delay(700)
            val before = app.graph.database.bookmarks().get(c.id, root)
            withContext(Dispatchers.Main) {
                vm.save(0, 77, 400)
                vm.flush()
            }
            delay(700)
            assertEquals(before?.anchor, app.graph.database.bookmarks().get(c.id, root)?.anchor)
            click("照片")
            val photos =
                withTimeout(10000) { vm.state.first { !it.busy && it.filter == MediaKind.Image } }
            assertTrue(photos.albums.flatMap { it.items }.all { it.kind == MediaKind.Image })
            click("全部")
            withTimeout(10000) { vm.state.first { !it.busy && it.filter == null } }
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("设置")), 5000))
            assertFalse(device.hasObject(By.text("最近浏览")))
            checkNotNull(device.wait(Until.findObject(By.desc("设置")), 5000)).click()
            click("主题")
            click("深色")
            assertEquals(
                ThemeMode.Dark,
                withTimeout(3000) { app.graph.settings.theme.first { it == ThemeMode.Dark } })
            click("存储用量")
            assertTrue(device.wait(Until.hasObject(By.text("缓存上限")), 5000))
            assertTrue(device.hasObject(By.text("视频片段")))
            device.takeScreenshot(
                java.io.File(
                    app.getExternalFilesDir(null),
                    "telegram-storage-dark.png"
                )
            )
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("主题")), 5000))
            click("主题")
            click("浅色")
            assertEquals(
                ThemeMode.Light,
                withTimeout(3000) { app.graph.settings.theme.first { it == ThemeMode.Light } })
            delay(1000)
            device.takeScreenshot(
                java.io.File(
                    app.getExternalFilesDir(null),
                    "telegram-settings-light.png"
                )
            )
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("设置")), 5000))
            assertFalse(device.hasObject(By.text("最近浏览")))
            assertTrue(
                app.graph.database.bookmarks().recent(c.id).first().any { it.directory == root })
        } finally {
            app.graph.settings.setTheme(originalTheme)
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }
}
