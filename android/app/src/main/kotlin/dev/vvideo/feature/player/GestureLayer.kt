package dev.vvideo.feature.player

import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A long press plays at this speed until it is released. */
private const val HOLD_SPEED = 2f

/**
 * [detectTapGestures], except that a press which travelled beyond the touch slop
 * is not a tap. detectTapGestures reports any release that nothing consumed as a
 * tap, so a swipe across a pager that does not scroll that way (vertical on the
 * photo pager, horizontal on the video feed) would toggle the viewer's controls.
 * The travel is only observed, never consumed. A press whose start this
 * detector did not see, as when its pointerInput restarts mid-swipe because a
 * pager brought another page to the front, is not a tap either.
 */
internal suspend fun PointerInputScope.detectTapsIgnoringDrags(
    onTap: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onPress: suspend PressGestureScope.(Offset) -> Unit = {}
) = coroutineScope {
    // Whether the last press seen from down to release stayed within the slop.
    var tappable = false
    launch {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            tappable = false
            var moved = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) moved = true
                if (!change.pressed) {
                    tappable = !moved
                    break
                }
            }
        }
    }
    detectTapGestures(
        onDoubleTap = onDoubleTap,
        onLongPress = onLongPress,
        onPress = onPress,
        onTap = { if (tappable) onTap() }
    )
}

/** Tap recognition yields vertical movement to the pager. Only the seek bar consumes drags. */
fun Modifier.viewerGestures(
    player: Player,
    onTap: () -> Unit,
    onHold: (Boolean) -> Unit,
    onSeek: (Boolean) -> Unit
): Modifier = pointerInput(player) {
    var held = false
    var originalSpeed = 1f
    detectTapsIgnoringDrags(
        onTap = onTap,
        onDoubleTap = { point ->
            when {
                point.x < size.width / 3f -> onSeek(false)
                point.x > size.width * 2f / 3f -> onSeek(true)
                else -> if (player.isPlaying) player.pause() else player.play()
            }
        },
        onLongPress = {
            originalSpeed = player.playbackParameters.speed
            held = true
            player.setPlaybackSpeed(HOLD_SPEED)
            onHold(true)
        },
        onPress = {
            try {
                tryAwaitRelease()
            } finally {
                if (held) {
                    player.setPlaybackSpeed(originalSpeed)
                    held = false
                    onHold(false)
                }
            }
        },
    )
}
