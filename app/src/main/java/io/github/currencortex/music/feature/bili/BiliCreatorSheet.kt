// SPDX-License-Identifier: GPL-3.0-only
// UP 主主页 / 相关推荐：B站 视频的「歌手页」与「相似歌曲」
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.ui.component.MusicOverlayDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun BiliCreatorSheet(
    container: AppContainer,
    mid: Long,
    onDismiss: () -> Unit,
    onPlay: (BiliPlayback.RelatedVideo) -> Unit = {},
) {
    val cookie = container.biliSession.cookieHeader()
    var creator by remember(mid) { mutableStateOf<BiliPlayback.Creator?>(null) }
    var videos by remember(mid) { mutableStateOf<List<BiliPlayback.RelatedVideo>>(emptyList()) }
    var loading by remember(mid) { mutableStateOf(true) }
    LaunchedEffect(mid) {
        val (c, v) = withContext(Dispatchers.IO) {
            BiliPlayback.creator(mid, cookie) to BiliPlayback.creatorVideos(mid, 1, cookie)
        }
        creator = c; videos = v; loading = false
    }
    MusicOverlayDialog(show = true, onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(creator?.avatar.orEmpty(), null,
                        Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(creator?.name ?: "UP 主", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(buildString {
                            if ((creator?.fans ?: 0) > 0) append("${compact(creator!!.fans)} 粉丝")
                        }.ifBlank { if (loading) "正在加载…" else "" },
                            Modifier.padding(top = 2.dp), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                    }
                }
                creator?.sign?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f))
                }
                Text("投稿视频", Modifier.padding(top = 14.dp, bottom = 4.dp), fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
            }
            items(videos, key = { it.bvid }) { video ->
                Row(Modifier.fillMaxWidth().clickable { onPlay(video) }
                    .padding(vertical = 6.dp).testTag("bili_creator_${video.bvid}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(video.cover, null, Modifier.width(104.dp).height(60.dp)
                        .clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(video.title, fontSize = 14.sp, maxLines = 2)
                        Text(formatDuration(video.durationSec), Modifier.padding(top = 2.dp),
                            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}

@Composable fun BiliRelatedSheet(
    container: AppContainer,
    bvid: String,
    onDismiss: () -> Unit,
    onPlay: (BiliPlayback.RelatedVideo) -> Unit = {},
) {
    val cookie = container.biliSession.cookieHeader()
    var items by remember(bvid) { mutableStateOf<List<BiliPlayback.RelatedVideo>>(emptyList()) }
    var loading by remember(bvid) { mutableStateOf(true) }
    LaunchedEffect(bvid) {
        items = withContext(Dispatchers.IO) { BiliPlayback.related(bvid, cookie) }
        loading = false
    }
    MusicOverlayDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("相关推荐", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (loading) Text("正在加载…", Modifier.padding(top = 10.dp), fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.bvid }) { video ->
                    Row(Modifier.fillMaxWidth().clickable { onPlay(video) }
                        .padding(vertical = 4.dp).testTag("bili_related_${video.bvid}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(video.cover, null, Modifier.width(104.dp).height(60.dp)
                            .clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(video.title, fontSize = 14.sp, maxLines = 2)
                            Text(video.owner + " · " + formatDuration(video.durationSec),
                                Modifier.padding(top = 2.dp), fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                        }
                    }
                }
            }
        }
    }
}

private fun compact(v: Long): String = when {
    v >= 10_000 -> (v / 10_000).toString() + "万"
    else -> v.toString()
}

private fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val m = sec / 60; val s = sec % 60
    return "%d:%02d".format(m, s)
}
