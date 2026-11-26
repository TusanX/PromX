// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.R
import io.github.currencortex.music.feature.bili.BiliDownloader
import io.github.currencortex.music.feature.player.PlayerSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

private val PlayerBackground = Color(0xFF07080A)
private val PlayerUiText = Color(0xFFF6F7F9)
private val PlayerUiMuted = Color(0xFFADB1B9)

/**
 * Standalone Bilibili video route. The player owns its own ExoPlayer for the lifetime of this
 * composition and never reads or mutates PromX's shared music playback state.
 */
@Composable
fun BiliPlayerScreen(
    container: AppContainer,
    bvid: String,
    initialCid: String = "",
    onBack: () -> Unit,
    onCreator: (Long) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val playerViewModel: BiliPlayerViewModel = viewModel(
        key = "bili-native-player-$bvid-$initialCid",
        factory = viewModelFactory { initializer { BiliPlayerViewModel(application) } },
    )
    val ui by playerViewModel.ui.collectAsStateWithLifecycle()
    val playback by playerViewModel.session.state.collectAsStateWithLifecycle()
    val cookie = remember(container) { container.biliSession.cookieHeader() }
    val csrf = remember(container) { container.biliSession.cookies["bili_jct"].orEmpty() }
    val activity = LocalActivity.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(BiliPlayerPanel.NONE) }
    var danmakuEnabled by rememberSaveable(bvid) { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintRevision by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var showComments by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var downloading by remember(bvid) { mutableStateOf(false) }
    var downloadProgress by remember(bvid) { mutableFloatStateOf(0f) }

    fun reveal() {
        controlsVisible = true
        interaction++
    }

    fun showHint(value: String) {
        hint = value
        hintRevision++
    }

    fun closePlayer() {
        playerViewModel.shutdown()
        onBack()
    }

    BackHandler {
        if (panel != BiliPlayerPanel.NONE) panel = BiliPlayerPanel.NONE else closePlayer()
    }
    PlayerSystemBars(immersive = true, hideNavigation = landscape)

    LaunchedEffect(bvid, initialCid, cookie, csrf) {
        playerViewModel.load(bvid, initialCid, cookie, csrf)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playerViewModel.pauseForBackground()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            playerViewModel.pauseForBackground()
        }
    }
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            if (activity != null && previous != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = previous
            }
        }
    }
    LaunchedEffect(controlsVisible, playback.playWhenReady, interaction, panel) {
        if (controlsVisible && playback.playWhenReady && panel == BiliPlayerPanel.NONE && !scrubbing) {
            delay(3_200L)
            if (playback.playWhenReady && panel == BiliPlayerPanel.NONE && !scrubbing) controlsVisible = false
        }
    }
    LaunchedEffect(hint, hintRevision) {
        if (hint != null) {
            delay(1_250L)
            hint = null
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { message ->
            showHint(message)
            delay(2_100L)
            playerViewModel.clearMessage()
        }
    }

    val displayedPlayback = if (scrubbing) playback.copy(positionMs = scrubPosition) else playback
    val playerSurface: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black).testTag("bili_native_player_surface")) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext).inflate(R.layout.view_bili_player, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        this.player = playerViewModel.session.player
                    }
                },
                update = { view ->
                    view.player = playerViewModel.session.player
                    view.useController = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            BiliDanmakuOverlay(
                items = ui.danmaku,
                visible = danmakuEnabled,
                playing = playback.playing,
                positionMs = playback.positionMs,
                speed = playback.speed,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize()
                    .biliPlayerGestures(
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) interaction++
                        },
                        onTogglePlayback = {
                            playerViewModel.session.playPause()
                            reveal()
                        },
                        onSeekBy = { delta ->
                            playerViewModel.session.seekBy(delta)
                            showHint(if (delta < 0L) "后退 10 秒" else "前进 10 秒")
                            reveal()
                        },
                        onSeekPreview = {
                            scrubbing = true
                            scrubPosition = it
                            reveal()
                        },
                        onSeekCommit = { position ->
                            playerViewModel.session.seekTo(position)
                            scrubbing = false
                            showHint(timeText(position))
                            reveal()
                        },
                    ),
            )
            BiliPlayerControlsOverlay(
                title = ui.info?.title.orEmpty(),
                uploader = ui.info?.owner.orEmpty(),
                landscape = landscape,
                visible = controlsVisible,
                playback = displayedPlayback,
                loadingSource = ui.sourceLoading,
                danmakuEnabled = danmakuEnabled,
                onBack = ::closePlayer,
                onPlayPause = {
                    playerViewModel.session.playPause()
                    reveal()
                },
                onSeekPreview = {
                    scrubbing = true
                    scrubPosition = it
                    reveal()
                },
                onSeekCommit = { position ->
                    playerViewModel.session.seekTo(position)
                    scrubbing = false
                    reveal()
                },
                onToggleDanmaku = {
                    danmakuEnabled = !danmakuEnabled
                    showHint(if (danmakuEnabled) "弹幕已开启" else "弹幕已关闭")
                    reveal()
                },
                onPanel = { next ->
                    panel = next
                    reveal()
                },
                onToggleOrientation = {
                    activity?.requestedOrientation = if (landscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    reveal()
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (ui.sourceLoading) {
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .68f)).padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), size = 16.dp, strokeWidth = 1.8.dp)
                    Text(if (playback.prepared) "正在切换清晰度" else "正在解析视频线路", color = PlayerUiText, fontSize = 11.sp)
                }
            }

            val playableError = ui.sourceError ?: playback.error
            if (!playableError.isNullOrBlank() && !ui.sourceLoading) {
                Column(
                    Modifier.align(Alignment.Center).padding(22.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = .86f)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(playableError, color = PlayerUiText, fontSize = 12.sp, lineHeight = 18.sp)
                    Text(
                        "重新加载",
                        Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFFFB7299))
                            .clickable {
                                playerViewModel.retrySource()
                                reveal()
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = hint != null,
                enter = fadeIn(tween(130)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    hint.orEmpty(),
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .72f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = PlayerUiText,
                    fontSize = 12.sp,
                )
            }
        }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(PlayerBackground)) {
            playerSurface(Modifier.fillMaxSize())
        }
    } else {
        Column(Modifier.fillMaxSize().background(PlayerBackground)) {
            playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            BiliPlayerInfo(
                state = ui,
                onLike = playerViewModel::toggleLike,
                onComments = { showComments = true },
                onFavorite = {
                    if (cookie.isBlank()) showHint("请先登录哔哩哔哩") else showFavorites = true
                },
                onShare = {
                    val title = ui.info?.title.orEmpty()
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "$title\nhttps://www.bilibili.com/video/$bvid")
                                },
                                "分享哔哩哔哩视频",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onDownload = {
                    if (!downloading && ui.info != null) {
                        downloading = true
                        downloadProgress = 0f
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                BiliDownloader.download(
                                    context = context,
                                    bvid = bvid,
                                    title = ui.info?.title.orEmpty(),
                                    cookie = cookie,
                                    onProgress = { downloadProgress = it },
                                )
                            }
                            downloading = false
                            showHint(when (result) {
                                is BiliDownloader.Result.Ok -> "已下载到 ${result.path}"
                                is BiliDownloader.Result.Failed -> "下载失败：${result.message}"
                            })
                        }
                    }
                },
                downloading = downloading,
                downloadProgress = downloadProgress,
                onPart = { page ->
                    playerViewModel.playPart(page)
                    showHint("正在切换到 P${page.page}")
                },
                onRelated = { video ->
                    playerViewModel.openVideo(video.bvid)
                    showHint("正在打开相关推荐")
                },
                onCreator = onCreator,
                modifier = Modifier.weight(1f),
            )
        }
    }

    BiliPlayerSettingsSheet(
        panel = panel,
        qualities = ui.qualities,
        selectedQn = playback.qualityQn,
        speed = playback.speed,
        onQuality = { option ->
            panel = BiliPlayerPanel.NONE
            playerViewModel.selectQuality(option.qn)
            reveal()
        },
        onSpeed = { speed ->
            playerViewModel.session.setSpeed(speed)
            panel = BiliPlayerPanel.NONE
            showHint("${speedLabel(speed)} 播放")
            reveal()
        },
        onDismiss = {
            panel = BiliPlayerPanel.NONE
            reveal()
        },
    )

    if (showComments && !ui.info?.aid.isNullOrBlank()) {
        BiliPlayerCommentsSheet(
            aid = ui.info?.aid.orEmpty(),
            cookie = cookie,
            csrf = csrf,
            total = ui.stats?.reply ?: 0L,
            onDismiss = { showComments = false },
        )
    }
    if (showFavorites) {
        BiliPlayerFavoriteSheet(
            container = container,
            bvid = bvid,
            onDismiss = { showFavorites = false },
        )
    }
}
