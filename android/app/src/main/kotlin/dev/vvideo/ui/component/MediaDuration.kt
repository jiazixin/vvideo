package dev.vvideo.ui.component

import java.util.Locale

fun mediaDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(
        Locale.ROOT,
        "%d:%02d:%02d",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60
    )
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
