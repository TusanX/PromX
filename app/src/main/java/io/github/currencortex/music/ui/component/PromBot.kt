// SPDX-License-Identifier: GPL-3.0-only
// PromX 小 AI（原生）：身体轮廓 + 眼型几何都来自 blessonism/grok-icon-study 的 geometry-data.js；
// 时间参数取自 tables.js 的 idle：BLINK_MS [6000,14000]、EYE_PLAYLIST [0,8]、EYE_HOLD_MS [9000,16000]。
package io.github.currencortex.music.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.sin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val BLOB_PATH = "M228.541 114.228C228.541 130.133 225.184 145.994 218.738 160.534C212.674 174.217 203.904 186.669 193.065 196.988C155.933 232.34 99.497 238.596 55.5255 212.24C45.097 205.99 35.6851 198.072 27.7451 188.866C19.1926 178.953 12.3686 167.569 7.65781 155.351C2.60712 142.264 0 128.257 0 114.228C0 98.3219 3.35751 82.4611 9.80315 67.9215C15.8672 54.2382 24.6377 41.7862 35.4767 31.4668C72.6081 -3.88483 129.044 -10.1413 173.016 16.2153C183.444 22.4653 192.856 30.3829 200.796 39.5896C209.349 49.5018 216.173 60.8859 220.883 73.1037C225.934 86.1906 228.541 100.198 228.541 114.228Z"

// 仓库 math.js 的 stepSpring：二阶弹簧积分（freq/damp 来自 tables.js 的 SPRINGS）
private const val DT = 1f / 120f
private const val EYE_CX = 114f
private const val EYE_CY = 114f

private class Spring(var x: Float, var v: Float = 0f) {
    fun step(target: Float, freq: Float, damp: Float, dt: Float) {
        v += (-2f * damp * freq * v - freq * freq * (x - target)) * dt
        x += v * dt
    }
    fun kick(velocity: Float) { v += velocity }
}

private class Physics {
    val spin = Spring(0f); val tx = Spring(0f); val ty = Spring(0f)
    val squash = Spring(1f); val blink = Spring(1f); val eyeScale = Spring(1f)
    val gazeX = Spring(0f); val gazeY = Spring(0f)
    // 仓库 math.js：DT = 1/120，一帧拆成若干子步，否则高频弹簧会发散
    fun step(dt: Float, t: Targets) {
        val steps = maxOf(1, kotlin.math.ceil(dt / DT).toInt())
        val h = dt / steps
        repeat(steps) {
            spin.step(t.spin, 5f, 0.9f, h)
            tx.step(t.tx, 3.5f, 1f, h)
            ty.step(t.ty, 4f, 1f, h)
            squash.step(t.squash, 10f, 0.8f, h)
            blink.step(t.blink, 26f, 1f, h)
            eyeScale.step(1f, 9f, 0.85f, h)
            gazeX.step(t.gazeX, 13f, 1f, h)
            gazeY.step(t.gazeY, 13f, 1f, h)
        }
        // 数值保护：发散时直接落回目标值
        listOf(spin to t.spin, tx to t.tx, ty to t.ty, squash to t.squash, blink to t.blink,
            gazeX to t.gazeX, gazeY to t.gazeY).forEach { (sp, tgt) ->
            if (!sp.x.isFinite() || kotlin.math.abs(sp.x) > 1e4f) { sp.x = tgt; sp.v = 0f }
        }
    }
}

private data class Targets(val spin: Float, val tx: Float, val ty: Float, val squash: Float,
                           val blink: Float, val gazeX: Float, val gazeY: Float)

private data class Gaze(val x: Float, val y: Float, val h0: Long, val h1: Long)

enum class BotMood { IDLE, LISTENING, EXCITED, HAPPY, SEARCHING, HUMMING, SAD, THINKING, SURPRISED, LAUGHING, CURIOUS, BORED, WAKING, WORKING, SLEEPING, PLAYFUL, PROUD, SHY, NOTIFYING, ALERTING, SUSPICIOUS, ANGRY, DROWSY, SCARED }

