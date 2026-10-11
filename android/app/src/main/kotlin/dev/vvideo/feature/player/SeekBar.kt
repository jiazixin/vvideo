package dev.vvideo.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.media3.common.Player
import dev.vvideo.R
import dev.vvideo.ui.component.*
import kotlinx.coroutines.delay

@Composable
fun SeekBar(player:Player,mediaId:String,enabled:Boolean,visible:Boolean,landscape:Boolean,onLandscape:()->Unit,modifier:Modifier=Modifier,onDragging:(Boolean)->Unit){
 var position by remember(mediaId){mutableLongStateOf(0)};var duration by remember(mediaId){mutableLongStateOf(0)};var buffered by remember(mediaId){mutableLongStateOf(0)}
 var playing by remember(player){mutableStateOf(player.isPlaying)};var speed by remember(player){mutableFloatStateOf(player.playbackParameters.speed)}
 var dragging by remember(mediaId){mutableStateOf(false)};var target by remember(mediaId){mutableFloatStateOf(0f)}
 DisposableEffect(player){val listener=object:Player.Listener{override fun onIsPlayingChanged(value:Boolean){playing=value};override fun onPlaybackParametersChanged(value:androidx.media3.common.PlaybackParameters){speed=value.speed}};player.addListener(listener);onDispose{player.removeListener(listener)}}
 // Progress reads stay in this subtree, never the page or pager.
 LaunchedEffect(player,mediaId){while(true){duration=player.duration.coerceAtLeast(0);position=player.currentPosition.coerceAtLeast(0);buffered=player.bufferedPosition.coerceAtLeast(0);delay(250)}}
 DisposableEffect(mediaId){onDispose{onDragging(false)}}
 val fraction=if(dragging)target else if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f)else 0f
 if(!visible){Canvas(modifier.fillMaxWidth().height(2.dp)){drawRect(Color.White.copy(alpha=.2f));drawRect(Color.White.copy(alpha=.85f),size=size.copy(width=size.width*fraction))}}
 val seekDescription=stringResource(R.string.seek)
 val speedDescription=stringResource(R.string.speed)
 androidx.compose.animation.AnimatedVisibility(visible,enter=androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),exit=androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))){
 Column(modifier.padding(horizontal=16.dp)){
  BoxWithConstraints(Modifier.fillMaxWidth()){
   if(dragging)Text(mediaDuration((target*duration).toLong()),Modifier.offset(x=(maxWidth*target-24.dp).coerceIn(0.dp,(maxWidth-48.dp).coerceAtLeast(0.dp)),y=(-28).dp).background(Color.Black.copy(alpha=.65f),RoundedCornerShape(10.dp)).padding(horizontal=8.dp,vertical=4.dp),color=Color.White,style=MaterialTheme.typography.labelMedium)
   Canvas(Modifier.fillMaxWidth().height(40.dp).semantics{
    contentDescription=seekDescription;progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f)
    setProgress{value->if(enabled&&duration>0){player.seekTo((value.coerceIn(0f,1f)*duration).toLong());true}else false}
   }.pointerInput(enabled,duration){if(enabled&&duration>0)detectDragGestures(onDragStart={p->dragging=true;target=(p.x/size.width).coerceIn(0f,1f);onDragging(true)},onDragEnd={player.seekTo((target*duration).toLong());dragging=false;onDragging(false)},onDragCancel={dragging=false;onDragging(false)}){change,_->change.consume();target=(change.position.x/size.width).coerceIn(0f,1f)}}.pointerInput(enabled,duration){if(enabled&&duration>0)detectTapGestures{p->player.seekTo(((p.x/size.width).coerceIn(0f,1f)*duration).toLong())}}){
    val y=size.height/2;val stroke=(if(dragging)5.dp else 3.dp).toPx()
    drawLine(Color.White.copy(alpha=.22f),Offset(0f,y),Offset(size.width,y),stroke)
    drawLine(Color.White.copy(alpha=.45f),Offset(0f,y),Offset(size.width*(if(duration>0)buffered.toFloat()/duration else 0f).coerceIn(0f,1f),y),stroke)
    drawLine(Color.White,Offset(0f,y),Offset(size.width*fraction,y),stroke)
    drawCircle(Color.White,(if(dragging)9.dp else 7.dp).toPx(),Offset(size.width*fraction,y))
   }
  }
  Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
   ActionIcon(if(playing)R.drawable.ic_pause else R.drawable.ic_play,if(playing)R.string.pause else R.string.play,{if(playing)player.pause()else player.play()},tint=Color.White,enabled=enabled)
   Text(mediaDuration(if(dragging)(target*duration).toLong()else position),color=Color.White,style=MaterialTheme.typography.labelLarge)
   Text(" / "+mediaDuration(duration),color=Color.White.copy(alpha=.6f),style=MaterialTheme.typography.labelLarge)
   Spacer(Modifier.weight(1f))
   OutlinedButton(onClick={player.setPlaybackSpeed(when(speed){1f->1.5f;1.5f->2f;2f->.5f;else->1f})},border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.65f)),contentPadding=PaddingValues(horizontal=12.dp),shape=RoundedCornerShape(10.dp),modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription=speedDescription}){Text(stringResource(R.string.speed_value,if(speed%1f==0f)speed.toInt().toString()else speed.toString()),color=Color.White)}
   if(landscape)ActionIcon(R.drawable.ic_fullscreen,R.string.fullscreen,onLandscape,tint=Color.White)
  }
 }
 }
}
