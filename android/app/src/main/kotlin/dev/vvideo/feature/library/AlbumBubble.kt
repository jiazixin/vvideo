package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.library.album.AlbumGeometryCalculator
import dev.vvideo.feature.library.album.TelegramGroupedLayout
import dev.vvideo.model.*
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.mediaDuration
import dev.vvideo.ui.theme.LocalVV
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumBubble(
    album: Album,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    onMedia: (Media) -> Unit,
    onDetails: (Media) -> Unit = {}
) {
    // A filter may retain the first item key while shrinking a group; everything
    // below restarts with the new item list.
    key(album.items) {
        val density = LocalDensity.current
        val window = LocalWindowInfo.current.containerSize
        val textStyle = MaterialTheme.typography.labelMedium
        val pending = stringResource(R.string.unknown_duration)
        val labels = remember(album.items, pending) {
            album.items.map {
                if (it.durationMs > 0) mediaDuration(it.durationMs) else pending
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
        ) {
            val albumWidth = constraints.maxWidth.toFloat()
            // Settled in the item's first composition from stable metadata and the
            // measured width, so the item never changes height after the list has
            // been positioned (a restored position near the end would move).
            val geometry = remember(album.items, albumWidth, window, density.density) {
                // Telegram sizes cells from the screen; before the window reports its
                // size, the album's own width stands in for a portrait screen.
                val known = window.width > 0 && window.height > 0
                val shortSide = (if (known) minOf(
                    window.width,
                    window.height
                ).toFloat() else albumWidth).coerceAtLeast(1f)
                val longSide = (if (known) maxOf(
                    window.width,
                    window.height
                ).toFloat() else albumWidth * 2f).coerceAtLeast(1f)
                val positions = TelegramGroupedLayout.calculate(
                    album.items.map(Media::displayRatio),
                    density.density,
                    shortSide
                )
                AlbumGeometryCalculator.geometry(
                    positions,
                    shortSide,
                    longSide,
                    albumWidth,
                    with(density) { CELL_GAP.toPx() }
                )
            }
            // One uniform clip for the album's outer corners and a uniform one per cell:
            // both stay simple rounded rects, which the renderer clips cheaply, where a
            // cell with mixed corner radii would need a path clip.
            Box(
                Modifier.fillMaxWidth().height(with(density) { geometry.height.toDp() })
                    .clip(RoundedCornerShape(OUTER_RADIUS))
            ) {
                for (cell in geometry.cells) {
                    val item = album.items[cell.index]
                    val shortSide = with(density) { minOf(cell.width, cell.height).toDp() }
                    AlbumCellContent(
                        item,
                        if (item.kind == "video") labels[cell.index] else null,
                        shortSide,
                        textStyle,
                        CELL_SHAPE,
                        Modifier
                            .offset { IntOffset(cell.x.roundToInt(), cell.y.roundToInt()) }
                            .size(
                                with(density) { cell.width.toDp() },
                                with(density) { cell.height.toDp() }),
                        loader,
                        connection,
                        thumbnail,
                        onClick = { onMedia(item) },
                        onLongClick = { onDetails(item) }
                    )
                }
            }
        }
    }
}

/** Space between cells. */
private val CELL_GAP = 2.dp

/** Telegram rounds an album's outer corners by its bubble radius minus 2 (17 dp by default). */
private val OUTER_RADIUS = 15.dp

/** Telegram keeps a small radius on the corners inside an album. */
private val INNER_RADIUS = 4.dp

/** Every cell keeps Telegram's small inner radius; the album clip rounds the outer corners. */
private val CELL_SHAPE = RoundedCornerShape(INNER_RADIUS)

/**
 * One cell: the cover, and for a video its duration and, on a large cell, a play button.
 * The cell's size is known from the album geometry, so [shortSide] replaces measuring
 * it again with BoxWithConstraints (a subcomposition per cell).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCellContent(
    item: Media,
    duration: String?,
    shortSide: Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
    shape: RoundedCornerShape,
    modifier: Modifier,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var coverLoaded by remember(item.id, item.version) { mutableStateOf(false) }
    Box(
        modifier.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Thumbnail(
            item,
            connection,
            loader,
            thumbnail,
            showLabel = shortSide >= 72.dp
        ) { coverLoaded = it }
        if (coverLoaded && duration != null && shortSide >= 96.dp) {
            Box(
                Modifier.size(40.dp).align(Alignment.Center).background(
                    Color.Black.copy(alpha = .45f),
                    androidx.compose.foundation.shape.CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_play),
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (duration != null) {
            Text(
                "▶ $duration",
                color = Color.White,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
internal fun Thumbnail(
    item: Media,
    c: Connection,
    loader: ImageLoader,
    url: (Connection, Media) -> String,
    showLabel: Boolean = false,
    onLoaded: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember(item.id, item.version) { mutableIntStateOf(0) }
    var failed by remember(item.id, item.version) { mutableStateOf(false) }
    var loaded by remember(item.id, item.version) { mutableStateOf(false) }
    var retryDelay by remember(item.id, item.version) { mutableLongStateOf(1000) }
    var retryable by remember(item.id, item.version) { mutableStateOf(true) }
    // Requests are tied to visible composition and cancelled by Coil on disposal.
    LaunchedEffect(failed, attempt) {
        if (failed && retryable && attempt < 3 && item.status != "failed") {
            delay(maxOf(1000L shl attempt, retryDelay))
            attempt++
            failed = false
        }
    }
    val request = remember(item.id, item.version, c.id, c.token, attempt) {
        ImageRequest.Builder(context).data(url(c, item)).crossfade(120)
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer ${c.token}").build())
            .build()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!loaded) Column(Modifier.padding(8.dp).clickable(enabled = failed && retryable) {
            attempt++
            failed = false
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = when {
                failed && !retryable -> R.drawable.ic_image_off
                failed -> R.drawable.ic_refresh
                else -> R.drawable.ic_clock
            }
            Icon(painterResource(icon), null, tint = LocalVV.current.textSecondary)
            if (showLabel) {
                val label = when {
                    failed && !retryable -> R.string.cover_unsupported
                    failed -> R.string.cover_retry
                    else -> R.string.cover_generating
                }
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
        AsyncImage(
            request,
            stringResource(R.string.media_description, item.name, item.kind),
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            onSuccess = {
                loaded = true
                failed = false
                onLoaded(true)
            },
            onError = { error ->
                val response = (error.result.throwable as? coil3.network.HttpException)?.response
                retryable = response == null || response.code in listOf(
                    408,
                    429,
                    500,
                    502,
                    503,
                    504
                ) || (response.code == 404 && item.status != "ready")
                val after = response?.headers?.get("Retry-After")
                scope.launch {
                    retryDelay = withContext(Dispatchers.Default) {
                        after?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }
                            ?.times(1000) ?: runCatching {
                            java.time.ZonedDateTime.parse(
                                after,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            ).toInstant().toEpochMilli() - System.currentTimeMillis()
                        }.getOrDefault(1000).coerceAtLeast(0)
                    }
                    failed = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
