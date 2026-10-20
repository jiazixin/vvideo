package dev.vvideo.feature.library.album

import org.junit.Assert.*
import org.junit.Test

class AlbumLayoutCalculatorTest {
    @Test
    fun coversEveryItemOnceAcrossShapesAndPartialAlbums() {
        for (count in 0..10) {
            for (shape in listOf(.01f, .3f, 1f, 3.3f, 100f, Float.NaN)) {
                val rows =
                    AlbumLayoutCalculator.calculate(List(count) { if (it % 2 == 0) shape else 1f })
                assertEquals((0 until count).toList(), rows.flatMap { it.indices })
                for (row in rows) {
                    assertTrue(row.indices.size <= 4)
                    assertTrue(row.heightRatio.isFinite() && row.heightRatio > 0)
                    assertEquals(1f, row.weights.sum(), .00001f)
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWholeDirectoryLayout() {
        AlbumLayoutCalculator.calculate(List(11) { 1f })
    }
}
