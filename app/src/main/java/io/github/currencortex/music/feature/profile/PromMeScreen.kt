// SPDX-License-Identifier: GPL-3.0-only
// Prom 账号的「我的」页：本地账号登录 / 已登录资料卡 + 我的音乐。
// 与 CurrentMusic 账号体系完全独立；不联网也能用。
package io.github.currencortex.music.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.feature.promaccount.PromAccountStore
import io.github.currencortex.music.feature.promaccount.PromRemoteApi
import io.github.currencortex.music.feature.promaccount.PromServerConfig
import io.github.currencortex.music.feature.promaccount.applyRemoteLogin
import io.github.currencortex.music.feature.promaccount.PromSync
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import io.github.currencortex.music.ui.component.nativeui.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable fun PromMeScreen(navigate: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(PromAccountStore.state.value) }
    LaunchedEffect(Unit) { PromAccountStore.load(context); profile = PromAccountStore.state.value }
    ProfilePageLayout(navigate, onSettings) { topInset ->
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            if (profile == null) {
                PromLoginForm(onSuccess = { profile = it })
            } else {
                SettingsListSection("Prom 账号") {
                    ProfileDestination(profile!!.name, "本机账号 · ${profile!!.uid}", io.github.currencortex.music.ui.component.MusicIcons.Account,
                        "prom_account_row") { }
                    SettingsDivider(52.dp)
                    ProfileDestination("退出登录", "保留本地数据，下次可再登录", io.github.currencortex.music.ui.component.MusicIcons.Close,
                        "prom_logout") {
                        PromAccountStore.logout(context); profile = null
                    }
                }
            }
            ProfileLibrarySection(navigate)
            SettingsListSection("音乐服务") {
                ProfileDestination("登录网易云", "扫码绑定网易云，解锁播放与推荐",
                    io.github.currencortex.music.ui.component.MusicIcons.Account, "prom_netease_login") { navigate("user/binding") }
                SettingsDivider(52.dp)
                ProfileDestination("导入歌单", "链接、文字、图片与本地歌曲",
                    io.github.currencortex.music.ui.component.MusicIcons.Transfer, "prom_import") { navigate("user/playlist-import") }
            }
        }
    }
}

@Composable private fun PromLoginForm(onSuccess: (io.github.currencortex.music.feature.promaccount.PromProfile) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(PromServerConfig.base(context)) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val remote = base.isNotBlank()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("登录 Prom", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text("本机账号：不需要网络，不依赖任何第三方服务。昵称与密码只保存在这台设备上（密码存加盐哈希）。",
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
        TextField(value = base, onValueChange = { base = it; message = null }, label = "服务器地址（留空＝本机账号）",
            modifier = Modifier.fillMaxWidth().testTag("prom_login_server"), singleLine = true)
        if (remote) {
            TextField(value = phone, onValueChange = { phone = it; message = null }, label = "邮箱",
                modifier = Modifier.fillMaxWidth().testTag("prom_login_email"), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            TextField(value = code, onValueChange = { code = it; message = null }, label = "密码（≥6 位）",
                modifier = Modifier.fillMaxWidth().testTag("prom_login_pwd"), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            GlassActionButton(label = "注册新账号", onClick = {
                message = null
                PromRemoteApi.loginEmail(base, phone.trim(), code, register = true).fold(
                    onSuccess = { (token, nick) ->
                        PromAccountStore.applyRemoteLogin(context, base, token, nick)
                        message = null
                        PromAccountStore.state.value?.let(onSuccess)
                        scope.launch { PromSync.syncOnce(context) }
                    },
                    onFailure = { message = it.message })
            }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        } else {
            TextField(value = name, onValueChange = { name = it; message = null }, label = "昵称",
                modifier = Modifier.fillMaxWidth().testTag("prom_login_name"), singleLine = true)
            TextField(value = password, onValueChange = { password = it; message = null }, label = "密码（≥4 位）",
                modifier = Modifier.fillMaxWidth().testTag("prom_login_password"), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        message?.let { Text(it, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFD9534F)) }
        GlassActionButton(label = if (busy) "登录中…" else if (remote) "云端登录" else "登录 / 创建", onClick = {
            busy = true
            if (remote) {
                val r = PromRemoteApi.loginEmail(base, phone.trim(), code, register = false)
                busy = false
                r.fold(onSuccess = { (token, nick) ->
                    PromAccountStore.applyRemoteLogin(context, base, token, nick)
                    message = null
                    PromAccountStore.state.value?.let(onSuccess)
                    scope.launch { PromSync.syncOnce(context) }
                }, onFailure = { message = it.message })
            } else {
                val result = PromAccountStore.loginOrCreate(context, name, password)
                busy = false
                result.fold(onSuccess = { onSuccess(it); message = null }, onFailure = { message = it.message })
            }
        }, primary = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("首次输入昵称和密码即创建账号；之后用同样的昵称与密码登录。",
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .45f))
    }
}
