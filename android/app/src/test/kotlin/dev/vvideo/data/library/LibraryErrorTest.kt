package dev.vvideo.data.library

import dev.vvideo.data.library.remote.ServiceFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class LibraryErrorTest {
    @Test
    fun unauthenticatedCodeIsARejectedToken() {
        assertEquals(LibraryError.Unauthenticated("r1"), ServiceFailure(401, "unauthenticated", "r1").libraryError())
    }

    @Test
    fun otherCodesKeepTheRequestId() {
        assertEquals(
            LibraryError.Service("not_found", "r2", ServiceCategory.Missing),
            ServiceFailure(404, "not_found", "r2").libraryError(),
        )
    }

    @Test
    fun failuresWithoutAResponseAreUnreachable() {
        assertEquals(LibraryError.Unreachable, IOException("service unavailable").libraryError())
    }
}
