package dev.vvideo.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.vvideo.R
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.library.remote.MediaResource
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.ui.component.imageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/** The largest pinch zoom of a photo. */
private const val MAX_ZOOM = 4f

/** A double tap zooms a photo to this scale around the tapped point. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** A pinch released below this scale settles back to full view. */
private const val ZOOM_SNAP_BACK_BELOW = 1.1f

// Photos page left and right through the folder's image queue, like a phone
// gallery; videos keep the vertical feed. Zoom belongs to the page in front:
// the pager stands still while it is enlarged, and one-finger drags pan the
// photo instead.
@Composable
fun PhotoScreen(
    state: PlayerUiState,
    connection: Connection,
    loader: ImageLoader,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (String) -> Unit,
    onPage: (PageEdge) -> Unit,
    leaving: Boolean = false
) {
    // The page count is read when the pager measures, so it must come from the
    // latest queue: a count captured with an earlier queue outlives a shrunken or
    // emptied one and indexes past it.
    val latest by rememberUpdatedState(state)
    // Sized from the loaded queue so the first frame is already the tapped photo.
    val pager = key(
        state.initialId,
        state.items.isEmpty()
    ) {
        rememberPagerState(initialPage = state.items.indexOfFirst { it.id == state.initialId }
            .coerceAtLeast(0), pageCount = { latest.items.size })
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
    var offset by remember(item?.id) { mutableStateOf(Offset.Zero) }
    var controls by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    // A pinch released a hair above full view would keep the pager locked with
    // nothing visibly enlarged, so it settles back to full view.
    LaunchedEffect(transform.isTransformInProgress) {
        if (!transform.isTransformInProgress && scale < ZOOM_SNAP_BACK_BELOW) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(pager, state.items.size, leaving) {
        if (leaving) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            latest.items.getOrNull(index)?.let { onSelected(it.id) }
            requestNearbyPages(index, latest.items.size, onPage)
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
            key = { latest.items[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            val photo = latest.items[index]
            val front = photo.id == item?.id
            PhotoPage(
                photo, connection, loader, front, resting, if (front) {
                    {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                } else null,
                Modifier.pointerInput(photo.id, front) {
                    detectTapsIgnoringDrags(onTap = { controls = !controls }, onDoubleTap = { point ->
                        if (front) {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                                offset = Offset(
                                    (size.width / 2f - point.x) * (DOUBLE_TAP_ZOOM - 1),
                                    (size.height / 2f - point.y) * (DOUBLE_TAP_ZOOM - 1)
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
    connection: Connection,
    loader: ImageLoader,
    front: Boolean,
    resting: Boolean,
    zoom: (GraphicsLayerScope.() -> Unit)?,
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
    val request = remember(item.id, item.version, connection.token, attempt) {
        item.imageRequest(context, connection, MediaResource.Bytes)
    }
    val thumb = remember(item.id, item.version, connection.token) {
        item.imageRequest(context, connection, MediaResource.Thumbnail)
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
