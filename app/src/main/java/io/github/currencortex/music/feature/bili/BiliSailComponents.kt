// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.ui.component.MusicIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

internal val BiliSailPink = Color(0xFFFB7299)
internal val BiliSailBlue = Color(0xFF00AEEC)
internal val BiliSailText = Color(0xFFF4F5F7)
internal val BiliSailMuted = Color(0xFFA8ACB3)
internal val BiliSailCanvas = Color(0xFF08090B)
internal val BiliSailSurface = Color(0xFF17181B)
internal val BiliSailDivider = Color(0xFF2A2B2F)

internal sealed interface BiliSailLoad<out T> {
    data object Loading : BiliSailLoad<Nothing>
    data class Ready<T>(val value: T) : BiliSailLoad<T>
    data class Failed(val message: String) : BiliSailLoad<Nothing>
}

/** The compact BiliSail-style workspace header. Page content owns its own visual language. */
@Composable
internal fun BiliSailTopBar(
    title: String,
    signedIn: Boolean,
    profileFace: String,
    onSearch: (() -> Unit)? = null,
    onMessages: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(58.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(31.dp).clip(RoundedCornerShape(8.dp)).background(BiliSailPink),
            contentAlignment = Alignment.Center,
        ) {
            BiliSailTelevisionGlyph(Modifier.size(19.dp), Color.White)
        }
        Text(
            title,
            Modifier.padding(start = 10.dp).weight(1f),
            color = BiliSailText,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onRefresh?.let {
            BiliSailHeaderAction("刷新内容", MusicIcons.Update, it, "bili_sail_refresh")
        }
        onSearch?.let {
            BiliSailHeaderAction("搜索哔哩哔哩", MusicIcons.Search, it, "bili_sail_search")
        }
        onMessages?.let {
            BiliSailHeaderAction("消息", MusicIcons.Comment, it, "bili_sail_messages")
        }
        onAccount?.let {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClick = it)
                    .semantics { contentDescription = if (signedIn) "哔哩哔哩账号" else "登录哔哩哔哩" }
                    .testTag("bili_sail_account"),
                contentAlignment = Alignment.Center,
            ) {
                if (profileFace.isNotBlank()) {
                    AsyncImage(
                        profileFace,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Icon(MusicIcons.Account, null, Modifier.size(23.dp), tint = BiliSailText.copy(alpha = .82f))
                }
            }
        }
    }
}

@Composable
private fun BiliSailHeaderAction(
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = BiliSailText.copy(alpha = .82f))
    }
}

