package dev.vvideo.model

/** Display ratios outside this range are clamped so one extreme item cannot dominate an album. */
private const val MIN_DISPLAY_RATIO = 0.3f
private const val MAX_DISPLAY_RATIO = 3.3f

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
    val kind: MediaKind,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val size: Long,
    val effectiveTime: Long,
    val mtime: Long,
    val status: MediaStatus,
    val capturedRaw: String,
    val capturedSource: CaptureSource
) {
    val displayRatio: Float
        get() {
            val ratio = if (rotation % 180 != 0) {
                height.toFloat() / width.coerceAtLeast(1)
            } else {
                width.toFloat() / height.coerceAtLeast(1)
            }
            return ratio.coerceIn(MIN_DISPLAY_RATIO, MAX_DISPLAY_RATIO)
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

/** Folders and media under a folder whose names match a search; [truncated] means more matched. */
data class SearchResult(
    val directories: List<Directory>,
    val items: List<Media>,
    val truncated: Boolean
)
