package dev.vvideo.feature.player

import dev.vvideo.data.library.PageEdge

/** Within this many items of the end of the loaded queue, the next page is asked for. */
private const val NEXT_PAGE_DISTANCE = 3

/** Before this index in the loaded queue, the previous page is asked for. */
private const val PREVIOUS_PAGE_DISTANCE = 2

/** Asks [onPage] for the pages next to item [index] of a loaded queue of [count] items. */
internal fun requestNearbyPages(index: Int, count: Int, onPage: (PageEdge) -> Unit) {
    if (index >= count - NEXT_PAGE_DISTANCE) onPage(PageEdge.Next)
    if (index < PREVIOUS_PAGE_DISTANCE && count > 0) onPage(PageEdge.Previous)
}
