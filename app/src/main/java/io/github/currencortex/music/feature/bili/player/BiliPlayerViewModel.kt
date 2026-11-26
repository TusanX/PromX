// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.core.alt.BiliQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BiliPlayerUiState(
    val bvid: String = "",
    val cid: String = "",
    val info: BiliPlayback.VideoInfo? = null,
    val stats: BiliPlayback.Stats? = null,
    val related: List<BiliPlayback.RelatedVideo> = emptyList(),
    val qualities: List<BiliQuality.Option> = emptyList(),
    val danmaku: List<BiliPlayback.Danmaku> = emptyList(),
    val liked: Boolean = false,
    val likeBusy: Boolean = false,
    val metadataLoading: Boolean = true,
    val sourceLoading: Boolean = false,
    val sourceError: String? = null,
    val message: String? = null,
)

/** Coordinates metadata, authenticated interactions, and a page-owned native video session. */
internal class BiliPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private var generation = 0
    private var sourceGeneration = 0
    private var metadataJob: Job? = null
    private var cookie = ""
    private var csrf = ""
    private var preferredQuality = BiliQuality.preferredQn

    internal val session = BiliPlayerSession(application.applicationContext, cookie)

    private val _ui = MutableStateFlow(BiliPlayerUiState())
    val ui: StateFlow<BiliPlayerUiState> = _ui.asStateFlow()

    fun load(bvid: String, initialCid: String, cookieHeader: String, csrfValue: String) {
        if (bvid.isBlank()) return
        cookie = cookieHeader
        csrf = csrfValue
        session.reopen(cookieHeader)
        val sameVideo = _ui.value.bvid == bvid && _ui.value.info != null &&
            (initialCid.isBlank() || _ui.value.cid == initialCid)
        if (sameVideo) {
            if (!session.snapshot().prepared) {
                _ui.value = _ui.value.copy(sourceLoading = true, sourceError = null)
                loadSource(generation, ++sourceGeneration, playWhenReady = true, startPositionMs = 0L)
            }
            return
        }
        val request = ++generation
        sourceGeneration++
        metadataJob?.cancel()
        _ui.value = BiliPlayerUiState(
            bvid = bvid,
            cid = initialCid,
            metadataLoading = true,
            sourceLoading = true,
        )
        metadataJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                coroutineScope {
                    val info = async { BiliPlayback.videoInfo(bvid, cookie) }
                    val stats = async { BiliPlayback.stats(bvid, cookie) }
                    val related = async { BiliPlayback.related(bvid, cookie) }
                    val liked = async { if (cookie.isBlank()) false else BiliAuth.isLiked(cookie, bvid) ?: false }
                    val infoValue = info.await()
                    val cid = initialCid.ifBlank { infoValue?.cid.orEmpty() }
                    val qualities = async {
                        if (cid.isBlank()) emptyList()
                        else BiliPlayback.qualityOptions(bvid, cid, cookie)
                    }
                    val danmaku = async {
                        if (cid.isBlank()) emptyList() else BiliPlayback.danmaku(cid)
                    }
                    LoadedVideo(
                        info = infoValue,
                        cid = cid,
                        stats = stats.await(),
                        related = related.await(),
                        qualities = qualities.await(),
                        danmaku = danmaku.await(),
                        liked = liked.await(),
                    )
                }
            }
            if (request != generation) return@launch
            if (loaded.info == null) {
                _ui.value = _ui.value.copy(
                    metadataLoading = false,
                    sourceLoading = false,
                    sourceError = "没有读取到这条视频的信息，可能已失效或仅限登录访问",
                )
                return@launch
            }
            _ui.value = _ui.value.copy(
                cid = loaded.cid,
                info = loaded.info,
                stats = loaded.stats,
                related = loaded.related,
                qualities = loaded.qualities,
                danmaku = loaded.danmaku,
                liked = loaded.liked,
                metadataLoading = false,
                sourceLoading = true,
                sourceError = null,
            )
            loadSource(request, sourceGeneration, playWhenReady = true, startPositionMs = 0L)
        }
    }

    fun playPart(page: BiliPlayback.Page) {
        val state = _ui.value
        if (page.cid.isBlank() || page.cid == state.cid) return
        session.pause()
        val request = ++generation
        sourceGeneration++
        metadataJob?.cancel()
        _ui.value = state.copy(cid = page.cid, metadataLoading = false, sourceLoading = true, sourceError = null)
        metadataJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                coroutineScope {
                    val qualities = async { BiliPlayback.qualityOptions(state.bvid, page.cid, cookie) }
                    val danmaku = async { BiliPlayback.danmaku(page.cid) }
                    LoadedPart(qualities.await(), danmaku.await())
                }
            }
            if (request != generation) return@launch
            _ui.value = _ui.value.copy(
                qualities = loaded.qualities,
                danmaku = loaded.danmaku,
                metadataLoading = false,
            )
            loadSource(request, sourceGeneration, playWhenReady = true, startPositionMs = 0L)
        }
    }

    fun openVideo(bvid: String) {
        if (bvid.isBlank() || bvid == _ui.value.bvid) return
        session.pause()
        load(bvid = bvid, initialCid = "", cookieHeader = cookie, csrfValue = csrf)
    }

    fun selectQuality(qn: Int) {
        val state = _ui.value
        if (qn <= 0 || state.cid.isBlank() || state.sourceLoading) return
        val option = state.qualities.firstOrNull { it.qn == qn }
        if (option != null && !option.available) {
            _ui.value = state.copy(message = when {
                option.requiresVip -> "此清晰度需要哔哩哔哩大会员"
                option.requiresLogin -> "登录哔哩哔哩后可选择此清晰度"
                else -> "这条视频暂不支持此清晰度"
            })
            return
        }
        preferredQuality = qn
        _ui.value = state.copy(sourceLoading = true, sourceError = null, message = null)
        val snapshot = session.snapshot()
        loadSource(generation, ++sourceGeneration, snapshot.playWhenReady, snapshot.positionMs)
    }

    fun retrySource() {
        val state = _ui.value
        if (state.cid.isBlank()) return
        val snapshot = session.snapshot()
        _ui.value = state.copy(sourceLoading = true, sourceError = null)
        loadSource(generation, ++sourceGeneration, snapshot.playWhenReady, snapshot.positionMs)
    }

    fun toggleLike() {
        val state = _ui.value
        if (state.likeBusy || state.bvid.isBlank()) return
        if (cookie.isBlank() || csrf.isBlank()) {
            _ui.value = state.copy(message = "请先登录哔哩哔哩")
            return
        }
        val target = !state.liked
        _ui.value = state.copy(
            liked = target,
            likeBusy = true,
            stats = state.stats?.copy(like = (state.stats.like + if (target) 1 else -1).coerceAtLeast(0L)),
        )
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { BiliAuth.setLiked(cookie, state.bvid, target, csrf) }
            val latest = _ui.value
            if (latest.bvid != state.bvid) return@launch
            _ui.value = if (ok) {
                latest.copy(likeBusy = false)
            } else {
                latest.copy(
                    liked = state.liked,
                    likeBusy = false,
                    stats = state.stats,
                    message = "点赞没有写入成功，请稍后重试",
                )
            }
        }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    fun pauseForBackground() = session.pause()

    fun shutdown() {
        session.release()
    }

    override fun onCleared() {
        session.release()
        super.onCleared()
    }

    private fun loadSource(
        requestGeneration: Int,
        sourceRequest: Int,
        playWhenReady: Boolean,
        startPositionMs: Long,
    ) {
        val state = _ui.value
        if (state.cid.isBlank()) return
        viewModelScope.launch {
            val media = withContext(Dispatchers.IO) {
                resolveMedia(state.bvid, state.cid, preferredQuality)
            }
            if (requestGeneration != generation || sourceRequest != sourceGeneration) return@launch
            if (media == null) {
                _ui.value = _ui.value.copy(
                    sourceLoading = false,
                    sourceError = if (cookie.isBlank())
                        "暂时没有可用视频线路，登录哔哩哔哩后重试"
                    else "视频线路已失效，可能下架或该清晰度需要更高权限",
                )
                return@launch
            }
            session.setMedia(media, startPositionMs, playWhenReady)
            _ui.value = _ui.value.copy(
                sourceLoading = false,
                sourceError = null,
                message = null,
            )
        }
    }

    private fun resolveMedia(bvid: String, cid: String, preferred: Int): BiliPlayerMedia? {
        val plan = BiliPlayback.plan(bvid, cid, preferred, cookie)
        if (plan?.video != null) {
            return BiliPlayerMedia(
                uri = BiliPlayback.manifestDataUri(plan),
                mimeType = BiliPlayerSession.DASH_MIME,
                qualityQn = plan.qualityQn,
                qualityLabel = plan.quality,
            )
        }
        plan?.mp4?.let { url ->
            return BiliPlayerMedia(
                uri = url,
                mimeType = BiliPlayerSession.MP4_MIME,
                qualityQn = plan.qualityQn,
                qualityLabel = plan.quality,
            )
        }
        val resolved = BiliPlayback.resolve(bvid, cid, cookie).url ?: return null
        return BiliPlayerMedia(
            uri = resolved,
            mimeType = BiliPlayerSession.MP4_MIME,
            qualityQn = 0,
            qualityLabel = "自动",
        )
    }

    private data class LoadedVideo(
        val info: BiliPlayback.VideoInfo?,
        val cid: String,
        val stats: BiliPlayback.Stats?,
        val related: List<BiliPlayback.RelatedVideo>,
        val qualities: List<BiliQuality.Option>,
        val danmaku: List<BiliPlayback.Danmaku>,
        val liked: Boolean,
    )

    private data class LoadedPart(
        val qualities: List<BiliQuality.Option>,
        val danmaku: List<BiliPlayback.Danmaku>,
    )
}
