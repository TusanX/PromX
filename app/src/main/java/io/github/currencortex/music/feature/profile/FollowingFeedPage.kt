package io.github.currencortex.music.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.currencortex.music.data.library.ArtistRelease
import io.github.currencortex.music.data.library.FollowedArtist
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.LoadingSongList
import io.github.currencortex.music.ui.component.MusicPullToRefresh
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.nativeui.MusicArtworkCard
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.SettingsListHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val releaseDate = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** Followed NetEase artists and their newest release. Shows no square/community content. */
@Composable
internal fun FollowingFeedPage(vm: FollowingViewModel, navigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val account by vm.container.neteaseSessions.state.collectAsStateWithLifecycle()
    val bottom = LocalMusicBottomInset.current
    MusicPullToRefresh(state.loading && state.artists.isNotEmpty(), vm::reload, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("discover_following"),
            contentPadding = PaddingValues(start = 16.dp, top = 22.dp, end = 16.dp, bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("following_intro") {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("网易云关注动态", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("关注音乐人的新专辑、单曲与近期发布", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
            }

            if (!account.loggedIn || account.stale) {
                item("following_login") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (account.stale) "网易云登录已失效" else "连接网易云音乐",
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("登录后可查看你关注的音乐人和他们最近发布的内容。", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .58f))
                            MusicTextAction("去连接网易云", { navigate("user/binding") }, Modifier.testTag("following_login_action"))
                        }
                    }
                }
                return@LazyColumn
            }

            if (state.loading && !state.loaded) item("following_loading") { LoadingSongList(4) }
            state.error?.let { message ->
                item("following_error") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("关注动态加载失败", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                            MusicTextAction("重新加载", vm::reload, Modifier.testTag("following_retry"))
                        }
                    }
                }
            }

            if (state.loaded && state.error == null && state.artists.isEmpty()) {
                item("following_empty") {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有关注音乐人", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("在网易云音乐关注喜欢的音乐人后，他们的新作品会出现在这里。", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                        }
                    }
                }
            }

            if (state.artists.isNotEmpty()) {
                item("following_artists_header") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SettingsListHeader("关注音乐人", Modifier.weight(1f))
                        Text("${state.totalArtists} 位", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .45f))
                    }
                }
                item("following_artists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.artists, key = { "following_artist_${it.id}" }) { artist ->
                            FollowedArtistItem(artist) { navigate("lib/artist/${artist.id}") }
                        }
                    }
                }
            }

            if (state.releases.isNotEmpty()) {
                item("following_releases_header") { SettingsListHeader("最新发布") }
                itemsIndexed(state.releases.chunked(2), key = { index, _ -> "following_release_row_$index" }) { _, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { release ->
                            ReleaseCard(release, Modifier.weight(1f)) { navigate("lib/album/${release.album.id}") }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else if (state.loaded && state.artists.isNotEmpty() && !state.loading) {
                item("following_releases_empty") {
                    Text("暂时没有读取到新发布内容，下拉刷新试试。", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                }
            }
        }
    }
}

@Composable
private fun FollowedArtistItem(artist: FollowedArtist, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(artist.cover) {
        ImageRequest.Builder(context).data(artist.cover).size(160).build()
    }
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .testTag("following_artist_${artist.id}").padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AsyncImage(request, artist.name, Modifier.size(64.dp).clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer), contentScale = ContentScale.Crop)
        Text(artist.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface)
        Text("${artist.albumTotal} 张专辑", fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .42f))
    }
}

@Composable
private fun ReleaseCard(release: ArtistRelease, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val date = release.album.publishTime.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(releaseDate)
    }
    MusicArtworkCard(
        cover = release.album.cover.ifBlank { release.artist.cover },
        title = release.album.name,
        subtitle = listOf(release.artist.name, date, release.album.type.takeIf { it.isNotBlank() })
            .filterNotNull().filter(String::isNotBlank).joinToString(" · "),
        onClick = onClick,
        modifier = modifier.testTag("following_release_${release.album.id}"),
    )
}
