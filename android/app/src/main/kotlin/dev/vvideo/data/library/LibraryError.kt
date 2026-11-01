package dev.vvideo.data.library

import dev.vvideo.data.library.remote.InvalidResponse
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.library.remote.ServiceFailure
import java.io.InterruptedIOException

/** Failures exposed to screens, with response IDs retained for diagnostics. */
internal sealed interface LibraryError {
    data object Unreachable : LibraryError
    data object Timeout : LibraryError
    data class InvalidResponse(val requestId: String) : LibraryError
    data class Unauthenticated(val requestId: String = "") : LibraryError
    data class Service(
        val code: String,
        val requestId: String,
        val category: ServiceCategory = ServiceCategory.Server,
    ) : LibraryError
}

/** Status fallback for unknown server codes, without HTTP details in UiState. */
internal enum class ServiceCategory { InvalidRequest, Forbidden, Missing, Conflict, Busy, Server }

/** Codes take precedence; unknown codes fall back to the response's status family. */
internal fun Throwable.libraryError(): LibraryError = when (this) {
    is InvalidResponse -> LibraryError.InvalidResponse(requestId)
    is RevisionChanged -> LibraryError.Service("revision_changed", requestId, ServiceCategory.Conflict)
    is ServiceFailure -> when (code) {
        "unauthenticated" -> LibraryError.Unauthenticated(requestId)
        "invalid_argument" -> LibraryError.Service(code, requestId, ServiceCategory.InvalidRequest)
        "permission_denied" -> LibraryError.Service(code, requestId, ServiceCategory.Forbidden)
        "not_found" -> LibraryError.Service(code, requestId, ServiceCategory.Missing)
        "revision_changed", "conflict", "precondition_failed" -> LibraryError.Service(
            code,
            requestId,
            ServiceCategory.Conflict,
        )
        "rate_limited", "unavailable", "media_busy", "thumbnail_pending" ->
            LibraryError.Service(code, requestId, ServiceCategory.Busy)
        else -> when (status) {
            401 -> LibraryError.Unauthenticated(requestId)
            400, 405, 413, 415 -> LibraryError.Service(code, requestId, ServiceCategory.InvalidRequest)
            403 -> LibraryError.Service(code, requestId, ServiceCategory.Forbidden)
            404 -> LibraryError.Service(code, requestId, ServiceCategory.Missing)
            409, 412 -> LibraryError.Service(code, requestId, ServiceCategory.Conflict)
            429, 503 -> LibraryError.Service(code, requestId, ServiceCategory.Busy)
            else -> LibraryError.Service(code, requestId)
        }
    }
    is InterruptedIOException -> LibraryError.Timeout
    else -> LibraryError.Unreachable
}
