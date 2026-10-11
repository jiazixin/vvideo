package dev.vvideo.data.library

import org.junit.Assert.*
import org.junit.Test

class PagingGateTest{
 // Opening the last video of a folder: the anchored page holds one item, so the
 // viewer asks for both edges at once and never repeats either request.
 @Test fun oppositeEdgeAskedDuringRequestRunsAfterIt(){val gate=PagingGate();assertTrue(gate.begin(false));assertFalse(gate.begin(true));assertEquals(true,gate.end(false));assertTrue(gate.begin(true));assertNull(gate.end(true))}
 @Test fun exhaustedEdgeStaysClosedUntilOppositeLoads(){val gate=PagingGate();assertTrue(gate.begin(false));assertNull(gate.end(false));assertFalse(gate.begin(false));assertTrue(gate.begin(true));assertNull(gate.end(true));assertTrue(gate.begin(false))}
 @Test fun repeatedSameEdgeIsNotQueued(){val gate=PagingGate();assertTrue(gate.begin(false));assertFalse(gate.begin(false));assertNull(gate.end(true));assertTrue(gate.begin(false))}
 @Test fun closedEdgeIsNeitherStartedNorKept(){val gate=PagingGate();assertTrue(gate.begin(true));assertNull(gate.end(false));assertTrue(gate.begin(false));assertFalse(gate.begin(true));assertNull(gate.end(false));assertFalse(gate.begin(false));assertFalse(gate.begin(true))}
 @Test fun failureKeepsBothEdgesAndTheKeptRequest(){val gate=PagingGate();assertTrue(gate.begin(false));assertFalse(gate.begin(true));assertEquals(true,gate.end(null));assertTrue(gate.begin(true));assertNull(gate.end(null));assertTrue(gate.begin(false))}
 @Test fun resetForgetsRunningAndKeptRequests(){val gate=PagingGate();gate.begin(false);gate.begin(true);gate.reset();assertNull(gate.end(true));assertTrue(gate.begin(true))}
}
