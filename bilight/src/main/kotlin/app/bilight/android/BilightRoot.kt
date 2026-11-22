// SPDX-License-Identifier: MIT
// PromX 的挂载点：把整块 Bilight 界面作为 Composable 暴露出去。
package app.bilight.android

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.bilight.android.core.AppModel
import app.bilight.android.ui.MainApp

/**
 * 进入时创建 AppModel（内部用 BilightServices 持有网络与下载），
 * 离开时让其在组合销毁后自然结束——不注册任何 Activity/Service。
 */
@Composable
fun rememberBilightModel(hostCookies: Map<String, String> = emptyMap()): AppModel {
    val app = LocalContext.current.applicationContext as Application
    // 宿主已登录 B站 时直接复用它的 Cookie，用户不必在嵌入界面里再扫一次码
    remember(hostCookies) { BilightSessionBridge.seed(app, hostCookies); hostCookies.hashCode() }
    return remember(app) { AppModel(app) }
}

@Composable
fun BilightRoot(
    hostCookies: Map<String, String> = emptyMap(),
    hostFont: androidx.compose.ui.text.font.FontFamily? = null,
    showChrome: Boolean = true,
) {
    val model = rememberBilightModel(hostCookies)
    MainApp(model, hostFont, showChrome)
}

/** 只渲染内容区（宿主自绘顶栏与底栏时用） */
@Composable
fun BilightContent(model: AppModel, hostFont: androidx.compose.ui.text.font.FontFamily? = null) {
    MainApp(model, hostFont, showChrome = false)
}
