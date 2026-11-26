// Stage renderer integration. The visualizer itself is Folia Major's own source
// (AGPL-3.0), built into the offline WebView asset `assets/folia-stage`.
package io.github.currencortex.music.feature.player

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.currencortex.music.core.media.DockAudioLevel
import io.github.currencortex.music.core.media.PlayerState
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.data.settings.FoliaLyricsScene
import io.github.currencortex.music.data.settings.LyricsRenderQuality
import io.github.currencortex.music.feature.lyrics.data.PlaybackLyricsState
import io.github.currencortex.music.ui.component.MusicDialog
import io.github.currencortex.music.ui.component.rememberArtworkGlowPalette
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

/** Folia is a visual stage; Compose owns tap/long-press navigation above it. */
private class StageWebView(context: android.content.Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false
}

private enum class StagePerformance(val query: String) {
    FULL("full"),
    BALANCED("balanced"),
    SAVER("saver"),
}

private fun stagePerformance(context: android.content.Context, requested: LyricsRenderQuality): StagePerformance {
    if (requested == LyricsRenderQuality.FULL) return StagePerformance.FULL
    if (requested == LyricsRenderQuality.BALANCED) return StagePerformance.BALANCED
    if (requested == LyricsRenderQuality.POWER_SAVER) return StagePerformance.SAVER
    val manager = context.getSystemService(ActivityManager::class.java)
    val memoryClass = manager?.memoryClass ?: 256
    val cores = Runtime.getRuntime().availableProcessors()
    return when {
        memoryClass >= 512 && cores >= 8 -> StagePerformance.FULL
        memoryClass >= 256 && cores >= 6 -> StagePerformance.BALANCED
        else -> StagePerformance.SAVER
    }
}

@Composable
internal fun FoliaStageControls(scene: FoliaLyricsScene, controlsVisible: Boolean, onSelect: () -> Unit,
    onToggleControls: () -> Unit, onExit: () -> Unit,
    modifier: Modifier = Modifier) {
    val sceneInteractions = remember { MutableInteractionSource() }
    val scenePressed by sceneInteractions.collectIsPressedAsState()
    val sceneScale by animateFloatAsState(if (scenePressed) .96f else 1f,
        tween(if (scenePressed) 80 else 150, easing = FoliaEaseOut), label = "folia scene press")
    val shell by animateColorAsState(Color.Black.copy(alpha = if (controlsVisible) .68f else .5f),
        tween(180, easing = FoliaEaseOut), label = "folia control shell")
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(shell)
        .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(999.dp)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.testTag("player_folia_scene").clip(RoundedCornerShape(999.dp))
            .clickable(interactionSource = sceneInteractions, indication = null,
                role = Role.Button, onClick = onSelect)
            .graphicsLayer { scaleX = sceneScale; scaleY = sceneScale }
            .height(38.dp).padding(horizontal = 13.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(Color(0xFFB9E7D7), RoundedCornerShape(999.dp)))
            Crossfade(scene.label, animationSpec = tween(160, easing = FoliaEaseOut), label = "folia stage scene") { label ->
                Text(label, color = Color.White.copy(alpha = .94f), fontSize = 12.sp, maxLines = 1)
            }
            FoliaControlGlyph(FoliaGlyph.CHEVRON, Color.White.copy(alpha = .46f), Modifier.size(12.dp))
        }
        Box(Modifier.width(1.dp).height(18.dp).background(Color.White.copy(alpha = .1f)))
        FoliaControlButton("toggle_folia_controls", "收起舞台控件", FoliaGlyph.SLIDERS,
            onToggleControls, controlsVisible)
        FoliaControlButton("exit_folia_stage", "退出舞台", FoliaGlyph.CLOSE, onExit, false)
    }
}

private val FoliaEaseOut = CubicBezierEasing(.23f, 1f, .32f, 1f)

private enum class FoliaGlyph { CHEVRON, CHECK, SLIDERS, CLOSE }

