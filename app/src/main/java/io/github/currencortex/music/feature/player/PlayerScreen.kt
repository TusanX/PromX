package io.github.currencortex.music.feature.player

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.core.media.*
import io.github.currencortex.music.feature.lyrics.ui.*
import io.github.currencortex.music.data.settings.LyricsWeight
import io.github.currencortex.music.data.settings.KaraokeScope
import io.github.currencortex.music.data.settings.LyricsStyle
import io.github.currencortex.music.data.settings.LyricsRenderQuality
import io.github.currencortex.music.ui.component.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

private enum class PlayerContent { COVER, LYRICS }
private enum class PlayerOverlay { NONE, QUEUE, COMMENTS, OPTIONS, SHARE, QUALITY, ACTIONS, MODE, LYRICS, WEIGHT, PERFORMANCE, KARAOKE, SLEEP, AI, ROAMING, BACKGROUND, LYRICS_MANAGEMENT }
internal val PlayerPagePosition = SemanticsPropertyKey<Float>("PlayerPagePosition")

internal val LocalPlayerExpansion = androidx.compose.runtime.staticCompositionLocalOf { 1f }

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlayerScreen(vm: PlayerViewModel, onBack: () -> Unit, onToggle: () -> Unit,
    actions: (@Composable (io.github.currencortex.music.data.song.Song) -> Unit)? = null,
    onRoom: (() -> Unit)? = null, onDialogActive: (Boolean) -> Unit = {},
    onNetwork: (() -> Unit)? = null, onLike: (() -> Unit)? = null, onEqualizer: (() -> Unit)? = null,
    onAiNavigate: (String) -> Unit = {}, roamingRequest: Int = 0, onRoamingRequestConsumed: () -> Unit = {},
    biliVideoRequest: Int = 0, onBiliVideoRequestConsumed: () -> Unit = {},
    // 0 = 收起（迷你条），1 = 完全展开；由 PlayerSheetDrag 的实时进度驱动，封面随之缩放
    expansion: () -> Float = { 1f }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val displayTrackTitle = remember(queue.current?.name) {
        queue.current?.name.orEmpty().decodedMediaText()
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val appearance by vm.container.settings.state.collectAsStateWithLifecycle()
    val backgroundImage = remember(appearance.playerBackground) { vm.container.playerBackgrounds.file(appearance.playerBackground) }
    val songActions by vm.actions.collectAsStateWithLifecycle()
    val comments by vm.comments.collectAsStateWithLifecycle()
    val commentComposer by vm.commentComposer.collectAsStateWithLifecycle()
    val sleepState by vm.sleepTimer.state.collectAsStateWithLifecycle()
    val together by vm.together.state.collectAsStateWithLifecycle()
    val sharedQueue by vm.together.queue.collectAsStateWithLifecycle()
    val roaming by vm.container.personalRoaming.state.collectAsStateWithLifecycle()
    val playbackMode = if (together.active) sharedQueue.mode else queue.mode
    val context = LocalContext.current
    // The timer can expire while the sheet is closed; report it once, on the next composition.
    LaunchedEffect(sleepState.notice) {
        sleepState.notice?.let {
            vm.container.errorNotices.showMessage(it, longDuration = true)
            vm.sleepTimer.consumeNotice()
        }
    }
    var content by rememberSaveable { mutableStateOf(PlayerContent.COVER) }
    val pager = rememberPagerState(initialPage = content.ordinal) { 2 }
    val queueMotion = remember { QueuePageMotion() }
    val pagerArtwork = remember(pager, queueMotion) { PlayerPagerArtworkTransition(
        { pager.currentPage + pager.currentPageOffsetFraction }, { queueMotion.progress }) }
    val scope = rememberCoroutineScope()
    val sidePage = remember(scope) { PlayerSidePageState(scope) }
    var sideDialog by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage) { content = PlayerContent.entries[pager.settledPage] }
    var overlay by rememberSaveable { mutableStateOf(PlayerOverlay.NONE) }
    LaunchedEffect(roamingRequest) {
        if (roamingRequest > 0) { overlay = PlayerOverlay.ROAMING; onRoamingRequestConsumed() }
    }
    LaunchedEffect(roaming.error) { if (roaming.active) roaming.error?.let(vm.container.errorNotices::show) }
    var queueGesture by remember { mutableStateOf(false) }
    var queueGestureJob by remember { mutableStateOf<Job?>(null) }
    var controlsRevealed by rememberSaveable { mutableStateOf(false) }
    var controlsHidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.lyricsDisplay.hideControls) { controlsRevealed = false; controlsHidden = false }
    val menuHost = LocalSongMenu.current
    val songNavigation = rememberPlayerSongNavigation(queue.current, vm.container.musicRepository, settings.server) {
        sidePage.close()
        onAiNavigate(it)
    }
    val artistLink = Modifier.testTag("player_song_artist").clickable(
        enabled = songNavigation.artistsEnabled, role = Role.Button,
        onClickLabel = "查看歌手") { songNavigation.openArtists(false) }
    val dismiss = {
        queueGestureJob?.cancel()
        queueGestureJob = null
        queueGesture = false
        overlay = PlayerOverlay.NONE
    }
    LaunchedEffect(overlay, queueGesture, sidePage.active, songNavigation.dialogActive) {
        onDialogActive(overlay != PlayerOverlay.NONE || queueGesture || sidePage.active || songNavigation.dialogActive)
    }
    LaunchedEffect(overlay) { if (overlay != PlayerOverlay.AI) vm.ai.close() }
    LaunchedEffect(overlay, songActions.songId) { if (overlay == PlayerOverlay.COMMENTS) vm.loadComments() }
    DisposableEffect(Unit) { onDispose { onDialogActive(false) } }
    val indication = LocalIndication.current
    var lyricsCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var previewCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var controlsCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val sheetProgress = LocalPlayerSheetProgress.current
    val expanded by remember(sheetProgress) { derivedStateOf { sheetProgress() >= .995f } }
    val backgroundSpeed = rememberPlayerFluidSpeed(vm.container.dockAudioLevel, state.playing,
        expanded && !sidePage.active && backgroundImage == null && appearance.playerDynamicBackground,
        queue.current, state.playbackSpeed)
    val sheetSettled by remember(sheetProgress) { derivedStateOf { sheetProgress() >= 1f } }
    val sheetDrag = LocalPlayerSheetDrag.current
    val colors = remember { darkColorScheme(primary = Color.White, onPrimary = Color(0xFF282629),
        background = Color(0xFF262428), surface = Color(0xFF262428)) }
    val queueFling = with(LocalDensity.current) { 700.dp.toPx() }
    val controlsHideDistance = with(LocalDensity.current) { 48.dp.toPx() }
    var controlsPull by remember { mutableFloatStateOf(0f) }
    val controlsDownDrag = PlayerControlsDownDrag({ controlsCoordinates }, PlayerUpDrag(
        begin = { controlsPull = 0f },
        drag = { controlsPull = (controlsPull + it).coerceAtLeast(0f) },
        end = { velocity, cancelled ->
            if (!cancelled && (controlsPull >= controlsHideDistance || (controlsPull > 0f && velocity > queueFling))) {
                controlsHidden = true; controlsRevealed = false
            }
        }))
    var queueHeight by remember { mutableFloatStateOf(1f) }
    // 哔哩哔哩 视频模式：画面直接进播放页，不跳页
    val commentsRequest by vm.openComments.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(commentsRequest) {
        if (commentsRequest > 0) {
            overlay = PlayerOverlay.COMMENTS
            vm.consumeOpenCommentsRequest()
        }
    }
    var biliFavoriteFor by remember { mutableStateOf<String?>(null) }
    // 哔哩哔哩 竖屏视频：只是把服务播放器的画面挂出来，切歌自动退出
    var biliVideo by rememberSaveable(queue.current?.id) {
        mutableStateOf(
            queue.current?.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI &&
                !queue.current?.externalIds?.bilibiliBvid.isNullOrBlank(),
        )
    }
    var biliVideoTrackId by rememberSaveable { mutableStateOf<Long?>(null) }
    val playerContext = androidx.compose.ui.platform.LocalContext.current
    val playerScope = androidx.compose.runtime.rememberCoroutineScope()
    var biliStats by remember { mutableStateOf<io.github.currencortex.music.core.alt.BiliPlayback.Stats?>(null) }
    LaunchedEffect(queue.current?.externalIds?.bilibiliBvid) {
        val bvid = queue.current?.externalIds?.bilibiliBvid ?: return@LaunchedEffect
        biliStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                io.github.currencortex.music.core.alt.BiliPlayback.stats(
                    bvid, vm.container.biliSession.cookieHeader())
            }.getOrNull()
        }
    }
    LaunchedEffect(queue.current?.id) {
        val currentId = queue.current?.id
        if (biliVideo && biliVideoTrackId != null && biliVideoTrackId != currentId) {
            biliVideo = false
            io.github.currencortex.music.core.alt.BiliQuality.wantsVideo = false
        }
        biliVideoTrackId = currentId
        if (biliVideo) io.github.currencortex.music.core.alt.BiliQuality.wantsVideo = true
    }
    val setBiliVideo: (Boolean) -> Unit = { enabled ->
        val wasEnabled = biliVideo
        biliVideo = enabled
        io.github.currencortex.music.core.alt.BiliQuality.wantsVideo = enabled
        if ((enabled || wasEnabled) && queue.current?.musicSource ==
            io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            // Re-resolve at the current position. Audio mode keeps the lightweight stream while
            // video mode asks BiliPlayback for the selected DASH video+audio composition.
            vm.container.playerController.load(
                play = state.showPause,
                position = state.positionMs,
                refreshSource = true,
            )
        }
    }
    LaunchedEffect(biliVideoRequest, queue.current?.musicSource, queue.current?.externalIds?.bilibiliBvid) {
        if (biliVideoRequest > 0 && queue.current?.musicSource ==
            io.github.currencortex.music.data.song.MusicSource.BILIBILI &&
            !queue.current?.externalIds?.bilibiliBvid.isNullOrBlank()) {
            setBiliVideo(true)
            onBiliVideoRequestConsumed()
        }
    }
    var videoSpeed by remember { mutableStateOf(1f) }
    var mvBackground by rememberSaveable(queue.current?.id) { mutableStateOf(false) }
    var foliaStage by remember { mutableStateOf(false) }
    var foliaEntering by remember { mutableStateOf(false) }
    var foliaScenePicker by remember { mutableStateOf(false) }
    var foliaSeeking by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded && foliaStage) {
            foliaStage = false
            foliaEntering = false
            controlsRevealed = false
            controlsHidden = false
        }
    }
    var mvSource by remember(queue.current?.id) { mutableStateOf<PlayerMvSource?>(null) }
    var mvLoading by remember(queue.current?.id) { mutableStateOf(false) }
    var mvReturnPage by rememberSaveable(queue.current?.id) { mutableIntStateOf(PlayerContent.COVER.ordinal) }
    var qqMvInfo by remember(queue.current?.id) {
        mutableStateOf<io.github.currencortex.music.core.alt.QqExtras.MvInfo?>(null)
    }
    val currentSongForMv = queue.current
    LaunchedEffect(currentSongForMv?.id, currentSongForMv?.musicSource) {
        qqMvInfo = null
        val song = currentSongForMv ?: return@LaunchedEffect
        if (song.musicSource != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC ||
            !song.externalIds.qqMvVid.isNullOrBlank()) return@LaunchedEffect
        val mid = song.externalIds.qqMusicId ?: return@LaunchedEffect
        val qq = vm.container.qqSession.takeIf { it.loggedIn }
        qqMvInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            song.externalIds.qqNumericId?.toLongOrNull()?.let { numericId ->
                io.github.currencortex.music.core.alt.QqExtras.mvBySongId(
                    numericId, qq?.cookies.orEmpty(), qq?.uin.orEmpty().ifBlank { "0" })
            } ?: io.github.currencortex.music.core.alt.QqExtras.mv(
                mid, qq?.cookies.orEmpty(), qq?.uin.orEmpty().ifBlank { "0" })
        }
    }
    val qqMvVid = currentSongForMv?.externalIds?.qqMvVid?.takeIf(String::isNotBlank)
        ?: qqMvInfo?.vid
    val hasMvBackground = currentSongForMv?.let { song ->
        when (song.musicSource) {
            io.github.currencortex.music.data.song.MusicSource.NETEASE,
            io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            io.github.currencortex.music.data.song.MusicSource.BILIBILI,
            io.github.currencortex.music.data.song.MusicSource.APPLE_MUSIC,
            io.github.currencortex.music.data.song.MusicSource.SPOTIFY -> true
            else -> false
        }
    } == true
    LaunchedEffect(mvBackground, currentSongForMv?.id, qqMvVid) {
        if (!mvBackground) {
            mvLoading = false
            return@LaunchedEffect
        }
        val song = currentSongForMv ?: return@LaunchedEffect
        mvSource = null
        mvLoading = true
        val biliCookie = vm.container.biliSession.cookieHeader()
        val biliPreferred = vm.container.biliSession.loggedIn ||
            song.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI
        val resolved = runCatching {
            val biliMv = if (biliPreferred) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliMvResolver.resolve(
                    title = song.name,
                    artist = song.artists,
                    durationMs = song.durationMs,
                    cookie = biliCookie,
                    knownBvid = song.externalIds.bilibiliBvid,
                )
            } else null
            if (biliMv != null) {
                PlayerMvSource(
                    urls = listOf(biliMv.url),
                    referer = "https://www.bilibili.com/",
                    headers = buildMap {
                        if (biliCookie.isNotBlank()) put("Cookie", biliCookie)
                    },
                    userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    mimeType = "video/mp4",
                )
            } else when (song.musicSource) {
                io.github.currencortex.music.data.song.MusicSource.NETEASE -> PlayerMvSource(
                    vm.container.libraryRepository.mvSource(song.mv, 720), "https://music.163.com/")
                io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC -> {
                    val vid = qqMvVid ?: return@runCatching null
                    val qq = vm.container.qqSession.takeIf { it.loggedIn }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.currencortex.music.core.alt.QqExtras.mvStream(
                            vid, qq?.cookies.orEmpty(), qq?.uin.orEmpty().ifBlank { "0" })
                    }?.let { PlayerMvSource(it.urls, it.referer) }
                }
                else -> null
            }
        }.getOrNull()
        mvLoading = false
        if (resolved == null) {
            mvBackground = false
            scope.launch { pager.animateScrollToPage(mvReturnPage) }
            vm.container.errorNotices.show("哔哩哔哩和当前音乐服务都没有找到可播放的 MV")
        } else mvSource = resolved
    }
    val toggleMvBackground: () -> Unit = {
        if (!mvBackground) {
            foliaStage = false
            mvReturnPage = pager.currentPage
            mvBackground = true
            scope.launch { pager.animateScrollToPage(PlayerContent.LYRICS.ordinal) }
        } else {
            mvBackground = false
            scope.launch { pager.animateScrollToPage(mvReturnPage) }
        }
    }
    val enterFoliaStage: () -> Unit = enter@{
        if (foliaStage || foliaEntering) return@enter
        mvReturnPage = pager.currentPage
        mvBackground = false
        mvSource = null
        if (biliVideo) {
            setBiliVideo(false)
            runCatching { vm.container.playerController.holdForVideo(false) }
        }
        foliaEntering = true
        scope.launch {
            try {
                pager.animateScrollToPage(PlayerContent.LYRICS.ordinal)
                withFrameNanos { }
                foliaStage = true
                controlsRevealed = true
                controlsHidden = false
            } finally {
                foliaEntering = false
            }
        }
    }
    val exitFoliaStage: () -> Unit = {
        foliaScenePicker = false
        foliaEntering = false
        foliaStage = false
        controlsRevealed = false
        controlsHidden = false
        scope.launch { pager.animateScrollToPage(mvReturnPage) }
    }
    val toggleFoliaControls: () -> Unit = {
        if (controlsRevealed || !controlsHidden) {
            controlsRevealed = false
            controlsHidden = true
        } else {
            controlsRevealed = true
            controlsHidden = false
        }
    }
    BackHandler(enabled = foliaStage && overlay == PlayerOverlay.NONE && !sidePage.active && !queueGesture) {
        exitFoliaStage()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // 离开播放页：无论如何都解除视频模式对音频的挂起
            runCatching { vm.container.playerController.holdForVideo(false) }
        }
    }
    val videoSpeedLabel = if (videoSpeed == 1f) "倍速 1.0x" else "倍速 ${videoSpeed}x"
    val upDrag = PlayerUpDrag(begin = {
            queueMotion.dragged = 0f
            queueGesture = true
        }, drag = { delta ->
            queueMotion.dragged?.let { queueMotion.dragged = (it - delta / queueHeight).coerceIn(0f, 1f) }
        }, end = end@{ velocity, cancelled ->
            // Back or the queue's close gesture may already have taken over this drag.
            if (!queueGesture || queueMotion.dragged == null) return@end
            val start = queueMotion.progress
            val open = !cancelled && (if (kotlin.math.abs(velocity) > queueFling) velocity < 0 else start >= .14f)
            queueGestureJob = scope.launch {
                queueMotion.animation.snapTo(start)
                queueMotion.dragged = null
                queueMotion.animation.animateTo(if (open) 1f else 0f,
                    tween((300 * (if (open) 1 - start else start)).toInt().coerceAtLeast(120),
                        easing = CubicBezierEasing(.2f, 0f, .2f, 1f)))
                if (open) overlay = PlayerOverlay.QUEUE
                queueGesture = false
                queueGestureJob = null
            }
    })
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player_screen")
        .onSizeChanged { queueHeight = it.height.toFloat().coerceAtLeast(1f) }
        .then(if (overlay == PlayerOverlay.NONE) Modifier.playerSheetDrag(fromMini = false,
            upDrag = if (expanded) upDrag else null,
            controlsDownDrag = if (expanded && pager.settledPage == PlayerContent.LYRICS.ordinal && !pager.isScrollInProgress)
                controlsDownDrag else null, enabled = !queueGesture && !sidePage.active) {
                listOfNotNull(lyricsCoordinates, previewCoordinates)
            } else Modifier)) {
        val widePlayer = maxWidth > maxHeight
        val immersiveCover = appearance.playerCoverStyle == io.github.currencortex.music.data.settings.PlayerCoverStyle.IMMERSIVE &&
            !(roaming.active && roaming.choice.code == "PUZZLE_MODE_RCMD")
        val foliaMode = if (foliaStage) settings.lyricsDisplay.foliaScene.mode else null
        val fullScreenFolia = foliaStage && !widePlayer && content == PlayerContent.LYRICS &&
            expanded && !mvBackground && !queueGesture
        SideEffect { pagerArtwork.immersive = immersiveCover && !widePlayer; pagerArtwork.updateBounds() }
        PlayerSystemBars(immersive = widePlayer || fullScreenFolia,
            hideNavigation = fullScreenFolia)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // Hiding the status bar must not resize the cover midway through its flight.
        // Landscape still avoids the cutout and navigation bar, including transient bars.
        val wideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility)
        val wideSafeInset = with(density) { maxOf(wideInsets.getLeft(density, layoutDirection),
            wideInsets.getRight(density, layoutDirection)).toDp() }
        val wideVerticalInset = with(density) { maxOf(wideInsets.getTop(density), wideInsets.getBottom(density)).toDp() }
        LaunchedEffect(widePlayer) {
            if (widePlayer) {
                sidePage.close()
                pager.scrollToPage(PlayerContent.LYRICS.ordinal)
            }
        }
        val hideControls = animateFloatAsState(
            if ((settings.lyricsDisplay.hideControls || controlsHidden || fullScreenFolia) && !controlsRevealed) 1f else 0f,
            tween(260, easing = LinearOutSlowInEasing), label = "lyricsControls")
        val foliaControlsVisible by remember { derivedStateOf { hideControls.value < .5f } }
        LaunchedEffect(fullScreenFolia, foliaControlsVisible, foliaScenePicker, foliaSeeking) {
            if (fullScreenFolia && foliaControlsVisible && !foliaScenePicker && !foliaSeeking) {
                delay(4200)
                if (foliaControlsVisible && !foliaScenePicker && !foliaSeeking) {
                    controlsRevealed = false
                    controlsHidden = true
                }
            }
        }
        // The neighbour page used to mount its complete Apple rail on the first drag frame.
        // Prepare it after the opening flight and other player transitions have settled,
        // while preserving the cheaper classic-page deferral and the immediate-swipe fallback.
        val idleForLyrics by remember(pager, queueMotion, sidePage, sheetProgress, sheetDrag, hideControls) {
            derivedStateOf {
                sheetProgress() >= 1f && !pager.isScrollInProgress && !queueGesture &&
                    queueMotion.progress <= .0001f && !sidePage.active && !sideDialog &&
                    overlay == PlayerOverlay.NONE && sheetDrag?.state?.dragging != true &&
                    (hideControls.value <= .0001f || hideControls.value >= .9999f)
            }
        }
        val fancyLyrics = settings.lyricsDisplay.style == LyricsStyle.APPLE_MUSIC || foliaStage
        var lyricsPrepared by remember { mutableStateOf(false) }
        LaunchedEffect(fancyLyrics, idleForLyrics) {
            if (fancyLyrics && idleForLyrics && !lyricsPrepared) {
                delay(120)
                withFrameNanos { }
                lyricsPrepared = true
            }
        }
        val controlsVisibility = {
            1f - hideControls.value * (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
        }
        val openQueue: () -> Unit = { scope.launch { queueMotion.animation.snapTo(0f); overlay = PlayerOverlay.QUEUE } }
        val functions: @Composable () -> Unit = {
            PlayerSongActionsBar(vm, vm::openCommentsRequest, openQueue,
                { overlay = PlayerOverlay.SLEEP }, onLike, onEffects = onEqualizer,
                onOptions = if (widePlayer) null else ({ overlay = PlayerOverlay.OPTIONS }))
        }
        val transport: @Composable () -> Unit = {
            PlayerTransport(vm, onToggle, quality = {
                Text(
                    if (state.song?.privateLibrary == true) state.song?.originalQualityLabel.orEmpty()
                    else state.audioInfo?.label ?: qualityLabelFor(settings.primarySource, settings.quality),
                    Modifier.testTag("open_quality_sheet")
                        .clickable(enabled = state.mode == PlayerMode.LOCAL && state.song?.privateLibrary != true,
                            role = Role.Button) { overlay = PlayerOverlay.QUALITY }
                        .padding(horizontal = 8.dp),
                    color = Color.White.copy(alpha = .65f),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }, leadingAction = { PlayerPlaybackModeButton(vm) },
                trailingAction = { PlayerQueueButton(vm, openQueue) },
                onSeekInteraction = { foliaSeeking = it })
        }
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().height(if (widePlayer) 48.dp else 52.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton(PlayerIcon.COLLAPSE, "收起播放器", onBack, Modifier.testTag("navigate_back"))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (roaming.active) Text("私人漫游 · ${roaming.choice.title}",
                        Modifier.testTag("open_roaming_modes")
                            .clickable(role = Role.Button) { overlay = PlayerOverlay.ROAMING }
                            .padding(horizontal = 8.dp, vertical = 14.dp), color = Color.White.copy(alpha = .65f),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val biliSong = queue.current?.takeIf {
                    it.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI
                }
                if (biliSong != null) PlayerIconButton(PlayerIcon.VIDEO,
                    if (biliVideo) "关闭视频，回到音频" else "视频模式",
                    {
                        val entering = !biliVideo
                        setBiliVideo(entering)
                    },
                    Modifier.testTag("player_video_mode"), selected = biliVideo)
                // 只有带 BV 号的在线视频才能下载；本地缓存项没有 bvid，不显示按钮
                if (biliSong?.externalIds?.bilibiliBvid?.isNotBlank() == true)
                    PlayerIconButton(PlayerIcon.DOWNLOAD, "下载到本地缓存", {
                    val bvid = biliSong.externalIds.bilibiliBvid.orEmpty()
                    android.util.Log.d("PromXDownload", "clicked bvid=$bvid")
                    if (bvid.isNotBlank()) {
                        android.widget.Toast.makeText(playerContext, "开始缓存视频…",
                            android.widget.Toast.LENGTH_SHORT).show()
                        // 直接调下载器，不经过宿主桥，少一层转发就少一处失联
                        playerScope.launch {
                            val cookies = vm.container.biliSession.cookieHeader()
                            val r = io.github.currencortex.music.feature.bili.BiliDownloader.download(
                                playerContext, bvid, biliSong.name, cookies)
                            android.util.Log.d("PromXDownload", "result=$r")
                            android.widget.Toast.makeText(playerContext,
                                when (r) {
                                    is io.github.currencortex.music.feature.bili.BiliDownloader.Result.Ok ->
                                        "已缓存：" + r.path.substringAfterLast('/')
                                    is io.github.currencortex.music.feature.bili.BiliDownloader.Result.Failed ->
                                        "缓存失败：" + r.message
                                }, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }, Modifier.testTag("player_bili_download"))
                if (biliSong != null) PlayerIconButton(PlayerIcon.MORE,
                    "收藏到 哔哩哔哩 收藏夹", { biliFavoriteFor = biliSong.externalIds.bilibiliBvid }, Modifier.testTag("player_bili_favorite"))
                if (hasMvBackground) {
                    PlayerIconButton(PlayerIcon.VIDEO,
                        if (mvBackground) "关闭 MV 背景" else "显示 MV 背景，长按进入 Folia", toggleMvBackground,
                        Modifier.testTag("player_mv_background"), selected = mvBackground, loading = mvLoading,
                        onLongClick = enterFoliaStage, onLongClickLabel = "进入 Folia 歌词舞台",
                        longPressHint = !mvBackground)
                } else PlayerIconButton(PlayerIcon.EFFECTS, "打开 Folia 歌词舞台", enterFoliaStage,
                    Modifier.testTag("player_folia_stage"), selected = foliaStage)
                PlayerIconButton(PlayerIcon.SHARE, "分享歌曲", { overlay = PlayerOverlay.SHARE }, Modifier.testTag("player_share"),
                    enabled = queue.current != null)
            }
        }
        PlayerSidePageLayout(sidePage,
            enabled = expanded && !widePlayer && !sideDialog && !songNavigation.dialogActive && overlay == PlayerOverlay.NONE &&
                !queueGesture && !foliaSeeking && sheetDrag?.state?.dragging != true,
            openingDirection = { if (kotlin.math.abs(pager.currentPageOffsetFraction) > .001f) 0f
                else if (pager.currentPage == 0) 1f else -1f },
            pagerScrolling = { pager.isScrollInProgress },
            excludedArea = { controlsCoordinates }, side = {
                MiuixTheme(controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.Dark, isDark = true, darkColors = colors) }) {
                    if (sidePage.direction > 0f) PlayerListeningPage(vm.container, onPlay = sidePage::close,
                        onBinding = { sidePage.close(); onAiNavigate("user/binding") },
                        onListening = { sidePage.close(); onAiNavigate("user/listening") },
                        onDialogActive = { sideDialog = it })
                    else PlayerSidePageContent(queue.current, songNavigation,
                        aiEnabled = io.github.currencortex.music.data.song.NeteaseSongActionsRepository.songId(queue.current) != null,
                        onAi = {
                            vm.openAi()
                            vm.ai.draft("请解读这首歌的创作背景和歌词含义。")
                            overlay = PlayerOverlay.AI
                        })
                }
            }) {
        PlayerBackdrop(queue.current?.cover.orEmpty(), Modifier.matchParentSize(), backgroundImage, appearance.playerBackgroundBlur,
            active = expanded && !sidePage.active && !mvBackground, dynamicBackground = appearance.playerDynamicBackground,
            motionSpeed = { backgroundSpeed.value })
        AnimatedVisibility(visible = mvBackground && mvSource != null,
            enter = fadeIn(tween(520)), exit = fadeOut(tween(320)),
            modifier = Modifier.matchParentSize()) {
            mvSource?.let { source -> PlayerMvBackdrop(
                source = source, playing = state.showPause,
                active = expanded && !sidePage.active,
                positionMs = state.positionMs, timelineRevision = state.timelineRevision,
                poster = currentSongForMv?.cover.orEmpty(),
                onError = { message ->
                    mvBackground = false; mvSource = null
                    scope.launch { pager.animateScrollToPage(mvReturnPage) }
                    vm.container.errorNotices.show(message)
                },
                modifier = Modifier.matchParentSize(),
            ) }
        }
        // The artwork viewport uses light ink; dialogs below inherit the app appearance.
        MiuixTheme(controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.Dark, isDark = true, darkColors = colors) }) {
        CompositionLocalProvider(LocalIndication provides indication) {
        CompositionLocalProvider(LocalPlayerPagerArtwork provides pagerArtwork) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { pagerArtwork.root = it; pagerArtwork.updateBounds() }) {
        if (immersiveCover && !widePlayer && !mvBackground) PlayerPagerArtwork(pagerArtwork, queue.current, drawText = false)
        AnimatedVisibility(visible = fullScreenFolia, modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(240)) + scaleIn(tween(300), initialScale = .992f),
            exit = fadeOut(tween(170)) + scaleOut(tween(190), targetScale = 1.008f)) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = playerSheetContentAlpha(sheetProgress())
            }) {
                // Keep the stage at its full viewport height. Resizing it while controls appear
                // made the WebView reflow and pull the subtitles out of their composition.
                LyricsPanel(vm, Modifier.matchParentSize().onGloballyPositioned { lyricsCoordinates = it },
                    active = true, prepare = fancyLyrics && lyricsPrepared, foliaMode = foliaMode,
                    foliaChromeHidden = false)
                if (!foliaControlsVisible) {
                    Box(Modifier.matchParentSize().zIndex(3f).pointerInput(Unit) {
                        detectTapGestures { toggleFoliaControls() }
                    })
                }
                AnimatedVisibility(visible = foliaControlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(bottom = 92.dp).zIndex(12f),
                    enter = fadeIn(tween(160)) + slideInVertically(tween(200, easing = LinearOutSlowInEasing)) { it / 4 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(150, easing = LinearOutSlowInEasing)) { it / 5 }) {
                    FoliaStageControls(settings.lyricsDisplay.foliaScene,
                        controlsVisible = foliaControlsVisible,
                        onSelect = { foliaScenePicker = true }, onToggleControls = toggleFoliaControls,
                        onExit = exitFoliaStage)
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(11f)
                        .onGloballyPositioned { controlsCoordinates = it },
                ) {
                    val duration = state.durationMs.takeIf { it > 0 } ?: queue.current?.durationMs ?: 0
                    FoliaProgressControl(
                        positionMs = state.positionMs,
                        durationMs = duration,
                        bufferedValue = if (state.song?.id == queue.current?.id) state.bufferedProgress(duration) else 0f,
                        enabled = state.canControlPlayback && queue.current != null,
                        onSeek = vm.player::seek,
                        onInteractionChange = { foliaSeeking = it },
                        compact = !foliaControlsVisible,
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (!fullScreenFolia) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = -size.height * queueMotion.progress }
            .onGloballyPositioned { pagerArtwork.mainPage = it; pagerArtwork.updateBounds() }) {
        if (widePlayer && immersiveCover && !mvBackground) PlayerImmersiveCover(queue.current?.cover.orEmpty(),
            Modifier.fillMaxSize().testTag("player_wide_immersive_cover"), bounds = { null }, radius = { 0f },
            immersion = { 1f }, alpha = { playerSheetContentAlpha(sheetProgress()) }, wideViewport = true)
        Column(Modifier.fillMaxSize()
            .then(if (overlay == PlayerOverlay.QUEUE || queueGesture) Modifier.semantics { hideFromAccessibility() } else Modifier)
            .then(if (widePlayer) Modifier.padding(horizontal = wideSafeInset, vertical = wideVerticalInset)
                else Modifier.statusBarsPadding().navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)))
            .padding(horizontal = 24.dp).testTag("player_safe_content")
            .graphicsLayer {
                alpha = playerSheetContentAlpha(sheetProgress()) * (1 - queueMotion.progress / .35f).coerceIn(0f, 1f) *
                    (if (biliVideo) 0f else 1f)
            }) {
            if (!widePlayer && !fullScreenFolia) header()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (widePlayer) {
                    val stageHeight = (maxHeight - 24.dp).coerceAtLeast(0.dp)
                    val stageWidth = minOf(maxWidth, stageHeight * 2.7f, 1000.dp)
                    Box(Modifier.fillMaxSize().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Row(Modifier.size(stageWidth, stageHeight).testTag("player_wide_stage"),
                            horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            WideCoverContent(vm, Modifier.weight(1f).fillMaxHeight(), immersiveCover || mvBackground, expand = expansion)
                            HorizontalPager(pager, Modifier.weight(1f).fillMaxHeight().testTag("player_wide_pager")
                                .semantics { this[PlayerPagePosition] = pager.currentPage + pager.currentPageOffsetFraction },
                                beyondViewportPageCount = 1,
                                userScrollEnabled = expanded && !sidePage.active && !mvBackground && !foliaSeeking &&
                                    sheetDrag?.state?.dragging != true,
                                flingBehavior = PagerDefaults.flingBehavior(pager,
                                    snapAnimationSpec = tween(240, easing = LinearOutSlowInEasing))) { page ->
                                Box(Modifier.fillMaxSize().then(if (pager.currentPage != page && !pager.isScrollInProgress)
                                    Modifier.clearAndSetSemantics {} else Modifier)) {
                                    if (page == 0) WideControlsContent(vm, onToggle, functions, artistLink)
                                    else LyricsPanel(vm, Modifier.fillMaxSize()
                                        .onGloballyPositioned { lyricsCoordinates = it },
                                        // Rasterizing the lyric rail competes with the mini-cover flight.
                                        // Reveal it after expansion; LyricsPanel retains it while collapsing.
                                        active = sheetSettled && (pager.currentPage == 1 || pager.isScrollInProgress), minimal = true,
                                        prepare = fancyLyrics && lyricsPrepared, foliaMode = foliaMode,
                                        foliaChromeHidden = hideControls.value > .5f)
                                }
                            }
                        }
                    }
                    Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                        if (foliaStage) FoliaStageControls(settings.lyricsDisplay.foliaScene,
                            controlsVisible = foliaControlsVisible,
                            onSelect = { foliaScenePicker = true }, onToggleControls = toggleFoliaControls,
                            onExit = exitFoliaStage,
                            modifier = Modifier.padding(end = 4.dp))
                        if (hasMvBackground) PlayerIconButton(PlayerIcon.VIDEO,
                            if (mvBackground) "关闭 MV 背景" else "显示 MV 背景，长按进入 Folia", toggleMvBackground,
                            Modifier.testTag("player_wide_mv_background"), selected = mvBackground, loading = mvLoading,
                            onLongClick = enterFoliaStage, onLongClickLabel = "进入 Folia 歌词舞台",
                            longPressHint = !mvBackground)
                        else PlayerIconButton(PlayerIcon.EFFECTS, "打开 Folia 歌词舞台", enterFoliaStage,
                            Modifier.testTag("player_wide_folia_stage"), selected = foliaStage)
                        PlayerIconButton(PlayerIcon.MORE, "播放与歌词设置", { overlay = PlayerOverlay.OPTIONS },
                            Modifier.testTag("lyrics_options"))
                    }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { pagerArtwork.container = it; pagerArtwork.updateBounds() }) {
                    HorizontalPager(pager, Modifier.fillMaxSize().testTag("player_content_pager")
                        .semantics { this[PlayerPagePosition] = pager.currentPage + pager.currentPageOffsetFraction },
                        beyondViewportPageCount = 1,
                        userScrollEnabled = expanded && !mvBackground && !foliaSeeking &&
                            (!sidePage.active || sidePage.pagerDragging) && sheetDrag?.state?.dragging != true,
                        overscrollEffect = null,
                        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(240, easing = LinearOutSlowInEasing))) { page ->
                        Box(Modifier.fillMaxSize()
                            .then(if (pager.currentPage != page && !pager.isScrollInProgress)
                            Modifier.clearAndSetSemantics {} else Modifier).onGloballyPositioned {
                            if (page == 0) pagerArtwork.coverPage = it else pagerArtwork.lyricsPage = it
                            pagerArtwork.updateBounds()
                        }) {
                        if (page == 1 && !fullScreenFolia) Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                                .testTag("player_lyrics_header"), verticalAlignment = Alignment.CenterVertically) {
                                PlayerArtwork(queue.current?.cover.orEmpty(), Modifier.size(46.dp).testTag("player_lyrics_cover"), pixels = 800,
                                    pagerRole = PlayerPagerArtworkRole.LYRICS)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    PlayerSongText(displayTrackTitle.ifBlank { "还没有选择歌曲" }, role = PlayerPagerArtworkRole.LYRICS, color = Color.White,
                                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    PlayerSongText(queue.current?.artists.orEmpty(), artistLink, role = PlayerPagerArtworkRole.LYRICS, subtitle = true, fontSize = 13.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            LyricsPanel(vm, Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { lyricsCoordinates = it },
                                active = pager.currentPage == 1 || pager.isScrollInProgress,
                                expandLyricsWindow = controlsHidden, prepare = fancyLyrics && lyricsPrepared, foliaMode = foliaMode,
                                foliaChromeHidden = hideControls.value > .5f)
                        } else if (page == 0) CoverContent(vm, Modifier.fillMaxSize(), transitionTarget = pager.currentPage == 0,
                            active = pager.currentPage == 0 || pager.isScrollInProgress,
                            pagerRole = PlayerPagerArtworkRole.COVER,
                            immersive = immersiveCover, artistLink = artistLink, expand = expansion,
                            onPreviewCoordinates = { previewCoordinates = it })
                        }
                    }
                    }
                    Column(Modifier.fillMaxWidth().testTag("player_controls")
                        .onGloballyPositioned { controlsCoordinates = it }) {
                        PlayerControlsRegion(controlsVisibility) { functions() }
                        PlayerControlsRegion(controlsVisibility, Modifier.testTag("player_transport_transition")) {
                            transport()
                        }
                    }
                }
            }
            if (!widePlayer) {
                PlayerControlsRegion({ 1f - controlsVisibility() }) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text("显示控制面板", color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                            modifier = Modifier.testTag("lyrics_reveal_controls").clickable(role = Role.Button) {
                                controlsHidden = false; controlsRevealed = true
                            }.padding(horizontal = 24.dp, vertical = 14.dp))
                    }
                }
            }
        }
        }
        if (queueGesture || overlay == PlayerOverlay.QUEUE)
            PlaybackQueuePage(vm, queueMotion, false, "player_queue_sheet", dismiss, actions)
        if (!widePlayer && (!roaming.active || roaming.choice.code != "PUZZLE_MODE_RCMD"))
            if (immersiveCover && !mvBackground) PlayerPagerSongText(pagerArtwork) else if (!mvBackground) PlayerPagerArtwork(pagerArtwork, queue.current)
        }
        }
        }
        }
        }
        }
        if (biliVideo && queue.current?.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            io.github.currencortex.music.feature.bili.BiliNativeVideoScreen(
                controller = vm.container.playerController,
                bvid = queue.current?.externalIds?.bilibiliBvid.orEmpty(),
                initialCid = queue.current?.externalIds?.bilibiliCid.orEmpty(),
                cookie = vm.container.biliSession.cookieHeader(),
                title = displayTrackTitle,
                uploader = queue.current?.artists.orEmpty(),
                initialCategory = queue.current?.category.orEmpty(),
                initialDurationMs = queue.current?.durationMs ?: 0L,
                liked = vm.actions.collectAsStateWithLifecycle().value.liked == true,
                likeCount = biliStats?.like ?: 0L,
                commentCount = biliStats?.reply ?: 0L,
                favoriteCount = biliStats?.favorite ?: 0L,
                viewCount = biliStats?.view ?: 0L,
                onClose = { setBiliVideo(false) },
                onLike = { vm.togglePrimaryLike() },
                onFavorite = {
                    val bvid = queue.current?.externalIds?.bilibiliBvid
                    android.util.Log.d("PromXVideo", "favorite tapped bvid=" + bvid)
                    biliFavoriteFor = bvid
                },
                onShare = { overlay = PlayerOverlay.SHARE },
                onComments = { overlay = PlayerOverlay.COMMENTS },
                onQualityChanged = { vm.container.playerController.refreshSource() },
                onDownload = {
                    val bvid = queue.current?.externalIds?.bilibiliBvid.orEmpty()
                    val title = displayTrackTitle
                    if (bvid.isNotBlank()) {
                        android.widget.Toast.makeText(playerContext, "开始缓存视频…",
                            android.widget.Toast.LENGTH_SHORT).show()
                        io.github.currencortex.music.feature.bili.BiliHost.of().download(bvid, title)
                    }
                },
                onRelatedSelected = { video ->
                    val relatedId = video.bvid.hashCode().toLong().let { if (it < 0) -it else it }
                    val next = io.github.currencortex.music.data.song.Song(
                        id = relatedId,
                        name = video.title,
                        artists = video.owner.ifBlank { "哔哩哔哩" } + " · 哔哩哔哩",
                        cover = video.cover,
                        durationMs = video.durationSec * 1000L,
                        video = true,
                        musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                        externalIds = io.github.currencortex.music.data.song.SongExternalIds(
                            bilibiliBvid = video.bvid,
                        ),
                    )
                    biliVideoTrackId = relatedId
                    biliVideo = true
                    io.github.currencortex.music.core.alt.BiliQuality.wantsVideo = true
                    vm.player.playSong(next)
                },
            )
        }
        BackHandler(enabled = sidePage.active && !sideDialog && overlay == PlayerOverlay.NONE && !queueGesture) { sidePage.close() }
        if (foliaScenePicker) FoliaScenePickerDialog(settings.lyricsDisplay.foliaScene,
            onSelect = { scene -> vm.lyricsDisplay { display -> display.copy(foliaScene = scene) } },
            onDismiss = { foliaScenePicker = false })
        biliFavoriteFor?.takeIf { it.isNotBlank() }?.let { bvid ->
            io.github.currencortex.music.feature.bili.BiliFavoriteSheet(
                container = vm.container, bvid = bvid, onDismiss = { biliFavoriteFor = null })
        }
        when (overlay) {
            PlayerOverlay.NONE -> Unit
            PlayerOverlay.BACKGROUND -> PlayerBackgroundDialog(vm.container, dismiss)
            PlayerOverlay.LYRICS_MANAGEMENT -> queue.current?.let { PlayerLyricsManagementDialog(vm.container, it, dismiss) }
            PlayerOverlay.SHARE -> queue.current?.let { io.github.currencortex.music.feature.share.SongShareDialog(it, dismiss) }
            PlayerOverlay.COMMENTS -> PlayerCommentsDialog(comments, dismiss, { vm.loadComments() }, { vm.loadComments(more = true) },
                commentComposer, vm::onCommentEdited, vm::replyTo, vm::sendComment,
                vm::selectCommentSort, vm::loadCommentReplies, vm::likeComment, vm::deleteComment,
                title = when (queue.current?.musicSource) {
                    io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC -> "QQ 音乐评论"
                    io.github.currencortex.music.data.song.MusicSource.BILIBILI -> "哔哩哔哩评论"
                    else -> "网易云评论"
                })
            PlayerOverlay.QUEUE -> Unit
            PlayerOverlay.ROAMING -> io.github.currencortex.music.feature.roaming.PersonalRoamingDialog(vm.container, dismiss) {
                onAiNavigate("user/binding")
            }
            PlayerOverlay.MODE -> MusicChoiceDialog("播放模式", dismiss) {
                PlaybackMode.entries.filter { !together.active || it != PlaybackMode.HEART }.forEach { mode -> MusicDialogChoiceRow(mode.label, mode == playbackMode,
                    onClick = { vm.setPlaybackMode(mode); overlay = PlayerOverlay.QUEUE },
                    enabled = (if (together.active) !together.busy else state.mode == PlayerMode.LOCAL) && (mode != PlaybackMode.HEART ||
                        io.github.currencortex.music.data.song.NeteaseSongActionsRepository.songId(queue.current) != null),
                    modifier = Modifier.testTag("playback_mode_${mode.name}").semantics { selected = mode == playbackMode }) }
            }
            PlayerOverlay.QUALITY -> AudioQualitySheet(settings.quality, vm::quality, dismiss, state.mode == PlayerMode.LOCAL,
                onNetwork = onNetwork, song = queue.current, insights = vm.container.musicRepository.insights,
                audioInfo = state.audioInfo, source = settings.primarySource,
                onRefresh = if (state.mode == PlayerMode.LOCAL && state.song?.musicSource == io.github.currencortex.music.data.song.MusicSource.NETEASE && !state.resolving)
                    ({ vm.container.playerController.refreshSource() }) else null)
            PlayerOverlay.ACTIONS -> if (queue.current != null) MusicChoiceDialog("歌曲操作", dismiss) { actions?.invoke(queue.current!!) }
            PlayerOverlay.OPTIONS -> MusicChoiceDialog("播放与歌词", dismiss) {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (io.github.currencortex.music.data.song.NeteaseSongActionsRepository.songId(queue.current) != null)
                    MusicDestinationRow("问AI", summary = "了解这首歌 · 私有对话", modifier = Modifier.testTag("open_player_ai"),
                        onClick = { vm.openAi(); overlay = PlayerOverlay.AI })
                MusicDestinationRow(if (content == PlayerContent.LYRICS) {
                    if (widePlayer) "显示控制栏" else "显示封面"
                } else "显示歌词",
                    modifier = Modifier.testTag("open_lyrics"), onClick = {
                        dismiss()
                        scope.launch { pager.animateScrollToPage(if (pager.targetPage == 0) 1 else 0,
                            animationSpec = tween(260, easing = LinearOutSlowInEasing)) }
                    })
                if (controlsRevealed && settings.lyricsDisplay.hideControls && content == PlayerContent.LYRICS)
                    MusicDestinationRow("隐藏控制面板", modifier = Modifier.testTag("lyrics_conceal_controls"), onClick = { controlsRevealed = false; dismiss() })
                if (actions != null && queue.current != null) MusicDestinationRow("歌曲操作", onClick = {
                    val song = queue.current ?: return@MusicDestinationRow
                    if (menuHost == null) overlay = PlayerOverlay.ACTIONS
                    else { dismiss(); menuHost(SongMenu(song, {}, {}, {}, extra = { actions(song) }, transport = false)) }
                })
                MusicDestinationRow("播放音质", summary = if (state.song?.privateLibrary == true) state.song?.originalQualityLabel.orEmpty() else state.audioInfo?.label ?: settings.quality.label, onClick = { overlay = PlayerOverlay.QUALITY }, enabled = state.mode == PlayerMode.LOCAL && state.song?.privateLibrary != true)
                MusicDestinationRow("播放背景", summary = if (backgroundImage != null) "自定义图片 · 模糊 ${appearance.playerBackgroundBlur.toInt()}%"
                    else if (appearance.playerDynamicBackground) "流动背景 · 跟随歌曲封面"
                    else "封面毛玻璃 · 模糊 ${appearance.playerBackgroundBlur.toInt()}%",
                    modifier = Modifier.testTag("open_player_background"), onClick = { overlay = PlayerOverlay.BACKGROUND })
                MusicDestinationRow("私人漫游", summary = if (roaming.active) roaming.choice.title else "按你的喜好连续推荐",
                    modifier = Modifier.testTag("open_player_roaming"), onClick = { overlay = PlayerOverlay.ROAMING })
                if (queue.current?.musicSource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC &&
                    queue.current?.video != true) {
                    MusicDestinationRow("相似歌曲电台", summary = "从当前歌曲继续生成 QQ 音乐推荐队列",
                        modifier = Modifier.testTag("play_qq_similar"), onClick = {
                            dismiss()
                            vm.playQqSimilarSongs()
                        })
                }
                if (onEqualizer != null) MusicDestinationRow("音效", onClick = { dismiss(); onEqualizer() },
                    modifier = Modifier.testTag("open_player_equalizer"))
                MusicDestinationRow("播放模式", summary = playbackMode.label, onClick = { overlay = PlayerOverlay.MODE }, enabled = state.mode == PlayerMode.LOCAL || (together.active && !together.busy))
                MusicDestinationRow("定时关闭", summary = sleepSummary(sleepState), modifier = Modifier.testTag("open_sleep_timer"),
                    onClick = { overlay = PlayerOverlay.SLEEP })
                MusicDestinationRow("歌词显示", summary = "${settings.lyricsDisplay.style.label} · ${settings.lyricsFont.label} · 字号 ${settings.lyricsFontSize.toInt()}",
                    modifier = Modifier.testTag("open_lyrics_display"), onClick = { overlay = PlayerOverlay.LYRICS })
                if (queue.current != null && queue.current?.video != true)
                    MusicDestinationRow("歌词管理", summary = "来源 · 在线匹配 · 单曲偏移 · 导入导出",
                        modifier = Modifier.testTag("open_lyrics_management"), onClick = { overlay = PlayerOverlay.LYRICS_MANAGEMENT })
                if (onRoom != null && state.song?.privateLibrary != true) MusicDestinationRow(if (state.mode == PlayerMode.ROOM) "一起听控制" else "一起听", onClick = { dismiss(); onRoom() })
                }
            }
            PlayerOverlay.AI -> PlayerAiDialog(vm.ai, dismiss) { card ->
                vm.ai.close(); dismiss()
                if (card.type == "song") vm.playAiCard(card)
                else onAiNavigate("lib/${if (card.type == "playlist") "ncmplaylist" else card.type}/${card.id}")
            }
            PlayerOverlay.WEIGHT -> MusicChoiceDialog("歌词字重", { overlay = PlayerOverlay.LYRICS }) {
                LyricsWeight.entries.forEach { weight ->
                    MusicDialogChoiceRow(weight.label, weight == settings.lyricsWeight,
                        modifier = Modifier.testTag("lyrics_weight_${weight.name}"),
                        onClick = { vm.lyricsWeight(weight); overlay = PlayerOverlay.LYRICS })
                }
            }
            PlayerOverlay.KARAOKE -> MusicChoiceDialog("卡拉OK（逐字）歌词动画兼容策略", { overlay = PlayerOverlay.LYRICS }) {
                KaraokeScope.entries.forEach { scope ->
                    MusicDialogChoiceRow(scope.label, scope == settings.lyricsDisplay.karaokeScope,
                        summary = when (scope) {
                            KaraokeScope.CURRENT -> "只对正在播放的行逐字高亮"
                            KaraokeScope.ALL -> "所有含逐字时间的可见行跟随进度高亮"
                            KaraokeScope.ALWAYS -> "全部行启用；普通歌词按行时长近似扫亮"
                        },
                        modifier = Modifier.testTag("karaoke_scope_${scope.name}"),
                        onClick = { vm.lyricsDisplay { it.copy(karaokeScope = scope) }; overlay = PlayerOverlay.LYRICS })
                }
            }
            PlayerOverlay.PERFORMANCE -> MusicChoiceDialog("歌词性能", { overlay = PlayerOverlay.LYRICS }) {
                LyricsRenderQuality.entries.forEach { quality ->
                    MusicDialogChoiceRow(quality.label, quality == settings.lyricsDisplay.renderQuality,
                        summary = when (quality) {
                            LyricsRenderQuality.AUTO -> "低内存或旧版图形能力自动降到省电"
                            LyricsRenderQuality.FULL -> "保留模糊、辉光、上提和全部轻量粒子"
                            LyricsRenderQuality.BALANCED -> "保留当前行模糊与辉光，关闭额外粒子场景"
                            LyricsRenderQuality.POWER_SAVER -> "关闭实时模糊、辉光和逐字上提"
                        },
                        modifier = Modifier.testTag("lyrics_performance_${quality.name}"),
                        onClick = { vm.lyricsDisplay { it.copy(renderQuality = quality) }; overlay = PlayerOverlay.LYRICS })
                }
            }
            PlayerOverlay.SLEEP -> SleepTimerSheet(vm.sleepTimer, dismiss)
            PlayerOverlay.LYRICS -> {
                val lyrics by vm.lyrics.collectAsStateWithLifecycle()
                val song by vm.currentSong.collectAsStateWithLifecycle()
                val currentLyric = currentSettingsLyric(lyrics, state, song, settings.lyricsOffsetMs)
                MusicDialog("歌词显示", dismiss) {
                    LyricsDisplaySettings(settings.lyricsFontSize, vm::lyricsFontSize, settings.lyricsWeight, { overlay = PlayerOverlay.WEIGHT },
                        settings.lyricsDisplay, vm::lyricsDisplay, { overlay = PlayerOverlay.KARAOKE },
                        onRenderQuality = { overlay = PlayerOverlay.PERFORMANCE },
                        settings.lyricsFont, settings.lyricsFontFile, vm::lyricsFont, vm::lyricsFontFile, currentLyric,
                        onStyle = { style -> vm.lyricsDisplay { it.copy(style = style) } })
                }
            }
        }
    }
}

