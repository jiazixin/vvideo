package dev.vvideo.feature.library

import androidx.compose.runtime.Composable
import dev.vvideo.model.Media

/** Image rendering supplied by the route; previews supply local content. */
internal typealias ThumbnailContent = @Composable (Media, Boolean, (Boolean) -> Unit) -> Unit
