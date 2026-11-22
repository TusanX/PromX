// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.currencortex.music.R
import io.github.currencortex.music.data.song.MusicSource
import top.yukonga.miuix.kmp.basic.Text

private data class PromSourceSpec(
    val source: MusicSource,
    val name: String,
    val accent: Color,
    val icon: Int,
)

private val promSources = listOf(
    PromSourceSpec(MusicSource.NETEASE, "网易云", Color(0xFFEC4141), R.drawable.brand_netease_official),
    PromSourceSpec(MusicSource.QQ_MUSIC, "QQ 音乐", Color(0xFF31C27C), R.drawable.brand_qqmusic_official),
    PromSourceSpec(MusicSource.BILIBILI, "哔哩哔哩", Color(0xFFFB7299), R.drawable.brand_bilibili),
)

private fun specOf(source: MusicSource): PromSourceSpec = promSources.firstOrNull { it.source == source } ?: promSources.first()

internal fun promSourceSearchHint(source: MusicSource): String = when (source) {
    MusicSource.QQ_MUSIC -> "搜索 QQ 音乐里的歌曲、歌手"
    MusicSource.BILIBILI -> "搜索视频、UP 主或音乐"
    else -> "搜索网易云歌曲、歌手或歌词"
}

/** Long press PromBot to open this springy source tray. */
@Composable
fun JellySourcePicker(
    show: Boolean,
    selected: MusicSource,
    connected: Map<MusicSource, Boolean>,
    onDismiss: () -> Unit,
    onSelect: (MusicSource) -> Unit,
    onManage: (MusicSource) -> Unit,
) {
    val expansion by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = spring(dampingRatio = .76f, stiffness = 540f),
        label = "prom-source-jelly",
    )
    if (!show && expansion < .01f) return
    val selectedSpec = specOf(selected)
    val connectedNow = connected[selected] == true
    val haptics = LocalHapticFeedback.current
    val dismissInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(40f)
            .background(Color.Black.copy(alpha = .16f * expansion.coerceIn(0f, 1f)))
            .clickable(interactionSource = dismissInteraction, indication = null, role = Role.Button, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 70.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 380.dp)
                .graphicsLayer {
                    alpha = (expansion * 2.2f).coerceIn(0f, 1f)
                    translationY = (1f - expansion) * -18.dp.toPx()
                    scaleX = .88f + .12f * expansion
                    scaleY = .86f + .14f * expansion
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .shadow(24.dp, RoundedCornerShape(30.dp), clip = false)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF24282E), Color(0xFF0D0F12))),
                    RoundedCornerShape(30.dp),
                )
                .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(30.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(16.dp)
                .testTag("jelly_source_picker"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("音乐来源", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "选择后，搜索、推荐和收藏会一起切换",
                        color = Color.White.copy(alpha = .52f),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
                SourceAppIcon(selectedSpec, 26.dp)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                promSources.forEachIndexed { index, spec ->
                    JellySourceButton(
                        spec = spec,
                        active = spec.source == selected,
                        connected = connected[spec.source] == true,
                        expansion = expansion,
                        index = index,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(spec.source)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = .055f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(6.dp).background(
                        if (connectedNow) Color(0xFF34C759) else Color.White.copy(alpha = .28f),
                        CircleShape,
                    ),
                )
                Text(
                    if (connectedNow) "${selectedSpec.name}账号已连接" else "仍可浏览公开内容，登录可读取账号收藏",
                    modifier = Modifier.weight(1f),
                    color = Color.White.copy(alpha = .62f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (connectedNow) "管理" else "连接",
                    modifier = Modifier.clickable(role = Role.Button) { onManage(selected) }.padding(horizontal = 5.dp, vertical = 4.dp),
                    color = selectedSpec.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun JellySourceButton(
    spec: PromSourceSpec,
    active: Boolean,
    connected: Boolean,
    expansion: Float,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeScale by animateFloatAsState(
        targetValue = if (active) 1.045f else 1f,
        animationSpec = spring(dampingRatio = .82f, stiffness = 600f),
        label = "jelly-source-active-${spec.source.name}",
    )
    val surface by animateColorAsState(
        targetValue = if (active) spec.accent.copy(alpha = .24f) else Color.White.copy(alpha = .055f),
        animationSpec = tween(180),
        label = "jelly-source-colour-${spec.source.name}",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = spring(dampingRatio = .8f, stiffness = 650f),
        label = "jelly-source-press",
    )
    Column(
        modifier
            .graphicsLayer {
                alpha = ((expansion - index * .035f) * 2.8f).coerceIn(0f, 1f)
                translationY = (1f - expansion) * (12.dp + (index * 4).dp).toPx()
                scaleX = pressScale * activeScale
                scaleY = pressScale * activeScale
            }
            .background(surface, RoundedCornerShape(22.dp))
            .border(1.dp, if (active) spec.accent.copy(alpha = .7f) else Color.White.copy(alpha = .07f), RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .testTag("jelly_source_${spec.source.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SourceAppIcon(spec, 52.dp)
        Text(
            spec.name,
            color = Color.White.copy(alpha = if (active) 1f else .78f),
            fontSize = 11.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(4.dp).background(if (connected) Color(0xFF34C759) else Color.White.copy(alpha = .23f), CircleShape))
            Text(if (connected) "已连接" else "未登录", color = Color.White.copy(alpha = .38f), fontSize = 9.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SourceAppIcon(spec: PromSourceSpec, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(size * .24f)
    if (spec.source == MusicSource.BILIBILI) {
        Box(
            Modifier.size(size).clip(shape).background(
                Brush.linearGradient(listOf(Color(0xFFFB7299), Color(0xFFEA7EA9))),
                shape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(spec.icon), contentDescription = null, Modifier.size(size * .64f))
        }
    } else {
        Image(
            painter = painterResource(spec.icon),
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
        )
    }
}