@Composable private fun CoverContent(vm: PlayerViewModel, modifier: Modifier, transitionTarget: Boolean = true,
    active: Boolean = true, pagerRole: PlayerPagerArtworkRole? = null, showPreview: Boolean = true,
    immersive: Boolean = false, artistLink: Modifier = Modifier, expand: () -> Float = { 1f },
    onPreviewCoordinates: (LayoutCoordinates) -> Unit = {}) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val song = queue.current
    val roaming by vm.container.personalRoaming.state.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sheetExpand = expand().coerceIn(0f, 1f)
        val coverSize = minOf(maxWidth * .92f, (maxHeight - if (showPreview) 200.dp else 128.dp).coerceAtLeast(72.dp), 360.dp) *
            (0.62f + 0.38f * sheetExpand)
        Column(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val puzzle = roaming.active && roaming.choice.code == "PUZZLE_MODE_RCMD"
            if (immersive) {
                Spacer(Modifier.weight(1f))
                // Retain an endpoint owner; the drawing bounds use the entire player viewport.
                PlayerArtwork(song?.cover.orEmpty(), Modifier.size(1.dp).testTag("player_cover").graphicsLayer { alpha = 0f },
                    transitionTarget = transitionTarget, pagerRole = pagerRole)
            } else if (puzzle) Box(Modifier.size(coverSize)) {
                PlayerArtwork(song?.cover.orEmpty(), Modifier.fillMaxSize().testTag("player_cover")
                    .graphicsLayer { alpha = 0f }, transitionTarget = transitionTarget)
                io.github.currencortex.music.feature.roaming.RoamingCoverPuzzle(song?.cover.orEmpty(), song?.id, showCaption = false)
            } else if (false) {
                // 视频模式：画面取代圆形封面，高度按视频比例走，标题紧跟其后
            } else PlayerArtwork(song?.cover.orEmpty(), Modifier.size(coverSize).testTag("player_cover"),
                transitionTarget = transitionTarget, pagerRole = pagerRole)
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayerSongText(song?.name ?: "还没有选择歌曲", Modifier.testTag("player_song_title"), role = pagerRole, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PlayerSongText(song?.artists.orEmpty(), artistLink, role = pagerRole, subtitle = true, fontSize = 18.sp, color = Color.White.copy(alpha = .55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showPreview) CoverLyricPreview(vm, Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp)
                .onGloballyPositioned(onPreviewCoordinates), active)
        }
    }
}

