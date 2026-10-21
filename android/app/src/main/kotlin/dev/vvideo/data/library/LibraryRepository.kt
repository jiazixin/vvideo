package dev.vvideo.data.library

import dev.vvideo.data.library.local.BookmarkDao
import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.model.ChildrenPage
import dev.vvideo.model.Directory
import dev.vvideo.model.Library
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaPage
import dev.vvideo.model.SearchResult
import dev.vvideo.model.Sort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** A folder being rescanned answers with a revision conflict; its first page is asked for this often. */
private const val FIRST_PAGE_ATTEMPTS = 5
private const val REVISION_RETRY_DELAY_MS = 700L

/** Folders, media and browse positions of the connected server. */
class LibraryRepository(
    private val api: LibraryApi,
    private val bookmarks: BookmarkDao,
    private val settings: SettingsRepository
) {
    suspend fun libraries(connection: Connection): List<Library> = api.libraries(connection)

    suspend fun path(connection: Connection, directoryId: Long): List<Directory> =
        api.path(connection, directoryId)

    suspend fun children(
        connection: Connection,
        directoryId: Long,
        cursor: String = "",
        edge: PageEdge = PageEdge.Next
    ): ChildrenPage = api.children(connection, directoryId, cursor, edge)

    suspend fun page(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        cursor: String,
        edge: PageEdge,
        kind: MediaKind?
    ): MediaPage = api.page(connection, directoryId, sort, cursor, edge, kind = kind)

    /** The page of [kind] media that holds [mediaId]. */
    suspend fun pageAround(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        mediaId: String,
        kind: MediaKind
    ): MediaPage = api.page(connection, directoryId, sort, anchor = mediaId, kind = kind)

    /**
     * The first page of a folder. A [saved] bookmark places it at the bookmark's
     * anchor; when that media is gone, at its neighbour, and otherwise where the
     * server's fallback cursor puts it.
     */
    suspend fun firstPage(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        saved: BookmarkEntity?,
        kind: MediaKind?
    ): MediaPage {
        var page: MediaPage? = null
        repeat(FIRST_PAGE_ATTEMPTS) {
            if (page == null) try {
                page = api.page(
                    connection,
                    directoryId,
                    sort,
                    anchor = saved?.anchor.orEmpty(),
                    fallback = saved?.fallback.orEmpty(),
                    kind = kind
                )
            } catch (_: RevisionChanged) {
                delay(REVISION_RETRY_DELAY_MS)
            }
        }
        val first = page ?: throw RevisionChanged()
        if (saved == null || first.anchorFound || saved.neighbor.isEmpty()) return first
        return api.page(
            connection,
            directoryId,
            sort,
            anchor = saved.neighbor,
            fallback = saved.fallback,
            kind = kind
        )
    }

    suspend fun search(connection: Connection, directoryId: Long, query: String): SearchResult =
        api.search(connection, directoryId, query)

    suspend fun media(connection: Connection, id: String): Media = api.media(connection, id)

    suspend fun bookmark(connectionId: String, directoryId: Long): BookmarkEntity? =
        bookmarks.get(connectionId, directoryId)

    /** Recently visited folders of a connection, newest first. */
    fun recentFolders(connectionId: String): Flow<List<BookmarkEntity>> =
        bookmarks.recent(connectionId)

    /** Stores a scroll position; it never revives a folder hidden from the recent list. */
    suspend fun saveBookmark(bookmark: BookmarkEntity) = bookmarks.put(bookmark)

    /** Records that a folder was opened, for the recent list and its placeholder. */
    suspend fun recordVisit(bookmark: BookmarkEntity) = bookmarks.visit(bookmark)

    /** The folder a cold start reopens. */
    suspend fun setLastDirectory(directoryId: Long) = settings.setLastDirectory(directoryId)
}
