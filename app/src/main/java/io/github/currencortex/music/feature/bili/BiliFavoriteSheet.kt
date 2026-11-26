// SPDX-License-Identifier: GPL-3.0-only
// 哔哩哔哩 收藏夹选择器：把当前视频加入指定收藏夹（交互参考 Bilight 的 FavoriteSheet）
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.ui.component.MusicOverlayDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun BiliFavoriteSheet(
    container: AppContainer,
    bvid: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<BiliAuth.FavFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var collected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var aid by remember { mutableStateOf("") }
    val session = container.biliSession

    LaunchedEffect(bvid) {
        val info = withContext(Dispatchers.IO) {
            io.github.currencortex.music.core.alt.BiliPlayback.videoInfo(bvid, session.cookieHeader())
        }
        aid = info?.aid.orEmpty()
        folders = withContext(Dispatchers.IO) {
            runCatching {
                BiliAuth.favoriteFolders(session.cookieHeader(), session.profile?.mid ?: 0L)
            }.getOrDefault(emptyList())
        }
        if (aid.isNotBlank()) {
            collected = withContext(Dispatchers.IO) { BiliAuth.collectedFolders(session.cookieHeader(), aid) }
        }
        loading = false
    }

    MusicOverlayDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("收藏夹", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (loading) Text("正在读取收藏夹…", fontSize = 13.sp)
            message?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(folders, key = { it.id }) { folder ->
                    val isIn = folder.id in collected
                    // 点之前再确认一次真实归属（本地集合可能是上一次的）
                    Row(Modifier.fillMaxWidth().clickable(enabled = !loading) {
                        scope.launch {
                            loading = true
                            // 先取真实的归属集合：否则“已收藏”只是本地推测，点了反而再加一次
                            val inside = withContext(Dispatchers.IO) {
                                BiliAuth.isInFolder(session.cookieHeader(), folder.id, bvid, maxPages = 3)
                            }
                            val ok = withContext(Dispatchers.IO) {
                                if (inside) BiliAuth.removeFromFolder(session.cookieHeader(), bvid, folder.id,
                                    session.cookies["bili_jct"].orEmpty())
                                else BiliAuth.addToFolder(session.cookieHeader(), bvid, folder.id,
                                    session.cookies["bili_jct"].orEmpty())
                            }
                            loading = false
                            collected = if (ok) {
                                if (inside) collected - folder.id else collected + folder.id
                            } else collected
                            message = when {
                                ok && inside -> "已取消收藏：${folder.title}"
                                ok -> "已收藏到「${folder.title}」"
                                inside -> "取消收藏失败：${folder.title}"
                                else -> "收藏失败：${folder.title}"
                            }
                        }
                    }.padding(vertical = 12.dp).testTag("bili_fav_${folder.id}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.title, fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isIn) "已收藏" else "${folder.count} 首", fontSize = 12.sp,
                                color = if (isIn) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isIn) "✓" else "+", fontSize = 15.sp,
                                color = if (isIn) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurface.copy(alpha = .35f))
                        }
                    }
                }
            }
            if (!loading && folders.isEmpty()) Text("没有读到收藏夹，请确认已登录 哔哩哔哩", fontSize = 13.sp)
        }
    }
}
