package dev.vvideo.core.media.model

data class PlaybackItem(
    val server: String,
    val library: String,
    val id: String,
    val version: String,
    val url: String,
    val indexUrl: String,
    val token: String,
    val size: Long,
    val durationMs: Long
) {
    val cacheKey: String get() = "$server|$library|$id|$version"
}

data class ByteRange(val offset: Long, val length: Long)

/** How much of the next video is fetched ahead of time. */
object PreloadBudget {
    /** All preloaded ranges of one video together. */
    const val MAX_BYTES = 8L * 1024 * 1024

    private const val MAX_OPENING_BYTES = 4L * 1024 * 1024
    private const val MIN_OPENING_BYTES = 256L * 1024

    /** The opening covers about this much playback at the video's average bitrate. */
    private const val OPENING_PLAYBACK_MS = 3000

    /** Opening bytes of a video whose duration is unknown. */
    private const val UNKNOWN_DURATION_OPENING_BYTES = 1024L * 1024

    /** Bytes from the start of a video of [size] bytes and [duration] ms to preload. */
    fun openingBytes(size: Long, duration: Long): Long = minOf(
        MAX_OPENING_BYTES,
        size.coerceAtLeast(0),
        if (duration > 0) {
            (size.toDouble() * OPENING_PLAYBACK_MS / duration).toLong()
                .coerceAtLeast(MIN_OPENING_BYTES)
        } else {
            UNKNOWN_DURATION_OPENING_BYTES
        }
    )

    /** Whether [range] lies inside a file of [size] bytes and fits the budget on its own. */
    fun allowed(range: ByteRange, size: Long): Boolean =
        range.offset >= 0 && range.length > 0 && range.length <= MAX_BYTES && range.offset <= size - range.length
}
