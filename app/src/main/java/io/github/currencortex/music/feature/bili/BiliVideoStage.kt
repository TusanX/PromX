// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.currencortex.music.R
import io.github.currencortex.music.core.media.MusicService

/**
 * Borrow the single MediaSession player surface for Bilibili video. Compose controls are siblings
 * above this TextureView, so there is still only one player, one timeline and one audio track.
 */
@Composable
fun BiliVideoSurface(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var binder by remember { mutableStateOf<MusicService.VideoBinder?>(null) }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as? MusicService.VideoBinder
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder = null
            }
        }
        context.bindService(
            Intent(context, MusicService::class.java).setAction(MusicService.VIDEO_SURFACE),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose { runCatching { context.unbindService(connection) } }
    }
    AndroidView(
        factory = { buildSurface(it) },
        update = { frame -> (frame.getChildAt(0) as? PlayerView)?.let { binder?.attach(it) } },
        onRelease = { frame -> (frame.getChildAt(0) as? PlayerView)?.let { binder?.detach(it) } },
        modifier = modifier,
    )
}

private fun buildSurface(context: Context): FrameLayout = FrameLayout(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    val playerView = LayoutInflater.from(context).inflate(R.layout.view_bili_player, null) as PlayerView
    playerView.useController = false
    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    playerView.setKeepContentOnPlayerReset(true)
    playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
    addView(
        playerView,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
}
