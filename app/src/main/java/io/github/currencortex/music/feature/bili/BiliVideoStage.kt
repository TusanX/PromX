// SPDX-License-Identifier: GPL-3.0-only
// B站 竖屏视频：画面借用 MusicService 里唯一的播放器（与 MV 同一套 VideoBinder），
// 控件是普通 Compose 兄弟层（TextureView 不会盖住它）——
// 因此只有一路音频，手势走标准 Compose 分发，不受 AndroidView 影响。
package io.github.currencortex.music.feature.bili

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.currencortex.music.R
import io.github.currencortex.music.core.alt.BiliQuality
import io.github.currencortex.music.core.media.MusicService
import io.github.currencortex.music.core.media.PlayerController
import io.github.currencortex.music.core.media.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private val Accent = Color(0xFFFB7299)
private val Ink = Color.White
/** 8dp 网格：所有间距取自 4/8 的倍数，界面才不散 */
private val Gap = 8.dp
/** iOS 果冻感：低阻尼弹簧，按下会稍稍“弹回”，而不是生硬缩放 */
private fun <T> jelly(stiffness: Float = 480f, damping: Float = .62f) =
    androidx.compose.animation.core.spring<T>(dampingRatio = damping, stiffness = stiffness)

/** 画面层：把服务里唯一播放器的 Surface 挂上来 */
@Composable fun BiliVideoSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var binder by remember { mutableStateOf<MusicService.VideoBinder?>(null) }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as? MusicService.VideoBinder
            }
            override fun onServiceDisconnected(name: ComponentName) { binder = null }
        }
        context.bindService(
            Intent(context, MusicService::class.java).setAction(MusicService.VIDEO_SURFACE),
            connection, Context.BIND_AUTO_CREATE,
        )
        onDispose { runCatching { context.unbindService(connection) } }
    }
    AndroidView(
        factory = { ctx -> buildSurface(ctx) },
        update = { frame -> (frame.getChildAt(0) as? PlayerView)?.let { binder?.attach(it) } },
        onRelease = { frame -> (frame.getChildAt(0) as? PlayerView)?.let { binder?.detach(it) } },
        modifier = modifier.fillMaxSize(),
    )
}

