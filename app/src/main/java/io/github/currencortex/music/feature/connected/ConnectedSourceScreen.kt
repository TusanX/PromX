// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.connected

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.data.connected.ConnectedEntry
import io.github.currencortex.music.data.connected.ConnectedTrack
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.feature.settings.MusicSettingsViewModel
import io.github.currencortex.music.ui.component.MusicCover
import io.github.currencortex.music.ui.component.MusicDestinationRow
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.nativeui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

enum class ConnectedSourceKind(val title: String, val tag: String) {
    WEBDAV("WebDAV", "webdav"),
    MUSIC_ASSISTANT("Music Assistant", "music_assistant"),
}

@Composable
fun ConnectedSourceScreen(
    kind: ConnectedSourceKind,
    vm: MusicSettingsViewModel,
    onBack: () -> Unit,
    play: (List<Song>, Int) -> Unit,
) {
    val webDav by vm.webDavAccount.collectAsStateWithLifecycle()
    val assistant by vm.musicAssistantAccount.collectAsStateWithLifecycle()
    val network by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val container = vm.container
    var server by rememberSaveable(kind) { mutableStateOf(if (kind == ConnectedSourceKind.WEBDAV) webDav.server else assistant.server) }
    var username by rememberSaveable(kind) { mutableStateOf(if (kind == ConnectedSourceKind.WEBDAV) webDav.username else "") }
    var secret by rememberSaveable(kind) { mutableStateOf("") }
    var loading by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var path by rememberSaveable(kind) { mutableStateOf("") }
    var entries by remember(kind) { mutableStateOf<List<ConnectedEntry>>(emptyList()) }
    var tracks by remember(kind) { mutableStateOf<List<ConnectedTrack>>(emptyList()) }
    var players by remember(kind) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedPlayer by rememberSaveable(kind) { mutableStateOf("") }
    val connected = if (kind == ConnectedSourceKind.WEBDAV) webDav.configured else assistant.configured

    fun loadWebDav(target: String = "") {
        scope.launch {
            loading = true; error = null
            try {
                entries = withContext(Dispatchers.IO) { container.webDav.browse(target) }
                path = target
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.userText() }
            finally { loading = false }
        }
    }

    fun loadAssistant(query: String = "") {
        scope.launch {
            loading = true; error = null
            try {
                tracks = withContext(Dispatchers.IO) {
                    if (query.isBlank()) container.musicAssistant.tracks() else container.musicAssistant.search(query)
                }
                players = withContext(Dispatchers.IO) { container.musicAssistant.remotePlayers() }
                if (selectedPlayer.isBlank()) selectedPlayer = players.firstOrNull()?.first.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.userText() }
            finally { loading = false }
        }
    }

    LaunchedEffect(connected, kind) {
        if (connected) {
            if (kind == ConnectedSourceKind.WEBDAV) loadWebDav() else loadAssistant()
        }
    }

    MusicSubpage(kind.title, kind.tag + "_source", onBack) { topInset ->
        LazyColumn(
            Modifier.fillMaxSize().testTag(kind.tag + "_screen"),
            contentPadding = PaddingValues(16.dp, topInset + 8.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!connected) item("connect") {
                SettingsSection("连接 " + kind.title, MusicIcons.Storage) {
                    TextField(server, { server = it }, label = "服务器地址",
                        modifier = Modifier.fillMaxWidth().testTag(kind.tag + "_server"))
                    if (kind == ConnectedSourceKind.WEBDAV) {
                        TextField(username, { username = it }, label = "用户名",
                            modifier = Modifier.fillMaxWidth().testTag(kind.tag + "_username"))
                        TextField(secret, { secret = it }, label = "密码", singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag(kind.tag + "_secret"))
                    } else {
                        TextField(secret, { secret = it }, label = "长期访问令牌", singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag(kind.tag + "_secret"))
                    }
                    Text(
                        if (kind == ConnectedSourceKind.WEBDAV) "支持 Nextcloud、坚果云及标准 WebDAV 音乐目录。"
                        else "在 Music Assistant 网页端个人资料中创建长期访问令牌。曲库可浏览并发送到已连接的播放器。",
                        style = fontsizeHint,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MusicTextAction(if (network.busy) "正在连接…" else "连接", {
                            if (kind == ConnectedSourceKind.WEBDAV) vm.connectWebDav(server, username, secret) { ok ->
                                if (ok) { secret = ""; loadWebDav() }
                            } else vm.connectMusicAssistant(server, secret) { ok ->
                                if (ok) { secret = ""; loadAssistant() }
                            }
                        }, Modifier.testTag(kind.tag + "_connect"), enabled = !network.busy)
                    }
                }
            } else item("account") {
                SettingsSection(if (kind == ConnectedSourceKind.WEBDAV) "WebDAV 曲库" else "Music Assistant 曲库",
                    MusicIcons.Storage) {
                    MusicDestinationRow(
                        if (kind == ConnectedSourceKind.WEBDAV) webDav.server else assistant.server,
                        {},
                        summary = if (kind == ConnectedSourceKind.WEBDAV) "已连接 " + webDav.username
                        else "已连接 " + assistant.userName.ifBlank { "Music Assistant" } + " · " + players.size + " 个可用播放器",
                        chevron = false,
                    )
                    SettingsDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicTextAction("刷新", {
                            if (kind == ConnectedSourceKind.WEBDAV) loadWebDav(path) else loadAssistant()
                        }, enabled = !loading)
                        if (kind == ConnectedSourceKind.WEBDAV && path.isNotBlank()) {
                            MusicTextAction("上一级", {
                                val parent = path.trimEnd('/').substringBeforeLast('/', "")
                                loadWebDav(if (parent.isBlank()) webDav.server + "/" else parent + "/")
                            }, enabled = !loading)
                        }
                        MusicTextAction("断开", {
                            if (kind == ConnectedSourceKind.WEBDAV) vm.disconnectWebDav() else vm.disconnectMusicAssistant()
                            entries = emptyList(); tracks = emptyList()
                        }, destructive = true, enabled = !network.busy)
                    }
                }
            }
            error?.let { message -> item { SettingsHint(message, error = true) } }
            network.message?.takeIf { !network.busy }?.let { message -> item { SettingsHint(message) } }
            if (connected && loading) item { SettingsHint("正在读取曲库…") }
            if (connected && kind == ConnectedSourceKind.WEBDAV) {
                items(entries, key = { it.path }) { entry ->
                    if (entry.directory) {
                        MusicDestinationRow(entry.title, { loadWebDav(entry.path) },
                            modifier = Modifier.testTag("webdav_folder_" + entry.id.hashCode()),
                            summary = "文件夹")
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MusicCover("", Modifier.size(48.dp), cornerRadius = 14.dp)
                            Column(Modifier.weight(1f)) {
                                Text(entry.title)
                                Text(entry.mime.ifBlank { "WebDAV 音频" }, style = fontsizeHint)
                            }
                            MusicTextAction("播放", {
                                play(listOf(container.webDav.prospectiveSong(entry)), 0)
                            }, enabled = entry.mime.startsWith("audio/") || entry.title.substringAfterLast('.', "")
                                .lowercase() in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav"))
                        }
                    }
                }
            }
            if (connected && kind == ConnectedSourceKind.MUSIC_ASSISTANT) {
                if (players.isNotEmpty()) item("player") {
                    SettingsSection("发送到播放器", MusicIcons.Play) {
                        players.forEachIndexed { index, (id, name) ->
                            if (index > 0) SettingsDivider()
                            MusicDestinationRow(name, { selectedPlayer = id },
                                summary = if (selectedPlayer == id) "当前目标" else "点击选择",
                                chevron = false)
                        }
                    }
                }
                items(tracks, key = { it.song.id }) { track ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicCover(track.song.cover, Modifier.size(54.dp), cornerRadius = 15.dp)
                        Column(Modifier.weight(1f)) {
                            Text(track.song.name)
                            Text(listOf(track.song.artists, track.provider).filter { it.isNotBlank() }.joinToString(" · "),
                                style = fontsizeHint)
                        }
                        MusicTextAction("播放", {
                            scope.launch {
                                try { container.musicAssistant.playRemote(track, selectedPlayer) }
                                catch (e: Exception) { error = e.userText() }
                            }
                        }, enabled = selectedPlayer.isNotBlank())
                    }
                }
            }
        }
    }
}

private val fontsizeHint = TextStyle(fontSize = TextUnit(13f, TextUnitType.Sp))

private fun Throwable.userText(): String = when (this) {
    is io.github.currencortex.music.core.network.ApiException -> userMessage ?: message ?: "请求失败"
    else -> message?.take(180) ?: "请求失败，请检查网络和服务地址"
}
