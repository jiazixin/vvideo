package dev.vvideo.feature.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.ButtonKind
import dev.vvideo.ui.component.VVButton

// Pieces of the video viewer. They take values and callbacks; the state they show
// is owned by PlayerRoute.

private val BadgeBackground = Color.Black.copy(alpha = .45f)

/** Shows [player] on a PlayerView that lives as long as the session, not a page. */
@OptIn(UnstableApi::class)
@Composable
internal fun SessionSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

/**
 * One page of the video feed. A [covered] page shows the video's thumbnail on an
 * opaque background, and a [failed] page shows the playback error over it.
 */
@Composable
internal fun VideoPage(
    item: Media,
    c: Connection,
    loader: ImageLoader,
    covered: Boolean,
    failed: Boolean,
    error: PlaybackException?,
    onDetails: () -> Unit,
    onRetry: () -> Unit
) {
    // A covered page is opaque: the shared Surface behind it may still hold the
    // previous video's last frame, which would show through letterboxing or a
    // cover that has not loaded yet.
    Box(
        Modifier.fillMaxSize()
            .then(if (covered) Modifier.background(Color.Black) else Modifier)
    ) {
        if (covered) {
            val context = LocalContext.current
            val request = remember(item.id, item.version, c.token) {
                item.imageRequest(context, c, "thumbnail")
            }
            AsyncImage(
                request,
                item.name,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (failed) {
            PlaybackErrorPanel(error, onDetails, onRetry, Modifier.align(Alignment.Center))
        }
    }
}

/** An unsupported format offers the media details; any other error offers a retry. */
@Composable
private fun PlaybackErrorPanel(
    error: PlaybackException?,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unsupported = error?.errorCode in listOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
    )
    Column(
        modifier.background(Color.Black.copy(alpha = .8f)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(R.drawable.ic_video_off),
            null,
            Modifier.size(48.dp),
            tint = Color.White
        )
        Text(
            stringResource(if (unsupported) R.string.cannot_decode else R.string.playback_error),
            color = Color.White
        )
        Text(
            error?.errorCodeName.orEmpty(),
            color = Color.White.copy(alpha = .6f)
        )
        VVButton(
            stringResource(if (unsupported) R.string.media_details else R.string.retry),
            { if (unsupported) onDetails() else onRetry() },
            kind = ButtonKind.Secondary
        )
    }
}

/** The quick fade the viewer uses for its controls. */
@Composable
internal fun ViewerFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(100))
    ) { content() }
}

/** The top bar of a viewer page, faded in and out with the controls. */
@Composable
internal fun FadingChrome(
    visible: Boolean,
    item: Media?,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    ViewerFade(visible, modifier) { PlayerChrome(item, onBack, onDetails) }
}

/** Shades the bottom of the video behind the full controls; the thin bar alone has no shade. */
@Composable
internal fun ControlsScrim(shaded: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.then(
            if (shaded) Modifier.background(BottomViewerGradient).padding(top = 40.dp) else Modifier
        ).navigationBarsPadding()
    ) { content() }
}

@Composable
internal fun PlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionIcon(
        R.drawable.ic_play,
        R.string.play,
        onClick,
        modifier.size(64.dp).background(BadgeBackground, CircleShape),
        Color.White
    )
}

/** Shown while a long press plays at double speed. */
@Composable
internal fun SpeedHoldLabel(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.speed_hold),
        modifier.statusBarsPadding().padding(top = 72.dp)
            .background(Color.Black.copy(alpha = .5f), CircleShape).padding(12.dp),
        color = Color.White
    )
}

/** The seconds a double-tap chain has seeked, on the side it seeks towards. */
@Composable
internal fun SeekFeedback(seconds: Int, forward: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxHeight(.5f).fillMaxWidth(.4f)
            .background(Color.White.copy(alpha = .13f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(if (forward) R.string.seek_forward else R.string.seek_back, seconds),
            color = Color.White
        )
    }
}

@Composable
internal fun BufferingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier.size(64.dp).background(BadgeBackground, CircleShape),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator(Modifier.size(48.dp), color = Color.White) }
}
