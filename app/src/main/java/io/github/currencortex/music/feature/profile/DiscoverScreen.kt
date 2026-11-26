package io.github.currencortex.music.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.ui.component.*
import io.github.currencortex.music.ui.component.nativeui.*
import io.github.currencortex.music.ui.util.collectAsPageState
import io.github.currencortex.music.ui.util.viewModelFactory
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

@Composable fun DiscoverScreen(vm: DiscoverViewModel, navigate: (String) -> Unit) {
    DiscoverScreen(vm, rememberPagerState { 2 }, navigate)
}

@Composable fun DiscoverScreen(vm: DiscoverViewModel,
    pager: androidx.compose.foundation.pager.PagerState, navigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by vm.container.musicSettings.state.collectAsStateWithLifecycle()
    val neteaseAccount by vm.container.neteaseSessions.state.collectAsStateWithLifecycle()
    val followingIdentity = if (settings.primarySource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC)
        vm.container.qqSession.uin else neteaseAccount.profile?.uid?.toString().orEmpty()
    val following: FollowingViewModel = viewModel(key = "following:${settings.primarySource}:$followingIdentity",
        factory = viewModelFactory { FollowingViewModel(vm.container) })
    SettingsScrollLayout(Modifier.fillMaxSize().testTag("discover_screen"),
        topBar = { SettingsRootTopBar("发现", Modifier.testTag("discover_root_toolbar")) }) { topInset ->
        // Tabs own a fixed row. In particular, Chat's reversed message list must not
        // spend scroll distance collapsing a parent header or resizing its viewport.
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            SettingsSegmentedControl(listOf("音乐", "关注"), pager.targetPage,
                { index -> scope.launch { pager.animateScrollToPage(index) } },
                Modifier.elasticRootComponent().padding(horizontal = 16.dp).padding(bottom = 12.dp), tag = "discover_tabs", role = Role.Tab)
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .testTag("discover_pager")) { page ->
                when (page) {
                    0 -> DiscoverMusicPage(vm, navigate)
                    1 -> FollowingFeedPage(following, navigate)
                }
            }
        }
    }
}

@Composable private fun DiscoverMusicPage(vm: DiscoverViewModel, navigate: (String) -> Unit) {
    val styles by vm.styles.collectAsPageState()
    val source by vm.container.musicSettings.state.collectAsStateWithLifecycle()
    val qq = source.primarySource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC
    val native = source.primarySource == io.github.currencortex.music.data.song.MusicSource.NETEASE
    val bottom = LocalMusicBottomInset.current
    LazyColumn(Modifier.fillMaxSize().testTag("discover_music"),
        contentPadding = PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 20.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        rootMotionItems(skipKeys = setOf("styles")) {
        item { SettingsGroup {
            ProfileDestination("每日推荐", if (qq) "QQ 音乐为今天生成的推荐" else "今天的专属歌单",
                MusicIcons.Play, "discover_daily") { navigate("lib/daily") }
            SettingsDivider(52.dp)
            ProfileDestination("猜你喜欢", if (qq) "来自 QQ 音乐的个性化电台" else "沿着喜欢的旋律继续探索",
                MusicIcons.Heart, "discover_foryou") { navigate("lib/foryou") }
            if (qq) {
                SettingsDivider(52.dp)
                ProfileDestination("新歌速递", "QQ 音乐最新上架歌曲", MusicIcons.Update,
                    "discover_qq_new_songs") { navigate("lib/qq/new-songs") }
            }
        } }
        if (qq) item(key = "qq_categories") { QqDiscoverCategories(navigate) }
        if (native) item(key = "styles") {
            io.github.currencortex.music.feature.style.MusicStyleCategories(styles, vm::loadStyles, navigate, vm::loadStyleCover, listHeader = true)
        }
    }
    }
}

@Composable
private fun QqDiscoverCategories(navigate: (String) -> Unit) {
    val vm: io.github.currencortex.music.feature.style.QqDiscoveryViewModel = viewModel(
        key = "qq-discovery-categories",
        factory = viewModelFactory { io.github.currencortex.music.feature.style.QqDiscoveryViewModel() })
    io.github.currencortex.music.feature.style.QqDiscoveryCategories(vm, navigate)
}
