// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

private val ControlPink = Color(0xFFFB7299)
private val ControlText = Color(0xFFF7F8FA)
private val ControlMuted = Color(0xFFA7ABB3)

internal enum class BiliControlGlyph {
    BACK, PLAY, PAUSE, DANMAKU, ORIENTATION, PORTRAIT,
}

@Composable
internal fun BiliPlayerControlsOverlay(
    title: String,
    uploader: String,
    landscape: Boolean,
    visible: Boolean,
    playback: BiliPlayerSessionState,
    loadingSource: Boolean,
    danmakuEnabled: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onToggleDanmaku: () -> Unit,
    onPanel: (BiliPlayerPanel) -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = .56f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = .82f),
                        ),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .statusBarsPadding().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlIconButton(BiliControlGlyph.BACK, "返回", onBack, "bili_player_back", 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        title.ifBlank { "哔哩哔哩" },
                        color = ControlText,
                        fontSize = if (landscape) 14.sp else 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (uploader.isNotBlank()) {
                        Text(uploader, color = ControlMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
                if (loadingSource) {
                    CircularProgressIndicator(Modifier.size(16.dp), size = 16.dp, strokeWidth = 1.8.dp)
                    Spacer(Modifier.size(8.dp))
                }
            }

            if (!playback.playWhenReady && !playback.ended && !loadingSource) {
                Box(
                    Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .54f))
                        .clickable(role = Role.Button, onClick = onPlayPause)
                        .semantics { contentDescription = "播放" },
                    contentAlignment = Alignment.Center,
                ) {
                    ControlGlyph(BiliControlGlyph.PLAY, Color.White, Modifier.size(25.dp))
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .navigationBarsPadding().padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                BiliPlayerProgressBar(
                    positionMs = playback.positionMs,
                    bufferedMs = playback.bufferedPositionMs,
                    durationMs = playback.durationMs,
                    onPreview = onSeekPreview,
                    onCommit = onSeekCommit,
                )
                Row(
                    Modifier.fillMaxWidth().height(46.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ControlIconButton(
                        if (playback.playWhenReady) BiliControlGlyph.PAUSE else BiliControlGlyph.PLAY,
                        if (playback.playWhenReady) "暂停" else "播放",
                        onPlayPause,
                        "bili_player_play_pause",
                        44.dp,
                    )
                    Text(
                        timeText(playback.positionMs) + " / " + timeText(playback.durationMs),
                        color = ControlText.copy(alpha = .84f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 3.dp),
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    ControlIconButton(
                        BiliControlGlyph.DANMAKU,
                        if (danmakuEnabled) "关闭弹幕" else "显示弹幕",
                        onToggleDanmaku,
                        "bili_player_danmaku",
                        40.dp,
                        selected = danmakuEnabled,
                    )
                    ControlLabel(
                        playback.qualityLabel.ifBlank {
                            "清晰度"
                        },
                        "选择清晰度",
                        { onPanel(BiliPlayerPanel.QUALITY) },
                        "bili_player_quality",
                    )
                    ControlLabel(
                        speedLabel(playback.speed),
                        "播放速度",
                        { onPanel(BiliPlayerPanel.SPEED) },
                        "bili_player_speed",
                    )
                    ControlIconButton(
                        if (landscape) BiliControlGlyph.PORTRAIT else BiliControlGlyph.ORIENTATION,
                        if (landscape) "竖屏播放" else "横屏播放",
                        onToggleOrientation,
                        "bili_player_orientation",
                        40.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BiliPlayerProgressBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onPreview: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0L) positionMs.toFloat() / duration else 0f
    val buffered = if (duration > 0L) bufferedMs.toFloat() / duration else 0f
    Box(
        Modifier.fillMaxWidth().height(24.dp).pointerInput(duration) {
            if (duration <= 0L) return@pointerInput
            val width = size.width.toFloat().coerceAtLeast(1f)
            var dragPosition = positionMs.coerceAtLeast(0L)
            fun target(x: Float) = ((x / width).coerceIn(0f, 1f) * duration).toLong()
            detectDragGestures(
                onDragStart = { offset ->
                    dragPosition = target(offset.x)
                    onPreview(dragPosition)
                },
                onDrag = { change, _ ->
                    change.consume()
                    dragPosition = target(change.position.x)
                    onPreview(dragPosition)
                },
                onDragEnd = { onCommit(dragPosition) },
                onDragCancel = { onCommit(dragPosition) },
            )
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)))
        Box(
            Modifier.fillMaxWidth(buffered.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = .30f)),
        )
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape)
                .background(ControlPink),
        )
    }
}

@Composable
private fun ControlIconButton(
    glyph: BiliControlGlyph,
    description: String,
    onClick: () -> Unit,
    tag: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    selected: Boolean = false,
) {
    Box(
        Modifier.size(buttonSize).testTag(tag).clip(CircleShape)
            .background(if (selected) ControlPink.copy(alpha = .15f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ControlGlyph(glyph, if (selected) ControlPink else ControlText.copy(alpha = .9f), Modifier.size(22.dp))
    }
}

@Composable
private fun ControlLabel(
    label: String,
    description: String,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier.height(36.dp).testTag(tag).clip(RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = .22f)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ControlText.copy(alpha = .9f), fontSize = 9.5.sp, maxLines = 1)
    }
}

@Composable
private fun ControlGlyph(glyph: BiliControlGlyph, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        when (glyph) {
            BiliControlGlyph.BACK -> {
                val path = Path().apply {
                    moveTo(size.width * .65f, size.height * .20f)
                    lineTo(size.width * .34f, size.height * .50f)
                    lineTo(size.width * .65f, size.height * .80f)
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BiliControlGlyph.PLAY -> {
                val path = Path().apply {
                    moveTo(size.width * .34f, size.height * .20f)
                    lineTo(size.width * .80f, size.height * .50f)
                    lineTo(size.width * .34f, size.height * .80f)
                    close()
                }
                drawPath(path, tint)
            }
            BiliControlGlyph.PAUSE -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .29f, size.height * .20f),
                    size = androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .60f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .57f, size.height * .20f),
                    size = androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .60f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
            BiliControlGlyph.DANMAKU -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .11f, size.height * .20f),
                    size = androidx.compose.ui.geometry.Size(size.width * .78f, size.height * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(tint, Offset(size.width * .28f, size.height * .37f), Offset(size.width * .72f, size.height * .37f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .36f, size.height * .53f), Offset(size.width * .64f, size.height * .53f), stroke, StrokeCap.Round)
            }
            BiliControlGlyph.ORIENTATION, BiliControlGlyph.PORTRAIT -> {
                if (glyph == BiliControlGlyph.ORIENTATION) {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(size.width * .24f, size.height * .14f),
                        size = androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .40f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        style = Stroke(stroke),
                    )
                    drawRoundRect(
                        tint,
                        topLeft = Offset(size.width * .14f, size.height * .44f),
                        size = androidx.compose.ui.geometry.Size(size.width * .30f, size.height * .44f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        style = Stroke(stroke),
                    )
                } else {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(size.width * .28f, size.height * .12f),
                        size = androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .76f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(stroke),
                    )
                }
            }
        }
    }
}

internal fun timeText(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1_000L)
    val hours = total / 3_600L
    val minutes = (total % 3_600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
