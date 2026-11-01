package dev.vvideo.model

/**
 * A folder's saved browse position: the album at the top of the list, how far it
 * was scrolled, and the folder's entry in the recent list.
 */
internal data class Bookmark(
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
    val name: String = "",
    val pathLabel: String = "",
    val visitedAt: Long = 0,
    val hiddenFromRecent: Boolean = false,
)
