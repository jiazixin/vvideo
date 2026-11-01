package dev.vvideo.data.library

import dev.vvideo.data.library.local.BookmarkDao
import dev.vvideo.data.library.local.toEntity
import dev.vvideo.data.library.local.toModel
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsData
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
import kotlinx.coroutines.flow.map

/** Folders, media and browse positions of the connected server. */
internal class LibraryRepository(
    private val api: LibraryApi,
    private val bookmarks: BookmarkDao,
    private val settings: SettingsData,
) : LibraryData {
    override suspend fun libraries(connection: Connection): List<Library> = api.libraries(connection)

    override suspend fun path(connection: Connection, directoryId: Long): List<Directory> =
        api.path(connection, directoryId)

    override suspend fun children(
        connection: Connection,
        directoryId: Long,
        cursor: String,
        edge: PageEdge,
    ): ChildrenPage = api.children(connection, directoryId, cursor, edge)

    override suspend fun page(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        cursor: String,
        edge: PageEdge,
        kind: MediaKind?,
    ): MediaPage = api.page(connection, directoryId, sort, cursor, edge, kind = kind)

    /** The page of [kind] media that holds [mediaId]. */
    override suspend fun pageAround(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        mediaId: String,
        kind: MediaKind,
    ): MediaPage = api.page(connection, directoryId, sort, anchor = mediaId, kind = kind)

    /**
     * The first page of a folder. A [saved] bookmark places it at the bookmark's
     * anchor; when that media is gone, at its neighbour, and otherwise where the
     * server's fallback cursor puts it.
     */
    override suspend fun firstPage(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        saved: Bookmark?,
        kind: MediaKind?,
    ): MediaPage {
        val first = api.page(
            connection,
            directoryId,
            sort,
            anchor = saved?.anchor.orEmpty(),
            fallback = saved?.fallback.orEmpty(),
            kind = kind,
        )
        if (saved == null || first.anchorFound || saved.neighbor.isEmpty()) return first
        return api.page(
            connection,
            directoryId,
            sort,
            anchor = saved.neighbor,
            fallback = saved.fallback,
            kind = kind,
        )
    }

    override suspend fun search(connection: Connection, directoryId: Long, query: String): SearchResult =
        api.search(connection, directoryId, query)

    override suspend fun media(connection: Connection, id: String): Media = api.media(connection, id)

    override suspend fun bookmark(connectionId: String, directoryId: Long): Bookmark? =
        bookmarks.get(connectionId, directoryId)?.toModel()

    /** Recently visited folders of a connection, newest first. */
    override fun recentFolders(connectionId: String): Flow<List<Bookmark>> =
        bookmarks.recent(connectionId).map { records -> records.map { it.toModel() } }

    /** Stores a scroll position; it never revives a folder hidden from the recent list. */
    override suspend fun saveBookmark(bookmark: Bookmark) = bookmarks.put(bookmark.toEntity())

    /** Records that a folder was opened, for the recent list and its placeholder. */
    override suspend fun recordVisit(bookmark: Bookmark) = bookmarks.visit(bookmark.toEntity())

    /** The folder a cold start reopens. */
    override suspend fun setLastDirectory(directoryId: Long) = settings.setLastDirectory(directoryId)
}
