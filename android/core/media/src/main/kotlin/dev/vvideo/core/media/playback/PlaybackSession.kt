package dev.vvideo.core.media.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.preload.NeighborPreloader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class PlaybackState(
    val id: String = "",
    val firstFrame: Boolean = false,
    val error: Boolean = false,
    val buffering: Boolean = true
)

@UnstableApi
class PlaybackSession(context: Context, cache: VideoCache, token: String) {
    private val preloader = NeighborPreloader(cache)
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var selectedAt = 0L
    private var closed = false
    private val renderers = SeekRenderersFactory(context.applicationContext)
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext, renderers)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                CacheDataSource.Factory().setCache(cache.cache).setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000)
                        .setReadTimeoutMs(10000)
                        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
                )
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(2000, 10000, 500, 1000)
                .setTargetBufferBytes(32 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false)
                .build()
        ).build()

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                mutable.value = mutable.value.copy(error = true, buffering = false)
                preloader.target(null)
            }

            override fun onPlaybackStateChanged(state: Int) {
                mutable.value = mutable.value.copy(buffering = state == Player.STATE_BUFFERING)
                if (state == Player.STATE_BUFFERING) preloader.target(null)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                if (eventTime.timeline.isEmpty) return
                val id = eventTime.timeline.getWindow(
                    eventTime.windowIndex,
                    Timeline.Window()
                ).mediaItem.mediaId
                if (id != mutable.value.id || mutable.value.firstFrame) return
                mutable.value = mutable.value.copy(firstFrame = true, buffering = false)
                Log.i(
                    "VVideoPlayback",
                    "first_frame_ms=${SystemClock.elapsedRealtime() - selectedAt}"
                )
            }
        })
    }

    fun select(item: PlaybackItem, selectionBeganMs: Long = SystemClock.elapsedRealtime()) {
        check(!closed)
        if (mutable.value.id == item.cacheKey && !mutable.value.error) {
            player.play()
            return
        }
        preloader.target(null)
        player.stop()
        player.clearMediaItems()
        mutable.value = PlaybackState(id = item.cacheKey)
        selectedAt = selectionBeganMs
        player.setMediaItem(
            MediaItem.Builder().setMediaId(item.cacheKey).setUri(item.url)
                .setCustomCacheKey(item.cacheKey).build()
        )
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() {
        if (!closed) {
            player.pause()
            preloader.target(null)
        }
    }

    fun preload(item: PlaybackItem?) {
        if (!closed && !mutable.value.buffering) preloader.target(item)
    }

    suspend fun release() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            try {
                player.stop()
                player.clearMediaItems()
            } finally {
                try {
                    player.release()
                } finally {
                    preloader.close()
                }
            }
        }
    }
}
