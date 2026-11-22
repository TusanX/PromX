// SPDX-License-Identifier: GPL-3.0-only
// B站 wbi 签名：playurl / view / player/v2 的 wbi 版本都要求 w_rid + wts，
// 不带签名的请求在新视频上会被当成未授权（拿到 403 或空数据）。
// 混入表与算法参考 Bilight（MIT）core/BiliHttp.kt。
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest

object BiliWbi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    /** 官方 wbi 混入表 */
    private val mixinTable = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39,
        12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63,
        57, 62, 11, 36, 20, 34, 44, 52,
    )

    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedAt: Long = 0L

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun rawGet(url: String, cookie: String): String {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            connectTimeout = 12_000; readTimeout = 15_000
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** 从 nav 里取 wbi_img 并算出 32 位 mixin key（缓存 30 分钟） */
    fun key(cookie: String = ""): String? {
        val now = System.currentTimeMillis()
        cachedKey?.let { if (now - cachedAt < 30 * 60_000L) return it }
        return runCatching {
            val body = rawGet("https://api.bilibili.com/x/web-interface/nav", cookie)
            val wbi = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
                ?.jsonObject?.get("wbi_img")?.jsonObject ?: return null
            val img = wbi["img_url"]?.jsonPrimitive?.content?.substringAfterLast('/')?.substringBefore('.') ?: return null
            val sub = wbi["sub_url"]?.jsonPrimitive?.content?.substringAfterLast('/')?.substringBefore('.') ?: return null
            val raw = img + sub
            val key = buildString { mixinTable.forEach { if (it < raw.length) append(raw[it]) } }.take(32)
            cachedKey = key; cachedAt = now
            key
        }.getOrNull()
    }

    /** 把参数签名成可直接拼到路径后的 query（含 wts 与 w_rid） */
    fun sign(query: Map<String, String>, key: String, timestamp: Long = System.currentTimeMillis() / 1000): String {
        val values = query + ("wts" to timestamp.toString())
        val text = values.toSortedMap().entries.joinToString("&") {
            enc(it.key) + "=" + enc(it.value.replace(Regex("[!'()*]"), ""))
        }
        val digest = MessageDigest.getInstance("MD5").digest((text + key).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$text&w_rid=$digest"
    }

    /** 带签名发起请求（返回原始 JSON 文本） */
    fun get(pathWithQuery: String, params: Map<String, String>, cookie: String = ""): String? {
        val key = key(cookie) ?: return null
        val url = "https://api.bilibili.com$pathWithQuery?" + sign(params, key)
        return runCatching { rawGet(url, cookie) }.getOrNull()
    }
}
