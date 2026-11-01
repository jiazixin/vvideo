package dev.vvideo.data.library.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {
    @Test
    fun retriesOnlyTransientFailuresTwice() {
        assertEquals(500L, retryDelayMillis(IOException(), 0) { 0.0 })
        assertEquals(1200L, retryDelayMillis(IOException(), 1) { 1.0 })
        assertNull(retryDelayMillis(IOException(), 2))
        for (status in listOf(400, 401, 404, 409, 412, 500)) {
            assertNull(retryDelayMillis(ServiceFailure(status), 0))
        }
        assertNull(retryDelayMillis(RevisionChanged(), 0))
        assertNull(retryDelayMillis(InvalidResponse("invalid JSON"), 0))
    }

    @Test
    fun honorsRetryAfterWithoutExceedingTheWaitBudget() {
        assertEquals(2000L, retryDelayMillis(ServiceFailure(503, retryAfterMillis = 2000), 0))
        assertEquals(0L, retryDelayMillis(ServiceFailure(429, retryAfterMillis = 0), 0))
        assertNull(retryDelayMillis(ServiceFailure(503, retryAfterMillis = 10001), 0))
        assertEquals(2000L, retryAfterMillis("2", 0))
        assertEquals(10001L, retryAfterMillis(Long.MAX_VALUE.toString(), 0))
        assertEquals(0L, retryAfterMillis("Thu, 01 Jan 1970 00:00:00 GMT", 1000))
        assertNull(retryAfterMillis("invalid", 0))
    }
}
