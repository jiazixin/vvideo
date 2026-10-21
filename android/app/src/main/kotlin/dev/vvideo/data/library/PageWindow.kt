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

    /**
     * Whether the window starts at the folder's first media. Every server page carries
     * a previous cursor, so only a page loaded from the start, or paging back to an
     * empty page, shows it.
     */
    var atStart = false
        private set

    fun reset(page: MediaPage, fromStart: Boolean = false) {
        pages.clear()
        pages.add(page)
        atStart = fromStart
    }

    fun clear() {
        pages.clear()
        atStart = false
    }

    fun append(page: MediaPage) {
        pages.addLast(page)
        while (pages.size > MAX_RESIDENT_PAGES) {
            pages.removeFirst()
            atStart = false
        }
    }

    fun prepend(page: MediaPage) {
        pages.addFirst(page)
        atStart = false
        while (pages.size > MAX_RESIDENT_PAGES) pages.removeLast()
    }

    /** Paging back found nothing before the window. */
    fun reachedStart() {
        atStart = true
    }
}
