// SPDX-License-Identifier: GPL-3.0-only
// 播放时的「热评气泡」：歌曲播到第 5 秒，在右上角浮出一条该曲的热评，8 秒后淡出。
// 数据走设备上的网易云登录态（ncm/comments/page type=hot），不依赖 Prom 服务。
package io.github.currencortex.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import io.github.currencortex.music.ui.component.nativeui.glassSurface
import io.github.currencortex.music.ui.component.nativeui.LocalGlassBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.data.song.CommentSort
import io.github.currencortex.music.data.song.SongCommentsRepository
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class HotComment(val text: String, val author: String, val likes: Long)

/**
 * 只在「正在播放 + 已登录网易云」时出现；换歌会重新计时，暂停后不再冒新气泡。
 */
@Composable fun HotCommentBubble(container: AppContainer, hidden: Boolean = false, modifier: Modifier = Modifier) {
    val player by container.playerController.state.collectAsStateWithLifecycle()
    val netease by container.neteaseSessions.state.collectAsStateWithLifecycle()
    val song = player.song
    val playing = player.playing
    var current by remember { mutableStateOf<HotComment?>(null) }
    var dismissed by remember(song?.id) { mutableStateOf(false) }
    val repo = remember {
        SongCommentsRepository(container.apiClient, session = { container.musicSession() },
            qqSession = { container.qqSession })
    }
    val cache = remember { mutableMapOf<Long, HotComment>() }

    // 哔哩哔哩/QQ 的评论不需要网易云登录，只有网易云歌曲才依赖登录态
    val needsNeteaseLogin = song?.musicSource == io.github.currencortex.music.data.song.MusicSource.NETEASE
    LaunchedEffect(song?.id, playing, netease.loggedIn, dismissed) {
        if (song == null || !playing || dismissed) return@LaunchedEffect
        if (needsNeteaseLogin && !netease.loggedIn) return@LaunchedEffect
        cache[song.id]?.let { hit -> current = hit; BotSignals.ping(); delay(8_000); current = null; return@LaunchedEffect }
        delay(1_200)
        val page = runCatching { repo.comments(song, CommentSort.HOT, page = 1) }.getOrNull() ?: return@LaunchedEffect
        // 字数筛选：优先挑 12–58 字的短评，太长/太短都不要，换下一条
        fun clean(c: String) = c.trim().replace("\n", " ").replace(Regex("\\s+"), " ")
        val ranked = page.comments.sortedByDescending { it.likedCount }
        val picked = ranked.firstOrNull { clean(it.content).length in 12..58 }
            ?: ranked.firstOrNull { clean(it.content).length in 8..90 }
            ?: ranked.firstOrNull() ?: return@LaunchedEffect
        val best = picked
        val text = clean(best.content)
        if (text.isBlank()) return@LaunchedEffect
        cache[song.id] = HotComment(
            text = if (text.length > 58) text.take(56) + "…" else text,
            author = best.user.nickname.ifBlank { "网易云用户" },
            likes = best.likedCount,
        )
        current = cache[song.id]
        BotSignals.ping()   // 通知小 AI 弹一下
        delay(8_000)
        current = null
    }

    // 播放器展开时收起气泡
    LaunchedEffect(hidden) { if (hidden) current = null }

    // 文字交给小 AI 自己长成气泡，不再叠加独立浮层
    LaunchedEffect(current) {
        BotSignals.bubbleText.value = current?.let { "${it.text}\n—— ${it.author}" }
    }

}
