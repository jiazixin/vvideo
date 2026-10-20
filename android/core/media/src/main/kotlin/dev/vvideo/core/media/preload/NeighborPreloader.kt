package dev.vvideo.core.media.preload

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

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

    private fun hints(item: PlaybackItem, serial: Long): List<ByteRange> {
        val c = URL(item.indexUrl).openConnection() as HttpURLConnection
        synchronized(gate) {
            check(serial == generation.get())
            connection = c
        }
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer ${item.token}")
        return try {
            if (c.responseCode != 200) return emptyList()
            val text = c.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    check(serial == generation.get())
                    val n = stream.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= 64 * 1024)
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            val j = JSONObject(text)
            if (j.getString("version") != item.version) return emptyList()
            val a = j.getJSONArray("ranges")
            List(minOf(a.length(), 4)) {
                val r = a.getJSONObject(it)
                ByteRange(r.getLong("offset"), r.getLong("length"))
            }
        } finally {
            c.disconnect()
            connection = null
        }
    }

    private fun fill(item: PlaybackItem, range: ByteRange, serial: Long) {
        val factory = CacheDataSource.Factory().setCache(cache.cache).setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setConnectTimeoutMs(3000).setReadTimeoutMs(3000)
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${item.token}"))
        ).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        val ds = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(item.url)).setKey(item.cacheKey)
            .setPosition(range.offset).setLength(range.length).build()
        val task = CacheWriter(ds, spec, ByteArray(64 * 1024), null)
        synchronized(gate) {
            check(serial == generation.get())
            writer = task
        }
        try {
            task.cache()
        } finally {
            ds.close()
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
