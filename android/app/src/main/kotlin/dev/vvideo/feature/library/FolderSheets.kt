package dev.vvideo.feature.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.Sort
import dev.vvideo.ui.component.*
import dev.vvideo.ui.theme.LocalVV
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun sortLabel(sort:Sort):String=stringResource(when(sort.field){"name"->R.string.file_name;"mtime"->R.string.modified_time;else->R.string.capture_time})+" · "+stringResource(if(sort.field=="name"){if(sort.direction=="asc")R.string.name_asc else R.string.name_desc}else if(sort.direction=="asc")R.string.oldest_first else R.string.newest_first)
@Composable
fun SortSheet(state:LibraryUiState,onDismiss:()->Unit,onSort:(Sort)->Unit){
 VVBottomSheet(stringResource(R.string.sort_title),onDismiss){
  Text(stringResource(R.string.sort_scope,state.directory?.name.orEmpty()),Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=LocalVV.current.textSecondary)
  listOf("capture" to R.string.capture_time,"mtime" to R.string.modified_time,"name" to R.string.file_name).forEach{(field,label)->
   Row(Modifier.fillMaxWidth().clickable{onSort(state.sort.copy(field=field))}.heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(state.sort.field==field,{onSort(state.sort.copy(field=field))});Column{Text(stringResource(label));if(field=="capture")Text(stringResource(R.string.sort_capture_hint),style=MaterialTheme.typography.bodySmall,color=LocalVV.current.textSecondary)}}
  }
  Text(stringResource(R.string.order),Modifier.padding(20.dp),color=LocalVV.current.accentText,style=MaterialTheme.typography.labelLarge)
  Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   val directions=if(state.sort.field=="name")listOf("asc" to R.string.name_asc,"desc" to R.string.name_desc)else listOf("asc" to R.string.oldest_first,"desc" to R.string.newest_first)
   directions.forEach{(direction,label)->VVButton((if(state.sort.direction==direction)"✓ " else "")+stringResource(label),{onSort(state.sort.copy(direction=direction))},Modifier.weight(1f),kind=ButtonKind.Secondary)}
  }
 }
}
@Composable
fun ChildrenSheet(state:LibraryUiState,onDismiss:()->Unit,onMore:(Boolean)->Unit,onOpen:(Long)->Unit){
 val list=rememberLazyListState()
 LaunchedEffect(list,state.children){snapshotFlow{list.layoutInfo.visibleItemsInfo.map{it.key}}.distinctUntilChanged().collect{keys->if(keys.contains("folder:${state.children.lastOrNull()?.id}"))onMore(false);if(keys.contains("folder:${state.children.firstOrNull()?.id}"))onMore(true)}}
 VVBottomSheet(stringResource(R.string.subfolders_count,state.children.size.toString()+if(state.childNext.isNotEmpty())"+" else ""),onDismiss){
  LazyColumn(Modifier.fillMaxWidth().heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.82f),state=list){items(state.children,key={"folder:${it.id}"}){dir->LibraryRow(dir.id.toString(),dir.name,compact=true,onClick={onOpen(dir.id)})}
   if(state.childBusy)item{CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))}
   if(state.childNext.isEmpty()&&state.children.size<5)item{Text(stringResource(R.string.subfolders_note),Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=LocalVV.current.textSecondary)}
  }
 }
}
@Composable
fun FolderInfoSheet(state:LibraryUiState,onDismiss:()->Unit){VVBottomSheet(stringResource(R.string.folder_info),onDismiss){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(state.path.joinToString(" › "){it.name});Text(sortLabel(state.sort),color=LocalVV.current.textSecondary)}}}
