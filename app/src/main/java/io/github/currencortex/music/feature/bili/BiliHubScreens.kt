// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.MusicSearchBar
import io.github.currencortex.music.ui.component.PromIcons
import io.github.currencortex.music.ui.component.nativeui.GlassActionButton
import io.github.currencortex.music.ui.component.nativeui.GlassIconButton
import io.github.currencortex.music.ui.component.nativeui.SettingsDivider
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import io.github.currencortex.music.ui.component.nativeui.SettingsListHeader
import io.github.currencortex.music.ui.component.nativeui.SettingsPage
import io.github.currencortex.music.ui.component.nativeui.SettingsPreference
import io.github.currencortex.music.ui.component.nativeui.SettingsRootTopBar
import io.github.currencortex.music.ui.component.nativeui.SettingsScrollLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val BiliPink = Color(0xFFFB7299)
private val BiliRoseDeep = Color(0xFFD94B68)
private val BiliBlue = Color(0xFF00AEEC)
private val BiliInk = Color(0xFFF5F6F9)

private sealed interface HubLoad<out T> {
    data object Loading : HubLoad<Nothing>
    data class Ready<T>(val data: T) : HubLoad<T>
    data class Failed(val message: String) : HubLoad<Nothing>
}

private val homeChannels = listOf(
    "推荐", "热门", "排行榜", "番剧", "国创", "影视", "直播",
    "动画", "音乐", "游戏", "科技", "知识", "生活", "美食", "纪录片",
    "放映厅", "专栏", "课程", "稍后再看", "历史记录", "我的收藏",
)

