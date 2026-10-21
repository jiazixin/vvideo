package dev.vvideo.data.library.remote

import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.CaptureSource
import dev.vvideo.model.ChildrenPage
import dev.vvideo.model.Directory
import dev.vvideo.model.Library
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaPage
import dev.vvideo.model.MediaStatus
import dev.vvideo.model.SearchResult
import dev.vvideo.model.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The largest JSON response the client reads. */
private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

/** Bytes read from the response body per step while counting against [MAX_RESPONSE_BYTES]. */
private const val READ_STEP_BYTES = 8192L

/** Media items per page; the server allows up to 200. */
private const val PAGE_SIZE = 100

private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val READ_TIMEOUT_SECONDS = 10L

const val HTTP_UNAUTHORIZED = 401
private const val HTTP_CONFLICT = 409

/** Reported for a server too old to page subfolders backwards. */
private const val HTTP_UPGRADE_REQUIRED = 426

class ServiceFailure(val status: Int) : IOException("service response $status")

class RevisionChanged : IOException("directory revision changed")

/** The HTTP status of a failed service response, or 0 when the server was not reached. */
fun Throwable.serviceStatus(): Int = (this as? ServiceFailure)?.status ?: 0

/** Client for the server's authenticated /v1 JSON API. */
class LibraryApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private suspend fun get(
        connection: Connection,
        path: String,
        params: Map<String, String> = emptyMap()
    ): String {
        val url = (connection.address + path).toHttpUrl().newBuilder().apply {
            params.filterValues { it.isNotEmpty() }
                .forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val call = client.newCall(
            Request.Builder().url(url).header(AUTHORIZATION_HEADER, connection.authorization())
                .build()
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("service unavailable"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.code == HTTP_CONFLICT) throw RevisionChanged()
                            if (!response.isSuccessful) throw ServiceFailure(response.code)
                            val source =
                                response.body?.source() ?: throw IOException("empty response")
                            val buffer = Buffer()
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer, READ_STEP_BYTES)
                                if (read < 0) break
                                total += read
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

    suspend fun libraries(connection: Connection): List<Library> = withContext(Dispatchers.IO) {
        JSONArray(get(connection, "/v1/libraries")).objects().map {
            Library(
                id = it.getString("id"),
                name = it.getString("name"),
                directoryId = it.getLong("directory_id"),
                healthy = it.getBoolean("healthy")
            )
        }
    }

    suspend fun path(connection: Connection, directoryId: Long): List<Directory> =
        withContext(Dispatchers.IO) {
            JSONArray(get(connection, "/v1/directories/$directoryId/path")).objects()
                .map(::directory)
        }

    suspend fun children(
        connection: Connection,
        directoryId: Long,
        cursor: String = "",
        edge: PageEdge = PageEdge.Next
    ): ChildrenPage = withContext(Dispatchers.IO) {
        val json = JSONObject(
            get(
                connection,
                "/v1/directories/$directoryId/children",
                mapOf("cursor" to cursor, "previous" to (edge == PageEdge.Previous).toString())
            )
        )
        if (cursor.isNotEmpty() && !json.has("previous")) throw ServiceFailure(HTTP_UPGRADE_REQUIRED)
        ChildrenPage(
            items = json.getJSONArray("items").objects().map(::directory),
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }

    /** One page of a folder's media; a null [kind] lists every kind. */
    suspend fun page(
        connection: Connection,
        directoryId: Long,
        sort: Sort,
        cursor: String = "",
        edge: PageEdge = PageEdge.Next,
        anchor: String = "",
        fallback: String = "",
        kind: MediaKind? = null
    ): MediaPage = withContext(Dispatchers.IO) {
        val json = JSONObject(
            get(
                connection,
                "/v1/directories/$directoryId/media",
                mapOf(
                    "sort" to sort.field.wire,
                    "direction" to sort.direction.wire,
                    "cursor" to cursor,
                    "previous" to (edge == PageEdge.Previous).toString(),
                    "anchor" to anchor,
                    "fallback" to fallback,
                    "kind" to kind?.wire.orEmpty(),
                    "limit" to PAGE_SIZE.toString()
                )
            )
        )
        MediaPage(
            directory = directory(json.getJSONObject("directory")),
            items = json.getJSONArray("items").objects().map(::media),
            next = json.optString("next"),
            previous = json.optString("previous"),
            anchor = json.optString("anchor"),
            anchorFound = json.optBoolean("anchor_found"),
            healthy = json.getBoolean("healthy")
        )
    }

    suspend fun search(connection: Connection, directoryId: Long, query: String): SearchResult =
        withContext(Dispatchers.IO) {
            val json = JSONObject(
                get(
                    connection,
                    "/v1/directories/$directoryId/search",
                    mapOf("q" to query)
                )
            )
            SearchResult(
                directories = json.getJSONArray("directories").objects().map(::directory),
                items = json.getJSONArray("items").objects().map(::media),
                truncated = json.optBoolean("truncated")
            )
        }

    suspend fun media(connection: Connection, id: String): Media =
        withContext(Dispatchers.IO) { media(JSONObject(get(connection, "/v1/media/$id"))) }

    private fun directory(json: JSONObject) = Directory(
        id = json.getLong("id"),
        libraryId = json.getString("library_id"),
        parentId = json.optLong("parent_id"),
        name = json.getString("name"),
        revision = json.getLong("revision")
    )

    private fun media(json: JSONObject) = Media(
        id = json.getString("id"),
        version = json.getString("version"),
        libraryId = json.getString("library_id"),
        directoryId = json.getLong("directory_id"),
        name = json.getString("name"),
        kind = MediaKind.fromWire(json.getString("kind")),
        width = json.optInt("width", 1),
        height = json.optInt("height", 1),
        rotation = json.optInt("rotation"),
        durationMs = json.optLong("duration_ms"),
        size = json.getLong("size"),
        effectiveTime = json.getLong("effective_time"),
        mtime = json.getLong("mtime"),
        status = MediaStatus.fromWire(json.getString("status")),
        capturedRaw = json.optString("captured_raw"),
        capturedSource = CaptureSource.fromWire(json.optString("captured_source"))
    )

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
}
