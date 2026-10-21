package dev.vvideo.feature.library

import dev.vvideo.model.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRetryTest {
    @Test
    fun transientFailuresAndMissingCoversOfPendingMediaAreRetried() {
        assertTrue(coverRetryable(null, MediaStatus.Ready))
        assertTrue(coverRetryable(503, MediaStatus.Ready))
        assertTrue(coverRetryable(429, MediaStatus.Ready))
        assertTrue(coverRetryable(404, MediaStatus.Pending))
    }

    @Test
    fun permanentFailuresAreNotRetried() {
        assertFalse(coverRetryable(404, MediaStatus.Ready))
        assertFalse(coverRetryable(415, MediaStatus.Pending))
        assertFalse(coverRetryable(401, MediaStatus.Ready))
    }

    @Test
    fun retryAfterReadsSecondsOrAnHttpDate() {
        assertEquals(5000L, retryAfterMs("5", 0))
        val now = 1_760_000_000_000L
        // Two seconds after `now`, as an RFC 1123 date.
        assertEquals(2000L, retryAfterMs("Thu, 9 Oct 2025 08:53:22 GMT", now))
        assertEquals(0L, retryAfterMs("Thu, 9 Oct 2025 08:53:00 GMT", now))
    }

    @Test
    fun missingOrUnreadableRetryAfterFallsBackToTheBaseDelay() {
        assertEquals(1000L, retryAfterMs(null, 0))
        assertEquals(1000L, retryAfterMs("soon", 0))
        assertEquals(1000L, retryAfterMs("-3", 0))
    }
}
