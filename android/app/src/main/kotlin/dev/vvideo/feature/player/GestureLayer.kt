package dev.vvideo.feature.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player

/** Tap recognition yields vertical movement to the pager. Only the seek bar consumes drags. */
fun Modifier.viewerGestures(player:Player,onTap:()->Unit,onHold:(Boolean)->Unit,onSeek:(Boolean)->Unit):Modifier=pointerInput(player){
 var held=false
 var originalSpeed=1f
 detectTapGestures(
  onTap={onTap()},
  onDoubleTap={point->when{point.x<size.width/3f->onSeek(false);point.x>size.width*2f/3f->onSeek(true);else->if(player.isPlaying)player.pause()else player.play()}},
  onLongPress={originalSpeed=player.playbackParameters.speed;held=true;player.setPlaybackSpeed(2f);onHold(true)},
  onPress={try{tryAwaitRelease()}finally{if(held){player.setPlaybackSpeed(originalSpeed);held=false;onHold(false)}}},
 )
}