@Composable
private fun FoliaControlButton(tag: String, description: String, glyph: FoliaGlyph,
    onClick: () -> Unit, selected: Boolean) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f,
        tween(if (pressed) 90 else 150, easing = FoliaEaseOut), label = "folia control press")
    val ink by animateColorAsState(Color.White.copy(alpha = if (selected) .96f else .74f),
        tween(160, easing = FoliaEaseOut), label = "folia control ink")
    val activeFill by animateColorAsState(if (selected) Color.White.copy(alpha = .08f) else Color.Transparent,
        tween(150, easing = FoliaEaseOut), label = "folia control active fill")
    Box(Modifier.size(40.dp).testTag(tag).clip(RoundedCornerShape(999.dp)).background(activeFill)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center) {
        FoliaControlGlyph(glyph, ink, Modifier.size(16.dp).graphicsLayer { scaleX = scale; scaleY = scale })
    }
}

@Composable
private fun FoliaControlGlyph(glyph: FoliaGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        when (glyph) {
            FoliaGlyph.CHEVRON -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .2f, size.height * .38f)
                    lineTo(size.width * .5f, size.height * .66f)
                    lineTo(size.width * .8f, size.height * .38f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            FoliaGlyph.CHECK -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .18f, size.height * .52f)
                    lineTo(size.width * .42f, size.height * .74f)
                    lineTo(size.width * .82f, size.height * .28f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            FoliaGlyph.SLIDERS -> {
                listOf(.28f to .34f, .5f to .68f, .72f to .4f).forEach { (y, knob) ->
                    drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .12f, size.height * y),
                        androidx.compose.ui.geometry.Offset(size.width * .88f, size.height * y), stroke, StrokeCap.Round)
                    drawCircle(color, 2.dp.toPx(),
                        androidx.compose.ui.geometry.Offset(size.width * knob, size.height * y))
                }
            }
            FoliaGlyph.CLOSE -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .26f, size.height * .26f),
                    androidx.compose.ui.geometry.Offset(size.width * .74f, size.height * .74f), stroke, StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .74f, size.height * .26f),
                    androidx.compose.ui.geometry.Offset(size.width * .26f, size.height * .74f), stroke, StrokeCap.Round)
            }
        }
    }
}

/** A persistent hairline becomes a labelled scrub card while the stage controls are open. */
@Composable
internal fun FoliaProgressControl(positionMs: Long, durationMs: Long, bufferedValue: Float, enabled: Boolean,
    onSeek: (Long) -> Unit, onInteractionChange: (Boolean) -> Unit, compact: Boolean = false,
    modifier: Modifier = Modifier) {
    var drag by remember(durationMs) { mutableStateOf<Float?>(null) }
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Crossfade(compact, animationSpec = tween(180, easing = FoliaEaseOut), label = "folia progress density") { quiet ->
        if (quiet) {
            Box(
                modifier.fillMaxWidth().height(18.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekBar(drag ?: progress.coerceIn(0f, 1f), enabled && durationMs > 0,
                    onPreview = { drag = it }, onSeek = {
                        onSeek((it * durationMs).toLong()); drag = null
                    }, onCancel = { drag = null }, bufferedValue = bufferedValue,
                    onInteractionChange = onInteractionChange)
            }
        } else {
            Column(
                modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = .48f))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(18.dp))
                    .padding(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 7.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlayerTime(positionMs), color = Color.White.copy(alpha = .9f),
                        fontSize = 10.sp, letterSpacing = .3.sp)
                    Spacer(Modifier.weight(1f))
                    Text("FOLIA", color = Color(0xFFB9E7D7).copy(alpha = .82f),
                        fontSize = 8.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatPlayerTime(durationMs), color = Color.White.copy(alpha = .56f),
                        fontSize = 10.sp, letterSpacing = .3.sp)
                }
                PlayerSeekBar(drag ?: progress.coerceIn(0f, 1f), enabled && durationMs > 0,
                    onPreview = { drag = it }, onSeek = {
                        onSeek((it * durationMs).toLong()); drag = null
                    }, onCancel = { drag = null }, bufferedValue = bufferedValue,
                    onInteractionChange = onInteractionChange)
            }
        }
    }
}

