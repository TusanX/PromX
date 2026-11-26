package io.github.currencortex.music.feature.style

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.currencortex.music.core.alt.QqLibrary
import io.github.currencortex.music.core.network.AppResult
import io.github.currencortex.music.core.network.appResult
import io.github.currencortex.music.ui.component.*
import io.github.currencortex.music.ui.component.nativeui.*
import io.github.currencortex.music.ui.util.collectAsPageState
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class QqDiscoveryState(val loading: Boolean = true,
    val groups: List<QqLibrary.CategoryGroup> = emptyList(),
    val chartGroups: List<QqLibrary.ChartGroup> = emptyList(), val error: String? = null)

class QqDiscoveryViewModel : ViewModel() {
    val state = MutableStateFlow(QqDiscoveryState())
    private var task: Job? = null

    init { reload() }

    fun reload() {
        task?.cancel()
        state.update { it.copy(loading = true, error = null) }
        task = viewModelScope.launch {
            when (val result = appResult {
                coroutineScope {
                    val categories = async(Dispatchers.IO) { QqLibrary.playlistCategories() }
                    val charts = async(Dispatchers.IO) { QqLibrary.chartGroups() }
                    categories.await() to charts.await()
                }
            }) {
                is AppResult.Success -> state.value = QqDiscoveryState(loading = false,
                    groups = result.value.first, chartGroups = result.value.second)
                is AppResult.Failure -> state.value = QqDiscoveryState(loading = false, error = result.kind.message)
            }
        }
    }

    override fun onCleared() { task?.cancel() }
}

data class QqCategoryDetailState(val loading: Boolean = true, val title: String = "QQ 音乐分类",
    val playlists: List<QqLibrary.PlaylistPreview> = emptyList(), val nextPage: Int = 0,
    val more: Boolean = false, val error: String? = null)

class QqCategoryDetailViewModel(private val categoryId: Long) : ViewModel() {
    val state = MutableStateFlow(QqCategoryDetailState())
    private var task: Job? = null

    init { load(reset = true) }

    fun reload() = load(reset = true)
    fun loadMore() = load(reset = false)

    private fun load(reset: Boolean) {
        val before = state.value
        if (!reset && (before.loading || !before.more)) return
        task?.cancel()
        state.update { it.copy(loading = true, error = null) }
        task = viewModelScope.launch {
            val page = if (reset) 0 else before.nextPage
            when (val result = appResult {
                withContext(Dispatchers.IO) {
                    val title = if (reset) QqLibrary.playlistCategories().asSequence()
                        .flatMap { it.categories.asSequence() }.firstOrNull { it.id == categoryId }?.name
                        ?: "QQ 音乐分类" else before.title
                    title to QqLibrary.categoryPlaylists(categoryId, page, 20)
                }
            }) {
                is AppResult.Success -> {
                    val (title, pageData) = result.value
                    val playlists = (if (reset) emptyList() else before.playlists) + pageData.first
                    state.value = QqCategoryDetailState(loading = false, title = title,
                        playlists = playlists.distinctBy { it.id }, nextPage = page + 1,
                        more = pageData.second)
                }
                is AppResult.Failure -> state.value = before.copy(loading = false, error = result.kind.message)
            }
        }
    }

    override fun onCleared() { task?.cancel() }
}

