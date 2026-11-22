package io.github.currencortex.music.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.currencortex.music.R
import io.github.currencortex.music.core.media.PlayerState
import io.github.currencortex.music.data.library.Playlist
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.feature.library.LibraryHomeState
import io.github.currencortex.music.ui.component.MusicCover
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.MusicSearchBar
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.promSourceSearchHint
import io.github.currencortex.music.ui.component.nativeui.ElasticPress
import io.github.currencortex.music.ui.component.nativeui.GlassActionButton
import io.github.currencortex.music.ui.component.nativeui.MusicArtworkCard
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import io.github.currencortex.music.ui.component.nativeui.SettingsListHeader
import io.github.currencortex.music.ui.component.nativeui.ElasticLazyRow
import io.github.currencortex.music.ui.component.LoadingArtworkCards
import io.github.currencortex.music.ui.component.LoadingSongList
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AltSourceTheme(
    val accent: Color,
    val icon: Int,
    val heroTitle: String,
    val heroSubtitle: String,
    val chartTitle: String,
    val recentTitle: String,
    val playlistsTitle: String,
)

private fun themeFor(source: MusicSource) = when (source) {
    MusicSource.QQ_MUSIC -> AltSourceTheme(
        accent = Color(0xFF31C27C), icon = R.drawable.brand_qqmusic_official,
        heroTitle = "QQ 每日推荐", heroSubtitle = "结合你的播放、收藏与近期偏好，整理今天适合你的歌",
        chartTitle = "每日推荐", recentTitle = "最近常听", playlistsTitle = "我的 QQ 歌单",
    )
    else -> AltSourceTheme(
        accent = Color(0xFFFB7299), icon = R.drawable.brand_bilibili,
        heroTitle = "B站推荐", heroSubtitle = "从你的推荐流和音乐区，接住正在流行的内容",
        chartTitle = "B站正在流行", recentTitle = "观看历史", playlistsTitle = "我的收藏夹",
    )
}

