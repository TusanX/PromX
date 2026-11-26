// SPDX-License-Identifier: GPL-3.0-only
// 面板宿主桥：歌曲菜单等位置不持有 Compose 状态，通过它请 App 层打开 哔哩哔哩 面板
package io.github.currencortex.music.feature.bili

import io.github.currencortex.music.core.alt.BiliPlayback

object BiliHost {
    private var instance: BiliHostImpl? = null
    fun of(): BiliHostImpl = instance ?: BiliHostImpl().also { instance = it }
    fun install(impl: BiliHostImpl) { instance = impl }

    class BiliHostImpl {
        var onCreator: ((Long) -> Unit)? = null
        var onRelated: ((String) -> Unit)? = null
        var onPlayVideo: ((BiliPlayback.RelatedVideo) -> Unit)? = null
        fun openCreatorFor(bvid: String) {
            Thread {
                val mid = BiliPlayback.uploaderMid(bvid)
                if (mid > 0) onCreator?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(mid) } }
            }.start()
        }
        fun openRelated(bvid: String) { onRelated?.invoke(bvid) }
        var onDownload: ((String, String) -> Unit)? = null
        fun download(bvid: String, title: String) { onDownload?.invoke(bvid, title) }
    }
}
