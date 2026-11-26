// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.core.alt.BiliPlayback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

private val InfoPink = Color(0xFFFB7299)
private val InfoText = Color(0xFFF5F6F9)
private val InfoMuted = Color(0xFF969BA5)
private val InfoSurface = Color(0xFF111216)

private enum class InfoGlyph { LIKE, COMMENT, FAVORITE, DOWNLOAD, SHARE }

@Composable
internal fun BiliPlayerInfo(
    state: BiliPlayerUiState,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    downloading: Boolean,
    downloadProgress: Float,
    onPart: (BiliPlayback.Page) -> Unit,
    onRelated: (BiliPlayback.RelatedVideo) -> Unit,
    onCreator: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    var descriptionExpanded by remember(info?.cid) { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize().background(InfoSurface),
        contentPadding = PaddingValues(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item("title") {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 13.dp)) {
                Text(
                    info?.title ?: "视频加载中",
                    color = InfoText,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("bili_player_title"),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val stats = state.stats
                    Text("${compact(stats?.view ?: 0L)} 播放", color = InfoMuted, fontSize = 10.5.sp)
                    MetaDot()
                    Text("${compact(stats?.danmaku ?: 0L)} 弹幕", color = InfoMuted, fontSize = 10.5.sp)
                    info?.publishedAt?.takeIf { it > 0L }?.let {
                        MetaDot()
                        Text(formatDate(it), color = InfoMuted, fontSize = 10.5.sp)
                    }
                }
            }
        }
        item("owner") {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = (info?.ownerMid ?: 0L) > 0L) {
                    (info?.ownerMid ?: 0L).takeIf { it > 0L }?.let(onCreator)
                }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(InfoPink.copy(alpha = .18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!info?.ownerFace.isNullOrBlank()) {
                        AsyncImage(
                            info?.ownerFace,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(info?.owner.orEmpty().take(1).ifBlank { "UP" }, color = InfoPink, fontSize = 13.sp)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        info?.owner?.ifBlank { "哔哩哔哩 UP 主" } ?: if (state.metadataLoading) "正在读取视频信息" else "哔哩哔哩 UP 主",
                        color = InfoText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        info?.category?.takeIf { it.isNotBlank() }?.let { "$it · 哔哩哔哩" } ?: "哔哩哔哩视频",
                        color = InfoMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                if ((info?.ownerMid ?: 0L) > 0L) {
                    Text(
                        "查看主页",
                        color = InfoPink,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    )
                }
            }
        }
        item("actions") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                InfoAction(
                    label = if (state.liked) "已点赞" else "点赞",
                    count = compact(state.stats?.like ?: 0L),
                    selected = state.liked,
                    glyph = InfoGlyph.LIKE,
                    tag = "bili_player_like",
                    onClick = onLike,
                )
                InfoAction("评论", compact(state.stats?.reply ?: 0L), false, InfoGlyph.COMMENT, "bili_player_comments", onComments)
                InfoAction("收藏", compact(state.stats?.favorite ?: 0L), false, InfoGlyph.FAVORITE, "bili_player_favorite", onFavorite)
                InfoAction(if (downloading) "${(downloadProgress * 100).toInt()}%" else "下载", "", false, InfoGlyph.DOWNLOAD, "bili_player_download", onDownload)
                InfoAction("分享", "", false, InfoGlyph.SHARE, "bili_player_share", onShare)
            }
        }
        if (!info?.description.isNullOrBlank()) item("description") {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    info?.description.orEmpty(),
                    color = InfoText.copy(alpha = .82f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if ((info?.description?.length ?: 0) > 70) {
                    Text(
                        if (descriptionExpanded) "收起" else "展开简介",
                        color = InfoMuted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 5.dp).clickable { descriptionExpanded = !descriptionExpanded },
                    )
                }
            }
        }
        if ((info?.pages?.size ?: 0) > 1) item("parts") {
            Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                Text(
                    "分P · ${info?.pages?.size} 个视频",
                    color = InfoText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    info?.pages.orEmpty().forEach { page ->
                        val selected = page.cid == state.cid
                        Column(
                            Modifier.width(132.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (selected) InfoPink.copy(alpha = .16f) else Color.White.copy(alpha = .055f))
                                .clickable { onPart(page) }.padding(9.dp),
                        ) {
                            Text(
                                "P${page.page}  ${page.title}",
                                color = if (selected) InfoPink else InfoText,
                                fontSize = 10.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item("comments_entry") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onComments)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoGlyphIcon(InfoGlyph.COMMENT, InfoMuted, Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text("评论区", color = InfoText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${compact(state.stats?.reply ?: 0L)} 条评论 · 查看完整讨论",
                        color = InfoMuted,
                        fontSize = 10.5.sp,
                    )
                }
                Text("查看", color = InfoPink, fontSize = 11.sp)
            }
        }
        if (state.related.isNotEmpty()) item("related_title") {
            Text(
                "相关推荐",
                color = InfoText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 11.dp, bottom = 7.dp),
            )
        }
        if (state.related.isNotEmpty()) item("related") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.related.take(12), key = { it.bvid }) { video ->
                    RelatedCard(video) { onRelated(video) }
                }
            }
        }
        if (state.metadataLoading) item("loading") {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(17.dp), size = 17.dp, strokeWidth = 2.dp)
                Text(" 正在整理相关推荐与评论", color = InfoMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InfoAction(
    label: String,
    count: String,
    selected: Boolean,
    glyph: InfoGlyph,
    tag: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(62.dp).clip(RoundedCornerShape(10.dp)).testTag(tag)
            .clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        InfoGlyphIcon(glyph, if (selected) InfoPink else InfoText.copy(alpha = .84f), Modifier.size(22.dp))
        Text(
            if (count.isNotBlank() && count != "0") count else label,
            color = if (selected) InfoPink else InfoMuted,
            fontSize = 9.5.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun InfoGlyphIcon(glyph: InfoGlyph, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        when (glyph) {
            InfoGlyph.LIKE -> {
                val path = Path().apply {
                    moveTo(size.width * .50f, size.height * .82f)
                    cubicTo(size.width * .18f, size.height * .62f, size.width * .10f, size.height * .40f,
                        size.width * .24f, size.height * .24f)
                    cubicTo(size.width * .36f, size.height * .10f, size.width * .47f, size.height * .22f,
                        size.width * .50f, size.height * .32f)
                    cubicTo(size.width * .54f, size.height * .22f, size.width * .65f, size.height * .10f,
                        size.width * .77f, size.height * .24f)
                    cubicTo(size.width * .90f, size.height * .40f, size.width * .82f, size.height * .62f,
                        size.width * .50f, size.height * .82f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            InfoGlyph.COMMENT -> {
                drawRoundRect(
                    tint,
                    topLeft = Offset(size.width * .10f, size.height * .17f),
                    size = Size(size.width * .80f, size.height * .58f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(tint, Offset(size.width * .35f, size.height * .77f), Offset(size.width * .30f, size.height * .90f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .30f, size.height * .90f), Offset(size.width * .52f, size.height * .75f), stroke, StrokeCap.Round)
            }
            InfoGlyph.FAVORITE -> {
                val path = Path().apply {
                    moveTo(size.width * .22f, size.height * .15f)
                    lineTo(size.width * .78f, size.height * .15f)
                    lineTo(size.width * .78f, size.height * .87f)
                    lineTo(size.width * .50f, size.height * .70f)
                    lineTo(size.width * .22f, size.height * .87f)
                    close()
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            InfoGlyph.DOWNLOAD -> {
                drawLine(tint, Offset(size.width * .50f, size.height * .12f), Offset(size.width * .50f, size.height * .64f), stroke, StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(size.width * .31f, size.height * .46f)
                    lineTo(size.width * .50f, size.height * .66f)
                    lineTo(size.width * .69f, size.height * .46f)
                }
                drawPath(arrow, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(tint, Offset(size.width * .22f, size.height * .83f), Offset(size.width * .78f, size.height * .83f), stroke, StrokeCap.Round)
            }
            InfoGlyph.SHARE -> {
                val left = Offset(size.width * .25f, size.height * .50f)
                val top = Offset(size.width * .73f, size.height * .23f)
                val bottom = Offset(size.width * .73f, size.height * .77f)
                drawLine(tint, left, top, stroke, StrokeCap.Round)
                drawLine(tint, left, bottom, stroke, StrokeCap.Round)
                drawCircle(tint, radius = 2.3.dp.toPx(), center = left, style = Stroke(stroke))
                drawCircle(tint, radius = 2.3.dp.toPx(), center = top, style = Stroke(stroke))
                drawCircle(tint, radius = 2.3.dp.toPx(), center = bottom, style = Stroke(stroke))
            }
        }
    }
}

@Composable
private fun RelatedCard(video: BiliPlayback.RelatedVideo, onClick: () -> Unit) {
    Column(
        Modifier.width(184.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .testTag("bili_related_${video.bvid}"),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = .06f)),
        ) {
            AsyncImage(
                video.cover,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                timeText(video.durationSec * 1_000L),
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            video.title,
            color = InfoText,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            video.owner,
            color = InfoMuted,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun MetaDot() {
    Box(Modifier.size(3.dp).clip(CircleShape).background(InfoMuted.copy(alpha = .5f)))
}

internal fun compact(value: Long): String = when {
    value >= 100_000_000L -> decimal(value / 100_000_000f) + "亿"
    value >= 10_000L -> decimal(value / 10_000f) + "万"
    else -> value.toString()
}

private fun decimal(value: Float): String =
    "%.1f".format(value).trimEnd('0').trimEnd('.')

private fun formatDate(seconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(seconds * 1_000L))
