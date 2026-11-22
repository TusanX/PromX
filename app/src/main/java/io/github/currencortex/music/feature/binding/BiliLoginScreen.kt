// SPDX-License-Identifier: GPL-3.0-only
// B站账号页：版式与「网易云账号」一致，扫码登录后可读头像/昵称/等级与收藏夹。
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.core.alt.BiliSessionStore
import io.github.currencortex.music.ui.component.musicScrollPadding
import io.github.currencortex.music.ui.component.nativeui.GlassActionButton
import io.github.currencortex.music.ui.component.nativeui.MusicSubpage
import io.github.currencortex.music.ui.component.nativeui.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun BiliLoginScreen(
    container: io.github.currencortex.music.AppContainer,
    onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BiliSessionStore(context) }
    var account by remember { mutableStateOf(store.profile) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<BiliAuth.QrCode?>(null) }

    LaunchedEffect(generation) {
        busy = true; status = "正在获取二维码…"
        val qr = withContext(Dispatchers.IO) { BiliAuth.generate() }
        code = qr
        qrBitmap = qr?.let {
            withContext(Dispatchers.Default) {
                Bitmap.createBitmap(qrPixels(it.url), 512, 512, Bitmap.Config.ARGB_8888)
            }
        }
        status = if (qr == null) "二维码获取失败，请重试" else "等待扫码"
        busy = false
    }

    LaunchedEffect(code) {
        val qr = code ?: return@LaunchedEffect
        while (true) {
            delay(1500)
            val r = withContext(Dispatchers.IO) { BiliAuth.poll(qr.key) }
            when (r.state) {
                BiliAuth.Poll.WAITING -> status = "等待扫码"
                BiliAuth.Poll.SCANNED -> status = "已扫码，请在手机上确认"
                BiliAuth.Poll.EXPIRED -> {
                    // 过期自动换一张，用户回来时看到的总是有效二维码
                    status = "二维码已过期，正在刷新…"
                    generation++
                    break
                }
                BiliAuth.Poll.SUCCESS -> {
                    status = "登录成功，正在读取账号…"
                    val profile = withContext(Dispatchers.IO) {
                        // 先补全 Cookie（bili_jct 等由别的域下发），收藏写入需要它算 csrf
                        store.cookies = BiliAuth.completeCookies(r.cookies)
                        val info = BiliAuth.profile(store.cookieHeader())
                        store.profile = info
                        info
                    }
                    // 登录后清掉曲库缓存，否则「歌单/我喜欢」还是登录前的空结果
                    withContext(Dispatchers.Default) { container.libraryRepository.invalidate() }
                    account = profile
                    status = when {
                        profile == null -> "登录成功，但读取账号失败（Cookie 可能不完整）"
                        else -> "已连接 " + profile.uname + " · Lv" + profile.level
                    }
                    break
                }
                BiliAuth.Poll.UNKNOWN -> Unit
            }
        }
    }

    MusicSubpage("哔哩哔哩账号", "bili_login_page", onBack, backTag = "bili_login_back") { topInset ->
        LazyColumn(Modifier.fillMaxSize().testTag("bili_login_screen"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, topInset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                AltBindingHero(
                    iconRes = io.github.currencortex.music.R.drawable.brand_bilibili,
                    title = "连接哔哩哔哩",
                    summary = "同步收藏夹与观看历史，让推荐流更贴近你的日常。登录信息仅保存在本机。",
                    accent = Color(0xFFFB7299),
                    features = listOf("推荐流", "收藏夹", "观看历史"),
                )
            }
            account?.let { p ->
                item {
                    SettingsGroup(Modifier.fillMaxWidth().testTag("bili_account_card")) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (p.face.isNotBlank()) {
                                coil3.compose.AsyncImage(
                                    model = p.face,
                                    contentDescription = "B站头像",
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)),
                                    contentScale = ContentScale.Crop)
                            }
                            Text(p.uname.ifBlank { "B站用户" }, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("Lv" + p.level, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        }
                        GlassActionButton(if (probing) "检测中…" else "检测收藏夹（诊断）", {
                            probing = true
                            kotlinx.coroutines.MainScope().launch {
                                val info = withContext(Dispatchers.IO) {
                                    val cookie = store.cookieHeader()
                                    val mid = store.profile?.mid ?: 0L
                                    val folders = BiliAuth.favoriteFolders(cookie, mid)
                                    val hasJct = store.cookies.containsKey("bili_jct")
                                    if (folders.isEmpty()) "收藏夹：0 个（接口未返回，可能 Cookie 失效）· bili_jct=" + (if (hasJct) "有" else "无")
                                    else "bili_jct=" + (if (hasJct) "有" else "无") + "\n" + folders.take(4).joinToString("\n") { f ->
                                        val items = BiliAuth.favoriteItems(cookie, f.id, 1, 5)
                                        "· ${f.title}（id=${f.id}，共${f.count}）→ ${items.size} 条" +
                                            items.firstOrNull()?.let { "：" + it.title.take(18) }.orEmpty()
                                    }
                                }
                                probeText = info
                                probing = false
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .testTag("bili_probe_folders"))
                        probeText?.let {
                            Text(it, fontSize = 10.sp, lineHeight = 15.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .62f),
                                modifier = Modifier.padding(horizontal = 16.dp).testTag("bili_probe_result"))
                        }
                        GlassActionButton("检查登录状态", {
                            status = "正在检查…"
                            kotlinx.coroutines.MainScope().launch {
                                val info = withContext(Dispatchers.IO) {
                                    val fresh = BiliAuth.profile(store.cookieHeader())
                                    if (fresh != null) store.profile = fresh
                                    fresh
                                }
                                if (info != null) { account = info; status = "登录有效 · " + info.uname }
                                else status = "登录已失效，请重新扫码"
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .testTag("bili_check_login"))
                        GlassActionButton("退出 B站 登录", {
                            store.clear(); account = null; status = "已退出登录"
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp)
                            .testTag("bili_logout"))
                    }
                }
            }
            item {
                SettingsGroup(Modifier.fillMaxWidth().testTag("bili_login_card")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("用哔哩哔哩扫一扫", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Box(Modifier.size(232.dp).clip(RoundedCornerShape(20.dp)).testTag("bili_login_qr"),
                            contentAlignment = Alignment.Center) {
                            val bmp = qrBitmap
                            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "B站登录二维码",
                                modifier = Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
                            else Text(if (busy) "加载中…" else "无二维码", fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .45f))
                        }
                        Text(status, fontSize = 12.sp, modifier = Modifier.testTag("bili_login_status"),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                        GlassActionButton("刷新二维码", { generation++ },
                            modifier = Modifier.fillMaxWidth().testTag("bili_login_refresh"))
                    }
                }
            }
            item {
                Text("收藏夹会显示为歌单；「我喜欢」使用 B站 中名为「我喜欢」的收藏夹，不存在时会自动新建。",
                    fontSize = 12.sp, lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
        }
    }
}
