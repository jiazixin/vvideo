package dev.vvideo.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.vvideo.R
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.mediaDuration
import dev.vvideo.ui.theme.ViewerColors
import kotlinx.coroutines.delay

/** How often the bar reads the player's position while it plays. */
private const val PROGRESS_POLL_MS = 250L

private const val CONTROLS_FADE_MS = 150

val PlaybackSpeeds = listOf(2f, 1.5f, 1.25f, 1f, .75f, .5f)

fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

/** A drag along the bar: while [active], the thumb follows [target] (0..1) instead of the player. */
@Stable
private class SeekDrag {
    var active by mutableStateOf(false)
    var target by mutableFloatStateOf(0f)
}

/**
 * [mediaId] names the page in front. Until the player has switched to it ([live]),
 * the bar shows that page's own start instead of the previous video's progress.
 */
@Composable
fun SeekBar(
    player: Player,
    mediaId: String,
    live: Boolean,
    expectedDurationMs: Long,
    enabled: Boolean,
    visible: Boolean,
    landscape: Boolean,
    onLandscape: () -> Unit,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
    onSpeedMenu: (Boolean) -> Unit = {},
    onDragging: (Boolean) -> Unit
) {
    var position by remember(mediaId) { mutableLongStateOf(0) }
    var duration by remember(mediaId) { mutableLongStateOf(0) }
    var buffered by remember(mediaId) { mutableLongStateOf(0) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    val drag = remember(mediaId) { SeekDrag() }
    var speedMenu by remember { mutableStateOf(false) }
    LaunchedEffect(speedMenu) { onSpeedMenu(speedMenu) }
    DisposableEffect(Unit) { onDispose { onSpeedMenu(false) } }
    LaunchedEffect(visible) { if (!visible) speedMenu = false }
    // A frozen bar keeps the play state it last showed while its page leaves.
    DisposableEffect(player, frozen) {
        if (frozen) return@DisposableEffect onDispose {}
        playing = player.isPlaying
        speed = player.playbackParameters.speed
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                playing = value
            }

            override fun onPlaybackParametersChanged(value: PlaybackParameters) {
                speed = value.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Progress reads stay in this subtree, never the page or pager.
    LaunchedEffect(player, mediaId, live) {
        if (!live) return@LaunchedEffect
        while (true) {
            duration = player.duration.coerceAtLeast(0)
            position = player.currentPosition.coerceAtLeast(0)
            buffered = player.bufferedPosition.coerceAtLeast(0)
            delay(PROGRESS_POLL_MS)
        }
    }
    // A seek moves the bar at once. Between polls the released thumb would otherwise
    // fall back to the position before the seek, then jump to the target.
    DisposableEffect(player, mediaId, live) {
        if (!live) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) position = newPosition.positionMs.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(mediaId) { onDispose { onDragging(false) } }
    val shownDuration = if (duration > 0) duration else expectedDurationMs.coerceAtLeast(0)
    val fraction = when {
        drag.active -> drag.target
        duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    if (!visible) ThinProgress(fraction, modifier)
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(CONTROLS_FADE_MS)),
        exit = fadeOut(tween(CONTROLS_FADE_MS))
    ) {
        Column(modifier.padding(horizontal = 12.dp)) {
            SeekTrack(
                player = player,
                enabled = enabled,
                durationMs = duration,
                fraction = fraction,
                bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(
                    0f,
                    1f
                ) else 0f,
                drag = drag,
                onDragging = onDragging
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionIcon(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) R.string.pause else R.string.play,
                    { if (playing) player.pause() else player.play() },
                    tint = Color.White,
                    enabled = enabled
                )
                Text(
                    mediaDuration(if (drag.active) (drag.target * duration).toLong() else position),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    " / " + mediaDuration(shownDuration),
                    color = Color.White.copy(alpha = .6f),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.weight(1f))
                Box {
                    SpeedButton(speed, enabled) { speedMenu = !speedMenu }
                    if (speedMenu) SpeedMenu(speed, { speedMenu = false }) {
                        player.setPlaybackSpeed(it)
                        speedMenu = false
                    }
                }
                if (landscape) ActionIcon(
                    R.drawable.ic_fullscreen,
                    R.string.fullscreen,
                    onLandscape,
                    tint = Color.White
                )
            }
        }
    }
}

