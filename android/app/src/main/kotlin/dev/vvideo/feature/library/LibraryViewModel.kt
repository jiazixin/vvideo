package dev.vvideo.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.ChildrenWindow
import dev.vvideo.data.library.LibraryRepository
import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.anchorFallback
import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.library.remote.serviceStatus
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Directory
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import dev.vvideo.ui.component.TimeLabels
import dev.vvideo.ui.component.pathLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Loads that finish sooner slide straight into loaded content; slower ones reveal
// the destination with its placeholder. Tuning value, not a measured threshold.
private const val REVEAL_DELAY_MS = 120L

/** Typing pauses this long before a search request is sent. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** While the list moves, its position is written to the bookmark at most this often. */
private const val BOOKMARK_SAVE_DELAY_MS = 500L

/** Media per album. */
private const val ALBUM_SIZE = 10

/** No reported position: the list has not been shown since it was (re)loaded. */
private val NO_POSITION = FolderPosition("", 0, 0)

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val persistScope: CoroutineScope,
    private val timeLabels: TimeLabels? = null
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private val mediaWindow = PageWindow()
    private val subfolderWindow = ChildrenWindow()
    private val positions = FolderPositions()
    private var connection = Connection()

    /** The position the shown list last reported. */
    private var shownPosition = NO_POSITION

    private var recentJob: Job? = null
    private var openJob: Job? = null
    private var pageJob: Job? = null
    private var subfolderJob: Job? = null
    private var searchJob: Job? = null
    private var saveJob: Job? = null

    /** Bumped by every cancellation; replies to an older round are dropped. */
    private var generation = 0L

    // True while another directory loads behind the still displayed page; that
    // page must neither show loading UI nor page or save against the new window.
    private var loading = false
    private val blocked get() = loading || _state.value.busy

    // Directory whose pages the windows hold; 0 once that page was left for home.
    private var windowDirectory = 0L

    // The loaded page a placeholder replaced. The windows keep its pages until
    // another directory finishes loading, so going back to it needs no request
    // and still works when the destination never loaded.
    private class KeptPage(
        val state: LibraryUiState,
        val bookmark: BookmarkEntity?,
        val position: FolderPosition
    )

    private var kept: KeptPage? = null

    private var paginating = false
    private var canPageNext = true
    private var canPagePrevious = true
    private var bookmark: BookmarkEntity? = null

    private fun cancelRequests() {
        generation++
        openJob?.cancel()
        pageJob?.cancel()
        subfolderJob?.cancel()
        searchJob?.cancel()
        paginating = false
        loading = false
    }

    /** Clears what belongs to the shown folder before another one, or none, is shown. */
    private fun forgetShownFolder() {
        shownPosition = NO_POSITION
        bookmark = null
        kept = null
        windowDirectory = 0
    }

    // A refresh of the same connection keeps the shown libraries until the reply replaces them.
    fun connect(newConnection: Connection, refresh: Boolean = false) {
        val sameServer = connection.id == newConnection.id
        val sameConnection = sameServer && connection.token == newConnection.token
        if (!refresh && sameConnection && _state.value.libraries.isNotEmpty()) return
        flush()
        cancelRequests()
        if (!sameConnection) positions.clear()
        forgetShownFolder()
        val previousRecent = if (sameServer) _state.value.recent else emptyList()
        val previousLibraries = if (sameConnection) _state.value.libraries else emptyList()
        connection = newConnection
        watchRecentFolders(newConnection.id)
        openJob = viewModelScope.launch {
            _state.value =
                LibraryUiState(busy = true, recent = previousRecent, libraries = previousLibraries)
            try {
                val libraries = repository.libraries(newConnection)
                _state.value = _state.value.copy(libraries = libraries, busy = false)
                if (newConnection.lastDirectory != 0L) open(newConnection.lastDirectory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(busy = false, error = true, errorStatus = e.serviceStatus())
            }
        }
    }

    private fun watchRecentFolders(connectionId: String) {
        recentJob?.cancel()
        recentJob = viewModelScope.launch {
            repository.recentFolders(connectionId).collectLatest { records ->
                val recent = withContext(Dispatchers.Default) {
                    records.map { RecentFolder(it, timeLabels?.recent(it.visitedAt).orEmpty()) }
                }
                _state.value = _state.value.copy(recent = recent)
            }
        }
    }

    /** Asks the list to scroll back to where it was, after returning to the page. */
    fun resume() {
        if (blocked) return
        val current = _state.value
        val saved = bookmark
        val reported = shownPosition.key.isNotEmpty()
        _state.value = current.copy(
            restore = ScrollRestore(
                key = shownPosition.key,
                anchor = saved?.anchor.orEmpty(),
                offset = if (reported) shownPosition.offset else saved?.pixelOffset ?: 0,
                width = if (reported) shownPosition.width else saved?.width ?: 0,
                serial = current.restore.serial + 1
            )
        )
    }

    // The notice belongs to the load that missed the anchor; resuming the page or
    // coming back from a viewer does not repeat it.
    fun anchorNoticeShown() {
        if (_state.value.anchorMissing) _state.value = _state.value.copy(anchorMissing = false)
    }

    /** The first visible row [key], scrolled [offset] px into a list [width] px wide. */
    fun reportViewport(key: String, offset: Int, width: Int) {
        if (blocked) return
        shownPosition = FolderPosition(key, offset, width)
        val index = _state.value.albums.indexOfFirst { albumKey(it) == key }
        if (index >= 0) save(index, offset, width)
    }

    fun back() {
        val directory = _state.value.directory ?: return
        if (directory.parentId != 0L) open(directory.parentId) else roots()
    }

    /** Searches the open folder for [query] after a short pause; a blank query ends the search. */
    fun search(query: String) {
        val directory = _state.value.directory ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(search = SearchState(query))
            return
        }
        _state.value = _state.value.copy(
            search = _state.value.search.copy(
                query = query,
                busy = true,
                error = false
            )
        )
        val searchConnection = connection
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val outcome = try {
                SearchState(query, repository.search(searchConnection, directory.id, query.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value.search.copy(busy = false, error = true)
            }
            if (_state.value.directory?.id == directory.id && _state.value.search.query == query) {
                _state.value = _state.value.copy(search = outcome)
            }
        }
    }

    /** Shows the library list. */
    fun roots() {
        leaveFolder()
        flush()
        cancelRequests()
        forgetShownFolder()
        _state.value =
            LibraryUiState(libraries = _state.value.libraries, recent = _state.value.recent)
        viewModelScope.launch { repository.setLastDirectory(0) }
        // The list stays shown; the reply refreshes library health or reports the
        // server unreachable, instead of the state from before the folder opened.
        val shown = connection
        openJob = viewModelScope.launch {
            try {
                val libraries = repository.libraries(shown)
                _state.value = _state.value.copy(libraries = libraries, error = false, errorStatus = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = true, errorStatus = e.serviceStatus())
            }
        }
    }

    /**
     * Opens folder [id]. Without [requestedSort], [filter] or [fromStart] it returns
     * to the position this session or the folder's bookmark left it at.
     */
    fun open(
        id: Long,
        requestedSort: Sort? = null,
        refresh: Boolean = false,
        filter: MediaKind? = null,
        fromStart: Boolean = false
    ) {
        val left = shownPosition
        val restore = requestedSort == null && filter == null && !fromStart
        // A refresh keeps the folder where it is; a new sort, filter or jump starts it over.
        if (_state.value.directory?.id != id || restore) leaveFolder()
        if (!restore) positions.forget(id)
        flush()
        cancelRequests()
        shownPosition = NO_POSITION
        // Opening any folder, including one found by a search, ends the search.
        if (_state.value.search != SearchState()) _state.value =
            _state.value.copy(search = SearchState())
        kept?.let { page ->
            if (page.state.directory?.id == id && restore && !refresh) {
                restoreKept(page)
                return
            }
        }
        loading = true
        val origin = _state.value
        val leaving = origin.directory?.id != id
        if (!leaving) _state.value = origin.copy(busy = true, error = false, filter = filter)
        openJob = viewModelScope.launch {
            // The displayed page stays intact while another directory loads; only a slow
            // load reveals the destination early, carrying its own first-load placeholder.
            val reveal = if (leaving) revealLater(origin, id, left) else null
            val saved = if (refresh && bookmark?.directory == id) bookmark else repository.bookmark(
                connection.id,
                id
            )
            val sort = requestedSort ?: saved?.let {
                Sort(SortField.fromWire(it.sort), SortDirection.fromWire(it.direction))
            } ?: Sort()
            try {
                val first =
                    repository.firstPage(connection, id, sort, if (restore) saved else null, filter)
                val subfolders = repository.children(connection, id)
                val path = resolvePath(origin.path, id, first.directory)
                val albums = albums(sort, first.items)
                val returnedTo = if (restore) positions[id] else null
                // Nothing suspends from here to the published state: the windows, the
                // bookmark and the page change together, and a late reveal must not undo it.
                reveal?.cancel()
                mediaWindow.reset(first)
                subfolderWindow.reset(subfolders)
                windowDirectory = id
                kept = null
                canPageNext = true
                canPagePrevious = first.previous.isNotEmpty()
                bookmark = if (requestedSort == null && saved != null) saved else BookmarkEntity(
                    connection.id,
                    id,
                    first.directory.libraryId,
                    sort.field.wire,
                    sort.direction.wire
                )
                loading = false
                val current = _state.value
                _state.value = current.copy(
                    directory = first.directory,
                    filter = filter,
                    subfolders = Subfolders(subfolders.items, subfolders.next, subfolders.previous),
                    pageBusy = false,
                    path = path,
                    pathLabel = pathLabel(path),
                    sort = sort,
                    hasMedia = first.items.isNotEmpty() || (filter != null && current.hasMedia),
                    hasPrevious = mediaWindow.previous.isNotEmpty(),
                    albums = albums,
                    busy = false,
                    error = false,
                    errorStatus = 0,
                    healthy = first.healthy,
                    restore = ScrollRestore(
                        key = returnedTo?.key.orEmpty(),
                        anchor = if (restore) saved?.anchor.orEmpty() else "",
                        offset = returnedTo?.offset ?: if (restore) saved?.pixelOffset ?: 0 else 0,
                        width = returnedTo?.width ?: saved?.width ?: 0,
                        serial = current.restore.serial + 1
                    ),
                    anchorMissing = restore && anchorMoved(saved?.anchor.orEmpty(), first)
                )
                recordVisit(id, first.directory, path, first.healthy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reveal?.cancel()
                loading = false
                // A destination that failed before it was revealed, as when the server
                // refuses the connection at once, is still shown with the error, so going
                // back moves up a level instead of staying on the folder being left.
                val pending = if (leaving && _state.value.directory?.id != id) {
                    pendingDirectory(origin, id)
                } else null
                if (pending != null) reveal(origin, pending, left)
                _state.value =
                    _state.value.copy(busy = false, error = true, errorStatus = e.serviceStatus())
            }
        }
    }

    /**
     * Shows the destination's placeholder in place of [origin] once the load is slow
     * enough to notice, keeping [origin] to return to. A destination that cannot be
     * named yet marks the displayed page busy instead.
     */
    private fun CoroutineScope.revealLater(
        origin: LibraryUiState,
        id: Long,
        left: FolderPosition
    ): Job {
        val pending = pendingDirectory(origin, id)
        return launch {
            delay(REVEAL_DELAY_MS)
            if (pending == null) {
                _state.value = _state.value.copy(busy = true)
                return@launch
            }
            reveal(origin, pending, left)
        }
    }

    /** Shows [pending] in place of [origin], keeping [origin] to return to. */
    private fun reveal(origin: LibraryUiState, pending: LibraryUiState, left: FolderPosition) {
        // Only a settled page that owns the windows can be returned to as it was.
        if (windowDirectory != 0L && origin.directory?.id == windowDirectory && !origin.busy) {
            kept = KeptPage(origin, bookmark, left)
        }
        // The placeholder was built from the state at the tap; lists refreshed since then stay.
        bookmark = null
        _state.value =
            _state.value.let { pending.copy(recent = it.recent, libraries = it.libraries) }
    }

    /** The path down to [directory], from the [known] path when it leads there, else from the server. */
    private suspend fun resolvePath(
        known: List<Directory>,
        id: Long,
        directory: Directory
    ): List<Directory> {
        val existing = known.indexOfFirst { it.id == id }
        val path = when {
            existing >= 0 -> known.take(existing + 1)
            known.lastOrNull()?.id == directory.parentId -> known + directory
            else -> listOf(directory)
        }
        if (path.size != 1 || directory.parentId == 0L) return path
        return try {
            repository.path(connection, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Browsing remains available if an older service has no directory endpoint.
            path
        }
    }

    /** Records the opened folder for the recent list and, while it is reachable, for a cold start. */
    private suspend fun recordVisit(
        id: Long,
        directory: Directory,
        path: List<Directory>,
        healthy: Boolean
    ) {
        val record = checkNotNull(bookmark).copy(
            name = directory.name,
            pathLabel = pathLabel(path),
            visitedAt = System.currentTimeMillis(),
            hiddenFromRecent = false
        )
        bookmark = record
        repository.recordVisit(record)
        if (healthy) repository.setLastDirectory(id)
    }

    // Keys of a filtered list do not place the unfiltered one, so only an unfiltered
    // page is remembered; the position key is blank until the shown page reported one.
    private fun leaveFolder() {
        val current = _state.value
        val directory = current.directory ?: return
        if (current.filter == null) positions.leave(directory.id, shownPosition)
    }

    private fun restoreKept(page: KeptPage) {
        kept = null
        bookmark = page.bookmark
        shownPosition = page.position
        _state.value = restoredPage(page.state, _state.value)
        resume()
    }

    private suspend fun albums(
        sort: Sort = _state.value.sort,
        items: List<Media> = mediaWindow.items
    ) =
        withContext(Dispatchers.Default) {
            datedAlbums(items.chunked(ALBUM_SIZE).map { Album(it) }, sort, timeLabels)
        }

    /** Shows only [kind] media of the open folder from its start; null shows every kind. */
    fun setFilter(kind: MediaKind?) {
        val current = _state.value
        current.directory?.let { open(it.id, current.sort, filter = kind, fromStart = true) }
    }

    fun jumpToStart() {
        val current = _state.value
        current.directory?.let {
            open(
                it.id,
                current.sort,
                filter = current.filter,
                fromStart = true
            )
        }
    }

    // A published window is ready for paging even while open() persists its visit.
    // Blocking on the whole request Job can discard the only visible-edge event.
    fun page(edge: PageEdge) {
        val previous = edge == PageEdge.Previous
        if (paginating || blocked || (!previous && !canPageNext) || (previous && !canPagePrevious)) return
        val directory = _state.value.directory ?: return
        val cursor = if (previous) mediaWindow.previous else mediaWindow.next
        if (cursor.isEmpty()) return
        paginating = true
        _state.value = _state.value.copy(pageBusy = true)
        val round = generation
        val sort = _state.value.sort
        pageJob = viewModelScope.launch {
            try {
                val page = repository.page(
                    connection,
                    directory.id,
                    sort,
                    cursor,
                    edge,
                    _state.value.filter
                )
                ensureActive()
                if (round != generation) return@launch
                if (page.items.isEmpty()) {
                    if (previous) canPagePrevious = false else canPageNext = false
                } else {
                    if (previous) {
                        mediaWindow.prepend(page)
                        canPageNext = true
                    } else {
                        mediaWindow.append(page)
                        canPagePrevious = true
                    }
                    val albums = albums()
                    _state.value = _state.value.copy(
                        albums = albums,
                        hasPrevious = mediaWindow.previous.isNotEmpty(),
                        error = false,
                        errorStatus = 0
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RevisionChanged) {
                open(directory.id, refresh = true, filter = _state.value.filter)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = true, errorStatus = e.serviceStatus())
            } finally {
                if (round == generation) {
                    paginating = false
                    _state.value = _state.value.copy(pageBusy = false)
                }
            }
        }
    }

    fun moreSubfolders(edge: PageEdge = PageEdge.Next) {
        val current = _state.value
        val directory = current.directory ?: return
        val cursor =
            if (edge == PageEdge.Previous) current.subfolders.previous else current.subfolders.next
        if (cursor.isEmpty() || subfolderJob?.isActive == true || blocked) return
        val round = generation
        subfolderJob = viewModelScope.launch {
            _state.value = _state.value.copy(subfolders = _state.value.subfolders.copy(busy = true))
            try {
                val page = repository.children(connection, directory.id, cursor, edge)
                ensureActive()
                if (round == generation) {
                    val exhausted = page.items.isEmpty()
                    if (!exhausted) subfolderWindow.add(page, edge)
                    _state.value = _state.value.copy(
                        subfolders = _state.value.subfolders.copy(
                            items = subfolderWindow.items,
                            next = if (edge == PageEdge.Next && exhausted) "" else subfolderWindow.next,
                            previous = if (edge == PageEdge.Previous && exhausted) "" else subfolderWindow.previous
                        ),
                        error = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (round == generation) {
                    _state.value = _state.value.copy(error = true, errorStatus = e.serviceStatus())
                }
            } finally {
                if (round == generation) {
                    _state.value =
                        _state.value.copy(subfolders = _state.value.subfolders.copy(busy = false))
                }
            }
        }
    }

    /** Remembers album [albumIndex], scrolled [offset] px into a list [width] px wide, as the bookmark. */
    fun save(albumIndex: Int, offset: Int, width: Int) {
        val current = _state.value
        if (blocked || current.error || !current.healthy || current.filter != null) return
        val album = current.albums.getOrNull(albumIndex) ?: return
        val first = album.items.firstOrNull() ?: return
        val base = bookmark ?: return
        val revision = current.directory?.revision ?: 0L
        bookmark = base.copy(
            anchor = first.id,
            neighbor = album.items.getOrNull(1)?.id.orEmpty(),
            fallback = anchorFallback(first, revision, current.sort),
            pixelOffset = offset,
            width = width,
            revision = revision,
            savedAt = System.currentTimeMillis()
        )
        if (saveJob?.isActive != true) saveJob = persistScope.launch {
            delay(BOOKMARK_SAVE_DELAY_MS)
            bookmark?.let { repository.saveBookmark(it) }
        }
    }

    /** Writes the bookmark now instead of after the save delay. */
    fun flush() {
        if (_state.value.filter != null) return
        val record = bookmark ?: return
        saveJob?.cancel()
        persistScope.launch { repository.saveBookmark(record) }
    }

    fun sort(sort: Sort) {
        val current = _state.value
        val directory = current.directory ?: return
        if (current.filter != null) {
            open(directory.id, sort, filter = current.filter, fromStart = true)
            return
        }
        val cleared =
            BookmarkEntity(
                connection.id,
                directory.id,
                directory.libraryId,
                sort.field.wire,
                sort.direction.wire
            )
        bookmark = cleared
        persistScope.launch { repository.saveBookmark(cleared) }
        open(directory.id, sort)
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }
}
