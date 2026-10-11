package dev.vvideo.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun animationsEnabled():Boolean {
 val resolver=LocalContext.current.contentResolver
 var enabled by remember{mutableStateOf(true)}
 val scope=rememberCoroutineScope()
 DisposableEffect(resolver){
  fun refresh(){scope.launch{enabled=withContext(Dispatchers.IO){Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f}}}
  val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){refresh()}}
  resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer);refresh()
  onDispose{resolver.unregisterContentObserver(observer)}
 }
 return enabled
}
