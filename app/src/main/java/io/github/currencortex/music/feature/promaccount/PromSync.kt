// SPDX-License-Identifier: GPL-3.0-only
// Prom 账号云同步：登录后把本地状态推到 SCF，启动时拉回。
// 目前同步的是账号资料 + 少量本地偏好；歌曲/歌单的本地库（Room）双写是下一步。
package io.github.currencortex.music.feature.promaccount

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PromSync {
    private const val PREF = "prom_sync"

    private fun localBlob(c: Context): JSONObject {
        val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("schema", 1)
            put("updatedAt", System.currentTimeMillis())
            put("nickname", PromAccountStore.state.value?.name ?: "")
            put("likes", p.getString("likes", "[]"))
            put("playlists", p.getString("playlists", "[]"))
            put("recent", p.getString("recent", "[]"))
        }
    }

    /** 登录成功后调用：先拉云端，云端没有就把本地推上去 */
    suspend fun syncOnce(c: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val base = PromServerConfig.base(c)
        val token = c.getSharedPreferences("prom_account", Context.MODE_PRIVATE).getString("token", "") ?: ""
        if (base.isBlank() || token.isBlank()) return@withContext Result.success(Unit)
        runCatching {
            val remote = PromRemoteApi.pull(base, token).getOrNull()
            val local = localBlob(c)
            val remoteAt = remote?.optLong("updatedAt", 0L) ?: 0L
            if (remoteAt > local.optLong("updatedAt", 0L) && remote != null) {
                // 云端更新：写回本地偏好
                c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString("likes", remote.optString("likes", "[]"))
                    .putString("playlists", remote.optString("playlists", "[]"))
                    .putString("recent", remote.optString("recent", "[]"))
                    .apply()
            } else {
                PromRemoteApi.push(base, token, local).getOrThrow()
            }
        }
    }

    /** 本地变更后调用（列表变化时），节流由调用方控制 */
    suspend fun pushNow(c: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val base = PromServerConfig.base(c)
        val token = c.getSharedPreferences("prom_account", Context.MODE_PRIVATE).getString("token", "") ?: ""
        if (base.isBlank() || token.isBlank()) return@withContext Result.success(Unit)
        PromRemoteApi.push(base, token, localBlob(c))
    }
}
