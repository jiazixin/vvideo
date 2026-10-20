package dev.vvideo.feature.library

/** Folders whose last position is kept; older ones fall back to their bookmark. */
private const val MAX_REMEMBERED_FOLDERS = 64

/** Where a folder's list stood: the first visible item's key, its scroll offset and the list width. */
data class FolderPosition(val key: String, val offset: Int, val width: Int)

/**
 * The position each folder was left at in this session, so going back to a parent
 * shows the subfolder that was opened from it. A subfolder list has no media anchor
 * and so no bookmark position; album folders keep their bookmark as the fallback.
 */
class FolderPositions {
    private val positions = object : LinkedHashMap<Long, FolderPosition>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FolderPosition>?) =
            size > MAX_REMEMBERED_FOLDERS
    }

    /** Records [directory]'s position; a blank key means it was never shown and keeps the old one. */
    fun leave(directory: Long, position: FolderPosition) {
        if (directory != 0L && position.key.isNotEmpty()) positions[directory] = position
    }

    operator fun get(directory: Long): FolderPosition? = positions[directory]

    /** Drops [directory]'s position, for a new sort, filter or jump to the start. */
    fun forget(directory: Long) {
        positions.remove(directory)
    }

    fun clear() = positions.clear()
}
