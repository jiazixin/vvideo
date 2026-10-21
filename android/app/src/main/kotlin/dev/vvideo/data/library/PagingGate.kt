package dev.vvideo.data.library

/** How an edge request ended. */
enum class PageResult {
    /** The page had items. */
    Items,

    /** The page was empty: that edge is exhausted. */
    Empty,

    /** The request failed; both edges stay as they were. */
    Failed
}

/**
 * One edge request at a time. A viewer reports an edge once per settled page,
 * so the opposite edge asked for while a request runs is kept and handed back
 * when that request ends instead of being dropped for good.
 */
class PagingGate {
    private var running: PageEdge? = null
    private var waiting: PageEdge? = null
    private var nextOpen = true
    private var previousOpen = true

    fun reset() {
        running = null
        waiting = null
        nextOpen = true
        previousOpen = true
    }

    private fun isOpen(edge: PageEdge) = when (edge) {
        PageEdge.Previous -> previousOpen
        PageEdge.Next -> nextOpen
    }

    /** True when the caller should request [edge] now. */
    fun begin(edge: PageEdge): Boolean {
        if (!isOpen(edge)) return false
        val current = running
        if (current != null) {
            if (current != edge) waiting = edge
            return false
        }
        running = edge
        return true
    }

    /**
     * Ends the running request with [result]. Items on one side may evict the far
     * page, so the opposite edge can be asked for again; an empty page closes its
     * edge. Returns the edge to request next, if one was kept.
     */
    fun end(result: PageResult): PageEdge? {
        val edge = running ?: return null
        running = null
        when (result) {
            PageResult.Items -> if (edge == PageEdge.Previous) nextOpen = true else previousOpen =
                true

            PageResult.Empty -> if (edge == PageEdge.Previous) previousOpen = false else nextOpen =
                false

            PageResult.Failed -> Unit
        }
        return waiting.also { waiting = null }
    }
}
