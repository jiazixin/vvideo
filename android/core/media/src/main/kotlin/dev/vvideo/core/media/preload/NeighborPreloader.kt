package dev.vvideo.core.media.preload

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.model.ByteRange
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.model.PreloadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Preloading gives up sooner than playback, which keeps the media client's timeouts. */
private const val PRELOAD_TIMEOUT_SECONDS = 3L
private const val INDEX_READ_STEP_BYTES = 4096L

/** A preload index larger than this is not read. */
private const val MAX_INDEX_BYTES = 64L * 1024

/** At most this many hinted ranges of one video are preloaded. */
private const val MAX_HINT_RANGES = 4
private const val CACHE_WRITE_BUFFER_BYTES = 64 * 1024
private const val HTTP_OK = 200

// One worker and a conflated mailbox prevent quick swipes from building up
// sockets, jobs or media-source references. CacheWriter cancellation closes its
// active range before the next target is allowed to start.
@OptIn(UnstableApi::class)
internal class NeighborPreloader(private val cache: VideoCache, client: OkHttpClient, io: CoroutineDispatcher) {
    private val client = client.newBuilder()
        .connectTimeout(PRELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PRELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PRELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val upstream = OkHttpDataSource.Factory(this.client)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val requests = Channel<Pair<Long, PlaybackItem?>>(Channel.CONFLATED)
    private val generation = AtomicLong()
    private val gate = Any()

    @Volatile
    private var writer: CacheWriter? = null

    @Volatile
    private var call: Call? = null
    private val worker = scope.launch {
        for ((serial, target) in requests) {
            if (target == null || serial != generation.get()) continue
            try {
                if (!cache.hasPreloadSpace()) continue
                // Bounded Range fills do not discover EOF. Persist the version-specific
                // server length so fully cached TS inputs still expose a seekable length.
                if (target.size > 0) {
                    cache.cache.applyContentMetadataMutations(
                        target.cacheKey,
                        ContentMetadataMutations.setContentLength(
                            ContentMetadataMutations(),
                            target.size,
                        ),
                    )
                }
                var remaining = PreloadBudget.MAX_BYTES
                for (range in hints(target, serial)) {
                    ensureActive()
                    check(serial == generation.get())
                    if (!PreloadBudget.allowed(
                            range,
                            target.size,
                        ) ||
                        range.length > remaining
                    ) {
                        continue
                    }
                    fill(target, range, serial)
                    remaining -= range.length
                }
                val opening =
                    minOf(PreloadBudget.openingBytes(target.size, target.durationMs), remaining)
                if (opening > 0) fill(target, ByteRange(0, opening), serial)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Preloading is optional; normal Range stays available. */
            } finally {
                writer = null
                call?.cancel()
                call = null
            }
        }
    }

    fun target(item: PlaybackItem?) {
        val previous = synchronized(gate) {
            val serial = generation.incrementAndGet()
            writer?.cancel()
            val old = call
            call = null
            requests.trySend(serial to item)
            old
        }
        // Socket shutdown belongs to IO; rapid swipes never disconnect on the UI thread.
        if (previous != null) scope.launch { previous.cancel() }
    }

    /** The byte ranges the server suggests preloading for [item], such as a trailing moov box. */
    private fun hints(item: PlaybackItem, serial: Long): List<ByteRange> {
        val request = client.newCall(Request.Builder().url(item.indexUrl).build())
        synchronized(gate) {
            check(serial == generation.get())
            call = request
        }
        return try {
            val text = request.execute().use { response ->
                if (response.code != HTTP_OK) return emptyList()
                val source = response.body?.source() ?: return emptyList()
                val buffer = Buffer()
                while (true) {
                    check(serial == generation.get())
                    if (source.read(buffer, INDEX_READ_STEP_BYTES) < 0) break
                    check(buffer.size <= MAX_INDEX_BYTES)
                }
                buffer.readUtf8()
            }
            val json = JSONObject(text)
            if (json.getString("version") != item.version) return emptyList()
            val ranges = json.getJSONArray("ranges")
            List(minOf(ranges.length(), MAX_HINT_RANGES)) {
                val range = ranges.getJSONObject(it)
                ByteRange(range.getLong("offset_bytes"), range.getLong("length_bytes"))
            }
        } finally {
            request.cancel()
            call = null
        }
    }

    private fun fill(item: PlaybackItem, range: ByteRange, serial: Long) {
        val factory = CacheDataSource.Factory().setCache(cache.cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        val dataSource = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(item.url)).setKey(item.cacheKey)
            .setPosition(range.offset).setLength(range.length).build()
        val task = CacheWriter(dataSource, spec, ByteArray(CACHE_WRITE_BUFFER_BYTES), null)
        synchronized(gate) {
            check(serial == generation.get())
            writer = task
        }
        try {
            task.cache()
        } finally {
            dataSource.close()
            writer = null
        }
    }

    suspend fun close() {
        target(null)
        requests.close()
        worker.cancelAndJoin()
        scope.cancel()
    }
}
