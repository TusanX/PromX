// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.currencortex.music.ui.component.nativeui.SettingsTopBar
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** In-app web surface for Bilibili pages that do not yet have a native Compose transport. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BiliEmbeddedWebScreen(
    url: String,
    cookieHeader: String,
    title: String,
    onBack: () -> Unit,
) {
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun installCookies(view: WebView) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        cookieHeader.split(';').map(String::trim).filter { '=' in it }.forEach { pair ->
            manager.setCookie(".bilibili.com", "$pair; Secure; SameSite=None")
            manager.setCookie(".bilivideo.com", "$pair; Secure; SameSite=None")
        }
        manager.flush()
    }

    fun leavePage() {
        val view = webView
        if (canGoBack && view != null) view.goBack() else onBack()
    }

    BackHandler(enabled = true) { leavePage() }

    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Box(Modifier.statusBarsPadding()) {
            SettingsTopBar(title, onBack = ::leavePage, tag = "bili_web_toolbar")
            if (progress in 1..99) LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxSize().navigationBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        installCookies(this)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                canGoBack = view?.canGoBack() == true
                                progress = 1
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canGoBack = view?.canGoBack() == true
                                progress = 100
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url ?: return false
                                return target.scheme !in setOf("http", "https")
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    if (view.url != url && view.url.isNullOrBlank()) view.loadUrl(url)
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.webChromeClient = null
                    view.webViewClient = WebViewClient()
                    view.destroy()
                },
            )
        }
    }
}

@Composable
fun BiliLiveScreen(roomId: Long, cookieHeader: String, onBack: () -> Unit) {
    BiliEmbeddedWebScreen(
        url = "https://live.bilibili.com/$roomId",
        cookieHeader = cookieHeader,
        title = "哔哩哔哩直播",
        onBack = onBack,
    )
}
