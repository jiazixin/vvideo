package dev.vvideo.benchmark

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the unminified, profileable device APK and the synthetic media fixture. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun keepRunnerAlive() {
        context.startForegroundService(Intent(context, BenchmarkKeepAliveService::class.java))
    }

    @After
    fun stopRunner() {
        context.stopService(Intent(context, BenchmarkKeepAliveService::class.java))
    }

    @Test
    fun startupAndAlbum() = rule.collect(
        packageName = "dev.vvideo",
        filterPredicate = { it.contains("Ldev/vvideo/") },
    ) {
        pressHome()
        startActivityAndWait()
        Configurator.getInstance().waitForIdleTimeout = 250
        check(device.wait(Until.hasObject(By.pkg("dev.vvideo").depth(0)), 15000))
        repeat(6) {
            if (!(device.hasObject(By.desc("设置")) && !device.hasObject(By.desc("返回")))) {
                if (!device.hasObject(By.desc("返回"))) {
                    device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
                }
                checkNotNull(device.wait(Until.findObject(By.desc("返回")), 5000)).click()
                device.waitForIdle()
            }
        }
        check(device.hasObject(By.desc("设置")))
        // Wait for initialized content; never profile an empty connection form.
        checkNotNull(device.wait(Until.findObject(By.text("media")), 15000)).click()
        checkNotNull(device.wait(Until.findObject(By.text("large")), 10000)).click()
        check(device.wait(Until.hasObject(By.text("全部")), 10000))
        repeat(4) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 3,
                30,
            )
        }
        repeat(4) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 3,
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                30,
            )
        }
    }
}
