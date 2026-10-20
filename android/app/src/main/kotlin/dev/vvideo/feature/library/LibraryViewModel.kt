package dev.vvideo.feature.library

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.local.BookmarkDao
import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.model.*
import dev.vvideo.ui.component.TimeLabels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

// Loads that finish sooner slide straight into loaded content; slower ones reveal
// the destination with its placeholder. Tuning value, not a measured threshold.
private const val REVEAL_DELAY_MS = 120L

/** Typing pauses this long before a search request is sent. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** A name search within the open folder and its subfolders; an empty query shows the folder. */
data class SearchState(
    val query: String = "",
    val result: SearchResult? = null,
    val busy: Boolean = false,
    val error: Boolean = false
) {
    val active: Boolean get() = query.isNotBlank()
}

// Row geometry depends on the measured width and is settled by the album item.
data class Album(
    val items: List<Media>,
    val dateLabel: String? = null,
    val dayLabel: String? = null
)

data class RecentFolder(val bookmark: BookmarkEntity, val timeLabel: String)

data class LibraryUiState(
    val recent: List<RecentFolder> = emptyList(),
    val kind: String = "",
    val hasMedia: Boolean = false,
    val hasPrevious: Boolean = false,
    val pageBusy: Boolean = false,
    val libraries: List<Library> = emptyList(),
    val directory: Directory? = null,
    val children: List<Directory> = emptyList(),
    val childNext: String = "",
    val childPrevious: String = "",
    val childBusy: Boolean = false,
    val path: List<Directory> = emptyList(),
    val pathLabel: String = "",
    val errorStatus: Int = 0,
    val albums: List<Album> = emptyList(),
    val sort: Sort = Sort(),
    val busy: Boolean = false,
    val error: Boolean = false,
    val healthy: Boolean = true,
    val restoreAnchor: String = "",
    val restoreKey: String = "",
    val restoreOffset: Int = 0,
    val restoreWidth: Int = 0,
    val restoreSerial: Long = 0,
    val anchorMissing: Boolean = false,
    val search: SearchState = SearchState()
)

