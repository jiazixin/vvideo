package dev.vvideo.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalAccessibilityManager
import dev.vvideo.ui.component.ActionIcon
import dev.vvideo.ui.component.mediaDuration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.vvideo.R
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.playback.PlaybackSession
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.Media
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged

@UnstableApi
@Composable
fun PlayerRoute(vm:PlayerViewModel,c:Connection,loader:ImageLoader,create:suspend(String)->PlaybackSession,release:suspend(PlaybackSession)->Unit,onBack:()->Unit,onInteraction:(Boolean)->Unit,initialSelectionMs:Long=0L,leaving:Boolean=false){
 val live by vm.state.collectAsStateWithLifecycle()
 val state by rememberUpdatedState(retainedWhileLeaving(live,leaving))
 var session by remember{mutableStateOf<PlaybackSession?>(null)}
 val pager=rememberPagerState(pageCount={state.items.size})
 val owner=LocalLifecycleOwner.current
 val latestItems by rememberUpdatedState(state.items)
 LaunchedEffect(state.initialId,state.items.isNotEmpty()){val index=state.items.indexOfFirst{it.id==state.initialId};if(index>=0)pager.scrollToPage(index)}
 var foreground by remember{mutableStateOf(true)}
 // The creating effect owns the player immediately, before a recomposition
 // can install a DisposableEffect keyed by it. Leaving during that gap must
 // still release the player that was already returned to this route.
 LaunchedEffect(c.id,c.token){val owned=create(c.token);try{session=owned;awaitCancellation()}finally{if(session===owned)session=null;withContext(NonCancellable){release(owned)}}}
 DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->when(event){Lifecycle.Event.ON_STOP->{foreground=false;session?.pause()};Lifecycle.Event.ON_START->foreground=true;else->Unit}};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
 var controlsVisible by remember{mutableStateOf(true)}
 var dragging by remember{mutableStateOf(false)}
 var menuOpen by remember{mutableStateOf(false)}
 var detailsOpen by remember{mutableStateOf(false)}
 var controlFocused by remember{mutableStateOf(false)}
 var interactionSerial by remember{mutableIntStateOf(0)}
 var playing by remember{mutableStateOf(false)}
 val activity=androidx.activity.compose.LocalActivity.current
 val orientation=androidx.compose.ui.platform.LocalConfiguration.current.orientation
 val isLandscape=orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
 // Read in the effect, after a route this one replaces has restored its own
 // request; composition would still see that route's landscape lock.
 DisposableEffect(activity){val originalOrientation=activity?.requestedOrientation?:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;onDispose{activity?.requestedOrientation=originalOrientation}}
 androidx.activity.compose.BackHandler(isLandscape){activity?.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
 var holding by remember{mutableStateOf(false)}
 var seekSeconds by remember{mutableIntStateOf(0)}
 var seekForward by remember{mutableStateOf(true)}
 var seekAt by remember{mutableLongStateOf(0L)}
 var bulletin by remember{mutableStateOf<String?>(null)}
 val copied=stringResource(R.string.copied)
 val lastVideo=stringResource(R.string.last_video)
 val accessibility=LocalAccessibilityManager.current
 val active=session
 if(active!=null){val playback by active.state.collectAsStateWithLifecycle()
  // A leaving page keeps its last frame and controls: it pauses at once so no
  // sound outlives the tap, stops tracking the player, and sends no further
  // commands to the session or ViewModel. Release still follows disposal.
  DisposableEffect(active,leaving){if(leaving)return@DisposableEffect onDispose{};playing=active.player.isPlaying;val listener=object:Player.Listener{override fun onIsPlayingChanged(value:Boolean){playing=value}};active.player.addListener(listener);onDispose{active.player.removeListener(listener)}}
  LaunchedEffect(playing,playback.error,dragging,detailsOpen,menuOpen,controlFocused,pager.settledPage,interactionSerial,controlsVisible){
   if(!playing||playback.error||dragging||detailsOpen||menuOpen||controlFocused)controlsVisible=true
   else if(controlsVisible){delay(accessibility?.calculateRecommendedTimeoutMillis(3000,containsIcons=true,containsText=true,containsControls=true)?:3000);controlsVisible=false}
  }
  LaunchedEffect(active,pager,foreground,leaving,state.items.getOrNull(pager.settledPage)?.version){if(leaving){active.pause();return@LaunchedEffect};snapshotFlow{pager.settledPage to pager.isScrollInProgress}.distinctUntilChanged().collect{(index,moving)->onInteraction(moving);if(moving||!foreground)active.pause()else latestItems.getOrNull(index)?.let{vm.selected(it.id);active.select(it.playback(c),if(it.id==state.initialId&&active.state.value.id.isEmpty()&&initialSelectionMs>0)initialSelectionMs else android.os.SystemClock.elapsedRealtime())}}}
  LaunchedEffect(active,playback.firstFrame,playback.buffering,pager.settledPage,state.items,leaving){if(playback.firstFrame&&!playback.buffering&&!pager.isScrollInProgress&&foreground&&!leaving)active.preload(state.items.getOrNull(pager.settledPage+1)?.playback(c))}
  LaunchedEffect(pager,state.items.size,leaving){if(leaving)return@LaunchedEffect;snapshotFlow{pager.settledPage}.distinctUntilChanged().collect{index->if(index>=state.items.size-3)vm.page(false);if(index<2&&state.items.isNotEmpty())vm.page(true)}}
  LaunchedEffect(playback.id){active.player.setPlaybackSpeed(1f);seekSeconds=0}
  LaunchedEffect(seekAt){if(seekSeconds>0){delay(600);seekSeconds=0}}
  val edgeFeedback=remember(pager,state.endReached,lastVideo){object:NestedScrollConnection{
   private var overflow=0f
   override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset{
    if(state.endReached&&!pager.canScrollForward&&available.y<0){overflow-=available.y;if(overflow>80){bulletin=lastVideo;overflow=0f}}else overflow=0f
    return Offset.Zero
   }
  }}
  val currentItem=state.items.getOrNull(pager.settledPage)
  if(detailsOpen&&currentItem!=null)MediaDetailsSheet(currentItem,state.pathLabel,{detailsOpen=false}){bulletin=copied}
  Box(Modifier.fillMaxSize().background(Color.Black)){
   // The video Surface belongs to the session, not a pager item. Recreating it
   // for every settled page forces compositor transactions and decoder output
   // handoffs. Pager covers conceal it until the matching media renders.
   AndroidView(factory={ctx->PlayerView(ctx).apply{player=active.player;useController=false;keepScreenOn=true;setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)}},update={it.player=active.player},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
   VerticalPager(state=pager,key={state.items[it].id},beyondViewportPageCount=1,userScrollEnabled=!dragging&&!isLandscape,modifier=Modifier.fillMaxSize().nestedScroll(edgeFeedback).viewerGestures(active.player,{controlsVisible=!controlsVisible;interactionSerial++},{holding=it},{forward->
     val now=android.os.SystemClock.elapsedRealtime();seekSeconds=if(now-seekAt<=600&&seekForward==forward)seekSeconds+10 else 10;seekForward=forward;seekAt=now
     active.player.seekTo((active.player.currentPosition+if(forward)10000 else -10000).coerceIn(0,active.player.duration.coerceAtLeast(0)));interactionSerial++
   })){index->val item=state.items[index];val activePage=pager.settledPage==index;val selected=activePage&&!pager.isScrollInProgress
    Box(Modifier.fillMaxSize()){
     if(!selected||playback.id!=item.playback(c).cacheKey||!playback.firstFrame){val context=LocalContext.current;val request=remember(item.id,item.version,c.token){ImageRequest.Builder(context).data("${c.address}/v1/media/${item.id}/thumbnail?version=${item.version}").httpHeaders(NetworkHeaders.Builder().set("Authorization","Bearer ${c.token}").build()).build()};AsyncImage(request,item.name,imageLoader=loader,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())}
     if(selected&&playback.error){val unsupported=active.player.playerError?.errorCode in listOf(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
      Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.8f)).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_video_off),null,Modifier.size(48.dp),tint=Color.White);Text(stringResource(if(unsupported)R.string.cannot_decode else R.string.playback_error),color=Color.White);Text(active.player.playerError?.errorCodeName.orEmpty(),color=Color.White.copy(alpha=.6f));dev.vvideo.ui.component.VVButton(stringResource(if(unsupported)R.string.media_details else R.string.retry),{if(unsupported)detailsOpen=true else {vm.retry(item.id);active.select(item.playback(c))}},kind=dev.vvideo.ui.component.ButtonKind.Secondary)}
     }

    }
   }
   androidx.compose.animation.AnimatedVisibility(controlsVisible,Modifier.align(Alignment.TopCenter),enter=androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),exit=androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))){PlayerChrome(currentItem,active.player,onBack,{detailsOpen=true},{bulletin=copied},onMenuChanged={menuOpen=it})}
   Box(Modifier.align(Alignment.BottomCenter).then(if(controlsVisible)Modifier.background(BottomViewerGradient).padding(top=40.dp)else Modifier).navigationBarsPadding()){
    SeekBar(active.player,playback.id,!pager.isScrollInProgress&&playback.firstFrame&&!playback.error,controlsVisible,currentItem?.displayRatio?.let{it>1f}==true,{activity?.requestedOrientation=if(isLandscape)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE},Modifier.onFocusChanged{controlFocused=it.hasFocus},leaving){dragging=it;interactionSerial++}
   }
   if(!playing&&playback.firstFrame&&!playback.error&&!playback.buffering&&!pager.isScrollInProgress)dev.vvideo.ui.component.ActionIcon(R.drawable.ic_play,R.string.play,{active.player.play()},Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha=.45f),androidx.compose.foundation.shape.CircleShape),Color.White)
   if(holding)Text(stringResource(R.string.speed_hold),Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top=72.dp).background(Color.Black.copy(alpha=.5f),androidx.compose.foundation.shape.CircleShape).padding(12.dp),color=Color.White)
   if(seekSeconds>0)Box(Modifier.align(if(seekForward)Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight(.5f).fillMaxWidth(.4f).background(Color.White.copy(alpha=.13f),androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center){Text(stringResource(if(seekForward)R.string.seek_forward else R.string.seek_back,seekSeconds),color=Color.White)}
   dev.vvideo.ui.component.BulletinHost(bulletin,{bulletin=null},Modifier.align(Alignment.BottomCenter))
   if(!state.error&&(playback.buffering||!playback.firstFrame&&!playback.error))Box(Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha=.45f),androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(48.dp),color=Color.White)}
   if(state.error&&state.items.isEmpty())Text(stringResource(R.string.playback_error),color=Color.White,modifier=Modifier.align(Alignment.Center))
  }
 }
}
fun Media.playback(c:Connection)=PlaybackItem(c.id,libraryId,id,version,"${c.address}/v1/media/$id/bytes?version=$version","${c.address}/v1/media/$id/index?version=$version",c.token,size,durationMs)
// Photos page through the folder's image queue the way videos do. Zoom belongs
// to the page in front: the pager stands still while it is enlarged, and
// one-finger drags pan the photo instead.
@Composable
fun PhotoScreen(state:PlayerUiState,c:Connection,loader:ImageLoader,onBack:()->Unit,onRetry:()->Unit,onSelected:(String)->Unit,onPage:(Boolean)->Unit,leaving:Boolean=false){
 // Sized from the loaded queue so the first frame is already the tapped photo.
 val pager=key(state.initialId,state.items.isEmpty()){rememberPagerState(initialPage=state.items.indexOfFirst{it.id==state.initialId}.coerceAtLeast(0),pageCount={state.items.size})}
 // The laid-out key names the page in front even in the one composition where
 // pages arrived ahead of it and its index has not been matched again yet.
 val frontKey by remember(pager){derivedStateOf{pager.layoutInfo.visiblePagesInfo.firstOrNull{it.index==pager.currentPage}?.key}}
 val item=state.items.firstOrNull{it.id==frontKey}?:state.items.getOrNull(pager.currentPage)
 val scaleState=remember(item?.id){mutableFloatStateOf(1f)};var scale by scaleState
 // Composition follows only the enlarged/full-view flip; the scale itself is
 // read while drawing, so a pinch does not recompose the pager every frame.
 val zoomed by remember(scaleState){derivedStateOf{scaleState.floatValue>1f}}
 val latest by rememberUpdatedState(state)
 var offset by remember(item?.id){mutableStateOf(Offset.Zero)}
 var controls by remember{mutableStateOf(true)};var details by remember{mutableStateOf(false)}
 var bulletin by remember{mutableStateOf<String?>(null)};val copied=stringResource(R.string.copied)
 val transform=rememberTransformableState{zoom,pan,_->scale=(scale*zoom).coerceIn(1f,4f);offset=if(scale==1f)Offset.Zero else offset+pan}
 // A pinch released a hair above full view would keep the pager locked with
 // nothing visibly enlarged, so it settles back to full view.
 LaunchedEffect(transform.isTransformInProgress){if(!transform.isTransformInProgress&&scale<1.1f){scale=1f;offset=Offset.Zero}}
 LaunchedEffect(pager,state.items.size,leaving){if(leaving)return@LaunchedEffect;snapshotFlow{pager.settledPage}.distinctUntilChanged().collect{index->latest.items.getOrNull(index)?.let{onSelected(it.id)};if(index>=latest.items.size-3)onPage(false);if(index<2&&latest.items.isNotEmpty())onPage(true)}}
 if(details&&item!=null)MediaDetailsSheet(item,state.pathLabel,{details=false}){bulletin=copied}
 Box(Modifier.fillMaxSize().background(Color.Black)){
  // Neighbours are composed ahead with their thumbnail only, so a swipe never
  // drags in an empty page. The original is fetched once a page comes in front
  // and kept until the pager rests on another page: one original at rest, two
  // while a swipe is under way.
  val resting=!pager.isScrollInProgress
  VerticalPager(state=pager,key={state.items[it].id},beyondViewportPageCount=1,userScrollEnabled=!zoomed,modifier=Modifier.fillMaxSize()){index->val photo=state.items[index];val front=photo.id==item?.id
   PhotoPage(photo,c,loader,front,resting,if(front){{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y}}else null,
    Modifier.pointerInput(photo.id,front){detectTapGestures(onTap={controls=!controls},onDoubleTap={point->if(front){if(scale>1f){scale=1f;offset=Offset.Zero}else{scale=2.5f;offset=Offset((size.width/2f-point.x)*1.5f,(size.height/2f-point.y)*1.5f)}}})}.then(if(front)Modifier.transformable(transform,canPan={scale>1f})else Modifier))
  }
  if(state.error&&state.items.isEmpty())Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(stringResource(R.string.photo_failed),color=Color.White);Button(onClick=onRetry){Text(stringResource(R.string.retry))}}
  if(controls)PlayerChrome(item,null,onBack,{details=true},{bulletin=copied},Modifier.align(Alignment.TopCenter))
  dev.vvideo.ui.component.BulletinHost(bulletin,{bulletin=null},Modifier.align(Alignment.BottomCenter))
 }
}
@Composable
private fun PhotoPage(item:Media,c:Connection,loader:ImageLoader,front:Boolean,resting:Boolean,zoom:(androidx.compose.ui.graphics.GraphicsLayerScope.()->Unit)?,modifier:Modifier=Modifier){
 val image=if(zoom!=null)Modifier.fillMaxSize().graphicsLayer(zoom)else Modifier.fillMaxSize()
 // A page being swiped away keeps its original until the pager rests, so it
 // does not fall back to its thumbnail while still on screen.
 var original by remember{mutableStateOf(front)}
 var attempt by remember(item.version){mutableIntStateOf(0)}
 var failed by remember(item.version,attempt){mutableStateOf(false)}
 var loaded by remember(item.version,attempt){mutableStateOf(false)}
 LaunchedEffect(front,resting){if(front)original=true else if(resting){original=false;loaded=false}}
 val context=LocalContext.current
 val request=remember(item.id,item.version,c.token,attempt){ImageRequest.Builder(context).data("${c.address}/v1/media/${item.id}/bytes?version=${item.version}").httpHeaders(NetworkHeaders.Builder().set("Authorization","Bearer ${c.token}").build()).build()}
 val thumb=remember(item.id,item.version,c.token){ImageRequest.Builder(context).data("${c.address}/v1/media/${item.id}/thumbnail?version=${item.version}").httpHeaders(NetworkHeaders.Builder().set("Authorization","Bearer ${c.token}").build()).build()}
 Box(modifier.fillMaxSize()){
  if(!loaded||!original)AsyncImage(thumb,null,imageLoader=loader,contentScale=ContentScale.Fit,modifier=image)
  if(original)AsyncImage(request,item.name,imageLoader=loader,contentScale=ContentScale.Fit,onSuccess={loaded=true},onError={failed=true},modifier=image)
  if(failed)Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(stringResource(R.string.photo_failed),color=Color.White);Button(onClick={attempt++}){Text(stringResource(R.string.retry))}}
 }
}
