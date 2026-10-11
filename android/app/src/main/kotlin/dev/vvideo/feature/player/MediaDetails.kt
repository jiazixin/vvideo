package dev.vvideo.feature.player

import dev.vvideo.ui.component.TimeLabels
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.Media
import dev.vvideo.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun MediaDetailsButton(item:Media?,modifier:Modifier=Modifier,onOpenChanged:(Boolean)->Unit={}) {
 if(item==null)return
 var open by remember(item.id){mutableStateOf(false)}
 ActionIcon(R.drawable.ic_info,R.string.media_details,{open=true;onOpenChanged(true)},modifier,Color.White)
 if(open)MediaDetailsSheet(item,"",{open=false;onOpenChanged(false)}){}
}
@Composable
fun MediaDetailsSheet(item:Media,pathLabel:String,onDismiss:()->Unit,onCopied:()->Unit) {
 val clipboard=LocalClipboardManager.current
 val context=LocalContext.current
 val pattern=stringResource(R.string.detail_time_pattern)
 val date by produceState("",item.effectiveTime,pattern){value=withContext(Dispatchers.Default){TimeLabels.timestamp(item.effectiveTime,pattern)}}
 VVBottomSheet(stringResource(R.string.media_details),onDismiss,darkViewer=true){Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){DetailRow(R.drawable.ic_info,item.name,stringResource(R.string.file_name),Modifier.weight(1f));ActionIcon(R.drawable.ic_copy,R.string.copy_name,{clipboard.setText(AnnotatedString(item.name));onCopied()},tint=Color.White)}
  DetailRow(R.drawable.ic_fullscreen,stringResource(R.string.media_resolution,item.width,item.height)+" · "+mediaDuration(item.durationMs)+" · "+Formatter.formatShortFileSize(context,item.size)+(if(item.rotation!=0)" · "+stringResource(R.string.media_rotation,item.rotation)else ""),stringResource(R.string.media_details))
  DetailRow(R.drawable.ic_clock,date,stringResource(when(item.capturedSource){"exif_datetime_original"->R.string.time_source_exif;"container_creation_time"->R.string.time_source_container;"mtime"->R.string.time_source_modified;else->R.string.time_source_unknown}))
  if(item.capturedSource=="container_creation_time")Text(stringResource(R.string.container_time_hint),color=Color(0xFFE8B04B),style=MaterialTheme.typography.bodySmall)
  if(item.capturedRaw.isNotEmpty())DetailRow(R.drawable.ic_clock,item.capturedRaw,stringResource(R.string.captured_raw_label))
  DetailRow(R.drawable.ic_folder,pathLabel.ifEmpty{stringResource(R.string.dash)},stringResource(R.string.folder_path))
 }}
}
@Composable
private fun DetailRow(icon:Int,value:String,label:String,modifier:Modifier=Modifier){Row(modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Icon(painterResource(icon),null,tint=Color.White.copy(alpha=.7f));Column(Modifier.weight(1f)){Text(value,color=Color.White,style=MaterialTheme.typography.bodyLarge);Text(label,color=Color.White.copy(alpha=.55f),style=MaterialTheme.typography.bodySmall)}}}
