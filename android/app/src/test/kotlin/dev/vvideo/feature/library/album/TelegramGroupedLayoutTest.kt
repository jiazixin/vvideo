package dev.vvideo.feature.library.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramGroupedLayoutTest {
    // The test phone: 1440 px wide portrait at density 3.5.
    private fun layout(vararg ratios: Float) =
        TelegramGroupedLayout.calculate(ratios.toList(), 3.5f, 1440f)

    private fun rows(positions: List<GroupedPosition>) =
        positions.groupBy { it.minY }.toSortedMap().values.map { it.size }

    @Test
    fun tenSquaresFillFourRowsWithTheBigCellsFirst() {
        val positions = layout(*FloatArray(10) { 1f })
        assertEquals(listOf(2, 2, 3, 3), rows(positions))
        for (row in positions.groupBy { it.minY }.values) {
            assertEquals(TelegramGroupedLayout.MAX_SIZE_WIDTH, row.sumOf { it.pw })
        }
    }

    @Test
    fun twoSquaresSitSideBySideAtHalfWidth() {
        val positions = layout(1f, 1f)
        assertEquals(listOf(400, 400), positions.map { it.pw })
        assertEquals(listOf(0, 0), positions.map { it.minY })
    }

    @Test
    fun aTallFirstOfThreeSpansBothRowsOnTheLeft() {
        val positions = layout(.6f, 1f, 1f)
        val first = positions[0]
        assertEquals(0, first.minY)
        assertEquals(1, first.maxY)
        assertNotNull(first.siblingHeights)
        assertEquals(first.pw, positions[2].leftSpanOffset)
        assertEquals(listOf(0, 1), positions.drop(1).map { it.minY })
    }

    @Test
    fun aWideFirstOfFourTakesTheTopRow() {
        val positions = layout(1.5f, 1f, 1f, 1f)
        assertEquals(TelegramGroupedLayout.MAX_SIZE_WIDTH, positions[0].pw)
        assertEquals(listOf(1, 1, 1), positions.drop(1).map { it.minY })
        assertEquals(TelegramGroupedLayout.MAX_SIZE_WIDTH, positions.drop(1).sumOf { it.pw })
    }

    @Test
    fun aVeryWideItemForcesTheRowSearch() {
        // A ratio above 2 skips the fixed two-item layouts: each item gets its own row.
        val positions = layout(2.5f, 1f)
        assertEquals(listOf(1, 1), rows(positions))
        assertEquals(listOf(800, 800), positions.map { it.pw })
    }

    @Test
    fun everyItemIsPlacedOnceWithPositiveSize() {
        for (count in 1..10) for (shape in listOf(.3f, .75f, 1f, 1.33f, 1.78f, 3f)) {
            val positions = layout(*FloatArray(count) { if (it % 2 == 0) shape else 1f })
            assertEquals(count, positions.size)
            assertTrue(positions.all { it.pw > 0 && it.ph > 0f })
        }
        assertTrue(layout().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanTenItemsAreRejected() {
        layout(*FloatArray(11) { 1f })
    }
}
