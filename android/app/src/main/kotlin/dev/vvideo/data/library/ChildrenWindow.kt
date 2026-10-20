package dev.vvideo.data.library

import dev.vvideo.model.ChildrenPage

/** Three pages; both edge cursors survive eviction. No growing cursor history. */
class ChildrenWindow {
    private val pages = ArrayDeque<ChildrenPage>()
    val items get() = pages.flatMap { it.items }
    val next get() = pages.lastOrNull()?.next.orEmpty()
    val previous get() = pages.firstOrNull()?.previous.orEmpty()

    fun reset(page: ChildrenPage) {
        pages.clear()
        pages.add(page)
    }

    fun add(page: ChildrenPage, backwards: Boolean) {
        if (backwards) {
            pages.addFirst(page)
            if (pages.size > MAX_RESIDENT_PAGES) pages.removeLast()
        } else {
            pages.addLast(page)
            if (pages.size > MAX_RESIDENT_PAGES) pages.removeFirst()
        }
    }
}
