package dev.vvideo.data.library.remote

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

private const val MAX_RETRIES = 2
private const val MAX_DELAY_MS = 10_000L
private const val INITIAL_DELAY_MS = 500L

/** Retry-After delta seconds or HTTP date, capped by the caller's retry policy. */
internal fun retryAfterMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? {
    value.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return if (seconds > MAX_DELAY_MS / 1000) MAX_DELAY_MS + 1 else seconds * 1000
    }
    return try {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
            .coerceAtLeast(0)
    } catch (_: DateTimeParseException) {
        null // A malformed server header uses the bounded backoff instead.
    }
}

/** Only GET transport failures, 429 and 503 retry; conflicts and invalid bodies do not. */
internal fun retryDelayMillis(error: IOException, attempt: Int, jitter: () -> Double = { Random.nextDouble() }): Long? {
    if (attempt >= MAX_RETRIES || error is RevisionChanged || error is InvalidResponse) return null
    if (error is ServiceFailure) {
        if (error.status != 429 && error.status != 503) return null
        error.retryAfterMillis?.let { return if (it <= MAX_DELAY_MS) it else null }
    }
    return (INITIAL_DELAY_MS * (1L shl attempt) * (1 + jitter() * 0.2)).toLong().coerceAtMost(MAX_DELAY_MS)
}

/** A bounded response that cannot satisfy the JSON contract is not a network outage. */
internal class InvalidResponse(message: String, val requestId: String = "", cause: Throwable? = null) :
    IOException(message, cause)
