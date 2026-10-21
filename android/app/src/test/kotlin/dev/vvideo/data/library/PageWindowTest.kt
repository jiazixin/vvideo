package dev.vvideo.data.library

import dev.vvideo.model.*
import dev.vvideo.model.CaptureSource
import dev.vvideo.model.MediaKind
import dev.vvideo.model.MediaStatus
import org.junit.Assert.*
import org.junit.Test

class PageWindowTest {
    private fun page(index: Int): MediaPage {
        val items = List(100) { j ->
            Media(
                "${index * 100 + j}",
                "v",
                "l",
                1,
                "n",
                MediaKind.Video,
                1,
                1,
                0,
                0,
                1,
                0,
                0,
                MediaStatus.Ready,
                "",
                CaptureSource.Modified
            )
        }
        return MediaPage(
            Directory(1, "l", 0, "root", 1),
            items,
            "next$index",
            "prev$index",
            "",
            false,
            true
        )
    }

    @Test
    fun traversalAndReverseKeepOnlyThreePages() {
        val window = PageWindow()
        window.reset(page(0))
        for (i in 1..50) window.append(page(i))
        assertEquals(3, window.retainedPages)
        assertEquals(300, window.items.size)
        assertEquals("4800", window.items.first().id)
        assertEquals("next50", window.next)
        window.prepend(page(47))
        assertEquals("4700", window.items.first().id)
        assertEquals("4999", window.items.last().id)
        assertEquals("prev47", window.previous)
    }

    @Test
    fun resetReleasesOldMetadata() {
        val window = PageWindow()
        window.reset(page(0))
        window.append(page(1))
        window.reset(page(30))
        assertEquals(1, window.retainedPages)
        assertEquals("3000", window.items.first().id)
    }
}
