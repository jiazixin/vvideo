package dev.vvideo.feature.player

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.Media
import dev.vvideo.ui.component.*
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
                if (item.kind == "video") Spec(
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
                                "exif_datetime_original" -> R.string.time_source_exif
                                "container_creation_time" -> R.string.time_source_container
                                "mtime" -> R.string.time_source_modified
                                else -> R.string.time_source_unknown
                            }
                        )
                    ),
                    if (item.capturedSource == "container_creation_time") {
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
            color = Color(0xFFE8B04B),
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
