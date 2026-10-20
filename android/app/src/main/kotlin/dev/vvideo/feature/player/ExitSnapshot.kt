package dev.vvideo.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Holds the last value a viewer page showed before it started leaving. */
class ExitSnapshot<T>(initial: T) {
    private var held = initial
    fun resolve(live: T, leaving: Boolean): T {
        if (!leaving) held = live
        return held
    }
}

// PlayerViewModel.leave() clears its state at once, while the route's exit
// transition keeps the page composed. The leaving page draws from its own
// snapshot instead of delaying that cleanup.
@Composable
fun <T> retainedWhileLeaving(live: T, leaving: Boolean): T =
    remember { ExitSnapshot(live) }.resolve(live, leaving)
