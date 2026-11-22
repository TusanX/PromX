package io.github.currencortex.music.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.data.profile.ProfileStats
import io.github.currencortex.music.data.profile.listeningDuration
import io.github.currencortex.music.ui.component.rememberPageNavigationSource
import io.github.currencortex.music.ui.component.LocalRootTabSwipe
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.nativeui.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun ProfilePageTitle(navigate: (String) -> Unit, onSettings: (() -> Unit)?,
    public: Boolean = false, loading: Boolean = false, reload: () -> Unit = {}) {
    val actions: @Composable RowScope.() -> Unit = {
        if (public) GlassIconButton(reload, "刷新", enabled = !loading) { Icon(MusicIcons.Update, null) }
        onSettings?.let { action -> GlassIconButton(action, "设置", Modifier.testTag("me_settings")) {
            Icon(Icons.Outlined.Settings, null)
        } }
    }
    SettingsUiScope {
        if (public) Row(Modifier.fillMaxWidth().height(SettingsToolbarHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("用户主页", Modifier.weight(1f), fontSize = 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            actions()
        }
        else SettingsRootTopBar("我的", Modifier.testTag("me_root_toolbar"), actions = actions)
    }
}

@Composable internal fun ProfilePageLayout(navigate: (String) -> Unit, onSettings: (() -> Unit)?,
    public: Boolean = false, loading: Boolean = false, reload: () -> Unit = {}, onBack: (() -> Unit)? = null, content: @Composable (Dp) -> Unit) {
    if (public) MusicSubpage("用户主页", "public_profile", onBack, actions = {
        GlassIconButton(reload, "刷新", enabled = !loading) { Icon(MusicIcons.Update, null) }
    }, content = content)
    else SettingsScrollLayout(Modifier.fillMaxSize(), topBar = {
        ProfilePageTitle(navigate, onSettings, loading = loading, reload = reload)
    }, content = content)
}

@Composable internal fun ProfileDestination(title: String, summary: String?, icon: ImageVector,
    tag: String, onClick: () -> Unit) {
    val source = rememberPageNavigationSource(title, summary.orEmpty(), icon)
    SettingsUiScope {
        SettingsPreference(title, summary, Modifier.testTag(tag).then(source.modifier), onClick = { source.open(onClick) },
            leading = { Icon(icon, null, Modifier.size(24.dp)) },
            trailing = { Icon(MusicIcons.Forward, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
    }
}

/**
 * 账号行：登录后左边换成平台头像，副标题写清楚连接状态——
 * 三行并排时，用户要一眼看出哪个平台已经连上了。
 */
@Composable internal fun ProfileAccountDestination(title: String, summary: String, avatarUrl: String,
    icon: ImageVector, tag: String, showDot: Boolean, onClick: () -> Unit) {
    val source = rememberPageNavigationSource(title, summary, icon)
    SettingsUiScope {
        SettingsPreference(title, summary, Modifier.testTag(tag).then(source.modifier), onClick = { source.open(onClick) },
            leading = {
                if (avatarUrl.isNotBlank()) {
                    coil3.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    Icon(icon, null, Modifier.size(24.dp))
                }
            },
            trailing = {
                if (showDot) Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MiuixTheme.colorScheme.primary))
                Icon(MusicIcons.Forward, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            })
    }
}

@Composable internal fun ProfileLibrarySection(navigate: (String) -> Unit,
    container: io.github.currencortex.music.AppContainer? = null) {
    val likesScope = androidx.compose.runtime.rememberCoroutineScope()
    SettingsListSection("我的音乐") {
        ProfileDestination("我喜欢", "收藏的歌曲", MusicIcons.Heart, "open_likes") {
            if (container == null) navigate("lib/likes") else likesScope.launch {
                navigate(io.github.currencortex.music.feature.library.likesRouteFor(container))
            }
        }
        SettingsDivider(52.dp)
        ProfileDestination("歌单", "创建与收藏的歌单", MusicIcons.List, "open_playlists") { navigate("lib/playlists") }
        SettingsDivider(52.dp)
        ProfileDestination("最近播放", "回顾听过的歌曲", MusicIcons.Update, "open_recent") { navigate("lib/recent") }
        val sourceMode = container?.musicSettings?.state?.collectAsStateWithLifecycle()?.value?.primarySource
        val qqMode = sourceMode == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC
        val biliMode = sourceMode == io.github.currencortex.music.data.song.MusicSource.BILIBILI
        if (qqMode) {
            SettingsDivider(52.dp)
            ProfileDestination("QQ 音乐听歌报告", "账号音乐基因、月度听歌与风格画像",
                MusicIcons.Headphones, "open_qq_listening_report") { navigate("user/qq-listening") }
        } else if (!biliMode) {
            SettingsDivider(52.dp)
            ProfileDestination("听歌时长", "本机与网易云听歌足迹", MusicIcons.Headphones, "open_listening") {
                navigate("user/listening")
            }
        }
        SettingsDivider(52.dp)
        if (biliMode) {
            ProfileDestination("本地缓存", "已缓存的哔哩哔哩视频与音频", MusicIcons.Device, "open_local_music") {
                navigate("lib/local")
            }
        } else {
            ProfileDestination("本地音乐", "手机上的歌曲", MusicIcons.Device, "open_local_music") { navigate("lib/local") }
        }
    }
}

/** Native and CurrentMusic accounts share destinations and the same list geometry. */
@Composable internal fun ProfileServicesSection(navigate: (String) -> Unit, tasks: Boolean,
    native: Boolean = false, bound: Boolean = true,
    navidromeConfigured: Boolean = false, embyConfigured: Boolean = false,
    container: io.github.currencortex.music.AppContainer? = null) {
    SettingsListSection("音乐服务") {
        ProfileDestination(if (native && !bound) "登录网易云" else "网易云账号",
            if (!bound) "扫码绑定网易云，解锁播放与推荐" else "已绑定 · 管理账号与歌单", MusicIcons.Account, "open_binding") { navigate("user/binding") }
        if (tasks) {
            SettingsDivider(52.dp)
            ProfileDestination("网易云任务", "签到、云贝与会员成长", MusicIcons.Check, "open_netease_tasks") { navigate("user/netease-tasks") }
        }
        SettingsDivider(52.dp)
        val qqAccount = container?.qqSession?.account
        ProfileAccountDestination(
            title = "QQ 音乐",
            summary = qqAccount?.let { "已连接 ${it.nickname.ifBlank { it.uin }}" } ?: "扫码登录，解锁 QQ 音乐曲库",
            avatarUrl = qqAccount?.avatar.orEmpty(), icon = MusicIcons.Account,
            tag = "profile_qq_login", showDot = qqAccount != null) { navigate("qq/login") }
        SettingsDivider(52.dp)
        val biliProfile = container?.biliSession?.profile
        ProfileAccountDestination(
            title = "哔哩哔哩",
            summary = biliProfile?.let { "已连接 ${it.uname} · Lv${it.level}" } ?: "扫码登录，读取收藏夹与关注",
            avatarUrl = biliProfile?.face.orEmpty(), icon = MusicIcons.Account,
            tag = "profile_bili_login", showDot = biliProfile != null) { navigate("bili/login") }
        SettingsDivider(52.dp)
        ProfileDestination("导入歌单", "链接、文字、图片与本地歌曲", MusicIcons.Transfer, "profile_playlist_import") { navigate("user/playlist-import") }
        if (navidromeConfigured) {
            SettingsDivider(52.dp)
            ProfileDestination("Navidrome 曲库", "自己的音乐服务器", MusicIcons.Storage, "profile_navidrome") { navigate("navidrome") }
        }
        if (embyConfigured) {
            SettingsDivider(52.dp)
            ProfileDestination("Emby 曲库", "自己的音乐服务器", MusicIcons.Storage, "profile_emby") { navigate("emby") }
        }
    }
}

@Composable internal fun ProfileAccountSection(navigate: (String) -> Unit, native: Boolean = false, administrator: Boolean = false) {
    // 账号系统已移除：不渲染任何账号/等级/安全/管理员入口
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ProfileStatsBlock(stats: ProfileStats, onListening: (() -> Unit)? = null) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("profile_stats"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("点赞" to "${stats.likes}", "收藏" to "${stats.favs}", "歌单" to "${stats.playlists}").forEach { (label, value) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(value, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp * LocalDensity.current.fontScale + 10.dp) 2 else 1
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .035f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
            Column(tile, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("听歌天数", fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                Text("${stats.playDays} 天", fontSize = 15.sp, lineHeight = 20.sp)
            }
            Column(tile.then(onListening?.let { Modifier.clickable(role = Role.Button, onClick = it).testTag("profile_listening_duration") }
                ?: Modifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("听歌时长", fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                Text(listeningDuration(stats.listenMs), fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
        }
    }
}
