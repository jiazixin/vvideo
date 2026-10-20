package dev.vvideo.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.Player
import dev.vvideo.R
import dev.vvideo.ui.component.*
import kotlinx.coroutines.delay

// The viewer is always dark, whatever the app theme.
private val ViewerAccent = Color(0xFF6AB3F8)

val PlaybackSpeeds = listOf(2f, 1.5f, 1.25f, 1f, .75f, .5f)

fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

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
    var dragging by remember(mediaId) { mutableStateOf(false) }
    var target by remember(mediaId) { mutableFloatStateOf(0f) }
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

            override fun onPlaybackParametersChanged(value: androidx.media3.common.PlaybackParameters) {
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
            delay(250)
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
    val fraction =
        if (dragging) target else if (duration > 0) (position.toFloat() / duration).coerceIn(
            0f,
            1f
        ) else 0f
    if (!visible) {
        Canvas(modifier.fillMaxWidth().height(2.dp)) {
            drawRect(Color.White.copy(alpha = .2f))
            drawRect(
                Color.White.copy(alpha = .85f),
                size = size.copy(width = size.width * fraction)
            )
        }
    }
    val seekDescription = stringResource(R.string.seek)
    val speedDescription = stringResource(R.string.speed)
    androidx.compose.animation.AnimatedVisibility(
        visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
    ) {
        Column(modifier.padding(horizontal = 12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (dragging) Text(
                    mediaDuration((target * duration).toLong()),
                    Modifier.offset(
                        x = (maxWidth * target - 24.dp).coerceIn(
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
                            if (enabled && duration > 0) {
                                player.seekTo((value.coerceIn(0f, 1f) * duration).toLong())
                                true
                            } else false
                        }
                    }.pointerInput(enabled, duration) {
                        if (enabled && duration > 0) detectDragGestures(onDragStart = { p ->
                            dragging = true
                            target = (p.x / size.width).coerceIn(0f, 1f)
                            onDragging(true)
                        }, onDragEnd = {
                            player.seekTo((target * duration).toLong())
                            dragging = false
                            onDragging(false)
                        }, onDragCancel = {
                            dragging = false
                            onDragging(false)
                        }) { change, _ ->
                            change.consume()
                            target = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }.pointerInput(
                        enabled,
                        duration
                    ) {
                        if (enabled && duration > 0) detectTapGestures { p ->
                            player.seekTo(
                                ((p.x / size.width).coerceIn(
                                    0f,
                                    1f
                                ) * duration).toLong()
                            )
                        }
                    }) {
                    val y = size.height / 2
                    val stroke = (if (dragging) 5.dp else 3.dp).toPx()
                    drawLine(
                        Color.White.copy(alpha = .22f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawLine(
                        Color.White.copy(alpha = .45f),
                        Offset(0f, y),
                        Offset(
                            size.width * (if (duration > 0) buffered.toFloat() / duration else 0f).coerceIn(
                                0f,
                                1f
                            ), y
                        ),
                        stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawLine(
                        Color.White,
                        Offset(0f, y),
                        Offset(size.width * fraction, y),
                        stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawCircle(
                        Color.White,
                        (if (dragging) 9.dp else 7.dp).toPx(),
                        Offset(size.width * fraction, y)
                    )
                }
            }
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
                    mediaDuration(if (dragging) (target * duration).toLong() else position),
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
                    // Plain text like the time beside it; a speed other than normal stands out in the accent colour.
                    Box(
                        Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled) { speedMenu = !speedMenu }.semantics {
                            contentDescription = speedDescription
                            role = Role.Button
                        }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (speed == 1f) stringResource(R.string.speed_normal) else stringResource(
                                R.string.speed_value,
                                speedLabel(speed)
                            ),
                            color = if (speed == 1f) Color.White else ViewerAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
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

/** Opens upward from the speed button, its right edge on the button's, and closes on any outside tap. */
@Composable
private fun SpeedMenu(current: Float, onDismiss: () -> Unit, onSelect: (Float) -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
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
                .background(Color(0xFF1B232C), RoundedCornerShape(14.dp)).padding(vertical = 6.dp)
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
                        color = if (selected) ViewerAccent else Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (selected) Icon(
                        painterResource(R.drawable.ic_check),
                        null,
                        Modifier.size(20.dp),
                        tint = ViewerAccent
                    )
                }
            }
        }
    }
}
