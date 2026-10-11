package dev.vvideo.data.cache

import android.content.Context
import android.os.StatFs
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.playback.PlaybackSession
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toOkioPath

data class CacheUsage(val video:Long=0,val images:Long=0,val overhead:Long=0,val lowSpace:Boolean=false)
@UnstableApi
class CacheCoordinator(private val context:Context) {
 private val mutex=Mutex()
 private var video:VideoCache?=null
 private val sessions=mutableSetOf<PlaybackSession>()
 private val mutableLoader=MutableStateFlow<ImageLoader?>(null)
 val loader=mutableLoader.asStateFlow()
 suspend fun configure(budget:Long,clear:Boolean=false)=mutex.withLock {
  withContext(Dispatchers.Main.immediate){sessions.toList().forEach{it.release()};sessions.clear()}
  withContext(Dispatchers.IO){
   val existingBytes=(video?.bytes()?:0)+(mutableLoader.value?.diskCache?.size?:0)
   mutableLoader.value?.let { old -> old.shutdown();if(clear)old.diskCache?.clear();old.diskCache?.shutdown() }
   video?.close(clear)
   val disk=StatFs(context.cacheDir.absolutePath)
   val reclaimable=(disk.availableBytes+if(clear)0 else existingBytes).coerceAtLeast(64*1024*1024)
   val usable=minOf(budget,(reclaimable-minOf(disk.totalBytes/20,1_000_000_000L)).coerceAtLeast(64*1024*1024))
   video=VideoCache.open(context,usable*9/10)
   val imageDir=File(context.cacheDir,"images")
   mutableLoader.value=ImageLoader.Builder(context).memoryCache{MemoryCache.Builder().maxSizeBytes(32L*1024*1024).build()}.diskCache{DiskCache.Builder().directory(imageDir.toOkioPath()).maxSizeBytes(usable/10).build()}.build()
  }
 }
 suspend fun session(token:String):PlaybackSession=mutex.withLock {
  var created:PlaybackSession?=null
  try {
   withContext(Dispatchers.Main.immediate) {
    val previous=sessions.toList();sessions.clear()
    withContext(NonCancellable){previous.forEach{it.release()}}
    ensureActive()
    PlaybackSession(context,checkNotNull(video),token).also{created=it;sessions+=it}
   }
  } catch(e:CancellationException) {
   // A dispatcher can cancel the return after construction succeeds. The
   // route never receives ownership in that case, so the coordinator releases
   // the pending player before unlocking and allowing another session.
   withContext(NonCancellable+Dispatchers.Main.immediate){created?.let{if(sessions.remove(it))it.release()}}
   throw e
  }
 }
 suspend fun release(session:PlaybackSession)=mutex.withLock{withContext(Dispatchers.Main.immediate){if(sessions.remove(session))session.release()}}
 suspend fun cachedVideoBytes(key:String):Long=mutex.withLock{video?.bytesFor(key)?:0}
 suspend fun usage():CacheUsage=mutex.withLock{withContext(Dispatchers.IO){val overhead=context.databaseList().filter{it.contains("exoplayer")}.sumOf{context.getDatabasePath(it).length()};CacheUsage(video?.bytes()?:0,mutableLoader.value?.diskCache?.size?:0,overhead,video?.hasPreloadSpace()==false)}}
}