@Composable
internal fun FoliaScenePickerDialog(current: FoliaLyricsScene, onSelect: (FoliaLyricsScene) -> Unit,
    onDismiss: () -> Unit) {
    MusicDialog("Folia 歌词场景", onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp).testTag("folia_scene_picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择视觉语言", color = Color.White.copy(alpha = .58f), fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                val otherScenes = FoliaLyricsScene.entries.filterNot { it == current }
                Text("随机切换", color = Color.White.copy(alpha = .72f), fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button) {
                            otherScenes.randomOrNull()?.let(onSelect)
                            onDismiss()
                        }.padding(horizontal = 10.dp, vertical = 7.dp).testTag("folia_random_scene"))
            }
            FoliaLyricsScene.entries.chunked(2).forEach { options ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { scene ->
                        FoliaSceneOption(scene, scene == current, Modifier.weight(1f)) {
                            onSelect(scene)
                            onDismiss()
                        }
                    }
                    if (options.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FoliaSceneOption(scene: FoliaLyricsScene, selected: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f,
        tween(if (pressed) 80 else 150, easing = FoliaEaseOut), label = "folia scene card press")
    val fill by animateColorAsState(if (selected) Color.White.copy(alpha = .16f)
        else Color.White.copy(alpha = .055f), tween(150, easing = FoliaEaseOut), label = "folia scene card fill")
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(fill)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { this.selected = selected }
        .padding(horizontal = 12.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(scene.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (selected) FoliaControlGlyph(FoliaGlyph.CHECK, Color(0xFFB9E7D7), Modifier.size(12.dp))
        }
        Text(scene.description, color = Color.White.copy(alpha = .52f), fontSize = 10.sp,
            maxLines = 2, modifier = Modifier.padding(top = 3.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun FoliaLyricsStage(
    song: Song?,
    lyrics: PlaybackLyricsState,
    player: PlayerState,
    position: State<Long>,
    lyricOffsetMs: Long,
    mode: String,
    translation: Boolean,
    romanization: Boolean,
    chromeHidden: Boolean,
    renderQuality: LyricsRenderQuality,
    audioMeter: DockAudioLevel,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val performance = remember(renderQuality, context) { stagePerformance(context, renderQuality) }
    val palette by rememberArtworkGlowPalette(song?.cover.orEmpty())
    val primary = palette.primary
    val secondary = palette.secondary
    val latestPlayer by rememberUpdatedState(player)
    val latestOffset by rememberUpdatedState(lyricOffsetMs)
    val latestPosition by rememberUpdatedState(position)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val payload = remember(song, lyrics.document, player.durationMs, player.playing, player.loading,
        mode, translation, romanization, chromeHidden, primary, secondary, performance) {
        buildStagePayload(song, lyrics, player, latestOffset, mode, translation, romanization,
            chromeHidden, primary, secondary, performance)
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    DisposableEffect(audioMeter, player.playing) {
        val subscription = if (player.playing) audioMeter.acquire() else null
        onDispose { subscription?.close() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize().testTag("folia_stage"),
        factory = { viewContext ->
            val debuggable = viewContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (debuggable) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            StageWebView(viewContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // WebView's WebGL surface must stay hardware accelerated. NONE can become an
                // opaque black layer on several vendor WebView builds after a surface resize.
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    // The stage is versioned inside the APK. Reusing an older cached module graph
                    // after an update produces a blank page because hashed chunks no longer match.
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    offscreenPreRaster = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                }
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                    .build()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) {
                            mainHandler.post { onError() }
                        }
                    }
                }
                if (debuggable) {
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            android.util.Log.d(
                                "PromXFolia",
                                "${consoleMessage.messageLevel()} ${consoleMessage.message()} " +
                                    "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                            )
                            return true
                        }
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onReady() {
                        mainHandler.post { ready = true }
                    }

                    @JavascriptInterface
                    fun onStageError(message: String) {
                        mainHandler.post { onError() }
                    }
                }, "PromXAndroid")
                loadUrl("https://appassets.androidplatform.net/assets/folia-stage/promx-stage.html?q=" + performance.query)
                webView = this
            }
        },
        update = { webView = it },
    )

    LaunchedEffect(ready, payload) {
        if (ready) webView?.evaluateJavascript(
            "window.PromXStage&&window.PromXStage.render(${JSONObject.quote(payload)})", null)
    }

    LaunchedEffect(ready, mode) {
        if (ready) webView?.evaluateJavascript(
            "window.PromXStage&&window.PromXStage.setMode(${JSONObject.quote(mode)})", null)
    }

    LaunchedEffect(ready, chromeHidden) {
        if (ready) webView?.evaluateJavascript(
            "window.PromXStage&&window.PromXStage.setChromeHidden($chromeHidden)", null)
    }

    LaunchedEffect(ready, performance) {
        if (ready) webView?.evaluateJavascript(
            "window.PromXStageRuntime&&window.PromXStageRuntime.setVisibility(true)", null)
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        while (true) {
            val current = latestPlayer
            val lyricTime = (latestPosition.value + latestOffset).coerceAtLeast(0)
            val audio = audioMeter.sample
            val audioAge = System.nanoTime() - audio.timestampNanos
            val audioAvailable = !current.loading && audio.timestampNanos > 0L && audioAge in 0L..750_000_000L
            webView?.evaluateJavascript(
                "window.PromXStage&&window.PromXStage.setClock($lyricTime,${!current.playing || current.loading}," +
                    "${current.durationMs},${current.playbackSpeed},${audio.energy},${audio.bass},${audio.pulse},$audioAvailable)",
                null,
            )
            // Frequent, lightweight clock anchors prevent queued WebView callbacks from leaving
            // the lyric stage behind the native playback clock.
            delay(when {
                !current.playing || current.loading -> 1200
                performance == StagePerformance.FULL -> 140
                performance == StagePerformance.BALANCED -> 180
                else -> 240
            })
        }
    }
}

private fun buildStagePayload(
    song: Song?,
    lyrics: PlaybackLyricsState,
    player: PlayerState,
    lyricOffsetMs: Long,
    mode: String,
    translation: Boolean,
    romanization: Boolean,
    chromeHidden: Boolean,
    primary: Int,
    secondary: Int,
    performance: StagePerformance,
): String {
    val durationMs = player.durationMs.takeIf { it > 0 } ?: song?.durationMs ?: 0
    val positionMs = (player.positionMs + lyricOffsetMs).coerceAtLeast(0)
    val root = JSONObject()
    root.put("version", 1)
    root.put("mode", mode)
    root.put("paused", !player.playing || player.loading)
    root.put("positionMs", positionMs)
    root.put("durationMs", durationMs)
    root.put("playbackSpeed", player.playbackSpeed)
    root.put("lyricsFontScale", 1.0)
    root.put("visualizerOpacity", 1.0)
    root.put("showTranslation", translation)
    root.put("showRomanization", romanization)
    root.put("playerChromeHidden", chromeHidden)
    root.put("quality", performance.query)

    song?.let {
        root.put("song", JSONObject().apply {
            put("id", it.id)
            put("title", it.name)
            put("artist", it.artists)
            put("album", it.album)
            put("coverUrl", it.cover)
            put("seed", "${it.musicSource}:${it.id}")
        })
    }

    root.put("theme", JSONObject().apply {
        put("name", song?.name ?: "PromX")
        put("backgroundColor", "#08090B")
        put("primaryColor", "#F8F6EF")
        put("accentColor", hexColor(primary))
        put("secondaryColor", hexColor(secondary))
        put("fontStyle", "sans")
        put("animationIntensity", if (performance == StagePerformance.SAVER) "calm" else "normal")
    })

    val lines = JSONArray()
    lyrics.document.lines.forEach { line ->
        lines.put(JSONObject().apply {
            put("startMs", line.startTimeMs)
            put("endMs", line.endTimeMs)
            put("text", line.text)
            if (line.translation.isNotBlank()) put("translation", line.translation)
            if (line.romanization.isNotBlank()) put("romanization", line.romanization)
            if (line.words.isNotEmpty() && !line.wordsEstimated) {
                val words = JSONArray()
                line.words.forEach { word ->
                    words.put(JSONObject().apply {
                        put("text", word.text)
                        put("startMs", word.startTimeMs)
                        put("endMs", word.endTimeMs)
                    })
                }
                put("words", words)
            }
        })
    }
    root.put("lines", lines)
    return root.toString()
}

private fun hexColor(value: Int): String = String.format("#%06X", value and 0xFFFFFF)
