package dev.vvideo.core.media.cache

import android.content.Context
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dev.vvideo.core.media.model.PreloadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

// The same free-space guard the app keeps when it sizes the caches: 5% of the
// disk (one twentieth), at most 1 GB.
private const val FREE_SPACE_GUARD_DIVISOR = 20
private const val MAX_FREE_SPACE_GUARD = 1_000_000_000L

@OptIn(UnstableApi::class)
class VideoCache private constructor(
    private val directory: File,
    private val provider: StandaloneDatabaseProvider,
    internal val cache: SimpleCache,
    private val io: CoroutineDispatcher,
) {
    companion object {
        /** Opens the cache on [io], which then runs its disk work. */
        suspend fun open(context: Context, budget: Long, io: CoroutineDispatcher): VideoCache {
            var created: VideoCache? = null
            try {
                return withContext(io) {
                    val directory = File(context.cacheDir, "video")
                    val provider = StandaloneDatabaseProvider(context)
                    var cache: SimpleCache? = null
                    try {
                        cache = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(budget), provider)
                        cache.checkInitialization()
                        VideoCache(directory, provider, cache, io).also { created = it }
                    } catch (e: Exception) {
                        try {
                            cache?.release()
                        } finally {
                            provider.close()
                        }
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                // Cancellation can win the dispatcher hand-off after initialization.
                withContext(NonCancellable + io) { created?.close(clear = false) }
                throw e
            }
        }
    }

    suspend fun bytes(): Long = withContext(io) { cache.cacheSpace }
    suspend fun bytesFor(key: String): Long = withContext(io) { cache.getCachedSpans(key).sumOf { it.length } }

    suspend fun close(clear: Boolean) = withContext(io) {
        try {
            cache.release()
            if (clear) SimpleCache.delete(directory, provider)
        } finally {
            provider.close()
        }
    }

    /** Whether a full preload still leaves the system its share of free space. */
    fun hasPreloadSpace(): Boolean {
        val stat = StatFs(directory.absolutePath)
        val guard = minOf(stat.totalBytes / FREE_SPACE_GUARD_DIVISOR, MAX_FREE_SPACE_GUARD)
        return stat.availableBytes > guard + PreloadBudget.MAX_BYTES
    }
}
