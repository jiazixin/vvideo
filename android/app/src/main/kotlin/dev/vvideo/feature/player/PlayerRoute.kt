package dev.vvideo.feature.player

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.core.media.playback.PlaybackSession
import dev.vvideo.data.settings.Connection
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The vertical video feed. This route owns the playback session and the viewer's UI
 * state; the pieces it composes take values and callbacks.
 */
@Composable
internal fun PlayerRoute(
    vm: PlayerViewModel,
    connection: Connection,
    loader: ImageLoader,
    create: suspend () -> PlaybackSession,
    release: suspend (PlaybackSession) -> Unit,
    onBack: () -> Unit,
    onInteraction: (Boolean) -> Unit,
    initialSelectionMs: Long = 0L,
    leaving: Boolean = false,
) {
    val live by vm.state.collectAsStateWithLifecycle()
    val state by rememberUpdatedState(retainedWhileLeaving(live, leaving))
    val pager = rememberPagerState(pageCount = { state.items.size })
    val latestItems by rememberUpdatedState(state.items)
    LaunchedEffect(state.initialId, state.items.isNotEmpty()) {
        val index = state.items.indexOfFirst { it.id == state.initialId }
        if (index >= 0) pager.scrollToPage(index)
    }
    val session by rememberPlaybackSession(connection, create, release)
    val foreground by rememberForeground(onStop = { session?.pause() })
    var controlsVisible by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var controlFocused by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ViewerOrientation(activity, isLandscape)
    var holding by remember { mutableStateOf(false) }
    val levels = remember(activity) { ViewerLevels(activity) }
    var edgeLevel by remember { mutableStateOf<Pair<EdgeControl, Float>?>(null) }
    // Brightness set by an edge drag belongs to the viewer; leaving restores the system's.
    DisposableEffect(levels) { onDispose { levels.resetBrightness() } }
    val seekChain = remember { SeekChain() }
    var bulletin by remember { mutableStateOf<String?>(null) }
    val lastVideo = stringResource(R.string.last_video)
    val active = session ?: return
    val playback by active.state.collectAsStateWithLifecycle()
    // A leaving page keeps its last frame and controls: it pauses at once so no
    // sound outlives the tap, stops tracking the player, and sends no further
    // commands to the session or ViewModel. Release still follows disposal.
    TrackIsPlaying(active, leaving) { playing = it }
    AutoHideControls(
        visible = controlsVisible,
        pinned = !playing || playback.error || dragging || detailsOpen || menuOpen || controlFocused,
        page = pager.settledPage,
        interactions = interactionSerial,
        onVisibleChange = { controlsVisible = it },
    )
    // The settled page plays; a moving pager or a stopped screen pauses.
    LaunchedEffect(
        active,
        pager,
        foreground,
        leaving,
        state.items.getOrNull(pager.settledPage)?.version,
    ) {
        if (leaving) {
            active.pause()
            return@LaunchedEffect
        }
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.distinctUntilChanged()
            .collect { (index, moving) ->
                onInteraction(moving)
                if (moving || !foreground) {
                    active.pause()
                } else {
                    latestItems.getOrNull(index)
                        ?.let {
                            vm.selected(it.id)
                            val opening = it.id == state.initialId && active.state.value.id.isEmpty()
                            active.select(
                                it.playback(connection),
                                if (opening && initialSelectionMs > 0) {
                                    initialSelectionMs
                                } else {
                                    SystemClock.elapsedRealtime()
                                },
                            )
                        }
                }
            }
    }
    // The next video preloads once the current one renders and stops buffering.
    LaunchedEffect(
        active,
        playback.firstFrame,
        playback.buffering,
        pager.settledPage,
        state.items,
        leaving,
    ) {
        val settled = playback.firstFrame && !playback.buffering && !pager.isScrollInProgress
        if (settled && foreground && !leaving) {
            active.preload(state.items.getOrNull(pager.settledPage + 1)?.playback(connection))
        }
    }
    LaunchedEffect(pager, state.items.size, leaving) {
        if (leaving) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            requestNearbyPages(index, state.items.size, vm::page)
        }
    }
    LaunchedEffect(playback.id) {
        active.player.setPlaybackSpeed(1f)
        seekChain.reset()
    }
    LaunchedEffect(seekChain.lastTapMs) { seekChain.expire() }
    val endOverscroll = rememberEndOverscroll(pager, state.endReached) { bulletin = lastVideo }
    val currentItem = state.items.getOrNull(pager.settledPage)
    // Controls describe one page. While pages move they step aside, and after the
    // pager rests they name the new page even before the player has switched to it,
    // so nothing from the previous video stays on screen.
    val swiping = pager.isScrollInProgress
    val currentKey = currentItem?.playback(connection)?.cacheKey
    val sessionOnPage = currentKey != null && playback.id == currentKey
    PlayerScreen(
        state = state.copy(
            leaving = leaving,
            presentation = ViewerPresentation(
                currentId = currentItem?.id.orEmpty(),
                controlsVisible = controlsVisible,
                swiping = swiping,
                showPlay = sessionOnPage &&
                    !playing &&
                    playback.firstFrame &&
                    !playback.error &&
                    !playback.buffering &&
                    !swiping,
                holding = holding,
                edgeLevel = edgeLevel?.second,
                adjustingBrightness = edgeLevel?.first == EdgeControl.Brightness,
                seekSeconds = seekChain.seconds,
                seekForward = seekChain.forward,
                bulletin = bulletin,
                buffering = !state.error &&
                    !swiping &&
                    (playback.buffering || !playback.firstFrame && !playback.error),
                detailsOpen = detailsOpen,
            ),
        ),
        onBack = onBack,
        onDetails = { detailsOpen = true },
        onDismissDetails = { detailsOpen = false },
        onPlay = { active.player.play() },
        onDismissBulletin = { bulletin = null },
        surface = { SessionSurface(active.player, Modifier.fillMaxSize()) },
        pages = {
            VerticalPager(
                state = pager,
                key = { state.items[it].id },
                beyondViewportPageCount = 1,
                userScrollEnabled = !dragging && !isLandscape,
                // The gesture callbacks are installed once per player, so they read and
                // write this route's state directly instead of capturing values.
                modifier = Modifier.fillMaxSize().nestedScroll(endOverscroll).edgeAdjustGestures(
                    onStart = { control ->
                        if (control == EdgeControl.Brightness) levels.brightness() else levels.volume()
                    },
                    onAdjust = { control, level ->
                        when (control) {
                            EdgeControl.Brightness -> levels.setBrightness(level)
                            EdgeControl.Volume -> levels.setVolume(level)
                        }
                        edgeLevel = control to level
                    },
                    onEnd = { edgeLevel = null },
                ).viewerGestures(
                    active.player,
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionSerial++
                    },
                    onHold = { holding = it },
                    onSeek = { forward ->
                        active.player.seekBy(seekChain.tap(forward, SystemClock.elapsedRealtime()))
                        interactionSerial++
                    },
                ),
            ) { index ->
                val item = state.items[index]
                val selected = pager.settledPage == index && !pager.isScrollInProgress
                val itemKey = item.playback(connection).cacheKey
                val failed = selected && playback.error && playback.id == itemKey
                VideoPage(
                    item,
                    connection,
                    loader,
                    covered = !selected || playback.id != itemKey || !playback.firstFrame,
                    failed = failed,
                    error = if (failed) active.player.playerError else null,
                    noDecoder = failed && playback.unsupported,
                    onDetails = { detailsOpen = true },
                    onRetry = {
                        vm.retry(item.id)
                        active.select(item.playback(connection))
                    },
                )
            }
        },
        seekControls = {
            SeekBar(
                active.player,
                currentKey.orEmpty(),
                sessionOnPage,
                currentItem?.durationMs ?: 0L,
                sessionOnPage && playback.firstFrame && !playback.error,
                controlsVisible,
                currentItem?.displayRatio?.let { it > 1f } == true,
                { activity?.lockOrientation(landscape = !isLandscape) },
                Modifier.onFocusChanged { controlFocused = it.hasFocus },
                leaving,
                { menuOpen = it },
            ) {
                dragging = it
                interactionSerial++
            }
        },
    )
}
