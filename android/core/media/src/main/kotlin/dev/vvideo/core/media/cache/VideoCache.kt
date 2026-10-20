package dev.vvideo.core.media.cache

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class VideoCache private constructor(
    private val directory: File,
    private val provider: StandaloneDatabaseProvider,
    val cache: SimpleCache
) {
    companion object {
        suspend fun open(context: Context, budget: Long): VideoCache = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "video")
            val provider = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(budget), provider)
            cache.checkInitialization()
            VideoCache(directory, provider, cache)
        }
    }

    suspend fun bytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }
    suspend fun bytesFor(key: String): Long =
        withContext(Dispatchers.IO) { cache.getCachedSpans(key).sumOf { it.length } }

    suspend fun close(clear: Boolean) = withContext(Dispatchers.IO) {
        try {
            cache.release()
            if (clear) SimpleCache.delete(directory, provider)
        } finally {
            provider.close()
        }
    }

    fun hasPreloadSpace(): Boolean {
        val stat = StatFs(directory.absolutePath)
        return stat.availableBytes > minOf(stat.totalBytes / 20, 1_000_000_000L) + 8 * 1024 * 1024
    }
}
