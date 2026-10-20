package dev.vvideo.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vvideo.R
import dev.vvideo.ui.theme.LocalVV
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move
import kotlinx.coroutines.delay

private val ButtonShape = RoundedCornerShape(12.dp)
private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun VVButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, kind: ButtonKind = ButtonKind.Primary) {
    val c = LocalVV.current
    val colors = ButtonDefaults.buttonColors(
        containerColor = when(kind) { ButtonKind.Primary -> c.accentFill; ButtonKind.Secondary -> c.accentSoft; ButtonKind.Danger -> Color.Transparent },
        contentColor = when(kind) { ButtonKind.Primary -> Color.White; ButtonKind.Secondary -> c.accentText; ButtonKind.Danger -> c.error },
    )
    Button(onClick, modifier.heightIn(min = 50.dp), enabled && !busy, shape = ButtonShape, colors = colors) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text)
    }
}

@Composable
fun Bulletin(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = ButtonShape, color = Color(0xFF2A3542), shadowElevation = 4.dp) {
        Row(Modifier.heightIn(min = 52.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_info), null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable
fun BulletinHost(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) { if (message != null) { delay(3000); dismiss() } }
    // The last message stays readable while the bulletin leaves.
    val shown = remember { object { var text = "" } }
    if (message != null) shown.text = message
    AnimatedVisibility(message != null, modifier, enter = fadeIn(fade()) + slideInVertically(move()) { it / 2 }, exit = fadeOut(fade()) + slideOutVertically(move()) { it / 2 }) {
        Bulletin(shown.text, Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 16.dp))
    }
}
@Composable
fun OfflineBanner(message: String = stringResource(R.string.offline_banner), onRetry: () -> Unit) {
    val dark = LocalVV.current.isDark
    val foreground = if(dark) Color(0xFFF1D59C) else Color(0xFF7A4E00)
    Row(Modifier.fillMaxWidth().background(if(dark) Color(0xFF3A2F17) else Color(0xFFFFF4DE)).heightIn(min=44.dp).padding(start=12.dp), verticalAlignment=Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_wifi_off), null, tint=foreground, modifier=Modifier.size(22.dp))
        Text(message, Modifier.weight(1f).padding(horizontal=10.dp), color=foreground, style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=onRetry) { Text(stringResource(R.string.retry),color=foreground) }
    }
}
@Composable
fun EmptyState(title: String, modifier: Modifier=Modifier, explanation: String?=null, action: String?=null, onAction: ()->Unit={}) {
    val c=LocalVV.current
    Box(modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=280.dp).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(72.dp).background(c.bgRaised,CircleShape),contentAlignment=Alignment.Center) { Icon(painterResource(R.drawable.ic_folder),null,tint=c.icon,modifier=Modifier.size(32.dp)) }
            Text(title,style=MaterialTheme.typography.titleSmall,color=c.textPrimary)
            if(explanation!=null)Text(explanation,style=MaterialTheme.typography.bodyMedium,color=c.textSecondary)
            if(action!=null)VVButton(action,onAction,kind=ButtonKind.Secondary)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VVBottomSheet(title: String, onDismiss: ()->Unit, darkViewer: Boolean=false, content: @Composable ColumnScope.()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,shape=SheetShape,containerColor=if(darkViewer) Color(0xFF1B232C) else LocalVV.current.bgRaised,contentColor=if(darkViewer) Color.White else LocalVV.current.textPrimary,
        dragHandle={Box(Modifier.padding(top=8.dp,bottom=12.dp).size(36.dp,4.dp).background(LocalVV.current.textTertiary,CircleShape))}) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium.copy(fontSize=18.sp,lineHeight=24.sp))
            ActionIcon(R.drawable.ic_close,R.string.close,onDismiss,tint=if(darkViewer)Color.White else LocalVV.current.icon)
        }
        Column(Modifier.fillMaxWidth().padding(bottom=16.dp),content=content)
    }
}
