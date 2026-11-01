package dev.vvideo.data.library.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import dev.vvideo.model.Bookmark

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["connection", "directory"],
    indices = [Index(value = ["connection", "hiddenFromRecent", "visitedAt"])],
)
internal data class BookmarkEntity(
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

internal fun BookmarkEntity.toModel() = Bookmark(
    connection = connection,
    directory = directory,
    library = library,
    sort = sort,
    direction = direction,
    anchor = anchor,
    neighbor = neighbor,
    fallback = fallback,
    pixelOffset = pixelOffset,
    width = width,
    revision = revision,
    savedAt = savedAt,
    name = name,
    pathLabel = pathLabel,
    visitedAt = visitedAt,
    hiddenFromRecent = hiddenFromRecent,
)

internal fun Bookmark.toEntity() = BookmarkEntity(
    connection = connection,
    directory = directory,
    library = library,
    sort = sort,
    direction = direction,
    anchor = anchor,
    neighbor = neighbor,
    fallback = fallback,
    pixelOffset = pixelOffset,
    width = width,
    revision = revision,
    savedAt = savedAt,
    name = name,
    pathLabel = pathLabel,
    visitedAt = visitedAt,
    hiddenFromRecent = hiddenFromRecent,
)
