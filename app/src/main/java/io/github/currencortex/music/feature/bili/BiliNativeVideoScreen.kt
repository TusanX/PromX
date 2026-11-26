// SPDX-License-Identifier: GPL-3.0-only
// Composition hierarchy is adapted from 10miaomiao/bilimiao2's BiliVideoScaffold,
// PlayerTopBar, VideoDetailContent, VideoInfoBox and VideoStatBox (GPL-3.0).
package io.github.currencortex.music.feature.bili

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.core.alt.BiliQuality
import io.github.currencortex.music.core.media.PlayerController
import io.github.currencortex.music.core.media.PlayerState
import io.github.currencortex.music.feature.player.PlayerSystemBars
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import kotlin.math.sin

private val BiliPink = Color(0xFFFB7299)
private val StageBlack = Color(0xFF08090B)
private val SoftWhite = Color(0xFFF6F7F9)
private val MutedWhite = Color(0xFF9BA1AD)

private enum class BiliPanel { NONE, QUALITY, SPEED }
private enum class BiliVideoGlyph { BACK, CLOSE, DOWNLOAD, PLAY, PAUSE, DANMAKU, ORIENTATION, FULLSCREEN, EXIT_FULLSCREEN, LIKE, COMMENT, FAVORITE, SHARE, LOCK }

/**
 * The Bilibili video surface is a separate composition from the audio player page. Video metadata
 * and social actions live below the picture in portrait, while transport controls stay inside the
 * picture and never compete with likes, comments, favorites, or sharing.
 */