@Composable
fun BiliHomeScreen(
    container: AppContainer,
    sessionRevision: Int,
    searchQuery: String,
    onSearch: () -> Unit,
    onSearchSubmit: () -> Unit,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit,
    onLive: (BiliHubRepository.LiveRoom) -> Unit,
    onWeb: (String) -> Unit,
    onLogin: () -> Unit,
    navigate: (String) -> Unit,
) {
    val account by container.neteaseSessions.state.collectAsStateWithLifecycle()
    val cookie = container.biliSession.cookieHeader()
    val signedIn = container.biliSession.loggedIn
    val bottom = LocalMusicBottomInset.current
    var channel by rememberSaveable { mutableStateOf("推荐") }
    var reload by remember { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Video>>>(HubLoad.Loading) }
    var bangumi by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Bangumi>>>(HubLoad.Loading) }
    var live by remember { mutableStateOf<HubLoad<List<BiliHubRepository.LiveRoom>>>(HubLoad.Loading) }

    LaunchedEffect(channel, cookie, reload, sessionRevision) {
        videos = HubLoad.Loading
        bangumi = HubLoad.Loading
        live = HubLoad.Loading
        withContext(Dispatchers.IO) {
            when (channel) {
                "番剧" -> bangumi = HubLoad.Ready(BiliHubRepository.bangumi(1, cookie))
                "国创" -> bangumi = HubLoad.Ready(BiliHubRepository.bangumi(4, cookie))
                "影视", "放映厅" -> bangumi = HubLoad.Ready(BiliHubRepository.bangumi(2, cookie))
                "直播" -> live = HubLoad.Ready(BiliHubRepository.live(cookie))
                "专栏", "课程" -> videos = HubLoad.Ready(emptyList())
                "稍后再看" -> videos = HubLoad.Ready(BiliHubRepository.watchLater(cookie))
                "历史记录" -> videos = HubLoad.Ready(BiliHubRepository.history(cookie))
                "我的收藏" -> {
                    val mid = container.biliSession.profile?.mid ?: 0L
                    val folder = BiliHubRepository.favoriteFolders(cookie, mid).firstOrNull()
                    val items = folder?.let { BiliAuth.favoriteItems(cookie, it.id, 1, 40) }.orEmpty()
                    videos = HubLoad.Ready(items.map {
                        BiliHubRepository.Video(it.bvid, it.title, it.cover, it.author,
                            durationSec = (it.durationMs / 1000).toInt())
                    })
                }
                else -> videos = HubLoad.Ready(BiliHubRepository.home(channel, cookie))
            }
        }
    }

    SettingsScrollLayout(
        Modifier.fillMaxSize().testTag("bili_home"),
        topBar = {
            SettingsRootTopBar("哔哩哔哩", Modifier.testTag("bili_home_toolbar")) {
                GlassIconButton(onSearch, "搜索哔哩哔哩", Modifier.testTag("bili_home_search")) {
                    Icon(MusicIcons.Search, null, Modifier.size(21.dp), tint = MiuixTheme.colorScheme.onSurface)
                }
                GlassIconButton({ navigate("bili/messages") }, "消息", Modifier.testTag("bili_home_messages")) {
                    Icon(MusicIcons.Comment, null, Modifier.size(21.dp), tint = MiuixTheme.colorScheme.onSurface)
                }
            }
        },
    ) { topInset ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 28.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("search") {
                MusicSearchBar(
                    searchQuery, onSearchSubmit,
                    Modifier.padding(horizontal = 16.dp).testTag("bili_home_search_bar"),
                    placeholder = "搜索视频、番剧或 UP 主", onActivate = onSearch,
                    inputTag = "bili_home_search_field",
                )
            }
            item("channels") {
                BiliChannelStrip(channel) { channel = it }
            }
            if (!signedIn && channel in setOf("推荐", "稍后再看", "历史记录", "我的收藏")) item("login_hint") {
                BiliConnectionCard(
                    "登录后接收更贴近兴趣的推荐、动态与账号内容",
                    onLogin = onLogin,
                )
            }
            if (channel !in setOf("专栏", "课程")) item("hero") {
                BiliHero(channel, videos, reload = { reload++ }, onVideo = onPlayVideo)
            }
            when (channel) {
                "番剧", "国创", "影视", "放映厅" -> when (val state = bangumi) {
                    HubLoad.Loading -> item("bangumi_loading") { BiliLoading("正在整理本周片单") }
                    is HubLoad.Failed -> item("bangumi_error") { BiliError(state.message) { reload++ } }
                    is HubLoad.Ready -> {
                        if (state.data.isEmpty()) item("bangumi_empty") {
                            BiliError("这个分区暂时没有取到内容") { reload++ }
                        }
                        itemsIndexed(state.data.chunked(2), key = { index, _ -> "bg_row_$index" }) { _, row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { item ->
                                    BiliBangumiCard(item, Modifier.weight(1f)) { onWeb(item.url) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                "直播" -> when (val state = live) {
                    HubLoad.Loading -> item("live_loading") { BiliLoading("正在连接直播广场") }
                    is HubLoad.Failed -> item("live_error") { BiliError(state.message) { reload++ } }
                    is HubLoad.Ready -> {
                        item("live_header") {
                            SettingsListHeader("正在直播", Modifier.padding(horizontal = 16.dp)) {
                                Text("${state.data.size} 个房间", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        itemsIndexed(state.data.chunked(2), key = { index, _ -> "live_row_$index" }) { _, row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { room -> BiliLiveCard(room, Modifier.weight(1f)) { onLive(room) } }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                "专栏" -> item("articles_entry") {
                    BiliSectionEntry("哔哩哔哩专栏", "长文章、图文与创作者观点都在这里", MusicIcons.File,
                        onClick = { onWeb("https://www.bilibili.com/read/home") })
                }
                "课程" -> item("courses_entry") {
                    BiliSectionEntry("哔哩哔哩课程", "公开课、付费课程和学习专题", MusicIcons.Play,
                        onClick = { onWeb("https://www.bilibili.com/cheese/") })
                }
                else -> when (val state = videos) {
                    HubLoad.Loading -> item("video_loading") { BiliLoading("正在加载 $channel") }
                    is HubLoad.Failed -> item("video_error") { BiliError(state.message) { reload++ } }
                    is HubLoad.Ready -> {
                        item("video_header") {
                            SettingsListHeader(if (channel == "推荐") "猜你喜欢" else "$channel · 新内容",
                                Modifier.padding(horizontal = 16.dp))
                        }
                        if (state.data.isEmpty()) item("video_empty") {
                            BiliError(if (!signedIn && channel in setOf("推荐", "稍后再看", "历史记录", "我的收藏"))
                                "登录后会读取你的${channel}内容" else "暂时没有取到视频") {
                                if (!signedIn) navigate("bili/login") else reload++
                            }
                        }
                        itemsIndexed(state.data.chunked(2), key = { index, _ -> "video_row_$index" }) { _, row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { video ->
                                    BiliVideoCard(video, Modifier.weight(1f), onClick = { onPlayVideo(video) },
                                        onCreator = { if (video.uploaderMid > 0) onCreator(video.uploaderMid) })
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BiliDynamicScreen(
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
    val bottom = LocalMusicBottomInset.current
    var reload by remember { mutableIntStateOf(0) }
    var dynamics by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Dynamic>>>(HubLoad.Loading) }
    var popular by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Video>>>(HubLoad.Loading) }
    var live by remember { mutableStateOf<HubLoad<List<BiliHubRepository.LiveRoom>>>(HubLoad.Loading) }
    var followedLive by remember { mutableStateOf<List<BiliHubRepository.LiveRoom>>(emptyList()) }

    LaunchedEffect(cookie, signedIn, reload, sessionRevision) {
        dynamics = HubLoad.Loading
        popular = HubLoad.Loading
        live = HubLoad.Loading
        withContext(Dispatchers.IO) {
            dynamics = HubLoad.Ready(if (signedIn) BiliHubRepository.dynamics(cookie) else emptyList())
            popular = HubLoad.Ready(BiliHubRepository.home("热门", cookie))
            live = HubLoad.Ready(BiliHubRepository.live(cookie).take(8))
            followedLive = if (signedIn) BiliHubRepository.followedLive(cookie) else emptyList()
        }
    }

    SettingsScrollLayout(Modifier.fillMaxSize().testTag("bili_dynamic"), topBar = {
        SettingsRootTopBar("动态", Modifier.testTag("bili_dynamic_toolbar")) {
            GlassIconButton({ onWeb("https://t.bilibili.com/") }, "发布动态") {
                Icon(MusicIcons.Add, "发布动态", Modifier.size(21.dp))
            }
            GlassIconButton({ reload++ }, "刷新动态") { Icon(MusicIcons.Update, "刷新", Modifier.size(21.dp)) }
        }
    }) { topInset ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = topInset + 18.dp, end = 16.dp, bottom = 28.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("dynamic_hero") { BiliDiscoveryHero() }
            if (!signedIn) item("login") {
                BiliConnectionCard(
                    "登录哔哩哔哩后，这里只显示你关注的人与追番动态",
                    onLogin = { navigate("bili/login") },
                )
            }
            if (signedIn) {
                item("following_header") { SettingsListHeader("关注动态") }
                when (val state = dynamics) {
                    HubLoad.Loading -> item("following_loading") { BiliLoading("正在同步关注动态") }
                    is HubLoad.Failed -> item("following_error") { BiliError(state.message) { reload++ } }
                    is HubLoad.Ready -> {
                        if (state.data.isEmpty()) item("following_empty") {
                            BiliError("还没有关注动态，可以先去主页关注感兴趣的内容") { reload++ }
                        }
                        items(state.data, key = { it.id }) { dynamic ->
                            BiliDynamicCard(dynamic, onClick = {
                                if (dynamic.bvid.isNotBlank()) {
                                    onPlayVideo(BiliHubRepository.Video(
                                        bvid = dynamic.bvid, title = dynamic.title.ifBlank { dynamic.text },
                                        cover = dynamic.cover, uploader = dynamic.author,
                                    ))
                                }
                            }, onCreator = { if (dynamic.authorMid > 0) onCreator(dynamic.authorMid) })
                        }
                    }
                }
                if (followedLive.isNotEmpty()) {
                    item("followed_live_header") { SettingsListHeader("关注主播正在直播") }
                    item("followed_live_row") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(followedLive, key = { "followed_live_${it.roomId}" }) { room ->
                                BiliLiveCard(room, Modifier.width(168.dp)) { onLive(room) }
                            }
                        }
                    }
                }
            }
            item("live_entry") {
                BiliSectionEntry("直播广场", "游戏、音乐、聊天与赛事正在发生", MusicIcons.Play,
                    onClick = { navigate("bili/live") })
            }
            item("popular_header") { SettingsListHeader("社区正在看") }
            when (val state = popular) {
                HubLoad.Loading -> item("popular_loading") { BiliLoading("正在加载热门视频") }
                is HubLoad.Failed -> item("popular_error") { BiliError(state.message) { reload++ } }
                is HubLoad.Ready -> items(state.data.take(10), key = { "popular_${it.bvid}" }) { video ->
                    BiliWideVideoRow(video, onClick = { onPlayVideo(video) }, onCreator = { onCreator(video.uploaderMid) })
                }
            }
            item("live_header") { SettingsListHeader("热门直播") }
            when (val state = live) {
                HubLoad.Loading -> item("live_loading") { BiliLoading("正在加载直播") }
                is HubLoad.Failed -> item("live_error") { BiliError(state.message) { reload++ } }
                is HubLoad.Ready -> item("live_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.data, key = { "dynamic_live_${it.roomId}" }) { room ->
                            BiliLiveCard(room, Modifier.width(168.dp)) { onLive(room) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BiliMineScreen(
    container: AppContainer,
    sessionRevision: Int,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit,
    navigate: (String) -> Unit,
) {
    val cookie = container.biliSession.cookieHeader()
    val signedIn = container.biliSession.loggedIn
    val bottom = LocalMusicBottomInset.current
    var reload by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf<HubLoad<BiliHubRepository.Account?>>(HubLoad.Loading) }
    var messages by remember { mutableStateOf(BiliHubRepository.MessageSummary(0, 0, 0, 0)) }
    var history by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Video>>>(HubLoad.Loading) }
    var watchLater by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Video>>>(HubLoad.Loading) }
    var folders by remember { mutableStateOf<List<BiliAuth.FavFolder>>(emptyList()) }

    LaunchedEffect(cookie, signedIn, reload, sessionRevision) {
        account = HubLoad.Loading
        history = HubLoad.Loading
        watchLater = HubLoad.Loading
        withContext(Dispatchers.IO) {
            if (signedIn) {
                val overview = BiliHubRepository.account(cookie)
                account = HubLoad.Ready(overview)
                messages = BiliHubRepository.messages(cookie)
                history = HubLoad.Ready(BiliHubRepository.history(cookie))
                watchLater = HubLoad.Ready(BiliHubRepository.watchLater(cookie))
                folders = overview?.let { BiliHubRepository.favoriteFolders(cookie, it.mid) }.orEmpty()
            } else {
                account = HubLoad.Ready(null)
                history = HubLoad.Ready(emptyList())
                watchLater = HubLoad.Ready(emptyList())
                messages = BiliHubRepository.MessageSummary(0, 0, 0, 0)
                folders = emptyList()
            }
        }
    }

    SettingsScrollLayout(Modifier.fillMaxSize().testTag("bili_mine"), topBar = {
        SettingsRootTopBar("我的", Modifier.testTag("bili_mine_toolbar")) {
            GlassIconButton({ reload++ }, "刷新账号资料") { Icon(MusicIcons.Update, "刷新", Modifier.size(21.dp)) }
        }
    }) { topInset ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = topInset + 14.dp, end = 16.dp, bottom = 28.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("account") {
                AnimatedContent(
                    targetState = account,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "bili-account",
                ) { state ->
                    when (state) {
                        HubLoad.Loading -> BiliLoading("正在读取账号资料")
                        is HubLoad.Failed -> BiliError(state.message) { reload++ }
                        is HubLoad.Ready -> state.data?.let { value ->
                            BiliAccountCard(
                                account = value,
                                unread = messages.total,
                                onProfile = { if (value.mid > 0) onCreator(value.mid) },
                                onMessages = { navigate("bili/messages") },
                            )
                        } ?: BiliSignedOutCard(
                            onLogin = { navigate("bili/login") },
                            onExplore = { navigate("bili/login") },
                        )
                    }
                }
            }
            item("shortcuts") {
                SettingsListHeader("我的内容")
                SettingsGroup {
                    BiliPreference("个人主页", "头像、等级、简介与投稿", PromIcons.Person,
                        enabled = signedIn) { if (signedIn) navigate("bili/profile") }
                    SettingsDivider(52.dp)
                    BiliPreference("消息中心", if (messages.total > 0) "${messages.total} 条未读" else "回复、@我、点赞与系统通知",
                        MusicIcons.Comment) { navigate("bili/messages") }
                    SettingsDivider(52.dp)
                    BiliPreference("历史记录", "在哔哩哔哩看过的视频", MusicIcons.Update) { navigate("lib/recent") }
                    SettingsDivider(52.dp)
                    BiliPreference("我的收藏", "默认收藏夹中的视频", MusicIcons.Heart) { navigate("lib/likes") }
                    SettingsDivider(52.dp)
                    BiliPreference("下载", "已离线的视频与缓存", MusicIcons.Download) { navigate("lib/local") }
                }
            }
            if (signedIn) {
                item("social") {
                    SettingsListHeader("账号关系")
                    SettingsGroup {
                        BiliPreference("关注", "我关注的 UP 主", PromIcons.Person) { navigate("bili/social/following") }
                        SettingsDivider(52.dp)
                        BiliPreference("粉丝", "关注我的人", PromIcons.Person) { navigate("bili/social/followers") }
                    }
                }
            }
            item("watch_later_header") { SettingsListHeader("稍后再看") }
            when (val state = watchLater) {
                HubLoad.Loading -> item("watch_loading") { BiliLoading("正在读取稍后再看") }
                is HubLoad.Failed -> item("watch_error") { BiliError(state.message) { reload++ } }
                is HubLoad.Ready -> if (state.data.isEmpty()) {
                    item("watch_empty") { BiliEmpty("暂时没有稍后再看的视频") }
                } else items(state.data.take(6), key = { "watch_${it.bvid}" }) { video ->
                    BiliWideVideoRow(video, onClick = { onPlayVideo(video) }, onCreator = { onCreator(video.uploaderMid) })
                }
            }
            item("folders_header") { SettingsListHeader("收藏夹") }
            if (folders.isEmpty()) item("folders_empty") {
                BiliEmpty(if (signedIn) "还没有新建收藏夹" else "登录后显示你的收藏夹")
            } else item("folders") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(folders, key = { it.id }) { folder ->
                        BiliFolderCard(folder) { navigate("bili/folder/${folder.id}") }
                    }
                }
            }
            item("history_header") { SettingsListHeader("最近观看") }
            when (val state = history) {
                HubLoad.Loading -> item("history_loading") { BiliLoading("正在读取观看历史") }
                is HubLoad.Failed -> item("history_error") { BiliError(state.message) { reload++ } }
                is HubLoad.Ready -> if (state.data.isEmpty()) {
                    item("history_empty") { BiliEmpty(if (signedIn) "最近还没有观看记录" else "登录后同步观看历史") }
                } else items(state.data.take(6), key = { "history_${it.bvid}" }) { video ->
                    BiliWideVideoRow(video, onClick = { onPlayVideo(video) }, onCreator = { onCreator(video.uploaderMid) })
                }
            }
        }
    }
}

@Composable
fun BiliMessagesScreen(container: AppContainer, onBack: () -> Unit, onWeb: (String) -> Unit) {
    val cookie = container.biliSession.cookieHeader()
    var summary by remember { mutableStateOf(BiliHubRepository.MessageSummary(0, 0, 0, 0)) }
    var items by remember { mutableStateOf<List<BiliHubRepository.MessageItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(cookie) {
        withContext(Dispatchers.IO) {
            summary = BiliHubRepository.messages(cookie)
            items = BiliHubRepository.messageItems(cookie)
        }
        loading = false
    }
    SettingsPage("消息中心", "bili_messages", onBack = onBack) {
        item("full_messages") {
            SettingsGroup {
                BiliPreference("私信与完整消息中心", "在应用内打开哔哩哔哩消息网页", MusicIcons.Comment) {
                    onWeb("https://message.bilibili.com/")
                }
            }
        }
        item("summary") {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BiliMessageMetric("回复", summary.replies, Modifier.weight(1f))
                    BiliMessageMetric("@我", summary.ats, Modifier.weight(1f))
                    BiliMessageMetric("点赞", summary.likes, Modifier.weight(1f))
                    BiliMessageMetric("系统", summary.system, Modifier.weight(1f))
                }
            }
        }
        item("hint") { SettingsHint("消息数据来自当前登录的哔哩哔哩账号。") }
        if (loading) item("loading") { BiliLoading("正在同步消息") }
        if (!loading && items.isEmpty()) item("empty") { BiliEmpty("暂时没有新的回复消息") }
        items(items, key = { it.id }) { item ->
            BiliMessageRow(item)
        }
    }
}

@Composable
fun BiliLiveHubScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLive: (BiliHubRepository.LiveRoom) -> Unit,
) {
    val cookie = container.biliSession.cookieHeader()
    val signedIn = container.biliSession.loggedIn
    var loading by remember { mutableStateOf(true) }
    var followed by remember { mutableStateOf<List<BiliHubRepository.LiveRoom>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<BiliHubRepository.LiveRoom>>(emptyList()) }
    LaunchedEffect(cookie, signedIn) {
        withContext(Dispatchers.IO) {
            recommended = BiliHubRepository.live(cookie)
            followed = if (signedIn) BiliHubRepository.followedLive(cookie) else emptyList()
        }
        loading = false
    }
    SettingsPage("直播广场", "bili_live_hub", onBack = onBack) {
        if (loading) item("loading") { BiliLoading("正在连接直播广场") }
        if (followed.isNotEmpty()) {
            item("followed_header") { SettingsListHeader("关注主播正在直播") }
            itemsIndexed(followed.chunked(2), key = { index, _ -> "followed_live_$index" }) { _, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { room -> BiliLiveCard(room, Modifier.weight(1f)) { onLive(room) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item("recommended_header") { SettingsListHeader("推荐直播") }
        itemsIndexed(recommended.chunked(2), key = { index, _ -> "recommended_live_$index" }) { _, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { room -> BiliLiveCard(room, Modifier.weight(1f)) { onLive(room) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun BiliSocialScreen(container: AppContainer, followers: Boolean, onBack: () -> Unit, onCreator: (Long) -> Unit) {
    val cookie = container.biliSession.cookieHeader()
    val mid = container.biliSession.profile?.mid ?: 0L
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<BiliHubRepository.SocialUser>>(emptyList()) }
    LaunchedEffect(mid, followers, cookie) {
        rows = withContext(Dispatchers.IO) { BiliHubRepository.social(mid, followers, cookie) }
        loading = false
    }
    SettingsPage(if (followers) "粉丝" else "关注", if (followers) "bili_followers" else "bili_following", onBack = onBack) {
        if (loading) item("loading") { BiliLoading("正在读取账号关系") }
        if (!loading && rows.isEmpty()) item("empty") { BiliEmpty("暂时没有可显示的账号") }
        items(rows, key = { it.mid }) { user ->
            SettingsGroup {
                SettingsPreference(
                    user.name, user.sign.ifBlank { "哔哩哔哩用户 ${user.mid}" },
                    leading = { BiliAvatar(user.avatar, 42.dp) },
                    trailing = { Text(if (user.following) "已关注" else "关注", fontSize = 13.sp, color = BiliPink) },
                    onClick = { if (user.mid > 0) onCreator(user.mid) },
                )
            }
        }
    }
}

@Composable
fun BiliProfileScreen(
    container: AppContainer,
    mid: Long,
    onBack: () -> Unit,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    navigate: (String) -> Unit,
) {
    val cookie = container.biliSession.cookieHeader()
    var account by remember { mutableStateOf<HubLoad<BiliHubRepository.Account?>>(HubLoad.Loading) }
    var creator by remember { mutableStateOf<BiliPlayback.Creator?>(null) }
    var videos by remember { mutableStateOf<HubLoad<List<BiliHubRepository.Video>>>(HubLoad.Loading) }
    LaunchedEffect(mid, cookie) {
        withContext(Dispatchers.IO) {
            account = HubLoad.Ready(BiliHubRepository.account(cookie).takeIf { it?.mid == mid }
                ?: BiliHubRepository.account(cookie).takeIf { mid == container.biliSession.profile?.mid })
            creator = BiliPlayback.creator(mid, cookie)
            videos = HubLoad.Ready(BiliPlayback.creatorVideos(mid, 1, cookie).map {
                BiliHubRepository.Video(it.bvid, it.title, it.cover, it.owner, durationSec = it.durationSec)
            })
        }
    }
    SettingsPage("个人主页", "bili_profile", onBack = onBack) {
        item("profile") {
            val own = account as? HubLoad.Ready
            val value = own?.data
            val creatorValue = creator
            BiliProfileHero(
                name = value?.name ?: creatorValue?.name ?: "哔哩哔哩用户",
                avatar = value?.face ?: creatorValue?.avatar.orEmpty(),
                sign = value?.sign ?: creatorValue?.sign.orEmpty(),
                level = value?.level ?: 0,
                following = value?.following ?: 0L,
                followers = value?.followers ?: creatorValue?.fans ?: 0L,
            )
        }
        if (mid == container.biliSession.profile?.mid) item("profile_actions") {
            SettingsGroup {
                BiliPreference("我的收藏", "默认收藏夹与收藏内容", MusicIcons.Heart) { navigate("lib/likes") }
                SettingsDivider(52.dp)
                BiliPreference("历史记录", "最近观看过的视频", MusicIcons.Update) { navigate("lib/recent") }
                SettingsDivider(52.dp)
                BiliPreference("关注与粉丝", "管理账号关系和关注列表", PromIcons.Person) { navigate("bili/social/following") }
                SettingsDivider(52.dp)
                BiliPreference("消息中心", "回复、@我、点赞与系统通知", MusicIcons.Comment) { navigate("bili/messages") }
                SettingsDivider(52.dp)
                BiliPreference("离线下载", "视频与音频缓存", MusicIcons.Download) { navigate("lib/local") }
            }
        }
        item("videos_header") { SettingsListHeader("投稿") }
        when (val state = videos) {
            HubLoad.Loading -> item("videos_loading") { BiliLoading("正在读取投稿") }
            is HubLoad.Failed -> item("videos_error") { BiliError(state.message) { } }
            is HubLoad.Ready -> items(state.data, key = { it.bvid }) { video ->
                BiliWideVideoRow(video, onClick = { onPlayVideo(video) }, onCreator = { })
            }
        }
    }
}

@Composable
fun BiliFolderScreen(container: AppContainer, folderId: Long, onBack: () -> Unit, onPlayVideo: (BiliHubRepository.Video) -> Unit) {
    val cookie = container.biliSession.cookieHeader()
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<BiliAuth.FavItem>>(emptyList()) }
    LaunchedEffect(folderId, cookie) {
        items = withContext(Dispatchers.IO) { BiliAuth.favoriteItems(cookie, folderId, 1, 40) }
        loading = false
    }
    SettingsPage("收藏夹", "bili_folder_$folderId", onBack = onBack) {
        if (loading) item("loading") { BiliLoading("正在读取收藏夹") }
        if (!loading && items.isEmpty()) item("empty") { BiliEmpty("这个收藏夹还是空的") }
        items(items, key = { it.bvid }) { item ->
            BiliWideVideoRow(
                BiliHubRepository.Video(item.bvid, item.title, item.cover, item.author,
                    durationSec = (item.durationMs / 1000).toInt()),
                onClick = { onPlayVideo(BiliHubRepository.Video(item.bvid, item.title, item.cover, item.author)) },
                onCreator = { },
            )
        }
    }
}

@Composable
private fun BiliChannelStrip(selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(homeChannels, key = { it }) { channel ->
            val active = channel == selected
            Box(
                Modifier.heightIn(min = 44.dp).clip(CircleShape)
                    .background(if (active) BiliPink else MiuixTheme.colorScheme.onSurface.copy(alpha = .055f))
                    .clickable(role = Role.Tab) { onSelect(channel) }
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
            ) {
                Text(channel, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) Color.White else MiuixTheme.colorScheme.onSurface.copy(alpha = .78f))
            }
        }
    }
}

@Composable
private fun BiliHero(channel: String, state: HubLoad<List<BiliHubRepository.Video>>, reload: () -> Unit,
    onVideo: (BiliHubRepository.Video) -> Unit) {
    val video = (state as? HubLoad.Ready)?.data?.firstOrNull()
    if (video == null) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(176.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(BiliRoseDeep.copy(alpha = .45f), BiliBlue.copy(alpha = .14f), Color.Transparent)))
                .drawBehind {
                    drawCircle(Color.White.copy(alpha = .05f), radius = size.minDimension * .7f, center = Offset(size.width, 0f))
                },
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("哔哩哔哩", fontSize = 13.sp, color = BiliPink, fontWeight = FontWeight.SemiBold)
                Text("发现此刻值得看的内容", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = BiliInk)
                Text("推荐会随账号、观看与互动持续更新", fontSize = 13.sp, color = Color.White.copy(alpha = .58f))
            }
        }
        return
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(224.dp)
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF171A20))
            .clickable(role = Role.Button) { onVideo(video) },
    ) {
        AsyncImage(video.cover, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .92f)),
                startY = 0f, endY = 950f,
            )),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(channel, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(BiliPink, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp))
                video.recommendationReason.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(video.title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${video.uploader}  ·  ${BiliHubRepository.compactCount(video.views)} 播放",
                fontSize = 12.sp, color = Color.White.copy(alpha = .64f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BiliVideoCard(video: BiliHubRepository.Video, modifier: Modifier = Modifier,
    onClick: () -> Unit, onCreator: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(15.dp)).background(Color(0xFF1A1D24))) {
            AsyncImage(video.cover, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video.durationSec > 0) Text(
                BiliHubRepository.durationLabel(video.durationSec),
                Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 10.sp,
            )
            if (video.badge.isNotBlank()) Text(
                video.badge, Modifier.align(Alignment.TopStart).padding(7.dp).background(BiliPink, RoundedCornerShape(7.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp,
            )
        }
        Text(video.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(video.uploader, Modifier.weight(1f).clickable(role = Role.Button, onClick = onCreator),
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${BiliHubRepository.compactCount(video.views)}播放", fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .7f))
        }
    }
}

@Composable
private fun BiliWideVideoRow(video: BiliHubRepository.Video, onClick: () -> Unit, onCreator: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .035f))
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(146.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1D24))) {
            AsyncImage(video.cover, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video.durationSec > 0) Text(
                BiliHubRepository.durationLabel(video.durationSec),
                Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp), color = Color.White, fontSize = 9.sp,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(video.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(video.uploader, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button, onClick = onCreator))
            Text("${BiliHubRepository.compactCount(video.views)} 播放  ·  ${BiliHubRepository.compactCount(video.comments)} 评论",
                fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .7f))
        }
    }
}

@Composable
private fun BiliBangumiCard(item: BiliHubRepository.Bangumi, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF191C22))) {
            AsyncImage(item.cover, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = .58f))
            )))
            if (item.badge.isNotBlank()) Text(item.badge, Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(BiliPink, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                color = Color.White, fontSize = 10.sp)
            item.progress.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.align(Alignment.BottomStart).padding(9.dp), color = Color.White,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(item.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(item.rating, "${BiliHubRepository.compactCount(item.views)}播放").filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BiliLiveCard(room: BiliHubRepository.LiveRoom, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(15.dp)).background(Color(0xFF1A1D24))) {
            AsyncImage(room.cover, room.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(Modifier.align(Alignment.TopStart).padding(7.dp).background(Color.Black.copy(alpha = .68f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(BiliPink))
                Text(BiliHubRepository.compactCount(room.online), color = Color.White, fontSize = 10.sp)
            }
        }
        Text(room.title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${room.anchor}  ·  ${room.area}", fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BiliDynamicCard(item: BiliHubRepository.Dynamic, onClick: () -> Unit, onCreator: () -> Unit) {
    SettingsGroup(Modifier.testTag("bili_dynamic_${item.id}")) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onCreator)) {
                    BiliAvatar(item.avatar, 42.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(item.author, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onCreator))
                    Text(item.timeText.ifBlank { "关注动态" }, fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item.text.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (item.cover.isNotBlank()) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF191C22))) {
                AsyncImage(item.cover, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                item.title.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f))))
                        .padding(12.dp), color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("${BiliHubRepository.compactCount(item.likes)} 赞", fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("${BiliHubRepository.compactCount(item.comments)} 评论", fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun BiliAccountCard(account: BiliHubRepository.Account, unread: Int, onProfile: () -> Unit, onMessages: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(BiliRoseDeep.copy(alpha = .32f), BiliBlue.copy(alpha = .12f), MiuixTheme.colorScheme.surfaceContainer)))
        .clickable(role = Role.Button, onClick = onProfile).padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BiliAvatar(account.face, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("LV${account.level}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(BiliPink, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color.White)
                    }
                    Text(account.sign.ifBlank { "还没有填写个人简介" }, fontSize = 12.sp, lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${account.vipLabel}  ·  UID ${account.mid}", fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .7f))
                }
                if (unread > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(BiliPink))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BiliAccountMetric("关注", account.following)
                BiliAccountMetric("粉丝", account.followers)
                BiliAccountMetric("动态", account.dynamics)
                BiliAccountMetric("硬币", account.coins.toLong())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BiliActionButton("消息 ${if (unread > 0) unread else ""}".trim(), onMessages,
                    Modifier.weight(1f), primary = unread > 0)
                BiliActionButton("查看主页", onProfile, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BiliAccountMetric(label: String, value: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(BiliHubRepository.compactCount(value), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BiliSignedOutCard(onLogin: () -> Unit, onExplore: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(BiliRoseDeep.copy(alpha = .32f), Color.Transparent)))
        .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("连接哔哩哔哩", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("登录后同步头像、等级、关注、收藏、历史和稍后再看。未登录仍可浏览公开内容。",
                fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BiliActionButton("扫码登录", onLogin, Modifier.weight(1f), primary = true)
                BiliActionButton("继续浏览", onExplore, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BiliConnectionCard(text: String, onLogin: () -> Unit) {
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("登录哔哩哔哩", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            BiliActionButton("登录", onLogin, Modifier.width(86.dp), primary = true)
        }
    }
}

@Composable
private fun BiliSectionEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SettingsGroup {
        SettingsPreference(title, subtitle, leading = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(BiliPink.copy(alpha = .14f)),
                contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp), tint = BiliPink) }
        }, trailing = { Icon(MusicIcons.Forward, null, Modifier.size(16.dp)) }, onClick = onClick)
    }
}

@Composable
private fun BiliPreference(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true, onClick: () -> Unit) {
    SettingsPreference(
        title, summary, enabled = enabled, onClick = onClick,
        leading = { Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = .3f)) },
        trailing = { Icon(MusicIcons.Forward, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
    )
}

@Composable
private fun BiliFolderCard(folder: BiliAuth.FavFolder, onClick: () -> Unit) {
    Column(Modifier.width(154.dp).clickable(role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1D24))) {
            AsyncImage(folder.cover, folder.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("${folder.count} 个视频", Modifier.align(Alignment.BottomEnd).padding(7.dp)
                .background(Color.Black.copy(alpha = .65f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color.White, fontSize = 10.sp)
        }
        Text(folder.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BiliMessageMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (value > 0) value.toString() else "0", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = if (value > 0) BiliPink else MiuixTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BiliMessageRow(item: BiliHubRepository.MessageItem) {
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BiliAvatar(item.avatar, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.timeText, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                item.summary.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(item.category, fontSize = 10.sp, color = BiliPink)
            }
        }
    }
}

@Composable
private fun BiliProfileHero(name: String, avatar: String, sign: String, level: Int, following: Long, followers: Long) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(BiliRoseDeep.copy(alpha = .32f), BiliBlue.copy(alpha = .10f), MiuixTheme.colorScheme.surfaceContainer)))
        .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BiliAvatar(avatar, 70.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (level > 0) Text("LV$level", fontSize = 10.sp, color = Color.White,
                            modifier = Modifier.background(BiliPink, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                    Text(sign.ifBlank { "哔哩哔哩 UP 主" }, fontSize = 12.sp, lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                BiliAccountMetric("关注", following)
                BiliAccountMetric("粉丝", followers)
            }
        }
    }
}

@Composable
private fun BiliDiscoveryHero() {
    Box(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(BiliRoseDeep.copy(alpha = .42f), BiliBlue.copy(alpha = .16f), Color.Transparent)))
        .drawBehind { drawCircle(Color.White.copy(alpha = .055f), radius = size.minDimension * .9f, center = Offset(0f, size.height)) },
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("社区动态", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BiliPink)
            Text("关注的人，正在发生的事", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("视频、直播与创作者更新集中在同一条时间线", fontSize = 12.sp, color = Color.White.copy(alpha = .58f))
        }
    }
}

@Composable
private fun BiliAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)),
        contentAlignment = Alignment.Center) {
        if (url.isBlank()) Icon(PromIcons.Person, null, Modifier.size(size * .52f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun BiliLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), size = 20.dp, strokeWidth = 2.dp)
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BiliActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape)
            .background(if (primary) BiliPink else colors.onSurface.copy(alpha = .065f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else colors.onSurface,
            fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BiliError(message: String, onRetry: () -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("内容加载失败", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            GlassActionButton("重试", onRetry, Modifier.width(96.dp), fillWidth = false)
        }
    }
}

@Composable
private fun BiliEmpty(message: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(18.dp))
        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .03f)).padding(18.dp),
        contentAlignment = Alignment.Center) {
        Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