private fun buildSurface(ctx: Context): FrameLayout = FrameLayout(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    val playerView = LayoutInflater.from(ctx).inflate(R.layout.view_bili_player, null) as PlayerView
    playerView.useController = false
    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    addView(playerView, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
}

/**
 * 控件层。与 PromX 播放页同一套语言：小字号、圆角胶囊、粉色只用在播放键与进度上。
 * 3 秒自动隐去，点画面唤回，双击播放/暂停；右上角可以进入「只留画面」的全屏。
 */
@Composable fun BiliVideoChrome(
    controller: PlayerController,
    title: String,
    uploader: String,
    liked: Boolean,
    likeCount: Long,
    commentCount: Long,
    favoriteCount: Long,
    onClose: () -> Unit,
    onLike: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onComments: () -> Unit,
    onQualityChanged: () -> Unit,
    onDownload: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var immersive by remember { mutableStateOf(false) }   // 全屏：只留画面
    // 双击播放/暂停的即时反馈（State indication）
    var hint by remember { mutableStateOf<Boolean?>(null) }
    val visible = !immersive
    var panel by remember { mutableStateOf(0) }           // 0 无 / 1 倍速 / 2 画质
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(1f) }
    var qualityQn by remember { mutableStateOf(BiliQuality.preferredQn) }
    val scope = rememberCoroutineScope()

    // 双击反馈：2 秒后自动消失（只做淡入淡出，不位移）
    LaunchedEffect(hint) {
        if (hint != null) { delay(900); hint = null }
    }

    Box(Modifier.fillMaxSize()) {
        // 只在「空白区域」上挂点击：放在最底层，按钮在上层优先接收，不会再抢触摸
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // 空白处单击：关掉打开的面板；全屏时单击退出全屏
                        if (panel != 0) panel = 0 else if (immersive) immersive = false
                    },
                    onDoubleTap = {
                        val willPlay = !controller.state.value.playing
                        controller.toggle()
                        hint = willPlay
                    },
                )
            },
        )
        // 只在顶部压一条：底部不再盖一大块黑，画面更干净
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))

        if (visible) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircle("收起视频", 44.dp, onClose, "bili_video_close") { ChevronGlyph() }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(uploader, color = Ink.copy(alpha = .55f), fontSize = 11.sp, maxLines = 1)
                }
                GlassCircle("下载到本地", 44.dp, onDownload, "bili_video_download") { DownloadGlyph() }
                Spacer(Modifier.width(8.dp))
                GlassCircle("进入全屏", 44.dp, { immersive = true }, "bili_video_fullscreen") { ExpandGlyph() }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = panel != 0,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                        androidx.compose.animation.scaleIn(
                            animationSpec = jelly(420f, .7f), initialScale = .94f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)) +
                        androidx.compose.animation.slideInVertically(
                            animationSpec = androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 420f),
                            initialOffsetY = { it / 5 }),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = Gap)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                                OptionChip(speedLabel(v), v == speed) {
                                    speed = v; controller.setSpeed(v)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                            listOf(16, 32, 64, 80).forEach { qn ->
                                OptionChip(BiliQuality.label(qn), qn == qualityQn) {
                                    qualityQn = qn; BiliQuality.preferredQn = qn; onQualityChanged()
                                }
                            }
                        }
                    }
                }

                Scrubber(
                    state = state, scrubbing = scrubbing, scrubMs = scrubMs,
                    onScrubStart = { scrubbing = true },
                    onScrub = { scrubMs = it },
                    onScrubEnd = { controller.seek(scrubMs); scope.launch { delay(450); scrubbing = false } },
                    onTapSeek = { controller.seek(it) },
                )

                Spacer(Modifier.height(6.dp))

                // 三行布局：动作行（带计数）在上，设置与播放键在下——
                // 竖屏宽度放不下「4 个动作 + 设置 + 播放键」一行，挤在一行一定被裁切。
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionItem(onLike, likeCount, if (liked) "取消点赞" else "点赞") {
                        ThumbGlyph(if (liked) Accent else Ink, filled = liked)
                    }
                    ActionItem(onComments, commentCount, "评论") { BubbleGlyph() }
                    ActionItem(onFavorite, favoriteCount, "收藏") { StarGlyph(if (liked) Accent else Ink) }
                    ActionItem(onShare, 0, "分享") { ShareGlyph() }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(BiliQuality.label(qualityQn) + " · " + speedLabel(speed), panel != 0) {
                        panel = if (panel == 0) 1 else 0
                    }
                    Spacer(Modifier.weight(1f))
                    val playInteractions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val playPressed by playInteractions.collectIsPressedAsState()
                    val playScale by androidx.compose.animation.core.animateFloatAsState(
                        if (playPressed) .90f else 1f, jelly(460f, .52f), label = "play press")
                    Box(
                        Modifier.size(56.dp).graphicsLayer { scaleX = playScale; scaleY = playScale }
                            .clip(CircleShape).background(Accent)
                            .semantics { contentDescription = if (state.playing) "暂停视频" else "播放视频" }
                            .testTag("bili_video_play")
                            .clickable(interactionSource = playInteractions, indication = null) { controller.toggle() },
                        contentAlignment = Alignment.Center,
                    ) { if (state.playing) PauseGlyph(22.dp) else PlayGlyph(22.dp) }
                }
            }
        }

        // 双击反馈：中央图标 + 一行字
        androidx.compose.animation.AnimatedVisibility(
            visible = hint != null,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hint == true) PlayGlyph(28.dp) else PauseGlyph(28.dp)
                }
                Text(
                    if (hint == true) "播放" else "已暂停",
                    Modifier.padding(top = 8.dp),
                    color = Ink.copy(alpha = .9f), fontSize = 12.5.sp,
                )
            }
        }

        // 全屏时留一个极轻的返回入口，避免“出不去”
        if (immersive) {
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                GlassCircle("退出全屏", 44.dp, { immersive = false }, "bili_video_exit_fullscreen") { CollapseGlyph() }
            }
        }
    }
}

@Composable private fun OptionsRow(
    panel: Int, speed: Float, qualityQn: Int,
    onPickSpeed: (Float) -> Unit, onPickQn: (Int) -> Unit,
) {
    val speeds = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
    val qns = listOf(16, 32, 64, 80)
    Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
        if (panel == 1) speeds.forEach { v ->
            OptionChip(speedLabel(v), v == speed) { onPickSpeed(v) }
        } else qns.forEach { qn ->
            OptionChip(BiliQuality.label(qn), qn == qualityQn) { onPickQn(qn) }
        }
    }
}

@Composable private fun OptionChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) Accent else Color.White.copy(alpha = .16f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Ink, fontSize = 12.sp) }
}

