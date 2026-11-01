package dev.vvideo.data.library.remote

import dev.vvideo.data.library.PageEdge
import dev.vvideo.data.network.API_TIMEOUT_SECONDS
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The largest JSON response the client reads. */
private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

/** Bytes read from the response body per step while counting against [MAX_RESPONSE_BYTES]. */
private const val READ_STEP_BYTES = 8192L

/** Error bodies are small JSON objects; anything longer is not parsed. */
private const val MAX_ERROR_BYTES = 4096L

/** Media items per page; the server allows up to 200. */
private const val PAGE_SIZE = 100

private const val NANOS_PER_SECOND = 1_000_000_000L

private const val REQUEST_ID_HEADER = "X-Request-ID"

/**
 * A response the server answered with an error status. [code] is the error code
 * of the response body (`{"error": {"code": ...}}`), empty when the body has none;
 * [requestId] is the response's X-Request-ID, which finds the request in the server log.
 */
internal class ServiceFailure(
    val status: Int,
    val code: String = "",
    val requestId: String = "",
    val retryAfterMillis: Long? = null,
) : IOException("service response $status $code request_id=$requestId")

/** Reads the error code from a failed response body of at most [MAX_ERROR_BYTES]. */
internal fun errorCode(body: String): String =
    runCatching { JSONObject(body).optJSONObject("error")?.optString("code").orEmpty() }.getOrDefault("")

internal class RevisionChanged(val requestId: String = "") :
    IOException("directory revision changed request_id=$requestId")

/**
 * Client for the server's authenticated JSON API under /api (docs/API.md). [client]
 * adds the Authorization header for the connection each request carries as a tag;
 * responses are parsed on [dispatcher].
 */
private data class ApiResponse(val body: String, val requestId: String)

