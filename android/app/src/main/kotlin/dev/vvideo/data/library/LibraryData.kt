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

/** Data operations consumed by page state holders, independently replaceable in tests. */
internal interface LibraryData {
    suspend fun libraries(connection: Connection): List<Library>
    suspend fun path(connection: Connection, directoryId: Long): List<Directory>
    suspend fun children(
        connection: Connection,
        directoryId: Long,
        cursor: String = "",
        edge: PageEdge = PageEdge.Next,
    ): ChildrenPage
    suspend fun page(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        cursor: String,
        edge: PageEdge,
        kind: MediaKind?,
    ): MediaPage
    suspend fun pageAround(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        mediaId: String,
        kind: MediaKind,
    ): MediaPage
    suspend fun firstPage(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        saved: Bookmark?,
        kind: MediaKind?,
    ): MediaPage
    suspend fun search(connection: Connection, directoryId: Long, query: String): SearchResult
    suspend fun media(connection: Connection, id: String): Media
    suspend fun bookmark(connectionId: String, directoryId: Long): Bookmark?
    fun recentFolders(connectionId: String): Flow<List<Bookmark>>
    suspend fun saveBookmark(bookmark: Bookmark)
    suspend fun recordVisit(bookmark: Bookmark)
    suspend fun setLastDirectory(directoryId: Long)
}
