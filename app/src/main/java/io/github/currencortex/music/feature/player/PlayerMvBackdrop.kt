package io.github.currencortex.music.feature.player

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.currencortex.music.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

internal data class PlayerMvSource(val url: String, val referer: String)

/** A muted looping MV layer. Audio remains owned by MusicService and the subtitle clock stays unchanged. */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerMvBackdrop(source: PlayerMvSource, playing: Boolean, active: Boolean,
    positionMs: Long, timelineRevision: Long, onError: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val currentError by rememberUpdatedState(onError)
    val trackSelector = remember {
        DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }
    val mediaSourceFactory = remember(source.referer) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf(
                "Referer" to source.referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13)",
            ))
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        DefaultMediaSourceFactory(http)
    }
    val player = remember(source.referer) {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
            }
    }
    val latest by rememberUpdatedState(MvClock(active, positionMs))

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                currentError("MV 背景播放失败，请重新打开")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, source.url) {
        player.setMediaItem(MediaItem.fromUri(source.url))
        player.prepare()
        player.seekTo(positionMs.coerceAtLeast(0))
    }
    LaunchedEffect(player, playing, active, lifecycleState) {
        player.playWhenReady = active && playing && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    }
    LaunchedEffect(player, timelineRevision) {
        val target = positionMs.coerceAtLeast(0)
        if (abs(player.currentPosition - target) > 600) player.seekTo(target)
    }
    LaunchedEffect(player, source.url) {
        while (isActive) {
            val clock = latest
            if (clock.active && !player.isLoading) {
                val drift = abs(player.currentPosition - clock.positionMs)
                if (drift > 1_800) player.seekTo(clock.positionMs.coerceAtLeast(0))
            }
            delay(1_000)
        }
    }

    Box(modifier.background(Color.Black).testTag("player_mv_backdrop")) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_bili_player, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .28f),
            Color.Black.copy(alpha = .14f),
            Color.Black.copy(alpha = .62f),
            Color.Black.copy(alpha = .82f),
        ))))
    }
}

private data class MvClock(val active: Boolean, val positionMs: Long)
