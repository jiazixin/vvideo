package dev.vvideo.feature.settings

import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvideo.R
import dev.vvideo.data.cache.CacheCoordinator
import dev.vvideo.data.cache.CacheUsage
import dev.vvideo.data.settings.SettingsRepository
import dev.vvideo.data.settings.Connection
import dev.vvideo.data.library.remote.LibraryApi
import dev.vvideo.data.library.remote.ServiceFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SettingsUiState(val libraryCount:Int=0,val releasedBytes:Long=0,val busy:Boolean=false,val result:Int=0,val usage:CacheUsage?=null,val connectedSerial:Long=0,val connectionStatus:Int=R.string.connection_saved,val invalidInput:Boolean=false)
@UnstableApi
class SettingsViewModel(private val settings:SettingsRepository,private val cache:CacheCoordinator,private val api:LibraryApi):ViewModel(){
 val theme=settings.theme
 fun theme(value:String){viewModelScope.launch{settings.setTheme(value)}}
 fun dismissResult(){mutable.value=mutable.value.copy(result=0,invalidInput=false)}
 private var verifyJob:Job?=null
 private val mutable=MutableStateFlow(SettingsUiState());val state=mutable.asStateFlow()
 fun usage(){viewModelScope.launch{try{mutable.value=mutable.value.copy(usage=cache.usage())}catch(e:CancellationException){throw e}catch(_:Exception){mutable.value=mutable.value.copy(result=R.string.cache_failed)}}}
 fun verify(c:Connection){if(!c.configured)return;verifyJob?.cancel();verifyJob=viewModelScope.launch{mutable.value=mutable.value.copy(connectionStatus=R.string.connection_checking);try{val libraries=api.libraries(c);mutable.value=mutable.value.copy(libraryCount=libraries.size,connectionStatus=R.string.connection_ok)}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(connectionStatus=if(e is ServiceFailure&&e.status==401)R.string.authentication_failed else R.string.offline)}}}
 fun connect(address:String,token:String)=operation(R.string.error_connection){
  mutable.value=mutable.value.copy(connectionStatus=R.string.connection_checking,invalidInput=false)
  val candidate=try{settings.candidate(address,token)}catch(e:Exception){mutable.value=mutable.value.copy(invalidInput=true);throw e}
  api.libraries(candidate)
  settings.connect(candidate.address,candidate.token)
  mutable.value=mutable.value.copy(connectionStatus=R.string.connection_ok,connectedSerial=mutable.value.connectedSerial+1)
 }
 fun budget(bytes:Long)=operation{cache.configure(bytes);settings.setBudget(bytes)}
 fun clear(bytes:Long)=operation{val before=cache.usage();cache.configure(bytes,true);val after=cache.usage();mutable.value=mutable.value.copy(result=R.string.cache_cleared,releasedBytes=(before.video+before.images+before.overhead-after.video-after.images-after.overhead).coerceAtLeast(0))}
 private fun operation(failure:Int=R.string.cache_failed,block:suspend()->Unit){if(mutable.value.busy)return;viewModelScope.launch{mutable.value=mutable.value.copy(busy=true,result=0);try{block();mutable.value=mutable.value.copy(usage=cache.usage())}catch(e:CancellationException){throw e}catch(e:Exception){val message=if(e is ServiceFailure&&e.status==401)R.string.authentication_failed else if(mutable.value.invalidInput)R.string.invalid_address else failure;mutable.value=mutable.value.copy(result=message,connectionStatus=if(failure==R.string.error_connection)message else mutable.value.connectionStatus)}finally{mutable.value=mutable.value.copy(busy=false)}}}
}