/** 进度条：左对齐增长（之前居中对齐导致看起来“从中间散开”），拖动时显示圆点 */
@Composable private fun Scrubber(
    state: PlayerState,
    scrubbing: Boolean,
    scrubMs: Long,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onTapSeek: (Long) -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0)
    val position = if (scrubbing) scrubMs else state.positionMs.coerceAtLeast(0)
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(duration) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun at(x: Float): Long = ((x / width).coerceIn(0f, 1f) * duration).toLong().coerceIn(0, duration)
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onScrubStart(); onScrub(at(offset.x)) },
                        onHorizontalDrag = { change, _ -> onScrub(at(change.position.x)) },
                        onDragEnd = { onScrubEnd() },
                        onDragCancel = { onScrubEnd() },
                    )
                }
                .pointerInput(duration) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    detectTapGestures(onTap = { offset ->
                        if (duration > 0) onTapSeek(((offset.x / width).coerceIn(0f, 1f) * duration).toLong())
                    })
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.copy(alpha = .20f)))
            Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(CircleShape).background(Accent))
            val thumbScale by androidx.compose.animation.core.animateFloatAsState(
                if (scrubbing) 1f else .001f, jelly(300f, .7f), label = "thumb")
            if (thumbScale > .01f) {
                Box(Modifier.fillMaxWidth(progress), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(13.dp).graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                        .clip(CircleShape).background(Accent))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(format(position), color = Ink.copy(alpha = .85f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.error != null -> state.error.orEmpty()
                    state.loading && duration <= 0 -> "正在准备画面"
                    else -> ""
                },
                color = Ink.copy(alpha = .6f), fontSize = 11.5.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("-" + format((duration - position).coerceAtLeast(0)),
                color = Ink.copy(alpha = .55f), fontSize = 12.sp)
        }
    }
}

@Composable private fun ActionItem(
    onClick: () -> Unit,
    count: Long,
    label: String = "",
    glyph: @Composable () -> Unit,
) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .then(if (label.isNotEmpty()) Modifier.semantics { contentDescription = label } else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { glyph() }
        if (count > 0) Text(compact(count), Modifier.padding(start = 5.dp),
            color = Ink.copy(alpha = .82f), fontSize = 13.sp)
    }
}

@Composable private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(11.dp))
            .background(if (active) Accent.copy(alpha = .30f) else Color.White.copy(alpha = .14f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Ink.copy(alpha = .92f), fontSize = 13.sp) }
}

@Composable private fun GlassCircle(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    tag: String = "",
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = .28f))
            .semantics { contentDescription = label }
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun speedLabel(speed: Float): String = when (speed) {
    1f -> "1.0x"; .75f -> "0.75x"; else -> speed.toString() + "x"
}

private fun compact(value: Long): String = if (value >= 10_000) (value / 10_000).toString() + "万" else value.toString()

