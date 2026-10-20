package dev.vvideo.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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

// The system animator setting, observed once by the theme rather than by every
// animated element.
val LocalAnimations=compositionLocalOf{true}

// Durations of the in-page state changes; page and viewer transitions keep
// their own timings in the navigation code.
object Motion {
 const val FADE=150
 const val CHANGE=200
}
// Opacity changes run at a constant rate; size and position use the standard
// curve. Both collapse to an instant change when system animations are off.
@Composable fun <T> fade(ms:Int=Motion.FADE):FiniteAnimationSpec<T> = tween(if(LocalAnimations.current)ms else 0,easing=LinearEasing)
@Composable fun <T> move(ms:Int=Motion.CHANGE):FiniteAnimationSpec<T> = tween(if(LocalAnimations.current)ms else 0,easing=FastOutSlowInEasing)
// A bar that appears or leaves inside a column grows or shrinks with it, so the
// content below slides instead of jumping.
@Composable fun barEnter():EnterTransition=fadeIn(fade())+expandVertically(move(),expandFrom=androidx.compose.ui.Alignment.Top)
@Composable fun barExit():ExitTransition=fadeOut(fade())+shrinkVertically(move(),shrinkTowards=androidx.compose.ui.Alignment.Top)
