package dev.vvideo.data.library.remote

import dev.vvideo.data.settings.Connection
import dev.vvideo.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The largest JSON response the client reads. */
private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

/** Media items per page; the server allows up to 200. */
private const val PAGE_SIZE = 100

class ServiceFailure(val status: Int) : IOException("service response $status")

class RevisionChanged : IOException("directory revision changed")

/** Client for the server's authenticated /v1 JSON API. */
class LibraryApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private suspend fun get(
        connection: Connection,
        path: String,
        params: Map<String, String> = emptyMap()
    ): String {
        val url = (connection.address + path).toHttpUrl().newBuilder().apply {
            params.filterValues { it.isNotEmpty() }.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val call = client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer ${connection.token}").build()
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("service unavailable"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            if (r.code == 409) throw RevisionChanged()
                            if (!r.isSuccessful) throw ServiceFailure(r.code)
                            val source = r.body?.source() ?: throw IOException("empty response")
                            val buffer = okio.Buffer()
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer, 8192)
                                if (count < 0) break
                                total += count
                                if (total > MAX_RESPONSE_BYTES) throw IOException("response exceeds bound")
                            }
                            if (continuation.isActive) continuation.resume(buffer.readUtf8())
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    suspend fun libraries(c: Connection): List<Library> = withContext(Dispatchers.IO) {
        JSONArray(get(c, "/v1/libraries")).objects().map {
            Library(
                it.getString("id"),
                it.getString("name"),
                it.getLong("directory_id"),
                it.getBoolean("healthy")
            )
        }
    }

    suspend fun path(c: Connection, id: Long): List<Directory> = withContext(Dispatchers.IO) {
        JSONArray(get(c, "/v1/directories/$id/path")).objects().map(::directory)
    }

    suspend fun children(
        c: Connection,
        dir: Long,
        cursor: String = "",
        previous: Boolean = false
    ): ChildrenPage = withContext(Dispatchers.IO) {
        val j = JSONObject(
            get(
                c,
                "/v1/directories/$dir/children",
                mapOf("cursor" to cursor, "previous" to previous.toString())
            )
        )
        if (cursor.isNotEmpty() && !j.has("previous")) throw ServiceFailure(426)
        ChildrenPage(
            j.getJSONArray("items").objects().map(::directory),
            j.optString("next"),
            j.optString("previous")
        )
    }

    suspend fun page(
        c: Connection,
        dir: Long,
        sort: Sort,
        cursor: String = "",
        previous: Boolean = false,
        anchor: String = "",
        fallback: String = "",
        kind: String = ""
    ): MediaPage = withContext(Dispatchers.IO) {
        val j = JSONObject(
            get(
                c,
                "/v1/directories/$dir/media",
                mapOf(
                    "sort" to sort.field,
                    "direction" to sort.direction,
                    "cursor" to cursor,
                    "previous" to previous.toString(),
                    "anchor" to anchor,
                    "fallback" to fallback,
                    "kind" to kind,
                    "limit" to PAGE_SIZE.toString()
                )
            )
        )
        MediaPage(
            directory(j.getJSONObject("directory")),
            j.getJSONArray("items").objects().map(::media),
            j.optString("next"),
            j.optString("previous"),
            j.optString("anchor"),
            j.optBoolean("anchor_found"),
            j.getBoolean("healthy")
        )
    }

    suspend fun search(c: Connection, dir: Long, query: String): SearchResult = withContext(Dispatchers.IO) {
        val j = JSONObject(get(c, "/v1/directories/$dir/search", mapOf("q" to query)))
        SearchResult(
            j.getJSONArray("directories").objects().map(::directory),
            j.getJSONArray("items").objects().map(::media),
            j.optBoolean("truncated")
        )
    }

    suspend fun media(c: Connection, id: String): Media =
        withContext(Dispatchers.IO) { media(JSONObject(get(c, "/v1/media/$id"))) }

    fun thumbnail(c: Connection, m: Media) =
        "${c.address}/v1/media/${m.id}/thumbnail?version=${m.version}"

    fun bytes(c: Connection, m: Media) = "${c.address}/v1/media/${m.id}/bytes?version=${m.version}"

    private fun directory(j: JSONObject) = Directory(
        j.getLong("id"),
        j.getString("library_id"),
        j.optLong("parent_id"),
        j.getString("name"),
        j.getLong("revision")
    )

    private fun media(j: JSONObject) = Media(
        j.getString("id"),
        j.getString("version"),
        j.getString("library_id"),
        j.getLong("directory_id"),
        j.getString("name"),
        j.getString("kind"),
        j.optInt("width", 1),
        j.optInt("height", 1),
        j.optInt("rotation"),
        j.optLong("duration_ms"),
        j.getLong("size"),
        j.getLong("effective_time"),
        j.getLong("mtime"),
        j.getString("status"),
        j.optString("captured_raw"),
        j.optString("captured_source")
    )

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
}
