package dev.vvideo.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.library.album.AlbumGeometryCalculator
import dev.vvideo.feature.library.album.TelegramGroupedLayout
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.ui.component.mediaDuration
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumBubble(
    album: Album,
    loader: ImageLoader,
    connection: Connection,
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
                        if (item.kind == MediaKind.Video) labels[cell.index] else null,
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

/** Cells at least this large on their short side explain a missing cover in words. */
private val COVER_LABEL_MIN_SIDE = 72.dp

/** Video cells at least this large on their short side show a play badge once the cover is in. */
private val PLAY_BADGE_MIN_SIDE = 96.dp

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
    textStyle: TextStyle,
    shape: RoundedCornerShape,
    modifier: Modifier,
    loader: ImageLoader,
    connection: Connection,
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
            showLabel = shortSide >= COVER_LABEL_MIN_SIDE
        ) { coverLoaded = it }
        if (coverLoaded && duration != null && shortSide >= PLAY_BADGE_MIN_SIDE) {
            Box(
                Modifier.size(40.dp).align(Alignment.Center).background(
                    Color.Black.copy(alpha = .45f),
                    CircleShape
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
                stringResource(R.string.video_duration_badge, duration),
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
