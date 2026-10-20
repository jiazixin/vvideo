package dev.vvideo.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** One double tap seeks this far; taps within [SEEK_CHAIN_MS] add up. */
internal const val SEEK_STEP_SECONDS = 10
internal const val SEEK_CHAIN_MS = 600L

/**
 * Double-tap seeking: consecutive taps in one direction add up into one figure on
 * screen, which disappears [SEEK_CHAIN_MS] after the last tap.
 */
@Stable
internal class SeekChain {
    /** Seconds shown for the current chain; 0 hides the feedback. */
    var seconds by mutableIntStateOf(0)
        private set
    var forward by mutableStateOf(true)
        private set

    /** Changes with every tap, so an effect keyed by it restarts the expiry. */
    var lastTapMs by mutableLongStateOf(0L)
        private set

    /** Records a double tap at [nowMs] and returns the offset to seek by, in ms. */
    fun tap(forward: Boolean, nowMs: Long): Long {
        val chained = nowMs - lastTapMs <= SEEK_CHAIN_MS && this.forward == forward
        seconds = if (chained) seconds + SEEK_STEP_SECONDS else SEEK_STEP_SECONDS
        this.forward = forward
        lastTapMs = nowMs
        return SEEK_STEP_SECONDS * 1000L * if (forward) 1 else -1
    }

    /** Clears the shown figure [SEEK_CHAIN_MS] after the last tap. */
    suspend fun expire() {
        if (seconds > 0) {
            delay(SEEK_CHAIN_MS)
            seconds = 0
        }
    }

    fun reset() {
        seconds = 0
    }
}

/** Seeks by [offsetMs], kept inside the media. */
internal fun Player.seekBy(offsetMs: Long) {
    seekTo((currentPosition + offsetMs).coerceIn(0, duration.coerceAtLeast(0)))
}
