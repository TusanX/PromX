package io.github.currencortex.music

import android.graphics.Color
import android.graphics.Typeface
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.currencortex.music.core.update.UpdateInstallAction
import io.github.currencortex.music.ui.CurrentMusicApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchPlayer: ExoPlayer? = null

    override fun onStart() {
        super.onStart()
        (application as CurrentMusicApplication).container.appForeground.value = true
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            (application as CurrentMusicApplication).container.appForeground.value = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        launchPlayer?.release()
        launchPlayer = null
        super.onDestroy()
    }

    private val desktopRequest = androidx.compose.runtime.mutableIntStateOf(0)
    private val recognitionRequest = androidx.compose.runtime.mutableIntStateOf(0)

    private fun handleRecognition(request: android.content.Intent) {
        if (request.getBooleanExtra(io.github.currencortex.music.feature.recognition.RecognitionDesktopService.OPEN, false)) {
            request.removeExtra(io.github.currencortex.music.feature.recognition.RecognitionDesktopService.OPEN)
            recognitionRequest.intValue++
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleRecognition(intent)
        handleUpdateView(intent)
        if (intent.getBooleanExtra(io.github.currencortex.music.core.update.WorkManagerUpdateBackground.OPEN, false)) {
            (application as CurrentMusicApplication).container.updates.reopenDownload()
            intent.removeExtra(io.github.currencortex.music.core.update.WorkManagerUpdateBackground.OPEN)
        }
        if (intent.getBooleanExtra(io.github.currencortex.music.feature.lyrics.desktop.DesktopLyricsHost.OPEN, false)) desktopRequest.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRecognition(intent)
        if (intent.getBooleanExtra(io.github.currencortex.music.feature.lyrics.desktop.DesktopLyricsHost.OPEN, false)) desktopRequest.intValue++
        val splashExited = CompletableDeferred<Unit>()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener {
                it.remove()
                splashExited.complete(Unit)
            }
        } else {
            splashExited.complete(Unit)
        }
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val app = application as CurrentMusicApplication
        if (intent.getBooleanExtra(io.github.currencortex.music.core.update.WorkManagerUpdateBackground.OPEN, false)) {
            app.container.updates.reopenDownload()
            intent.removeExtra(io.github.currencortex.music.core.update.WorkManagerUpdateBackground.OPEN)
        }
        val animateLaunch = app.consumeLaunchAnimation() && savedInstanceState == null
        installContent(app, animateLaunch, splashExited)
        handleUpdateView(intent)
    }

    private fun installContent(
        app: CurrentMusicApplication,
        showGlassLaunch: Boolean,
        splashExited: CompletableDeferred<Unit>,
    ) {
        val root = FrameLayout(this).apply { setBackgroundColor(LAUNCH_BACKGROUND) }
        var composeMounted = false
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        val mountCompose = {
            if (!composeMounted) {
                composeMounted = true
                composeView.setContent {
                    CurrentMusicApp(
                        container = app.container,
                        animateLaunch = false,
                        openDesktopRequest = desktopRequest.intValue,
                        openRecognitionRequest = recognitionRequest.intValue,
                    )
                }
            }
        }
        root.addView(composeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (showGlassLaunch) installGlassLaunch(root, composeView, mountCompose, splashExited) else mountCompose()
    }

    private fun installGlassLaunch(
        root: FrameLayout,
        composeView: ComposeView,
        mountCompose: () -> Unit,
        splashExited: CompletableDeferred<Unit>,
    ) {
        val player = ExoPlayer.Builder(this).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri("android.resource://${packageName}/${R.raw.promx_glass_reveal_black}"))
        }
        launchPlayer = player
        val metrics = resources.displayMetrics
        val side = minOf(metrics.widthPixels, metrics.heightPixels)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val scrim = View(this).apply { setBackgroundColor(LAUNCH_BACKGROUND) }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val wordmark = TextView(this).apply {
            text = "PROMX"
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = .16f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.listening_outfit_black)
                ?: Typeface.create("sans-serif-black", Typeface.BOLD)
            alpha = 0f
            translationY = 18f * resources.displayMetrics.density
        }
        val density = resources.displayMetrics.density
        // The final video frame keeps the note in the lower part of its square. Anchor the
        // wordmark from that visible edge so the gap stays consistent on different screens.
        val portrait = metrics.heightPixels > metrics.widthPixels
        val identityLift = if (portrait) minOf(52f * density, metrics.heightPixels * .045f) else 0f
        val titleTop = metrics.heightPixels / 2f + side * .20f - identityLift + 48f * density
        val identity = FrameLayout(this).apply {
            visibility = View.VISIBLE
            addView(
                wordmark,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = titleTop.toInt()
                },
            )
        }
        val videoView = TextureView(this).apply {
            isClickable = false
            translationY = -identityLift
        }
        root.addView(videoView, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        root.addView(identity, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        player.setVideoTextureView(videoView)

        var videoFinished = false
        var contentDrawn = false
        var revealing = false
        var contentMountStarted = false
        var wordmarkRevealStarted = false
        fun revealWordmarkFromFirstFrame() {
            if (wordmarkRevealStarted) return
            wordmarkRevealStarted = true
            wordmark.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(WORDMARK_REVEAL_MS)
                .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f))
                .start()
        }
        fun revealWhenReady() {
            if (revealing || !videoFinished || !contentDrawn) return
            revealing = true
            identity.animate()
                .alpha(0f)
                .setDuration(LAUNCH_FADE_MS)
                .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f))
                .start()
            videoView.animate()
                .alpha(0f)
                .setDuration(LAUNCH_FADE_MS)
                .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f))
                .start()
            scrim.animate()
                .alpha(0f)
                .setDuration(LAUNCH_FADE_MS)
                .withEndAction {
                    if (videoView.parent === root) root.removeView(videoView)
                    if (identity.parent === root) root.removeView(identity)
                    if (scrim.parent === root) root.removeView(scrim)
                    if (launchPlayer === player) launchPlayer = null
                    player.release()
                    WindowCompat.getInsetsController(window, root).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = true
                    }
                }
                .start()
        }
        fun mountContent() {
            if (contentMountStarted) return
            contentMountStarted = true
            mountCompose()
            composeView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (composeView.viewTreeObserver.isAlive) {
                        composeView.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    contentDrawn = true
                    revealWhenReady()
                    return true
                }
            })
        }

        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Warm the home tree behind the opaque launch scrim after the video's first
                // frame is safely on screen. This keeps decode startup clean while ensuring
                // the reveal does not wait on a cold Compose mount after the video ends.
                root.postDelayed({ mountContent() }, 120L)
                revealWordmarkFromFirstFrame()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    mountContent()
                    // Keep the decoder's last frame on screen for a full second. The home tree
                    // is already loaded underneath and then fades in directly after this hold.
                    root.postDelayed({
                        videoFinished = true
                        revealWhenReady()
                    }, LAST_FRAME_HOLD_MS)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                mountContent()
                root.postDelayed({
                    videoFinished = true
                    revealWhenReady()
                }, LAST_FRAME_HOLD_MS)
            }
        })
        lifecycleScope.launch {
            splashExited.await()
            if (ValueAnimator.areAnimatorsEnabled()) {
                player.prepare()
                player.play()
            } else {
                // Respect Android's remove-animations accessibility setting: keep the
                // identity legible, but do not force the full-screen motion sequence.
                videoView.visibility = View.INVISIBLE
                wordmark.alpha = 1f
                wordmark.translationY = 0f
                videoFinished = true
                root.postDelayed({ mountContent() }, 120L)
                revealWhenReady()
            }
        }
    }

    private fun handleUpdateView(request: android.content.Intent) {
        if (!request.getBooleanExtra(UpdateInstallAction.EXTRA, false)) return
        request.removeExtra(UpdateInstallAction.EXTRA)
        (application as CurrentMusicApplication).container.updates.reopenDownload()
    }

    private companion object {
        val LAUNCH_BACKGROUND = Color.BLACK
        const val WORDMARK_REVEAL_MS = 900L
        const val LAST_FRAME_HOLD_MS = 1_000L
        const val LAUNCH_FADE_MS = 220L
    }
}
