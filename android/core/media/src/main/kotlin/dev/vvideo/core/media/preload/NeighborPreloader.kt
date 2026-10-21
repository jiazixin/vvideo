package dev.vvideo.core.media.preload

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.datasource.authorizationHeaders
import dev.vvideo.core.media.model.ByteRange
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.model.PreloadBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

private const val PRELOAD_TIMEOUT_MS = 3000
private const val INDEX_READ_BUFFER_BYTES = 4096

/** A preload index larger than this is not read. */
private const val MAX_INDEX_BYTES = 64 * 1024

/** At most this many hinted ranges of one video are preloaded. */
private const val MAX_HINT_RANGES = 4
private const val CACHE_WRITE_BUFFER_BYTES = 64 * 1024

// One worker and a conflated mailbox prevent quick swipes from building up
// sockets, jobs or media-source references. CacheWriter cancellation closes its
// active range before the next target is allowed to start.
@UnstableApi
class NeighborPreloader(private val cache: VideoCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Pair<Long, PlaybackItem?>>(Channel.CONFLATED)
    private val generation = AtomicLong()
    private val gate = Any()

    @Volatile
    private var writer: CacheWriter? = null

    @Volatile
    private var connection: HttpURLConnection? = null
    private val worker = scope.launch {
        for ((serial, target) in requests) {
            if (target == null || serial != generation.get() || !cache.hasPreloadSpace()) continue
            try {
                // Bounded Range fills do not discover EOF. Persist the version-specific
                // server length so fully cached TS inputs still expose a seekable length.
                if (target.size > 0) cache.cache.applyContentMetadataMutations(
                    target.cacheKey,
                    ContentMetadataMutations.setContentLength(
                        ContentMetadataMutations(),
                        target.size
                    )
                )
                var remaining = PreloadBudget.MAX_BYTES
                for (range in hints(target, serial)) {
                    ensureActive()
                    check(serial == generation.get())
                    if (!PreloadBudget.allowed(
                            range,
                            target.size
                        ) || range.length > remaining
                    ) continue
                    fill(target, range, serial)
                    remaining -= range.length
                }
                val opening =
                    minOf(PreloadBudget.openingBytes(target.size, target.durationMs), remaining)
                if (opening > 0) fill(target, ByteRange(0, opening), serial)
            } catch (_: Exception) { /* Preloading is optional; normal Range stays available. */
            } finally {
                writer = null
                connection?.disconnect()
                connection = null
            }
        }
    }

    fun target(item: PlaybackItem?) {
        val previous = synchronized(gate) {
            val serial = generation.incrementAndGet()
            writer?.cancel()
            val old = connection
            connection = null
            requests.trySend(serial to item)
            old
        }
        // Socket shutdown belongs to IO; rapid swipes never disconnect on the UI thread.
        if (previous != null) scope.launch { previous.disconnect() }
    }

    /** The byte ranges the server suggests preloading for [item], such as a trailing moov box. */
    private fun hints(item: PlaybackItem, serial: Long): List<ByteRange> {
        val request = URL(item.indexUrl).openConnection() as HttpURLConnection
        synchronized(gate) {
            check(serial == generation.get())
            connection = request
        }
        request.connectTimeout = PRELOAD_TIMEOUT_MS
        request.readTimeout = PRELOAD_TIMEOUT_MS
        request.instanceFollowRedirects = false
        authorizationHeaders(item.token).forEach { (name, value) -> request.setRequestProperty(name, value) }
        return try {
            if (request.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val text = request.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(INDEX_READ_BUFFER_BYTES)
                while (true) {
                    check(serial == generation.get())
                    val read = stream.read(buffer)
                    if (read < 0) break
                    check(out.size() + read <= MAX_INDEX_BYTES)
                    out.write(buffer, 0, read)
                }
                out.toString("UTF-8")
            }
            val json = JSONObject(text)
            if (json.getString("version") != item.version) return emptyList()
            val ranges = json.getJSONArray("ranges")
            List(minOf(ranges.length(), MAX_HINT_RANGES)) {
                val range = ranges.getJSONObject(it)
                ByteRange(range.getLong("offset"), range.getLong("length"))
            }
        } finally {
            request.disconnect()
            connection = null
        }
    }

    private fun fill(item: PlaybackItem, range: ByteRange, serial: Long) {
        val factory = CacheDataSource.Factory().setCache(cache.cache).setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setConnectTimeoutMs(PRELOAD_TIMEOUT_MS)
                .setReadTimeoutMs(PRELOAD_TIMEOUT_MS)
                .setDefaultRequestProperties(authorizationHeaders(item.token))
        ).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
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
