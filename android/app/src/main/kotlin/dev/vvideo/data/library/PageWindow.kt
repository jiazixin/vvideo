package dev.vvideo.data.library

import dev.vvideo.model.MediaPage

/** At most this many server pages stay resident in a window. */
internal const val MAX_RESIDENT_PAGES = 3

// At most three server pages stay resident; stable keys preserve visible media.
class PageWindow {
    private val pages = ArrayDeque<MediaPage>()
    val items get() = pages.flatMap { it.items }
    val next get() = pages.lastOrNull()?.next.orEmpty()
    val previous get() = pages.firstOrNull()?.previous.orEmpty()
    val retainedPages get() = pages.size

    fun reset(page: MediaPage) {
        pages.clear()
        pages.add(page)
    }

    fun clear() {
        pages.clear()
    }

    fun append(page: MediaPage) {
        pages.addLast(page)
        while (pages.size > MAX_RESIDENT_PAGES) pages.removeFirst()
    }

    fun prepend(page: MediaPage) {
        pages.addFirst(page)
        while (pages.size > MAX_RESIDENT_PAGES) pages.removeLast()
    }
}
