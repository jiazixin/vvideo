package dev.vvideo.data.library

import dev.vvideo.model.ChildrenPage
import dev.vvideo.model.Directory

/** Three pages; both edge cursors survive eviction. No growing cursor history. */
class ChildrenWindow {
    private val pages = ArrayDeque<ChildrenPage>()
    val items: List<Directory> get() = pages.flatMap { it.items }
    val next: String get() = pages.lastOrNull()?.next.orEmpty()
    val previous: String get() = pages.firstOrNull()?.previous.orEmpty()

    fun reset(page: ChildrenPage) {
        pages.clear()
        pages.add(page)
    }

    /** Adds [page] at [edge], evicting the page at the other end beyond [MAX_RESIDENT_PAGES]. */
    fun add(page: ChildrenPage, edge: PageEdge) {
        when (edge) {
            PageEdge.Previous -> {
                pages.addFirst(page)
                if (pages.size > MAX_RESIDENT_PAGES) pages.removeLast()
            }

            PageEdge.Next -> {
                pages.addLast(page)
                if (pages.size > MAX_RESIDENT_PAGES) pages.removeFirst()
            }
        }
    }
}
