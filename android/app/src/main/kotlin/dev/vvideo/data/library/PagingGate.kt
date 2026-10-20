package dev.vvideo.data.library

/**
 * One edge request at a time. A viewer reports an edge once per settled page,
 * so the opposite edge asked for while a request runs is kept and handed back
 * when that request ends instead of being dropped for good.
 */
class PagingGate {
    private var running: Boolean? = null
    private var waiting: Boolean? = null
    private var next = true
    private var previous = true

    fun reset() {
        running = null
        waiting = null
        next = true
        previous = true
    }

    private fun open(previous: Boolean) = if (previous) this.previous else next

    /** True when the caller should request that edge now. */
    fun begin(previous: Boolean): Boolean {
        if (!open(previous)) return false
        if (running != null) {
            if (running != previous) waiting = previous
            return false
        }
        running = previous
        return true
    }

    /**
     * Ends the running request: [loaded] is true for a page with items, false for
     * an empty one (that edge is exhausted) and null for a failure. Items on one
     * side may evict the far page, so the opposite edge can be asked for again.
     * Returns the edge to request next, if one was kept.
     */
    fun end(loaded: Boolean?): Boolean? {
        val edge = running ?: return null
        running = null
        when (loaded) {
            true -> if (edge) next = true else previous = true
            false -> if (edge) previous = false else next = false
            null -> Unit
        }
        return waiting.also { waiting = null }
    }
}
