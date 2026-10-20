package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.library.album.AlbumLayoutCalculator
import dev.vvideo.model.*
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.mediaDuration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class, androidx.activity.ExperimentalActivityApi::class)
@Composable
fun LibraryRoute(vm:LibraryViewModel,thumbnail:(Connection,Media)->String,c:Connection,loader:ImageLoader,onSettings:()->Unit,onMedia:(Media)->Unit,onInteraction:(Boolean)->Unit,onDetails:(Media)->Unit={},onConnection:()->Unit=onSettings) {
 val state by vm.state.collectAsStateWithLifecycle()
 val list=key(state.directory?.id){rememberLazyListState()}
 var predictive by remember{mutableFloatStateOf(0f)}
 val animations=dev.vvideo.ui.theme.LocalAnimations.current
 PredictiveBackHandler(state.directory!=null){events->try{events.collect{predictive=it.progress};vm.back()}catch(_:CancellationException){}finally{predictive=0f}}
 val owner=LocalLifecycleOwner.current
 val width=with(LocalDensity.current){LocalConfiguration.current.screenWidthDp.dp.roundToPx()}
 val current by rememberUpdatedState(state)
 var restoring by remember{mutableStateOf(false)}
 // Draw order of the two pages in a transition: a page entered by going back
 // lies beneath the one it replaces. Only the latest pair is retained.
 val layers=remember{mutableMapOf<Long?,Float>()}
 // Direction of the latest directory change, taken while the page being left is
 // still known; the transition itself is driven by the directory id alone.
 val shown=remember{object{var id:Long?=null;var parentId:Long?=null;var returning=false}}
 state.directory.let{d->if(d?.id!=shown.id){shown.returning=d==null||shown.parentId==d.id;shown.id=d?.id};shown.parentId=d?.parentId}
 LaunchedEffect(c.id,c.token,width){vm.connect(c);vm.resume()}
 DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)vm.flush()};owner.lifecycle.addObserver(observer);onDispose{vm.flush();onInteraction(false);owner.lifecycle.removeObserver(observer)}}
 LaunchedEffect(state.restoreSerial,list){
  restoring=true
  val keys=libraryKeys(state)
  list.scrollToItem(restoreIndex(keys,state.albums,state.restoreKey,state.restoreAnchor),if(state.restoreWidth==width)state.restoreOffset else 0)
  restoring=false
 }
 LaunchedEffect(list,width){snapshotFlow{list.layoutInfo.visibleItemsInfo.let{items->items.firstOrNull{it.key.toString().startsWith("album:")}?:items.firstOrNull()}?.let{it.key to -it.offset}}.sample(200).collect{visible->
  if(!restoring&&visible!=null){vm.viewport(visible.first.toString(),visible.second.coerceAtLeast(0),width)}
 }}
 LaunchedEffect(list){snapshotFlow{list.isScrollInProgress}.distinctUntilChanged().collect(onInteraction)}
 LaunchedEffect(list,state.albums,state.children){snapshotFlow{list.layoutInfo.visibleItemsInfo.map{it.key}}.distinctUntilChanged().collect{keys->
  if(state.albums.isEmpty()&&state.children.isNotEmpty()){
   if(keys.contains("folder:${state.children.last().id}"))vm.moreChildren(false)
   if(keys.contains("folder:${state.children.first().id}"))vm.moreChildren(true)
  }
  if(state.albums.isNotEmpty()){
   if(keys.contains("album:${state.albums.last().items.first().id}"))vm.page(false)
   if(keys.contains("album:${state.albums.first().items.first().id}"))vm.page(true)
  }
 }}
 Box(Modifier.fillMaxSize().background(Color.Black)){
  // AnimatedContent discards the page being left when its target changes during
  // the transition, so the target is the directory id and never the whole state.
  AnimatedContent(state.directory?.id,modifier=Modifier.graphicsLayer{translationX=size.width*predictive},transitionSpec={
   val returning=shown.returning
   val ms=if(!animations)0 else if(returning)220 else 260
   val from=initialState;val to=targetState
   val layer=if(from==to)layers[to]?:0f else (layers[from]?:0f)+if(returning)-1f else 1f
   if(from!=to){layers.keys.retainAll(setOf(from));layers[to]=layer}
   ((slideInHorizontally(tween(ms,easing=FastOutSlowInEasing)){if(returning)-it/4 else it}+fadeIn(tween(ms),initialAlpha=if(returning).85f else 1f)) togetherWith
    (slideOutHorizontally(tween(ms,easing=FastOutSlowInEasing)){if(returning)it else -it/4}+fadeOut(tween(ms),targetAlpha=if(returning)1f else .85f))).apply{targetContentZIndex=layer}
  },label="directory"){id->
   // The current page follows live state and the list that restoration,
   // pagination and viewport persistence observe; a page being left keeps
   // the last state and list it showed for the bounded transition. A page
   // returned to before it was disposed takes the current list again.
   val page=remember{object{var latest=state;var shownList=list}}
   if(state.directory?.id==id){page.latest=state;page.shownList=list}
   val screen=page.latest
   LibraryScreen(screen,loader,c,thumbnail,page.shownList,onSettings,vm::back,vm::openDirectory,vm::sort,vm::moreChildren,{screen.directory?.let{vm.open(it.id,refresh=true,kind=screen.kind)}?:vm.connect(c.copy(lastDirectory=0),refresh=true)},onMedia,vm::filter,vm::jumpToStart,onDetails,onConnection)
  }
 }

}
private fun LibraryViewModel.openDirectory(id:Long)=open(id)

