package dev.vvideo.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.MediaKind
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move

@Composable
internal fun MediaTabs(selected: MediaKind?, onSelect: (MediaKind?) -> Unit) {
    val tabs = remember {
        listOf(
            null to R.string.tab_all,
            MediaKind.Video to R.string.tab_video,
            MediaKind.Image to R.string.tab_photo
        )
    }
    val widths = remember { mutableStateListOf(0, 0, 0) }
    val density = LocalDensity.current
    val index = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val left by animateDpAsState(with(density) { widths.take(index).sum().toDp() } + 12.dp,
        move(),
        label = "tab-left")
    val width by animateDpAsState((with(density) { widths[index].toDp() } - 24.dp).coerceAtLeast(0.dp),
        move(),
        label = "tab-width")
    val colors = LocalVVColors.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row {
            tabs.forEachIndexed { i, (kind, label) ->
                val color by animateColorAsState(
                    if (selected == kind) colors.accentText else colors.textSecondary,
                    fade(),
                    label = "tab-color"
                )
                Text(stringResource(label), Modifier.onSizeChanged { widths[i] = it.width }
                    .clickable { if (selected != kind) onSelect(kind) }
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.5.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = color)
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = left).width(width).height(3.dp)
                .background(colors.accentText, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        )
    }
}
