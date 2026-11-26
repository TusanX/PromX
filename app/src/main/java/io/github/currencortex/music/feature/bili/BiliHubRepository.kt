// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import io.github.currencortex.music.core.alt.BiliAuth
import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.core.alt.BiliWbi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Independent data layer for PromX's Bilibili shell. It never feeds the NetEase or QQ roots. */
object BiliHubRepository {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Video(
        val bvid: String,
        val title: String,
        val cover: String,
        val uploader: String,
        val uploaderMid: Long = 0L,
        val durationSec: Int = 0,
        val views: Long = 0L,
        val comments: Long = 0L,
        val likes: Long = 0L,
        val publishedAt: Long = 0L,
        val badge: String = "",
        val recommendationReason: String = "",
    )

    data class Bangumi(
        val seasonId: Long,
        val title: String,
        val cover: String,
        val badge: String,
        val rating: String,
        val progress: String,
        val views: Long,
        val url: String,
    )

    data class LiveRoom(
        val roomId: Long,
        val title: String,
        val cover: String,
        val anchor: String,
        val avatar: String,
        val area: String,
        val online: Long,
    )

    data class Dynamic(
        val id: String,
        val author: String,
        val authorMid: Long = 0L,
        val avatar: String,
        val timeText: String,
        val text: String,
        val cover: String = "",
        val title: String = "",
        val bvid: String = "",
        val comments: Long = 0L,
        val likes: Long = 0L,
    )

    data class Account(
        val mid: Long,
        val name: String,
        val face: String,
        val sign: String,
        val level: Int,
        val currentExp: Long,
        val nextExp: Long,
        val following: Long,
        val followers: Long,
        val dynamics: Long,
        val coins: Double,
        val bCoins: Double,
        val vipLabel: String,
    )

    data class MessageSummary(
        val replies: Int,
        val ats: Int,
        val likes: Int,
        val system: Int,
    ) {
        val total get() = replies + ats + likes + system
    }

    data class MessageItem(
        val id: String,
        val title: String,
        val summary: String,
        val avatar: String,
        val timeText: String,
        val category: String,
    )

    data class SocialUser(
        val mid: Long,
        val name: String,
        val avatar: String,
        val sign: String,
        val following: Boolean,
    )

    private fun connection(url: String, cookie: String = "", referer: String = "https://www.bilibili.com/"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            connectTimeout = 12_000
            readTimeout = 18_000
        }

