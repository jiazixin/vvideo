package dev.vvideo.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.ui.theme.LocalVV
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move

@Composable
internal fun MediaTabs(selected: String, onSelect: (String) -> Unit) {
    val tabs = remember {
        listOf(
            "" to R.string.tab_all,
            "video" to R.string.tab_video,
            "image" to R.string.tab_photo
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
    val colors = LocalVV.current
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