@Composable
fun BiliNativeVideoScreen(
    controller: PlayerController,
    bvid: String,
    initialCid: String,
    cookie: String,
    title: String,
    uploader: String,
    initialCategory: String,
    initialDurationMs: Long,
    liked: Boolean,
    likeCount: Long,
    commentCount: Long,
    favoriteCount: Long,
    viewCount: Long,
    onClose: () -> Unit,
    onLike: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onComments: () -> Unit,
    onQualityChanged: () -> Unit,
    onDownload: () -> Unit,
    onRelatedSelected: (BiliPlayback.RelatedVideo) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val originalOrientation = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var cid by rememberSaveable(bvid, initialCid) { mutableStateOf(initialCid) }
    var qualities by remember(bvid) { mutableStateOf<List<BiliQuality.Option>>(emptyList()) }
    var danmaku by remember(bvid) { mutableStateOf<List<BiliPlayback.Danmaku>>(emptyList()) }
    var loadingDanmaku by remember(bvid) { mutableStateOf(false) }
    var danmakuEnabled by rememberSaveable(bvid) { mutableStateOf(true) }
    var metadataError by remember(bvid) { mutableStateOf<String?>(null) }
    var related by remember(bvid) { mutableStateOf<List<BiliPlayback.RelatedVideo>>(emptyList()) }
    var durationSeconds by remember(bvid, initialDurationMs) { mutableIntStateOf((initialDurationMs / 1000).toInt()) }
    var category by remember(bvid, initialCategory) { mutableStateOf(initialCategory) }
    var displayTitle by remember(bvid, title) { mutableStateOf(biliDisplayText(title)) }
    var displayUploader by remember(bvid, uploader) { mutableStateOf(biliDisplayText(uploader)) }
    var ownerAvatar by remember(bvid) { mutableStateOf("") }
    var poster by remember(bvid) { mutableStateOf("") }

    fun restoreOrientation() {
        activity?.requestedOrientation = originalOrientation
    }

    fun close() {
        restoreOrientation()
        onClose()
    }

    LaunchedEffect(bvid, initialCid, cookie) {
        metadataError = null
        val resolvedInfo = withContext(Dispatchers.IO) {
            BiliPlayback.videoInfo(bvid, cookie)
        }
        resolvedInfo?.title?.takeIf(String::isNotBlank)?.let { displayTitle = biliDisplayText(it) }
        resolvedInfo?.owner?.takeIf(String::isNotBlank)?.let { displayUploader = biliDisplayText(it) }
        resolvedInfo?.ownerFace?.let { ownerAvatar = it }
        resolvedInfo?.cover?.let { poster = it }
        if (cid.isBlank()) cid = resolvedInfo?.cid.orEmpty()
        durationSeconds = resolvedInfo?.durationSec ?: durationSeconds
        if (cid.isBlank()) {
            metadataError = "没有取得视频 CID"
            return@LaunchedEffect
        }
        val currentCid = cid
        withContext(Dispatchers.IO) { BiliPlayback.qualityOptions(bvid, currentCid, cookie) }
            .takeIf { it.isNotEmpty() }?.let { qualities = it }
        loadingDanmaku = true
        danmaku = withContext(Dispatchers.IO) { BiliPlayback.danmaku(currentCid) }
        loadingDanmaku = false
        related = withContext(Dispatchers.IO) { BiliPlayback.related(bvid, cookie) }
    }

    DisposableEffect(activity, originalOrientation) {
        onDispose { activity?.requestedOrientation = originalOrientation }
    }

    BackHandler(enabled = true) {
        if (landscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else close()
    }

    PlayerSystemBars(immersive = landscape, hideNavigation = landscape)

    if (landscape) {
        BiliVideoStage(
            controller = controller,
            title = displayTitle,
            uploader = displayUploader,
            landscape = true,
            poster = poster,
            qualities = qualities,
            danmaku = danmaku,
            loadingDanmaku = loadingDanmaku,
            danmakuEnabled = danmakuEnabled,
            onDanmakuEnabled = { danmakuEnabled = it },
            metadataError = metadataError,
            onClose = ::close,
            onDownload = onDownload,
            onRotate = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT },
            onFullscreen = { activity?.requestedOrientation = originalOrientation },
            onQualityChanged = { qn ->
                BiliQuality.preferredQn = qn
                onQualityChanged()
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Column(Modifier.fillMaxSize().background(StageBlack)) {
            BiliVideoPageTopBar(
                title = displayTitle,
                onClose = ::close,
                onDownload = onDownload,
                onShare = onShare,
            )
            BiliVideoStage(
                controller = controller,
                title = displayTitle,
                uploader = displayUploader,
                landscape = false,
                poster = poster,
                qualities = qualities,
                danmaku = danmaku,
                loadingDanmaku = loadingDanmaku,
                danmakuEnabled = danmakuEnabled,
                onDanmakuEnabled = { danmakuEnabled = it },
                metadataError = metadataError,
                onClose = ::close,
                onDownload = onDownload,
                onRotate = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE },
                onFullscreen = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE },
                onQualityChanged = { qn ->
                    BiliQuality.preferredQn = qn
                    onQualityChanged()
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            BiliVideoInformation(
                title = displayTitle,
                uploader = displayUploader,
                ownerAvatar = ownerAvatar,
                viewCount = viewCount,
                durationSeconds = durationSeconds,
                category = category,
                liked = liked,
                likeCount = likeCount,
                commentCount = commentCount,
                favoriteCount = favoriteCount,
                onLike = onLike,
                onComments = onComments,
                onFavorite = onFavorite,
                onShare = onShare,
                related = related,
                onRelatedSelected = onRelatedSelected,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BiliVideoStage(
    controller: PlayerController,
    title: String,
    uploader: String,
    landscape: Boolean,
    poster: String,
    qualities: List<BiliQuality.Option>,
    danmaku: List<BiliPlayback.Danmaku>,
    loadingDanmaku: Boolean,
    danmakuEnabled: Boolean,
    onDanmakuEnabled: (Boolean) -> Unit,
    metadataError: String?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onRotate: () -> Unit,
    onFullscreen: () -> Unit,
    onQualityChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf(BiliPanel.NONE) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf<String?>(null) }
    var holdingSpeed by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    var interaction by remember { mutableIntStateOf(0) }
    val latestState by rememberUpdatedState(state)

    fun revealControls() {
        controlsVisible = true
        interaction++
    }

    fun seekBy(delta: Long) {
        val duration = latestState.durationMs.coerceAtLeast(0)
        if (duration <= 0) return
        val target = (latestState.positionMs + delta).coerceIn(0, duration)
        controller.seek(target)
        hint = (if (delta < 0) "−10 秒" else "+10 秒") + "  " + biliTime(target)
        revealControls()
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_050)
            hint = null
        }
    }
    LaunchedEffect(controlsVisible, panel, scrubbing, state.playing, interaction) {
        if (controlsVisible && panel == BiliPanel.NONE && !scrubbing && state.playing) {
            delay(3_200)
            controlsVisible = false
        }
    }

    Box(modifier.background(Color.Black)) {
        if (poster.isNotBlank() && (state.error != null || state.loading || state.resolving)) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .54f)))
        }
        BiliVideoSurface(Modifier.fillMaxSize())
        BiliDanmakuLayer(
            items = danmaku,
            visible = danmakuEnabled,
            playing = state.playing,
            position = state.positionMs,
            anchorRealtimeMs = state.anchorRealtimeMs,
            playbackSpeed = state.playbackSpeed,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier.fillMaxSize().pointerInput(state.durationMs) {
                detectTapGestures(
                    onTap = {
                        if (panel != BiliPanel.NONE) panel = BiliPanel.NONE else controlsVisible = !controlsVisible
                        interaction++
                    },
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width * .35f -> seekBy(-10_000)
                            offset.x > size.width * .65f -> seekBy(10_000)
                            else -> {
                                controller.toggle()
                                hint = if (latestState.playing) "暂停" else "播放"
                                revealControls()
                            }
                        }
                    },
                    onLongPress = {
                        if (latestState.playing) {
                            speedBeforeHold = latestState.playbackSpeed.takeIf { it > 0f } ?: 1f
                            holdingSpeed = true
                            controller.setSpeed(2f)
                            hint = "正在 2× 播放"
                            revealControls()
                        }
                    },
                    onPress = {
                        try { tryAwaitRelease() } finally {
                            if (holdingSpeed) {
                                controller.setSpeed(speedBeforeHold)
                                holdingSpeed = false
                            }
                        }
                    },
                )
            },
        )

        Box(
            Modifier.fillMaxSize().pointerInput(state.durationMs) {
                var startPosition = 0L
                var startX = 0f
                var startVolume = 0
                var maxVolume = 1
                var startBrightness = .5f
                var gesture = 0 // 0 unset, 1 seek, 2 volume, 3 brightness
                var accumulated = Offset.Zero
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

                detectDragGestures(
                    onDragStart = { offset ->
                        startPosition = latestState.positionMs.coerceAtLeast(0)
                        startX = offset.x
                        accumulated = Offset.Zero
                        gesture = 0
                        maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
                        startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                        startBrightness = context.findActivity()?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: .5f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulated += amount
                        if (gesture == 0 && (abs(accumulated.x) > 8f || abs(accumulated.y) > 8f)) {
                            gesture = if (abs(accumulated.x) > abs(accumulated.y)) 1
                            else if (startX < size.width / 2f) 3 else 2
                            revealControls()
                            panel = BiliPanel.NONE
                            if (gesture == 1) {
                                scrubbing = true
                                scrubPosition = startPosition
                            }
                        }
                        when (gesture) {
                            1 -> if (latestState.durationMs > 0) {
                                scrubPosition = (startPosition + (accumulated.x / size.width * latestState.durationMs).toLong())
                                    .coerceIn(0, latestState.durationMs)
                                hint = biliTime(scrubPosition) + " / " + biliTime(latestState.durationMs)
                            }
                            2 -> {
                                val target = (startVolume - accumulated.y / size.height * maxVolume).toInt()
                                    .coerceIn(0, maxVolume)
                                runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                                hint = "音量 ${(target * 100f / maxVolume).toInt()}%"
                            }
                            3 -> {
                                val target = (startBrightness - accumulated.y / size.height).coerceIn(.02f, 1f)
                                context.findActivity()?.window?.let { window ->
                                    val attributes = window.attributes
                                    attributes.screenBrightness = target
                                    window.attributes = attributes
                                }
                                hint = "亮度 ${(target * 100).toInt()}%"
                            }
                        }
                    },
                    onDragEnd = {
                        if (gesture == 1 && scrubbing) {
                            controller.seek(scrubPosition)
                            scope.launch { delay(180); scrubbing = false; hint = null }
                        } else {
                            scrubbing = false
                            scope.launch { delay(500); hint = null }
                        }
                        interaction++
                    },
                    onDragCancel = {
                        scrubbing = false
                        hint = null
                    },
                )
            },
        )

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(tween(150)), exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    Color.Black.copy(alpha = .56f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .78f))))
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && landscape,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BiliGlassButton("返回", BiliVideoGlyph.BACK, onClose, "bili_video_back", 42.dp)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, color = SoftWhite, fontSize = if (landscape) 14.sp else 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (landscape) Text(uploader, color = MutedWhite, fontSize = 10.sp, maxLines = 1)
                }
                BiliGlassButton("下载", BiliVideoGlyph.DOWNLOAD, onDownload, "bili_video_download", 42.dp)
            }
        }

        if (!state.playing && !state.loading && state.error == null) {
            Box(
                Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .54f)).clickable(role = Role.Button) {
                        controller.toggle(); interaction++
                    },
                contentAlignment = Alignment.Center,
            ) { BiliGlyph(BiliVideoGlyph.PLAY, Color.White, Modifier.size(25.dp)) }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (panel != BiliPanel.NONE) {
                    BiliSettingsPanel(
                        panel = panel,
                        qualities = qualities,
                        selectedQn = BiliQuality.preferredQn,
                        speed = state.playbackSpeed,
                        onQuality = { option ->
                            if (option.available) {
                                onQualityChanged(option.qn)
                                panel = BiliPanel.NONE
                                revealControls()
                            } else {
                                hint = when {
                                    option.requiresVip -> "此清晰度需要大会员"
                                    option.requiresLogin -> "登录哔哩哔哩后可选择"
                                    else -> "当前视频不支持此清晰度"
                                }
                            }
                        },
                        onSpeed = { speed ->
                            controller.setSpeed(speed)
                            panel = BiliPanel.NONE
                            revealControls()
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                BiliProgressBar(
                    state = state,
                    scrubbing = scrubbing,
                    scrubPosition = scrubPosition,
                    onScrubStart = { scrubbing = true; scrubPosition = state.positionMs; interaction++ },
                    onScrub = { scrubPosition = it },
                    onScrubEnd = {
                        controller.seek(scrubPosition)
                        scope.launch { delay(180); scrubbing = false }
                        interaction++
                    },
                )

                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BiliTransportButton(if (state.playing) BiliVideoGlyph.PAUSE else BiliVideoGlyph.PLAY,
                        if (state.playing) "暂停" else "播放", {
                            controller.toggle(); interaction++
                        }, "bili_play_pause", 44.dp)
                    Text(
                        biliTime(if (scrubbing) scrubPosition else state.positionMs) + " / " + biliTime(state.durationMs),
                        color = SoftWhite.copy(alpha = .82f), fontSize = 10.5.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    BiliTransportButton(BiliVideoGlyph.DANMAKU,
                        if (danmakuEnabled) "关闭弹幕" else "显示弹幕",
                        { onDanmakuEnabled(!danmakuEnabled); interaction++ },
                        "bili_danmaku_toggle", 42.dp,
                        selected = danmakuEnabled,
                        loading = loadingDanmaku,
                    )
                    BiliLabelButton(BiliQuality.label(BiliQuality.preferredQn), "选择清晰度", {
                        panel = if (panel == BiliPanel.QUALITY) BiliPanel.NONE else BiliPanel.QUALITY
                        interaction++
                    }, "bili_quality")
                    BiliLabelButton(biliSpeedLabel(state.playbackSpeed), "播放速度", {
                        panel = if (panel == BiliPanel.SPEED) BiliPanel.NONE else BiliPanel.SPEED
                        interaction++
                    }, "bili_speed")
                    if (landscape) {
                        BiliTransportButton(BiliVideoGlyph.EXIT_FULLSCREEN, "竖屏", onRotate,
                            "bili_portrait", 42.dp)
                    } else {
                        BiliTransportButton(BiliVideoGlyph.ORIENTATION, "横屏", onRotate,
                            "bili_rotate_landscape", 42.dp)
                    }
                }
            }
        }

        if (!controlsVisible) {
            val duration = state.durationMs.coerceAtLeast(0)
            val progress = if (duration > 0) state.positionMs.toFloat() / duration else 0f
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                .background(Color.White.copy(alpha = .16f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(BiliPink))
            }
        }

        AnimatedVisibility(
            visible = hint != null || metadataError != null,
            enter = fadeIn(tween(130)), exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                hint ?: metadataError.orEmpty(),
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .72f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                color = SoftWhite, fontSize = 12.sp,
            )
        }

        if (panel == BiliPanel.NONE && (state.loading || state.resolving) && state.error == null) {
            Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = .66f)).padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(17.dp), size = 17.dp, strokeWidth = 2.dp)
                Text(if (state.resolving) "正在切换清晰度" else "正在缓冲", color = SoftWhite, fontSize = 11.sp)
            }
        }

        if (panel == BiliPanel.NONE) state.error?.takeIf { it.isNotBlank() }?.let { error ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 292.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = .84f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (error.contains("音频") || error.contains("音质")) "视频线路暂时不可用，请重试或降低清晰度"
                    else error,
                    color = SoftWhite,
                    fontSize = 12.sp,
                    maxLines = 4,
                )
                BiliLabelButton("重新加载", "重新加载视频", {
                    controller.refreshSource(); interaction++
                }, "bili_retry")
            }
        }
    }
}

