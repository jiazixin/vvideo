package dev.vvideo.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ThumbnailRetryTest {
    @Test
    fun onlyTransportAndTransientStatusesRetryTwice() {
        val failure = IOException("fixture")
        assertNotNull(coverRetryDelay(failure, 0))
        assertNotNull(coverRetryDelay(failure, 1, status = 503))
        assertNotNull(coverRetryDelay(failure, 0, status = 429))
        assertNull(coverRetryDelay(failure, 2, status = 503))
        for (status in listOf(401, 403, 404, 408, 415, 500, 502, 504)) {
            assertNull(coverRetryDelay(failure, 0, status = status))
        }
        assertNull(coverRetryDelay(IllegalArgumentException("invalid image"), 0))
    }

    @Test
    fun retryAfterIsRespectedAndLongWaitsAreRejected() {
        assertEquals(5000L, coverRetryDelay(IOException(), 0, "5", 503))
        assertNull(coverRetryDelay(IOException(), 0, "11", 503))
    }
}
