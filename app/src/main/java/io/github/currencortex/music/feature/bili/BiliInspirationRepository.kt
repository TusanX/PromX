// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili

import io.github.currencortex.music.core.alt.BiliPlayback
import io.github.currencortex.music.core.alt.BiliWbi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln

/** Search-backed editorial lane for the inspirational "仙尊" videos and audio posts. */
internal object BiliInspirationRepository {
    private const val SHARED_VIDEO = "https://b23.tv/jptPB2T"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Group(val id: String, val title: String)

    data class Video(
        val bvid: String,
        val title: String,
        val cover: String,
        val owner: String,
        val durationSec: Int,
        val views: Long,
        val group: String,
        val featured: Boolean = false,
    )

    val groups = listOf(
        Group("all", "精选"),
        Group("action", "行动"),
        Group("growth", "成长"),
        Group("low", "低谷"),
        Group("clear", "清醒"),
        Group("sleep", "助眠音频"),
    )

    private val queries = mapOf(
        "action" to listOf(
            "仙尊 励志 人生路",
            "仙尊 行动 坚持",
            "励志仙尊 清醒",
        ),
        "growth" to listOf(
            "仙尊 成长 人生感悟",
            "仙尊 人呐 走在自己的人生路上",
            "修仙 励志 成长",
        ),
        "low" to listOf(
            "仙尊 低谷 坚持",
            "仙尊 不怕彷徨",
            "励志 修仙 逆境",
        ),
        "clear" to listOf(
            "仙尊 清醒 人生",
            "仙尊 格局 道心",
            "人呐 不要怕彷徨",
        ),
        "sleep" to listOf(
            "仙尊 睡前 励志 音频",
            "仙尊 人生感悟 音频",
            "励志 修仙 故事 音频",
        ),
    )

    suspend fun load(group: String, cookie: String): List<Video> = withContext(Dispatchers.IO) {
        val pickedQueries = if (group == "all") queries.values.mapNotNull { it.firstOrNull() } else queries[group].orEmpty()
        val searches = coroutineScope {
            pickedQueries.map { query ->
                async(Dispatchers.IO) { search(query, group.ifBlank { "all" }, cookie) }
            }.awaitAll()
        }
        val featured = resolveSharedVideo(cookie)?.copy(featured = true, group = "featured")
        (listOfNotNull(featured) + searches.flatten())
            .distinctBy { it.bvid }
            .sortedWith(compareByDescending<Video> { it.featured }.thenByDescending { score(it) })
            .take(60)
    }

    private fun search(query: String, group: String, cookie: String): List<Video> = runCatching {
        val body = BiliWbi.get(
            "/x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "video",
                "keyword" to query,
                "page" to "1",
                "page_size" to "30",
                "order" to "totalrank",
            ),
            cookie,
        ) ?: return emptyList()
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.intOrNull != 0) return emptyList()
        val result = root["data"]?.jsonObject?.get("result")?.jsonArray ?: return emptyList()
        result.mapNotNull { element ->
            val item = element.jsonObject
            val bvid = item["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = clean(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val owner = clean(item["author"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val durationSec = durationSeconds(item["duration"]?.jsonPrimitive?.contentOrNull.orEmpty())
            if (!isRelevant(title, owner, durationSec)) return@mapNotNull null
            Video(
                bvid = bvid,
                title = title,
                cover = normalizeImage(item["pic"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                owner = owner.ifBlank { "哔哩哔哩" },
                durationSec = durationSec,
                views = item["play"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                group = group,
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun resolveSharedVideo(cookie: String): Video? = withContext(Dispatchers.IO) {
        runCatching {
            val bvid = redirectBvid(SHARED_VIDEO) ?: return@runCatching null
            val info = BiliPlayback.videoInfo(bvid, cookie) ?: return@runCatching null
            val stats = BiliPlayback.stats(bvid, cookie)
            Video(
                bvid = bvid,
                title = info.title,
                cover = normalizeImage(info.cover),
                owner = info.owner.ifBlank { "哔哩哔哩" },
                durationSec = info.durationSec,
                views = stats?.view ?: 0L,
                group = "featured",
                featured = true,
            )
        }.getOrNull()
    }

    private fun redirectBvid(url: String): String? {
        var current = url
        repeat(6) {
            findBvid(current)?.let { return it }
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            runCatching { connection.inputStream.close(); connection.errorStream?.close() }
            if (code !in 300..399 || location.isNullOrBlank()) return findBvid(current)
            current = URL(URL(current), location).toString()
        }
        return findBvid(current)
    }

    private fun findBvid(text: String): String? =
        Regex("BV[0-9A-HJ-NP-Za-km-z]{10}").find(text)?.value

    private fun isRelevant(title: String, owner: String, durationSec: Int): Boolean {
        if (title.isBlank()) return false
        if (durationSec > 0 && durationSec < 25) return false
        val text = "$title $owner"
        val hasSpiritual = listOf("仙尊", "修仙", "道心", "修行", "仙途").any(text::contains)
        val hasGrowth = listOf("励志", "人生", "成长", "低谷", "彷徨", "坚持", "清醒", "感悟", "醒悟")
            .any(text::contains)
        val isAudio = listOf("音频", "朗读", "睡前", "助眠", "故事").any(text::contains)
        return hasSpiritual && (hasGrowth || isAudio)
    }

    private fun score(video: Video): Double {
        val text = "${video.title} ${video.owner}"
        var value = ln((video.views + 1).coerceAtLeast(1L).toDouble()) * 12.0
        if (video.title.contains("仙尊")) value += 24
        if (listOf("励志", "人生", "成长", "低谷", "清醒", "坚持").any(video.title::contains)) value += 18
        if (listOf("合集", "完整版", "长视频").any(text::contains)) value += 12
        if (listOf("音频", "朗读", "睡前", "助眠").any(text::contains)) value += 8
        if (listOf("直播回放", "切片", "reaction", "鬼畜").any(text::contains)) value -= 45
        if (video.durationSec in 90..3600) value += 12
        return value
    }

    private fun durationSeconds(raw: String): Int = raw.split(':').fold(0) { total, part ->
        total * 60 + (part.toIntOrNull() ?: 0)
    }

    private fun normalizeImage(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        else -> raw
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim()
}