@Composable fun PromBot(modifier: Modifier = Modifier, size: Dp = 48.dp,
    tint: Color = Color(0xFF0B0B0D), mood: BotMood = BotMood.IDLE,
    onTap: () -> Unit = {}, onLongPress: (() -> Unit)? = null) {
    // 点击：短促的 happy 反应
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(tapped) { if (tapped) { delay(1200); tapped = false } }

    // 待机时穿插小动作，节奏参照仓库 ONBOARDING_MS = 1200
    var idleBeat by remember { mutableIntStateOf(0) }
    var idleRotation by remember { mutableIntStateOf(0) }
    LaunchedEffect(mood) {
        while (mood == BotMood.IDLE) {
            // 仓库 ONBOARDING: curious → happy → playful → excited → listening → proud → laughing → shy
            idleRotation = (idleRotation + 1) % 8
            idleBeat = idleRotation + 1
            delay(1200)
            idleBeat = 0
            delay(1200)
        }
    }
    var stateSince by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mood) { stateSince = SystemClock.elapsedRealtime() }
    val since = (SystemClock.elapsedRealtime() - stateSince).toFloat()

    val onboardMood = when (idleRotation) {
        0 -> BotMood.CURIOUS; 1 -> BotMood.HAPPY; 2 -> BotMood.PLAYFUL; 3 -> BotMood.EXCITED
        4 -> BotMood.LISTENING; 5 -> BotMood.PROUD; 6 -> BotMood.LAUGHING; else -> BotMood.SHY
    }
    var notifying by remember { mutableStateOf(false) }
    var waking by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); waking = false }
    val effective = when {
        waking && mood == BotMood.IDLE -> BotMood.WAKING
        notifying -> BotMood.NOTIFYING
        tapped -> BotMood.HAPPY
        mood != BotMood.IDLE -> mood
        idleBeat != 0 -> onboardMood
        else -> BotMood.IDLE
    }
    // idle 姿态曲线：逐字取自 pose.js
    //   spin = sin(mt*0.5)*1.5 + sin(mt*0.17)*0.6
    //   tx = sin(mt*0.27)*1 ; ty = sin(mt*0.85)*1.2 ; squash = 1 + sin(mt*0.85)*0.007
    // 唯一时间源：逐帧更新，单位是「秒」。仓库里 mt 就是秒，之前放大了 6.28 倍才会抽动
    var frameMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) { androidx.compose.runtime.withFrameMillis { frameMs = it.toFloat() } }
    }
    val baseMs = remember { SystemClock.elapsedRealtime() }
    val mt = (frameMs - baseMs) / 1000f
    // 眼型轮换：EYE_PLAYLIST [0,8]，每个保持 9–16 秒，切换时 320ms 形态插值
    var eyeIndex by remember { mutableIntStateOf(0) }
    var morph by remember { mutableFloatStateOf(0f) }   // 0 = 当前型，1 = 下一型
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(9000, 16000))
            val start = withFrameMillis { it }
            while (true) {
                val t = ((withFrameMillis { it } - start) / 320f).coerceIn(0f, 1f)
                morph = t
                if (t >= 1f) break
            }
            eyeIndex = (eyeIndex + 1) % BotEyes.Idle.size
            morph = 0f
        }
    }
    // 眨眼：BLINK_MS idle [6000,14000]，闭眼是弹簧（freq 26）压到 0.06
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(6000, 14000))
            blinking = true; delay(110); blinking = false
        }
    }

    // gaze：逐字对应 pose.js 的 nextGaze(state)，单位是 259 网格里的像素
    var gazeTarget by remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(effective) {
        val r = kotlin.random.Random
        fun sign() = if (r.nextBoolean()) 1f else -1f
        while (true) {
            val g = when (effective) {
                BotMood.IDLE -> Gaze(0f, 0f, 2500L, 5500L)
                BotMood.LISTENING -> Gaze(r.nextFloat() * 0.6f * 15f - 0.3f * 15f, r.nextFloat() * 0.5f * 9f - 0.25f * 9f, 2200L, 4200L)
                BotMood.THINKING -> Gaze(sign() * (r.nextFloat() * 0.5f + 0.5f) * 15f, -(r.nextFloat() * 0.6f + 0.4f) * 9f, 1500L, 2800L)
                BotMood.SEARCHING, BotMood.CURIOUS -> Gaze(
                    sign() * (r.nextFloat() * 0.4f + 0.6f) * 15f,
                    (r.nextFloat() * 2f - 1f) * 9f,
                    if (effective == BotMood.SEARCHING) 550L else 950L,
                    if (effective == BotMood.SEARCHING) 1150L else 1900L)
                BotMood.WORKING -> Gaze((r.nextFloat() * 0.8f - 0.4f) * 15f, (r.nextFloat() * 0.6f + 0.4f) * 9f, 1200L, 2400L)
                BotMood.EXCITED -> Gaze((r.nextFloat() * 2f - 1f) * 15f, (r.nextFloat() * 1.3f - 1f) * 9f, 700L, 1400L)
                BotMood.SURPRISED -> Gaze(0f, 0f, 1600L, 2600L)
                BotMood.HAPPY -> Gaze((r.nextFloat() * 1.4f - 0.7f) * 15f, -r.nextFloat() * 0.6f * 9f, 1800L, 3400L)
                BotMood.BORED -> Gaze(sign() * (r.nextFloat() * 0.3f + 0.7f) * 15f, (r.nextFloat() * 0.5f + 0.4f) * 9f, 3000L, 6000L)
                BotMood.PLAYFUL -> Gaze(sign() * (r.nextFloat() * 0.5f + 0.5f) * 15f, -r.nextFloat() * 0.6f * 9f, 900L, 1800L)
                BotMood.PROUD -> Gaze((r.nextFloat() * 0.6f - 0.3f) * 15f, -(r.nextFloat() * 0.4f + 0.3f) * 9f, 2600L, 4600L)
                BotMood.SHY -> Gaze(sign() * (r.nextFloat() * 0.4f + 0.6f) * 15f, (r.nextFloat() * 0.5f + 0.5f) * 9f, 2000L, 4000L)
                BotMood.SUSPICIOUS -> Gaze(sign() * 15f, 0.3f * 9f, 2200L, 4200L)
                BotMood.ANGRY -> Gaze((r.nextFloat() * 0.4f - 0.2f) * 15f, 0.2f * 9f, 1800L, 3200L)
                BotMood.DROWSY -> Gaze((r.nextFloat() * 0.8f - 0.4f) * 15f, (r.nextFloat() * 0.6f + 0.4f) * 9f, 2500L, 4500L)
                BotMood.SCARED -> Gaze(0f, 0f, 900L, 1800L)
                BotMood.SAD -> Gaze((r.nextFloat() * 0.6f - 0.3f) * 15f, (r.nextFloat() * 0.4f + 0.6f) * 9f, 2800L, 5000L)
                BotMood.SLEEPING -> Gaze(0f, 0f, 4000L, 7000L)
                else -> Gaze(0f, 0f, 2500L, 5500L)
            }
            gazeTarget = g.x to g.y
            // 上下界相等会让 nextLong 抛异常（SLEEPING 曾踩到）
            kotlinx.coroutines.delay(r.nextLong(g.h0, maxOf(g.h1, g.h0 + 1L)))
        }
    }
    val gazeX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = gazeTarget.first,
        animationSpec = androidx.compose.animation.core.tween(520, easing = LinearEasing), label = "gx")
    val gazeY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = gazeTarget.second,
        animationSpec = androidx.compose.animation.core.tween(520, easing = LinearEasing), label = "gy")

    // 逐帧：先算目标姿态，再用弹簧积分逼近（仓库就是这么做的）
    var pose by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f, 1f)) }
    var gaze by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    var blinkVal by remember { mutableFloatStateOf(1f) }
    val physics = remember { Physics() }
    LaunchedEffect(Unit) {
        BotSignals.notifyTick.collect {
            notifying = true
            physics.ty.kick(-26f)      // 仓库 notifying 的 tyKick
            physics.blink.x = 0.06f    // 顺带眨一下
            delay(1200)
            notifying = false
        }
    }
    LaunchedEffect(effective) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0.001f, 0.05f)
                last = now
                val mt = (now - baseMs) / 1000f
                val t = poseTargets(effective, mt, since, tapped)
                physics.step(dt, Targets(t[0], t[1], t[2], t[3],
                    if (effective == BotMood.SLEEPING) 0.08f else if (blinking) 0.06f else 1f,
                    gazeTarget.first, gazeTarget.second))
                pose = floatArrayOf(physics.spin.x, physics.tx.x, physics.ty.x, physics.squash.x)
                gaze = floatArrayOf(physics.gazeX.x, physics.gazeY.x)
                blinkVal = physics.blink.x
            }
        }
    }
    // 有热评时：身体形变成 squircle，整体放大成气泡并显示文字；之后弹回 blob
    val bubbleText by BotSignals.bubbleText.collectAsState()
    val bubbleShown = bubbleText != null
    val shapeBlend by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (bubbleShown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 190f),
        label = "shapeBlend")
    // 40dp ↔ 168dp：长成气泡再收回去
    val grown by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (bubbleShown) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.68f, stiffness = 150f),
        label = "grown")

    val restBlink = blinkVal
    val bubbleW = 164.dp
    val haptics = LocalHapticFeedback.current
    androidx.compose.foundation.layout.Box(
        modifier.size(size + (bubbleW - size) * grown).combinedClickable(
            onClick = { onTap(); tapped = true },
            onLongClick = onLongPress?.let { callback ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    callback()
                    tapped = true
                }
            },
        )
    ) {
    Canvas(androidx.compose.ui.Modifier.fillMaxSize().testTag("prom_bot")) {
        val w = this.size.width
        val k = w / 259f
        val spinDeg = pose[0]; val tx = pose[1]; val ty = pose[2]; val squash = pose[3]
        val gazeX = gaze[0]; val gazeY = gaze[1]
        val off = Offset((w - 259f * k) / 2f, (w - 259f * k) / 2f)
        // 身体
        rotate(spinDeg, pivot = Offset(w / 2f, w / 2f)) {
        scale(k * squash, k, pivot = Offset.Zero) {
            translate(tx * k, ty * k) {
                if (shapeBlend <= 0.01f) {
                    drawPath(Paths.blob, tint)
                } else {
                    val body = Path()
                    val n = blobSamples.size / 2
                    for (i in 0 until n) {
                        val x = blobSamples[i * 2] + (bubbleSamples[i * 2] - blobSamples[i * 2]) * shapeBlend
                        val y = blobSamples[i * 2 + 1] + (bubbleSamples[i * 2 + 1] - blobSamples[i * 2 + 1]) * shapeBlend
                        if (i == 0) body.moveTo(x, y) else body.lineTo(x, y)
                    }
                    body.close()
                    drawPath(body, tint)
                }
            }
            // 长成气泡时隐藏眼睛
            if (grown < 0.45f) {
                val table = when (effective) {
                    BotMood.THINKING -> BotEyes.Thinking
                    BotMood.SEARCHING -> BotEyes.Searching
                    BotMood.CURIOUS -> BotEyes.Searching
                    BotMood.BORED -> BotEyes.Thinking
                    BotMood.ALERTING -> BotEyes.Searching
                    else -> BotEyes.Idle
                }
                val cur = table[eyeIndex % table.size]
                val yaw = (gazeX / 15f) * 0.75f
                val pitchA = (gazeY / 9f) * 0.55f
                // 眼睛尺寸取自仓库眼型的包围盒（宽/高比例保持），形状用胶囊，方向交给 gaze
                val nextShape = table[(eyeIndex + 1) % table.size]
                fun bbox(poly: FloatArray): Pair<Float, Float> {
                    var mnX = Float.MAX_VALUE; var mxX = -Float.MAX_VALUE
                    var mnY = Float.MAX_VALUE; var mxY = -Float.MAX_VALUE
                    var kk = 0
                    while (kk + 1 < poly.size) {
                        mnX = minOf(mnX, poly[kk]); mxX = maxOf(mxX, poly[kk])
                        mnY = minOf(mnY, poly[kk + 1]); mxY = maxOf(mxY, poly[kk + 1])
                        kk += 2
                    }
                    return (mxX - mnX) to (mxY - mnY)
                }
                cur.forEachIndexed { pi, poly ->
                    val nxt = nextShape.getOrNull(pi)
                    val (wCur, hCur) = bbox(poly)
                    val (wNext, hNext) = if (nxt != null) bbox(nxt) else (wCur to hCur)
                    // 眼镜尺寸随眼型过渡插值，切换不再跳变
                    val w0 = (wCur + (wNext - wCur) * morph).coerceAtLeast(8f)
                    val h0 = (hCur + (hNext - hCur) * morph).coerceAtLeast(8f)
                    val baseAz = if (pi == 0) -0.215f else 0.215f
                    val az = baseAz + yaw
                    val zz = kotlin.math.cos(az) * kotlin.math.cos(pitchA)
                    if (zz <= 0.06f) return@forEachIndexed
                    val cx = EYE_CX + kotlin.math.sin(az) * 96f * kotlin.math.cos(pitchA)
                    val cy = EYE_CY - 8f - kotlin.math.sin(pitchA) * 62f
                    val w = w0 * zz
                    val h = h0 * zz
                    drawRoundRect(
                        color = Color(0xFFF5F2EA),
                        topLeft = androidx.compose.ui.geometry.Offset(cx - w / 2f, cy - h / 2f),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, h / 2f),
                    )
                }
            }
        }
        }
    }
    // 气泡文字：交给 Compose 排版（自动断行、行高一致）
    if (grown > 0.5f && bubbleText != null) {
        top.yukonga.miuix.kmp.basic.Text(
            text = bubbleText!!,
            color = Color(0xFFF5F2EA),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 5,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                .padding(horizontal = 15.dp),
        )
    }
    }
}

