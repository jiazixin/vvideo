package dev.vvideo.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.vvideo.core.media.playback.PlaybackSession
import dev.vvideo.data.settings.Connection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Effect-only helpers of the video route. The Unit ones are non-restartable, like
// the effects they wrap, so they recompose exactly as the same code written inline.

/** Controls hide after this long without interaction, unless accessibility asks for longer. */
private const val CONTROLS_TIMEOUT_MS = 3000L

/** Pulling past the last video this far (in px) shows the "last video" notice. */
private const val END_OVERSCROLL_PX = 80f

/**
 * The playback session this route owns for [connection], or null until it is created.
 *
 * The creating effect owns the player immediately, before a recomposition could
 * install a DisposableEffect keyed by it. Leaving during that gap must still release
 * the player that was already returned to this route.
 */
@UnstableApi
@Composable
internal fun rememberPlaybackSession(
    connection: Connection,
    create: suspend (String) -> PlaybackSession,
    release: suspend (PlaybackSession) -> Unit
): State<PlaybackSession?> {
    val session = remember { mutableStateOf<PlaybackSession?>(null) }
    LaunchedEffect(connection.id, connection.token) {
        val owned = create(connection.token)
        try {
            session.value = owned
            awaitCancellation()
        } finally {
            if (session.value === owned) session.value = null
            withContext(NonCancellable) { release(owned) }
        }
    }
    return session
}

/**
 * Whether the screen is started. [onStop] runs when it stops; it is captured once
 * per lifecycle owner, so it has to read the latest values itself.
 */
@Composable
internal fun rememberForeground(onStop: () -> Unit): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val foreground = remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground.value = false
                    onStop()
                }

                Lifecycle.Event.ON_START -> foreground.value = true
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return foreground
}

/**
 * Leaving the viewer restores the orientation the activity asked for before it, and
 * back first returns a landscape viewer to portrait.
 */
@Composable
@NonRestartableComposable
internal fun ViewerOrientation(activity: Activity?, landscape: Boolean) {
    // Read in the effect, after a route this one replaces has restored its own
    // request; composition would still see that route's landscape lock.
    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = originalOrientation }
    }
    BackHandler(landscape) { activity?.lockOrientation(landscape = false) }
}

internal fun Activity.lockOrientation(landscape: Boolean) {
    requestedOrientation = if (landscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/**
 * Reports the player's isPlaying through [onChange]. A leaving page stops tracking
 * the player and keeps the last value it showed.
 */
@UnstableApi
@Composable
@NonRestartableComposable
internal fun TrackIsPlaying(
    session: PlaybackSession,
    leaving: Boolean,
    onChange: (Boolean) -> Unit
) {
    DisposableEffect(session, leaving) {
        if (leaving) return@DisposableEffect onDispose {}
        onChange(session.player.isPlaying)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                onChange(value)
            }
        }
        session.player.addListener(listener)
        onDispose { session.player.removeListener(listener) }
    }
}

/**
 * Hides the controls after a quiet period. While [pinned] they stay up; a new page
 * or another [interactions] count starts the period again.
 */
@Composable
@NonRestartableComposable
internal fun AutoHideControls(
    visible: Boolean,
    pinned: Boolean,
    page: Int,
    interactions: Int,
    onVisibleChange: (Boolean) -> Unit
) {
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(pinned, page, interactions, visible) {
        if (pinned) {
            onVisibleChange(true)
        } else if (visible) {
            delay(
                accessibility?.calculateRecommendedTimeoutMillis(
                    CONTROLS_TIMEOUT_MS,
                    containsIcons = true,
                    containsText = true,
                    containsControls = true
                ) ?: CONTROLS_TIMEOUT_MS
            )
            onVisibleChange(false)
        }
    }
}

/** Pulling on past the last video, once the feed has no more, calls [onEnd]. */
@Composable
internal fun rememberEndOverscroll(
    pager: PagerState,
    endReached: Boolean,
    onEnd: () -> Unit
): NestedScrollConnection {
    val currentOnEnd by rememberUpdatedState(onEnd)
    return remember(pager, endReached) {
        object : NestedScrollConnection {
            private var overflow = 0f

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (endReached && !pager.canScrollForward && available.y < 0) {
                    overflow -= available.y
                    if (overflow > END_OVERSCROLL_PX) {
                        currentOnEnd()
                        overflow = 0f
                    }
                } else {
                    overflow = 0f
                }
                return Offset.Zero
            }
        }
    }
}
