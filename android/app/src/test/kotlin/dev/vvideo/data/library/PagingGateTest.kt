package dev.vvideo.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingGateTest {
    // Opening the last video of a folder: the anchored page holds one item, so the
    // viewer asks for both edges at once and never repeats either request.
    @Test
    fun oppositeEdgeAskedDuringRequestRunsAfterIt() {
        val gate = PagingGate()
        assertTrue(gate.begin(PageEdge.Next))
        assertFalse(gate.begin(PageEdge.Previous))
        assertEquals(PageEdge.Previous, gate.end(PageResult.Empty))
        assertTrue(gate.begin(PageEdge.Previous))
        assertNull(gate.end(PageResult.Items))
    }

    @Test
    fun exhaustedEdgeStaysClosedUntilOppositeLoads() {
        val gate = PagingGate()
        assertTrue(gate.begin(PageEdge.Next))
        assertNull(gate.end(PageResult.Empty))
        assertFalse(gate.begin(PageEdge.Next))
        assertTrue(gate.begin(PageEdge.Previous))
        assertNull(gate.end(PageResult.Items))
        assertTrue(gate.begin(PageEdge.Next))
    }

    @Test
    fun repeatedSameEdgeIsNotQueued() {
        val gate = PagingGate()
        assertTrue(gate.begin(PageEdge.Next))
        assertFalse(gate.begin(PageEdge.Next))
        assertNull(gate.end(PageResult.Items))
        assertTrue(gate.begin(PageEdge.Next))
    }

    @Test
    fun closedEdgeIsNeitherStartedNorKept() {
        val gate = PagingGate()
        assertTrue(gate.begin(PageEdge.Previous))
        assertNull(gate.end(PageResult.Empty))
        assertTrue(gate.begin(PageEdge.Next))
        assertFalse(gate.begin(PageEdge.Previous))
        assertNull(gate.end(PageResult.Empty))
        assertFalse(gate.begin(PageEdge.Next))
        assertFalse(gate.begin(PageEdge.Previous))
    }

    @Test
    fun failureKeepsBothEdgesAndTheKeptRequest() {
        val gate = PagingGate()
        assertTrue(gate.begin(PageEdge.Next))
        assertFalse(gate.begin(PageEdge.Previous))
        assertEquals(PageEdge.Previous, gate.end(PageResult.Failed))
        assertTrue(gate.begin(PageEdge.Previous))
        assertNull(gate.end(PageResult.Failed))
        assertTrue(gate.begin(PageEdge.Next))
    }

    @Test
    fun resetForgetsRunningAndKeptRequests() {
        val gate = PagingGate()
        gate.begin(PageEdge.Next)
        gate.begin(PageEdge.Previous)
        gate.reset()
        assertNull(gate.end(PageResult.Items))
        assertTrue(gate.begin(PageEdge.Previous))
    }
}