/** The bar alone, under hidden controls. */
@Composable
private fun ThinProgress(fraction: Float, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(Color.White.copy(alpha = .2f))
        drawRect(
            Color.White.copy(alpha = .85f),
            size = size.copy(width = size.width * fraction)
        )
    }
}

/**
 * The draggable bar with buffered and played lengths. A drag shows the target time
 * above the thumb and seeks when released; a tap seeks at once.
 */
@Composable
private fun SeekTrack(
    player: Player,
    enabled: Boolean,
    durationMs: Long,
    fraction: Float,
    bufferedFraction: Float,
    drag: SeekDrag,
    onDragging: (Boolean) -> Unit
) {
    val seekDescription = stringResource(R.string.seek)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (drag.active) Text(
            mediaDuration((drag.target * durationMs).toLong()),
            Modifier.offset(
                x = (maxWidth * drag.target - 24.dp).coerceIn(
                    0.dp,
                    (maxWidth - 48.dp).coerceAtLeast(0.dp)
                ), y = (-28).dp
            ).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).semantics {
                contentDescription = seekDescription
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    if (enabled && durationMs > 0) {
                        player.seekTo((value.coerceIn(0f, 1f) * durationMs).toLong())
                        true
                    } else false
                }
            }.pointerInput(enabled, durationMs) {
                if (enabled && durationMs > 0) detectDragGestures(onDragStart = { point ->
                    drag.active = true
                    drag.target = (point.x / size.width).coerceIn(0f, 1f)
                    onDragging(true)
                }, onDragEnd = {
                    player.seekTo((drag.target * durationMs).toLong())
                    drag.active = false
                    onDragging(false)
                }, onDragCancel = {
                    drag.active = false
                    onDragging(false)
                }) { change, _ ->
                    change.consume()
                    drag.target = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }.pointerInput(enabled, durationMs) {
                if (enabled && durationMs > 0) detectTapGestures { point ->
                    player.seekTo(((point.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                }
            }) {
            val y = size.height / 2
            val stroke = (if (drag.active) 5.dp else 3.dp).toPx()
            drawLine(
                Color.White.copy(alpha = .22f),
                Offset(0f, y),
                Offset(size.width, y),
                stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                Color.White.copy(alpha = .45f),
                Offset(0f, y),
                Offset(size.width * bufferedFraction, y),
                stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                Color.White,
                Offset(0f, y),
                Offset(size.width * fraction, y),
                stroke,
                cap = StrokeCap.Round
            )
            drawCircle(
                Color.White,
                (if (drag.active) 9.dp else 7.dp).toPx(),
                Offset(size.width * fraction, y)
            )
        }
    }
}

/** Plain text like the time beside it; a speed other than normal stands out in the accent colour. */
@Composable
private fun SpeedButton(speed: Float, enabled: Boolean, onClick: () -> Unit) {
    val speedDescription = stringResource(R.string.speed)
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).semantics {
                contentDescription = speedDescription
                role = Role.Button
            }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center
    ) {
        Text(
            if (speed == 1f) {
                stringResource(R.string.speed_normal)
            } else {
                stringResource(R.string.speed_value, speedLabel(speed))
            },
            color = if (speed == 1f) Color.White else ViewerColors.Accent,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Opens upward from the speed button, its right edge on the button's, and closes on any outside tap. */
@Composable
private fun SpeedMenu(current: Float, onDismiss: () -> Unit, onSelect: (Float) -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val above = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ) = IntOffset(
                (anchorBounds.right - popupContentSize.width).coerceIn(
                    0,
                    (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                ), (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
            )
        }
    }
    Popup(
        popupPositionProvider = above,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier.width(132.dp).shadow(8.dp, RoundedCornerShape(14.dp))
                .background(ViewerColors.Sheet, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)
        ) {
            Text(
                stringResource(R.string.speed),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = .56f),
                style = MaterialTheme.typography.bodySmall
            )
            for (option in PlaybackSpeeds) {
                val selected = option == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onSelect(option) }
                        .semantics { this.selected = selected }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.speed_value, speedLabel(option)),
                        Modifier.weight(1f),
                        color = if (selected) ViewerColors.Accent else Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (selected) Icon(
                        painterResource(R.drawable.ic_check),
                        null,
                        Modifier.size(20.dp),
                        tint = ViewerColors.Accent
                    )
                }
            }
        }
    }
}
