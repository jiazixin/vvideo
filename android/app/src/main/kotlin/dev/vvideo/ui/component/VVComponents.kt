package dev.vvideo.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vvideo.R
import dev.vvideo.ui.theme.BulletinBackground
import dev.vvideo.ui.theme.LocalVVColors
import dev.vvideo.ui.theme.ViewerColors
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move
import kotlinx.coroutines.delay

/** A bulletin leaves on its own after this long. */
private const val BULLETIN_VISIBLE_MS = 3000L

private val ButtonShape = RoundedCornerShape(12.dp)
private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

enum class ButtonKind { Primary, Secondary }

@Composable
fun VVButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    kind: ButtonKind = ButtonKind.Primary
) {
    val colors = LocalVVColors.current
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = when (kind) {
            ButtonKind.Primary -> colors.accentFill
            ButtonKind.Secondary -> colors.accentSoft
        },
        contentColor = when (kind) {
            ButtonKind.Primary -> Color.White
            ButtonKind.Secondary -> colors.accentText
        },
    )
    Button(
        onClick,
        modifier.heightIn(min = 50.dp),
        enabled && !busy,
        shape = ButtonShape,
        colors = buttonColors
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}

@Composable
fun Bulletin(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = ButtonShape,
        color = BulletinBackground,
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BulletinHost(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        if (message != null) {
            delay(BULLETIN_VISIBLE_MS)
            dismiss()
        }
    }
    // The last message stays readable while the bulletin leaves.
    val shown = remember {
        object {
            var text = ""
        }
    }
    if (message != null) shown.text = message
    AnimatedVisibility(
        message != null,
        modifier,
        enter = fadeIn(fade()) + slideInVertically(move()) { it / 2 },
        exit = fadeOut(fade()) + slideOutVertically(move()) { it / 2 }) {
        Bulletin(
            shown.text,
            Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 16.dp)
        )
    }
}

@Composable
fun OfflineBanner(message: String = stringResource(R.string.offline_banner), onRetry: () -> Unit) {
    val colors = LocalVVColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.bannerBackground)
            .heightIn(min = 44.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_wifi_off),
            null,
            tint = colors.bannerContent,
            modifier = Modifier.size(22.dp)
        )
        Text(
            message,
            Modifier.weight(1f).padding(horizontal = 10.dp),
            color = colors.bannerContent,
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onRetry) {
            Text(
                stringResource(R.string.retry),
                color = colors.bannerContent
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    val colors = LocalVVColors.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 280.dp).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(72.dp).background(colors.bgRaised, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_folder),
                    null,
                    tint = colors.icon,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            if (action != null) VVButton(action, onAction, kind = ButtonKind.Secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VVBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    darkViewer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalVVColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = if (darkViewer) ViewerColors.Sheet else colors.bgRaised,
        contentColor = if (darkViewer) Color.White else colors.textPrimary,
        dragHandle = {
            Box(
                Modifier.padding(top = 8.dp, bottom = 12.dp).size(36.dp, 4.dp)
                    .background(colors.textTertiary, CircleShape)
            )
        }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    lineHeight = 24.sp
                )
            )
            ActionIcon(
                R.drawable.ic_close,
                R.string.close,
                onDismiss,
                tint = if (darkViewer) Color.White else colors.icon
            )
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), content = content)
    }
}
