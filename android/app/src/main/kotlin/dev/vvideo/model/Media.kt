package dev.vvideo.model

data class Directory(
    val id: Long,
    val libraryId: String,
    val parentId: Long,
    val name: String,
    val revision: Long
)

data class Library(val id: String, val name: String, val directoryId: Long, val healthy: Boolean)

data class Media(
    val id: String,
    val version: String,
    val libraryId: String,
    val directoryId: Long,
    val name: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val size: Long,
    val effectiveTime: Long,
    val mtime: Long,
    val status: String,
    val capturedRaw: String,
    val capturedSource: String
) {
    val displayRatio: Float
        get() {
            val r = if (rotation % 180 != 0) {
                height.toFloat() / width.coerceAtLeast(1)
            } else {
                width.toFloat() / height.coerceAtLeast(1)
            }
            return r.coerceIn(0.3f, 3.3f)
        }
}

data class MediaPage(
    val directory: Directory,
    val items: List<Media>,
    val next: String,
    val previous: String,
    val anchor: String,
    val anchorFound: Boolean,
    val healthy: Boolean
)

data class ChildrenPage(val items: List<Directory>, val next: String, val previous: String = "")

data class Sort(val field: String = "capture", val direction: String = "asc")
