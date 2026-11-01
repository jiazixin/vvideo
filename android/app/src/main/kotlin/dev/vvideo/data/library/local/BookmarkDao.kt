package dev.vvideo.data.library.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val RECENT_FOLDER_LIMIT = 30

@Dao
internal interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE connection=:connection AND directory=:directory")
    suspend fun get(connection: String, directory: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query(
        "UPDATE bookmarks SET library=:library,sort=:sort,direction=:direction,anchor=:anchor," +
            "neighbor=:neighbor,fallback=:fallback,pixelOffset=:pixelOffset,width=:width," +
            "revision=:revision,savedAt=:savedAt,formatVersion=:formatVersion " +
            "WHERE connection=:connection AND directory=:directory",
    )
    suspend fun updateAnchor(
        connection: String,
        directory: Long,
        library: String,
        sort: String,
        direction: String,
        anchor: String,
        neighbor: String,
        fallback: String,
        pixelOffset: Int,
        width: Int,
        revision: Long,
        savedAt: Long,
        formatVersion: Int,
    )

    /** Scrolling must not resurrect a hidden entry or overwrite a newer visit. */
    @Transaction
    suspend fun put(bookmark: BookmarkEntity) {
        insert(bookmark)
        updateAnchor(
            bookmark.connection,
            bookmark.directory,
            bookmark.library,
            bookmark.sort,
            bookmark.direction,
            bookmark.anchor,
            bookmark.neighbor,
            bookmark.fallback,
            bookmark.pixelOffset,
            bookmark.width,
            bookmark.revision,
            bookmark.savedAt,
            bookmark.formatVersion,
        )
    }

    @Query(
        "UPDATE bookmarks SET name=:name,pathLabel=:pathLabel,visitedAt=:visitedAt,hiddenFromRecent=0 " +
            "WHERE connection=:connection AND directory=:directory",
    )
    suspend fun updateVisit(connection: String, directory: Long, name: String, pathLabel: String, visitedAt: Long)

    @Transaction
    suspend fun visit(bookmark: BookmarkEntity) {
        insert(bookmark)
        updateVisit(
            bookmark.connection,
            bookmark.directory,
            bookmark.name,
            bookmark.pathLabel,
            bookmark.visitedAt,
        )
    }

    @Query(
        "SELECT * FROM bookmarks WHERE connection=:connection AND hiddenFromRecent=0 AND name!='' " +
            "ORDER BY visitedAt DESC LIMIT $RECENT_FOLDER_LIMIT",
    )
    fun recent(connection: String): Flow<List<BookmarkEntity>>

    @Query("UPDATE bookmarks SET hiddenFromRecent=1 WHERE connection=:connection AND directory=:directory")
    suspend fun hide(connection: String, directory: Long)
}
