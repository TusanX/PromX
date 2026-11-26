// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.core.alt.BiliQuality
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs

internal enum class BiliPlayerPanel { NONE, QUALITY, SPEED }

private val PlayerPink = Color(0xFFFB7299)
private val SheetText = Color(0xFFF4F5F7)
private val SheetMuted = Color(0xFF9AA0AA)
private val SheetBackground = Color(0xFF17181D)

/**
 * A screen-level playback sheet. Keeping this outside the 16:9 video bounds prevents the quality
 * list from being clipped in portrait while still covering the stage in landscape.
 */
@Composable
internal fun BiliPlayerSettingsSheet(
    panel: BiliPlayerPanel,
    qualities: List<BiliQuality.Option>,
    selectedQn: Int,
    speed: Float,
    onQuality: (BiliQuality.Option) -> Unit,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == BiliPlayerPanel.NONE) return
    val dismissInteraction = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = .52f))
            .clickable(interactionSource = dismissInteraction, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.82f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(SheetBackground)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .padding(top = 9.dp)
                .testTag("bili_player_options_sheet"),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .18f)),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (panel == BiliPlayerPanel.QUALITY) "清晰度" else "播放速度",
                        color = SheetText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (panel == BiliPlayerPanel.QUALITY) "由哔哩哔哩当前账号权限决定可用线路" else "切换后保持当前播放位置",
                        color = SheetMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    "完成",
                    Modifier.clip(RoundedCornerShape(9.dp)).background(PlayerPink.copy(alpha = .14f))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "关闭播放选项" }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = PlayerPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (panel == BiliPlayerPanel.QUALITY) {
                val options = qualities.ifEmpty {
                    BiliQuality.definitions.map {
                        BiliQuality.Option(it.qn, it.label, it.detail, it.requiresLogin, it.requiresVip, false)
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 13.dp, end = 13.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(options, key = { it.qn }) { option ->
                        QualityRow(
                            option = option,
                            selected = option.qn == selectedQn,
                            onClick = { onQuality(option) },
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        val selected = abs(speed - value) < .01f
                        Box(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (selected) PlayerPink.copy(alpha = .18f) else Color.White.copy(alpha = .055f))
                                .clickable(role = Role.Button) { onSpeed(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                speedLabel(value),
                                color = if (selected) PlayerPink else SheetText,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun QualityRow(
    option: BiliQuality.Option,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val available = option.available || selected
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) PlayerPink.copy(alpha = .14f) else Color.White.copy(alpha = .05f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                color = if (available) SheetText else SheetMuted.copy(alpha = .72f),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !option.available && option.requiresVip -> "大会员专享"
                    !option.available && option.requiresLogin -> "登录哔哩哔哩后可用"
                    !option.available -> "当前视频不可用"
                    else -> option.detail
                },
                color = SheetMuted,
                fontSize = 9.5.sp,
                maxLines = 1,
            )
        }
        Text(
            if (selected) "✓" else if (option.requiresVip) "大会员" else "",
            color = if (selected) PlayerPink else SheetMuted,
            fontSize = if (selected) 17.sp else 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

internal fun speedLabel(value: Float): String = when {
    value < .7f -> "0.5×"
    value < .9f -> "0.75×"
    value < 1.1f -> "1.0×"
    value < 1.4f -> "1.25×"
    value < 1.8f -> "1.5×"
    else -> "2.0×"
}