private fun format(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

// —— 手绘图标：统一 1.8dp 线宽，不用 emoji / 贴图 ——
@Composable private fun ChevronGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val p = Path().apply {
            moveTo(size.width * .25f, size.height * .62f)
            lineTo(size.width * .5f, size.height * .36f)
            lineTo(size.width * .75f, size.height * .62f)
        }
        drawPath(p, Ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable private fun CollapseGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val p = Path().apply {
            moveTo(size.width * .3f, size.height * .7f); lineTo(size.width * .7f, size.height * .3f)
            moveTo(size.width * .55f, size.height * .3f); lineTo(size.width * .7f, size.height * .3f)
            lineTo(size.width * .7f, size.height * .45f)
            moveTo(size.width * .45f, size.height * .7f); lineTo(size.width * .3f, size.height * .7f)
            lineTo(size.width * .3f, size.height * .55f)
        }
        drawPath(p, Ink, style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable private fun DownloadGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        val p = Path().apply {
            moveTo(size.width * .5f, size.height * .16f)
            lineTo(size.width * .5f, size.height * .62f)
            moveTo(size.width * .30f, size.height * .44f)
            lineTo(size.width * .5f, size.height * .64f)
            lineTo(size.width * .70f, size.height * .44f)
            moveTo(size.width * .22f, size.height * .84f)
            lineTo(size.width * .78f, size.height * .84f)
        }
        drawPath(p, Ink, style = stroke)
    }
}

@Composable private fun ExpandGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val p = Path().apply {
            moveTo(size.width * .3f, size.height * .55f); lineTo(size.width * .3f, size.height * .3f)
            lineTo(size.width * .55f, size.height * .3f)
            moveTo(size.width * .7f, size.height * .45f); lineTo(size.width * .7f, size.height * .7f)
            lineTo(size.width * .45f, size.height * .7f)
        }
        drawPath(p, Ink, style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 点赞：拇指（B站 语义），比心形更贴场景 */
@Composable private fun ThumbGlyph(tint: Color, filled: Boolean) {
    Canvas(Modifier.size(26.dp)) {
        val s = size
        val stroke = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round)
        // 手掌
        val palm = Path().apply {
            addRoundRect(RoundRect(Rect(s.width * .10f, s.height * .42f, s.width * .34f, s.height * .88f),
                CornerRadius(2.dp.toPx())))
        }
        // 拇指与上沿
        val arm = Path().apply {
            moveTo(s.width * .38f, s.height * .52f)
            cubicTo(s.width * .50f, s.height * .40f, s.width * .52f, s.height * .34f, s.width * .55f, s.height * .18f)
            cubicTo(s.width * .57f, s.height * .06f, s.width * .70f, s.height * .08f, s.width * .70f, s.height * .20f)
            lineTo(s.width * .68f, s.height * .40f)
            lineTo(s.width * .84f, s.height * .40f)
            quadraticTo(s.width * .92f, s.height * .40f, s.width * .90f, s.height * .50f)
            lineTo(s.width * .86f, s.height * .82f)
            quadraticTo(s.width * .85f, s.height * .88f, s.width * .78f, s.height * .88f)
            lineTo(s.width * .38f, s.height * .88f)
            close()
        }
        if (filled) { drawPath(palm, tint); drawPath(arm, tint) }
        else { drawPath(palm, tint, style = stroke); drawPath(arm, tint, style = stroke) }
    }
}

@Composable private fun BubbleGlyph() {
    Canvas(Modifier.size(26.dp)) {
        val path = Path().apply {
            addRoundRect(RoundRect(Rect(size.width * .10f, size.height * .18f,
                size.width * .90f, size.height * .68f), CornerRadius(5.dp.toPx())))
            moveTo(size.width * .34f, size.height * .66f)
            lineTo(size.width * .30f, size.height * .86f)
            lineTo(size.width * .54f, size.height * .69f)
            close()
        }
        drawPath(path, Ink)
    }
}

@Composable private fun StarGlyph(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val pts = ArrayList<Offset>(10)
        for (i in 0 until 10) {
            val angle = (i * Math.PI / 5 - Math.PI / 2).toFloat()
            val r = if (i % 2 == 0) .47f else .23f
            pts.add(Offset(cx + kotlin.math.cos(angle) * r * size.width,
                cy + kotlin.math.sin(angle) * r * size.height))
        }
        fun mix(a: Offset, b: Offset) = Offset(a.x * .86f + b.x * .14f, a.y * .86f + b.y * .14f)
        val path = Path()
        val startPt = mix(pts[0], pts[9]); path.moveTo(startPt.x, startPt.y)
        for (i in 0 until 10) {
            val next = mix(pts[i], pts[(i + 1) % 10])
            path.quadraticTo(pts[i].x, pts[i].y, next.x, next.y)
            val endPt = mix(pts[(i + 1) % 10], pts[i])
            path.lineTo(endPt.x, endPt.y)
        }
        path.close(); drawPath(path, tint)
    }
}

@Composable private fun ShareGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val p = Path().apply {
            moveTo(size.width * .5f, size.height * .12f); lineTo(size.width * .5f, size.height * .62f)
            moveTo(size.width * .3f, size.height * .32f); lineTo(size.width * .5f, size.height * .12f)
            lineTo(size.width * .7f, size.height * .32f)
            moveTo(size.width * .22f, size.height * .56f); lineTo(size.width * .22f, size.height * .88f)
            lineTo(size.width * .78f, size.height * .88f); lineTo(size.width * .78f, size.height * .56f)
        }
        drawPath(p, Ink, style = Stroke(2.1.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable private fun PlayGlyph(dimension: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(dimension)) {
        val p = Path().apply {
            moveTo(size.width * .32f, size.height * .18f)
            lineTo(size.width * .82f, size.height * .5f)
            lineTo(size.width * .32f, size.height * .82f)
            close()
        }
        drawPath(p, Ink)
    }
}

@Composable private fun PauseGlyph(dimension: androidx.compose.ui.unit.Dp = 18.dp) {
    Canvas(Modifier.size(dimension)) {
        drawRoundRect(Ink, Offset(size.width * .28f, size.height * .2f),
            Size(size.width * .15f, size.height * .6f), CornerRadius(1.5.dp.toPx()))
        drawRoundRect(Ink, Offset(size.width * .57f, size.height * .2f),
            Size(size.width * .15f, size.height * .6f), CornerRadius(1.5.dp.toPx()))
    }
}
