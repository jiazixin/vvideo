package dev.vvideo.feature.player

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.CaptureSource
import dev.vvideo.model.Media
import dev.vvideo.model.MediaKind
import dev.vvideo.ui.component.TimeLabels
import dev.vvideo.ui.component.VVBottomSheet
import dev.vvideo.ui.component.mediaDuration
import dev.vvideo.ui.theme.ViewerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Panel = RoundedCornerShape(14.dp)
private val PanelColor = Color.White.copy(alpha = .07f)
private val Secondary = Color.White.copy(alpha = .56f)

/**
 * Name first, then the three numbers people compare at a glance, then where the
 * sort time comes from and where the file lives.
 */
@Composable
fun MediaDetailsSheet(item: Media, pathLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pattern = stringResource(R.string.detail_time_pattern)
    val date by produceState("", item.effectiveTime, pattern) {
        value =
            withContext(Dispatchers.Default) { TimeLabels.timestamp(item.effectiveTime, pattern) }
    }
    VVBottomSheet(stringResource(R.string.media_details), onDismiss, darkViewer = true) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                item.name,
                Modifier.padding(horizontal = 4.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val resolution = if (item.width > 0 && item.height > 0) stringResource(
                    R.string.media_resolution,
                    item.width,
                    item.height
                ) else stringResource(R.string.dash)
                Spec(
                    resolution,
                    if (item.rotation != 0) stringResource(
                        R.string.detail_resolution_rotated,
                        item.rotation
                    ) else stringResource(R.string.detail_resolution),
                    Modifier.weight(1.35f)
                )
                if (item.kind == MediaKind.Video) Spec(
                    if (item.durationMs > 0) mediaDuration(item.durationMs) else stringResource(
                        R.string.dash
                    ), stringResource(R.string.detail_duration), Modifier.weight(1f)
                )
                Spec(
                    Formatter.formatShortFileSize(context, item.size),
                    stringResource(R.string.detail_size),
                    Modifier.weight(1f)
                )
            }
            Column(Modifier.fillMaxWidth().background(PanelColor, Panel)) {
                Field(
                    stringResource(R.string.capture_time), date, stringResource(
                        R.string.detail_time_source, stringResource(
                            when (item.capturedSource) {
                                CaptureSource.ExifOriginal -> R.string.time_source_exif
                                CaptureSource.ContainerCreation -> R.string.time_source_container
                                CaptureSource.Modified -> R.string.time_source_modified
                                CaptureSource.Unknown -> R.string.time_source_unknown
                            }
                        )
                    ),
                    if (item.capturedSource == CaptureSource.ContainerCreation) {
                        stringResource(R.string.container_time_hint)
                    } else {
                        null
                    }
                )
                if (item.capturedRaw.isNotEmpty()) {
                    Divider()
                    Field(stringResource(R.string.captured_raw_label), item.capturedRaw)
                }
                Divider()
                Field(
                    stringResource(R.string.folder_path),
                    pathLabel.ifEmpty { stringResource(R.string.dash) })
            }
        }
    }
}

@Composable
private fun Spec(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.background(PanelColor, Panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            value,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            color = Secondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Field(label: String, value: String, note: String? = null, warning: String? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = Secondary, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, color = Secondary, style = MaterialTheme.typography.bodySmall)
        if (warning != null) Text(
            warning,
            color = ViewerColors.Warning,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        Modifier.padding(start = 14.dp),
        thickness = .5.dp,
        color = Color.White.copy(alpha = .1f)
    )
}
