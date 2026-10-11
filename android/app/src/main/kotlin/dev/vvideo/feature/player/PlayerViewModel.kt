package dev.vvideo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.data.library.PageWindow
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.remote.RevisionChanged
import dev.vvideo.data.settings.Connection
import dev.vvideo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlayerUiState(val endReached:Boolean=false,val pathLabel:String="",val items:List<Media> = emptyList(),val error:Boolean=false,val initialId:String="",val photo:Media?=null)
class PlayerViewModel(private val api:LibraryApi):ViewModel(){
 private val mutable=MutableStateFlow(PlayerUiState())
 val state=mutable.asStateFlow()
 private var connection=Connection();private var directory=0L;private var sort=Sort();private val window=PageWindow();private var request:Job?=null;private var pageRequest:Job?=null;private var retryRequest:Job?=null;private var generation=0L;private var selectedId="";private var paginating=false;private var canNext=true;private var canPrevious=true
 private fun cancelRequests(){generation++;request?.cancel();pageRequest?.cancel();retryRequest?.cancel();paginating=false}
 fun selected(id:String){selectedId=id}
 fun open(c:Connection,dir:Long,order:Sort,id:String,photo:Boolean=false){cancelRequests();connection=c;directory=dir;sort=order;selectedId=id;mutable.value=PlayerUiState(initialId=id);request=viewModelScope.launch{try{val path=runCatching{api.path(c,dir).joinToString(" › "){it.name}}.getOrDefault("");ensureActive();mutable.value=mutable.value.copy(pathLabel=path);if(photo){mutable.value=mutable.value.copy(photo=api.media(c,id));return@launch};val page=api.page(c,dir,order,anchor=id,kind="video");window.reset(page);canNext=true;canPrevious=true;mutable.value=mutable.value.copy(items=window.items,endReached=window.next.isEmpty())}catch(e:CancellationException){throw e}catch(_:Exception){mutable.value=mutable.value.copy(error=true)}}}
 fun page(previous:Boolean){if(paginating||request?.isActive==true||(!previous&&!canNext)||(previous&&!canPrevious))return;val cursor=if(previous)window.previous else window.next;if(cursor.isEmpty())return;paginating=true;val round=generation;pageRequest=viewModelScope.launch{try{val p=api.page(connection,directory,sort,cursor,previous,kind="video");ensureActive();if(round!=generation)return@launch;if(p.items.isEmpty()){if(previous)canPrevious=false else canNext=false}else{if(previous){window.prepend(p);canNext=true}else{window.append(p);canPrevious=true};mutable.value=mutable.value.copy(items=window.items,endReached=window.next.isEmpty())}}catch(e:CancellationException){throw e}catch(_:RevisionChanged){if(round==generation)open(connection,directory,sort,selectedId)}catch(_:Exception){if(round==generation)mutable.value=mutable.value.copy(error=true)}finally{if(round==generation)paginating=false}}}
 fun retry(id:String){retryRequest?.cancel();val round=generation;retryRequest=viewModelScope.launch{try{val refreshed=api.media(connection,id);ensureActive();if(round==generation)mutable.value=mutable.value.copy(items=mutable.value.items.map{if(it.id==id)refreshed else it},error=false)}catch(e:CancellationException){throw e}catch(_:Exception){if(round==generation)mutable.value=mutable.value.copy(error=true)}}}
 fun leave(){cancelRequests();mutable.value=PlayerUiState()}
}
