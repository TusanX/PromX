// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.sail

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.feature.bili.BiliHubRepository
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicIcons
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import java.util.Locale

private val SailBackground = Color(0xFF101116)
private val SailSurface = Color(0xFF1B1C22)
private val SailSurfaceRaised = Color(0xFF23242B)
private val SailPrimary = Color(0xFFFB7299)
private val SailBlue = Color(0xFF00AEEC)
private val SailText = Color(0xFFF4F5F7)
private val SailMuted = Color(0xFF9CA0AA)

private val defaultSuggestions = listOf(
    "每日必看", "音乐现场", "知识区", "科技数码", "人文历史",
    "纪录片", "游戏实况", "番剧推荐", "电影解说", "高能剪辑",
)

private object SearchHistoryStore {
    private const val FILE = "bili_sail_search"
    private const val KEY = "history"
    private const val SEPARATOR = "\u001F"
    private const val LIMIT = 18

    fun load(context: Context): List<String> = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY, "")
        .orEmpty()
        .split(SEPARATOR)
        .filter { it.isNotBlank() }

    fun remember(context: Context, query: String): List<String> {
        val next = (listOf(query) + load(context).filterNot { it.equals(query, ignoreCase = true) })
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(LIMIT)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, next.joinToString(SEPARATOR)).apply()
        return next
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** Standalone BiliSail-style search page. It never reads PromX search state or result rows. */
@Composable
fun BiliSailSearchScreen(
    container: AppContainer,
    initialQuery: String = "",
    onBack: () -> Unit,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit = {},
    onWeb: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val cookie = remember(container) { container.biliSession.cookieHeader() }
    val bottomInset = LocalMusicBottomInset.current
    val history = remember { mutableStateListOf<String>().apply { addAll(SearchHistoryStore.load(context)) } }

    var input by rememberSaveable(initialQuery, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialQuery))
    }
    var submitted by rememberSaveable { mutableStateOf(initialQuery.trim()) }
    var category by rememberSaveable { mutableStateOf(BiliSailSearchCategory.ALL) }
    var retry by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var result by remember { mutableStateOf(BiliSailSearchBundle()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String = input.text) {
        val query = raw.trim()
        if (query.isEmpty()) {
            focusRequester.requestFocus()
            keyboard?.show()
            return
        }
        input = TextFieldValue(query, selection = androidx.compose.ui.text.TextRange(query.length))
        submitted = query
        page = 1
        error = null
        history.clear()
        history.addAll(SearchHistoryStore.remember(context, query))
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun chooseSuggestion(query: String) = submit(query)

    LaunchedEffect(submitted, category, retry) {
        if (submitted.isBlank()) return@LaunchedEffect
        loading = true
        loadingMore = false
        error = null
        val loaded = runCatching {
            BiliSailSearchRepository.search(submitted, category, 1, cookie)
        }
        result = loaded.getOrDefault(BiliSailSearchBundle())
        error = loaded.exceptionOrNull()?.let { "搜索暂时不可用，请检查网络后重试" }
        page = 1
        loading = false
    }

    fun loadMore() {
        if (loadingMore || loading || page >= result.videoPages) return
        loadingMore = true
        scope.launch {
            val nextPage = page + 1
            val more = runCatching {
                BiliSailSearchRepository.search(submitted, category, nextPage, cookie)
            }.getOrNull()
            if (more != null) {
                result = result.copy(
                    videos = (result.videos + more.videos).distinctBy { it.bvid },
                    videoPages = more.videoPages,
                    videoTotal = more.videoTotal,
                )
                page = nextPage
            }
            loadingMore = false
        }
    }

    BackHandler {
        if (submitted.isNotBlank()) {
            submitted = ""
            input = TextFieldValue("")
            result = BiliSailSearchBundle()
            error = null
        } else onBack()
    }

    Column(Modifier.fillMaxSize().background(SailBackground).testTag("bili_sail_search")) {
        SailSearchHeader(
            input = input,
            onInputChange = { input = it },
            onSubmit = { submit() },
            onBack = {
                if (submitted.isNotBlank()) {
                    submitted = ""
                    input = TextFieldValue("")
                    result = BiliSailSearchBundle()
                } else onBack()
            },
            focusRequester = focusRequester,
        )

        if (submitted.isBlank()) {
            SearchDiscovery(
                history = history,
                onSearch = ::chooseSuggestion,
                onClearHistory = {
                    history.clear()
                    SearchHistoryStore.clear(context)
                },
                modifier = Modifier.weight(1f),
                bottomInset = bottomInset,
            )
            return@Column
        }

        SailCategoryBar(
            selected = category,
            counts = mapOf(
                BiliSailSearchCategory.VIDEO to result.videoTotal,
                BiliSailSearchCategory.USER to result.users.size,
                BiliSailSearchCategory.BANGUMI to result.media.size,
            ),
            onSelect = { category = it },
        )

        when {
            loading -> SearchCenteredState(
                "正在搜索哔哩哔哩",
                modifier = Modifier.weight(1f),
                showProgress = true,
            )
            error != null -> SearchCenteredState(
                error.orEmpty(),
                action = "重新搜索",
                onAction = { retry++ },
                modifier = Modifier.weight(1f),
            )
            result.videos.isEmpty() && result.users.isEmpty() && result.media.isEmpty() -> {
                SearchCenteredState(
                    "没有找到“$submitted”相关内容\n换个关键词试试",
                    modifier = Modifier.weight(1f),
                )
            }
            else -> SearchResultsGrid(
                result = result,
                category = category,
                loadingMore = loadingMore,
                loadMore = ::loadMore,
                onPlayVideo = onPlayVideo,
                onCreator = onCreator,
                onWeb = onWeb,
                modifier = Modifier.weight(1f),
                bottomInset = bottomInset,
            )
        }
    }
}

@Composable
private fun SailSearchHeader(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MusicIcons.Back, null, Modifier.size(22.dp), tint = SailText)
        }
        Row(
            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                .background(SailSurface).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MusicIcons.Search, null, Modifier.size(19.dp), tint = SailMuted)
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp)
                    .focusRequester(focusRequester).testTag("bili_sail_search_field"),
                singleLine = true,
                textStyle = TextStyle(color = SailText, fontSize = 14.sp),
                cursorBrush = SolidColor(SailPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSubmit()
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (input.text.isEmpty()) Text("搜索视频、UP 主、番剧", color = SailMuted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
            if (input.text.isNotEmpty()) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button) {
                        onInputChange(TextFieldValue(""))
                    }.semantics { contentDescription = "清空搜索" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MusicIcons.Close, null, Modifier.size(17.dp), tint = SailMuted)
                }
            }
        }
        Box(
            Modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(SailPrimary)
                .clickable(role = Role.Button, onClick = onSubmit).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("搜索", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SearchDiscovery(
    history: List<String>,
    onSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (history.isNotEmpty()) {
            item("history-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", Modifier.weight(1f), color = SailText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("清空", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearHistory)
                        .padding(horizontal = 8.dp, vertical = 5.dp), color = SailMuted, fontSize = 12.sp)
                }
            }
            items(history, key = { "history-$it" }) { query ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { onSearch(query) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↗", color = SailMuted, fontSize = 15.sp)
                    Text(query, Modifier.padding(start = 11.dp), color = SailText.copy(alpha = .86f), fontSize = 14.sp)
                }
            }
        }
        item("hot-title") {
            Text("大家都在搜", color = SailText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        items(defaultSuggestions.chunked(2), key = { row -> "suggest-${row.firstOrNull()}" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { index, query ->
                    Row(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(12.dp))
                            .background(SailSurface).clickable(role = Role.Button) { onSearch(query) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (defaultSuggestions.indexOf(query) + 1).toString().padStart(2, '0'),
                            color = if (index == 0) SailPrimary else SailBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(query, Modifier.padding(start = 9.dp), color = SailText, fontSize = 13.sp, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SailCategoryBar(
    selected: BiliSailSearchCategory,
    counts: Map<BiliSailSearchCategory, Int>,
    onSelect: (BiliSailSearchCategory) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BiliSailSearchCategory.entries.forEach { item ->
            val active = item == selected
            Row(
                Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (active) SailPrimary else SailSurface)
                    .clickable(role = Role.Tab) { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag("bili_sail_search_${item.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.label, color = if (active) Color.White else SailMuted, fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                val count = counts[item] ?: 0
                if (item != BiliSailSearchCategory.ALL && count > 0) {
                    Text(if (count > 99) "99+" else count.toString(), Modifier.padding(start = 5.dp),
                        color = if (active) Color.White.copy(alpha = .75f) else SailMuted.copy(alpha = .65f),
                        fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsGrid(
    result: BiliSailSearchBundle,
    category: BiliSailSearchCategory,
    loadingMore: Boolean,
    loadMore: () -> Unit,
    onPlayVideo: (BiliHubRepository.Video) -> Unit,
    onCreator: (Long) -> Unit,
    onWeb: (String) -> Unit,
    modifier: Modifier,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    val showUsers = category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.USER)
    val showMedia = category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.BANGUMI)
    val showVideos = category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.VIDEO)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        modifier = modifier.testTag("bili_sail_search_results"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (showUsers && result.users.isNotEmpty()) {
            item(key = "users-title", span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionTitle("UP 主", result.users.size)
            }
            items(result.users, key = { "user-${it.mid}" }, span = { GridItemSpan(maxLineSpan) }) { user ->
                UserResultRow(user, onCreator)
            }
        }
        if (showMedia && result.media.isNotEmpty()) {
            item(key = "media-title", span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionTitle("番剧与影视", result.media.size)
            }
            items(result.media, key = { "media-${it.seasonId}" }) { item ->
                MediaResultCard(item, onWeb)
            }
        }
        if (showVideos && result.videos.isNotEmpty()) {
            item(key = "videos-title", span = { GridItemSpan(maxLineSpan) }) {
                SearchSectionTitle("视频", result.videoTotal.takeIf { it > 0 } ?: result.videos.size)
            }
            items(result.videos, key = { "video-${it.bvid}" }) { video ->
                VideoResultCard(video, onPlayVideo)
            }
        }
        if (showVideos && (result.videoTotal > result.videos.size || result.videoPages > 1)) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp).height(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(SailSurface).clickable(enabled = !loadingMore, role = Role.Button, onClick = loadMore),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingMore) CircularProgressIndicator(Modifier.size(18.dp), size = 18.dp, strokeWidth = 2.dp)
                    else Text("加载更多", color = SailText, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = SailText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), Modifier.padding(start = 7.dp), color = SailMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun VideoResultCard(video: BiliHubRepository.Video, onClick: (BiliHubRepository.Video) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button) { onClick(video) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).background(SailSurfaceRaised)) {
            AsyncImage(
                model = video.cover,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.durationSec > 0) {
                Text(
                    BiliHubRepository.durationLabel(video.durationSec),
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                )
            }
        }
        Text(
            video.title,
            Modifier.fillMaxWidth().padding(top = 7.dp),
            color = SailText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("UP", color = SailPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(video.uploader.ifBlank { "哔哩哔哩" }, Modifier.padding(start = 5.dp).weight(1f),
                color = SailMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (video.views > 0) {
            Text(
                BiliHubRepository.compactCount(video.views) + " 播放" +
                    (if (video.comments > 0) " · ${BiliHubRepository.compactCount(video.comments)} 弹幕" else ""),
                Modifier.padding(top = 2.dp), color = SailMuted.copy(alpha = .76f), fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun UserResultRow(user: BiliSailSearchUser, onCreator: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SailSurface)
            .clickable(role = Role.Button) { onCreator(user.mid) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.avatar,
            contentDescription = user.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(50.dp).clip(CircleShape).background(SailSurfaceRaised),
        )
        Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.name, color = SailText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("UP 主", Modifier.padding(start = 7.dp).clip(RoundedCornerShape(4.dp))
                    .background(SailPrimary.copy(alpha = .16f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    color = SailPrimary, fontSize = 8.sp)
            }
            Text(user.sign.ifBlank { "这个人很神秘，什么都没有写" }, color = SailMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${BiliHubRepository.compactCount(user.fans)} 粉丝 · ${user.videos} 个视频",
                color = SailMuted.copy(alpha = .72f), fontSize = 9.5.sp)
        }
        Text("关注", Modifier.clip(RoundedCornerShape(14.dp)).background(SailPrimary)
            .padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun MediaResultCard(item: BiliSailSearchMedia, onWeb: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button) { onWeb(item.url) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(9.dp)).background(SailSurfaceRaised)) {
            AsyncImage(
                model = item.cover,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.score.isNotBlank()) {
                Text(item.score, Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = .74f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    color = Color(0xFFFFCE66), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(item.progress.ifBlank { "已完结" }, Modifier.align(Alignment.BottomStart).padding(6.dp),
                color = Color.White, fontSize = 9.sp)
        }
        Text(item.title, Modifier.padding(top = 7.dp), color = SailText, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.areas.isNotBlank()) Text(item.areas, Modifier.padding(top = 3.dp), color = SailMuted, fontSize = 9.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchCenteredState(
    message: String,
    modifier: Modifier,
    showProgress: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (showProgress) CircularProgressIndicator(Modifier.size(24.dp), size = 24.dp, strokeWidth = 2.dp)
        Text(message, Modifier.padding(horizontal = 26.dp, vertical = 14.dp), color = SailMuted,
            fontSize = 13.sp, lineHeight = 20.sp)
        if (action != null && onAction != null) {
            Text(action, Modifier.clip(RoundedCornerShape(18.dp)).background(SailPrimary)
                .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White, fontSize = 12.sp)
        }
    }
}
