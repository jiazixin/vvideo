package dev.vvideo.feature.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.ui.component.*
import dev.vvideo.ui.theme.LocalVV

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state:LibraryUiState,onSettings:()->Unit,onOpen:(Long)->Unit,onRetry:()->Unit,onHideRecent:(Long)->Unit={},onConnection:()->Unit=onSettings) {
 val c=LocalVV.current
 var removed by remember{mutableStateOf<RecentFolder?>(null)}
 removed?.let{recent->VVBottomSheet(recent.bookmark.name,{removed=null}){VVButton(stringResource(R.string.remove_from_recent),{onHideRecent(recent.bookmark.directory);removed=null},Modifier.fillMaxWidth().padding(horizontal=20.dp),kind=ButtonKind.Danger)}}
 Scaffold(containerColor=c.bgSurface,topBar={TopAppBar(expandedHeight=56.dp,title={Row(Modifier.clickable{if(state.errorStatus==401)onConnection() else onRetry()},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  if(state.busy)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
  Text(stringResource(when{state.busy&&state.libraries.isEmpty()->R.string.status_connecting;state.error&&state.errorStatus==401->R.string.status_bad_token;state.error&&state.errorStatus==0->R.string.status_waiting_network;else->R.string.home_title}),color=if(state.errorStatus==401)c.warn else c.textPrimary)
 }},actions={ActionIcon(R.drawable.ic_settings,R.string.settings,onSettings)},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))}){padding->
  if(state.libraries.isEmpty()&&state.recent.isEmpty()&&!state.busy&&!state.error)EmptyState(stringResource(R.string.empty_roots),Modifier.padding(padding))
  else LazyColumn(Modifier.fillMaxSize().padding(padding)) {
   items(state.libraries,key={it.id}){lib->
    LibraryRow(lib.id,lib.name,stringResource(if(lib.healthy)R.string.library_online else R.string.library_offline),pinned=true,healthy=lib.healthy,onClick={onOpen(lib.directoryId)})
   }
   if(state.recent.isNotEmpty())item("recent"){Text(stringResource(R.string.recent),Modifier.padding(horizontal=16.dp,vertical=14.dp),color=c.accentText,style=MaterialTheme.typography.labelLarge)}
   items(state.recent,key={"recent:${it.bookmark.directory}"}){recent->val b=recent.bookmark;LibraryRow(b.directory.toString(),b.name,b.pathLabel,recent.timeLabel,onLongClick={removed=recent},onClick={onOpen(b.directory)})}
  }
 }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(id:String,name:String,subtitle:String?=null,time:String?=null,pinned:Boolean=false,healthy:Boolean=true,compact:Boolean=false,onLongClick:(()->Unit)?=null,onClick:()->Unit) {
 val c=LocalVV.current
 Column(Modifier.background(if(pinned)c.pinnedRow else c.bgSurface)){
  Row(Modifier.fillMaxWidth().combinedClickable(onClick=onClick,onLongClick=onLongClick).heightIn(min=if(compact)64.dp else 72.dp).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   LetterAvatar(id,name,if(compact)44.dp else 52.dp)
   Column(Modifier.weight(1f)){
    Row(verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=if(!healthy)c.warn else c.textPrimary,maxLines=1,overflow=TextOverflow.Ellipsis)
     if(pinned)Icon(painterResource(R.drawable.ic_pin),null,Modifier.size(16.dp),tint=c.textTertiary)
     else if(time!=null)Text(time,style=MaterialTheme.typography.bodySmall,color=c.textSecondary)
     else Icon(painterResource(R.drawable.ic_chevron),null,tint=c.textTertiary)
    }
    if(subtitle!=null)Row(Modifier.padding(top=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
     if(pinned)Box(Modifier.size(7.dp).background(if(healthy)c.online else c.warn,CircleShape))
     Text(subtitle,color=if(!healthy)c.warn else c.textSecondary,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
   }
  }
  HorizontalDivider(Modifier.padding(start=76.dp),color=c.divider)
 }
}
