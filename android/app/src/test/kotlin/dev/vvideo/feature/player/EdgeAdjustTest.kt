package dev.vvideo.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeAdjustTest {
    @Test
    fun edgeStripsCoverTheOuterFifths() {
        assertEquals(EdgeControl.Brightness, edgeControlAt(0f, 1000f))
        assertEquals(EdgeControl.Brightness, edgeControlAt(199f, 1000f))
        assertNull(edgeControlAt(200f, 1000f))
        assertNull(edgeControlAt(500f, 1000f))
        assertNull(edgeControlAt(800f, 1000f))
        assertEquals(EdgeControl.Volume, edgeControlAt(801f, 1000f))
        assertNull(edgeControlAt(10f, 0f))
    }

    @Test
    fun draggingUpRaisesAndDownLowersWithinRange() {
        // 60% of a 1000 px viewer spans the whole range.
        assertEquals(0.8f, adjustedLevel(0.5f, -180f, 1000f), 0.0001f)
        assertEquals(0.2f, adjustedLevel(0.5f, 180f, 1000f), 0.0001f)
        assertEquals(1f, adjustedLevel(0.9f, -600f, 1000f), 0f)
        assertEquals(0f, adjustedLevel(0.1f, 600f, 1000f), 0f)
        assertEquals(0.4f, adjustedLevel(0.4f, 50f, 0f), 0f)
    }

    @Test
    fun volumeAndPercentRoundToTheNearestStep() {
        assertEquals(0, volumeIndex(0f, 15))
        assertEquals(8, volumeIndex(0.5f, 15))
        assertEquals(15, volumeIndex(1.2f, 15))
        assertEquals(33, levelPercent(0.334f))
        assertEquals(100, levelPercent(1f))
    }
}
