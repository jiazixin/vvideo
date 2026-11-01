package dev.vvideo.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.vvideo.data.library.PageEdge
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

private const val VIEWPORT_SAMPLE_MS = 200L

/** The library list while no folder is open, otherwise the open folder. */
@OptIn(FlowPreview::class)
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    thumbnail: ThumbnailContent,
    onViewport: (String, Int, Int) -> Unit,
    onResize: () -> Unit,
    onInteraction: (Boolean) -> Unit,
    onPage: (PageEdge) -> Unit,
    onSubfolderPage: (PageEdge) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSort: (Sort) -> Unit,
    onRetry: () -> Unit,
    onMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
    onFilter: (MediaKind?) -> Unit = {},
    onJump: () -> Unit = {},
    onDetails: (Media) -> Unit = {},
    onConnection: () -> Unit = onSettings,
    onAnchorNotice: () -> Unit = {},
    onSearch: (String) -> Unit = {},
) {
    val list = rememberLazyListState()
    val width = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(width, state.active) { if (state.active) onResize() }
    LaunchedEffect(state.restore.serial, list, state.active) {
        if (!state.active) return@LaunchedEffect
        restoring = true
        val restore = state.restore
        val keys = libraryKeys(state)
        // A position whose row is gone starts the list at its top, not part way into it.
        val index = restoreIndex(keys, state.albums, restore.key, restore.anchor)
        list.scrollToItem(
            index ?: 0,
            if (index != null && restore.width == width) restore.offset else 0,
        )
        restoring = false
    }
    LaunchedEffect(list, width, state.active) {
        if (!state.active) return@LaunchedEffect
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.let { items ->
                items.firstOrNull { isPositionKey(it.key.toString()) } ?: items.firstOrNull()
            }?.let { it.key to -it.offset }
        }.sample(VIEWPORT_SAMPLE_MS).collect { visible ->
            if (!restoring && visible != null) {
                onViewport(visible.first.toString(), visible.second.coerceAtLeast(0), width)
            }
        }
    }
    LaunchedEffect(list, state.active) {
        if (!state.active) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collect(onInteraction)
    }
    // hasPrevious decides whether the subfolder rows are listed, so it restarts this too.
    LaunchedEffect(list, state.albums, state.subfolders.items, state.hasPrevious, state.active) {
        if (!state.active) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key } }.distinctUntilChanged()
            .collect { keys ->
                val subfolders = state.subfolders.items
                if (subfoldersShown(state) && subfolders.isNotEmpty()) {
                    if (keys.contains(folderKey(subfolders.last().id))) onSubfolderPage(PageEdge.Next)
                    if (keys.contains(folderKey(subfolders.first().id))) onSubfolderPage(PageEdge.Previous)
                }
                if (state.albums.isNotEmpty()) {
                    if (keys.contains(albumKey(state.albums.last()))) onPage(PageEdge.Next)
                    if (keys.contains(albumKey(state.albums.first()))) onPage(PageEdge.Previous)
                }
            }
    }

    Box(modifier) {
        if (state.directory == null) {
            HomeScreen(state, onSettings, onOpen, onRetry, onConnection = onConnection)
        } else {
            FolderContent(
                state = state,
                thumbnail = thumbnail,
                list = list,
                onBack = onBack,
                onOpen = onOpen,
                onSort = onSort,
                onRetry = onRetry,
                onMedia = onMedia,
                onFilter = onFilter,
                onJump = onJump,
                onDetails = onDetails,
                onAnchorNotice = onAnchorNotice,
                onSearch = onSearch,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, fontScale = 1.5f)
@Composable
private fun LibraryScreenPreview() {
    dev.vvideo.ui.theme.VVideoTheme {
        LibraryScreen(
            state = LibraryUiState(libraries = listOf(dev.vvideo.model.Library("preview", "家庭相册", 1, true))),
            thumbnail = { _, _, _ -> },
            onViewport = { _, _, _ -> }, onResize = {}, onInteraction = {}, onPage = {}, onSubfolderPage = {},
            onSettings = {}, onBack = {}, onOpen = {}, onSort = {}, onRetry = {}, onMedia = {},
        )
    }
}