/** QQ/B站 share the app's information architecture but get source-specific discovery and actions. */
@Composable internal fun AltSourceHomeContent(
    source: MusicSource,
    home: LibraryHomeState,
    playback: PlayerState,
    searchQuery: String,
    hideSearch: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    connected: Boolean,
    onSearchBounds: (Rect) -> Unit,
    onSearchSubmit: () -> Unit,
    onActivateSearch: () -> Unit,
    navigate: (String) -> Unit,
    play: (List<Song>, Int) -> Unit,
    onSongPreview: (Song, Rect) -> Unit,
    onPlaylistPreview: (Playlist, Rect) -> Unit,
    onScrolledChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val visual = themeFor(source)
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollBackward }
            .distinctUntilChanged()
            .collect(onScrolledChanged)
    }
    val request = io.github.currencortex.music.ui.component.LocalRoomSongRequest.current
    val loading = home.loading && home.pendingSections.isNotEmpty()
    val error = home.errors["每日推荐"] ?: home.errors["最近播放"] ?: home.errors["我的歌单"]
    fun playAt(songs: List<Song>, index: Int) {
        val song = songs.getOrNull(index) ?: return
        if (request != null) request.request(song)
        else if (playback.song?.id == song.id && playback.showPause) play(songs, index)
        else play(songs, index)
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("alt_source_home_${source.name.lowercase()}"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = topInset + 8.dp, end = 16.dp, bottom = 28.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "search") {
            MusicSearchBar(
                searchQuery, onSearchSubmit,
                Modifier.testTag("open_home_search").graphicsLayer { alpha = if (hideSearch) 0f else 1f }
                    .onGloballyPositioned { onSearchBounds(it.boundsInRoot()) },
                placeholder = promSourceSearchHint(source), onActivate = onActivateSearch,
                inputTag = "home_search_field",
            )
        }
        item(key = "source_hero") {
            SourceHeroCard(
                visual, source, home.daily, loading,
                connected = connected,
                canPlay = request == null && home.daily.isNotEmpty(),
                onPlay = { playAt(home.daily, 0) },
                onOpen = { navigate("lib/daily") },
                onLogin = { navigate(if (source == MusicSource.QQ_MUSIC) "qq/login" else "bili/login") },
            )
        }
        item(key = "shortcuts") { SourceShortcuts(source, navigate) }
        if (error != null) item(key = "retry") {
            MusicTextAction("部分内容加载失败，重新加载", onRetry, Modifier.testTag("alt_home_retry"), !loading)
        }

        item(key = "for_you_header") { SettingsListHeader(if (source == MusicSource.QQ_MUSIC) "猜你喜欢" else "继续发现") }
        if (loading && home.forYou.isEmpty()) item(key = "for_you_loading") {
            LoadingArtworkCards(148.dp)
        }
        if (home.forYou.isNotEmpty()) item(key = "for_you") {
            ElasticLazyRow(edgeToEdgeInset = 16.dp) {
                itemsIndexed(home.forYou.take(10), key = { _, song -> "alt-${song.id}" }) { index, song ->
                    if (source == MusicSource.BILIBILI) AltVideoCard(
                        song, visual.accent, { playAt(home.forYou, index) }, { bounds -> onSongPreview(song, bounds) },
                    ) else MusicArtworkCard(
                            cover = song.cover, title = song.name,
                            subtitle = song.artists.ifBlank { "QQ 音乐" },
                            onClick = { playAt(home.forYou, index) },
                            modifier = Modifier.width(148.dp).testTag("alt_home_foryou_${song.id}"),
                            onLongClick = { bounds -> onSongPreview(song, bounds) },
                        )
                }
            }
        }

        item(key = "chart_header") {
            SettingsListHeader(visual.chartTitle) {
                GlassActionButton("全部", { navigate("lib/daily") }, fillWidth = false, minHeight = 36.dp)
            }
        }
        if (loading && home.daily.isEmpty()) item(key = "chart_loading") { LoadingSongList(3) }
        itemsIndexed(home.daily.take(8), key = { _, song -> "daily-${song.id}" }) { index, song ->
            AltRankedRow(
                rank = index + 1, song = song, accent = visual.accent,
                playing = playback.song?.id == song.id && playback.showPause,
                onPlay = { playAt(home.daily, index) },
                onPreview = { bounds -> onSongPreview(song, bounds) },
            )
        }
        if (!loading && home.daily.isEmpty() && error == null) item(key = "chart_empty") {
            SourceEmptyState(
                title = if (source == MusicSource.QQ_MUSIC) "每日推荐暂时为空" else "还没有拿到 B站 推荐",
                detail = if (source == MusicSource.QQ_MUSIC) "稍后刷新，或直接搜索一首歌" else "登录 B站 后会优先使用你的推荐流",
                action = "重新加载", onAction = onRetry,
            )
        }

        item(key = "recent_header") { SettingsListHeader(visual.recentTitle) { GlassActionButton("全部", { navigate("lib/recent") }, fillWidth = false, minHeight = 36.dp) } }
        if (home.recent.isNotEmpty()) itemsIndexed(home.recent.take(4), key = { _, song -> "recent-${song.id}" }) { index, song ->
            CompactSourceSong(
                song, visual.accent, playback.song?.id == song.id && playback.showPause,
                { playAt(home.recent, index) }, { bounds -> onSongPreview(song, bounds) },
            )
        } else if (!loading) item(key = "recent_empty") {
            SettingsHint(
                if (source == MusicSource.QQ_MUSIC) "在 PromX 播放过的 QQ 音乐，会出现在这里"
                else if (home.errors["最近播放"] != null) "B站 历史读取失败，登录后可重新同步"
                else "播放 B站 内容后会同步观看历史",
            )
        }

        item(key = "playlists_header") {
            SettingsListHeader(visual.playlistsTitle) {
                GlassActionButton("管理", { navigate("lib/playlists") }, fillWidth = false, minHeight = 36.dp)
            }
        }
        if (home.playlists.isNotEmpty()) item(key = "playlists") {
            ElasticLazyRow(edgeToEdgeInset = 16.dp) {
                itemsIndexed(home.playlists, key = { _, playlist -> "playlist-${playlist.id}" }) { _, playlist ->
                    MusicArtworkCard(
                        cover = playlist.cover.ifBlank { playlist.songs.firstOrNull()?.cover.orEmpty() },
                        title = playlist.name,
                        subtitle = "${playlist.count.takeIf { it > 0 } ?: playlist.songs.size} 首",
                        onClick = { navigate("lib/playlist/${playlist.id}") },
                        modifier = Modifier.width(154.dp).testTag("alt_home_playlist_${playlist.id}"),
                        onLongClick = { bounds -> onPlaylistPreview(playlist, bounds) },
                    )
                }
            }
        } else if (!loading) item(key = "playlists_empty") {
            SourceEmptyState(
                title = if (source == MusicSource.QQ_MUSIC) "连接后同步你的 QQ 歌单" else "连接后同步 B站 收藏夹",
                detail = "账号凭据只保存在本机，公开推荐和搜索无需登录",
                action = if (source == MusicSource.QQ_MUSIC) "连接 QQ 音乐" else "连接哔哩哔哩",
                onAction = { navigate(if (source == MusicSource.QQ_MUSIC) "qq/login" else "bili/login") },
            )
        }
        item(key = "refresh") {
            MusicTextAction(if (loading) "正在同步音乐来源…" else "刷新 ${if (source == MusicSource.QQ_MUSIC) "QQ 音乐" else "B站"} 内容",
                onRetry, Modifier.fillMaxWidth().testTag("alt_home_refresh"), !loading)
        }
    }
}

