package dev.vvideo.core.media.cache

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(UnstableApi::class)
class VideoCacheDeviceTest {
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun isolatedCacheEvictionAndVersionKeys() = runTest {
        withContext(Dispatchers.IO) {
            val directory = withContext(Dispatchers.IO) {
                File(
                    application.cacheDir,
                    "eviction-fixture",
                ).apply { mkdirs() }
            }
            val context = object : android.content.ContextWrapper(application) {
                override fun getCacheDir(): File = directory
                override fun getApplicationContext(): android.content.Context = this
                override fun getDatabasePath(name: String): File = File(directory, name)
            }
            var cache = dev.vvideo.core.media.cache.VideoCache.open(context, 64 * 1024L, Dispatchers.IO)
            try {
                suspend fun fill(key: String, value: Byte) = withContext(Dispatchers.IO) {
                    val span = checkNotNull(cache.cache.startReadWrite(key, 0, 40 * 1024L))
                    assertTrue(span.isHoleSpan)
                    try {
                        val file = cache.cache.startFile(key, 0, 40 * 1024L)
                        file.writeBytes(ByteArray(40 * 1024) { value })
                        cache.cache.commitFile(file, 40 * 1024L)
                    } finally {
                        cache.cache.releaseHoleSpan(span)
                    }
                }
                fill("fixture:old-version", 1)
                assertEquals(40 * 1024L, cache.bytesFor("fixture:old-version"))
                fill("fixture:new-version", 2)
                assertEquals(0L, cache.bytesFor("fixture:old-version"))
                assertEquals(40 * 1024L, cache.bytesFor("fixture:new-version"))
                assertTrue(cache.bytes() <= 64 * 1024L)
                cache.close(false)
                cache = dev.vvideo.core.media.cache.VideoCache.open(context, 64 * 1024L, Dispatchers.IO)
                assertEquals(40 * 1024L, cache.bytesFor("fixture:new-version"))
                assertEquals(0L, cache.bytesFor("fixture:old-version"))
                fill("fixture:third", 3)
                assertEquals(0L, cache.bytesFor("fixture:new-version"))
                assertTrue(cache.bytes() <= 64 * 1024L)
                Log.i("VVideoValidation", "isolated_lru_version_reopen=true")
            } finally {
                cache.close(true)
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
            }
        }
    }
}
