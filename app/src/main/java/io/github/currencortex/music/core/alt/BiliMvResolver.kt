// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/** Finds a browser-friendly Bilibili video track for the currently playing song. */
object BiliMvResolver {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"
    private const val SEARCH_REFERER = "https://search.bilibili.com/"

    data class Match(
        val bvid: String,
        val title: String,
        val author: String,
        val url: String,
        val quality: String,
        val cookie: String = "",
    )

    private data class Candidate(
        val bvid: String,
        val title: String,
        val author: String,
        val durationSec: Int,
        val views: Long,
        val score: Int,
        val nameHit: Boolean,
        val artistHit: Boolean,
    )

    private data class VideoMeta(val cid: String, val durationSec: Int, val title: String, val author: String,
        val width: Int = 0, val height: Int = 0, val rotate: Int = 0)
    private data class VideoTrack(val url: String, val backupUrls: List<String>, val id: Int,
        val codec: String, val bandwidth: Long)

    private val songCache = ConcurrentHashMap<String, String>()
    private val noise = Regex(
        "(翻唱|翻自|cover|伴奏|karaoke|纯音乐|钢琴|吉他|演奏|弹奏|教学|曲谱|reaction|解说|直播|录播|合集|混剪|remix|鬼畜|搞笑|恶搞)",
        RegexOption.IGNORE_CASE,
    )
    private val qualityHint = Regex(
        "(\\bmv\\b|music\\s*video|官方|official|1080|4k|完整版|\\bhd\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val subtitleHint = Regex(
        "(中字|字幕|双语|ktv|karaoke|卡拉ok|歌词版|lyric\\s*video|\\bcc\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val verticalHint = Regex("(竖屏|竖版|全屏版|动态壁纸)", RegexOption.IGNORE_CASE)

    /** Search first and fall back to the direct Bilibili song identity when one is available. */
    fun resolve(
        title: String,
        artist: String,
        durationMs: Long,
        cookie: String = "",
        knownBvid: String? = null,
    ): Match? {
        knownBvid?.takeIf { it.matches(Regex("BV[0-9A-Za-z]{8,12}")) }
            ?.let { resolveBvid(it, cookie) }?.let { return it }
        if (title.isBlank()) return null

        val key = songKey(title, artist, durationMs)
        songCache[key]?.let { cached -> resolveBvid(cached, cookie)?.let { return it }; songCache.remove(key) }

        val candidates = search(title, artist, cookie, durationMs)
            .ifEmpty { search(title, "", cookie, durationMs) }
            .filter { it.nameHit }
            .sortedByDescending { it.score }
            .take(6)
            .map { candidate ->
                val meta = videoMeta(candidate.bvid, cookie)
                candidate.copy(score = candidate.score + videoPreference(candidate, meta, cookie))
            }
            .sortedByDescending { it.score }
        val best = candidates.firstOrNull { it.score >= 42 } ?: return null
        val resolved = resolveBvid(best.bvid, cookie) ?: return null
        songCache[key] = best.bvid
        return resolved
    }

    fun resolveBvid(bvid: String, cookie: String = ""): Match? = runCatching {
        val meta = videoMeta(bvid, cookie) ?: return null
        playableTracks(bvid, meta.cid, cookie)
            .forEach { track ->
                val urls = (listOf(track.url) + track.backupUrls).distinct()
                val working = urls.firstOrNull { probe(it, cookie) }
                if (working != null) {
                    return Match(
                        bvid = bvid,
                        title = meta.title,
                        author = meta.author,
                        url = working,
                        quality = qualityLabel(track.id),
                        cookie = cookie,
                    )
                }
            }
        fallbackMp4(bvid, meta.cid, cookie)?.let { url ->
            return Match(bvid, meta.title, meta.author, url, "MP4", cookie)
        }
        null
    }.getOrNull()

    private fun search(songTitle: String, artist: String, cookie: String, durationMs: Long): List<Candidate> = runCatching {
        val keyword = listOf(songTitle.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val body = BiliWbi.get(
            "/x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to "1",
                "page_size" to "24",
                "order" to "totalrank",
            ),
            cookie,
        ) ?: return emptyList()
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.intOrNull != 0) return emptyList()
        val list = root["data"]?.jsonObject?.get("result")?.jsonArray ?: return emptyList()
        list.mapNotNull { element ->
            val item = element.jsonObject
            val bvid = item["bvid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = clean(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val author = clean(item["author"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val durationSec = durationSeconds(item["duration"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val views = item["play"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val scored = score(title, author, durationSec, views, songTitle, artist, durationMs)
            Candidate(bvid, title, author, durationSec, views, scored,
                nameHit(title, songTitle), artistHit(title, author, artist))
        }
    }.getOrDefault(emptyList())

    private fun score(title: String, uploader: String, seconds: Int, views: Long,
        wantedTitle: String, wantedArtist: String, wantedDurationMs: Long): Int {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isBlank()) return -100
        val normalizedWantedTitle = normalize(wantedTitle)
        val normalizedArtist = normalize(wantedArtist)
        var score = 0
        if (normalizedWantedTitle.isNotBlank()) {
            score += when {
                normalizedTitle.contains(normalizedWantedTitle) -> 62
                normalizedWantedTitle.length >= 4 && normalizedTitle.contains(normalizedWantedTitle.take(6)) -> 16
                else -> -26
            }
        }
        val normalizedUploader = normalize(uploader)
        score += when {
            normalizedArtist.isNotBlank() && normalizedUploader.contains(normalizedArtist) -> 34
            normalizedArtist.isNotBlank() && normalizedTitle.contains(normalizedArtist) -> 18
            else -> 0
        }
        if (seconds in 45..900) score += 12
        if (seconds <= 0 || seconds < 25 || seconds > 1800) score -= 30
        score += ln((views + 1).coerceAtLeast(1L).toDouble()).times(5.0).toInt().coerceAtMost(28)
        if (qualityHint.containsMatchIn(title)) score += 14
        if (noise.containsMatchIn(title)) score -= 55
        if (subtitleHint.containsMatchIn(title)) score -= 32
        if (verticalHint.containsMatchIn(title)) score += 12
        if (wantedDurationMs > 0 && seconds > 0) {
            val delta = kotlin.math.abs(seconds - (wantedDurationMs / 1000).toInt())
            score += when {
                delta < 10 -> 22
                delta < 35 -> 9
                delta > 240 -> -14
                else -> 0
            }
        }
        return score
    }

