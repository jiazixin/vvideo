package dev.vvideo.feature.library.album

import org.junit.Assert.*
import org.junit.Test

class AlbumReadabilityTest {
    @Test
    fun hourAndUnknownDurationDoNotBecomeFakeZero() {
        assertEquals("1:01:01", dev.vvideo.ui.component.mediaDuration(3661000))
        assertEquals("0:09", dev.vvideo.ui.component.mediaDuration(9000))
    }
}
