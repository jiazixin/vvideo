package dev.vvideo.data.library.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceFailureTest {
    @Test
    fun readsTheErrorCodeOfAStandardBody() {
        assertEquals(
            "thumbnail_pending",
            errorCode("""{"error":{"code":"thumbnail_pending","message":"thumbnail not ready yet"}}"""),
        )
    }

    @Test
    fun returnsEmptyForOtherBodies() {
        assertEquals("", errorCode(""))
        assertEquals("", errorCode("not json"))
        assertEquals("", errorCode("""{"error":"unavailable"}"""))
    }
}
