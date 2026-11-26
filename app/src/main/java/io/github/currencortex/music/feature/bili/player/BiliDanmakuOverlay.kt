// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.core.alt.BiliPlayback

/** Draws the public Bilibili comment track above the video without causing PlayerView to rebuild. */
@Composable
internal fun BiliDanmakuOverlay(
    items: List<BiliPlayback.Danmaku>,
    visible: Boolean,
    playing: Boolean,
    positionMs: Long,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    if (!visible || items.isEmpty()) return
    var frame by remember { mutableLongStateOf(0L) }
    val anchor = remember(playing, positionMs) {
        if (playing) FrameAnchor(positionMs, SystemClock.elapsedRealtime()) else null
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) withFrameNanos { frame++ }
    }
    val density = LocalDensity.current
    Canvas(modifier.fillMaxSize()) {
        frame
        val estimated = anchor?.let {
            it.positionMs + ((SystemClock.elapsedRealtime() - it.realMs) * speed.coerceIn(.5f, 2f)).toLong()
        } ?: positionMs
        val lineHeight = 22.dp.toPx()
        val lanes = (size.height * .58f / lineHeight).toInt().coerceIn(3, 12)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = with(density) { 11.sp.toPx() }
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK)
        }
        val active = items.asSequence()
            .filter { it.timeMs <= estimated && estimated - it.timeMs <= 8_500L }
            .toList()
            .takeLast(56)
        active.forEachIndexed { index, item ->
            if (item.mode == 7) return@forEachIndexed
            paint.color = 0xFF000000.toInt() or (item.color and 0x00FFFFFF)
            paint.textSize = with(density) {
                (10.5f * item.fontScale / 25f).coerceIn(9f, 14f).sp.toPx()
            }
            val width = paint.measureText(item.text)
            when (item.mode) {
                4, 5 -> {
                    val elapsed = estimated - item.timeMs
                    if (elapsed <= 4_300L) {
                        paint.alpha = (255 * ((4_300L - elapsed) / 700f).coerceIn(0f, 1f)).toInt()
                        val y = if (item.mode == 5) 18.dp.toPx() + (index % lanes) * lineHeight
                        else size.height - 18.dp.toPx() - (index % lanes) * lineHeight
                        drawContext.canvas.nativeCanvas.drawText(item.text, size.width * .5f, y, paint)
                    }
                }
                else -> {
                    val progress = ((estimated - item.timeMs) / 8_500f).coerceIn(0f, 1f)
                    val x = size.width - progress * (size.width + width)
                    val lane = (item.text.hashCode() and Int.MAX_VALUE) % lanes
                    paint.alpha = if (progress > .92f) {
                        (255 * ((1f - progress) / .08f)).toInt().coerceIn(0, 255)
                    } else 255
                    paint.textSize = with(density) {
                        (10.5f * item.fontScale / 25f).coerceIn(9f, 14f).sp.toPx()
                    }
                    drawContext.canvas.nativeCanvas.drawText(item.text, x, 15.dp.toPx() + lane * lineHeight, paint)
                }
            }
        }
    }
}

private data class FrameAnchor(val positionMs: Long, val realMs: Long)
