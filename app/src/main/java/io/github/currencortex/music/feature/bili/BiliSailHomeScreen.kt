// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicPullToRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BiliSailHomeChannels = listOf(
    "推荐", "热门", "排行榜", "番剧", "国创", "影视", "直播",
    "动画", "游戏", "科技", "知识", "生活", "美食", "纪录片",
    "稍后再看", "历史记录", "我的收藏",
)

private data class BiliSailHomePayload(
    val videos: List<BiliHubRepository.Video> = emptyList(),
    val bangumi: List<BiliHubRepository.Bangumi> = emptyList(),
    val live: List<BiliHubRepository.LiveRoom> = emptyList(),
)

/**
 * BiliSail-inspired home surface. The information model follows the feed: a channel bar, account
 * gate, responsive video grid, and dedicated bangumi/live adapters instead of the PromX home cards.
 */
@Composable
fun BiliSailHomeScreen(
    container: AppContainer,
    sessionRevision: Int,
    searchQuery: String,
    onSearch: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSearchSubmit: () -> Unit,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit,
    onLive: (BiliHubRepository.LiveRoom) -> Unit,
    onWeb: (String) -> Unit,
    onLogin: () -> Unit,
    navigate: (String) -> Unit,
) {
    val cookie = container.biliSession.cookieHeader()
    val signedIn = container.biliSession.loggedIn
    val profile = container.biliSession.profile
    val bottom = LocalMusicBottomInset.current
    var channel by rememberSaveable { mutableStateOf("推荐") }
    var reload by remember { mutableIntStateOf(0) }
    var payload by remember { mutableStateOf<BiliSailLoad<BiliSailHomePayload>>(BiliSailLoad.Loading) }
    val loading = payload is BiliSailLoad.Loading

    LaunchedEffect(channel, cookie, signedIn, reload, sessionRevision) {
        payload = BiliSailLoad.Loading
        payload = runCatching {
            withContext(Dispatchers.IO) {
                when (channel) {
                    "番剧" -> BiliSailHomePayload(bangumi = BiliHubRepository.bangumi(1, cookie))
                    "国创" -> BiliSailHomePayload(bangumi = BiliHubRepository.bangumi(4, cookie))
                    "影视" -> BiliSailHomePayload(bangumi = BiliHubRepository.bangumi(2, cookie))
                    "直播" -> BiliSailHomePayload(live = BiliHubRepository.live(cookie))
                    "稍后再看" -> BiliSailHomePayload(videos = BiliHubRepository.watchLater(cookie))
                    "历史记录" -> BiliSailHomePayload(videos = BiliHubRepository.history(cookie))
                    "我的收藏" -> {
                        val mid = profile?.mid ?: 0L
                        val folder = if (mid > 0L) {
                            BiliHubRepository.favoriteFolders(cookie, mid).firstOrNull()
                        } else null
                        val items = folder?.let {
                            io.github.currencortex.music.core.alt.BiliAuth.favoriteItems(cookie, it.id, 1, 40)
                        }.orEmpty()
                        BiliSailHomePayload(videos = items.map {
                            BiliHubRepository.Video(
                                bvid = it.bvid,
                                title = it.title,
                                cover = it.cover,
                                uploader = it.author,
                                durationSec = (it.durationMs / 1_000L).toInt(),
                            )
                        })
                    }
                    else -> BiliSailHomePayload(videos = BiliHubRepository.home(channel, cookie))
                }
            }
        }.fold(
            onSuccess = { BiliSailLoad.Ready(it) },
            onFailure = { BiliSailLoad.Failed(it.message ?: "哔哩哔哩内容加载失败") },
        )
    }

    Column(Modifier.fillMaxSize().background(BiliSailCanvas).testTag("bili_sail_home")) {
        BiliSailTopBar(
            title = "哔哩哔哩",
            signedIn = signedIn,
            profileFace = profile?.face.orEmpty(),
            onSearch = onSearch,
            onMessages = { navigate("bili/messages") },
            onAccount = { if (signedIn) navigate("bili/profile") else onLogin() },
            onRefresh = { reload++ },
        )
        MusicPullToRefresh(loading, { reload++ }, Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = when {
                    maxWidth >= 900.dp -> 4
                    maxWidth >= 620.dp -> 3
                    else -> 2
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().testTag("bili_sail_feed"),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottom + 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        BiliSailSearchEntry(searchQuery, onSearch)
                    }
                    item(key = "channels", span = { GridItemSpan(maxLineSpan) }) {
                        BiliSailChannelStrip(BiliSailHomeChannels, channel) { channel = it }
                    }
                    if (!signedIn && channel in setOf("推荐", "稍后再看", "历史记录", "我的收藏")) {
                        item(key = "login", span = { GridItemSpan(maxLineSpan) }) {
                            BiliSailLoginBanner(onLogin)
                        }
                    }
                    when (val state = payload) {
                        BiliSailLoad.Loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                BiliSailLoadingCards(4)
                            }
                        }
                        is BiliSailLoad.Failed -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            BiliSailError(state.message) { reload++ }
                        }
                        is BiliSailLoad.Ready -> {
                            val data = state.value
                            when {
                                data.bangumi.isNotEmpty() -> {
                                    item(key = "bangumi_title", span = { GridItemSpan(maxLineSpan) }) {
                                        BiliSailSectionTitle(
                                            channel,
                                            "${data.bangumi.size} 部 · 正在热播",
                                        )
                                    }
                                    items(data.bangumi, key = { "bangumi_${it.seasonId}" }) { item ->
                                        BiliSailBangumiCard(item) {
                                            onWeb(item.url.ifBlank {
                                                "https://www.bilibili.com/bangumi/play/ss${item.seasonId}"
                                            })
                                        }
                                    }
                                }
                                data.live.isNotEmpty() -> {
                                    item(key = "live_title", span = { GridItemSpan(maxLineSpan) }) {
                                        BiliSailSectionTitle("正在直播", "${data.live.size} 个直播间")
                                    }
                                    items(data.live, key = { "live_${it.roomId}" }) { room ->
                                        BiliSailLiveCard(room) { onLive(room) }
                                    }
                                }
                                data.videos.isNotEmpty() -> {
                                    item(key = "feed_title", span = { GridItemSpan(maxLineSpan) }) {
                                        BiliSailSectionTitle(
                                            if (channel == "推荐") "推荐视频" else channel,
                                            if (channel == "推荐") "根据当前兴趣持续更新" else "${data.videos.size} 条内容",
                                        )
                                    }
                                    items(data.videos, key = { it.bvid }) { video ->
                                        BiliSailVideoCard(
                                            video = video,
                                            onClick = { onPlayVideo(video) },
                                            onCreator = {
                                                if (video.uploaderMid > 0L) onCreator(video.uploaderMid)
                                                else onSearch()
                                            },
                                        )
                                    }
                                }
                                else -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                    BiliSailError(
                                        if (!signedIn && channel in setOf("稍后再看", "历史记录", "我的收藏")) {
                                            "登录哔哩哔哩后读取你的${channel}"
                                        } else "这个分区暂时没有内容",
                                    ) {
                                        if (!signedIn) onLogin() else reload++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
