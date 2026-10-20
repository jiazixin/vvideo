package dev.vvideo.feature.library

import dev.vvideo.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryKeysTest {
    private val dir = Directory(5, "lib", 1, "folder", 2)
    private val item =
        Media("m1", "v", "lib", 5, "a.mp4", "video", 1920, 1080, 0, 1000, 10, 0, 0, "ready", "", "")

    private fun page(found: Boolean, items: List<Media>) =
        MediaPage(dir, items, "", "", "", found, true)

    @Test
    fun missingAnchorWithNearbyMediaIsReported() {
        assertTrue(anchorMoved("gone", page(false, listOf(item))))
    }

    @Test
    fun foundAnchorOrNoSavedPositionIsNotReported() {
        assertFalse(anchorMoved("m1", page(true, listOf(item))))
        assertFalse(anchorMoved("", page(false, listOf(item))))
    }

    // An empty folder keeps its stale bookmark; every visit used to repeat the notice.
    @Test
    fun emptyFolderIsNotReported() {
        assertFalse(anchorMoved("gone", page(false, emptyList())))
    }
}
