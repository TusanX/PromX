// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A resolved video presentation. DASH uses a generated MPD; progressive sources use their HTTP URL. */
internal data class BiliPlayerMedia(
    val uri: String,
    val mimeType: String?,
    val qualityQn: Int,
    val qualityLabel: String,
)

internal data class BiliPlayerSessionState(
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val qualityQn: Int = 0,
    val qualityLabel: String = "",
    val error: String? = null,
)

/**
 * Owns the ExoPlayer used by one Bilibili video page. Nothing here touches MusicService or the
 * app's shared audio queue, so closing a video cannot stop, replace, or rewrite music playback.
 */
@OptIn(UnstableApi::class)
internal class BiliPlayerSession(context: Context, cookie: String) {
    private val appContext = context.applicationContext
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var released = false

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(requestProperties(cookie))
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(25_000)

    var player: ExoPlayer = buildPlayer()
        private set

    private val _state = MutableStateFlow(BiliPlayerSessionState())
    val state: StateFlow<BiliPlayerSessionState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = when {
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "网络连接中断，请检查网络后重试"
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "视频线路拒绝了请求，可能已过期，请重新加载"
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "当前设备无法解码这条视频，请降低清晰度"
                else -> error.localizedMessage ?: "视频播放失败"
            }
            publish(error = message)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Recreates a released ExoPlayer when a route-backed ViewModel is opened again. */
    fun reopen(cookie: String) {
        httpFactory.setDefaultRequestProperties(requestProperties(cookie))
        if (!released) return
        released = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = buildPlayer()
        player.addListener(listener)
        _state.value = BiliPlayerSessionState()
    }

    fun setMedia(media: BiliPlayerMedia, startPositionMs: Long, playWhenReady: Boolean) {
        if (released) return
        val builder = MediaItem.Builder().setUri(media.uri)
        media.mimeType?.let(builder::setMimeType)
        player.setMediaItem(builder.build())
        player.prepare()
        player.seekTo(startPositionMs.coerceAtLeast(0L))
        player.playWhenReady = playWhenReady
        _state.value = _state.value.copy(
            prepared = true,
            buffering = true,
            ended = false,
            speed = player.playbackParameters.speed,
            qualityQn = media.qualityQn,
            qualityLabel = media.qualityLabel,
            error = null,
        )
        startTicker()
    }

    fun playPause() {
        if (released) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.playWhenReady = !player.playWhenReady
        publish()
    }

    fun play() {
        if (released) return
        player.playWhenReady = true
        publish()
    }

    fun pause() {
        if (released) return
        player.playWhenReady = false
        publish()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    fun seekBy(deltaMs: Long) {
        if (released) return
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    fun setSpeed(speed: Float) {
        if (released) return
        player.setPlaybackSpeed(speed.coerceIn(.5f, 2f))
        publish()
    }

    fun retry() {
        if (released) return
        player.prepare()
        _state.value = _state.value.copy(error = null, buffering = true)
    }

    fun snapshot(): BiliPlayerSessionState = _state.value

    fun release() {
        if (released) return
        released = true
        ticker?.cancel()
        scope.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && !released) {
                publish()
                delay(250L)
            }
        }
    }

    private fun publish(error: String? = null) {
        if (released) return
        val duration = player.duration.takeIf { it > 0L } ?: _state.value.durationMs
        _state.value = _state.value.copy(
            prepared = player.playbackState != Player.STATE_IDLE,
            playing = player.isPlaying,
            playWhenReady = player.playWhenReady,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            error = error ?: _state.value.error,
        )
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(appContext)
        // The generated DASH manifest is a data: URI. DefaultDataSource handles that locally
        // while routing the child video and audio URLs through Bilibili's request headers.
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, httpFactory)))
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }

    companion object {
        const val BILI_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

        const val DASH_MIME = MimeTypes.APPLICATION_MPD
        const val MP4_MIME = MimeTypes.VIDEO_MP4

        private fun requestProperties(cookie: String): Map<String, String> = buildMap {
            put("User-Agent", BILI_USER_AGENT)
            put("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }
}
