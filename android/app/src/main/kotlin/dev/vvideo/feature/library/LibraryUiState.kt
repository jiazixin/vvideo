package dev.vvideo.feature.library

import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.model.Directory
import dev.vvideo.model.Library
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.SearchResult
import dev.vvideo.model.Sort

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

/** The open folder's subfolders, paged by [next] and [previous] cursors. */
data class Subfolders(
    val items: List<Directory> = emptyList(),
    val next: String = "",
    val previous: String = "",
    val busy: Boolean = false
)

/**
 * Where the list scrolls whenever [serial] changes: the row with [key] at [offset]
 * if the list is still [width] wide, else the album holding [anchor].
 */
data class ScrollRestore(
    val key: String = "",
    val anchor: String = "",
    val offset: Int = 0,
    val width: Int = 0,
    val serial: Long = 0
)

data class LibraryUiState(
    val recent: List<RecentFolder> = emptyList(),
    /** The media kind the folder is filtered to; null shows every kind. */
    val filter: MediaKind? = null,
    val hasMedia: Boolean = false,
    val hasPrevious: Boolean = false,
    val pageBusy: Boolean = false,
    val libraries: List<Library> = emptyList(),
    val directory: Directory? = null,
    val subfolders: Subfolders = Subfolders(),
    val path: List<Directory> = emptyList(),
    val pathLabel: String = "",
    /** HTTP status of the last failure, or 0 when the server was not reached. */
    val errorStatus: Int = 0,
    val albums: List<Album> = emptyList(),
    val sort: Sort = Sort(),
    val busy: Boolean = false,
    val error: Boolean = false,
    val healthy: Boolean = true,
    val restore: ScrollRestore = ScrollRestore(),
    val anchorMissing: Boolean = false,
    val search: SearchState = SearchState()
)
