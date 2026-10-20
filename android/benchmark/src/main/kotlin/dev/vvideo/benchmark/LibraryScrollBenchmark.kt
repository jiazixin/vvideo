package dev.vvideo.benchmark

import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class LibraryScrollBenchmark {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before
    fun keepRunnerAlive() {
        context.startForegroundService(Intent(context, BenchmarkKeepAliveService::class.java))
    }

    @After
    fun stopRunner() {
        context.stopService(Intent(context, BenchmarkKeepAliveService::class.java))
    }

    @get:Rule
    val rule = MacrobenchmarkRule()
    @Test
    fun warmAlbumScroll() = rule.measureRepeated(
        packageName = "dev.vvideo",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("dev.vvideo").depth(0)), 10000)
            Configurator.getInstance().waitForIdleTimeout = 250
            // Navigate through observed channel controls. A failed lookup must fail the
            // benchmark rather than silently measure the home screen.
            if (!(device.hasObject(By.text("large")) && device.hasObject(By.desc("返回")) && device.hasObject(
                    By.text("全部")
                ))
            ) {
                repeat(6) {
                    if (!(device.hasObject(By.desc("设置")) && !device.hasObject(By.desc("返回")))) {
                        if (!device.hasObject(By.desc("返回"))) device.click(
                            device.displayWidth / 2,
                            device.displayHeight * 2 / 5
                        )
                        checkNotNull(device.wait(Until.findObject(By.desc("返回")), 5000)).click()
                        device.waitForIdle()
                    }
                }
                check(device.hasObject(By.desc("设置"))) { "Synthetic home not reached" }
                checkNotNull(device.wait(Until.findObject(By.text("media")), 5000)).click()
                checkNotNull(
                    device.wait(
                        Until.findObject(By.textStartsWith("子文件夹 ·")),
                        10000
                    )
                ).click()
                checkNotNull(device.wait(Until.findObject(By.text("large")), 5000)).click()
            }
            check(
                device.wait(
                    Until.hasObject(By.text("全部")),
                    10000
                )
            ) { "Synthetic album not reached" }
            device.waitForIdle()
            repeat(5) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    30
                )
                device.waitForIdle()
            }
            repeat(5) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    30
                )
                device.waitForIdle()
            }
        },
        measureBlock = {
            repeat(12) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    25
                )
            }
            repeat(12) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight / 3,
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    25
                )
            }
        })
}
