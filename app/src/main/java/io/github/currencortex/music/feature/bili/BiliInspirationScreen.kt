// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.nativeui.ElasticPress
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val BiliAccent = Color(0xFFFB7299)
private val BiliGold = Color(0xFFE8BD70)

/** A focused lane inside 哔哩哔哩 for long-form inspirational 仙尊 videos and audio. */
@Composable
internal fun BiliInspirationScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onPlay: (List<BiliInspirationRepository.Video>, Int, Boolean) -> Unit,
) {
    var selectedGroup by remember { mutableStateOf("all") }
    var videos by remember { mutableStateOf<List<BiliInspirationRepository.Video>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var videoFirst by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val cookie = remember(container) { container.biliSession.cookieHeader() }

    LaunchedEffect(selectedGroup, refresh) {
        loading = true
        error = null
        val result = runCatching {
            withContext(Dispatchers.IO) {
                BiliInspirationRepository.load(selectedGroup, cookie)
            }
        }
        videos = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
        loading = false
    }

    MusicSubpage("励志仙尊", "bili_inspiration", onBack, backTag = "bili_inspiration_back") { topInset ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("bili_inspiration_list"),
            contentPadding = PaddingValues(start = 16.dp, top = topInset + 8.dp, end = 16.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") { InspirationHero(container.biliSession.loggedIn) }
            item(key = "filters") { InspirationFilters(selectedGroup) { selectedGroup = it } }
            item(key = "play_mode") {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayModePill(
                            label = "看视频",
                            detail = "进入竖屏视频",
                            selected = videoFirst,
                            modifier = Modifier.weight(1f),
                        ) { videoFirst = true }
                        PlayModePill(
                            label = "听音频",
                            detail = "仅播放声音",
                            selected = !videoFirst,
                            modifier = Modifier.weight(1f),
                        ) { videoFirst = false }
                    }
                }
            }

            when {
                loading && videos.isEmpty() -> items(4) { index ->
                    InspirationLoadingRow(index)
                }
                error != null && videos.isEmpty() -> item(key = "error") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("内容加载失败", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            SettingsHint("检查网络后重试。未登录哔哩哔哩账号也可以浏览公开内容。")
                            InspirationAction("重新加载", Modifier.fillMaxWidth()) { refresh++ }
                        }
                    }
                }
                videos.isEmpty() -> item(key = "empty") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("暂时没有找到合适的内容", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            SettingsHint("更换分类或稍后刷新。搜索只保留带人生成长、修行与励志主题的长内容。")
                            InspirationAction("重新加载", Modifier.fillMaxWidth()) { refresh++ }
                        }
                    }
                }
                else -> {
                    item(key = "section_title") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selectedGroup == "all") "为你整理" else BiliInspirationRepository.groups.firstOrNull { it.id == selectedGroup }?.title.orEmpty(),
                                Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            )
                            Text("${videos.size} 条", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .48f))
                        }
                    }
                    itemsIndexed(videos, key = { _, video -> video.bvid }) { index, video ->
                        InspirationVideoRow(
                            video = video,
                            videoFirst = videoFirst,
                            onClick = { onPlay(videos, index, videoFirst) },
                        )
                    }
                    item(key = "refresh") {
                        InspirationAction(if (loading) "正在整理…" else "换一批", Modifier.fillMaxWidth(), !loading) { refresh++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspirationHero(loggedIn: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF39232B), Color(0xFF17161A))))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(BiliAccent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(MusicIcons.Rocket, null, Modifier.size(21.dp), tint = BiliAccent)
            }
            Column {
                Text("在人人生路上，守住自己的方向", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (loggedIn) "基于你的哔哩哔哩账号，持续整理励志仙尊视频与音频"
                    else "公开搜索可用，登录后可同步哔哩哔哩偏好",
                    color = Color.White.copy(alpha = .58f), fontSize = 11.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HeroTag("长视频优先")
            HeroTag("低谷与成长")
            HeroTag("可只听音频")
        }
    }
}

@Composable
private fun HeroTag(label: String) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .075f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = Color.White.copy(alpha = .74f), fontSize = 10.sp)
    }
}

@Composable
private fun InspirationFilters(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        BiliInspirationRepository.groups.forEach { group ->
            val active = group.id == selected
            ElasticPress(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (active) BiliAccent else MiuixTheme.colorScheme.surfaceContainer)
                    .clickable { onSelect(group.id) }
                    .padding(vertical = 8.dp).testTag("bili_inspiration_${group.id}"),
            ) {
                Text(
                    group.title, Modifier.fillMaxWidth(),
                    color = if (active) Color.White else MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PlayModePill(
    label: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElasticPress(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) BiliGold.copy(alpha = .14f) else MiuixTheme.colorScheme.onSurface.copy(alpha = .035f))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) BiliGold else MiuixTheme.colorScheme.onSurface)
            Text(detail, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .48f))
        }
    }
}

@Composable
private fun InspirationVideoRow(
    video: BiliInspirationRepository.Video,
    videoFirst: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).testTag("bili_inspiration_${video.bvid}")
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(132.dp).height(80.dp).clip(RoundedCornerShape(14.dp))) {
            AsyncImage(
                video.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (videoFirst) MusicIcons.Play else MusicIcons.Headphones,
                    null, Modifier.size(12.dp), tint = Color.White,
                )
                Spacer(Modifier.width(4.dp))
                Text(formatDuration(video.durationSec), color = Color.White, fontSize = 9.sp)
            }
            if (video.featured) {
                Text(
                    "本期", Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape)
                        .background(BiliAccent).padding(horizontal = 7.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(video.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(video.owner, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
            Text(
                buildString {
                    if (video.views > 0) append(compact(video.views)).append(" 播放")
                    if (video.durationSec >= 20 * 60) {
                        if (isNotEmpty()) append(" · ")
                        append("长内容")
                    }
                }.ifBlank { "哔哩哔哩" },
                fontSize = 10.sp, color = BiliAccent.copy(alpha = .78f),
            )
        }
        Icon(MusicIcons.Forward, null, Modifier.size(17.dp), tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .3f))
    }
}

@Composable
private fun InspirationLoadingRow(index: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(132.dp).height(80.dp).clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .055f)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(if (index % 2 == 0) .9f else .72f).height(14.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .055f)))
            Box(Modifier.fillMaxWidth(.55f).height(10.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .04f)))
        }
    }
}

@Composable
private fun InspirationAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    ElasticPress(
        modifier.clip(CircleShape).background(BiliAccent.copy(alpha = if (enabled) .16f else .06f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
        enabled = enabled,
    ) {
        Text(
            label, Modifier.fillMaxWidth(), color = if (enabled) BiliAccent else MiuixTheme.colorScheme.onSurface.copy(alpha = .3f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

private fun compact(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}
