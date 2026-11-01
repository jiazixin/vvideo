package dev.vvideo.data.library

import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Bookmark
import dev.vvideo.model.ChildrenPage
import dev.vvideo.model.Directory
import dev.vvideo.model.Library
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaPage
import dev.vvideo.model.SearchResult
import dev.vvideo.model.Sort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal open class FakeLibraryData : LibraryData {
    var fetchLibraries: suspend (Connection) -> List<Library> = { emptyList() }
    var fetchPage: suspend (String) -> MediaPage = { error("page not configured") }
    var fetchMedia: suspend (String) -> Media = { error("media not configured") }
    val saved = mutableListOf<Bookmark>()

    override suspend fun libraries(connection: Connection) = fetchLibraries(connection)
    override suspend fun path(connection: Connection, directoryId: Long) = emptyList<Directory>()
    override suspend fun children(connection: Connection, directoryId: Long, cursor: String, edge: PageEdge) =
        ChildrenPage(emptyList(), "")
    override suspend fun page(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        cursor: String,
        edge: PageEdge,
        kind: MediaKind?,
    ) = fetchPage(cursor)
    override suspend fun pageAround(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        mediaId: String,
        kind: MediaKind,
    ) = fetchPage(mediaId)
    override suspend fun firstPage(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        saved: Bookmark?,
        kind: MediaKind?,
    ) = fetchPage(saved?.anchor.orEmpty())
    override suspend fun search(connection: Connection, directoryId: Long, query: String) =
        SearchResult(emptyList(), emptyList(), false)
    override suspend fun media(connection: Connection, id: String) = fetchMedia(id)
    override suspend fun bookmark(connectionId: String, directoryId: Long): Bookmark? = null
    override fun recentFolders(connectionId: String): Flow<List<Bookmark>> = flowOf(emptyList())
    override suspend fun saveBookmark(bookmark: Bookmark) {
        saved += bookmark
    }
    override suspend fun recordVisit(bookmark: Bookmark) {
        saved += bookmark
    }
    override suspend fun setLastDirectory(directoryId: Long) = Unit
}
