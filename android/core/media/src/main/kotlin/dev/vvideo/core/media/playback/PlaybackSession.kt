package dev.vvideo.core.media.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.datasource.authorizationHeaders
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.preload.NeighborPreloader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val CONNECT_TIMEOUT_MS = 5000
private const val READ_TIMEOUT_MS = 10000

// Buffering: keep 2–10 s ahead within 32 MiB; start after 0.5 s, resume after a stall at 1 s.
private const val MIN_BUFFER_MS = 2000
private const val MAX_BUFFER_MS = 10000
private const val START_BUFFER_MS = 500
private const val REBUFFER_MS = 1000
private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

data class PlaybackState(
    val id: String = "",
    val firstFrame: Boolean = false,
    val error: Boolean = false,
    val buffering: Boolean = true
)

@UnstableApi
class PlaybackSession(context: Context, cache: VideoCache, token: String) {
    private val preloader = NeighborPreloader(cache)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var selectedAt = 0L
    private var closed = false
    private val renderers = SeekRenderersFactory(context.applicationContext)
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext, renderers)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                CacheDataSource.Factory().setCache(cache.cache).setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory().setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                        .setReadTimeoutMs(READ_TIMEOUT_MS)
                        .setDefaultRequestProperties(authorizationHeaders(token))
                )
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_MS
            ).setTargetBufferBytes(TARGET_BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false)
                .build()
        ).build()

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = true, buffering = false)
                preloader.target(null)
            }

            override fun onPlaybackStateChanged(state: Int) {
                _state.value = _state.value.copy(buffering = state == Player.STATE_BUFFERING)
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
                if (id != _state.value.id || _state.value.firstFrame) return
                _state.value = _state.value.copy(firstFrame = true, buffering = false)
                Log.i(
                    "VVideoPlayback",
                    "first_frame_ms=${SystemClock.elapsedRealtime() - selectedAt}"
                )
            }
        })
    }

    fun select(item: PlaybackItem, selectionBeganMs: Long = SystemClock.elapsedRealtime()) {
        check(!closed)
        if (_state.value.id == item.cacheKey && !_state.value.error) {
            player.play()
            return
        }
        preloader.target(null)
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState(id = item.cacheKey)
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
        if (!closed && !_state.value.buffering) preloader.target(item)
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
