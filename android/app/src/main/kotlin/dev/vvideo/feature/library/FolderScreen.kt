package dev.vvideo.feature.library

import androidx.activity.compose.BackHandler
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
import dev.vvideo.ui.theme.LocalVV
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state:LibraryUiState,loader:ImageLoader,connection:Connection,thumbnail:(Connection,Media)->String,list:LazyListState,onSettings:()->Unit,onBack:()->Unit,onOpen:(Long)->Unit,onSort:(Sort)->Unit,onMore:(Boolean)->Unit,onRetry:()->Unit,onMedia:(Media)->Unit,onKind:(String)->Unit={},onJump:()->Unit={},onHideRecent:(Long)->Unit={},onDetails:(Media)->Unit={},onConnection:()->Unit=onSettings) {
 if(state.directory==null){HomeScreen(state,onSettings,onOpen,onRetry,onHideRecent,onConnection);return}
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
 LaunchedEffect(list.isScrollInProgress){if(list.isScrollInProgress)floating=true else {delay(1200);floating=false}}
 Scaffold(containerColor=c.bgPage,topBar={Column(Modifier.background(c.bgSurface)){
  TopAppBar(expandedHeight=56.dp,title={Row(Modifier.clickable{sheet="info"},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   LetterAvatar(d.id.toString(),d.name,40.dp)
   Column{Text(d.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(state.path.size<=1)stringResource(R.string.libraries) else state.path.dropLast(1).takeLast(2).joinToString(" › "){it.name},style=MaterialTheme.typography.bodySmall,color=c.textSecondary,maxLines=1,overflow=TextOverflow.Ellipsis)}
  }},navigationIcon={ActionIcon(R.drawable.ic_back,R.string.back,onBack)},actions={Box{
   ActionIcon(R.drawable.ic_more,R.string.sort,{menu=true})
   DropdownMenu(menu,{menu=false},Modifier.width(236.dp),shape=RoundedCornerShape(10.dp),containerColor=c.bgRaised){
    DropdownMenuItem(text={Column{Text(stringResource(R.string.sort_title));Text(sortLabel(state.sort),style=MaterialTheme.typography.bodySmall,color=c.textSecondary)}},leadingIcon={Icon(painterResource(R.drawable.ic_sort),null)},onClick={menu=false;sheet="sort"})
    DropdownMenuItem(text={Text(stringResource(if(state.sort.field=="name")R.string.jump_to_beginning else R.string.jump_to_start))},leadingIcon={Icon(painterResource(R.drawable.ic_arrow_up),null)},onClick={menu=false;jump()})
    DropdownMenuItem(text={Text(stringResource(R.string.refresh))},leadingIcon={Icon(painterResource(R.drawable.ic_refresh),null)},onClick={menu=false;onRetry()})
    HorizontalDivider()
    DropdownMenuItem(text={Text(stringResource(R.string.folder_info))},leadingIcon={Icon(painterResource(R.drawable.ic_info),null)},onClick={menu=false;sheet="info"})
   }
  }},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))
  if(!state.healthy||state.error)OfflineBanner(if(state.error)stringResource(R.string.offline_banner) else stringResource(R.string.library_offline),onRetry)
  if(state.hasMedia)MediaTabs(state.kind,onKind)
  if(state.albums.isNotEmpty()&&state.children.isNotEmpty())SubfolderBar(state){sheet="children"}
 }}){padding->Box(Modifier.fillMaxSize().padding(padding)){
  LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)){
   // Zero-height key preserves a single mapping shared by restoration and pagination.
   if(state.albums.isNotEmpty()&&state.children.isNotEmpty())item("subfolder-bar"){Spacer(Modifier.height(0.dp))}
   if(state.albums.isEmpty())items(state.children,key={"folder:${it.id}"},contentType={"folder"}){dir->LibraryRow(dir.id.toString(),dir.name,onClick={onOpen(dir.id)})}
   state.albums.forEach{album->
    if(album.dateLabel!=null)item("date:${album.items.first().id}",contentType="date"){Box(Modifier.fillMaxWidth().padding(top=12.dp,bottom=8.dp),contentAlignment=Alignment.Center){DatePill(album.dateLabel)}}
    item("album:${album.items.first().id}",contentType="album"){AlbumBubble(album,loader,connection,thumbnail,onMedia,onDetails)}
   }
   if(state.childBusy||state.pageBusy)item("paging"){Box(Modifier.fillMaxWidth().padding(8.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)}}
  }
  if(state.busy)Column(Modifier.fillMaxSize().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){repeat(2){Box(Modifier.fillMaxWidth().height(260.dp).background(c.bgPlaceholder,RoundedCornerShape(16.dp)))}}
  else if(state.albums.isEmpty()&&state.children.isEmpty())EmptyState(stringResource(R.string.empty_media),action=stringResource(R.string.refresh),onAction=onRetry)
  androidx.compose.animation.AnimatedVisibility(floating&&firstAlbum?.dayLabel!=null,Modifier.align(Alignment.TopCenter).padding(top=8.dp),enter=androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),exit=androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))){firstAlbum?.dayLabel?.let{DatePill(it)}}
  if(showFab)SmallFloatingActionButton(onClick=jump,Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end=16.dp,bottom=24.dp).size(48.dp),shape=CircleShape,containerColor=c.bgRaised,contentColor=c.icon){Icon(painterResource(R.drawable.ic_arrow_up),stringResource(R.string.jump_to_start))}
  BulletinHost(bulletin,{bulletin=null},Modifier.align(Alignment.BottomCenter))
 }}
}
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
