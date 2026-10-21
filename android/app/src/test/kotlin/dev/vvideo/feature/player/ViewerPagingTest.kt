package dev.vvideo.feature.player

import dev.vvideo.data.library.PageEdge
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPagingTest {
    private fun requested(index: Int, count: Int) =
        buildList { requestNearbyPages(index, count) { add(it) } }

    @Test
    fun middleOfTheQueueAsksForNothing() {
        assertEquals(emptyList<PageEdge>(), requested(50, 100))
    }

    @Test
    fun nearEitherEndAsksForThatSide() {
        assertEquals(listOf(PageEdge.Next), requested(97, 100))
        assertEquals(listOf(PageEdge.Previous), requested(1, 100))
    }

    @Test
    fun aSingleItemQueueAsksForBothSides() {
        assertEquals(listOf(PageEdge.Next, PageEdge.Previous), requested(0, 1))
    }

    @Test
    fun anEmptyQueueOnlyAsksForMore() {
        assertEquals(listOf(PageEdge.Next), requested(0, 0))
    }
}
