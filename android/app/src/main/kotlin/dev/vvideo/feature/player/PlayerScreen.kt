package dev.vvideo.feature.player

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.vvideo.R
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.playback.PlaybackSession
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.component.BulletinHost
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The vertical video feed. This route owns the playback session and the viewer's UI
 * state; the pieces it composes take values and callbacks.
 */
@UnstableApi
@Composable
fun PlayerRoute(
    vm: PlayerViewModel,
    c: Connection,
    loader: ImageLoader,
    create: suspend (String) -> PlaybackSession,
    release: suspend (PlaybackSession) -> Unit,
    onBack: () -> Unit,
    onInteraction: (Boolean) -> Unit,
    initialSelectionMs: Long = 0L,
    leaving: Boolean = false
) {
    val live by vm.state.collectAsStateWithLifecycle()
    val state by rememberUpdatedState(retainedWhileLeaving(live, leaving))
    val pager = rememberPagerState(pageCount = { state.items.size })
    val latestItems by rememberUpdatedState(state.items)
    LaunchedEffect(state.initialId, state.items.isNotEmpty()) {
        val index = state.items.indexOfFirst { it.id == state.initialId }
        if (index >= 0) pager.scrollToPage(index)
    }
    val session by rememberPlaybackSession(c, create, release)
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
        onVisibleChange = { controlsVisible = it }
    )
    // The settled page plays; a moving pager or a stopped screen pauses.
    LaunchedEffect(
        active,
        pager,
        foreground,
        leaving,
        state.items.getOrNull(pager.settledPage)?.version
    ) {
        if (leaving) {
            active.pause()
            return@LaunchedEffect
        }
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.distinctUntilChanged()
            .collect { (index, moving) ->
                onInteraction(moving)
                if (moving || !foreground) active.pause() else latestItems.getOrNull(index)
                    ?.let {
                        vm.selected(it.id)
                        val opening = it.id == state.initialId && active.state.value.id.isEmpty()
                        active.select(
                            it.playback(c),
                            if (opening && initialSelectionMs > 0) {
                                initialSelectionMs
                            } else {
                                SystemClock.elapsedRealtime()
                            }
                        )
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
        leaving
    ) {
        val settled = playback.firstFrame && !playback.buffering && !pager.isScrollInProgress
        if (settled && foreground && !leaving) {
            active.preload(state.items.getOrNull(pager.settledPage + 1)?.playback(c))
        }
    }
    LaunchedEffect(pager, state.items.size, leaving) {
        if (leaving) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            if (index >= state.items.size - 3) vm.page(false)
            if (index < 2 && state.items.isNotEmpty()) vm.page(true)
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
    val currentKey = currentItem?.playback(c)?.cacheKey
    val sessionOnPage = currentKey != null && playback.id == currentKey
    if (detailsOpen && currentItem != null) {
        MediaDetailsSheet(currentItem, state.pathLabel) { detailsOpen = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The video Surface belongs to the session, not a pager item. Recreating it
        // for every settled page forces compositor transactions and decoder output
        // handoffs. Pager covers conceal it until the matching media renders.
        SessionSurface(active.player, Modifier.fillMaxSize())
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
                    if (control == EdgeControl.Brightness) levels.setBrightness(level) else levels.setVolume(level)
                    edgeLevel = control to level
                },
                onEnd = { edgeLevel = null }
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
                }
            )
        ) { index ->
            val item = state.items[index]
            val selected = pager.settledPage == index && !pager.isScrollInProgress
            val itemKey = item.playback(c).cacheKey
            val failed = selected && playback.error && playback.id == itemKey
            VideoPage(
                item,
                c,
                loader,
                covered = !selected || playback.id != itemKey || !playback.firstFrame,
                failed = failed,
                error = if (failed) active.player.playerError else null,
                onDetails = { detailsOpen = true },
                onRetry = {
                    vm.retry(item.id)
                    active.select(item.playback(c))
                }
            )
        }
        FadingChrome(
            controlsVisible && !swiping,
            currentItem,
            onBack,
            { detailsOpen = true },
            Modifier.align(Alignment.TopCenter)
        )
        ViewerFade(!swiping, Modifier.align(Alignment.BottomCenter)) {
            ControlsScrim(shaded = controlsVisible) {
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
                    { menuOpen = it }
                ) {
                    dragging = it
                    interactionSerial++
                }
            }
        }
        val showPlay = sessionOnPage && !playing && playback.firstFrame && !playback.error &&
                !playback.buffering && !pager.isScrollInProgress
        if (showPlay) PlayButton({ active.player.play() }, Modifier.align(Alignment.Center))
        if (holding) SpeedHoldLabel(Modifier.align(Alignment.TopCenter))
        edgeLevel?.let { (control, level) -> EdgeLevelLabel(control, level, Modifier.align(Alignment.TopCenter)) }
        if (seekChain.seconds > 0) {
            SeekFeedback(
                seekChain.seconds,
                seekChain.forward,
                Modifier.align(if (seekChain.forward) Alignment.CenterEnd else Alignment.CenterStart)
            )
        }
        BulletinHost(bulletin, { bulletin = null }, Modifier.align(Alignment.BottomCenter))
        if (!state.error && !swiping && (playback.buffering || !playback.firstFrame && !playback.error)) {
            BufferingIndicator(Modifier.align(Alignment.Center))
        }
        if (state.error && state.items.isEmpty()) {
            Text(
                stringResource(R.string.playback_error),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

fun Media.playback(c: Connection) = PlaybackItem(
    c.id,
    libraryId,
    id,
    version,
    "${c.address}/v1/media/$id/bytes?version=$version",
    "${c.address}/v1/media/$id/index?version=$version",
    c.token,
    size,
    durationMs
)

/** An authorized image request for this media's [resource], such as "thumbnail" or "bytes". */
internal fun Media.imageRequest(context: Context, c: Connection, resource: String): ImageRequest =
    ImageRequest.Builder(context)
        .data("${c.address}/v1/media/$id/$resource?version=$version")
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer ${c.token}").build())
        .build()

// Photos page left and right through the folder's image queue, like a phone
// gallery; videos keep the vertical feed. Zoom belongs to the page in front:
// the pager stands still while it is enlarged, and one-finger drags pan the
// photo instead.
@Composable
fun PhotoScreen(
    state: PlayerUiState,
    c: Connection,
    loader: ImageLoader,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (String) -> Unit,
    onPage: (Boolean) -> Unit,
    leaving: Boolean = false
) {
    // Sized from the loaded queue so the first frame is already the tapped photo.
    val pager = key(
        state.initialId,
        state.items.isEmpty()
    ) {
        rememberPagerState(initialPage = state.items.indexOfFirst { it.id == state.initialId }
            .coerceAtLeast(0), pageCount = { state.items.size })
    }
    // The laid-out key names the page in front even in the one composition where
    // pages arrived ahead of it and its index has not been matched again yet.
    val frontKey by remember(pager) {
        derivedStateOf {
            pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pager.currentPage }?.key
        }
    }
    val item =
        state.items.firstOrNull { it.id == frontKey } ?: state.items.getOrNull(pager.currentPage)
    val scaleState = remember(item?.id) { mutableFloatStateOf(1f) }
    var scale by scaleState
    // Composition follows only the enlarged/full-view flip; the scale itself is
    // read while drawing, so a pinch does not recompose the pager every frame.
    val zoomed by remember(scaleState) { derivedStateOf { scaleState.floatValue > 1f } }
    val latest by rememberUpdatedState(state)
    var offset by remember(item?.id) { mutableStateOf(Offset.Zero) }
    var controls by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    // A pinch released a hair above full view would keep the pager locked with
    // nothing visibly enlarged, so it settles back to full view.
    LaunchedEffect(transform.isTransformInProgress) {
        if (!transform.isTransformInProgress && scale < 1.1f) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(pager, state.items.size, leaving) {
        if (leaving) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            latest.items.getOrNull(index)?.let { onSelected(it.id) }
            if (index >= latest.items.size - 3) onPage(false)
            if (index < 2 && latest.items.isNotEmpty()) onPage(true)
        }
    }
    if (details && item != null) MediaDetailsSheet(item, state.pathLabel) { details = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Neighbours are composed ahead with their thumbnail only, so a swipe never
        // drags in an empty page. The original is fetched once a page comes in front
        // and kept until the pager rests on another page: one original at rest, two
        // while a swipe is under way.
        val resting = !pager.isScrollInProgress
        // The gap keeps two photos apart while a swipe shows both.
        HorizontalPager(
            state = pager,
            key = { state.items[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            val photo = state.items[index]
            val front = photo.id == item?.id
            PhotoPage(
                photo, c, loader, front, resting, if (front) {
                {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            } else null,
                Modifier.pointerInput(photo.id, front) {
                    detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { point ->
                        if (front) {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset(
                                    (size.width / 2f - point.x) * 1.5f,
                                    (size.height / 2f - point.y) * 1.5f
                                )
                            }
                        }
                    })
                }.then(
                    if (front) Modifier.transformable(
                        transform,
                        canPan = { scale > 1f }) else Modifier
                )
            )
        }
        if (state.error && state.items.isEmpty()) Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.photo_failed), color = Color.White)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        FadingChrome(
            controls && !pager.isScrollInProgress,
            item,
            onBack,
            { details = true },
            Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun PhotoPage(
    item: Media,
    c: Connection,
    loader: ImageLoader,
    front: Boolean,
    resting: Boolean,
    zoom: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val image =
        if (zoom != null) Modifier.fillMaxSize().graphicsLayer(zoom) else Modifier.fillMaxSize()
    // A page being swiped away keeps its original until the pager rests, so it
    // does not fall back to its thumbnail while still on screen.
    var original by remember { mutableStateOf(front) }
    var attempt by remember(item.version) { mutableIntStateOf(0) }
    var failed by remember(item.version, attempt) { mutableStateOf(false) }
    var loaded by remember(item.version, attempt) { mutableStateOf(false) }
    LaunchedEffect(front, resting) {
        if (front) original = true else if (resting) {
            original = false
            loaded = false
        }
    }
    val context = LocalContext.current
    val request = remember(item.id, item.version, c.token, attempt) {
        item.imageRequest(context, c, "bytes")
    }
    val thumb = remember(item.id, item.version, c.token) {
        item.imageRequest(context, c, "thumbnail")
    }
    Box(modifier.fillMaxSize()) {
        if (!loaded || !original) AsyncImage(
            thumb,
            null,
            imageLoader = loader,
            contentScale = ContentScale.Fit,
            modifier = image
        )
        if (original) AsyncImage(
            request,
            item.name,
            imageLoader = loader,
            contentScale = ContentScale.Fit,
            onSuccess = { loaded = true },
            onError = { failed = true },
            modifier = image
        )
        if (failed) Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.photo_failed), color = Color.White)
            Button(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
        }
    }
}