/** Artwork stays at the center of its pane while controls and lyrics slide beside it. */
@Composable private fun WideCoverContent(vm: PlayerViewModel, modifier: Modifier, immersive: Boolean, expand: () -> Float = { 1f }) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val roaming by vm.container.personalRoaming.state.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier.fillMaxWidth().testTag("player_wide_cover_content")) {
        val sheetExpand = expand().coerceIn(0f, 1f)
        val coverSize = minOf(maxWidth * .96f, (maxHeight - 8.dp).coerceAtLeast(0.dp), 360.dp) *
            (0.62f + 0.38f * sheetExpand)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val puzzle = roaming.active && roaming.choice.code == "PUZZLE_MODE_RCMD"
            if (puzzle) Box(Modifier.size(coverSize)) {
                PlayerArtwork(queue.current?.cover.orEmpty(), Modifier.fillMaxSize().testTag("player_cover")
                    .graphicsLayer { alpha = 0f })
                io.github.currencortex.music.feature.roaming.RoamingCoverPuzzle(queue.current?.cover.orEmpty(), queue.current?.id, showCaption = false)
            } else if (!immersive) PlayerArtwork(queue.current?.cover.orEmpty(), Modifier.size(coverSize).testTag("player_cover"))
        }
    }
}

@Composable private fun WideControlsContent(vm: PlayerViewModel, onToggle: () -> Unit,
    functions: @Composable () -> Unit, artistLink: Modifier) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val displayTitle = remember(queue.current?.name) {
        queue.current?.name.orEmpty().decodedMediaText()
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player_wide_controls")) {
        val compact = maxHeight < 360.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp, Alignment.CenterVertically)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(displayTitle.ifBlank { "还没有选择歌曲" }, Modifier.testTag("player_song_title"),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(queue.current?.artists.orEmpty(), Modifier.padding(top = 6.dp).then(artistLink),
                    color = Color.White.copy(alpha = .55f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerTransport(vm, onToggle, compact = compact)
            functions()
        }
    }
}

