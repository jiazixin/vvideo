package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Directory
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.BulletinHost
import dev.vvideo.ui.component.EmptyState
import dev.vvideo.ui.component.LetterAvatar
import dev.vvideo.ui.component.OfflineBanner
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.Motion
import dev.vvideo.ui.theme.barEnter
import dev.vvideo.ui.theme.barExit
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The floating date stays this long after the list stops scrolling. */
private const val FLOATING_DATE_HOLD_MS = 1200L

/** Index of the first album whose top row shows the jump-to-start button. */
private const val JUMP_BUTTON_FIRST_ALBUM = 3

/** The bottom sheets a folder page can open. */
private enum class FolderSheet { Sort, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    state: LibraryUiState,
    loader: ImageLoader,
    connection: Connection,
    list: LazyListState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSort: (Sort) -> Unit,
    onRetry: () -> Unit,
    onMedia: (Media) -> Unit,
    onFilter: (MediaKind?) -> Unit,
    onJump: () -> Unit,
    onDetails: (Media) -> Unit,
    onAnchorNotice: () -> Unit = {},
    onSearch: (String) -> Unit = {}
) {
    val colors = LocalVVColors.current
    val directory = checkNotNull(state.directory)
    var menuOpen by remember(directory.id) { mutableStateOf(false) }
    var sheet by remember(directory.id) { mutableStateOf<FolderSheet?>(null) }
    var bulletin by remember { mutableStateOf<String?>(null) }
    val moved = stringResource(R.string.anchor_moved)
    LaunchedEffect(state.restore.serial) {
        if (state.anchorMissing) {
            bulletin = moved
            onAnchorNotice()
        }
    }
    val scope = rememberCoroutineScope()
    // Earlier pages that are not loaded are reached through the repository.
    val jump = {
        if (state.hasPrevious) onJump() else {
            scope.launch { list.animateScrollToItem(0) }
            Unit
        }
    }
    BackHandler(menuOpen || sheet != null) { if (menuOpen) menuOpen = false else sheet = null }
    // Back first ends a search and returns to the folder's albums.
    BackHandler(state.search.active && !menuOpen && sheet == null) { onSearch("") }
    FolderSheetHost(sheet, state, { sheet = null }, onSort)
    val reveal =
        rememberListReveal(if (state.busy) null else Triple(directory.id, state.filter, state.sort))
    Scaffold(containerColor = colors.bgPage, topBar = {
        FolderTopBar(
            directory,
            state,
            menuOpen,
            { menuOpen = it },
            { sheet = it },
            onBack,
            jump,
            onRetry,
            onFilter,
            onSearch
        )
    }) { padding ->
        if (state.search.active) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                FolderSearchResults(state.search, loader, connection, onOpen, onMedia) {
                    onSearch(state.search.query)
                }
            }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            FolderContentList(
                list,
                state,
                { reveal.value },
                loader,
                connection,
                onOpen,
                onMedia,
                onDetails
            )
            LoadingOrEmpty(directory.id, state, onRetry)
            FloatingDate(
                list,
                state.albums,
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            )
            JumpToStartButton(
                list,
                state.albums,
                state.hasPrevious,
                state.sort,
                jump,
                Modifier.align(Alignment.BottomEnd)
            )
            BulletinHost(bulletin, { bulletin = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun FolderSheetHost(
    sheet: FolderSheet?,
    state: LibraryUiState,
    onDismiss: () -> Unit,
    onSort: (Sort) -> Unit
) {
    when (sheet) {
        FolderSheet.Sort -> SortSheet(state, onDismiss) {
            onDismiss()
            onSort(it)
        }

        FolderSheet.Info -> FolderInfoSheet(state, onDismiss)
        null -> Unit
    }
}

/**
 * Fades the list in when [content] (folder, kind and sort) replaces a different one.
 * Opening, refreshing or returning to the page does not fade; null means loading.
 */
@Composable
private fun rememberListReveal(content: Any?): Animatable<Float, AnimationVector1D> {
    val reveal = remember { Animatable(1f) }
    val revealSpec = fade<Float>(Motion.CHANGE)
    val shown = remember {
        object {
            var content: Any? = null
        }
    }
    LaunchedEffect(content) {
        if (content == null) return@LaunchedEffect
        val previous = shown.content
        shown.content = content
        if (previous != null && previous != content) {
            reveal.snapTo(.2f)
            reveal.animateTo(1f, revealSpec)
        }
    }
    return reveal
}

/** The app bar with the bars that slide in under it: offline and media kind. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTopBar(
    directory: Directory,
    state: LibraryUiState,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onSheet: (FolderSheet) -> Unit,
    onBack: () -> Unit,
    onJump: () -> Unit,
    onRetry: () -> Unit,
    onFilter: (MediaKind?) -> Unit,
    onSearch: (String) -> Unit
) {
    val colors = LocalVVColors.current
    val searching = state.search.active
    Column(Modifier.background(colors.bgSurface)) {
        TopAppBar(
            expandedHeight = 56.dp,
            title = { FolderTitle(directory, state.pathLabel) { onSheet(FolderSheet.Info) } },
            navigationIcon = { ActionIcon(R.drawable.ic_back, R.string.back, onBack) },
            actions = { FolderMenu(menuOpen, onMenuOpen, state.sort, onSheet, onJump, onRetry) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgSurface)
        )
        FolderSearchField(state.search.query, onSearch)
        AnimatedVisibility(
            !state.healthy || state.error,
            enter = barEnter(),
            exit = barExit()
        ) {
            OfflineBanner(
                if (state.error) stringResource(R.string.offline_banner) else stringResource(
                    R.string.library_offline
                ), onRetry
            )
        }
        AnimatedVisibility(state.hasMedia && !searching, enter = barEnter(), exit = barExit()) {
            MediaTabs(
                state.filter,
                onFilter
            )
        }
    }
}

@Composable
private fun FolderTitle(directory: Directory, pathLabel: String, onClick: () -> Unit) {
    val colors = LocalVVColors.current
    Row(
        Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LetterAvatar(directory.id.toString(), directory.name, 40.dp)
        Column {
            Text(
                directory.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pathLabel.ifEmpty { stringResource(R.string.libraries) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The ⋮ menu: sort, back to the start, refresh and folder info. Every item closes it. */
@Composable
private fun FolderMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sort: Sort,
    onSheet: (FolderSheet) -> Unit,
    onJump: () -> Unit,
    onRefresh: () -> Unit
) {
    val colors = LocalVVColors.current
    Box {
        ActionIcon(R.drawable.ic_more, R.string.sort, { onExpandedChange(true) })
        DropdownMenu(
            expanded,
            { onExpandedChange(false) },
            Modifier.width(236.dp),
            shape = RoundedCornerShape(10.dp),
            containerColor = colors.bgRaised
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.sort_title))
                        Text(
                            sortLabel(sort),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null) },
                onClick = {
                    onExpandedChange(false)
                    onSheet(FolderSheet.Sort)
                })
            DropdownMenuItem(
                text = { Text(stringResource(jumpLabel(sort))) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_up), null) },
                onClick = {
                    onExpandedChange(false)
                    onJump()
                })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.refresh)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), null) },
                onClick = {
                    onExpandedChange(false)
                    onRefresh()
                })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_info)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) },
                onClick = {
                    onExpandedChange(false)
                    onSheet(FolderSheet.Info)
                })
        }
    }
}

