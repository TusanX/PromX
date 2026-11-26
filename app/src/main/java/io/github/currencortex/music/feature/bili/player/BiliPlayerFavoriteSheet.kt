// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.core.alt.BiliPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

private val FavoritePink = Color(0xFFFB7299)
private val FavoriteText = Color(0xFFF4F5F7)
private val FavoriteMuted = Color(0xFF959AA4)

/** Bilibili-native folder picker used by the standalone player. */
@Composable
internal fun BiliPlayerFavoriteSheet(
    container: AppContainer,
    bvid: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = container.biliSession
    var folders by remember(bvid) { mutableStateOf<List<BiliAuth.FavFolder>>(emptyList()) }
    var collected by remember(bvid) { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember(bvid) { mutableStateOf(true) }
    var workingFolder by remember(bvid) { mutableStateOf<Long?>(null) }
    var message by remember(bvid) { mutableStateOf<String?>(null) }

    LaunchedEffect(bvid) {
        val aid = withContext(Dispatchers.IO) {
            BiliPlayback.videoInfo(bvid, session.cookieHeader())?.aid.orEmpty()
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                BiliAuth.favoriteFolders(session.cookieHeader(), session.profile?.mid ?: 0L)
            }.getOrDefault(emptyList())
        }
        val inside = if (aid.isBlank()) emptySet() else withContext(Dispatchers.IO) {
            BiliAuth.collectedFolders(session.cookieHeader(), aid)
        }
        folders = loaded
        collected = inside
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.66f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF17181D)).navigationBarsPadding()
                .padding(top = 9.dp).testTag("bili_player_favorite_sheet"),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .18f)),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("收藏到", color = FavoriteText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("再次点击可取消该收藏夹中的视频", color = FavoriteMuted, fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 3.dp))
                }
                Text(
                    "完成",
                    Modifier.clip(RoundedCornerShape(9.dp)).background(FavoritePink.copy(alpha = .14f))
                        .clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                    color = FavoritePink, fontSize = 12.sp,
                )
            }
            message?.let {
                Text(it, color = FavoritePink, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 4.dp))
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("正在读取收藏夹…", color = FavoriteMuted, fontSize = 12.sp)
                }
                folders.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("没有读到收藏夹，请先登录哔哩哔哩", color = FavoriteMuted, fontSize = 12.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 13.dp, end = 13.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val selected = folder.id in collected
                        val busy = workingFolder == folder.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (selected) FavoritePink.copy(alpha = .13f) else Color.White.copy(alpha = .05f))
                                .clickable(enabled = !busy) {
                                    scope.launch {
                                        workingFolder = folder.id
                                        val inside = withContext(Dispatchers.IO) {
                                            BiliAuth.isInFolder(session.cookieHeader(), folder.id, bvid, maxPages = 3)
                                        }
                                        val ok = withContext(Dispatchers.IO) {
                                            val cookie = session.cookieHeader()
                                            val csrf = session.cookies["bili_jct"].orEmpty()
                                            if (inside) BiliAuth.removeFromFolder(cookie, bvid, folder.id, csrf)
                                            else BiliAuth.addToFolder(cookie, bvid, folder.id, csrf)
                                        }
                                        if (ok) {
                                            collected = if (inside) collected - folder.id else collected + folder.id
                                            message = if (inside) "已取消收藏：${folder.title}" else "已收藏到「${folder.title}」"
                                        } else {
                                            message = if (inside) "取消收藏失败" else "收藏失败，请检查登录状态"
                                        }
                                        workingFolder = null
                                    }
                                }.padding(horizontal = 13.dp, vertical = 11.dp).testTag("bili_player_fav_${folder.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))
                                    .background(FavoritePink.copy(alpha = .15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("番", color = FavoritePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    folder.title,
                                    color = FavoriteText,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("${folder.count} 个内容", color = FavoriteMuted, fontSize = 9.5.sp,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            Text(
                                when {
                                    busy -> "处理中"
                                    selected -> "✓ 已收藏"
                                    else -> "+ 收藏"
                                },
                                color = if (selected) FavoritePink else FavoriteMuted,
                                fontSize = 10.5.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

