// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.data.radio.FreeRadioRepository
import io.github.currencortex.music.ui.component.MusicCover
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.nativeui.GlassActionButton
import io.github.currencortex.music.ui.component.nativeui.GlassSearchField
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Public internet radio in the same list language as the rest of PromX. */
@Composable
fun FreeRadioScreen(
    vm: FreeRadioViewModel,
    onBack: () -> Unit,
    onPlay: (FreeRadioRepository.Station) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    MusicSubpage("自由电台", "free_radio", onBack) { topInset ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("free_radio_list"),
            contentPadding = PaddingValues(top = topInset + 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                        GlassSearchField(
                            value = state.query,
                            onValueChange = vm::query,
                            modifier = Modifier.weight(1f),
                            requester = focus,
                            inputTag = "free_radio_search",
                            placeholder = "搜索电台、节目或风格",
                            onSubmit = vm::submit,
                            submitTag = "free_radio_submit",
                        )
                        if (state.query.isNotBlank()) {
                            Box(Modifier.padding(end = 8.dp)) {
                                GlassActionButton("搜索", vm::submit, fillWidth = false, minHeight = 36.dp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.query.isBlank()) "大家都在听" else "搜索结果",
                            Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .65f),
                        )
                        Box(Modifier.clickable(enabled = !state.loading, onClick = vm::refresh)
                            .padding(horizontal = 8.dp, vertical = 6.dp).testTag("free_radio_refresh")) {
                            Icon(MusicIcons.Update, "刷新", Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                        }
                    }
                }
            }
            if (state.loading) item {
                Text("正在连接公开电台…", Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
            state.error?.let { error -> item {
                Text(error, Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
            } }
            items(state.stations, key = { it.id }) { station ->
                StationRow(station) { onPlay(station) }
            }
            if (state.stations.isNotEmpty()) item {
                Text(
                    "由 Radio Browser 索引，节目与直播流由各电台提供。",
                    Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    fontSize = 11.sp, lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .38f),
                )
            }
        }
    }
}

@Composable
private fun StationRow(station: FreeRadioRepository.Station, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp).testTag("free_radio_${station.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(station.favicon, Modifier.size(50.dp), cornerRadius = 14.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(station.name, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    station.country.takeIf { it.isNotBlank() },
                    station.tags.substringBefore(',').takeIf { it.isNotBlank() },
                    station.codec.uppercase().takeIf { it.isNotBlank() },
                    station.bitrate.takeIf { it > 0 }?.let { "${it}kbps" },
                ).joinToString(" · "),
                Modifier.padding(top = 3.dp), fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .52f),
            )
        }
        Icon(MusicIcons.Play, "播放电台", Modifier.size(21.dp), tint = MiuixTheme.colorScheme.primary)
    }
}
