package dev.vvideo.feature.player

import dev.vvideo.ui.component.TimeLabels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.vvideo.R
import dev.vvideo.model.Media
import dev.vvideo.ui.component.ActionIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

val TopViewerGradient=Brush.verticalGradient(listOf(Color.Black.copy(alpha=.62f),Color.Transparent))
val BottomViewerGradient=Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.85f)))
@Composable
fun PlayerChrome(item:Media?,player:Player?,onBack:()->Unit,onDetails:()->Unit,onCopied:()->Unit,modifier:Modifier=Modifier,onMenuChanged:(Boolean)->Unit={}){
 var menu by remember(item?.id){mutableStateOf(false)}
 LaunchedEffect(menu){onMenuChanged(menu)}
 DisposableEffect(Unit){onDispose{onMenuChanged(false)}}
 var loop by remember(player){mutableStateOf(player?.repeatMode==Player.REPEAT_MODE_ONE)}
 val clipboard=LocalClipboardManager.current
 val pattern=stringResource(R.string.viewer_time_pattern)
 val time by produceState("",item?.effectiveTime,pattern){value=withContext(Dispatchers.Default){item?.let{TimeLabels.timestamp(it.effectiveTime,pattern)}.orEmpty()}}
 Row(modifier.fillMaxWidth().background(TopViewerGradient).statusBarsPadding().padding(bottom=32.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
  ActionIcon(R.drawable.ic_back,R.string.back,onBack,tint=Color.White)
  Column(Modifier.weight(1f).padding(horizontal=8.dp)){Text(item?.name.orEmpty(),color=Color.White,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);Text(time,color=Color.White.copy(alpha=.72f),style=MaterialTheme.typography.bodySmall)}
  Box{ActionIcon(R.drawable.ic_more,R.string.media_details,{menu=true},tint=Color.White);DropdownMenu(menu,{menu=false},shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),containerColor=Color(0xFF1B232C)){
   DropdownMenuItem(text={Text(stringResource(R.string.media_details),color=Color.White)},onClick={menu=false;onDetails()})
   if(player!=null)DropdownMenuItem(text={Text((if(loop)"✓ " else "")+stringResource(R.string.loop),color=Color.White)},onClick={loop=!loop;player.repeatMode=if(loop)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF;menu=false})
   DropdownMenuItem(text={Text(stringResource(R.string.copy_name),color=Color.White)},onClick={clipboard.setText(AnnotatedString(item?.name.orEmpty()));menu=false;onCopied()})
  }}
 }
}
