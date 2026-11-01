package dev.vvideo.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.vvideo.data.settings.Connection

@Composable
internal fun PhotoRoute(
    player: PlayerViewModel,
    connection: Connection,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    leaving: Boolean,
) {
    val live by player.state.collectAsStateWithLifecycle()
    PhotoScreen(
        state = retainedWhileLeaving(live, leaving).copy(leaving = leaving),
        photoContent = { item, front, resting, zoom, modifier ->
            PhotoPage(item, connection, imageLoader, front, resting, zoom, modifier)
        },
        onBack = onBack,
        onRetry = onRetry,
        onSelected = player::selected,
        onPage = player::page,
    )
}
