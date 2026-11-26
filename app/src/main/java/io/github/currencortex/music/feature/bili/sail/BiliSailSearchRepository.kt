// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.sail

import io.github.currencortex.music.feature.bili.BiliHubRepository
import io.github.currencortex.music.core.alt.BiliWbi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal enum class BiliSailSearchCategory(val label: String) {
    ALL("综合"),
    VIDEO("视频"),
    USER("用户"),
    BANGUMI("番剧"),
}

internal data class BiliSailSearchUser(
    val mid: Long,
    val name: String,
    val avatar: String,
    val sign: String,
    val fans: Long,
    val videos: Int,
)

internal data class BiliSailSearchMedia(
    val seasonId: Long,
    val title: String,
    val cover: String,
    val score: String,
    val areas: String,
    val progress: String,
    val url: String,
)

internal data class BiliSailSearchBundle(
    val videos: List<BiliHubRepository.Video> = emptyList(),
    val users: List<BiliSailSearchUser> = emptyList(),
    val media: List<BiliSailSearchMedia> = emptyList(),
    val videoTotal: Int = 0,
    val videoPages: Int = 0,
)

/** Search transport independent from PromX's NetEase SearchViewModel and generic result rows. */
internal object BiliSailSearchRepository {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    private fun connection(url: String, cookie: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://search.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            connectTimeout = 12_000
            readTimeout = 18_000
        }

    private fun searchData(type: String, keyword: String, page: Int, cookie: String): JsonObject? {
        val params = mapOf(
            "search_type" to type,
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "30",
        )
        val signed = runCatching {
            BiliWbi.get("/x/web-interface/wbi/search/type", params, cookie)
                ?.let { Json.parseToJsonElement(it).jsonObject["data"]?.jsonObject }
        }.getOrNull()
        if (signed != null) return signed
        return runCatching {
            val encoded = URLEncoder.encode(keyword, Charsets.UTF_8.name())
            val url = "https://api.bilibili.com/x/web-interface/search/type" +
                "?search_type=$type&keyword=$encoded&page=$page&page_size=30"
            val source = connection(url, cookie)
            val stream = if (source.responseCode in 200..299) source.inputStream else source.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
        }.getOrNull()
    }

    private fun cleanText(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    private fun cleanUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://${value.removePrefix("http://")}"
        else -> value
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    private fun JsonObject.int(name: String): Int =
        this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull {
        runCatching { it.jsonObject }.getOrNull()
    }

    private fun JsonElement.resultObjects(): List<JsonObject> =
        runCatching { jsonObject["result"]?.jsonArray?.objects().orEmpty() }.getOrDefault(emptyList())

    private fun durationSeconds(value: String): Int = when {
        value.isBlank() -> 0
        // The legacy search endpoint returns "total-seconds:frame" (for example 2875:59),
        // unlike other Bilibili APIs which use HH:MM:SS / MM:SS.
        value.contains(':') -> value.split(':').let { parts ->
            if (parts.size == 2) parts[0].toIntOrNull() ?: 0
            else parts.fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: 0) }
        }
        else -> value.toIntOrNull() ?: 0
    }

    private fun videos(keyword: String, page: Int, cookie: String): Pair<List<BiliHubRepository.Video>, Pair<Int, Int>> {
        val data = searchData("video", keyword, page, cookie) ?: return emptyList<BiliHubRepository.Video>() to (0 to 0)
        val items = data.resultObjects().mapNotNull { item ->
            val bvid = item.string("bvid")
            if (bvid.isBlank()) return@mapNotNull null
            BiliHubRepository.Video(
                bvid = bvid,
                title = cleanText(item.string("title")),
                cover = cleanUrl(item.string("pic")),
                uploader = cleanText(item.string("author")),
                uploaderMid = item.long("mid"),
                durationSec = durationSeconds(item.string("duration")),
                views = item.long("play"),
                comments = item.long("video_review"),
                likes = item.long("like"),
                publishedAt = item.long("pubdate"),
                badge = "视频",
            )
        }
        return items to (data.int("numResults") to data.int("numPages"))
    }

    private fun users(keyword: String, cookie: String): List<BiliSailSearchUser> {
        val data = searchData("bili_user", keyword, 1, cookie) ?: return emptyList()
        return data.resultObjects().mapNotNull { item ->
            val mid = item.long("mid")
            if (mid <= 0L) return@mapNotNull null
            BiliSailSearchUser(
                mid = mid,
                name = cleanText(item.string("uname")),
                avatar = cleanUrl(item.string("upic")),
                sign = cleanText(item.string("usign")),
                fans = item.long("fans"),
                videos = item.int("videos"),
            )
        }
    }

    private fun media(keyword: String, cookie: String): List<BiliSailSearchMedia> {
        val data = searchData("media_bangumi", keyword, 1, cookie) ?: return emptyList()
        return data.resultObjects().mapNotNull { item ->
            val seasonId = item.long("season_id")
            if (seasonId <= 0L) return@mapNotNull null
            val score = runCatching {
                item["media_score"]?.jsonObject?.string("score").orEmpty()
            }.getOrDefault("")
            BiliSailSearchMedia(
                seasonId = seasonId,
                title = cleanText(item.string("title")),
                cover = cleanUrl(item.string("cover")),
                score = score,
                areas = cleanText(item.string("areas")),
                progress = cleanText(item.string("index_show")),
                url = "https://www.bilibili.com/bangumi/play/ss$seasonId",
            )
        }
    }

    suspend fun search(
        keyword: String,
        category: BiliSailSearchCategory,
        page: Int,
        cookie: String,
    ): BiliSailSearchBundle = withContext(Dispatchers.IO) {
        coroutineScope {
            val videoTask = if (category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.VIDEO)) {
                async { videos(keyword, page, cookie) }
            } else null
            val userTask = if (category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.USER)) {
                async { users(keyword, cookie) }
            } else null
            val mediaTask = if (category in setOf(BiliSailSearchCategory.ALL, BiliSailSearchCategory.BANGUMI)) {
                async { media(keyword, cookie) }
            } else null
            val videoResult = videoTask?.await()
            BiliSailSearchBundle(
                videos = videoResult?.first.orEmpty(),
                users = userTask?.await().orEmpty(),
                media = mediaTask?.await().orEmpty(),
                videoTotal = videoResult?.second?.first ?: 0,
                videoPages = videoResult?.second?.second ?: 0,
            )
        }
    }
}
