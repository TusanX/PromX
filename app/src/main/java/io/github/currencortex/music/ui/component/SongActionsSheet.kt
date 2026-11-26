package io.github.currencortex.music.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.data.song.Song
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SongMenu(val song: Song, val play: () -> Unit, val next: () -> Unit, val queue: () -> Unit,
    val roomRequest: Boolean = false, val extra: (@Composable () -> Unit)? = null, val transport: Boolean = true,
    val previewAnchor: androidx.compose.ui.geometry.Rect? = null, val shareInitially: Boolean = false)
data class RoomSongRequest(val request: (Song) -> Unit, val pending: (Long) -> Boolean)
val LocalSongMenu = staticCompositionLocalOf<((SongMenu) -> Unit)?> { null }
val LocalSongDownload = staticCompositionLocalOf<((Song) -> Unit)?> { null }
val LocalDismissSongMenu = staticCompositionLocalOf<() -> Unit> { {} }
val LocalRoomSongRequest = staticCompositionLocalOf<RoomSongRequest?> { null }

@Composable fun SongActionsSheet(menu: SongMenu, onDownload: ((Song) -> Unit)? = null, onDismiss: () -> Unit) {
    var sharing by remember(menu.song) { mutableStateOf(menu.shareInitially) }
    if (sharing) {
        io.github.currencortex.music.feature.share.SongShareDialog(menu.song, onDismiss)
        return
    }
    MusicChoiceDialog(menu.song.name, onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("song_actions_sheet")) {
            Text(menu.song.artists, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
            if (menu.transport) MusicDestinationRow(if (menu.roomRequest) "加入一起听" else "立即播放", { onDismiss(); menu.play() }, chevron = false)
            if (menu.transport && !menu.roomRequest) {
                MusicDestinationRow("下一首播放", { onDismiss(); menu.next() }, chevron = false)
                MusicDestinationRow("加入队列", { onDismiss(); menu.queue() }, chevron = false)
            }
            val biliBvid = menu.song.externalIds.bilibiliBvid?.takeIf { it.isNotBlank() }
            if (!menu.song.video && menu.song.musicSource != io.github.currencortex.music.data.song.MusicSource.LOCAL && onDownload != null && biliBvid == null)
                MusicDestinationRow("下载歌曲", {
                    onDismiss(); onDownload(menu.song)
                }, modifier = Modifier.testTag("song_download"), summary = "保存歌曲信息、封面与歌词", chevron = false)
            if (!menu.song.video && menu.song.musicSource != io.github.currencortex.music.data.song.MusicSource.LOCAL) MusicDestinationRow("分享歌曲", { sharing = true },
                modifier = Modifier.testTag("song_share"), summary = "生成海报、保存图片或复制链接", chevron = false)
            // 哔哩哔哩 专属入口：跳到视频页 / 复制 BV 号
            menu.song.externalIds.bilibiliBvid?.takeIf { it.isNotBlank() }?.let { bvid ->
                val context = androidx.compose.ui.platform.LocalContext.current
                MusicDestinationRow("在 哔哩哔哩 打开", {
                    onDismiss()
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.bilibili.com/video/" + bvid)).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            setPackage("tv.danmaku.bili")
                        })
                    }
                }, modifier = Modifier.testTag("song_open_bili"), summary = bvid, chevron = false)
                MusicDestinationRow("下载视频到本地缓存", {
                    onDismiss()
                    io.github.currencortex.music.feature.bili.BiliHost.of().download(bvid, menu.song.name)
                }, modifier = Modifier.testTag("song_download_video"), summary = "存到 App 的 Movies/PromX", chevron = false)
                MusicDestinationRow("复制 BV 号", {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BV", bvid))
                    onDismiss()
                }, modifier = Modifier.testTag("song_copy_bv"), chevron = false)
            }
            CompositionLocalProvider(LocalDismissSongMenu provides onDismiss) { menu.extra?.invoke() }
        }
    }
}
