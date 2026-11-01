package dev.vvideo.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.vvideo.R
import dev.vvideo.ui.component.BulletinHost

/** Player chrome renders from values; the route binds the surface, pager and seek controls. */
@Composable
internal fun PlayerScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    onDismissDetails: () -> Unit,
    onPlay: () -> Unit,
    onDismissBulletin: () -> Unit,
    surface: @Composable () -> Unit,
    pages: @Composable () -> Unit,
    seekControls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = state.presentation
    val currentItem = state.items.firstOrNull { it.id == view.currentId }
    if (view.detailsOpen && currentItem != null) {
        MediaDetailsSheet(currentItem, state.pathLabel, onDismissDetails)
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        surface()
        pages()
        FadingChrome(
            view.controlsVisible && !view.swiping,
            currentItem,
            onBack,
            onDetails,
            Modifier.align(Alignment.TopCenter),
        )
        ViewerFade(!view.swiping, Modifier.align(Alignment.BottomCenter)) {
            ControlsScrim(shaded = view.controlsVisible) { seekControls() }
        }
        if (view.showPlay) PlayButton(onPlay, Modifier.align(Alignment.Center))
        if (view.holding) SpeedHoldLabel(Modifier.align(Alignment.TopCenter))
        view.edgeLevel?.let { level ->
            EdgeLevelLabel(
                if (view.adjustingBrightness) EdgeControl.Brightness else EdgeControl.Volume,
                level,
                Modifier.align(Alignment.TopCenter),
            )
        }
        if (view.seekSeconds > 0) {
            SeekFeedback(
                view.seekSeconds,
                view.seekForward,
                Modifier.align(if (view.seekForward) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }
        BulletinHost(view.bulletin, onDismissBulletin, Modifier.align(Alignment.BottomCenter))
        if (view.buffering) BufferingIndicator(Modifier.align(Alignment.Center))
        if (state.error && state.items.isEmpty()) {
            Text(
                stringResource(R.string.playback_error),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, fontScale = 1.5f)
@Composable
private fun PlayerScreenPreview() {
    dev.vvideo.ui.theme.VVideoTheme {
        PlayerScreen(
            state = PlayerUiState(presentation = ViewerPresentation(showPlay = true, seekSeconds = 20)),
            onBack = {}, onDetails = {}, onDismissDetails = {}, onPlay = {}, onDismissBulletin = {},
            surface = {}, pages = {}, seekControls = {},
        )
    }
}
