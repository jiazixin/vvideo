package dev.vvideo.data.library.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Folders listed as recently visited, per connection. */
private const val RECENT_FOLDER_LIMIT = 30

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["connection", "directory"],
    indices = [Index(value = ["connection", "hiddenFromRecent", "visitedAt"])]
)
data class BookmarkEntity(
    val connection: String,
    val directory: Long,
    val library: String,
    val sort: String = "capture",
    val direction: String = "asc",
    val anchor: String = "",
    val neighbor: String = "",
    val fallback: String = "",
    val pixelOffset: Int = 0,
    val width: Int = 0,
    val revision: Long = 0,
    val savedAt: Long = 0,
    val formatVersion: Int = 1,
    @ColumnInfo(defaultValue = "''") val name: String = "",
    @ColumnInfo(defaultValue = "''") val pathLabel: String = "",
    @ColumnInfo(defaultValue = "0") val visitedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val hiddenFromRecent: Boolean = false,
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE connection=:connection AND directory=:directory")
    suspend fun get(connection: String, directory: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query(
        "UPDATE bookmarks SET library=:library,sort=:sort,direction=:direction,anchor=:anchor," +
                "neighbor=:neighbor,fallback=:fallback,pixelOffset=:pixelOffset,width=:width," +
                "revision=:revision,savedAt=:savedAt,formatVersion=:formatVersion " +
                "WHERE connection=:connection AND directory=:directory"
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
        formatVersion: Int
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
            bookmark.formatVersion
        )
    }

    @Query(
        "UPDATE bookmarks SET name=:name,pathLabel=:pathLabel,visitedAt=:visitedAt,hiddenFromRecent=0 " +
                "WHERE connection=:connection AND directory=:directory"
    )
    suspend fun updateVisit(
        connection: String,
        directory: Long,
        name: String,
        pathLabel: String,
        visitedAt: Long
    )

    @Transaction
    suspend fun visit(bookmark: BookmarkEntity) {
        insert(bookmark)
        updateVisit(
            bookmark.connection,
            bookmark.directory,
            bookmark.name,
            bookmark.pathLabel,
            bookmark.visitedAt
        )
    }

    @Query(
        "SELECT * FROM bookmarks WHERE connection=:connection AND hiddenFromRecent=0 AND name!='' " +
                "ORDER BY visitedAt DESC LIMIT $RECENT_FOLDER_LIMIT"
    )
    fun recent(connection: String): Flow<List<BookmarkEntity>>

    @Query("UPDATE bookmarks SET hiddenFromRecent=1 WHERE connection=:connection AND directory=:directory")
    suspend fun hide(connection: String, directory: Long)
}

// No migrations before the first release: a database of any other version is
// dropped and recreated (see AppGraph); these are only local browse positions.
@Database(entities = [BookmarkEntity::class], version = 3, exportSchema = true)
abstract class BrowseDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
}
