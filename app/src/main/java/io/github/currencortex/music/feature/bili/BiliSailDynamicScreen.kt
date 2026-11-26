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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.MusicPullToRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

private data class BiliSailDynamicPayload(
    val dynamics: List<BiliHubRepository.Dynamic> = emptyList(),
    val videos: List<BiliHubRepository.Video> = emptyList(),
    val live: List<BiliHubRepository.LiveRoom> = emptyList(),
)

private enum class BiliSailDynamicTab(val label: String) {
    DYNAMIC("动态"),
    VIDEO("视频动态"),
    POPULAR("社区热门"),
    LIVE("直播"),
}

/**
 * BiliSail-style dynamic workspace. Rich posts and video posts are separate views, matching the
 * upstream navigation model instead of mixing social entries into the PromX music feed.
 */
@Composable
fun BiliSailDynamicScreen(
    container: AppContainer,
    sessionRevision: Int,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit,
    onLive: (BiliHubRepository.LiveRoom) -> Unit,
    onWeb: (String) -> Unit,
    navigate: (String) -> Unit,
) {
    val cookie = container.biliSession.cookieHeader()
    val signedIn = container.biliSession.loggedIn
    val profile = container.biliSession.profile
    val bottom = LocalMusicBottomInset.current
    var tab by rememberSaveable { mutableStateOf(BiliSailDynamicTab.DYNAMIC) }
    var author by remember { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<BiliSailLoad<BiliSailDynamicPayload>>(BiliSailLoad.Loading) }
    val loading = state is BiliSailLoad.Loading

    LaunchedEffect(cookie, signedIn, reload, sessionRevision) {
        state = BiliSailLoad.Loading
        state = runCatching {
            withContext(Dispatchers.IO) {
                BiliSailDynamicPayload(
                    dynamics = if (signedIn) BiliHubRepository.dynamics(cookie) else emptyList(),
                    videos = BiliHubRepository.home("热门", cookie),
                    live = BiliHubRepository.live(cookie).take(12),
                )
            }
        }.fold(
            onSuccess = { BiliSailLoad.Ready(it) },
            onFailure = { BiliSailLoad.Failed(it.message ?: "动态加载失败") },
        )
    }

    Column(Modifier.fillMaxSize().background(BiliSailCanvas).testTag("bili_sail_dynamic")) {
        BiliSailTopBar(
            title = "动态",
            signedIn = signedIn,
            profileFace = profile?.face.orEmpty(),
            onMessages = { navigate("bili/messages") },
            onAccount = { if (signedIn) navigate("bili/profile") else navigate("bili/login") },
            onRefresh = { reload++ },
        )
        BiliSailDynamicTabs(tab) { tab = it; author = null }
        MusicPullToRefresh(loading, { reload++ }, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottom + 28.dp),
            ) {
                if (!signedIn && tab == BiliSailDynamicTab.DYNAMIC) {
                    item(key = "login") {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            BiliSailLoginBanner { navigate("bili/login") }
                        }
                    }
                }
                when (val result = state) {
                    BiliSailLoad.Loading -> item(key = "loading") {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            BiliSailLoadingCards(3)
                        }
                    }
                    is BiliSailLoad.Failed -> item(key = "error") {
                        BiliSailError(result.message) { reload++ }
                    }
                    is BiliSailLoad.Ready -> when (tab) {
                        BiliSailDynamicTab.DYNAMIC -> {
                            val authors = result.value.dynamics
                                .filter { it.authorMid > 0L }
                                .distinctBy { it.authorMid }
                            if (signedIn && authors.isNotEmpty()) {
                                item(key = "authors") {
                                    BiliSailAuthorFilter(authors, author, onSelect = { author = it })
                                }
                            }
                            val visible = result.value.dynamics.filter { author == null || it.authorMid == author }
                            if (signedIn && visible.isEmpty()) {
                                item(key = "empty") {
                                    BiliSailError("关注动态暂时为空，可以先去首页关注感兴趣的 UP 主") { reload++ }
                                }
                            }
                            items(visible, key = { it.id }) { dynamic ->
                                BiliSailDynamicPost(
                                    dynamic = dynamic,
                                    onCreator = { if (dynamic.authorMid > 0L) onCreator(dynamic.authorMid) },
                                    onVideo = {
                                        if (dynamic.bvid.isNotBlank()) {
                                            onPlayVideo(BiliHubRepository.Video(
                                                bvid = dynamic.bvid,
                                                title = dynamic.title.ifBlank { dynamic.text },
                                                cover = dynamic.cover,
                                                uploader = dynamic.author,
                                                uploaderMid = dynamic.authorMid,
                                                comments = dynamic.comments,
                                                likes = dynamic.likes,
                                            ))
                                        } else if (dynamic.text.isNotBlank()) {
                                            onWeb("https://t.bilibili.com/${dynamic.id}")
                                        }
                                    },
                                )
                            }
                        }
                        BiliSailDynamicTab.VIDEO, BiliSailDynamicTab.POPULAR -> {
                            item(key = "video_title") {
                                BiliSailSectionTitle(
                                    if (tab == BiliSailDynamicTab.VIDEO) "关注 UP 主的视频" else "社区正在看",
                                    "${result.value.videos.size} 条",
                                )
                            }
                            items(result.value.videos, key = { "dynamic_video_${it.bvid}" }) { video ->
                                BiliSailVideoListCard(
                                    video = video,
                                    onClick = { onPlayVideo(video) },
                                    onCreator = { if (video.uploaderMid > 0L) onCreator(video.uploaderMid) },
                                )
                            }
                        }
                        BiliSailDynamicTab.LIVE -> {
                            item(key = "live_title") { BiliSailSectionTitle("直播广场", "${result.value.live.size} 个房间") }
                            items(result.value.live, key = { "dynamic_live_${it.roomId}" }) { room ->
                                BiliSailLiveListRow(room) { onLive(room) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiliSailDynamicTabs(
    selected: BiliSailDynamicTab,
    onSelected: (BiliSailDynamicTab) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(BiliSailDynamicTab.entries, key = { it.name }) { tab ->
            val active = tab == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (active) BiliSailPink else BiliSailSurface)
                    .clickable(role = Role.Tab) { onSelected(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("bili_sail_dynamic_${tab.name.lowercase()}"),
            ) {
                Text(tab.label, color = if (active) Color.White else BiliSailMuted,
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun BiliSailAuthorFilter(
    authors: List<BiliHubRepository.Dynamic>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            BiliSailAuthorChip("全部关注", null, selected == null, onClick = { onSelect(null) })
        }
        items(authors, key = { "author_${it.authorMid}" }) { author ->
            BiliSailAuthorChip(author.author, author.avatar, selected == author.authorMid) {
                onSelect(author.authorMid)
            }
        }
    }
}

@Composable
private fun BiliSailAuthorChip(label: String, avatar: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (selected) BiliSailPink else BiliSailSurface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!avatar.isNullOrBlank()) {
            AsyncImage(avatar, null, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) Color.White else BiliSailMuted, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
private fun BiliSailDynamicPost(
    dynamic: BiliHubRepository.Dynamic,
    onCreator: () -> Unit,
    onVideo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(BiliSailSurface), contentAlignment = Alignment.Center) {
                if (dynamic.avatar.isNotBlank()) AsyncImage(
                    dynamic.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(MusicIcons.Account, null, Modifier.size(20.dp), tint = BiliSailMuted)
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(dynamic.author.ifBlank { "哔哩哔哩用户" },
                    Modifier.clickable(role = Role.Button, onClick = onCreator),
                    color = BiliSailText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(dynamic.timeText.ifBlank { "刚刚" }, color = BiliSailMuted, fontSize = 10.sp)
            }
            Icon(MusicIcons.More, "更多", Modifier.size(20.dp), tint = BiliSailMuted)
        }
        if (dynamic.text.isNotBlank()) {
            Text(dynamic.text, Modifier.padding(top = 10.dp), color = BiliSailText, fontSize = 13.sp,
                lineHeight = 19.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
        if (dynamic.cover.isNotBlank() || dynamic.bvid.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                    .background(BiliSailSurface).clickable(role = Role.Button, onClick = onVideo),
            ) {
                if (dynamic.cover.isNotBlank()) {
                    AsyncImage(
                        dynamic.cover,
                        dynamic.title,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (dynamic.title.isNotBlank()) Text(
                    dynamic.title, Modifier.padding(10.dp), color = BiliSailText,
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (dynamic.comments > 0L) Text(BiliHubRepository.compactCount(dynamic.comments) + " 评论", color = BiliSailMuted, fontSize = 10.sp)
            if (dynamic.likes > 0L) Text(BiliHubRepository.compactCount(dynamic.likes) + " 赞", color = BiliSailMuted, fontSize = 10.sp)
            Text("分享", color = BiliSailMuted, fontSize = 10.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(0.5.dp).background(BiliSailDivider))
    }
}

@Composable
private fun BiliSailLiveListRow(room: BiliHubRepository.LiveRoom, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.width(154.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)).background(BiliSailSurface)) {
            AsyncImage(room.cover, room.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("LIVE", Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(3.dp))
                .background(BiliSailPink).padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(room.title, color = BiliSailText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(room.anchor, color = BiliSailMuted, fontSize = 10.5.sp, maxLines = 1)
            Text(
                listOf(room.area, if (room.online > 0) BiliHubRepository.compactCount(room.online) + " 人气" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                color = BiliSailMuted.copy(alpha = .76f), fontSize = 9.5.sp, maxLines = 1,
            )
        }
    }
}