@Composable
private fun SourceHeroCard(
    visual: AltSourceTheme, source: MusicSource, songs: List<Song>, loading: Boolean,
    connected: Boolean, canPlay: Boolean, onPlay: () -> Unit, onOpen: () -> Unit, onLogin: () -> Unit,
) {
    val lead = songs.firstOrNull()
    val shape = RoundedCornerShape(22.dp)
    val colors = MiuixTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(colors.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(visual.icon), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(visual.heroTitle, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(visual.heroSubtitle, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurface.copy(alpha = .58f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.clip(CircleShape).background(if (connected) visual.accent.copy(alpha = .18f) else colors.onSurface.copy(alpha = .06f))
                .clickable(role = Role.Button, onClick = onLogin).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(if (connected) "已连接" else "连接", fontSize = 11.sp, color = if (connected) visual.accent else colors.onSurface.copy(alpha = .62f))
            }
        }
        if (source == MusicSource.BILIBILI) {
            MusicCover(lead?.cover.orEmpty(), Modifier.fillMaxWidth().aspectRatio(16f / 9f), 640, cornerRadius = 18.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (lead != null) lead.name else if (loading) "正在整理今日内容…" else "今天的推荐还没到",
                        fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(lead?.artists.orEmpty().ifBlank { if (loading) "正在连接当前音乐来源" else "搜索或刷新试试" },
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = .55f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroPill("播放", visual.accent, canPlay, onPlay, primary = true)
                    HeroPill("全部", colors.onSurface.copy(alpha = .66f), songs.isNotEmpty(), onOpen)
                }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(lead?.cover.orEmpty(), Modifier.size(112.dp), 420, cornerRadius = 18.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (lead != null) lead.name else if (loading) "正在整理今日内容…" else "今天的推荐还没到",
                    fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(lead?.artists.orEmpty().ifBlank { if (loading) "正在连接当前音乐来源" else "搜索或刷新试试" },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = .55f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 5.dp)) {
                    HeroPill("播放", visual.accent, canPlay, onPlay, primary = true)
                    HeroPill("全部", colors.onSurface.copy(alpha = .66f), songs.isNotEmpty(), onOpen)
                }
            }
        }
    }
}

@Composable
private fun AltVideoCard(song: Song, accent: Color, onPlay: () -> Unit, onPreview: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    ElasticPress(Modifier.width(238.dp).onGloballyPositioned { bounds = it.boundsInRoot() }
        .combinedClickable(role = Role.Button, onClick = onPlay, onLongClickLabel = "预览内容", onLongClick = { onPreview(bounds) })) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            MusicCover(song.cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f), 480, cornerRadius = 12.dp)
            Text(song.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(song.artists.ifBlank { "哔哩哔哩" }, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .52f))
                Icon(MusicIcons.Play, null, Modifier.size(16.dp), tint = accent)
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, accent: Color, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    val fill = if (primary) accent else MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)
    ElasticPress(Modifier.clip(CircleShape).background(fill).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 15.dp, vertical = 8.dp), enabled = enabled) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (primary) Color.White else MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) .78f else .35f))
    }
}

@Composable
private fun SourceShortcuts(source: MusicSource, navigate: (String) -> Unit) {
    val items = if (source == MusicSource.QQ_MUSIC) listOf(
        Triple("每日推荐", "lib/daily", MusicIcons.List),
        Triple("我喜欢", "lib/likes", MusicIcons.Heart),
        Triple("歌单", "lib/playlists", MusicIcons.Folder),
        Triple("最近", "lib/recent", MusicIcons.Update),
    ) else listOf(
        Triple("推荐流", "lib/foryou", MusicIcons.Play),
        Triple("收藏夹", "lib/playlists", MusicIcons.Folder),
        Triple("历史", "lib/recent", MusicIcons.Update),
        Triple("B站缓存", "lib/local", MusicIcons.Download),
    )
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            items.forEach { (label, route, icon) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { navigate(route) }
                    .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
                    Text(label, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AltRankedRow(rank: Int, song: Song, accent: Color, playing: Boolean, onPlay: () -> Unit, onPreview: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(role = Role.Button, onClick = onPlay, onLongClickLabel = "预览歌曲", onLongClick = { onPreview(bounds) })
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", Modifier.width(28.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (rank <= 3) accent else MiuixTheme.colorScheme.onSurface.copy(alpha = .35f))
        MusicCover(song.cover, Modifier.size(48.dp), 180, cornerRadius = 13.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artists.ifBlank { song.album }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .52f))
        }
        Icon(if (playing) MusicIcons.Pause else MusicIcons.Play, if (playing) "暂停" else "播放", Modifier.size(24.dp), tint = accent)
    }
}

@Composable
private fun CompactSourceSong(song: Song, accent: Color, playing: Boolean, onPlay: () -> Unit, onPreview: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
        .combinedClickable(role = Role.Button, onClick = onPlay, onLongClickLabel = "预览歌曲", onLongClick = { onPreview(bounds) })
        .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicCover(song.cover, Modifier.size(44.dp), 180, cornerRadius = 12.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artists, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        Icon(if (playing) MusicIcons.Pause else MusicIcons.Play, null, Modifier.size(21.dp), tint = accent.copy(alpha = .85f))
    }
}

@Composable
private fun SourceEmptyState(title: String, detail: String, action: String, onAction: () -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .52f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            GlassActionButton(action, onAction, fillWidth = false, minHeight = 38.dp)
        }
    }
}
