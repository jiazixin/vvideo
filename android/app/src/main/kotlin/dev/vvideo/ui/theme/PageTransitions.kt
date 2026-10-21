package dev.vvideo.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/** Durations of page and viewer transitions, in ms. */
object PageMotion {
    const val ENTER = 260
    const val RETURN = 220
    const val VIEWER_OPEN = 180
    const val VIEWER_CLOSE = 200
}

/** The covered page shifts by its width divided by this while another slides over it. */
private const val UNDERLAY_SHIFT_DIVISOR = 4

/** Opacity of the covered page at the end of a slide over it. */
private const val UNDERLAY_ALPHA = .85f

/**
 * A page slides in from the right over the one it covers, which shifts a little
 * left and dims slightly; [returning] plays the same slide backwards.
 */
fun pageSlide(returning: Boolean, durationMs: Int): ContentTransform {
    val slide = tween<IntOffset>(durationMs, easing = FastOutSlowInEasing)
    val enter = slideInHorizontally(slide) { if (returning) -it / UNDERLAY_SHIFT_DIVISOR else it } +
            fadeIn(tween(durationMs), initialAlpha = if (returning) UNDERLAY_ALPHA else 1f)
    val exit = slideOutHorizontally(slide) { if (returning) it else -it / UNDERLAY_SHIFT_DIVISOR } +
            fadeOut(tween(durationMs), targetAlpha = if (returning) 1f else UNDERLAY_ALPHA)
    return enter togetherWith exit
}