/** 把 SVG 路径按等弧长采样成固定点数，便于两种形状之间逐点插值 */
private fun sampleShape(pathData: String, count: Int = 96): FloatArray {
    val path = Path().apply {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(pathData).toPath(this)
    }
    val measure = androidx.compose.ui.graphics.PathMeasure()
    measure.setPath(path, false)
    val total = measure.length
    val out = FloatArray(count * 2)
    for (i in 0 until count) {
        val pos: androidx.compose.ui.geometry.Offset = measure.getPosition(total * i / count)
        out[i * 2] = pos.x
        out[i * 2 + 1] = pos.y
    }
    return out
}

private val blobSamples by lazy { sampleShape(BotShapes.BLOB) }
private val bubbleSamples by lazy { sampleShape(BotShapes.SQUIRCLE) }

/** 一组眼型的质心（相对 259 网格） */
private fun centroidOf(shape: List<FloatArray>): Pair<Float, Float> {
    var sx = 0f; var sy = 0f; var n = 0
    shape.forEach { poly ->
        var i = 0
        while (i + 1 < poly.size) { sx += poly[i]; sy += poly[i + 1]; n++; i += 2 }
    }
    return if (n == 0) 0f to 0f else (sx / n) to (sy / n)
}

private val refCentroid: Pair<Float, Float> by lazy { centroidOf(BotEyes.Shape0) }

