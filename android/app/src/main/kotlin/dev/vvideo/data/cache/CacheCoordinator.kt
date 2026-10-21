package dev.vvideo.data.cache

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.playback.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

/** Below this the caches are still created, however little space is free. */
private const val MIN_CACHE_BYTES = 64L * 1024 * 1024

/** Free space left to the system: 5% of the disk (one twentieth), at most this much. */
private const val MAX_FREE_SPACE_GUARD = 1_000_000_000L
private const val FREE_SPACE_GUARD_DIVISOR = 20

/** Tenths of the budget for video bytes and for images. */
private const val VIDEO_TENTHS = 9
private const val IMAGE_TENTHS = 1

/** In-memory decoded image cache, independent of the disk budget. */
private const val IMAGE_MEMORY_BYTES = 32L * 1024 * 1024

data class CacheUsage(
    val video: Long = 0,
    val images: Long = 0,
    val overhead: Long = 0,
    val lowSpace: Boolean = false
) {
    val total: Long get() = video + images + overhead
}

/**
 * Owns the video byte cache and the image loader, which share one disk budget
 * (nine tenths for video, one tenth for images). Reconfiguring or clearing
 * releases every playback session first, so no player reads a closed cache.
 */
@UnstableApi
class CacheCoordinator(private val context: Context) {
    private val mutex = Mutex()
    private var video: VideoCache? = null
    private val sessions = mutableSetOf<PlaybackSession>()
    private val mutableLoader = MutableStateFlow<ImageLoader?>(null)
    val loader = mutableLoader.asStateFlow()

    suspend fun configure(budget: Long, clear: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            sessions.toList().forEach { it.release() }
            sessions.clear()
        }
        withContext(Dispatchers.IO) {
            val existingBytes = (video?.bytes() ?: 0) + (mutableLoader.value?.diskCache?.size ?: 0)
            mutableLoader.value?.let { old ->
                old.shutdown()
                if (clear) old.diskCache?.clear()
                old.diskCache?.shutdown()
            }
            video?.close(clear)
            val disk = StatFs(context.cacheDir.absolutePath)
            val reclaimable = (disk.availableBytes + if (clear) 0 else existingBytes)
                .coerceAtLeast(MIN_CACHE_BYTES)
            val guard = minOf(disk.totalBytes / FREE_SPACE_GUARD_DIVISOR, MAX_FREE_SPACE_GUARD)
            val usable = minOf(budget, (reclaimable - guard).coerceAtLeast(MIN_CACHE_BYTES))
            video = VideoCache.open(context, usable * VIDEO_TENTHS / 10)
            val imageDir = File(context.cacheDir, "images")
            mutableLoader.value = ImageLoader.Builder(context)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(imageDir.toOkioPath())
                        .maxSizeBytes(usable * IMAGE_TENTHS / 10)
                        .build()
                }
                .build()
        }
    }

    suspend fun session(token: String): PlaybackSession = mutex.withLock {
        var created: PlaybackSession? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val previous = sessions.toList()
                sessions.clear()
                withContext(NonCancellable) { previous.forEach { it.release() } }
                ensureActive()
                PlaybackSession(context, checkNotNull(video), token).also {
                    created = it
                    sessions += it
                }
            }
        } catch (e: CancellationException) {
            // A dispatcher can cancel the return after construction succeeds. The
            // route never receives ownership in that case, so the coordinator releases
            // the pending player before unlocking and allowing another session.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                created?.let { if (sessions.remove(it)) it.release() }
            }
            throw e
        }
    }

    suspend fun release(session: PlaybackSession) = mutex.withLock {
        withContext(Dispatchers.Main.immediate) { if (sessions.remove(session)) session.release() }
    }

    suspend fun cachedVideoBytes(key: String): Long = mutex.withLock { video?.bytesFor(key) ?: 0 }

    suspend fun usage(): CacheUsage = mutex.withLock {
        withContext(Dispatchers.IO) {
            val overhead = context.databaseList().filter { it.contains("exoplayer") }
                .sumOf { context.getDatabasePath(it).length() }
            CacheUsage(
                video?.bytes() ?: 0,
                mutableLoader.value?.diskCache?.size ?: 0,
                overhead,
                video?.hasPreloadSpace() == false
            )
        }
    }
}
