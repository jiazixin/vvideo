package dev.vvideo.feature.library

import dev.vvideo.ui.component.TimeLabels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.local.BookmarkDao
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.local.BookmarkEntity
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.feature.library.album.*
import dev.vvideo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import android.util.Base64

// Geometry is prepared outside the UI thread and retained for only its window.
data class Album(val items:List<Media>,val rows:List<AlbumRow>,val dateLabel:String?=null,val timeLabel:String?=null,val dayLabel:String?=null)
data class RecentFolder(val bookmark:BookmarkEntity,val timeLabel:String)
data class LibraryUiState(val recent:List<RecentFolder> = emptyList(),val kind:String="",val hasMedia:Boolean=false,val hasPrevious:Boolean=false,val pageBusy:Boolean=false,val libraries:List<Library> = emptyList(),val directory:Directory?=null,val children:List<Directory> = emptyList(),val childNext:String="",val childPrevious:String="",val childBusy:Boolean=false,val path:List<Directory> = emptyList(),val errorStatus:Int=0,val albums:List<Album> = emptyList(),val sort:Sort=Sort(),val busy:Boolean=false,val error:Boolean=false,val healthy:Boolean=true,val restoreAnchor:String="",val restoreKey:String="",val restoreOffset:Int=0,val restoreWidth:Int=0,val restoreSerial:Long=0,val anchorMissing:Boolean=false)
class LibraryViewModel(private val api:LibraryApi,private val bookmarks:BookmarkDao,private val settings:SettingsRepository,private val persistScope:CoroutineScope,private val timeLabels:TimeLabels?=null):ViewModel() {
 private val mutable=MutableStateFlow(LibraryUiState())
 val state=mutable.asStateFlow()
 private val window=PageWindow()
 private val childrenWindow=dev.vvideo.data.library.ChildrenWindow()
 private var viewportKey=""
 private var viewportOffset=0
 private var viewportWidth=0
 private var connection=Connection()
 private var recentRequest:Job?=null
 private var request:Job?=null
 private var pageRequest:Job?=null
 private var childRequest:Job?=null
 private var generation=0L
 private fun cancelRequests(){generation++;request?.cancel();pageRequest?.cancel();childRequest?.cancel();paginating=false}
 private var paginating=false
 private var loadedNext=true
 private var loadedPrevious=true
 private var bookmark:BookmarkEntity?=null
 private var saveJob:Job?=null
 fun connect(c:Connection,refresh:Boolean=false){if(!refresh&&connection.id==c.id&&connection.token==c.token&&mutable.value.libraries.isNotEmpty())return;flush();cancelRequests();viewportKey="";bookmark=null;val previousRecent=if(connection.id==c.id)mutable.value.recent else emptyList();connection=c;recentRequest?.cancel();recentRequest=viewModelScope.launch{bookmarks.recent(c.id).collectLatest{records->val recent=withContext(Dispatchers.Default){records.map{RecentFolder(it,timeLabels?.recent(it.visitedAt).orEmpty())}};mutable.value=mutable.value.copy(recent=recent)}};request=viewModelScope.launch{mutable.value=LibraryUiState(busy=true,recent=previousRecent);try{val libs=api.libraries(c);mutable.value=mutable.value.copy(libraries=libs,busy=false);if(c.lastDirectory!=0L)open(c.lastDirectory)}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(busy=false,error=true,errorStatus=(e as? dev.vvideo.data.library.remote.ServiceFailure)?.status?:0)}}}
 fun hideRecent(id:Long){viewModelScope.launch{bookmarks.hide(connection.id,id)}}
 fun resume(){val s=mutable.value;if(s.busy)return;val b=bookmark
  mutable.value=s.copy(restoreKey=viewportKey,restoreAnchor=b?.anchor.orEmpty(),restoreOffset=if(viewportKey.isNotEmpty())viewportOffset else b?.pixelOffset?:0,restoreWidth=if(viewportKey.isNotEmpty())viewportWidth else b?.width?:0,restoreSerial=s.restoreSerial+1)
 }
 fun viewport(key:String,offset:Int,width:Int){if(mutable.value.busy)return;viewportKey=key;viewportOffset=offset;viewportWidth=width;val index=mutable.value.albums.indexOfFirst{"album:${it.items.first().id}"==key};if(index>=0)save(index,offset,width)}
 fun back(){val d=mutable.value.directory?:return;if(d.parentId!=0L)open(d.parentId)else roots()}
 fun roots(){flush();cancelRequests();viewportKey="";bookmark=null;mutable.value=LibraryUiState(libraries=mutable.value.libraries,recent=mutable.value.recent);viewModelScope.launch{settings.recent(0)}}
 fun open(id:Long,override:Sort?=null,refresh:Boolean=false,kind:String="",fromStart:Boolean=false){flush();cancelRequests();viewportKey="";request=viewModelScope.launch {
  val saved=if(refresh)bookmark else bookmarks.get(connection.id,id)
  val restore=override==null&&kind.isEmpty()&&!fromStart
  val sort=override ?: saved?.let{Sort(it.sort,it.direction)} ?: Sort()
  mutable.value=mutable.value.copy(busy=true,error=false,kind=kind)
  try {
   var page:MediaPage?=null
   repeat(5){if(page==null)try{page=api.page(connection,id,sort,anchor=if(restore)saved?.anchor.orEmpty() else "",fallback=if(restore)saved?.fallback.orEmpty() else "",kind=kind)}catch(e:RevisionChanged){delay(700)}}
   var initial=page ?: throw RevisionChanged()
   if(restore&&saved!=null&&!initial.anchorFound&&saved.neighbor.isNotEmpty()) {
    initial=api.page(connection,id,sort,anchor=saved.neighbor,fallback=saved.fallback,kind=kind)
   }
   val children=api.children(connection,id)
   childrenWindow.reset(children)
   val old=mutable.value.path
   val existing=old.indexOfFirst{it.id==id}
   var path=if(existing>=0)old.take(existing+1) else if(old.lastOrNull()?.id==initial.directory.parentId)old+initial.directory else listOf(initial.directory)
   if(path.size==1&&initial.directory.parentId!=0L){
    try{path=api.path(connection,id)}
    catch(e:CancellationException){throw e}catch(_:Exception){/* Browsing remains available if an older service has no directory endpoint. */}
   }
   window.reset(initial);loadedNext=true;loadedPrevious=initial.previous.isNotEmpty()
   bookmark=if(override==null&&saved!=null)saved else BookmarkEntity(connection.id,id,initial.directory.libraryId,sort.field,sort.direction)
   val preparedAlbums=albums(sort)
   mutable.value=mutable.value.copy(directory=initial.directory,children=children.items,childNext=children.next,childPrevious=children.previous,childBusy=false,path=path,sort=sort,hasMedia=initial.items.isNotEmpty()||(kind.isNotEmpty()&&mutable.value.hasMedia),hasPrevious=window.previous.isNotEmpty(),albums=preparedAlbums,busy=false,error=false,errorStatus=0,healthy=initial.healthy,restoreKey="",restoreAnchor=if(restore)saved?.anchor.orEmpty() else "",restoreOffset=if(restore)saved?.pixelOffset?:0 else 0,restoreWidth=saved?.width?:0,restoreSerial=mutable.value.restoreSerial+1,anchorMissing=restore&&saved?.anchor?.isNotEmpty()==true&&!initial.anchorFound)
   val record=checkNotNull(bookmark).copy(name=initial.directory.name,pathLabel=path.dropLast(1).takeLast(2).joinToString(" › "){it.name},visitedAt=System.currentTimeMillis(),hiddenFromRecent=false)
   bookmark=record
   bookmarks.visit(record)
   if(initial.healthy)settings.recent(id)
  }catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(busy=false,error=true,errorStatus=(e as? dev.vvideo.data.library.remote.ServiceFailure)?.status?:0)}
 }}
 private suspend fun albums(sort:Sort=mutable.value.sort)=withContext(Dispatchers.Default){datedAlbums(window.items.chunked(10).map{Album(it,AlbumLayoutCalculator.calculate(it.map(Media::displayRatio)))},sort,timeLabels)}
 fun filter(kind:String){require(kind in setOf("","video","image"));val s=mutable.value; s.directory?.let{open(it.id,s.sort,kind=kind,fromStart=true)}}
 fun jumpToStart(){val s=mutable.value;s.directory?.let{open(it.id,s.sort,kind=s.kind,fromStart=true)}}
 // A published window is ready for paging even while open() persists its visit.
 // Blocking on the whole request Job can discard the only visible-edge event.
 fun page(previous:Boolean){if(paginating||mutable.value.busy||(!previous&&!loadedNext)||(previous&&!loadedPrevious))return;val d=mutable.value.directory?:return;val cursor=if(previous)window.previous else window.next;if(cursor.isEmpty())return;paginating=true;mutable.value=mutable.value.copy(pageBusy=true);val round=generation;val sort=mutable.value.sort;pageRequest=viewModelScope.launch {try{val page=api.page(connection,d.id,sort,cursor,previous,kind=mutable.value.kind);ensureActive();if(round!=generation)return@launch;if(page.items.isEmpty()){if(previous)loadedPrevious=false else loadedNext=false}else{if(previous){window.prepend(page);loadedNext=true}else{window.append(page);loadedPrevious=true};val preparedAlbums=albums();mutable.value=mutable.value.copy(albums=preparedAlbums,hasPrevious=window.previous.isNotEmpty(),error=false,errorStatus=0)}}catch(e:CancellationException){throw e}catch(_:RevisionChanged){open(d.id,refresh=true,kind=mutable.value.kind)}catch(e:Exception){mutable.value=mutable.value.copy(error=true,errorStatus=(e as? dev.vvideo.data.library.remote.ServiceFailure)?.status?:0)}finally{if(round==generation){paginating=false;mutable.value=mutable.value.copy(pageBusy=false)}}}}
 fun moreChildren(previous:Boolean=false){
  val s=mutable.value;val d=s.directory?:return
  val cursor=if(previous)s.childPrevious else s.childNext
  if(cursor.isEmpty()||childRequest?.isActive==true||s.busy)return
  val round=generation
  childRequest=viewModelScope.launch{
   mutable.value=mutable.value.copy(childBusy=true)
   try{
    val p=api.children(connection,d.id,cursor,previous);ensureActive()
    if(round==generation){
     if(p.items.isNotEmpty())childrenWindow.add(p,previous)
     mutable.value=mutable.value.copy(children=childrenWindow.items,childNext=if(!previous&&p.items.isEmpty())"" else childrenWindow.next,childPrevious=if(previous&&p.items.isEmpty())"" else childrenWindow.previous,error=false)
    }
   }catch(e:CancellationException){throw e}catch(e:Exception){if(round==generation)mutable.value=mutable.value.copy(error=true,errorStatus=(e as? dev.vvideo.data.library.remote.ServiceFailure)?.status?:0)}
   finally{if(round==generation)mutable.value=mutable.value.copy(childBusy=false)}
  }
 }
 fun save(album:Int,offset:Int,width:Int){val s=mutable.value;if(s.busy||s.error||!s.healthy||s.kind.isNotEmpty())return;val group=s.albums.getOrNull(album)?:return;val item=group.items.firstOrNull()?:return;val base=bookmark?:return
  val key=when(s.sort.field){"name"->item.name;"mtime"->item.mtime.toString();else->item.effectiveTime.toString()}
  val fallback=Base64.encodeToString(JSONObject().put("directory_id",item.directoryId).put("revision",s.directory?.revision?:0).put("sort",s.sort.field).put("direction",s.sort.direction).put("kind","").put("key",key).put("id",item.id).toString().toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  val record=base.copy(anchor=item.id,neighbor=group.items.getOrNull(1)?.id.orEmpty(),fallback=fallback,pixelOffset=offset,width=width,revision=s.directory?.revision?:0,savedAt=System.currentTimeMillis());bookmark=record
  if(saveJob?.isActive!=true)saveJob=persistScope.launch{delay(500);bookmark?.let{bookmarks.put(it)}}
 }
 fun flush(){if(mutable.value.kind.isNotEmpty())return;val record=bookmark?:return;saveJob?.cancel();persistScope.launch{bookmarks.put(record)}}
 fun sort(sort:Sort){val d=mutable.value.directory?:return;if(mutable.value.kind.isNotEmpty()){open(d.id,sort,kind=mutable.value.kind,fromStart=true);return};bookmark=BookmarkEntity(connection.id,d.id,d.libraryId,sort.field,sort.direction);val cleared=checkNotNull(bookmark);persistScope.launch{bookmarks.put(cleared)};open(d.id,sort,kind=mutable.value.kind)}
 override fun onCleared(){flush();super.onCleared()}
}
