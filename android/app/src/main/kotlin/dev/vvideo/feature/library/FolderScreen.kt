package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.*
import dev.vvideo.ui.component.*
import dev.vvideo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    list: LazyListState,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSort: (Sort) -> Unit,
    onMore: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onMedia: (Media) -> Unit,
    onKind: (String) -> Unit = {},
    onJump: () -> Unit = {},
    onDetails: (Media) -> Unit = {},
    onConnection: () -> Unit = onSettings,
    onAnchorNotice: () -> Unit = {}
) {
    if (state.directory == null) {
        HomeScreen(state, onSettings, onOpen, onRetry, onConnection)
        return
    }
    FolderScreen(
        state,
        loader,
        connection,
        thumbnail,
        list,
        onBack,
        onOpen,
        onSort,
        onMore,
        onRetry,
        onMedia,
        onKind,
        onJump,
        onDetails,
        onAnchorNotice
    )
}

/** The bottom sheets a folder page can open. */
private enum class FolderSheet { Sort, Children, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    state: LibraryUiState,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    list: LazyListState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSort: (Sort) -> Unit,
    onMore: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onMedia: (Media) -> Unit,
    onKind: (String) -> Unit,
    onJump: () -> Unit,
    onDetails: (Media) -> Unit,
    onAnchorNotice: () -> Unit = {}
) {
    val c = LocalVV.current
    val d = checkNotNull(state.directory)
    var menuOpen by remember(d.id) { mutableStateOf(false) }
    var sheet by remember(d.id) { mutableStateOf<FolderSheet?>(null) }
    var bulletin by remember { mutableStateOf<String?>(null) }
    val moved = stringResource(R.string.anchor_moved)
    LaunchedEffect(state.restoreSerial) {
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
    FolderSheetHost(sheet, state, { sheet = null }, onSort, onMore, onOpen)
    val reveal = rememberListReveal(if (state.busy) null else Triple(d.id, state.kind, state.sort))
    Scaffold(containerColor = c.bgPage, topBar = {
        FolderTopBar(
            d,
            state,
            menuOpen,
            { menuOpen = it },
            { sheet = it },
            onBack,
            jump,
            onRetry,
            onKind
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            FolderContentList(
                list,
                state,
                { reveal.value },
                loader,
                connection,
                thumbnail,
                onOpen,
                onMedia,
                onDetails
            )
            LoadingOrEmpty(d.id, state, onRetry)
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
    onSort: (Sort) -> Unit,
    onMore: (Boolean) -> Unit,
    onOpen: (Long) -> Unit
) {
    when (sheet) {
        FolderSheet.Sort -> SortSheet(state, onDismiss) {
            onDismiss()
            onSort(it)
        }

        FolderSheet.Children -> ChildrenSheet(state, onDismiss, onMore) {
            onDismiss()
            onOpen(it)
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

/** The app bar with the bars that slide in under it: offline, media kind and subfolders. */
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
    onKind: (String) -> Unit
) {
    val c = LocalVV.current
    Column(Modifier.background(c.bgSurface)) {
        TopAppBar(
            expandedHeight = 56.dp,
            title = { FolderTitle(directory, state.pathLabel) { onSheet(FolderSheet.Info) } },
            navigationIcon = { ActionIcon(R.drawable.ic_back, R.string.back, onBack) },
            actions = { FolderMenu(menuOpen, onMenuOpen, state.sort, onSheet, onJump, onRetry) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgSurface)
        )
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
        AnimatedVisibility(state.hasMedia, enter = barEnter(), exit = barExit()) {
            MediaTabs(
                state.kind,
                onKind
            )
        }
        AnimatedVisibility(
            state.albums.isNotEmpty() && state.children.isNotEmpty(),
            enter = barEnter(),
            exit = barExit()
        ) { SubfolderBar(state) { onSheet(FolderSheet.Children) } }
    }
}

@Composable
private fun FolderTitle(directory: Directory, pathLabel: String, onClick: () -> Unit) {
    val c = LocalVV.current
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
                color = c.textSecondary,
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
    val c = LocalVV.current
    Box {
        ActionIcon(R.drawable.ic_more, R.string.sort, { onExpandedChange(true) })
        DropdownMenu(
            expanded,
            { onExpandedChange(false) },
            Modifier.width(236.dp),
            shape = RoundedCornerShape(10.dp),
            containerColor = c.bgRaised
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.sort_title))
                        Text(
                            sortLabel(sort),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary
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

/** Subfolders when the folder has no media, otherwise its albums under their date rows. */
@Composable
private fun FolderContentList(
    list: LazyListState,
    state: LibraryUiState,
    contentAlpha: () -> Float,
    loader: ImageLoader,
    connection: Connection,
    thumbnail: (Connection, Media) -> String,
    onOpen: (Long) -> Unit,
    onMedia: (Media) -> Unit,
    onDetails: (Media) -> Unit
) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() },
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Zero-height key preserves a single mapping shared by restoration and pagination.
        if (state.albums.isNotEmpty() && state.children.isNotEmpty()) item("subfolder-bar") {
            Spacer(
                Modifier.height(0.dp)
            )
        }
        if (state.albums.isEmpty()) items(
            state.children,
            key = { "folder:${it.id}" },
            contentType = { "folder" }) { dir ->
            LibraryRow(
                dir.id.toString(),
                dir.name,
                onClick = { onOpen(dir.id) })
        }
        state.albums.forEach { album ->
            if (album.dateLabel != null) item(
                "date:${album.items.first().id}",
                contentType = "date"
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
                    thumbnail,
                    onMedia,
                    onDetails
                )
            }
        }
        if (state.childBusy || state.pageBusy) item("paging") {
            Box(
                Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
    }
}

/** The loading placeholder of a page without content yet, or the empty-folder state. */
@Composable
private fun LoadingOrEmpty(directoryId: Long, state: LibraryUiState, onRetry: () -> Unit) {
    val c = LocalVV.current
    // The placeholder belongs to a page without content of its own; a reload
    // keeps what is already shown, and a failed load is not an empty folder.
    val blank = state.albums.isEmpty() && state.children.isEmpty()
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
                        .background(c.bgPlaceholder, RoundedCornerShape(16.dp))
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
                it.contentType == "date" && it.offset < dateBand && it.offset + it.size > 0
            }
        }
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) floating = true else {
            delay(1200)
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
    val c = LocalVV.current
    val visible by remember(list, albums, hasPrevious) {
        derivedStateOf {
            hasPrevious || albums.indexOfFirst {
                albumKey(it) == list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.key.toString().startsWith(ALBUM_KEY_PREFIX)
                }?.key
            } > 2
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
            containerColor = c.bgRaised,
            contentColor = c.icon
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
    sort.field == "name" -> R.string.jump_to_beginning
    sort.direction == "asc" -> R.string.jump_to_oldest
    else -> R.string.jump_to_start
}

@Composable
fun DatePill(label: String) {
    Text(
        label,
        Modifier.background(LocalVV.current.datePill, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = LocalVV.current.datePillText,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun SubfolderBar(state: LibraryUiState, onClick: () -> Unit) {
    val c = LocalVV.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(c.accentSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(painterResource(R.drawable.ic_folder), null, tint = c.accentText) }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.subfolders_count,
                    state.children.size.toString() + if (state.childNext.isNotEmpty()) "+" else ""
                ), color = c.accentText, style = MaterialTheme.typography.labelSmall
            )
            Text(
                state.children.take(3).joinToString("、") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(painterResource(R.drawable.ic_chevron), null, tint = c.textTertiary)
    }
}
