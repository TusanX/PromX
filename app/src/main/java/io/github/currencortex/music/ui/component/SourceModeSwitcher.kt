// SPDX-License-Identifier: GPL-3.0-only
// 内容来源切换器：三段式，指示块用弹簧滑动，标签颜色交叉淡入。
// 这是「换模式 = 整个内容层跟着换」的唯一入口，所以放在主页搜索栏下方常驻。
package io.github.currencortex.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.data.song.MusicSource
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val modes = listOf(
    MusicSource.NETEASE to "网易云",
    MusicSource.QQ_MUSIC to "QQ 音乐",
    MusicSource.BILIBILI to "哔哩哔哩",
)

/**
 * @param selected 当前模式
 * @param onSelect 选择回调（会立刻反映到指示块位置，不等数据回来）
 */
@Composable fun SourceModeSwitcher(
    selected: MusicSource,
    onSelect: (MusicSource) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val count = modes.size
    val index = modes.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    // 弹簧滑动：Material 的 medium-low 刚度 + 轻微阻尼，停下来时有一点「落位感」
    val position by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "source-mode-position",
    )
    var trackWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val inset = 3.dp
    val segmentWidthPx = remember(trackWidth, count, density) {
        if (trackWidth == 0) 0f else with(density) { (trackWidth - (inset * 2).toPx()) / count }
    }
    val track = if (dark) Color.White.copy(alpha = .08f) else MiuixTheme.colorScheme.surfaceVariant

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (dark) Color.White.copy(alpha = .07f) else MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
            .onSizeChanged { trackWidth = it.width }
            .testTag("source_mode_switcher"),
    ) {
        // 指示块：只做位置与宽度动画，不参与布局测量，避免每帧重排
        Box(
            Modifier
                .padding(inset)
                .offset { IntOffset((segmentWidthPx * position).roundToInt(), 0) }
                .width(with(density) { segmentWidthPx.toDp() })
                .fillMaxHeight()
                .clip(RoundedCornerShape(19.dp))
                // 品牌色淡底 + 品牌色文字：比纯白块更清楚地读出「当前是这一个」
                .background(if (dark) Color(0xFFFB7299).copy(alpha = .22f) else MiuixTheme.colorScheme.primary.copy(alpha = .15f))
                .testTag("source_mode_indicator"),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            modes.forEachIndexed { i, (source, label) ->
                val active = i == index
                val color by animateColorAsState(
                    targetValue = if (active) (if (dark) Color(0xFFFB7299) else MiuixTheme.colorScheme.primary)
                    else (if (dark) Color.White.copy(alpha = .55f) else MiuixTheme.colorScheme.onSurface.copy(alpha = .58f)),
                    animationSpec = androidx.compose.animation.core.tween(180),
                    label = "source-mode-label-$i",
                )
                val weight = if (active) FontWeight.SemiBold else FontWeight.Medium
                val interactions = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(19.dp))
                        .clickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            role = Role.Tab,
                        ) { if (!active) onSelect(source) }
                        .semantics { this.selected = active }
                        .testTag("source_mode_${source.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = weight, color = color)
                }
            }
        }
    }
}

/** 模式附带的登录状态提示，一句话说清「现在这个模式能不能用账号数据」 */
@Composable fun SourceModeHint(
    selected: MusicSource,
    neteaseBound: Boolean,
    qqLoggedIn: Boolean,
    biliLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val text: String
    val ok: Boolean
    when (selected) {
        MusicSource.BILIBILI -> {
            ok = biliLoggedIn
            text = if (ok) "已连接 B站，歌单与「我喜欢」同步自收藏夹" else "未登录 B站，仅能播放与看评论"
        }
        MusicSource.QQ_MUSIC -> {
            ok = qqLoggedIn
            text = if (ok) "已连接 QQ 音乐，可播放免费曲目" else "未登录 QQ 音乐，会员曲目无法播放"
        }
        else -> {
            ok = neteaseBound
            text = if (ok) "已绑定网易云，推荐与喜欢来自你的账号" else "未绑定网易云，推荐使用公开歌单"
        }
    }
    val color by animateColorAsState(
        targetValue = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = .5f),
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "source-mode-hint-color",
    )
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = if (ok) .9f else .45f)),
        )
        Text(text, fontSize = 11.5.sp, color = color, modifier = Modifier.testTag("source_mode_hint"))
    }
}
