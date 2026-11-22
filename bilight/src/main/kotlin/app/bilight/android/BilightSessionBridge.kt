// SPDX-License-Identifier: MIT
// 移植改动：让宿主 App 把已有的 B站 登录态写进 Bilight 的加密会话，
// 这样嵌入后不需要再扫一次码。
package app.bilight.android

import android.app.Application
import app.bilight.android.core.SessionVault
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

object BilightSessionBridge {
    private val host = "https://api.bilibili.com/".toHttpUrl()

    /** 用宿主提供的 Cookie 组覆盖写入 Bilight 的会话（仅当宿主确实传了 SESSDATA） */
    fun seed(application: Application, cookies: Map<String, String>) {
        if (cookies["SESSDATA"].isNullOrBlank()) return
        val expires = System.currentTimeMillis() + 180L * 24 * 3600 * 1000
        val serialized = cookies.entries.mapNotNull { (name, value) ->
            runCatching {
                Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/")
                    .expiresAt(expires).build().toString()
            }.getOrNull()
        }
        if (serialized.isEmpty()) return
        runCatching {
            SessionVault(application).save(
                JSONObject().put("cookies", JSONArray(serialized)).toString().toByteArray())
        }
    }

    fun clear(application: Application) = runCatching { SessionVault(application).clear() }
}
