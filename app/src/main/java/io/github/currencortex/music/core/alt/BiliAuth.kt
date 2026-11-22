// SPDX-License-Identifier: GPL-3.0-only
// B站扫码登录：generate 拿二维码 URL + qrcode_key → 轮询 poll → 成功后 Set-Cookie 存会话。
// 登录后可读 nav（头像/昵称/等级）、收藏夹（当作歌单）、以及「我喜欢」收藏夹。
package io.github.currencortex.music.core.alt

import android.content.Context
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

object BiliAuth {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class QrCode(val url: String, val key: String)
    enum class Poll { WAITING, SCANNED, EXPIRED, SUCCESS, UNKNOWN }
    data class PollResult(val state: Poll, val cookies: Map<String, String> = emptyMap(), val message: String = "")

    data class Profile(val uname: String, val face: String, val level: Int, val mid: Long)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            connectTimeout = 12000; readTimeout = 15000
        }

    private fun readCookies(c: HttpURLConnection): Map<String, String> {
        val out = mutableMapOf<String, String>()
        c.headerFields.forEach { (k, v) ->
            if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                val pair = raw.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
        }
        return out
    }

    fun generate(): QrCode? = runCatching {
        val c = open("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        val data = obj["data"]?.jsonObject ?: return null
        val url = data["url"]?.jsonPrimitive?.content ?: return null
        val key = data["qrcode_key"]?.jsonPrimitive?.content ?: return null
        QrCode(url, key)
    }.getOrNull()

    fun poll(key: String): PollResult = runCatching {
        val c = open("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val cookies = readCookies(c)
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
        val code = data?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        when (code) {
            0 -> PollResult(Poll.SUCCESS, cookies, "登录成功")
            86101 -> PollResult(Poll.WAITING, message = "等待扫码")
            86090 -> PollResult(Poll.SCANNED, message = "已扫码，请在手机上确认")
            86038 -> PollResult(Poll.EXPIRED, message = "二维码已失效")
            else -> PollResult(Poll.UNKNOWN, message = data?.get("message")?.jsonPrimitive?.content ?: "")
        }
    }.getOrElse { PollResult(Poll.UNKNOWN, message = it.message ?: "网络错误") }

    /** 登录后补全 Cookie：bili_jct / DedeUserID 等由其它域下发，需要额外请求首页与 nav 收集 */
    fun completeCookies(cookies: Map<String, String>): Map<String, String> {
        val merged = cookies.toMutableMap()
        val header = cookies.entries.joinToString("; ") { it.key + "=" + it.value }
        listOf("https://www.bilibili.com/", "https://api.bilibili.com/x/web-interface/nav").forEach { url ->
            runCatching {
                val c = open(url).apply {
                    setRequestProperty("Cookie", header)
                    instanceFollowRedirects = false
                }
                runCatching { c.inputStream?.close(); c.errorStream?.close() }
                c.headerFields.forEach { (k, v) ->
                    if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                        val pair = raw.substringBefore(';')
                        val name = pair.substringBefore('=').trim()
                        val value = pair.substringAfter('=', "").trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) merged[name] = value
                    }
                }
            }
        }
        return merged
    }

    /** 登录后读取账号信息（昵称/头像/等级） */
    fun profile(cookieHeader: String): Profile? = runCatching {
        val c = open("https://api.bilibili.com/x/web-interface/nav").apply {
            setRequestProperty("Cookie", cookieHeader)
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return null
        if (data["isLogin"]?.jsonPrimitive?.content?.toBoolean() != true) return null
        Profile(
            uname = data["uname"]?.jsonPrimitive?.content ?: "",
            face = data["face"]?.jsonPrimitive?.content ?: "",
            // nav 的等级在 level_info.current_level，顶层 level 常常是 0
            level = data["level_info"]?.jsonObject?.get("current_level")?.jsonPrimitive?.content?.toIntOrNull()
                ?: data["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            mid = data["mid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    /** 视频自带字幕 → LRC 文本（需要登录 Cookie，B站对未登录用户不返回字幕列表） */
    /**
     * 取视频字幕（含 AI 自动生成）。
     * 关键点：B站 的 AI 字幕是**懒生成**的——前几次请求会返回空列表，过一两秒再请求才出现。
     * 所以这里最多轮询 4 次；另外优先人工中文（zh-CN/zh-Hans），其次 ai-zh，最后英文。
     */
    fun subtitleLrc(cookieHeader: String, bvid: String, cid: String, preferCjk: Boolean = true): String? = runCatching {
        // 先试 wbi 签名版（Bilight 用的入口，命中率更高）
        runCatching {
            val body = BiliWbi.get("/x/player/wbi/v2",
                mapOf("bvid" to bvid, "cid" to cid), cookieHeader)
            if (body != null) {
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
                    ?.jsonObject?.get("subtitle")?.jsonObject?.get("subtitles")?.jsonArray
                if (!arr.isNullOrEmpty()) return@runCatching arr
            }
            null
        }.getOrNull()?.let { wbiTracks ->
            val tracksW = wbiTracks
            val wbiUrl = tracksW.firstOrNull { t ->
                val l = kotlinx.serialization.json.Json.parseToJsonElement(t.toString()).jsonObject["lan"]?.jsonPrimitive?.content.orEmpty()
                l.startsWith("ai-zh") || l.startsWith("zh")
            }?.jsonObject?.get("subtitle_url")?.jsonPrimitive?.content
                ?: tracksW.first().jsonObject["subtitle_url"]?.jsonPrimitive?.content
            if (wbiUrl != null) {
                val full = if (wbiUrl.startsWith("//")) "https:$wbiUrl" else wbiUrl
                val json = open(full).let { it.inputStream.bufferedReader().use { r -> r.readText() } }
                val lines = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["body"]?.jsonArray
                if (lines != null) {
                    val sb = StringBuilder()
                    lines.forEach { el ->
                        val o = el.jsonObject
                        val from = o["from"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                        val content = o["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (content.isEmpty()) return@forEach
                        val min = (from / 60).toInt(); val sec = from - min * 60
                        sb.append(String.format("[%02d:%05.2f]%s\n", min, sec, content))
                    }
                    val lrc = sb.toString()
                    if (lrc.isNotBlank()) return@runCatching lrc
                }
            }
        }

        fun fetchSubs(api: String): kotlinx.serialization.json.JsonArray? = runCatching {
            val c = open("https://api.bilibili.com/$api?bvid=$bvid&cid=$cid").apply {
                if (cookieHeader.isNotBlank()) setRequestProperty("Cookie", cookieHeader)
                setRequestProperty("Referer", "https://www.bilibili.com/video/$bvid")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("subtitle")?.jsonObject?.get("subtitles")?.jsonArray
        }.getOrNull()

        var subs: kotlinx.serialization.json.JsonArray? = null
        val delays = longArrayOf(0L, 700L, 1400L, 2600L)
        for (wait in delays) {
            if (wait > 0) Thread.sleep(wait)
            val a = fetchSubs("x/player/v2")
            if (!a.isNullOrEmpty()) { subs = a; break }
            val b = fetchSubs("x/player/wbi/v2")
            if (!b.isNullOrEmpty()) { subs = b; break }
        }
        val tracks = subs ?: run {
            io.github.currencortex.music.core.alt.BiliPlayback.lastSubtitleInfo = "bvid=" + bvid + " 无字幕轨"
            return null
        }
        if (tracks.isEmpty()) return null

        fun lan(el: kotlinx.serialization.json.JsonElement): String =
            el.jsonObject["lan"]?.jsonPrimitive?.content.orEmpty()
        // 不再只认中文：按「人工字幕 > 与标题语言一致的 AI 字幕 > 任意字幕」打分。
        // 英文歌会优先拿到 en/en-US/ai-en，其他语种（葡语等）只要有轨就用。
        fun score(track: kotlinx.serialization.json.JsonElement): Int {
            val l = lan(track)
            val cjk = l.startsWith("zh") || l == "ai-zh"
            val english = l.startsWith("en") || l == "ai-en"
            val manual = !l.startsWith("ai")
            return when {
                // 标题语言与轨道语言一致的最优先（含 AI 轨）：
                // 英文歌即便只有 ai-en，也要压过“人工中文翻译轨”，否则读起来全是中文
                cjk && preferCjk && manual -> 100
                cjk && preferCjk -> 96
                english && !preferCjk && manual -> 100
                english && !preferCjk -> 94
                // 与标题语言不一致、但是人工字幕（通常是翻译轨）
                manual && cjk -> 88
                manual && english -> 88
                manual -> 84
                // 其余 AI 轨：只有在没有更合适的时候才用
                cjk -> 70
                english -> 68
                else -> 74                          // 其它语种 AI 轨（葡语等）
            }
        }
        val picked = tracks.maxByOrNull { score(it) } ?: tracks.first()

        val url = picked.jsonObject["subtitle_url"]?.jsonPrimitive?.content ?: return null
        io.github.currencortex.music.core.alt.BiliPlayback.lastSubtitleInfo =
            (picked.jsonObject["lan_doc"]?.jsonPrimitive?.content ?: lan(picked))
        val full = if (url.startsWith("//")) "https:$url" else url
        val json = open(full).let { it.inputStream.bufferedReader().use { r -> r.readText() } }
        val lines = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["body"]?.jsonArray ?: return null
        val sb = StringBuilder()
        lines.forEach { el ->
            val o = el.jsonObject
            val from = o["from"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
            val content = o["content"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (content.isEmpty()) return@forEach
            val min = (from / 60).toInt()
            val sec = from - min * 60
            sb.append(String.format("[%02d:%05.2f]%s\n", min, sec, content))
        }
        sb.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 收藏夹列表（第一个是默认收藏夹，含「我喜欢」语义） */

    /** 收藏夹里的内容（视频 → 歌曲） */
    data class FavItem(val bvid: String, val title: String, val author: String,
                       val cover: String, val durationMs: Long)

    fun favoriteItems(cookieHeader: String, mediaId: Long, page: Int = 1, size: Int = 20): List<FavItem> = runCatching {
        val c = open("https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=$size&platform=web").apply {
            setRequestProperty("Cookie", cookieHeader)
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return emptyList()
        val medias = data["medias"]?.jsonArray ?: return emptyList()
        medias.mapNotNull { el ->
            val o = el.jsonObject
            val bvid = o["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            FavItem(
                bvid = bvid,
                title = o["title"]?.jsonPrimitive?.content ?: "",
                author = o["upper"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                cover = (o["cover"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it },
                durationMs = (o["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 1000,
            )
        }
    }.getOrDefault(emptyList())

    /** 新建收藏夹，返回其 id */
    fun createFolder(cookieHeader: String, title: String, csrf: String): Long? = runCatching {
        val c = (java.net.URL("https://api.bilibili.com/x/v3/fav/folder/add").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Cookie", cookieHeader)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "title=" + java.net.URLEncoder.encode(title, "UTF-8") + "&privacy=0&csrf=" + csrf
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
    }.getOrNull()

    /** 把视频加入收藏夹（rid 必须是 aid，传 bvid 会被判无效） */
    fun addToFolder(cookieHeader: String, bvid: String, mediaId: Long, csrf: String): Boolean = runCatching {
        val aid = io.github.currencortex.music.core.alt.BiliPlayback.videoInfo(bvid, cookieHeader)?.aid
            ?: return false
        val c = (java.net.URL("https://api.bilibili.com/x/v3/fav/resource/deal").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Cookie", cookieHeader)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "rid=" + aid + "&type=2&add_media_ids=" + mediaId + "&del_media_ids=&csrf=" + csrf
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content == "0"
    }.getOrDefault(false)

    /** 从收藏夹移除（取消喜欢） */
    /**
     * 直接翻收藏夹内容判断某条视频是否在里面。
     * 比 collected/list 更可靠（后者常因风控返回空，导致“只能加不能删”）。
     */
    fun isInFolder(cookieHeader: String, folderId: Long, bvid: String, maxPages: Int = 5): Boolean = runCatching {
        for (page in 1..maxPages) {
            val items = favoriteItems(cookieHeader, folderId, page)
            if (items.isEmpty()) return false
            if (items.any { it.bvid == bvid }) return true
        }
        false
    }.getOrDefault(false)

    /** 这条视频当前已经在哪些收藏夹里（用于显示「已收藏」并支持取消） */
    fun collectedFolders(cookieHeader: String, aid: String): Set<Long> = runCatching {
        val body = BiliWbi.get("/x/v3/fav/folder/collected/list",
            mapOf("up_mid" to "0", "rid" to aid, "type" to "2"), cookieHeader) ?: return emptySet()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        val list = root["data"]?.jsonObject?.get("list")?.jsonArray ?: return emptySet()
        list.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull() }.toSet()
    }.getOrDefault(emptySet())

    fun removeFromFolder(cookieHeader: String, bvid: String, mediaId: Long, csrf: String): Boolean = runCatching {
        val aid = io.github.currencortex.music.core.alt.BiliPlayback.videoInfo(bvid, cookieHeader)?.aid
            ?: return false
        val c = (java.net.URL("https://api.bilibili.com/x/v3/fav/resource/deal").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Cookie", cookieHeader)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "rid=" + aid + "&type=2&add_media_ids=&del_media_ids=" + mediaId + "&csrf=" + csrf
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content == "0"
    }.getOrDefault(false)

    /** B站 的 csrf/bkn：由 bili_jct 计算（注意收藏接口要原值，这里仅保留算法备用） */
    fun bkn(jct: String): String {
        var hash = 5381L
        for (ch in jct) hash += (hash shl 5) + ch.code
        return (hash and 0x7FFFFFFFL).toString()
    }

    data class FavFolder(val id: Long, val title: String, val cover: String, val count: Int)

    fun favoriteFolders(cookieHeader: String, mid: Long): List<FavFolder> = runCatching {
        val c = open("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid").apply {
            setRequestProperty("Cookie", cookieHeader)
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val list = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("list")?.jsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            FavFolder(
                id = id,
                title = o["title"]?.jsonPrimitive?.content ?: "收藏夹",
                cover = (o["cover"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it },
                count = o["media_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }.getOrDefault(emptyList())
}

/** B站的会话（Cookie + 账号信息） */
class BiliSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prom_bili_session", Context.MODE_PRIVATE)

    var cookies: Map<String, String>
        get() = prefs.getString("cookies", null)
            ?.split('\n')
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap() ?: emptyMap()
        set(value) { prefs.edit().putString("cookies", value.entries.joinToString("\n") { it.key + "=" + it.value }).apply() }

    var profile: BiliAuth.Profile?
        get() = prefs.getString("uname", null)?.let {
            BiliAuth.Profile(it, prefs.getString("face", "").orEmpty(),
                prefs.getInt("level", 0), prefs.getLong("mid", 0L))
        }
        set(value) = prefs.edit().apply {
            putString("uname", value?.uname)
            putString("face", value?.face)
            putInt("level", value?.level ?: 0)
            putLong("mid", value?.mid ?: 0L)
        }.apply()

    val loggedIn get() = profile != null && cookies.isNotEmpty()
    fun cookieHeader(): String = cookies.entries.joinToString("; ") { it.key + "=" + it.value }
    fun clear() = prefs.edit().clear().apply()
}
