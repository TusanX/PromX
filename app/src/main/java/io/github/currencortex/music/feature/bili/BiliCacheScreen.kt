// SPDX-License-Identifier: GPL-3.0-only
// 哔哩哔哩 模式的「本地缓存」：已下载视频 + 音频缓存占用，点条目直接播本地文件。
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun BiliCacheScreen(
    onBack: () -> Unit,
    onPlay: (BiliCacheItem) -> Unit,
) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<BiliCacheItem>>(emptyList()) }
    var audios by remember { mutableStateOf<List<BiliCacheItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        videos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BiliCacheStore.videos(context)
        }
        audios = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BiliCacheStore.audios(context)
        }
    }
    val videoBytes = videos.sumOf { it.sizeBytes }
    val audioBytes = audios.sumOf { it.sizeBytes }
    var cleared by remember { mutableStateOf(false) }
    fun clearAudioCache() {
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    java.io.File(context.cacheDir, "audio").deleteRecursively()
                }
            }
            audios = emptyList()
            cleared = true
        }
    }

    MusicSubpage("本地缓存", "bili_cache", onBack) { topInset ->
        LazyColumn(Modifier.fillMaxSize().testTag("bili_cache_list"),
            contentPadding = PaddingValues(top = topInset + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard("视频缓存", "${videos.size} 个", BiliCacheStore.formatSize(videoBytes), Modifier.weight(1f))
                    StatCard("音频缓存", "${audios.size} 个", BiliCacheStore.formatSize(audioBytes), Modifier.weight(1f))
                }
            }
            if (audios.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cleared) "已清理" else "清理音频缓存（不影响已下载视频）",
                            Modifier.weight(1f), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                        Text("清理", Modifier.clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .clickable { clearAudioCache() }
                            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("bili_cache_clear"),
                            fontSize = 12.5.sp)
                    }
                }
            }
            if (videos.isEmpty() && audios.isEmpty()) {
                item {
                    Text("还没有缓存。播放 哔哩哔哩 歌曲时点歌曲菜单里的「下载视频」，就会出现在这里。",
                        Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
            }
            if (videos.isNotEmpty()) {
                item { SectionLabel("已下载视频") }
                items(videos, key = { it.uri }) { item ->
                    CacheRow(item) { onPlay(item) }
                }
            }
            if (audios.isNotEmpty()) {
                item { SectionLabel("音频缓存") }
                items(audios.take(60), key = { it.uri }) { item ->
                    CacheRow(item) { onPlay(item) }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
}

@Composable private fun StatCard(title: String, count: String, size: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer).padding(14.dp),
    ) {
        Text(title, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
        Text(count, Modifier.padding(top = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(size, Modifier.padding(top = 2.dp), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
    }
}

@Composable private fun CacheRow(item: BiliCacheItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (item.isVideo) "视频" else "音频", fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, fontSize = 14.sp, maxLines = 1)
            Text(BiliCacheStore.formatSize(item.sizeBytes), Modifier.padding(top = 2.dp),
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
    }
}

/** 把缓存文件包成可播放的 Song（哔哩哔哩 来源 + 本地路径） */
fun toSongForPlay(item: BiliCacheItem): Song = item.toSong()

fun BiliCacheItem.toSong(): Song = Song(
    id = uri.hashCode().toLong().let { if (it < 0) -it else it },
    name = name.substringBeforeLast('.'),
    artists = "本地缓存 · 哔哩哔哩",
    musicSource = MusicSource.BILIBILI,
    localUri = uri,
)
