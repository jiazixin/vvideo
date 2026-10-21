package dev.vvideo.feature.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort

/** The library list while no folder is open, otherwise the open folder. */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loader: ImageLoader,
    connection: Connection,
    list: LazyListState,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSort: (Sort) -> Unit,
    onMoreSubfolders: (PageEdge) -> Unit,
    onRetry: () -> Unit,
    onMedia: (Media) -> Unit,
    onFilter: (MediaKind?) -> Unit = {},
    onJump: () -> Unit = {},
    onDetails: (Media) -> Unit = {},
    onConnection: () -> Unit = onSettings,
    onAnchorNotice: () -> Unit = {},
    onSearch: (String) -> Unit = {}
) {
    if (state.directory == null) {
        HomeScreen(state, onSettings, onOpen, onRetry, onConnection)
        return
    }
    FolderScreen(
        state = state,
        loader = loader,
        connection = connection,
        list = list,
        onBack = onBack,
        onOpen = onOpen,
        onSort = onSort,
        onMoreSubfolders = onMoreSubfolders,
        onRetry = onRetry,
        onMedia = onMedia,
        onFilter = onFilter,
        onJump = onJump,
        onDetails = onDetails,
        onAnchorNotice = onAnchorNotice,
        onSearch = onSearch
    )
}
