package dev.vvideo.feature.player

import dev.vvideo.model.Media

internal data class PlayerUiState(
    val leaving: Boolean = false,
    val presentation: ViewerPresentation = ViewerPresentation(),
    val endReached: Boolean = false,
    val pathLabel: String = "",
    val items: List<Media> = emptyList(),
    val error: Boolean = false,
    val initialId: String = "",
)

/** Rendering values derived by the route from player events and local gestures. */
internal data class ViewerPresentation(
    val currentId: String = "",
    val controlsVisible: Boolean = true,
    val swiping: Boolean = false,
    val showPlay: Boolean = false,
    val holding: Boolean = false,
    val edgeLevel: Float? = null,
    val adjustingBrightness: Boolean = false,
    val seekSeconds: Int = 0,
    val seekForward: Boolean = true,
    val bulletin: String? = null,
    val buffering: Boolean = false,
    val detailsOpen: Boolean = false,
)