/** The folder's subfolders, then its albums under their date rows; [libraryKeys] lists the same rows. */
@Composable
private fun FolderContentList(
    list: LazyListState,
    state: LibraryUiState,
    contentAlpha: () -> Float,
    loader: ImageLoader,
    connection: Connection,
    onOpen: (Long) -> Unit,
    onMedia: (Media) -> Unit,
    onDetails: (Media) -> Unit
) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() },
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (subfoldersShown(state)) items(
            state.subfolders.items,
            key = { folderKey(it.id) },
            contentType = { "folder" }) { dir ->
            LibraryRow(
                dir.id.toString(),
                dir.name,
                onClick = { onOpen(dir.id) })
        }
        state.albums.forEach { album ->
            if (album.dateLabel != null) item(
                dateKey(album),
                contentType = DATE_CONTENT_TYPE
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) { DatePill(album.dateLabel) }
            }
            item(albumKey(album), contentType = "album") {
                AlbumBubble(
                    album,
                    loader,
                    connection,
                    onMedia,
                    onDetails
                )
            }
        }
        if (state.subfolders.busy || state.pageBusy) item("paging") {
            Box(
                Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
    }
}

/** The loading placeholder of a page without content yet, or the empty-folder state. */
@Composable
private fun LoadingOrEmpty(directoryId: Long, state: LibraryUiState, onRetry: () -> Unit) {
    val colors = LocalVVColors.current
    // The placeholder belongs to a page without content of its own; a reload
    // keeps what is already shown, and a failed load is not an empty folder.
    val blank = state.albums.isEmpty() && state.subfolders.items.isEmpty()
    // An empty folder is content too: reloading it keeps the empty state
    // instead of swapping in the placeholder and back.
    val settled = remember(directoryId) {
        object {
            var empty = false
        }
    }
    if (!state.busy) settled.empty = blank && !state.error
    // Arriving content shows through the fading placeholder.
    AnimatedVisibility(
        state.busy && blank && !settled.empty,
        enter = fadeIn(fade()),
        exit = fadeOut(fade(Motion.CHANGE))
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(2) {
                Box(
                    Modifier.fillMaxWidth().height(260.dp)
                        .background(colors.bgPlaceholder, RoundedCornerShape(16.dp))
                )
            }
        }
    }
    AnimatedVisibility(
        blank && !state.error && (!state.busy || settled.empty),
        enter = fadeIn(fade(Motion.CHANGE)),
        exit = fadeOut(fade())
    ) {
        EmptyState(
            stringResource(R.string.empty_media),
            action = stringResource(R.string.refresh),
            onAction = onRetry
        )
    }
}

