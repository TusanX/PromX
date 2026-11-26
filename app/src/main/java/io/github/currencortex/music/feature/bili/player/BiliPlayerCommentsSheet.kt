// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.core.alt.BiliPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun BiliPlayerCommentsSheet(
    aid: String,
    cookie: String,
    csrf: String,
    total: Long,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var hot by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(1) }
    var comments by remember(aid) { mutableStateOf<List<BiliPlayback.Comment>>(emptyList()) }
    var loading by remember(aid) { mutableStateOf(true) }
    var loadingMore by remember(aid) { mutableStateOf(false) }
    var hasMore by remember(aid) { mutableStateOf(false) }
    var input by remember(aid) { mutableStateOf("") }
    var sending by remember(aid) { mutableStateOf(false) }
    var message by remember(aid) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(aid, hot, revision) {
        if (aid.isBlank()) return@LaunchedEffect
        loading = true
        page = 1
        val result = withContext(Dispatchers.IO) { BiliPlayback.comments(aid, hot, 1, 20, cookie) }
        comments = result.comments
        hasMore = result.more
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .56f))) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.88f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF17181D)).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("评论", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (total > 0) "  ${compactCount(total)}" else "",
                    color = CommentMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                CommentSortChip("热门", hot) { hot = true }
                Spacer(Modifier.width(6.dp))
                CommentSortChip("最新", !hot) { hot = false }
                Spacer(Modifier.width(10.dp))
                Text(
                    "关闭",
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color(0xFFB8BBC2),
                    fontSize = 11.sp,
                )
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = .055f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(2_000) },
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("bili_comment_input"),
                    textStyle = TextStyle(color = Color(0xFFF5F6F9), fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFFFB7299)),
                    decorationBox = { inner ->
                        if (input.isBlank()) Text("说点什么…", color = Color(0xFF8F949E), fontSize = 13.sp)
                        inner()
                    },
                )
                Text(
                    if (sending) "发送中" else "发送",
                    Modifier.clip(RoundedCornerShape(9.dp))
                        .background(if (input.isBlank() || sending) Color.White.copy(alpha = .07f) else Color(0xFFFB7299))
                        .clickable(enabled = input.isNotBlank() && !sending) {
                            if (cookie.isBlank() || csrf.isBlank()) {
                                message = "请先登录哔哩哔哩"
                                return@clickable
                            }
                            val body = input.trim()
                            sending = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    BiliPlayback.sendComment(aid, body, null, cookie, csrf)
                                }
                                sending = false
                                if (ok) {
                                    input = ""
                                    message = "评论已发送"
                                    revision++
                                } else message = "发送失败，请检查登录状态或评论权限"
                            }
                        }
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            message?.let {
                Text(it, color = Color(0xFFFB7299), fontSize = 11.sp)
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("正在读取评论…", color = CommentMuted, fontSize = 13.sp)
                }
                comments.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("还没有评论", color = CommentMuted, fontSize = 13.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(comments, key = { it.id.ifBlank { it.user + it.content } }) { comment ->
                        CommentRow(comment) { target ->
                            val previous = comments
                            comments = comments.map {
                                if (it.id == comment.id) it.copy(
                                    likedByMe = target,
                                    likes = (it.likes + if (target) 1 else -1).coerceAtLeast(0L),
                                ) else it
                            }
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    BiliPlayback.likeComment(aid, comment.id, target, cookie, csrf)
                                }
                                if (!ok) {
                                    comments = previous
                                    message = "评论点赞失败"
                                }
                            }
                        }
                    }
                    item("more") {
                        if (hasMore) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 42.dp)
                                    .clickable(enabled = !loadingMore) {
                                        loadingMore = true
                                        scope.launch {
                                            val nextPage = page + 1
                                            val result = withContext(Dispatchers.IO) {
                                                BiliPlayback.comments(aid, hot, nextPage, 20, cookie)
                                            }
                                            val known = comments.mapTo(mutableSetOf()) { it.id }
                                            comments = comments + result.comments.filter { it.id !in known }
                                            page = nextPage
                                            hasMore = result.more
                                            loadingMore = false
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (loadingMore) "加载中…" else "加载更多评论",
                                    color = Color(0xFFFB7299),
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: BiliPlayback.Comment, onLike: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (comment.avatar.isNotBlank()) {
                AsyncImage(
                    comment.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else Text(comment.user.take(1).ifBlank { "B" }, color = Color.White, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.user,
                    color = Color(0xFFB8BBC2),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (comment.level > 0) {
                    Text("  Lv${comment.level}", color = Color(0xFFFB7299), fontSize = 9.sp)
                }
            }
            Text(
                comment.content,
                color = Color(0xFFF5F6F9),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (comment.replyCount > 0) {
                    Text("${comment.replyCount} 条回复", color = CommentMuted, fontSize = 10.sp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    (if (comment.likedByMe) "已赞" else "赞") + if (comment.likes > 0) " ${compactCount(comment.likes)}" else "",
                    color = if (comment.likedByMe) Color(0xFFFB7299) else CommentMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { onLike(!comment.likedByMe) },
                )
            }
        }
    }
}

@Composable
private fun CommentSortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFFB7299).copy(alpha = .16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        color = if (selected) Color(0xFFFB7299) else CommentMuted,
        fontSize = 11.sp,
    )
}

private val CommentMuted = Color(0xFF9297A1)

private fun compactCount(value: Long): String = when {
    value >= 100_000_000 -> compactNumber(value / 100_000_000f) + "亿"
    value >= 10_000 -> compactNumber(value / 10_000f) + "万"
    else -> value.toString()
}

private fun compactNumber(value: Float): String =
    "%.1f".format(value).trimEnd('0').trimEnd('.')
