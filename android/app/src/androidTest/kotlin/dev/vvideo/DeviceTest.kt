package dev.vvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

/** Real-device playback and frame measurements must not use a virtual clock. */
internal fun deviceTest(block: suspend CoroutineScope.() -> Unit) = runTest(timeout = 60.minutes) {
    withContext(Dispatchers.Default, block)
}