private fun String.decodedMediaText(): String {
    var value = this
    repeat(3) {
        val decoded = value
            .replace("&amp;amp;", "&amp;")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        if (decoded == value) return value
        value = decoded
    }
    return value
}

@Composable private fun LyricsPanel(vm: PlayerViewModel, modifier: Modifier, active: Boolean = true, minimal: Boolean = false,
    expandLyricsWindow: Boolean = false, prepare: Boolean = false, foliaMode: String? = null,
    foliaChromeHidden: Boolean = true) {
    // The pager keeps its neighbouring header measured for shared-element endpoints.
    // Apple paragraphs are prepared in the settled player's idle interval; waiting for
    // the first horizontal drag would put shaping and raster work on that gesture frame.
    // Keep the prepared/visited composition so returning does not repeat that work.
    var revealed by remember { mutableStateOf(false) }
    if (!active && !prepare && !revealed) {
        Box(modifier.testTag("player_lyrics_deferred"))
        return
    }
    SideEffect { revealed = true }
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val player by vm.state.collectAsStateWithLifecycle()
    val position = rememberLyricsPosition(if (active) player else player.copy(playing = false))
    if (lyrics.matches(player.song) && lyrics.document.lines.isNotEmpty()) key(lyrics.songKey, lyrics.document) {
        // 哔哩哔哩 字幕是机器听写，界面必须声明“可能不准确”，否则用户会以为是官方歌词
        val autoNote = lyrics.origin?.provider == "bilibili"
        if (autoNote) Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            val lang = io.github.currencortex.music.core.alt.BiliPlayback.lastSubtitleInfo
            Text("字幕由 哔哩哔哩自动生成" + if (lang.isBlank()) "" else "（" + lang + "）" + "，可能存在误差",
                Modifier.padding(bottom = 4.dp).testTag("lyrics_auto_note"),
                fontSize = 10.5.sp, color = Color.White.copy(alpha = .42f), letterSpacing = .3.sp)
        }
        val settings by vm.settings.collectAsStateWithLifecycle()
        if (foliaMode != null) {
            val stageLyrics = remember(lyrics, settings.lyricsDisplay.immersiveRomanization) {
                if (settings.lyricsDisplay.immersiveRomanization)
                    lyrics.copy(document = lyrics.document.withImmersiveRomanization()) else lyrics
            }
            FoliaLyricsStage(
                song = player.song,
                lyrics = stageLyrics,
                player = player,
                position = position,
                lyricOffsetMs = settings.lyricsOffsetMs - lyrics.songOffsetMs,
                mode = foliaMode,
                translation = settings.lyricsDisplay.translation,
                romanization = settings.lyricsDisplay.romanization,
                chromeHidden = foliaChromeHidden,
                renderQuality = settings.lyricsDisplay.renderQuality,
                audioMeter = vm.container.dockAudioLevel,
                onError = { vm.container.errorNotices.show("Folia 歌词舞台加载失败") },
                modifier = modifier,
            )
        } else {
            val rhythmGlow = rememberLyricsRhythmGlow(vm.container.dockAudioLevel, active && settings.lyricsDisplay.glow,
                active && player.playing && !player.loading)
            LyricsScreen(lyrics.document, position, vm.player::seek, modifier, player.canControlPlayback,
                settings.lyricsDisplay.translation, settings.lyricsDisplay.romanization, settings.lyricsDisplay.wordAnimation,
                settings.lyricsDisplay.blur, if (minimal) settings.lyricsFontSize * .75f else settings.lyricsFontSize,
                settings.lyricsWeight, settings.lyricsOffsetMs - lyrics.songOffsetMs,
                settings.lyricsDisplay.copy(hideControls = settings.lyricsDisplay.hideControls || expandLyricsWindow),
                minimal = minimal, perspective = settings.lyricsDisplay.perspective, cover = player.song?.cover.orEmpty(),
                mediaDurationMs = player.durationMs, playbackRunning = active && player.playing && !player.loading,
                rhythmGlow = rhythmGlow)
        }
    } else Box(modifier.testTag("lyrics_panel"), contentAlignment = Alignment.Center) {
        if (lyrics.loading) Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            MusicPlaceholder(Modifier.fillMaxWidth(.8f).height(30.dp))
            MusicPlaceholder(Modifier.fillMaxWidth().height(30.dp))
            MusicPlaceholder(Modifier.fillMaxWidth(.6f).height(30.dp))
            Text("正在加载歌词", fontSize = 13.sp, color = Color.White.copy(alpha = .45f))
        } else {
            // 哔哩哔哩 视频没有字幕时明确说“暂无字幕”，并说明不会用别处的歌词顶替
            val isBili = lyrics.origin?.provider == "bilibili"
            val message = lyrics.error ?: if (isBili) "这条视频没有字幕" else "暂无歌词"
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = Color.White.copy(alpha = .62f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (isBili) Text("哔哩哔哩 未提供自动字幕，不会用其它来源的歌词替代",
                    fontSize = 12.sp, color = Color.White.copy(alpha = .38f))
            }
        }
    }
}