/**
 * The day of the first visible album, floating over the list while it scrolls and
 * for a moment after. Its scroll-driven reads stay in this scope.
 */
@Composable
private fun FloatingDate(list: LazyListState, albums: List<Album>, modifier: Modifier = Modifier) {
    val firstAlbum by remember(list, albums) {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { visible ->
                albums.firstOrNull { albumKey(it) == visible.key }
            }
        }
    }
    var floating by remember { mutableStateOf(false) }
    // The floating date covers the top of the list. Wherever an in-list date row
    // passes through that band, the two would show the same day twice (or two days
    // on top of each other), so the floating one gives way at once, without fading.
    val dateBand = with(LocalDensity.current) { 48.dp.roundToPx() }
    val dateRowAtTop by remember(list, dateBand) {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.any {
                it.contentType == DATE_CONTENT_TYPE && it.offset < dateBand && it.offset + it.size > 0
            }
        }
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) floating = true else {
            delay(FLOATING_DATE_HOLD_MS)
            floating = false
        }
    }
    AnimatedVisibility(
        floating && !dateRowAtTop && firstAlbum?.dayLabel != null,
        modifier,
        enter = fadeIn(fade()),
        exit = if (dateRowAtTop) ExitTransition.None else fadeOut(fade(Motion.CHANGE))
    ) { firstAlbum?.dayLabel?.let { DatePill(it) } }
}

/** Shown once the list is past its third album, or earlier pages exist above it. */
@Composable
private fun JumpToStartButton(
    list: LazyListState,
    albums: List<Album>,
    hasPrevious: Boolean,
    sort: Sort,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalVVColors.current
    val visible by remember(list, albums, hasPrevious) {
        derivedStateOf {
            hasPrevious || albums.indexOfFirst {
                albumKey(it) == list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key.toString().startsWith(ALBUM_KEY_PREFIX)
                }?.key
            } >= JUMP_BUTTON_FIRST_ALBUM
        }
    }
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(fade()) + scaleIn(move(), initialScale = .6f),
        exit = fadeOut(fade()) + scaleOut(move(), targetScale = .6f)
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            Modifier.navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp)
                .size(48.dp),
            shape = CircleShape,
            containerColor = colors.bgRaised,
            contentColor = colors.icon
        ) {
            Icon(
                painterResource(R.drawable.ic_arrow_up),
                stringResource(jumpLabel(sort))
            )
        }
    }
}

// The start of the list is the oldest or newest media depending on the direction.
private fun jumpLabel(sort: Sort) = when {
    sort.field == SortField.Name -> R.string.jump_to_beginning
    sort.direction == SortDirection.Ascending -> R.string.jump_to_oldest
    else -> R.string.jump_to_start
}

@Composable
fun DatePill(label: String) {
    Text(
        label,
        Modifier.background(LocalVVColors.current.datePill, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = LocalVVColors.current.datePillText,
        style = MaterialTheme.typography.labelSmall
    )
}
