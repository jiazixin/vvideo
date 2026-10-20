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

@UnstableApi
class MainActivity : ComponentActivity() {
    private var interacting = false
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
        val current = window.decorView.display ?: return
        val native = current.mode
        val best = current.supportedModes
            .filter { it.physicalWidth == native.physicalWidth && it.physicalHeight == native.physicalHeight }
            .maxByOrNull { it.refreshRate }
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (active) best?.modeId ?: 0 else 0
        }
    }

    override fun onStop() {
        interaction(false)
        super.onStop()
    }
}