@Composable
private fun BiliVideoPageTopBar(
    title: String,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BiliTransportButton(BiliVideoGlyph.BACK, "返回", onClose, "bili_video_back", 44.dp)
        Text(
            title,
            color = SoftWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        BiliTransportButton(BiliVideoGlyph.DOWNLOAD, "下载", onDownload, "bili_video_download", 44.dp)
        BiliTransportButton(BiliVideoGlyph.SHARE, "分享", onShare, "bili_video_share", 44.dp)
    }
}

/** Portrait detail layout follows Bilimiao's upper -> title/meta -> actions -> related hierarchy. */
@Composable
private fun BiliVideoInformation(
    title: String,
    uploader: String,
    ownerAvatar: String,
    viewCount: Long,
    durationSeconds: Int,
    category: String,
    liked: Boolean,
    likeCount: Long,
    commentCount: Long,
    favoriteCount: Long,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    related: List<BiliPlayback.RelatedVideo>,
    onRelatedSelected: (BiliPlayback.RelatedVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(BiliPink.copy(alpha = .20f)),
                contentAlignment = Alignment.Center,
            ) {
                if (ownerAvatar.isNotBlank()) {
                    AsyncImage(
                        model = ownerAvatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(uploader.take(1).ifBlank { "UP" }, color = BiliPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(uploader.ifBlank { "哔哩哔哩 UP 主" }, color = SoftWhite,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("哔哩哔哩 · 视频", color = MutedWhite, fontSize = 10.5.sp)
            }
        }

        Text(
            title,
            color = SoftWhite,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            BiliGlyph(BiliVideoGlyph.PLAY, MutedWhite, Modifier.size(13.dp))
            Text("${biliCompact(viewCount)} 播放", color = MutedWhite, fontSize = 10.5.sp)
            Box(Modifier.size(3.dp).clip(CircleShape).background(MutedWhite.copy(alpha = .45f)))
            Text(if (durationSeconds > 0) biliTime(durationSeconds * 1000L) else "视频", color = MutedWhite, fontSize = 10.5.sp)
            if (category.isNotBlank()) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(MutedWhite.copy(alpha = .45f)))
                Text(category, color = MutedWhite, fontSize = 10.5.sp, maxLines = 1)
            }
        }

        Spacer(Modifier.height(13.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BiliStatChip(BiliVideoGlyph.LIKE, biliCompact(likeCount).ifBlank { "点赞" }, liked, onLike)
            BiliStatChip(BiliVideoGlyph.COMMENT, biliCompact(commentCount), false, onComments)
            BiliStatChip(BiliVideoGlyph.FAVORITE, biliCompact(favoriteCount), false, onFavorite)
            BiliStatChip(BiliVideoGlyph.SHARE, "分享", false, onShare)
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .07f)))

        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onComments)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BiliGlyph(BiliVideoGlyph.COMMENT, MutedWhite, Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text("评论区", color = SoftWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${biliCompact(commentCount)} 条评论 · 点击查看完整讨论", color = MutedWhite, fontSize = 10.5.sp)
            }
            Text("查看", color = BiliPink, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .07f)))

        if (related.isNotEmpty()) {
            Text("相关推荐", color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 15.dp, bottom = 9.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                related.take(8).forEach { video ->
                    Column(
                        Modifier.width(184.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onRelatedSelected(video) },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = .06f))) {
                            AsyncImage(
                                model = video.cover,
                                contentDescription = video.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Text(
                                biliTime(video.durationSec * 1000L),
                                color = Color.White,
                                fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.BottomEnd)
                                    .padding(5.dp).clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = .68f)).padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        Text(video.title, color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(video.owner, color = MutedWhite, fontSize = 9.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

private fun biliDisplayText(raw: String): String {
    var value = raw
    repeat(3) {
        val decoded = value
            .replace("&amp;amp;", "&amp;")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        if (decoded == value) return value
        value = decoded
    }
    return value
}

@Composable
private fun BiliStatChip(
    glyph: BiliVideoGlyph,
    count: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) BiliPink.copy(alpha = .14f) else Color.White.copy(alpha = .055f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BiliGlyph(glyph, if (selected) BiliPink else SoftWhite.copy(alpha = .82f), Modifier.size(18.dp))
        Text(count.ifBlank { "0" }, color = if (selected) BiliPink else MutedWhite, fontSize = 10.5.sp)
    }
}

@Composable
private fun BiliSocialAction(
    glyph: BiliVideoGlyph,
    label: String,
    count: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, spring(dampingRatio = .72f, stiffness = 420f), label = "$label selected")
    Column(
        Modifier.width(66.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(vertical = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BiliGlyph(glyph, if (selected) BiliPink else SoftWhite.copy(alpha = .86f), Modifier.size(24.dp))
        Text(if (count > 0) biliCompact(count) else label, color = if (selected) BiliPink else MutedWhite,
            fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun BiliProgressBar(
    state: PlayerState,
    scrubbing: Boolean,
    scrubPosition: Long,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0)
    val position = if (scrubbing) scrubPosition else state.positionMs
    val progress = if (duration > 0) position.toFloat() / duration else 0f
    val buffered = if (duration > 0) state.bufferedPositionMs.toFloat() / duration else 0f
    Box(
        Modifier.fillMaxWidth().height(22.dp).pointerInput(duration) {
            val width = size.width.toFloat().coerceAtLeast(1f)
            fun positionAt(x: Float) = ((x / width).coerceIn(0f, 1f) * duration).toLong()
            detectDragGestures(
                onDragStart = { onScrubStart(); onScrub(positionAt(it.x)) },
                onDrag = { change, _ -> change.consume(); onScrub(positionAt(change.position.x)) },
                onDragEnd = onScrubEnd,
                onDragCancel = onScrubEnd,
            )
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)))
        Box(Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = .30f)))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(BiliPink))
        if (scrubbing) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(BiliPink))
            }
        }
    }
}

