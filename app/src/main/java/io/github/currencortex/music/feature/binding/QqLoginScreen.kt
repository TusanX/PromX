// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐登录页：版式与「网易云账号」页保持一致（同样的卡片、二维码区、状态行、底部说明）。
package io.github.currencortex.music.feature.binding

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.core.alt.QqAuth
import io.github.currencortex.music.core.alt.QqAuth.Poll
import io.github.currencortex.music.ui.component.nativeui.GlassActionButton
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import io.github.currencortex.music.ui.component.musicScrollPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun QqLoginScreen(onBack: () -> Unit, onSessionChanged: () -> Unit = {}) {
    var session by remember { mutableStateOf<QqAuth.QrSession?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("等待扫码") }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { io.github.currencortex.music.core.alt.QqSessionStore(context) }
    var account by remember { mutableStateOf(store.account) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    // 取二维码
    LaunchedEffect(generation) {
        busy = true; status = "正在获取二维码…"
        session?.close()
        val s = withContext(Dispatchers.IO) { QqAuth.newQr() }
        session = s
        bitmap = s?.let { q ->
            withContext(Dispatchers.Default) {
                runCatching { android.graphics.BitmapFactory.decodeByteArray(q.image, 0, q.image.size) }.getOrNull()
            }
        }
        status = if (s == null) "二维码获取失败，请重试" else "等待扫码"
        busy = false
    }

    // 轮询扫码结果
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (true) {
            delay(1500)
            val r = withContext(Dispatchers.IO) { QqAuth.poll(s) }
            // 状态文案用自己的措辞，不用 QQ 返回的原始提示
            when (r.state) {
                Poll.WAITING -> status = "等待扫码"
                Poll.SCANNED -> status = "已扫码，请在手机上确认"
                Poll.SUCCESS -> {
                    status = "登录成功，正在保存…"
                    val merged = r.credential?.let { credential ->
                        store.saveCredential(credential)
                        store.account
                    } ?: store.account
                    if (merged == null) {
                        status = "QQ 音乐授权保存失败，请重新扫码"
                        break
                    }
                    account = merged
                    status = "已连接 QQ " + merged.uin
                    onSessionChanged()
                    break
                }
                Poll.EXPIRED -> { status = "二维码已失效，请刷新"; break }
                Poll.UNKNOWN -> Unit
            }
        }
    }

    DisposableEffect(session) {
        val current = session
        onDispose { current?.close() }
    }

    MusicSubpage("QQ 音乐账号", "qq_login_page", onBack, backTag = "qq_login_back") { topInset ->
        LazyColumn(Modifier.fillMaxSize().testTag("qq_login_screen"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, topInset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                AltBindingHero(
                    iconRes = io.github.currencortex.music.R.drawable.brand_qqmusic_official,
                    title = "连接 QQ 音乐",
                    summary = "同步我喜欢与自建歌单，并在 PromX 中播放账号可用的曲目。",
                    accent = Color(0xFF31C27C),
                    features = listOf("热歌榜", "我喜欢", "我的歌单"),
                )
            }
            account?.let { acc ->
                item {
                    SettingsGroup(Modifier.fillMaxWidth().testTag("qq_account_card")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("已连接 QQ 音乐", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("账号 " + acc.uin + " · 账号权限 " +
                                if (store.needsNativeLogin) "旧版网页登录，请重新扫码升级为 QQ 音乐原生登录"
                                else if (store.cookies["qm_keyst"] != null || store.cookies["qqmusic_key"] != null) "已同步"
                                else "仅基础访问", fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                            // 会员曲需要 qm_keyst，扫码登录拿不到；这里支持从浏览器直接粘贴 Cookie
                            var cookieInput by remember { mutableStateOf("") }
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                                top.yukonga.miuix.kmp.basic.TextField(
                                    value = cookieInput,
                                    onValueChange = { cookieInput = it },
                                    modifier = Modifier.fillMaxWidth().testTag("qq_cookie_input"),
                                    label = "粘贴 y.qq.com 的 Cookie（含 qm_keyst）",
                                    singleLine = false,
                                )
                                GlassActionButton("导入 Cookie", {
                                    val parsed = cookieInput.split(';', '\n')
                                        .mapNotNull { part ->
                                            val idx = part.indexOf('=')
                                            if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                                        }
                                        .filter { it.first.isNotEmpty() }
                                        .toMap()
                                    if (parsed.isNotEmpty()) {
                                        store.cookies = store.cookies + parsed
                                        val uin = parsed["uin"] ?: parsed["p_uin"]?.removePrefix("o") ?: acc.uin
                                        store.account = acc.copy(uin = uin)
                                        status = "登录信息已更新，可重新播放会员歌曲"
                                        onSessionChanged()
                                        cookieInput = ""
                                    } else {
                                        status = "没有解析到 Cookie，请检查格式"
                                    }
                                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("qq_cookie_import"))
                            }
                            GlassActionButton(if (probing) "正在检查…" else "检查连接与曲库", {
                                probing = true
                                kotlinx.coroutines.MainScope().launch {
                                    val info = withContext(Dispatchers.IO) {
                                        io.github.currencortex.music.core.alt.QqLibrary.probe(
                                            store.cookies, acc.uin)
                                    }
                                    probeText = info
                                    probing = false
                                }
                            }, modifier = Modifier.fillMaxWidth().testTag("qq_probe"))
                            probeText?.let { Text(it, fontSize = 10.sp, lineHeight = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                                modifier = Modifier.testTag("qq_probe_result")) }
                            GlassActionButton("退出 QQ 登录", {
                                store.clear(); account = null; status = "已退出登录"; onSessionChanged()
                            }, modifier = Modifier.fillMaxWidth().testTag("qq_logout"))
                        }
                    }
                }
            }
            item {
                SettingsGroup(Modifier.fillMaxWidth().testTag("qq_login_card")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("用 QQ 或 QQ 音乐扫一扫", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text("扫描二维码 · 确认授权 · 连接音乐库", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                        Box(
                            Modifier.size(232.dp).clip(RoundedCornerShape(20.dp))
                                .testTag("qq_login_qr"),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = bitmap
                            if (bmp != null) {
                                Image(bmp.asImageBitmap(), contentDescription = "QQ 登录二维码",
                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                    contentScale = ContentScale.Fit)
                            } else {
                                Text(if (busy) "加载中…" else "无二维码", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .45f))
                            }
                        }
                        Text(status, fontSize = 12.sp, modifier = Modifier.testTag("qq_login_status"),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                        GlassActionButton("刷新二维码", { generation++ }, modifier = Modifier
                            .fillMaxWidth().testTag("qq_login_refresh"))
                    }
                }
            }
            item {
                Text("会员歌曲优先播放完整版；没有会员权限时，使用 QQ 音乐提供的官方试听。",
                    fontSize = 12.sp, lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
        }
    }
}