    private fun videoPreference(candidate: Candidate, meta: VideoMeta?, cookie: String): Int {
        if (meta == null) return 0
        val rotated = meta.rotate % 180 != 0
        val width = if (rotated) meta.height else meta.width
        val height = if (rotated) meta.width else meta.height
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else 0f
        var bonus = when {
            ratio in 0.42f..0.72f -> 70   // 9:16 / 3:4 portrait
            ratio in .72f..1.18f -> 16  // near-square is less harmful in portrait layouts
            ratio > 1.18f -> -18       // landscape remains usable, but loses to a close portrait match
            else -> 0
        }
        val subtitleCount = subtitleCount(candidate.bvid, meta.cid, cookie)
        bonus += if (subtitleCount == 0) 18 else -30
        if (meta.width >= 1080 || meta.height >= 1080) bonus += 8
        return bonus
    }

    private fun nameHit(title: String, wanted: String): Boolean {
        val normalizedTitle = normalize(title)
        val normalizedWanted = normalize(wanted)
        return normalizedWanted.length >= 2 && normalizedWanted in normalizedTitle
    }

    private fun artistHit(title: String, uploader: String, wanted: String): Boolean {
        val artist = normalize(wanted)
        return artist.isNotBlank() && (normalize(uploader).contains(artist) || normalize(title).contains(artist))
    }

