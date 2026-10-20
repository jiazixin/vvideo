package dev.vvideo.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of each edge strip, as a fraction of the viewer width. */
private const val EDGE_FRACTION = 0.2f

/** Dragging this fraction of the viewer height moves a level from 0 to 1. */
private const val FULL_RANGE_FRACTION = 0.6f

/** The window never goes fully dark; 0 would make the screen unreadable. */
private const val MIN_BRIGHTNESS = 0.01f

/** The level a vertical drag on a viewer edge adjusts. */
enum class EdgeControl { Brightness, Volume }

/** The control for a touch at [x], or null outside both edge strips. */
fun edgeControlAt(x: Float, width: Float): EdgeControl? = when {
    width <= 0f -> null
    x < width * EDGE_FRACTION -> EdgeControl.Brightness
    x > width * (1 - EDGE_FRACTION) -> EdgeControl.Volume
    else -> null
}

/** The level after dragging [dragY] pixels (negative is up) from [start], within 0..1. */
fun adjustedLevel(start: Float, dragY: Float, height: Float): Float {
    if (height <= 0f) return start.coerceIn(0f, 1f)
    return (start - dragY / (height * FULL_RANGE_FRACTION)).coerceIn(0f, 1f)
}

/** The stream volume index closest to [level] for a stream with [max] steps. */
fun volumeIndex(level: Float, max: Int): Int = (level.coerceIn(0f, 1f) * max).roundToInt()

/** Percent shown for a level. */
fun levelPercent(level: Float): Int = (level.coerceIn(0f, 1f) * 100).roundToInt()

/**
 * Vertical drags that start in the left or right edge strip adjust brightness or
 * volume. A drag is claimed only once it moves vertically past the touch slop;
 * from then on its events are consumed before the pager and the tap detector see
 * them. Taps, horizontal movement and drags in the middle pass through untouched.
 *
 * [onStart] returns the control's current level; [onAdjust] receives each new level.
 */
fun Modifier.edgeAdjustGestures(
    onStart: (EdgeControl) -> Float,
    onAdjust: (EdgeControl, Float) -> Unit,
    onEnd: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val control =
            edgeControlAt(down.position.x, size.width.toFloat()) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var claimed = false
        var start = 0f
        var startY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || event.changes.size > 1) break
            if (!claimed) {
                if (change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    claimed = true
                    start = onStart(control)
                    startY = change.position.y
                } else if (abs(dx) > slop) {
                    break
                } else {
                    continue
                }
            }
            onAdjust(
                control,
                adjustedLevel(start, change.position.y - startY, size.height.toFloat())
            )
            change.consume()
        }
        if (claimed) onEnd()
    }
}

/** Reads and sets the viewer window's brightness and the media volume. */
class ViewerLevels(private val activity: Activity?) {
    private val audio = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** The window's brightness, or the system brightness while the window follows it. */
    fun brightness(): Float {
        val window = activity?.window ?: return 0.5f
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        val system = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        return (system / 255f).coerceIn(0f, 1f)
    }

    fun setBrightness(level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
        }
    }

    /** Returns the window to the system brightness. */
    fun resetBrightness() {
        val window = activity?.window ?: return
        if (window.attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun volume(): Float {
        val audio = audio ?: return 0f
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    fun setVolume(level: Float) {
        val audio = audio ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = volumeIndex(level, max)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
    }
}