    private fun get(url: String, cookie: String = "", referer: String = "https://www.bilibili.com/"): JsonObject? = runCatching {
        val c = connection(url, cookie, referer)
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        Json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun JsonObject.dataObject(): JsonObject? = this["data"]?.jsonObject
    private fun JsonObject.dataArray(): JsonArray? = this["data"]?.jsonArray
    private fun JsonObject.resultObject(): JsonObject? = this["result"]?.jsonObject
    private fun JsonObject.prim(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull
        ?: this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
        ?: this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    private fun JsonObject.double(name: String): Double = this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun cleanUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
        else -> url
    }

    private fun durationSeconds(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        else -> raw?.toString()?.split(':')?.fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: 0) } ?: 0
    }

    private fun parseVideo(o: JsonObject): Video? {
        val bvid = o.prim("bvid").ifBlank {
            o["uri"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfter("BV", "").let { if (it.isBlank()) "" else "BV$it" }
        }
        val title = o.prim("title").ifBlank { o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (bvid.isBlank() || title.isBlank()) return null
        val owner = o["owner"]?.jsonObject
        val stat = o["stat"]?.jsonObject
        val duration = when (val value = o["duration"]) {
            null -> 0
            else -> durationSeconds(runCatching { value.jsonPrimitive.content }.getOrNull())
        }
        return Video(
            bvid = bvid,
            title = title,
            cover = cleanUrl(o.prim("pic").ifBlank { o.prim("cover") }),
            uploader = owner?.prim("name") ?: o.prim("author_name").ifBlank { o.prim("owner_name") },
            uploaderMid = owner?.long("mid") ?: o.long("mid"),
            durationSec = duration,
            views = stat?.long("view") ?: o.long("play"),
            comments = stat?.long("reply") ?: o.long("video_review"),
            likes = stat?.long("like") ?: o.long("like"),
            publishedAt = o.long("pubdate"),
            badge = o.prim("badge"),
            recommendationReason = o.prim("reason").ifBlank { o.prim("rcmd_reason") },
        )
    }

    private fun videoArray(arr: JsonArray?): List<Video> = arr.orEmpty().mapNotNull { runCatching { parseVideo(it.jsonObject) }.getOrNull() }

    private fun onlineVideos(cookie: String): List<Video> = get(
        "https://api.bilibili.com/x/web-interface/online/list", cookie
    )?.dataArray()?.let(::videoArray).orEmpty()

    private fun recommended(cookie: String): List<Video> {
        val body = BiliWbi.get(
            "/x/web-interface/wbi/index/top/feed/rcmd",
            linkedMapOf("ps" to "20", "fresh_type" to "4", "feed_version" to "V8", "homepage_ver" to "1"),
            cookie,
        )
        val items = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.dataObject()?.get("item")?.jsonArray
        return videoArray(items).ifEmpty { onlineVideos(cookie) }
    }

    fun home(channel: String, cookie: String = ""): List<Video> = when (channel) {
        "推荐" -> recommended(cookie)
        "热门" -> get("https://api.bilibili.com/x/web-interface/popular?pn=1&ps=20", cookie)
            ?.dataObject()?.get("list")?.jsonArray?.let(::videoArray).orEmpty()
            .ifEmpty { onlineVideos(cookie) }
        "排行榜" -> get("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all", cookie)
            ?.dataObject()?.get("list")?.jsonArray?.let(::videoArray).orEmpty()
        "动画" -> newVideos(1, cookie)
        "音乐" -> newVideos(3, cookie)
        "游戏" -> newVideos(4, cookie)
        "科技" -> newVideos(188, cookie)
        "知识" -> newVideos(36, cookie)
        "生活" -> newVideos(160, cookie)
        "美食" -> newVideos(211, cookie)
        "影视" -> newVideos(181, cookie)
        "纪录片" -> newVideos(177, cookie)
        else -> recommended(cookie)
    }

    private fun newVideos(rid: Int, cookie: String): List<Video> = get(
        "https://api.bilibili.com/x/web-interface/newlist?rid=$rid&pn=1&ps=20", cookie
    )?.dataObject()?.get("archives")?.jsonArray?.let(::videoArray).orEmpty()

    fun bangumi(type: Int, cookie: String = ""): List<Bangumi> = runCatching {
        val body = get("https://api.bilibili.com/pgc/web/rank/list?day=3&season_type=$type&order=3&st=1", cookie)
            ?: return emptyList()
        body.resultObject()?.get("list")?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val id = o.long("season_id")
            if (id == 0L) return@mapNotNull null
            Bangumi(
                seasonId = id,
                title = o.prim("title"),
                cover = cleanUrl(o.prim("cover")),
                badge = o.prim("badge"),
                rating = o.prim("rating"),
                progress = o["new_ep"]?.jsonObject?.prim("index_show").orEmpty(),
                views = o["stat"]?.jsonObject?.long("view") ?: 0L,
                url = o.prim("url"),
            )
        }
    }.getOrDefault(emptyList())

    fun live(cookie: String = ""): List<LiveRoom> = runCatching {
        val body = get(
            "https://api.live.bilibili.com/room/v1/room/get_user_recommend?page=1&page_size=20",
            cookie,
            referer = "https://live.bilibili.com/",
        ) ?: return emptyList()
        body.dataArray().orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val roomId = o.long("roomid")
            if (roomId == 0L) return@mapNotNull null
            LiveRoom(
                roomId = roomId,
                title = o.prim("title"),
                cover = cleanUrl(o.prim("user_cover").ifBlank { o.prim("system_cover") }),
                anchor = o.prim("uname"),
                avatar = cleanUrl(o.prim("face")),
                area = o.prim("areaName"),
                online = o.long("online"),
            )
        }
    }.getOrDefault(emptyList())

    fun followedLive(cookie: String): List<LiveRoom> = runCatching {
        val body = get(
            "https://api.live.bilibili.com/xlive/web-ucenter/user/following?page=1&page_size=20&ignoreRecord=1&hit_ab=true",
            cookie,
            referer = "https://live.bilibili.com/",
        ) ?: return emptyList()
        body.dataObject()?.get("list")?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val roomId = o.long("roomid").let { if (it > 0) it else o.long("room_id") }
            if (roomId == 0L) return@mapNotNull null
            LiveRoom(
                roomId = roomId,
                title = o.prim("title"),
                cover = cleanUrl(o.prim("cover").ifBlank { o.prim("user_cover") }),
                anchor = o.prim("uname").ifBlank { o["uname"]?.jsonPrimitive?.contentOrNull.orEmpty() },
                avatar = cleanUrl(o.prim("face")),
                area = o.prim("area_name").ifBlank { o.prim("areaName") },
                online = o.long("online"),
            )
        }
    }.getOrDefault(emptyList())

    /** Anonymous users get a clear login state; logged-in users receive the native followed dynamic feed. */
    fun dynamics(cookie: String = ""): List<Dynamic> = runCatching {
        val body = get(
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?page=1",
            cookie,
        ) ?: return emptyList()
        val items = body.dataObject()?.get("items")?.jsonArray ?: return emptyList()
        items.mapNotNull { el ->
            val root = el.jsonObject
            val modules = root["modules"]?.jsonObject ?: return@mapNotNull null
            val author = modules["module_author"]?.jsonObject
            val dynamic = modules["module_dynamic"]?.jsonObject
            val archive = dynamic?.get("major")?.jsonObject?.get("archive")?.jsonObject
            val draw = dynamic?.get("major")?.jsonObject?.get("draw")?.jsonObject
            val desc = dynamic?.get("desc")?.jsonObject?.prim("text").orEmpty()
            val cover = cleanUrl(
                archive?.prim("cover").orEmpty().ifBlank {
                    draw?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.prim("src").orEmpty()
                }
            )
            Dynamic(
                id = root.prim("id"),
                author = author?.prim("name").orEmpty(),
                authorMid = author?.long("mid") ?: 0L,
                avatar = cleanUrl(author?.prim("face").orEmpty()),
                timeText = author?.prim("pub_time").orEmpty(),
                text = desc,
                cover = cover,
                title = archive?.prim("title").orEmpty(),
                bvid = archive?.prim("bvid").orEmpty(),
                comments = modules["module_stat"]?.jsonObject?.get("comment")?.jsonObject?.long("count") ?: 0L,
                likes = modules["module_stat"]?.jsonObject?.get("like")?.jsonObject?.long("count") ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    fun watchLater(cookie: String): List<Video> = runCatching {
        val body = get("https://api.bilibili.com/x/v2/history/toview", cookie) ?: return emptyList()
        body.dataObject()?.get("list")?.jsonArray?.let(::videoArray).orEmpty()
    }.getOrDefault(emptyList())

    fun history(cookie: String): List<Video> = BiliPlayback.history(cookie, 30).map {
        Video(it.bvid, it.title, it.cover, it.owner, durationSec = it.durationSec)
    }

    fun account(cookie: String): Account? = runCatching {
        val nav = get("https://api.bilibili.com/x/web-interface/nav", cookie)?.dataObject() ?: return null
        val mid = nav.long("mid")
        if (mid == 0L) return null
        val relation = get("https://api.bilibili.com/x/relation/stat?vmid=$mid", cookie)?.dataObject()
        val levelInfo = nav["level_info"]?.jsonObject
        val vip = nav["vip"]?.jsonObject
        val wallet = nav["wallet"]?.jsonObject
        Account(
            mid = mid,
            name = nav.prim("uname"),
            face = cleanUrl(nav.prim("face")),
            sign = nav.prim("sign"),
            level = levelInfo?.int("current_level") ?: nav.int("level"),
            currentExp = levelInfo?.long("current_exp") ?: 0L,
            nextExp = levelInfo?.long("next_exp") ?: 0L,
            following = relation?.long("following") ?: 0L,
            followers = relation?.long("follower") ?: 0L,
            dynamics = relation?.long("dynamic") ?: 0L,
            coins = wallet?.double("coin") ?: nav.double("money"),
            bCoins = wallet?.double("bcoin") ?: 0.0,
            vipLabel = if (vip?.int("status") == 1) vip.prim("label") else "普通会员",
        )
    }.getOrNull()

    fun messages(cookie: String): MessageSummary = runCatching {
        val data = get("https://api.bilibili.com/x/msgfeed/unread", cookie)?.dataObject()
        MessageSummary(
            replies = data?.int("reply") ?: data?.int("replies") ?: 0,
            ats = data?.int("at") ?: 0,
            likes = data?.int("like") ?: 0,
            system = data?.int("sys_msg") ?: data?.int("system") ?: 0,
        )
    }.getOrDefault(MessageSummary(0, 0, 0, 0))

    fun messageItems(cookie: String): List<MessageItem> = runCatching {
        val body = get("https://api.bilibili.com/x/msgfeed/reply?platform=web&build=0&mobi_app=web", cookie)
            ?: return emptyList()
        val list = body.dataObject()?.get("items")?.jsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val user = o["user"]?.jsonObject
            MessageItem(
                id = o.prim("id").ifBlank { o.prim("reply_id") },
                title = user?.prim("nickname").orEmpty().ifBlank { "收到新回复" },
                summary = o["item"]?.jsonObject?.prim("source_content").orEmpty().ifBlank {
                    o["item"]?.jsonObject?.prim("title").orEmpty()
                },
                avatar = cleanUrl(user?.prim("avatar").orEmpty()),
                timeText = formatTime(o.long("reply_time").takeIf { it > 0 } ?: o.long("timestamp")),
                category = "回复我的",
            )
        }
    }.getOrDefault(emptyList())

    fun social(mid: Long, followers: Boolean, cookie: String): List<SocialUser> = runCatching {
        val kind = if (followers) "followers" else "followings"
        val body = get("https://api.bilibili.com/x/relation/$kind?vmid=$mid&pn=1&ps=30&order=desc", cookie)
            ?: return emptyList()
        body.dataObject()?.get("list")?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            SocialUser(
                mid = o.long("mid"),
                name = o.prim("uname"),
                avatar = cleanUrl(o.prim("face")),
                sign = o.prim("sign"),
                following = o.int("attribute") == 2 || o.int("attribute") == 6,
            )
        }
    }.getOrDefault(emptyList())

    fun favoriteFolders(cookie: String, mid: Long): List<BiliAuth.FavFolder> = BiliAuth.favoriteFolders(cookie, mid)

    fun search(keyword: String, cookie: String = ""): List<Video> = runCatching {
        val q = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val body = get("https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$q&page=1", cookie)
            ?: return emptyList()
        body.dataObject()?.get("result")?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val title = o.prim("title").replace(Regex("<[^>]+>"), "")
            Video(
                bvid = o.prim("bvid"),
                title = title,
                cover = cleanUrl(o.prim("pic")),
                uploader = o.prim("author"),
                uploaderMid = o.long("mid"),
                durationSec = durationSeconds(o.prim("duration")),
                views = o.long("play"),
                comments = o.long("video_review"),
                likes = o.long("like"),
                publishedAt = o.long("pubdate"),
            )
        }
    }.getOrDefault(emptyList())

    private fun formatTime(epochSeconds: Long): String = if (epochSeconds <= 0) "" else
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochSeconds * 1000))

    fun compactCount(value: Long): String = when {
        value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
        value >= 10_000 -> "%.1f万".format(value / 10_000.0)
        else -> value.toString()
    }

    fun durationLabel(seconds: Int): String = if (seconds <= 0) "" else
        "%d:%02d".format(seconds / 60, seconds % 60)
}
