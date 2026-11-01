package dev.vvideo.core.media.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.vvideo.core.media.cache.VideoCache
import dev.vvideo.core.media.model.PlaybackItem
import dev.vvideo.core.media.preload.NeighborPreloader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "VVideoPlayback"

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
    /** The video track cannot be extracted or decoded on this device. */
    val unsupported: Boolean = false,
    val buffering: Boolean = true,
)

/**
 * One player with its cache and preloader. [client] is the app's media client,
 * which already authorizes requests; [io] runs the preloader.
 */
@OptIn(UnstableApi::class)
class PlaybackSession(context: Context, cache: VideoCache, client: OkHttpClient, io: CoroutineDispatcher) {
    private val preloader = NeighborPreloader(cache, client, io)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var selectedAt = 0L
    private var closed = false
    private val renderers = SeekRenderersFactory(context.applicationContext)
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext, renderers)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                CacheDataSource.Factory().setCache(cache.cache)
                    .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client)),
            ),
        )
        .setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_MS,
            ).setTargetBufferBytes(TARGET_BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        ).build()
    val player: Player get() = exoPlayer

    init {
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
        exoPlayer.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = true, buffering = false) }
                preloader.target(null)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Media3 drops a video track it cannot extract or decode and
                // plays on without a picture; report it instead of spinning.
                if (state == Player.STATE_READY &&
                    !player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)
                ) {
                    _state.update { it.copy(error = true, unsupported = true, buffering = false) }
                    preloader.target(null)
                    player.pause()
                    return
                }
                _state.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_BUFFERING) preloader.target(null)
            }
        })
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.timeline.isEmpty) return
                val id = eventTime.timeline.getWindow(
                    eventTime.windowIndex,
                    Timeline.Window(),
                ).mediaItem.mediaId
                if (id != _state.value.id || _state.value.firstFrame) return
                _state.update { it.copy(firstFrame = true, buffering = false) }
                Log.i(
                    TAG,
                    "first_frame_ms=${SystemClock.elapsedRealtime() - selectedAt}",
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
        _state.update { PlaybackState(id = item.cacheKey) }
        selectedAt = selectionBeganMs
        player.setMediaItem(
            MediaItem.Builder().setMediaId(item.cacheKey).setUri(item.url)
                .setCustomCacheKey(item.cacheKey).build(),
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
