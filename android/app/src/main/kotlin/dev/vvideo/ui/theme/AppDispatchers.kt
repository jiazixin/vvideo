package dev.vvideo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher

/** The dispatchers classes receive from the app graph instead of naming `Dispatchers` themselves. */
internal class AppDispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val main: CoroutineDispatcher,
)

/** The app graph's dispatchers, for composables that move work off the main thread. */
internal val LocalDispatchers = staticCompositionLocalOf<AppDispatchers> { error("AppDispatchers not provided") }
