// SPDX-License-Identifier: GPL-3.0-only
// 小 AI：直接运行 blessonism/grok-icon-study 的 replica（assets/grok/mini.html）。
// 关键点：WebView 的「布局尺寸」给到 visual 的 3 倍，再用 graphicsLayer 缩回目标大小，
// 这样内容不会被容器裁掉（之前只显示四分之一就是这个原因）。
package io.github.currencortex.music.ui.component

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val LAYOUT_MULTIPLIER = 3f

@SuppressLint("SetJavaScriptEnabled")
@Composable fun GrokCreatureView(
    modifier: Modifier = Modifier,
    visualSize: Dp = 40.dp,
) {
    Box(modifier.size(visualSize), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .size(visualSize * LAYOUT_MULTIPLIER)
                .graphicsLayer {
                    scaleX = 1f / LAYOUT_MULTIPLIER
                    scaleY = 1f / LAYOUT_MULTIPLIER
                },
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.loadsImagesAutomatically = true
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    webViewClient = android.webkit.WebViewClient()
                    loadUrl("file:///android_asset/grok/mini.html")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
