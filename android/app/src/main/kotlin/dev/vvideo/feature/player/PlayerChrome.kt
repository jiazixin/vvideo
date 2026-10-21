package dev.vvideo.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.Media
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.TimeLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val TopViewerGradient =
    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .62f), Color.Transparent))

val BottomViewerGradient =
    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))

/**
 * Back · name and time · details. Details is the only remaining action, so it
 * is a direct button rather than a one-item menu.
 */
@Composable
fun PlayerChrome(
    item: Media?,
    onBack: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pattern = stringResource(R.string.viewer_time_pattern)
    // Keyed by the item so a new page never shows the previous page's time while its own is formatted.
    val time by produceState("", item?.id, item?.effectiveTime, pattern) {
        value = ""
        value = withContext(Dispatchers.Default) {
            item?.let {
                TimeLabels.timestamp(
                    it.effectiveTime,
                    pattern
                )
            }.orEmpty()
        }
    }
    Row(
        modifier.fillMaxWidth().background(TopViewerGradient).statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, bottom = 32.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIcon(R.drawable.ic_back, R.string.back, onBack, tint = Color.White)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                item?.name.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Always laid out, so the name does not shift while the time is formatted.
            Text(
                time,
                color = Color.White.copy(alpha = .72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (item != null) ActionIcon(
            R.drawable.ic_info,
            R.string.media_details,
            onDetails,
            tint = Color.White
        )
    }
}