@Composable
internal fun BiliSailSearchEntry(query: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(42.dp)
            .clip(RoundedCornerShape(12.dp)).background(BiliSailSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "搜索视频、番剧或 UP 主" }
            .testTag("bili_sail_search_entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MusicIcons.Search, null, Modifier.padding(start = 13.dp).size(18.dp), tint = BiliSailMuted)
        Text(
            query.ifBlank { "搜索视频、番剧或 UP 主" },
            Modifier.padding(horizontal = 10.dp),
            color = if (query.isBlank()) BiliSailMuted else BiliSailText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BiliSailChannelStrip(
    channels: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().testTag("bili_sail_channels"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(channels, key = { it }) { channel ->
            val active = channel == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (active) BiliSailPink else BiliSailSurface)
                    .clickable(role = Role.Tab) { onSelected(channel) }
                    .padding(horizontal = 13.dp, vertical = 8.dp)
                    .testTag("bili_sail_channel_$channel"),
            ) {
                Text(
                    channel,
                    color = if (active) Color.White else BiliSailMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun BiliSailLoginBanner(onLogin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(10.dp))
            .background(BiliSailSurface).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)),
            contentAlignment = Alignment.Center) {
            Icon(MusicIcons.Account, null, Modifier.size(21.dp), tint = BiliSailMuted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("登录哔哩哔哩", color = BiliSailText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("同步关注、收藏、历史与兴趣推荐", color = BiliSailMuted, fontSize = 10.5.sp)
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(BiliSailPink)
                .clickable(role = Role.Button, onClick = onLogin)
                .padding(horizontal = 13.dp, vertical = 7.dp).testTag("bili_sail_login"),
        ) { Text("登录", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun BiliSailSectionTitle(title: String, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = BiliSailText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        detail?.let { Text(it, color = BiliSailMuted, fontSize = 11.sp) }
    }
}

@Composable
internal fun BiliSailVideoCard(
    video: BiliHubRepository.Video,
    onClick: () -> Unit,
    onCreator: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag("bili_sail_video_${video.bvid}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(BiliSailSurface)) {
            AsyncImage(
                video.cover,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.durationSec > 0) {
                Text(
                    BiliHubRepository.durationLabel(video.durationSec),
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .72f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
            val badge = video.badge.ifBlank { video.recommendationReason }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(BiliSailPink.copy(alpha = .92f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .15f))),
                ),
            )
        }
        Text(
            video.title,
            Modifier.padding(top = 7.dp),
            color = BiliSailText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                video.uploader.ifBlank { "哔哩哔哩 UP 主" },
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onCreator),
                color = BiliSailMuted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (video.views > 0) Text(
                BiliHubRepository.compactCount(video.views) + " 播放",
                color = BiliSailMuted.copy(alpha = .78f),
                fontSize = 9.5.sp,
            )
        }
    }
}

@Composable
internal fun BiliSailVideoListCard(
    video: BiliHubRepository.Video,
    onClick: () -> Unit,
    onCreator: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.width(154.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp)).background(BiliSailSurface)) {
            AsyncImage(video.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video.durationSec > 0) Text(
                BiliHubRepository.durationLabel(video.durationSec),
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = .7f)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 9.sp,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(video.title, color = BiliSailText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(video.uploader.ifBlank { "哔哩哔哩 UP 主" },
                Modifier.clickable(role = Role.Button, onClick = onCreator),
                color = BiliSailMuted, fontSize = 10.5.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (video.views > 0) Text(BiliHubRepository.compactCount(video.views) + " 播放", color = BiliSailMuted.copy(alpha = .72f), fontSize = 9.5.sp)
                if (video.comments > 0) Text(BiliHubRepository.compactCount(video.comments) + " 弹幕", color = BiliSailMuted.copy(alpha = .72f), fontSize = 9.5.sp)
            }
        }
    }
}

@Composable
internal fun BiliSailLiveCard(room: BiliHubRepository.LiveRoom, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(BiliSailSurface)) {
            AsyncImage(room.cover, room.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(BiliSailPink).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                Text("LIVE", Modifier.padding(start = 4.dp), color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            }
            if (room.online > 0) Text(
                BiliHubRepository.compactCount(room.online) + " 人气",
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = .66f)).padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 9.sp,
            )
        }
        Text(room.title, Modifier.padding(top = 7.dp), color = BiliSailText, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
        Text(room.anchor + if (room.area.isBlank()) "" else " · " + room.area,
            Modifier.padding(top = 3.dp), color = BiliSailMuted, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
internal fun BiliSailBangumiCard(item: BiliHubRepository.Bangumi, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(BiliSailSurface)) {
            AsyncImage(item.cover, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.badge.isNotBlank()) Text(item.badge,
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = .68f)).padding(horizontal = 5.dp, vertical = 2.dp),
                color = Color.White, fontSize = 9.sp)
            if (item.rating.isNotBlank()) Text(item.rating,
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                color = Color(0xFFFFC94A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(item.title, Modifier.padding(top = 7.dp), color = BiliSailText, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.progress.isNotBlank()) Text(item.progress, Modifier.padding(top = 3.dp),
            color = BiliSailMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun BiliSailLoadingCards(count: Int = 4) {
    repeat(count) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = .055f)))
            Box(Modifier.fillMaxWidth(.84f).padding(top = 8.dp).height(12.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .05f)))
            Box(Modifier.fillMaxWidth(.48f).padding(top = 6.dp).height(9.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .04f)))
        }
    }
}

@Composable
internal fun BiliSailError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = BiliSailMuted, fontSize = 12.5.sp, lineHeight = 18.sp)
        Text(
            "重新加载",
            Modifier.clip(RoundedCornerShape(8.dp)).background(BiliSailSurface)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = BiliSailText,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun BiliSailTelevisionGlyph(modifier: Modifier, tint: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 1.9.dp.toPx()
        drawRoundRect(
            tint,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * .10f, size.height * .25f),
            size = androidx.compose.ui.geometry.Size(size.width * .80f, size.height * .56f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * .30f, size.height * .12f),
            androidx.compose.ui.geometry.Offset(size.width * .43f, size.height * .25f), stroke,
            androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * .70f, size.height * .12f),
            androidx.compose.ui.geometry.Offset(size.width * .57f, size.height * .25f), stroke,
            androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
