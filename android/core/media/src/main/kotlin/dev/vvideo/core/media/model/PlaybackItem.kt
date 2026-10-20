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

object PreloadBudget {
    const val MAX_BYTES = 8L * 1024 * 1024
    fun openingBytes(size: Long, duration: Long): Long = minOf(
        4L * 1024 * 1024,
        size.coerceAtLeast(0),
        if (duration > 0) (size.toDouble() * 3000 / duration).toLong()
            .coerceAtLeast(256 * 1024) else 1024 * 1024
    )

    fun allowed(range: ByteRange, size: Long): Boolean =
        range.offset >= 0 && range.length > 0 && range.length <= MAX_BYTES && range.offset <= size - range.length
}
