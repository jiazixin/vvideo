package dev.vvideo.ui.theme

import androidx.compose.ui.graphics.Color

/** The viewer is always dark, whatever the app theme. */
object ViewerColors {
    /** Highlights the selected speed and a speed other than normal. */
    val Accent = Color(0xFF6AB3F8)

    /** Sheets and menus over a video or photo. */
    val Sheet = Color(0xFF1B232C)

    /** A caution in the media details, such as a time read from the container. */
    val Warning = Color(0xFFE8B04B)
}

/** The bulletin keeps one dark look in both themes so it stands out from either. */
val BulletinBackground = Color(0xFF2A3542)

/** Storage page ring and legend: video, images and cache index, in that order. */
val StorageUsageColors = listOf(Color(0xFF4FA3F7), Color(0xFFF2A541), Color(0xFF8E9AA6))