class LibraryViewModel(
    private val api: LibraryApi,
    private val bookmarks: BookmarkDao,
    private val settings: SettingsRepository,
    private val persistScope: CoroutineScope,
    private val timeLabels: TimeLabels? = null
) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryUiState())
    val state = mutable.asStateFlow()
    private val window = PageWindow()
    private val childrenWindow = dev.vvideo.data.library.ChildrenWindow()
    private var viewportKey = ""
    private var viewportOffset = 0
    private var viewportWidth = 0
    private var connection = Connection()
    private var recentRequest: Job? = null
    private var request: Job? = null
    private var pageRequest: Job? = null
    private var childRequest: Job? = null
    private var generation = 0L

    // True while another directory loads behind the still displayed page; that
    // page must neither show loading UI nor page or save against the new window.
    private var loading = false
    private val blocked get() = loading || mutable.value.busy

    // Directory whose pages the windows hold; 0 once that page was left for home.
    private var windowDirectory = 0L

    // The loaded page a placeholder replaced. The windows keep its pages until
    // another directory finishes loading, so going back to it needs no request
    // and still works when the destination never loaded.
    private class KeptPage(
        val state: LibraryUiState,
        val bookmark: BookmarkEntity?,
        val viewportKey: String,
        val viewportOffset: Int,
        val viewportWidth: Int
    )

    private var kept: KeptPage? = null
    private var searchRequest: Job? = null

    private fun cancelRequests() {
        generation++
        request?.cancel()
        pageRequest?.cancel()
        childRequest?.cancel()
        searchRequest?.cancel()
        paginating = false
        loading = false
    }

    private var paginating = false
    private var loadedNext = true
    private var loadedPrevious = true
    private var bookmark: BookmarkEntity? = null
    private var saveJob: Job? = null

    // A refresh of the same connection keeps the shown libraries until the reply replaces them.
    fun connect(c: Connection, refresh: Boolean = false) {
        val sameConnection = connection.id == c.id && connection.token == c.token
        if (!refresh && sameConnection && mutable.value.libraries.isNotEmpty()) return
        flush()
        cancelRequests()
        viewportKey = ""
        bookmark = null
        kept = null
        windowDirectory = 0
        val previousRecent = if (connection.id == c.id) mutable.value.recent else emptyList()
        val previousLibraries =
            if (connection.id == c.id && connection.token == c.token) mutable.value.libraries else emptyList()
        connection = c
        recentRequest?.cancel()
        recentRequest = viewModelScope.launch {
            bookmarks.recent(c.id).collectLatest { records ->
                val recent = withContext(Dispatchers.Default) {
                    records.map {
                        RecentFolder(
                            it,
                            timeLabels?.recent(it.visitedAt).orEmpty()
                        )
                    }
                }
                mutable.value = mutable.value.copy(recent = recent)
            }
        }
        request = viewModelScope.launch {
            mutable.value =
                LibraryUiState(busy = true, recent = previousRecent, libraries = previousLibraries)
            try {
                val libs = api.libraries(c)
                mutable.value = mutable.value.copy(libraries = libs, busy = false)
                if (c.lastDirectory != 0L) open(c.lastDirectory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(
                    busy = false,
                    error = true,
                    errorStatus = (e as? dev.vvideo.data.library.remote.ServiceFailure)?.status ?: 0
                )
            }
        }
    }

    fun resume() {
        val s = mutable.value
        if (blocked) return
        val b = bookmark
        mutable.value = s.copy(
            restoreKey = viewportKey,
            restoreAnchor = b?.anchor.orEmpty(),
            restoreOffset = if (viewportKey.isNotEmpty()) viewportOffset else b?.pixelOffset ?: 0,
            restoreWidth = if (viewportKey.isNotEmpty()) viewportWidth else b?.width ?: 0,
            restoreSerial = s.restoreSerial + 1
        )
    }

    // The notice belongs to the load that missed the anchor; resuming the page or
    // coming back from a viewer does not repeat it.
    fun anchorNoticeShown() {
        if (mutable.value.anchorMissing) mutable.value = mutable.value.copy(anchorMissing = false)
    }

    fun viewport(key: String, offset: Int, width: Int) {
        if (blocked) return
        viewportKey = key
        viewportOffset = offset
        viewportWidth = width
        val index = mutable.value.albums.indexOfFirst { albumKey(it) == key }
        if (index >= 0) save(index, offset, width)
    }

    fun back() {
        val d = mutable.value.directory ?: return
        if (d.parentId != 0L) open(d.parentId) else roots()
    }

    /** Searches the open folder for [query] after a short pause; a blank query ends the search. */
    fun search(query: String) {
        val directory = mutable.value.directory ?: return
        searchRequest?.cancel()
        if (query.isBlank()) {
            mutable.value = mutable.value.copy(search = SearchState(query))
            return
        }
        mutable.value = mutable.value.copy(search = mutable.value.search.copy(query = query, busy = true, error = false))
        val c = connection
        searchRequest = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val outcome = try {
                SearchState(query, api.search(c, directory.id, query.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value.search.copy(busy = false, error = true)
            }
            if (mutable.value.directory?.id == directory.id && mutable.value.search.query == query) {
                mutable.value = mutable.value.copy(search = outcome)
            }
        }
    }

    fun roots() {
        flush()
        cancelRequests()
        viewportKey = ""
        bookmark = null
        kept = null
        windowDirectory = 0
        mutable.value =
            LibraryUiState(libraries = mutable.value.libraries, recent = mutable.value.recent)
        viewModelScope.launch { settings.recent(0) }
    }

    fun open(
        id: Long,
        override: Sort? = null,
        refresh: Boolean = false,
        kind: String = "",
        fromStart: Boolean = false
    ) {
        val leftKey = viewportKey
        val leftOffset = viewportOffset
        val leftWidth = viewportWidth
        flush()
        cancelRequests()
        viewportKey = ""
        // Opening any folder, including one found by a search, ends the search.
        if (mutable.value.search != SearchState()) mutable.value = mutable.value.copy(search = SearchState())
        kept?.let { page ->
            if (page.state.directory?.id == id && override == null && !refresh && kind.isEmpty() && !fromStart) {
                restore(page)
                return
            }
        }
        loading = true
        val origin = mutable.value
        val leaving = origin.directory?.id != id
        if (!leaving) mutable.value = origin.copy(busy = true, error = false, kind = kind)
        request = viewModelScope.launch {
            // The displayed page stays intact while another directory loads; only a slow
            // load reveals the destination early, carrying its own first-load placeholder.
            // A destination that cannot be named yet marks the displayed page busy instead.
            val pending = if (leaving) pendingDirectory(origin, id) else null
            val reveal = if (leaving) launch {
                delay(REVEAL_DELAY_MS)
                if (pending == null) {
                    mutable.value = mutable.value.copy(busy = true)
                    return@launch
                }
                // Only a settled page that owns the windows can be returned to as it was.
                if (windowDirectory != 0L && origin.directory?.id == windowDirectory && !origin.busy) {
                    kept = KeptPage(origin, bookmark, leftKey, leftOffset, leftWidth)
                }
                // The placeholder was built from the state at the tap; lists refreshed since then stay.
                bookmark = null
                mutable.value =
                    mutable.value.let { pending.copy(recent = it.recent, libraries = it.libraries) }
            } else null
            val saved = if (refresh && bookmark?.directory == id) {
                bookmark
            } else {
                bookmarks.get(connection.id, id)
            }
            val restore = override == null && kind.isEmpty() && !fromStart
            val sort = override ?: saved?.let { Sort(it.sort, it.direction) } ?: Sort()
            try {
                var page: MediaPage? = null
                repeat(5) {
                    if (page == null) try {
                        page = api.page(
                            connection,
                            id,
                            sort,
                            anchor = if (restore) saved?.anchor.orEmpty() else "",
                            fallback = if (restore) saved?.fallback.orEmpty() else "",
                            kind = kind
                        )
                    } catch (e: RevisionChanged) {
                        delay(700)
                    }
                }
                var initial = page ?: throw RevisionChanged()
                if (restore && saved != null && !initial.anchorFound && saved.neighbor.isNotEmpty()) {
                    initial = api.page(
                        connection,
                        id,
                        sort,
                        anchor = saved.neighbor,
                        fallback = saved.fallback,
                        kind = kind
                    )
                }
                val children = api.children(connection, id)
                val old = origin.path
                val existing = old.indexOfFirst { it.id == id }
                var path = when {
                    existing >= 0 -> old.take(existing + 1)
                    old.lastOrNull()?.id == initial.directory.parentId -> old + initial.directory
                    else -> listOf(initial.directory)
                }
                if (path.size == 1 && initial.directory.parentId != 0L) {
                    try {
                        path = api.path(connection, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Browsing remains available if an older service has no directory endpoint.
                    }
                }
                val preparedAlbums = albums(sort, initial.items)
                // Nothing suspends from here to the published state: the windows, the
                // bookmark and the page change together, and a late reveal must not undo it.
                reveal?.cancel()
                window.reset(initial)
                childrenWindow.reset(children)
                windowDirectory = id
                kept = null
                loadedNext = true
                loadedPrevious = initial.previous.isNotEmpty()
                bookmark = if (override == null && saved != null) saved else BookmarkEntity(
                    connection.id,
                    id,
                    initial.directory.libraryId,
                    sort.field,
                    sort.direction
                )
                loading = false
                mutable.value = mutable.value.copy(
                    directory = initial.directory,
                    kind = kind,
                    children = children.items,
                    childNext = children.next,
                    childPrevious = children.previous,
                    childBusy = false,
                    pageBusy = false,
                    path = path,
                    pathLabel = pathLabel(path),
                    sort = sort,
                    hasMedia = initial.items.isNotEmpty() || (kind.isNotEmpty() && mutable.value.hasMedia),
                    hasPrevious = window.previous.isNotEmpty(),
                    albums = preparedAlbums,
                    busy = false,
                    error = false,
                    errorStatus = 0,
                    healthy = initial.healthy,
                    restoreKey = "",
                    restoreAnchor = if (restore) saved?.anchor.orEmpty() else "",
                    restoreOffset = if (restore) saved?.pixelOffset ?: 0 else 0,
                    restoreWidth = saved?.width ?: 0,
                    restoreSerial = mutable.value.restoreSerial + 1,
                    anchorMissing = restore && anchorMoved(saved?.anchor.orEmpty(), initial)
                )
                val record = checkNotNull(bookmark).copy(
                    name = initial.directory.name,
                    pathLabel = pathLabel(path),
                    visitedAt = System.currentTimeMillis(),
                    hiddenFromRecent = false
                )
                bookmark = record
                bookmarks.visit(record)
                if (initial.healthy) settings.recent(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reveal?.cancel()
                loading = false
                mutable.value = mutable.value.copy(
                    busy = false,
                    error = true,
                    errorStatus = (e as? dev.vvideo.data.library.remote.ServiceFailure)?.status ?: 0
                )
            }
        }
    }

    private fun restore(page: KeptPage) {
        kept = null
        bookmark = page.bookmark
        viewportKey = page.viewportKey
        viewportOffset = page.viewportOffset
        viewportWidth = page.viewportWidth
        mutable.value = restoredPage(page.state, mutable.value)
        resume()
    }

    private suspend fun albums(sort: Sort = mutable.value.sort, items: List<Media> = window.items) =
        withContext(Dispatchers.Default) {
            datedAlbums(
                items.chunked(10).map { Album(it) },
                sort,
                timeLabels
            )
        }

    fun filter(kind: String) {
        require(kind in setOf("", "video", "image"))
        val s = mutable.value
        s.directory?.let { open(it.id, s.sort, kind = kind, fromStart = true) }
    }

    fun jumpToStart() {
        val s = mutable.value
        s.directory?.let { open(it.id, s.sort, kind = s.kind, fromStart = true) }
    }

    // A published window is ready for paging even while open() persists its visit.
    // Blocking on the whole request Job can discard the only visible-edge event.
    fun page(previous: Boolean) {
        if (paginating || blocked || (!previous && !loadedNext) || (previous && !loadedPrevious)) return
        val d = mutable.value.directory ?: return
        val cursor = if (previous) window.previous else window.next
        if (cursor.isEmpty()) return
        paginating = true
        mutable.value = mutable.value.copy(pageBusy = true)
        val round = generation
        val sort = mutable.value.sort
        pageRequest = viewModelScope.launch {
            try {
                val page =
                    api.page(connection, d.id, sort, cursor, previous, kind = mutable.value.kind)
                ensureActive()
                if (round != generation) return@launch
                if (page.items.isEmpty()) {
                    if (previous) loadedPrevious = false else loadedNext = false
                } else {
                    if (previous) {
                        window.prepend(page)
                        loadedNext = true
                    } else {
                        window.append(page)
                        loadedPrevious = true
                    }
                    val preparedAlbums = albums()
                    mutable.value = mutable.value.copy(
                        albums = preparedAlbums,
                        hasPrevious = window.previous.isNotEmpty(),
                        error = false,
                        errorStatus = 0
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RevisionChanged) {
                open(d.id, refresh = true, kind = mutable.value.kind)
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(
                    error = true,
                    errorStatus = (e as? dev.vvideo.data.library.remote.ServiceFailure)?.status ?: 0
                )
            } finally {
                if (round == generation) {
                    paginating = false
                    mutable.value = mutable.value.copy(pageBusy = false)
                }
            }
        }
    }

    fun moreChildren(previous: Boolean = false) {
        val s = mutable.value
        val d = s.directory ?: return
        val cursor = if (previous) s.childPrevious else s.childNext
        if (cursor.isEmpty() || childRequest?.isActive == true || blocked) return
        val round = generation
        childRequest = viewModelScope.launch {
            mutable.value = mutable.value.copy(childBusy = true)
            try {
                val p = api.children(connection, d.id, cursor, previous)
                ensureActive()
                if (round == generation) {
                    if (p.items.isNotEmpty()) childrenWindow.add(p, previous)
                    mutable.value = mutable.value.copy(
                        children = childrenWindow.items,
                        childNext = if (!previous && p.items.isEmpty()) "" else childrenWindow.next,
                        childPrevious = if (previous && p.items.isEmpty()) "" else childrenWindow.previous,
                        error = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (round == generation) mutable.value = mutable.value.copy(
                    error = true,
                    errorStatus = (e as? dev.vvideo.data.library.remote.ServiceFailure)?.status ?: 0
                )
            } finally {
                if (round == generation) mutable.value = mutable.value.copy(childBusy = false)
            }
        }
    }

    fun save(album: Int, offset: Int, width: Int) {
        val s = mutable.value
        if (blocked || s.error || !s.healthy || s.kind.isNotEmpty()) return
        val group = s.albums.getOrNull(album) ?: return
        val item = group.items.firstOrNull() ?: return
        val base = bookmark ?: return
        val key = when (s.sort.field) {
            "name" -> item.name
            "mtime" -> item.mtime.toString()
            else -> item.effectiveTime.toString()
        }
        val fallback = Base64.encodeToString(
            JSONObject().put("directory_id", item.directoryId)
                .put("revision", s.directory?.revision ?: 0).put("sort", s.sort.field)
                .put("direction", s.sort.direction).put("kind", "").put("key", key)
                .put("id", item.id).toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val record = base.copy(
            anchor = item.id,
            neighbor = group.items.getOrNull(1)?.id.orEmpty(),
            fallback = fallback,
            pixelOffset = offset,
            width = width,
            revision = s.directory?.revision ?: 0,
            savedAt = System.currentTimeMillis()
        )
        bookmark = record
        if (saveJob?.isActive != true) saveJob = persistScope.launch {
            delay(500)
            bookmark?.let { bookmarks.put(it) }
        }
    }

    fun flush() {
        if (mutable.value.kind.isNotEmpty()) return
        val record = bookmark ?: return
        saveJob?.cancel()
        persistScope.launch { bookmarks.put(record) }
    }

    fun sort(sort: Sort) {
        val d = mutable.value.directory ?: return
        if (mutable.value.kind.isNotEmpty()) {
            open(d.id, sort, kind = mutable.value.kind, fromStart = true)
            return
        }
        bookmark = BookmarkEntity(connection.id, d.id, d.libraryId, sort.field, sort.direction)
        val cleared = checkNotNull(bookmark)
        persistScope.launch { bookmarks.put(cleared) }
        open(d.id, sort, kind = mutable.value.kind)
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }
}