    private fun normalize(value: String): String = clean(value)
        .replace(Regex("[（(\\[【{][^）)\\]】}]*[）)\\]】}]"), " ")
        .replace(Regex("\\b(feat|ft|prod|remix|official|mv|music\\s*video|lyrics?|video|hd|4k)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        .lowercase()

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun durationSeconds(value: String): Int {
        value.toIntOrNull()?.let { return it }
        val pieces = value.split(':').mapNotNull { it.trim().toIntOrNull() }
        if (pieces.isEmpty() || pieces.size != value.split(':').size) return 0
        return pieces.fold(0) { total, part -> total * 60 + part }
    }

    private fun songKey(title: String, artist: String, durationMs: Long): String =
        "${normalize(title)}|${normalize(artist)}|${(durationMs / 1000).coerceAtLeast(0)}"

    private fun videoMeta(bvid: String, cookie: String): VideoMeta? = runCatching {
        val body = getJson("https://api.bilibili.com/x/web-interface/view?bvid=${encode(bvid)}", cookie)
        val data = body["data"]?.jsonObject ?: return null
        val cid = data["cid"]?.jsonPrimitive?.contentOrNull ?: return null
        val dimension = data["dimension"]?.jsonObject
        VideoMeta(
            cid = cid,
            durationSec = data["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            title = clean(data["title"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            author = clean(data["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()),
            width = dimension?.get("width")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            height = dimension?.get("height")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            rotate = dimension?.get("rotate")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }.getOrNull()

    private fun subtitleCount(bvid: String, cid: String, cookie: String): Int = runCatching {
        val body = BiliWbi.get("/x/player/wbi/v2", mapOf("bvid" to bvid, "cid" to cid), cookie)
            ?: return 0
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("subtitle")?.jsonObject?.get("subtitles")?.jsonArray?.size ?: 0
    }.getOrDefault(0)

    private fun playableTracks(bvid: String, cid: String, cookie: String): List<VideoTrack> = runCatching {
        val body = getJson(
            "https://api.bilibili.com/x/player/playurl?bvid=${encode(bvid)}&cid=${encode(cid)}" +
                "&fnval=4048&fnver=0&fourk=1&qn=120&high_quality=1",
            cookie,
        )
        val tracks = body["data"]?.jsonObject?.get("dash")?.jsonObject?.get("video")?.jsonArray
            ?: return emptyList()
        tracks.mapNotNull { element ->
            val item = element.jsonObject
            val url = (item["baseUrl"] ?: item["base_url"])?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val backup = (item["backupUrl"] ?: item["backup_url"])?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            VideoTrack(
                url = url,
                backupUrls = backup,
                id = item["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                codec = item["codecs"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                bandwidth = item["bandwidth"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }.sortedWith(
            compareByDescending<VideoTrack> { it.codec.startsWith("avc1") }
                .thenByDescending { it.id <= 80 }
                .thenByDescending { it.bandwidth },
        ).take(8)
    }.getOrDefault(emptyList())

    private fun fallbackMp4(bvid: String, cid: String, cookie: String): String? = runCatching {
        val body = getJson(
            "https://api.bilibili.com/x/player/playurl?bvid=${encode(bvid)}&cid=${encode(cid)}" +
                "&fnval=1&fnver=0&fourk=1&qn=80&platform=html5&high_quality=1",
            cookie,
        )
        body["data"]?.jsonObject?.get("durl")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
            ?.takeIf { probe(it, cookie) }
    }.getOrNull()

    private fun probe(url: String, cookie: String): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Range", "bytes=0-1")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        val code = connection.responseCode
        runCatching { connection.inputStream?.close(); connection.errorStream?.close() }
        code in 200..299
    }.getOrDefault(false)

    private fun getJson(url: String, cookie: String): kotlinx.serialization.json.JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Referer", if (url.contains("/wbi/search/")) SEARCH_REFERER else REFERER)
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun qualityLabel(id: Int): String = when (id) {
        6, 16 -> "360P"
        32 -> "480P"
        64, 74 -> "720P"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        else -> "视频"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