/** 眼型相对基准眼型 0 的质心偏移：减掉它就等于「所有眼型正视前方」 */
private fun centOffset(shape: List<FloatArray>): Pair<Float, Float> {
    val c = centroidOf(shape)
    return (c.first - refCentroid.first) to (c.second - refCentroid.second)
}

private object Paths {
    val blob: Path by lazy {
        Path().apply {
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(BLOB_PATH).toPath(this)
        }
    }
}

/** pose.js 的目标姿态（只给目标值，弹簧负责逼近） */
private fun poseTargets(m: BotMood, mt: Float, sinceMs: Float, tapped: Boolean): FloatArray {
    var spin = 0f; var tx = 0f; var ty = 0f; var squash = 1f
    when (m) {
        BotMood.EXCITED -> {
            val et = (mt * 2.2f) % 1f
            val en = kotlin.math.sin(et * Math.PI).toFloat()
            ty = -en * 10f + 2f
            squash = if (et < 0.1f) 0.92f else if (et < 0.3f) 1.05f else 1f
            tx = kotlin.math.sin(mt * 1.1f) * 4f
            spin = kotlin.math.sin(mt * Math.PI.toFloat() * 2f * 1.1f) * 7f
        }
        BotMood.HAPPY -> {
            val et = kotlin.math.sin(mt * 2.4f)
            spin = kotlin.math.sin(mt * 1.2f) * 3f
            tx = kotlin.math.sin(mt * 1.1f) * 2.5f
            ty = -kotlin.math.abs(et) * 3f
            squash = 1f + et * 0.02f
        }
        BotMood.PLAYFUL -> {
            spin = kotlin.math.sin(mt * 1.4f) * 8f
            tx = kotlin.math.sin(mt * 1.1f) * 4f
            ty = -kotlin.math.abs(kotlin.math.sin(mt * 2.2f)) * 3f
            squash = 1f + kotlin.math.sin(mt * 2.2f) * 0.015f
        }
        BotMood.PROUD -> {
            spin = kotlin.math.sin(mt * 0.4f) * 2.5f
            tx = kotlin.math.sin(mt * 0.35f) * 2f
            ty = -4f + kotlin.math.sin(mt * 0.6f)
            squash = 1.03f
        }
        BotMood.SHY -> {
            spin = kotlin.math.sin(mt * 0.5f) * 3f - 8f
            tx = -3f + kotlin.math.sin(mt * 0.4f) * 2f
            ty = 3f; squash = 0.98f
        }
        BotMood.LAUGHING -> {
            val et = kotlin.math.sin(mt * Math.PI.toFloat() * 2f * 3.2f)
            spin = et * 4f
            tx = kotlin.math.sin(mt * 2f) * 2f
            ty = -kotlin.math.abs(et) * 5f
            squash = 1f + et * 0.03f
        }
        BotMood.CURIOUS -> {
            spin = kotlin.math.sin(mt * 0.7f) * 6f + 10f
            tx = kotlin.math.sin(mt * 0.6f) * 5f
            ty = -2f + kotlin.math.sin(mt * 0.9f) * 1.5f
            squash = 1.01f
        }
        BotMood.BORED -> {
            spin = kotlin.math.sin(mt * 0.25f) * 4f - 3f
            tx = kotlin.math.sin(mt * 0.2f) * 4f
            ty = 5f + kotlin.math.sin(mt * 0.35f) * 1.5f
            squash = 0.99f
        }
        BotMood.SEARCHING -> {
            val et = kotlin.math.sin(mt * 1.3f)
            spin = et * 13f; tx = et * 7f
            ty = kotlin.math.sin(mt * 1.7f) * 3f
        }
        BotMood.THINKING -> {
            spin = kotlin.math.sin(mt * 0.35f) * 5f - 9f
            tx = kotlin.math.sin(mt * 0.3f) * 5f
            ty = kotlin.math.sin(mt * 0.6f) * 2.5f
        }
        BotMood.HUMMING -> {
            spin = kotlin.math.sin(mt * 0.4f) * 2f
            tx = kotlin.math.sin(mt * 0.3f) * 1.5f
            ty = kotlin.math.sin(mt * 0.7f) * 1.5f
        }
        BotMood.SAD -> {
            spin = kotlin.math.sin(mt * 0.3f) * 2f + 3f
            tx = kotlin.math.sin(mt * 0.25f) * 1.5f
            ty = 7f + kotlin.math.sin(mt * 0.4f)
            squash = 0.97f
        }
        BotMood.LISTENING -> {
            spin = kotlin.math.sin(mt * 0.5f) * 1.5f + 8f
            tx = 2f
            ty = -2f + kotlin.math.sin(mt * 0.8f) * 0.8f
            squash = 1.015f
        }
        BotMood.WORKING -> {
            val et = kotlin.math.sin(mt * Math.PI.toFloat() * 2f * 1.6f)
            val up = maxOf(0f, et)
            spin = 4f + et * 2.5f; tx = 3f
            ty = 1.5f + up * 3f; squash = 1f - up * 0.02f
        }
        BotMood.SLEEPING -> {
            val en = (sinceMs / 2000f).coerceAtMost(1f)
            val zt = kotlin.math.sin((sinceMs / 500f).coerceAtMost(1f) * Math.PI.toFloat())
            spin = kotlin.math.sin(mt * 0.25f) * 2f + 4f * en
            tx = -2f * en
            ty = 8f * en + kotlin.math.sin(mt * 0.55f) * 3f - zt * 5f
            squash = 1f + kotlin.math.sin(mt * 0.55f) * 0.016f + zt * 0.05f
        }
        BotMood.SURPRISED -> {
            val et = (sinceMs / 1200f).coerceIn(0f, 1f)
            tx = -4f * (1f - et); ty = -8f * (1f - et)
            squash = if (sinceMs < 200f) 1.08f else 1f
            spin = kotlin.math.sin(mt * 11f) * 1.5f * (1f - et)
        }
        BotMood.WAKING -> {
            if (sinceMs < 500f) { ty = 6f } else { squash = 1.02f; spin = kotlin.math.sin(mt * 0.8f) * 2f }
        }
        BotMood.SUSPICIOUS -> {
            spin = kotlin.math.sin(mt * 0.3f) * 3f - 6f
            tx = kotlin.math.sin(mt * 0.25f) * -4f
            ty = 1f + kotlin.math.sin(mt * 0.45f) * 1.2f
            squash = 1f
        }
        BotMood.ANGRY -> {
            spin = kotlin.math.sin(mt * 40f) * 4.5f     // 抖动（仓库用 now*0.05 的快速正弦）
            tx = 0f; ty = 0f; squash = 1f
        }
        BotMood.DROWSY -> {
            spin = kotlin.math.sin(mt * 0.32f) * 2.5f
            tx = kotlin.math.sin(mt * 0.2f) * 1.5f
            ty = 6f + kotlin.math.sin(mt * 0.36f) * 2.2f
            squash = 1f + kotlin.math.sin(mt * 0.36f) * 0.022f
        }
        BotMood.SCARED -> {
            spin = kotlin.math.sin(mt * 0.8f) * 2f
            tx = -2f + kotlin.math.sin(mt * 0.9f) * 1.5f
            ty = 2f + kotlin.math.sin(mt * 1.5f)
            squash = 0.97f
        }
        BotMood.ALERTING -> {
            // pose.js 里 alerting 没有专门分支，落到 default（pt/0/0/1），差异只在眼型
            spin = 0f; tx = 0f; ty = 0f; squash = 1f
        }
        BotMood.NOTIFYING -> {
            // pose.js notifying：轻轻一挺
            spin = 3f; tx = 2f; ty = -1f; squash = 1f
        }
        BotMood.IDLE -> {
            spin = kotlin.math.sin(mt * 0.5f) * 1.5f + kotlin.math.sin(mt * 0.17f) * 0.6f
            tx = kotlin.math.sin(mt * 0.27f)
            ty = kotlin.math.sin(mt * 0.85f) * 1.2f
            squash = 1f + kotlin.math.sin(mt * 0.85f) * 0.007f
        }
    }
    return floatArrayOf(spin, tx, ty, squash)
}