@Composable
fun QqDiscoveryCategories(vm: QqDiscoveryViewModel, navigate: (String) -> Unit) {
    val state by vm.state.collectAsPageState()
    val categories = state.groups.flatMap(QqLibrary.CategoryGroup::categories).distinctBy(QqLibrary.Category::id)
    val charts = state.chartGroups.flatMap(QqLibrary.ChartGroup::charts).distinctBy(QqLibrary.Chart::id)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var chartsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("qq_discovery_categories"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsListHeader("QQ 音乐分类") {
            if (categories.size > 6) MusicTextAction(if (expanded) "收起" else "全部 ${categories.size}",
                { expanded = !expanded }, Modifier.testTag("qq_categories_expand"))
        }
        if (state.loading && categories.isEmpty()) {
            repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(2) { Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(22.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)) }
            } }
        }
        (if (expanded) categories else categories.take(6)).chunked(2).forEachIndexed { rowIndex, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { columnIndex, category ->
                    key(category.id) {
                        QqCategoryCard(category, (rowIndex * 2 + columnIndex) % 5,
                            Modifier.weight(1f).elasticRootComponent(rowIndex * 2 + columnIndex)) {
                            navigate("lib/qq/discover/${category.id}")
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        state.error?.let {
            Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            MusicTextAction("重新加载分类", vm::reload, Modifier.testTag("qq_categories_retry"))
        }
        if (charts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SettingsListHeader("QQ 榜单") {
                if (charts.size > 4) MusicTextAction(if (chartsExpanded) "收起" else "全部 ${charts.size}",
                    { chartsExpanded = !chartsExpanded }, Modifier.testTag("qq_charts_expand"))
            }
            (if (chartsExpanded) charts else charts.take(4)).chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { columnIndex, chart ->
                        key("chart_${chart.id}") {
                            QqChartCard(chart, Modifier.weight(1f).elasticRootComponent(rowIndex * 2 + columnIndex)) {
                                navigate("lib/qq/toplist/${chart.id}/${chart.period}")
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QqCategoryCard(category: QqLibrary.Category, tone: Int, modifier: Modifier,
    onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val accent = listOf(Color(0xFF1F8A70), Color(0xFF4A7A96), Color(0xFF8B6F47),
        Color(0xFF6C668C), Color(0xFF987067))[tone]
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(160), label = "QQ category press")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.height(96.dp).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(accent.copy(alpha = .32f),
            colors.surfaceContainer))).clickable(source, indication = null, role = Role.Button, onClick = onClick)
        .testTag("qq_category_${category.id}").padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(category.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text("歌单精选", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun QqChartCard(chart: QqLibrary.Chart, modifier: Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(150), label = "QQ chart press")
    SettingsGroup(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.testTag("qq_chart_${chart.id}")) {
        Column(Modifier.fillMaxWidth().clickable(source, indication = null, role = Role.Button,
            onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MusicCover(chart.cover, Modifier.size(48.dp), 240, cornerRadius = 12.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(chart.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(chart.total.takeIf { it > 0 }?.let { "$it 首" },
                        chart.listenCount.takeIf { it > 0 }?.let(::compactCount)).filterNotNull()
                        .joinToString(" · ").ifBlank { "QQ 官方榜单" }, fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            chart.preview.firstOrNull()?.let { preview ->
                Text("${preview.rank}. ${preview.title}  ${preview.artist}", fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun QqCategoryDetailScreen(categoryId: Long, onBack: () -> Unit,
    navigate: (String) -> Unit) {
    val vm: QqCategoryDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "qq-category-$categoryId", factory = io.github.currencortex.music.ui.util.viewModelFactory {
            QqCategoryDetailViewModel(categoryId)
        })
    val state by vm.state.collectAsPageState()
    MusicSubpage(state.title, "qq_category_page", onBack) { topInset ->
        MusicPullToRefresh(state.loading, vm::reload, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("qq_category_content"),
                contentPadding = PaddingValues(start = 16.dp, top = topInset + 14.dp,
                    end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("intro") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(state.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("来自 QQ 音乐分类精选歌单", fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                if (state.loading && state.playlists.isEmpty()) item("loading") { LoadingSongList(5) }
                itemsIndexed(state.playlists, key = { _, item -> item.id }) { index, playlist ->
                    SettingsGroup(Modifier.testTag("qq_category_playlist_${playlist.id}")) {
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            navigate("lib/playlist/${playlist.id}")
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            MusicArtworkCard(playlist.cover, playlist.title,
                                listOf(playlist.creator, playlist.playCount?.let(::compactCount)).filterNotNull()
                                    .filter(String::isNotBlank).joinToString(" · "),
                                { navigate("lib/playlist/${playlist.id}") }, Modifier.width(126.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(playlist.title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(playlist.description.ifBlank { "QQ 音乐精选歌单" }, fontSize = 12.sp,
                                    lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
                state.error?.let { error -> item("error") {
                    Text(error, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    MusicTextAction("重试", vm::reload, Modifier.testTag("qq_category_retry"))
                } }
                if (state.more && state.error == null) item("more") {
                    MusicTextAction(if (state.loading) "正在加载…" else "加载更多", vm::loadMore,
                        Modifier.fillMaxWidth().testTag("qq_category_more"), enabled = !state.loading)
                }
            }
        }
    }
}

private fun compactCount(value: Long): String = when {
    value >= 100_000_000 -> "%.1f 亿播放".format(value / 100_000_000f)
    value >= 10_000 -> "%.1f 万播放".format(value / 10_000f)
    else -> "$value 次播放"
}
