package dev.vvideo.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTextTest {
    @Test
    fun bytesUseDecimalUnitsWithAPoint() {
        assertEquals("5.00 GB", formatBytes(5_000_000_000))
        assertEquals("1.5 MB", formatBytes(1_500_000))
        assertEquals("0.0 KB", formatBytes(0))
        assertEquals(10, wholeGigabytes(10_000_000_000))
    }
}