@Composable
private fun BiliSettingsPanel(
    panel: BiliPanel,
    qualities: List<BiliQuality.Option>,
    selectedQn: Int,
    speed: Float,
    onQuality: (BiliQuality.Option) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xF216171B)).border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        if (panel == BiliPanel.QUALITY) {
            Text("清晰度", color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            (qualities.ifEmpty {
                BiliQuality.definitions.map {
                    BiliQuality.Option(it.qn, it.label, it.detail, it.requiresLogin, it.requiresVip, false)
                }
            }).chunked(2).take(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { option ->
                        val active = option.qn == selectedQn && option.available
                        Row(
                            Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (active) BiliPink.copy(alpha = .18f) else Color.White.copy(alpha = .055f))
                                .clickable(role = Role.Button) { onQuality(option) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.label, color = if (option.available || active) SoftWhite else MutedWhite,
                                    fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                                Text(if (!option.available && option.requiresVip) "大会员" else option.detail,
                                    color = MutedWhite, fontSize = 8.5.sp, maxLines = 1)
                            }
                            if (!option.available) BiliGlyph(BiliVideoGlyph.LOCK, MutedWhite, Modifier.size(13.dp))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(7.dp))
            }
        } else {
            Text("播放速度", color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                    val active = abs(speed - value) < .01f
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (active) BiliPink.copy(alpha = .20f) else Color.White.copy(alpha = .055f))
                            .clickable { onSpeed(value) },
                        contentAlignment = Alignment.Center,
                    ) { Text(biliSpeedLabel(value), color = if (active) BiliPink else SoftWhite, fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
private fun BiliDanmakuLayer(
    items: List<BiliPlayback.Danmaku>,
    visible: Boolean,
    playing: Boolean,
    position: Long,
    anchorRealtimeMs: Long,
    playbackSpeed: Float,
    modifier: Modifier = Modifier,
) {
    if (!visible || items.isEmpty()) return
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                withFrameNanos { frame++ }
            }
        }
    }
    val density = LocalDensity.current
    Canvas(modifier) {
        // Read frame so a new frame invalidates the canvas while positions stay time-based.
        frame
        val estimated = if (playing && anchorRealtimeMs > 0)
            position + ((SystemClock.elapsedRealtime() - anchorRealtimeMs) * playbackSpeed.coerceAtLeast(.1f)).toLong()
        else position
        val lineHeight = 22.dp.toPx()
        val lanes = (size.height * .54f / lineHeight).toInt().coerceAtLeast(3)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = with(density) { 11.sp.toPx() }
            setShadowLayer(2.5f, 0f, 1f, android.graphics.Color.BLACK)
        }
        val active = items.filter { estimated >= it.timeMs && estimated - it.timeMs <= 8_500L }
            .takeLast(80)
        active.forEachIndexed { index, item ->
            if (item.mode == 7) return@forEachIndexed
            paint.color = 0xFF000000.toInt() or (item.color and 0x00FFFFFF)
            paint.textSize = with(density) { (10.5f * item.fontScale / 25f).coerceIn(9f, 14f).sp.toPx() }
            val textWidth = paint.measureText(item.text)
            if (item.mode == 4 || item.mode == 5) {
                val elapsed = estimated - item.timeMs
                if (elapsed <= 4_300L) {
                    val alpha = ((4_300L - elapsed) / 700f).coerceIn(0f, 1f)
                    paint.alpha = (255 * alpha).toInt()
                    val y = if (item.mode == 5) 18.dp.toPx() + (index % lanes) * lineHeight
                    else size.height - 18.dp.toPx() - (index % lanes) * lineHeight
                    drawContext.canvas.nativeCanvas.drawText(item.text, size.width * .5f, y, paint)
                }
            } else {
                val elapsed = estimated - item.timeMs
                val progress = (elapsed / 8_500f).coerceIn(0f, 1f)
                val x = size.width - progress * (size.width + textWidth)
                val lane = (item.text.hashCode() and Int.MAX_VALUE) % lanes
                paint.alpha = if (progress > .92f) (255 * ((1f - progress) / .08f)).toInt().coerceIn(0, 255) else 255
                drawContext.canvas.nativeCanvas.drawText(item.text, x, 15.dp.toPx() + lane * lineHeight, paint)
            }
        }
    }
}

