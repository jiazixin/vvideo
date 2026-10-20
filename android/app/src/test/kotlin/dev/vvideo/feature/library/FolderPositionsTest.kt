package dev.vvideo.feature.library

import org.junit.Assert.*
import org.junit.Test

class FolderPositionsTest {
    // A parent that lists only subfolders has no bookmark position; going back to it
    // from a subfolder used to start the list at its top.
    @Test
    fun parentKeepsTheSubfolderItWasLeftAt() {
        val positions = FolderPositions()
        positions.leave(1, FolderPosition("folder:7", 120, 1080))
        assertEquals(FolderPosition("folder:7", 120, 1080), positions[1])
        assertNull(positions[2])
    }

    // A page left before it reported a position must not erase the one it had.
    @Test
    fun blankPositionKeepsTheEarlierOne() {
        val positions = FolderPositions()
        positions.leave(1, FolderPosition("folder:7", 120, 1080))
        positions.leave(1, FolderPosition("", 0, 0))
        positions.leave(0, FolderPosition("folder:9", 0, 1080))
        assertEquals("folder:7", positions[1]?.key)
        assertNull(positions[0])
    }

    @Test
    fun forgottenFolderStartsOver() {
        val positions = FolderPositions()
        positions.leave(1, FolderPosition("album:m1", 0, 1080))
        positions.forget(1)
        assertNull(positions[1])
    }

    @Test
    fun onlyRecentFoldersAreKept() {
        val positions = FolderPositions()
        for (id in 1L..64L) positions.leave(id, FolderPosition("folder:$id", 0, 1080))
        // Reading a folder makes it recent, so the next eviction takes folder 2.
        assertNotNull(positions[1])
        positions.leave(65, FolderPosition("folder:65", 0, 1080))
        assertNotNull(positions[1])
        assertNull(positions[2])
        assertNotNull(positions[65])
    }
}
