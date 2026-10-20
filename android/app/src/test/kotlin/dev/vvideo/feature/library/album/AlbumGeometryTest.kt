package dev.vvideo.feature.library.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGeometryTest {
    private val gap = 7f
    private val albumWidth = 1384f

    private fun geometry(vararg ratios: Float): AlbumGeometry {
        val positions = TelegramGroupedLayout.calculate(ratios.toList(), 3.5f, 1440f)
        return AlbumGeometryCalculator.geometry(positions, 1440f, 3200f, albumWidth, gap)
    }

    private fun AlbumCell.overlaps(other: AlbumCell) =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    @Test
    fun cellsTileTheAlbumWithoutOverlapping() {
        for (count in 1..10) for (shape in listOf(.5f, 1f, 1.5f, 2.5f)) {
            val album = geometry(*FloatArray(count) { if (it % 3 == 0) shape else 1f })
            assertEquals(count, album.cells.size)
            for (cell in album.cells) {
                assertTrue(cell.width > 0f && cell.height > 0f)
                assertTrue(cell.x >= 0f && cell.x + cell.width <= albumWidth + .01f)
                assertTrue(cell.y >= 0f && cell.y + cell.height <= album.height + .01f)
                if (cell.atRight) assertEquals(albumWidth, cell.x + cell.width, .01f)
                if (cell.atBottom) assertEquals(album.height, cell.y + cell.height, .01f)
            }
            for (a in album.cells) for (b in album.cells) if (a !== b) assertTrue(!a.overlaps(b))
        }
    }

    @Test
    fun cellsInARowShareTheirTopAndLeaveTheGapBetween() {
        val album = geometry(*FloatArray(10) { 1f })
        val firstRow = album.cells.filter { it.atTop }.sortedBy { it.x }
        assertEquals(2, firstRow.size)
        assertEquals(firstRow[0].y, firstRow[1].y, 0f)
        assertEquals(gap, firstRow[1].x - (firstRow[0].x + firstRow[0].width), .5f)
    }

    @Test
    fun aTallLeftCellCoversTheRowsBesideIt() {
        val album = geometry(.6f, 1f, 1f)
        val left = album.cells[0]
        assertEquals(0f, left.y, 0f)
        assertEquals(album.height, left.height, .01f)
        val (upper, lower) = album.cells.drop(1)
        assertEquals(upper.x, lower.x, 1f)
        assertTrue(lower.y > upper.y + upper.height)
    }
}