internal class LibraryApi(client: OkHttpClient, private val dispatcher: CoroutineDispatcher) {
    private val client = client.newBuilder().callTimeout(API_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private suspend fun get(
        connection: Connection,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiResponse {
        var attempt = 0
        while (true) {
            try {
                return request(connection, path, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val wait = retryDelayMillis(e, attempt++) ?: throw e
                delay(wait)
            }
        }
    }

    private suspend fun request(
        connection: Connection,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val url = (connection.address + path).toHttpUrl().newBuilder().apply {
            params.filterValues { it.isNotEmpty() }
                .forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val call = client.newCall(
            Request.Builder().url(url).tag(Connection::class.java, connection).build(),
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) {
                                val body = response.body?.source()?.let { source ->
                                    Buffer().also { buffer ->
                                        while (buffer.size <= MAX_ERROR_BYTES) {
                                            if (source.read(buffer, MAX_ERROR_BYTES + 1 - buffer.size) < 0) break
                                        }
                                    }.let { if (it.size <= MAX_ERROR_BYTES) it.readUtf8() else "" }
                                }.orEmpty()
                                val code = errorCode(body)
                                val requestId = response.header(REQUEST_ID_HEADER).orEmpty()
                                if (code == "revision_changed") throw RevisionChanged(requestId)
                                throw ServiceFailure(
                                    response.code,
                                    code,
                                    requestId,
                                    response.header("Retry-After")?.let(::retryAfterMillis),
                                )
                            }
                            val source =
                                response.body?.source()
                                    ?: throw InvalidResponse(
                                        "empty response",
                                        response.header(REQUEST_ID_HEADER).orEmpty(),
                                    )
                            val buffer = Buffer()
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer, READ_STEP_BYTES)
                                if (read < 0) break
                                total += read
                                if (total >
                                    MAX_RESPONSE_BYTES
                                ) {
                                    throw InvalidResponse(
                                        "response exceeds bound",
                                        response.header(REQUEST_ID_HEADER).orEmpty(),
                                    )
                                }
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    ApiResponse(buffer.readUtf8(), response.header(REQUEST_ID_HEADER).orEmpty()),
                                )
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private suspend fun <T> decode(
        connection: Connection,
        path: String,
        params: Map<String, String> = emptyMap(),
        transform: (JSONObject) -> T,
    ): T = withContext(dispatcher) {
        val response = get(connection, path, params)
        try {
            transform(JSONObject(response.body))
        } catch (e: JSONException) {
            throw InvalidResponse("invalid JSON response", response.requestId, e)
        } catch (e: DateTimeException) {
            throw InvalidResponse("invalid response time", response.requestId, e)
        } catch (e: ArithmeticException) {
            throw InvalidResponse("response time exceeds bounds", response.requestId, e)
        }
    }

    suspend fun libraries(connection: Connection): List<Library> = decode(connection, "/api/libraries") { json ->
        json.getJSONArray("items").objects().map {
            Library(
                id = it.getString("id"),
                name = it.getString("name"),
                directoryId = it.getLong("directory_id"),
                healthy = it.getBoolean("healthy"),
            )
        }
    }

    suspend fun path(connection: Connection, directoryId: Long): List<Directory> =
        decode(connection, "/api/directories/$directoryId/path") { json ->
            json.getJSONArray("items").objects().map(::directory)
        }

    suspend fun children(
        connection: Connection,
        directoryId: Long,
        cursor: String = "",
        edge: PageEdge = PageEdge.Next,
    ): ChildrenPage = decode(
        connection,
        "/api/directories/$directoryId/children",
        mapOf("cursor" to cursor, "previous" to (edge == PageEdge.Previous).toString()),
    ) { json ->
        ChildrenPage(
            items = json.getJSONArray("items").objects().map(::directory),
            next = json.optString("next_cursor"),
            previous = json.optString("previous_cursor"),
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
        kind: MediaKind? = null,
    ): MediaPage = decode(
        connection,
        "/api/directories/$directoryId/media",
        mapOf(
            "sort" to sort.field.wire,
            "direction" to sort.direction.wire,
            "cursor" to cursor,
            "previous" to (edge == PageEdge.Previous).toString(),
            "anchor" to anchor,
            "fallback" to fallback,
            "kind" to kind?.wire.orEmpty(),
            "limit" to PAGE_SIZE.toString(),
        ),
    ) { json ->
        MediaPage(
            directory = directory(json.getJSONObject("directory")),
            items = json.getJSONArray("items").objects().map(::media),
            next = json.optString("next_cursor"),
            previous = json.optString("previous_cursor"),
            anchor = json.optString("anchor"),
            anchorFound = json.optBoolean("anchor_found"),
            healthy = json.getBoolean("healthy"),
        )
    }

    suspend fun search(connection: Connection, directoryId: Long, query: String): SearchResult =
        decode(connection, "/api/directories/$directoryId/search", mapOf("q" to query)) { json ->
            SearchResult(
                directories = json.getJSONArray("directories").objects().map(::directory),
                items = json.getJSONArray("items").objects().map(::media),
                truncated = json.optBoolean("truncated"),
            )
        }

    suspend fun media(connection: Connection, id: String): Media =
        decode(connection, "/api/media/$id", transform = ::media)

    private fun directory(json: JSONObject) = Directory(
        id = json.getLong("id"),
        libraryId = json.getString("library_id"),
        parentId = json.optLong("parent_id"),
        name = json.getString("name"),
        revision = json.getLong("revision"),
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
        size = json.getLong("size_bytes"),
        effectiveTime = epochNanos(json.getString("effective_at")),
        mtime = epochNanos(json.getString("modified_at")),
        status = MediaStatus.fromWire(json.getString("status")),
        capturedRaw = json.optString("captured_raw"),
        capturedSource = CaptureSource.fromWire(json.optString("captured_source")),
    )

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    /** The model keeps times as epoch nanoseconds; the API sends UTC RFC 3339 with milliseconds. */
    private fun epochNanos(value: String): Long = Instant.parse(value).let {
        Math.addExact(Math.multiplyExact(it.epochSecond, NANOS_PER_SECOND), it.nano.toLong())
    }
}
