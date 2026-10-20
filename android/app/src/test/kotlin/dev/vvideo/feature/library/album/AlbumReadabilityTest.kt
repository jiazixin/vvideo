package dev.vvideo.feature.library.album

import org.junit.Assert.*
import org.junit.Test

class AlbumReadabilityTest {
    @Test
    fun longDurationsAndLargeFontsKeepCellsWide() {
        val ratios = listOf(.3f, 3.3f, .4f, 1f, .3f, 2f, .3f, 1f, .5f, 1f)
        val rows = AlbumLayoutCalculator.calculate(ratios, 336f, List(10) { 130f })
        assertEquals((0..9).toList(), rows.flatMap { it.indices })
        for (row in rows) for (weight in row.weights) assertTrue((336f - 2 * (row.weights.size - 1)) * weight >= 130f)
    }

    @Test
    fun hourAndUnknownDurationDoNotBecomeFakeZero() {
        assertEquals("1:01:01", dev.vvideo.ui.component.mediaDuration(3661000))
        assertEquals("0:09", dev.vvideo.ui.component.mediaDuration(9000))
    }
}
