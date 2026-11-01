package dev.vvideo.data.cache

import android.content.Context
import android.os.StatFs
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.playback.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

internal data class CacheUsage(
    val video: Long = 0,
    val images: Long = 0,
    val overhead: Long = 0,
    val lowSpace: Boolean = false,
) {
    val total: Long get() = video + images + overhead
}

/**
 * Owns the video byte cache and the image loader, which share one disk budget
 * (nine tenths for video, one tenth for images). Reconfiguring or clearing
 * releases every playback session first, so no player reads a closed cache.
 */
internal class CacheCoordinator(
    private val context: Context,
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
) : CacheSettings {
    private val mutex = Mutex()
    private var video: VideoCache? = null
    private val sessions = mutableSetOf<PlaybackSession>()
    private val mutableLoader = MutableStateFlow<ImageLoader?>(null)
    val loader = mutableLoader.asStateFlow()
    private val _failure = MutableStateFlow(false)
    val failure = _failure.asStateFlow()

    override suspend fun configure(budget: Long, clear: Boolean) = mutex.withLock {
        _failure.value = false
        try {
            withContext(NonCancellable + mainDispatcher) {
                sessions.toList().forEach { it.release() }
                sessions.clear()
            }
            withContext(ioDispatcher) {
                val existingBytes = (video?.bytes() ?: 0) + (mutableLoader.value?.diskCache?.size ?: 0)
                val oldLoader = mutableLoader.value
                val oldVideo = video
                mutableLoader.value = null
                video = null
                // Ownership has been detached. Finish closing both caches even if
                // cancellation or a disk error interrupts one of the cleanup steps.
                withContext(NonCancellable) { closeCaches(oldLoader, oldVideo, clear) }
                val disk = StatFs(context.cacheDir.absolutePath)
                val reclaimable = (disk.availableBytes + if (clear) 0 else existingBytes)
                    .coerceAtLeast(MIN_CACHE_BYTES)
                val guard = minOf(disk.totalBytes / FREE_SPACE_GUARD_DIVISOR, MAX_FREE_SPACE_GUARD)
                val usable = minOf(budget, (reclaimable - guard).coerceAtLeast(MIN_CACHE_BYTES))
                val openedVideo = VideoCache.open(context, usable * VIDEO_TENTHS / 10, ioDispatcher)
                try {
                    val imageDir = File(context.cacheDir, "images")
                    val openedLoader = ImageLoader.Builder(context)
                        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                        .memoryCache { MemoryCache.Builder().maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(imageDir.toOkioPath())
                                .maxSizeBytes(usable * IMAGE_TENTHS / 10)
                                .build()
                        }
                        .build()
                    video = openedVideo
                    mutableLoader.value = openedLoader
                } catch (e: Exception) {
                    withContext(NonCancellable + ioDispatcher) { openedVideo.close(clear = false) }
                    throw e
                }
            }
        } finally {
            _failure.value = mutableLoader.value == null
        }
    }

    private suspend fun closeCaches(loader: ImageLoader?, cache: VideoCache?, clear: Boolean) {
        try {
            loader?.shutdown()
        } finally {
            try {
                if (clear) loader?.diskCache?.clear()
            } finally {
                try {
                    loader?.diskCache?.shutdown()
                } finally {
                    cache?.close(clear)
                }
            }
        }
    }

    /** A new player; the previous one is released first. Requests are authorized by [client]. */
    suspend fun session(): PlaybackSession = mutex.withLock {
        var created: PlaybackSession? = null
        try {
            withContext(mainDispatcher) {
                val previous = sessions.toList()
                sessions.clear()
                withContext(NonCancellable) { previous.forEach { it.release() } }
                ensureActive()
                PlaybackSession(context, checkNotNull(video), client, ioDispatcher).also {
                    created = it
                    sessions += it
                }
            }
        } catch (e: CancellationException) {
            // A dispatcher can cancel the return after construction succeeds. The
            // route never receives ownership in that case, so the coordinator releases
            // the pending player before unlocking and allowing another session.
            withContext(NonCancellable + mainDispatcher) {
                created?.let { if (sessions.remove(it)) it.release() }
            }
            throw e
        }
    }

    suspend fun release(session: PlaybackSession) = mutex.withLock {
        withContext(mainDispatcher) { if (sessions.remove(session)) session.release() }
    }

    suspend fun cachedVideoBytes(key: String): Long = mutex.withLock { video?.bytesFor(key) ?: 0 }

    override suspend fun usage(): CacheUsage = mutex.withLock {
        withContext(ioDispatcher) {
            val overhead = context.databaseList().filter { it.contains("exoplayer") }
                .sumOf { context.getDatabasePath(it).length() }
            CacheUsage(
                video?.bytes() ?: 0,
                mutableLoader.value?.diskCache?.size ?: 0,
                overhead,
                video?.hasPreloadSpace() == false,
            )
        }
    }
}
