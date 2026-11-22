package io.github.currencortex.music.feature.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.alt.QqLibrary
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicPullToRefresh
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun QqListeningReportScreen(container: AppContainer, onBack: () -> Unit, onLogin: () -> Unit) {
    val account = container.qqSession.account
    val signedIn = container.qqSession.loggedIn
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember(account?.uin, signedIn, refresh) { mutableStateOf(signedIn) }
    var report by remember(account?.uin, signedIn, refresh) { mutableStateOf<QqLibrary.ListeningReport?>(null) }
    var error by remember(account?.uin, signedIn, refresh) { mutableStateOf<String?>(null) }
    val bottomInset = LocalMusicBottomInset.current
    LaunchedEffect(account?.uin, signedIn, refresh) {
        if (!signedIn) {
            loading = false
            error = "登录 QQ 音乐后查看账号听歌报告"
            return@LaunchedEffect
        }
        loading = true
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            QqLibrary.listeningReport(container.qqSession.cookies, container.qqSession.uin)
        }
        report = result
        loading = false
        error = if (result == null) "QQ 音乐暂未生成听歌报告，或账号状态需要重新同步" else null
    }
    MusicSubpage("QQ 音乐听歌报告", "qq_listening_report", onBack) { topInset ->
        MusicPullToRefresh(loading, { refresh++ }, Modifier.fillMaxSize(), enabled = signedIn) {
            LazyColumn(Modifier.fillMaxSize().testTag("qq_listening_report_content"),
                contentPadding = PaddingValues(start = 16.dp, top = topInset + 20.dp,
                    end = 16.dp, bottom = 32.dp + bottomInset),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("account") {
                    SettingsGroup(Modifier.testTag("qq_listening_report_account")) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val avatar = report?.avatar?.ifBlank { account?.avatar.orEmpty() }.orEmpty()
                            if (avatar.isNotBlank()) AsyncImage(avatar, null,
                                Modifier.size(62.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            else Box(Modifier.size(62.dp).clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = .14f)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(report?.nickname?.ifBlank { account?.display.orEmpty() }
                                    ?: account?.display.orEmpty(), fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(report?.signature?.ifBlank { "QQ 音乐账号听歌报告" }
                                    ?: "QQ 音乐账号听歌报告", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (loading && report == null) item("loading") {
                    SettingsGroup { Text("正在读取 QQ 音乐听歌报告…", Modifier.padding(20.dp), fontSize = 14.sp) }
                }
                error?.let { message -> item("error") {
                    SettingsGroup(Modifier.testTag("qq_listening_report_error")) {
                        SettingsHint(message, error = report == null)
                        MusicTextAction(if (signedIn) "重新加载" else "登录 QQ 音乐",
                            { if (signedIn) refresh++ else onLogin() }, Modifier.padding(start = 16.dp, bottom = 12.dp))
                    }
                } }
                report?.months?.takeIf(List<*>::isNotEmpty)?.let { months ->
                    item("months") {
                        SettingsGroup(Modifier.testTag("qq_listening_months")) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("最近听歌", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                val maximum = months.maxOfOrNull { it.count }?.coerceAtLeast(1L) ?: 1L
                                months.takeLast(6).forEach { month ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("${month.month} 月", Modifier.width(42.dp), fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        Box(Modifier.weight(1f).height(8.dp).background(
                                            MiuixTheme.colorScheme.onSurface.copy(alpha = .07f), RoundedCornerShape(99.dp))) {
                                            Box(Modifier.fillMaxWidth((month.count.toFloat() / maximum).coerceIn(.04f, 1f))
                                                .fillMaxHeight().background(MiuixTheme.colorScheme.primary.copy(alpha = .78f),
                                                    RoundedCornerShape(99.dp)))
                                        }
                                        Text("${month.count} 首", Modifier.width(58.dp), fontSize = 12.sp,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                report?.cards?.takeIf(List<*>::isNotEmpty)?.let { cards ->
                    item("profile") {
                        SettingsGroup(Modifier.testTag("qq_listening_profile")) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                                Text("听歌画像", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 8.dp))
                                cards.forEachIndexed { index, card ->
                                    if (index > 0) HorizontalDivider(
                                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .07f))
                                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                        .testTag("qq_listening_${card.key}_${card.value}"),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Column(Modifier.width(82.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(card.title.ifBlank { "偏好" }, fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                            Text(card.value, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(card.summary.ifBlank { "来自最近一段时间的听歌记录" },
                                            Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                report?.description?.takeIf(String::isNotBlank)?.let { description ->
                    item("description") {
                        SettingsGroup(Modifier.testTag("qq_listening_description")) {
                            Text(description, Modifier.padding(18.dp), fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                }
                item("note") {
                    Text("报告来自 QQ 音乐账号。新开通会员或刚产生播放记录时，腾讯侧数据可能延迟更新。",
                        Modifier.padding(horizontal = 4.dp), fontSize = 11.sp, lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}
