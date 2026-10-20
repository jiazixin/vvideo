package dev.vvideo.data.library

import dev.vvideo.model.*
import org.junit.Assert.*
import org.junit.Test

class ChildrenWindowTest {
    private fun page(n: Int) = ChildrenPage(
        List(100) { Directory((n * 100 + it).toLong(), "lib", 1, "folder-$it", 1) },
        "next-$n",
        if (n == 0) "" else "previous-$n"
    )

    @Test
    fun evictionKeepsBothEdgesRecoverable() {
        val window = ChildrenWindow()
        window.reset(page(0))
        for (n in 1..9) window.add(page(n), false)
        assertEquals(300, window.items.size)
        assertEquals(700L, window.items.first().id)
        assertEquals("previous-7", window.previous)
        for (n in 6 downTo 0) window.add(page(n), true)
        assertEquals(0L, window.items.first().id)
        assertEquals("next-2", window.next)
        assertEquals("", window.previous)
        assertEquals(300, window.items.map { it.id }.distinct().size)
    }
}
