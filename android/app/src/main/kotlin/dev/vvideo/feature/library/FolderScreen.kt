package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.vvideo.R
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.*
import dev.vvideo.ui.component.*
import androidx.compose.ui.graphics.graphicsLayer
import dev.vvideo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state:LibraryUiState,loader:ImageLoader,connection:Connection,thumbnail:(Connection,Media)->String,list:LazyListState,onSettings:()->Unit,onBack:()->Unit,onOpen:(Long)->Unit,onSort:(Sort)->Unit,onMore:(Boolean)->Unit,onRetry:()->Unit,onMedia:(Media)->Unit,onKind:(String)->Unit={},onJump:()->Unit={},onDetails:(Media)->Unit={},onConnection:()->Unit=onSettings) {
 if(state.directory==null){HomeScreen(state,onSettings,onOpen,onRetry,onConnection);return}
 FolderScreen(state,loader,connection,thumbnail,list,onBack,onOpen,onSort,onMore,onRetry,onMedia,onKind,onJump,onDetails)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(state:LibraryUiState,loader:ImageLoader,connection:Connection,thumbnail:(Connection,Media)->String,list:LazyListState,onBack:()->Unit,onOpen:(Long)->Unit,onSort:(Sort)->Unit,onMore:(Boolean)->Unit,onRetry:()->Unit,onMedia:(Media)->Unit,onKind:(String)->Unit,onJump:()->Unit,onDetails:(Media)->Unit) {
 val c=LocalVV.current;val d=checkNotNull(state.directory)
 var menu by remember(d.id){mutableStateOf(false)};var sheet by remember(d.id){mutableStateOf("")}
 var bulletin by remember{mutableStateOf<String?>(null)}
 val moved=stringResource(R.string.anchor_moved)
 LaunchedEffect(state.restoreSerial){if(state.anchorMissing)bulletin=moved}
 val scope=rememberCoroutineScope()
 val jump={if(state.hasPrevious)onJump() else {scope.launch{list.animateScrollToItem(0)};Unit}}
 BackHandler(menu||sheet.isNotEmpty()){if(menu)menu=false else sheet=""}
 if(sheet=="sort")SortSheet(state,{sheet=""}){sheet="";onSort(it)}
 if(sheet=="children")ChildrenSheet(state,{sheet=""},onMore){sheet="";onOpen(it)}
 if(sheet=="info")FolderInfoSheet(state){sheet=""}
 val firstAlbum by remember(list,state.albums){derivedStateOf{list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull{visible->state.albums.firstOrNull{"album:${it.items.first().id}"==visible.key}}}}
 val showFab by remember(list,state.albums,state.hasPrevious){derivedStateOf{state.hasPrevious||state.albums.indexOfFirst{"album:${it.items.first().id}"==list.layoutInfo.visibleItemsInfo.firstOrNull{it.key.toString().startsWith("album:")}?.key}>2}}
 var floating by remember{mutableStateOf(false)}
 // The floating date covers the top of the list. Wherever an in-list date row
 // passes through that band, the two would show the same day twice (or two days
 // on top of each other), so the floating one gives way at once, without fading.
 val density=androidx.compose.ui.platform.LocalDensity.current
 val dateBand=with(density){48.dp.roundToPx()}
 val dateRowAtTop by remember(list,dateBand){derivedStateOf{list.layoutInfo.visibleItemsInfo.any{it.contentType=="date"&&it.offset<dateBand&&it.offset+it.size>0}}}
 LaunchedEffect(list.isScrollInProgress){if(list.isScrollInProgress)floating=true else {delay(1200);floating=false}}
 // A filter or sort change replaces the whole album list; the new list fades in
 // once it is published. Opening, refreshing or returning to the page does not.
 val reveal=remember{Animatable(1f)};val revealSpec=fade<Float>(Motion.CHANGE)
 val shown=remember{object{var content:Any?=null}}
 val content=if(state.busy)null else Triple(d.id,state.kind,state.sort)
 LaunchedEffect(content){if(content==null)return@LaunchedEffect;val previous=shown.content;shown.content=content;if(previous!=null&&previous!=content){reveal.snapTo(.2f);reveal.animateTo(1f,revealSpec)}}
 Scaffold(containerColor=c.bgPage,topBar={Column(Modifier.background(c.bgSurface)){
  TopAppBar(expandedHeight=56.dp,title={Row(Modifier.clickable{sheet="info"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   LetterAvatar(d.id.toString(),d.name,40.dp)
   Column{Text(d.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(state.pathLabel.ifEmpty{stringResource(R.string.libraries)},style=MaterialTheme.typography.bodySmall,color=c.textSecondary,maxLines=1,overflow=TextOverflow.Ellipsis)}
  }},navigationIcon={ActionIcon(R.drawable.ic_back,R.string.back,onBack)},actions={Box{
   ActionIcon(R.drawable.ic_more,R.string.sort,{menu=true})
   DropdownMenu(menu,{menu=false},Modifier.width(236.dp),shape=RoundedCornerShape(10.dp),containerColor=c.bgRaised){
    DropdownMenuItem(text={Column{Text(stringResource(R.string.sort_title));Text(sortLabel(state.sort),style=MaterialTheme.typography.bodySmall,color=c.textSecondary)}},leadingIcon={Icon(painterResource(R.drawable.ic_sort),null)},onClick={menu=false;sheet="sort"})
    DropdownMenuItem(text={Text(stringResource(jumpLabel(state.sort)))},leadingIcon={Icon(painterResource(R.drawable.ic_arrow_up),null)},onClick={menu=false;jump()})
    DropdownMenuItem(text={Text(stringResource(R.string.refresh))},leadingIcon={Icon(painterResource(R.drawable.ic_refresh),null)},onClick={menu=false;onRetry()})
    HorizontalDivider()
    DropdownMenuItem(text={Text(stringResource(R.string.folder_info))},leadingIcon={Icon(painterResource(R.drawable.ic_info),null)},onClick={menu=false;sheet="info"})
   }
  }},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))
  AnimatedVisibility(!state.healthy||state.error,enter=barEnter(),exit=barExit()){OfflineBanner(if(state.error)stringResource(R.string.offline_banner) else stringResource(R.string.library_offline),onRetry)}
  AnimatedVisibility(state.hasMedia,enter=barEnter(),exit=barExit()){MediaTabs(state.kind,onKind)}
  AnimatedVisibility(state.albums.isNotEmpty()&&state.children.isNotEmpty(),enter=barEnter(),exit=barExit()){SubfolderBar(state){sheet="children"}}
 }}){padding->Box(Modifier.fillMaxSize().padding(padding)){
  LazyColumn(state=list,modifier=Modifier.fillMaxSize().graphicsLayer{alpha=reveal.value},contentPadding=PaddingValues(bottom=24.dp)){
   // Zero-height key preserves a single mapping shared by restoration and pagination.
   if(state.albums.isNotEmpty()&&state.children.isNotEmpty())item("subfolder-bar"){Spacer(Modifier.height(0.dp))}
   if(state.albums.isEmpty())items(state.children,key={"folder:${it.id}"},contentType={"folder"}){dir->LibraryRow(dir.id.toString(),dir.name,onClick={onOpen(dir.id)})}
   state.albums.forEach{album->
    if(album.dateLabel!=null)item("date:${album.items.first().id}",contentType="date"){Box(Modifier.fillMaxWidth().padding(top=12.dp,bottom=8.dp),contentAlignment=Alignment.Center){DatePill(album.dateLabel)}}
    item("album:${album.items.first().id}",contentType="album"){AlbumBubble(album,loader,connection,thumbnail,onMedia,onDetails)}
   }
   if(state.childBusy||state.pageBusy)item("paging"){Box(Modifier.fillMaxWidth().padding(8.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)}}
  }
  // The placeholder belongs to a page without content of its own; a reload
  // keeps what is already shown, and a failed load is not an empty folder.
  val blank=state.albums.isEmpty()&&state.children.isEmpty()
  // Arriving content shows through the fading placeholder.
  AnimatedVisibility(state.busy&&blank,enter=fadeIn(fade()),exit=fadeOut(fade(Motion.CHANGE))){Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){repeat(2){Box(Modifier.fillMaxWidth().height(260.dp).background(c.bgPlaceholder,RoundedCornerShape(16.dp)))}}}
  AnimatedVisibility(!state.busy&&blank&&!state.error,enter=fadeIn(fade(Motion.CHANGE)),exit=fadeOut(fade())){EmptyState(stringResource(R.string.empty_media),action=stringResource(R.string.refresh),onAction=onRetry)}
  androidx.compose.animation.AnimatedVisibility(floating&&!dateRowAtTop&&firstAlbum?.dayLabel!=null,Modifier.align(Alignment.TopCenter).padding(top=12.dp),enter=fadeIn(fade()),exit=if(dateRowAtTop)ExitTransition.None else fadeOut(fade(Motion.CHANGE))){firstAlbum?.dayLabel?.let{DatePill(it)}}
  AnimatedVisibility(showFab,Modifier.align(Alignment.BottomEnd),enter=fadeIn(fade())+scaleIn(move(),initialScale=.6f),exit=fadeOut(fade())+scaleOut(move(),targetScale=.6f)){SmallFloatingActionButton(onClick=jump,Modifier.navigationBarsPadding().padding(end=16.dp,bottom=24.dp).size(48.dp),shape=CircleShape,containerColor=c.bgRaised,contentColor=c.icon){Icon(painterResource(R.drawable.ic_arrow_up),stringResource(jumpLabel(state.sort)))}}
  BulletinHost(bulletin,{bulletin=null},Modifier.align(Alignment.BottomCenter))
 }}
}
// The start of the list is the oldest or newest media depending on the direction.
private fun jumpLabel(sort:Sort)=when{sort.field=="name"->R.string.jump_to_beginning;sort.direction=="asc"->R.string.jump_to_oldest;else->R.string.jump_to_start}
@Composable
fun DatePill(label:String){Text(label,Modifier.background(LocalVV.current.datePill,RoundedCornerShape(12.dp)).padding(horizontal=10.dp,vertical=3.dp),color=LocalVV.current.datePillText,style=MaterialTheme.typography.labelSmall)}
@Composable
private fun SubfolderBar(state:LibraryUiState,onClick:()->Unit){val c=LocalVV.current
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=52.dp).padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
  Box(Modifier.size(32.dp).background(c.accentSoft,CircleShape),contentAlignment=Alignment.Center){Icon(painterResource(R.drawable.ic_folder),null,tint=c.accentText)}
  Column(Modifier.weight(1f)){Text(stringResource(R.string.subfolders_count,state.children.size.toString()+if(state.childNext.isNotEmpty())"+" else ""),color=c.accentText,style=MaterialTheme.typography.labelSmall);Text(state.children.take(3).joinToString("、"){it.name},style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)}
  Icon(painterResource(R.drawable.ic_chevron),null,tint=c.textTertiary)
 }
}
