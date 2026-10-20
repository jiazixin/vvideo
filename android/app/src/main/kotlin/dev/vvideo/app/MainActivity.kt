package dev.vvideo.app

import android.os.Build
import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.vvideo.app.navigation.VVideoRoot
import dev.vvideo.ui.theme.ThemeMode
import dev.vvideo.ui.theme.VVideoTheme

/**
 * How long the highest refresh rate stays requested after scrolling stops. Without
 * it, the short pause between two flicks releases the request: the panel drops to
 * 60 Hz and switches back on the next flick, and each display mode switch stalls
 * the frame pipeline for tens of milliseconds on the test device.
 */
private const val REFRESH_HOLD_MS = 1500L

@UnstableApi
class MainActivity : ComponentActivity() {
    private var interacting = false
    private var highRefresh = false
    private val releaseHighRefresh = Runnable { requestHighRefresh(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as VVideoApplication
            val theme by app.graph.settings.theme.collectAsStateWithLifecycle(initialValue = "system")
            VVideoTheme(
                when (theme) {
                    "light" -> ThemeMode.Light
                    "dark" -> ThemeMode.Dark
                    else -> ThemeMode.System
                }
            ) { VVideoRoot(app, ::interaction) }
        }
    }

    private fun interaction(active: Boolean) {
        if (interacting == active) return
        interacting = active
        if (Build.VERSION.SDK_INT >= 29) {
            if (active) {
                Trace.beginAsyncSection("VVideoScroll", 1)
            } else {
                Trace.endAsyncSection("VVideoScroll", 1)
            }
        }
        val decor = window.decorView
        decor.removeCallbacks(releaseHighRefresh)
        if (active) {
            requestHighRefresh(true)
        } else {
            decor.postDelayed(releaseHighRefresh, REFRESH_HOLD_MS)
        }
    }

    /** Asks for the display's highest refresh rate at its current resolution, or releases the request. */
    private fun requestHighRefresh(enabled: Boolean) {
        if (highRefresh == enabled) return
        val current = window.decorView.display ?: return
        val native = current.mode
        val best = current.supportedModes
            .filter { it.physicalWidth == native.physicalWidth && it.physicalHeight == native.physicalHeight }
            .maxByOrNull { it.refreshRate }
        highRefresh = enabled
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (enabled) best?.modeId ?: 0 else 0
        }
    }

    override fun onStop() {
        interaction(false)
        // A stopped app releases its request at once rather than after the hold.
        window.decorView.removeCallbacks(releaseHighRefresh)
        requestHighRefresh(false)
        super.onStop()
    }
}
