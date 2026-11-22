// SPDX-License-Identifier: GPL-3.0-only
// 「我喜欢」的入口在每个音源下形态不同：
//   网易云 → 喜欢列表；QQ → 喜欢列表（未登录则空）；B站 → 直接进「我喜欢」收藏夹
package io.github.currencortex.music.feature.library

import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.data.song.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 返回点击「我喜欢」应该去的路由 */
suspend fun likesRouteFor(container: AppContainer): String {
    val mode = container.musicSettings.state.value.primarySource
    if (mode != MusicSource.BILIBILI) return "lib/likes"
    val session = container.biliSession
    if (!session.loggedIn) return "lib/playlists"
    val folderId = withContext(Dispatchers.IO) {
        runCatching {
            val folders = io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(
                session.cookieHeader(), session.profile!!.mid)
            (folders.firstOrNull { it.title.contains("我喜欢") } ?: folders.firstOrNull())?.id
        }.getOrNull()
    }
    // 收藏夹详情就是「之前的收藏夹界面」
    return folderId?.let { "lib/playlist/$it" } ?: "lib/playlists"
}