@Composable
private fun BiliTransportButton(
    glyph: BiliVideoGlyph,
    description: String,
    onClick: () -> Unit,
    tag: String,
    size: androidx.compose.ui.unit.Dp,
    selected: Boolean = false,
    loading: Boolean = false,
) {
    val tint = if (selected) BiliPink else SoftWhite.copy(alpha = .88f)
    Box(
        Modifier.size(size).testTag(tag).clip(CircleShape)
            .background(if (selected) BiliPink.copy(alpha = .13f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(15.dp), size = 15.dp, strokeWidth = 1.7.dp)
        else BiliGlyph(glyph, tint, Modifier.size(22.dp))
    }
}

@Composable
private fun BiliGlassButton(description: String, glyph: BiliVideoGlyph, onClick: () -> Unit, tag: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).testTag(tag).clip(CircleShape).background(Color.Black.copy(alpha = .30f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BiliGlyph(glyph, SoftWhite, Modifier.size(21.dp)) }
}

@Composable
private fun BiliLabelButton(label: String, description: String, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.height(38.dp).testTag(tag).clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = .22f)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = SoftWhite.copy(alpha = .9f), fontSize = 10.sp, maxLines = 1) }
}

@Composable
private fun BiliGlyph(glyph: BiliVideoGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        when (glyph) {
            BiliVideoGlyph.BACK -> {
                val p = Path().apply {
                    moveTo(size.width * .64f, size.height * .22f)
                    lineTo(size.width * .36f, size.height * .5f)
                    lineTo(size.width * .64f, size.height * .78f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.CLOSE -> {
                drawLine(tint, Offset(size.width * .27f, size.height * .27f),
                    Offset(size.width * .73f, size.height * .73f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .73f, size.height * .27f),
                    Offset(size.width * .27f, size.height * .73f), stroke, StrokeCap.Round)
            }
            BiliVideoGlyph.DOWNLOAD -> {
                drawLine(tint, Offset(size.width * .5f, size.height * .16f),
                    Offset(size.width * .5f, size.height * .62f), stroke, StrokeCap.Round)
                val p = Path().apply {
                    moveTo(size.width * .29f, size.height * .43f); lineTo(size.width * .5f, size.height * .64f)
                    lineTo(size.width * .71f, size.height * .43f)
                    moveTo(size.width * .22f, size.height * .84f); lineTo(size.width * .78f, size.height * .84f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.PLAY -> {
                val p = Path().apply {
                    moveTo(size.width * .35f, size.height * .20f); lineTo(size.width * .80f, size.height * .50f)
                    lineTo(size.width * .35f, size.height * .80f); close()
                }
                drawPath(p, tint)
            }
            BiliVideoGlyph.PAUSE -> {
                drawRoundRect(tint, Offset(size.width * .29f, size.height * .21f),
                    Size(size.width * .14f, size.height * .58f), CornerRadius(1.5.dp.toPx()))
                drawRoundRect(tint, Offset(size.width * .57f, size.height * .21f),
                    Size(size.width * .14f, size.height * .58f), CornerRadius(1.5.dp.toPx()))
            }
            BiliVideoGlyph.DANMAKU -> {
                drawRoundRect(tint, Offset(size.width * .10f, size.height * .20f),
                    Size(size.width * .80f, size.height * .48f), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                drawLine(tint, Offset(size.width * .49f, size.height * .33f),
                    Offset(size.width * .78f, size.height * .33f), stroke * .8f, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .28f, size.height * .50f),
                    Offset(size.width * .65f, size.height * .50f), stroke * .8f, StrokeCap.Round)
            }
            BiliVideoGlyph.ORIENTATION, BiliVideoGlyph.FULLSCREEN -> {
                val p = Path().apply {
                    moveTo(size.width * .28f, size.height * .52f); lineTo(size.width * .28f, size.height * .28f)
                    lineTo(size.width * .52f, size.height * .28f)
                    moveTo(size.width * .72f, size.height * .48f); lineTo(size.width * .72f, size.height * .72f)
                    lineTo(size.width * .48f, size.height * .72f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                if (glyph == BiliVideoGlyph.ORIENTATION) drawRoundRect(tint,
                    Offset(size.width * .12f, size.height * .42f), Size(size.width * .30f, size.height * .45f),
                    CornerRadius(2.dp.toPx()), style = Stroke(stroke * .75f))
            }
            BiliVideoGlyph.EXIT_FULLSCREEN -> {
                val p = Path().apply {
                    moveTo(size.width * .45f, size.height * .28f); lineTo(size.width * .72f, size.height * .28f)
                    lineTo(size.width * .72f, size.height * .55f)
                    moveTo(size.width * .55f, size.height * .72f); lineTo(size.width * .28f, size.height * .72f)
                    lineTo(size.width * .28f, size.height * .45f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.LIKE -> {
                val p = Path().apply {
                    moveTo(size.width * .17f, size.height * .43f); lineTo(size.width * .31f, size.height * .43f)
                    lineTo(size.width * .31f, size.height * .83f); lineTo(size.width * .17f, size.height * .83f); close()
                    moveTo(size.width * .36f, size.height * .50f); lineTo(size.width * .48f, size.height * .27f)
                    quadraticTo(size.width * .56f, size.height * .12f, size.width * .61f, size.height * .24f)
                    lineTo(size.width * .57f, size.height * .43f); lineTo(size.width * .82f, size.height * .43f)
                    lineTo(size.width * .76f, size.height * .79f); lineTo(size.width * .36f, size.height * .79f); close()
                }
                drawPath(p, tint)
            }
            BiliVideoGlyph.COMMENT -> {
                drawRoundRect(tint, Offset(size.width * .11f, size.height * .20f),
                    Size(size.width * .78f, size.height * .52f), CornerRadius(5.dp.toPx()), style = Stroke(stroke))
                val p = Path().apply {
                    moveTo(size.width * .31f, size.height * .69f); lineTo(size.width * .27f, size.height * .87f)
                    lineTo(size.width * .51f, size.height * .72f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.FAVORITE -> {
                val p = Path().apply {
                    moveTo(size.width * .5f, size.height * .82f)
                    cubicTo(size.width * .20f, size.height * .62f, size.width * .13f, size.height * .40f,
                        size.width * .30f, size.height * .27f)
                    cubicTo(size.width * .40f, size.height * .19f, size.width * .50f, size.height * .28f,
                        size.width * .50f, size.height * .36f)
                    cubicTo(size.width * .50f, size.height * .28f, size.width * .60f, size.height * .19f,
                        size.width * .70f, size.height * .27f)
                    cubicTo(size.width * .87f, size.height * .40f, size.width * .80f, size.height * .62f,
                        size.width * .5f, size.height * .82f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.SHARE -> {
                val p = Path().apply {
                    moveTo(size.width * .5f, size.height * .13f); lineTo(size.width * .5f, size.height * .61f)
                    moveTo(size.width * .31f, size.height * .32f); lineTo(size.width * .5f, size.height * .13f)
                    lineTo(size.width * .69f, size.height * .32f)
                    moveTo(size.width * .22f, size.height * .54f); lineTo(size.width * .22f, size.height * .86f)
                    lineTo(size.width * .78f, size.height * .86f); lineTo(size.width * .78f, size.height * .54f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliVideoGlyph.LOCK -> {
                drawRoundRect(tint, Offset(size.width * .24f, size.height * .44f),
                    Size(size.width * .52f, size.height * .42f), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
                val p = Path().apply {
                    moveTo(size.width * .34f, size.height * .44f); lineTo(size.width * .34f, size.height * .30f)
                    cubicTo(size.width * .34f, size.height * .12f, size.width * .66f, size.height * .12f,
                        size.width * .66f, size.height * .30f)
                    lineTo(size.width * .66f, size.height * .44f)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}

private fun biliTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun biliCompact(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000f)
    value >= 10_000 -> "%.1f万".format(value / 10_000f)
    else -> value.toString()
}

private fun biliSpeedLabel(speed: Float): String = when {
    abs(speed - 1f) < .01f -> "倍速"
    abs(speed - 2f) < .01f -> "2×"
    else -> speed.toString().removeSuffix("0").removeSuffix(".") + "×"
}
