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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import dev.vvideo.feature.library.album.AlbumLayoutCalculator
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
        val measurer = rememberTextMeasurer()
        val textStyle = MaterialTheme.typography.labelMedium
        val pending = stringResource(R.string.unknown_duration)
        val labels = remember(album.items, pending) {
            album.items.map {
                if (it.durationMs > 0) mediaDuration(it.durationMs) else pending
            }
        }
        // Only video cells carry a label (their duration). The group's capture time
        // is no longer drawn in the last cell: on a photo it read as the photo's own
        // time or duration, and the date row above the group already names the day.
        val minWidths = remember(
            labels,
            density.fontScale,
            textStyle
        ) {
            labels.map { label ->
                with(density) {
                    measurer.measure(
                        "▶ " + label,
                        textStyle
                    ).size.width.toDp().value
                } + 26f
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
        ) {
            val width = maxWidth
            // Settled in the item's first composition: the row split depends on the
            // measured width and label widths, and a provisional split replaced a frame
            // later changes the item's height after the list has been positioned, which
            // cuts short a restored position near the end. Ten items, four cells a row.
            val rows = remember(
                album.items,
                width,
                minWidths
            ) {
                AlbumLayoutCalculator.calculate(
                    album.items.map(Media::displayRatio),
                    width.value,
                    minWidths
                )
            }
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (row in rows) Row(
                    Modifier.fillMaxWidth().height(width * row.heightRatio),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    row.indices.forEachIndexed { position, index ->
                        val item = album.items[index]
                        var coverLoaded by remember(item.id, item.version) { mutableStateOf(false) }
                        BoxWithConstraints(
                            Modifier.weight(row.weights[position]).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = { onMedia(item) },
                                    onLongClick = { onDetails(item) })
                        ) {
                            Thumbnail(item, connection, loader, thumbnail) { coverLoaded = it }
                            if (coverLoaded && item.kind == "video" && minOf(
                                    maxWidth,
                                    maxHeight
                                ) >= 96.dp
                            ) Box(
                                Modifier.size(40.dp).align(Alignment.Center).background(
                                    Color.Black.copy(alpha = .45f),
                                    androidx.compose.foundation.shape.CircleShape
                                ), contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_play),
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            if (item.kind == "video") Text(
                                "▶ " + labels[index],
                                color = Color.White,
                                style = textStyle,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                    .background(
                                        Color.Black.copy(alpha = .5f),
                                        RoundedCornerShape(10.dp)
                                    ).padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(
    item: Media,
    c: Connection,
    loader: ImageLoader,
    url: (Connection, Media) -> String,
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
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val showLabel = minOf(maxWidth, maxHeight) >= 72.dp
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
