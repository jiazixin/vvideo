package dev.vvideo.feature.library

import androidx.compose.animation.*
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
import dev.vvideo.ui.theme.Motion
import dev.vvideo.ui.theme.fade
import dev.vvideo.ui.theme.move
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state:LibraryUiState,onSettings:()->Unit,onOpen:(Long)->Unit,onRetry:()->Unit,onConnection:()->Unit=onSettings) {
 val c=LocalVV.current
 val quick=fade<Float>();val slow=fade<Float>(Motion.CHANGE);val resize=move<androidx.compose.ui.unit.IntSize>()
 val status=when{state.busy&&state.libraries.isEmpty()->R.string.status_connecting;state.error&&state.errorStatus==401->R.string.status_bad_token;state.error&&state.errorStatus==0->R.string.status_waiting_network;else->R.string.home_title}
 // A refresh behind a shown list only gets a spinner once it is slow enough
 // to notice, so a quick reload does not shift the title back and forth.
 var spinner by remember{mutableStateOf(state.busy)}
 LaunchedEffect(state.busy){if(state.busy){if(state.libraries.isNotEmpty())delay(300);spinner=true}else spinner=false}
 // The title only acts while it reports a problem: retry, or fix the credential.
 val action=if(!state.error||state.busy)null else if(state.errorStatus==401)onConnection else onRetry
 Scaffold(containerColor=c.bgSurface,topBar={TopAppBar(expandedHeight=56.dp,title={Row(if(action!=null)Modifier.clickable(onClick=action) else Modifier,verticalAlignment=Alignment.CenterVertically){
  AnimatedVisibility(spinner,enter=fadeIn(fade())+expandHorizontally(move()),exit=fadeOut(fade())+shrinkHorizontally(move())){CircularProgressIndicator(Modifier.padding(end=8.dp).size(16.dp),strokeWidth=2.dp)}
  val color by animateColorAsState(if(state.errorStatus==401)c.warn else c.textPrimary,fade(),label="home-title-color")
  AnimatedContent(status,transitionSpec={fadeIn(quick) togetherWith fadeOut(quick) using SizeTransform(clip=false){_,_->resize}},label="home-title"){Text(stringResource(it),color=color)}
 }},actions={ActionIcon(R.drawable.ic_settings,R.string.settings,onSettings)},colors=TopAppBarDefaults.topAppBarColors(containerColor=c.bgSurface))}){padding->
  // The library list fades in once on arrival; its rows have no entrance animation of their own.
  AnimatedContent(state.libraries.isEmpty(),Modifier.padding(padding),transitionSpec={fadeIn(slow) togetherWith fadeOut(slow)},label="home-content"){empty->
   if(empty){if(!state.busy&&!state.error)EmptyState(stringResource(R.string.empty_roots)) else Box(Modifier.fillMaxSize())}
   else LazyColumn(Modifier.fillMaxSize()){
    items(state.libraries,key={it.id}){lib->
     LibraryRow(lib.id,lib.name,stringResource(if(lib.healthy)R.string.library_online else R.string.library_offline),pinned=true,healthy=lib.healthy,onClick={onOpen(lib.directoryId)})
    }
   }
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
