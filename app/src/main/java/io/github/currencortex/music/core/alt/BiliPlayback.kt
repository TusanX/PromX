// SPDX-License-Identifier: GPL-3.0-only
// B站 播放与评论：把「视频」当作音源时最容易踩的两个坑都在这里处理
//   1) 部分视频没有 dash 音轨 → 回退到 durl 的 mp4（ExoPlayer 能直接解出音轨）
//   2) 评论要按「aid（数字）」查，不是 bvid
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

object BiliPlayback {
    /** 最近一次字幕抓取的诊断信息（bvid / 语言 / 行数），显示在歌词管理里 */
    @Volatile var lastSubtitleInfo: String = ""

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Creator(val mid: Long, val name: String, val avatar: String, val sign: String, val fans: Long, val videos: Int)
    data class RelatedVideo(val bvid: String, val title: String, val cover: String, val owner: String, val durationSec: Int)

    data class VideoInfo(val cid: String, val aid: String, val durationSec: Int, val title: String,
        val cover: String = "", val owner: String = "")

    private fun open(url: String, cookie: String = ""): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            connectTimeout = 12000; readTimeout = 15000
        }

    /** B站 评论正文里带 HTML 实体（&#34; &amp; 等），显示前要还原 */
    private fun decodeEntities(text: String): String = text
        .replace("&#34;", "\"").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun get(url: String, cookie: String = ""): String =
        open(url, cookie).inputStream.bufferedReader().use { it.readText() }

    /** bvid → cid / aid（播放与评论都要） */
    /** 观看历史（B站 的「最近播放」） */
    fun history(cookie: String = "", limit: Int = 30): List<RelatedVideo> = runCatching {
        val body = BiliWbi.get("/x/web-interface/history/cursor",
            mapOf("ps" to limit.toString(), "type" to "archive"), cookie) ?: return emptyList()
        val list = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
            ?.jsonObject?.get("list")?.jsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val bvid = o["history"]?.jsonObject?.get("bvid")?.jsonPrimitive?.content
                ?: o["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val pic = (o["cover"]?.jsonPrimitive?.content ?: o["pic"]?.jsonPrimitive?.content ?: "")
                .let { if (it.startsWith("//")) "https:$it" else it }
            RelatedVideo(
                bvid = bvid,
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                cover = pic,
                owner = o["author_name"]?.jsonPrimitive?.content
                    ?: o["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty(),
                durationSec = (o["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0),
            )
        }
    }.getOrDefault(emptyList())

    /** UP 主空间信息（wbi 签名 + 登录态） */
    fun creator(mid: Long, cookie: String = ""): Creator? = runCatching {
        val body = BiliWbi.get("/x/space/wbi/acc/info", mapOf("mid" to mid.toString()), cookie) ?: return null
        val d = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return null
        Creator(
            mid = d["mid"]?.jsonPrimitive?.content?.toLongOrNull() ?: mid,
            name = d["name"]?.jsonPrimitive?.content.orEmpty(),
            avatar = d["face"]?.jsonPrimitive?.content.orEmpty(),
            sign = d["sign"]?.jsonPrimitive?.content.orEmpty(),
            fans = d["follower"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            videos = 0,
        )
    }.getOrNull()

    /** UP 主投稿列表 */
    fun creatorVideos(mid: Long, page: Int = 1, cookie: String = ""): List<RelatedVideo> = runCatching {
        val body = BiliWbi.get("/x/space/wbi/arc/search",
            mapOf("mid" to mid.toString(), "ps" to "30", "pn" to page.toString(), "order" to "pubdate"), cookie)
            ?: return emptyList()
        val list = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
            ?.jsonObject?.get("list")?.jsonObject?.get("vlist")?.jsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val bvid = o["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val pic = (o["pic"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it }
            RelatedVideo(bvid, o["title"]?.jsonPrimitive?.content.orEmpty(), pic,
                o["author"]?.jsonPrimitive?.content.orEmpty(),
                o["length"]?.jsonPrimitive?.content?.split(":")?.let { parts ->
                    parts.fold(0) { acc, v -> acc * 60 + (v.toIntOrNull() ?: 0) }
                } ?: 0)
        }
    }.getOrDefault(emptyList())

    /** 相关推荐（相似视频） */
    fun related(bvid: String, cookie: String = ""): List<RelatedVideo> = runCatching {
        val body = BiliWbi.get("/x/web-interface/archive/related", mapOf("bvid" to bvid), cookie)
            ?: return emptyList()
        val list = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val pic = (o["pic"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it }
            RelatedVideo(id, o["title"]?.jsonPrimitive?.content.orEmpty(), pic,
                o["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty(),
                o["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
        }
    }.getOrDefault(emptyList())

    /** UP 主 mid（视频详情里取） */
    fun uploaderMid(bvid: String, cookie: String = ""): Long = runCatching {
        val body = BiliWbi.get("/x/web-interface/wbi/view", mapOf("bvid" to bvid), cookie) ?: return 0L
        kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("owner")?.jsonObject?.get("mid")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    fun videoInfo(bvid: String, cookie: String = ""): VideoInfo? = runCatching {
        val body = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", cookie)
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return null
        val cid = data["cid"]?.jsonPrimitive?.content ?: return null
        val aid = data["aid"]?.jsonPrimitive?.content ?: return null
        VideoInfo(cid, aid,
            data["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            data["title"]?.jsonPrimitive?.content ?: "",
            (data["pic"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it },
            data["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty())
    }.getOrNull()

    /**
     * 取可播放地址：
     *  - 先试 dash 的纯音频（体积小、音质好）
     *  - 没有音轨（部分视频/直播回放）就退到 durl 的 mp4
     * 新版视频的地址绑定会话（x-upsig），不带登录 Cookie 会 403，所以要逐个探测再返回。
     */
    data class Resolved(val url: String?, val needsLogin: Boolean)

    /** 探测地址是否可读：带 Cookie 请求第一个字节 */
    private fun probe(url: String, cookie: String): Int = runCatching {
        val c = open(url, cookie).apply {
            setRequestProperty("Range", "bytes=0-1")
            requestMethod = "GET"
        }
        val code = c.responseCode
        runCatching { c.inputStream?.close(); c.errorStream?.close() }
        code
    }.getOrDefault(0)

    private fun qualityLabelOf(id: Int): String = when (id) {
        6, 16 -> "360P"; 32 -> "480P"; 64, 74 -> "720P"; 80 -> "1080P"; 112, 116, 120 -> "1080P+"
        else -> "视频"
    }

    private fun qualityLabel(q: Int?): String = when (q) {
        6, 16 -> "360P"; 32 -> "480P"; 64, 74 -> "720P"; 80 -> "1080P"; 112, 116, 120 -> "1080P+"
        else -> "MP4"
    }

    /** html5 通道：返回 mp4（自带音轨），B站 对这条通道的签名在本机可用 */
    private fun html5Mp4(bvid: String, cid: String, cookie: String, qn: Int = 80): Pair<String, String>? = runCatching {
        val body = get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=0&fnver=0&fourk=1&platform=html5&qn=$qn", cookie)
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return null
        val url = data["durl"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: return null
        val q = data["quality"]?.jsonPrimitive?.content?.toIntOrNull()
        url to qualityLabel(q)
    }.getOrNull()

    fun resolve(bvid: String, cid: String, cookie: String = ""): Resolved {
        val candidates = mutableListOf<String>()
        runCatching {
            val dashBody = get("https://api.bilibili.com/x/player/playurl?fnval=16&fourk=1&bvid=$bvid&cid=$cid", cookie)
            val dash = kotlinx.serialization.json.Json.parseToJsonElement(dashBody).jsonObject["data"]
                ?.jsonObject?.get("dash")?.jsonObject
            val audios = dash?.get("audio")?.jsonArray ?: return@runCatching
            audios.forEach { el ->
                val o = el.jsonObject
                (o["baseUrl"] ?: o["base_url"])?.jsonPrimitive?.content?.let { candidates.add(it) }
                o["backupUrl"]?.jsonArray?.forEach { b -> candidates.add(b.jsonPrimitive.content) }
            }
        }
        runCatching {
            val durlBody = get("https://api.bilibili.com/x/player/playurl?fnval=1&fnver=0&fourk=1&bvid=$bvid&cid=$cid", cookie)
            kotlinx.serialization.json.Json.parseToJsonElement(durlBody).jsonObject["data"]
                ?.jsonObject?.get("durl")?.jsonArray?.forEach { el ->
                    el.jsonObject["url"]?.jsonPrimitive?.content?.let { candidates.add(it) }
                }
        }
        if (candidates.isEmpty()) {
            val mp4 = html5Mp4(bvid, cid, cookie)
            if (mp4 != null && probe(mp4.first, cookie) in 200..299) {
                return Resolved(mp4.first, needsLogin = false)
            }
            return Resolved(null, needsLogin = false)
        }
        // 逐个探测；第一能读的就用
        for (url in candidates.distinct().take(4)) {
            val code = probe(url, cookie)
            if (code in 200..299) return Resolved(url, needsLogin = false)
        }
        // dash 全部被拒时走 html5 通道（实测 platform=html5 的签名可用）
        val mp4 = html5Mp4(bvid, cid, cookie)
        if (mp4 != null && probe(mp4.first, cookie) in 200..299) {
            return Resolved(mp4.first, needsLogin = false)
        }
        return Resolved(null, needsLogin = cookie.isBlank())
    }

    fun playable(bvid: String, cid: String, cookie: String = ""): String? = resolve(bvid, cid, cookie).url

    /** 视频的互动数据：收藏 / 评论 / 点赞 / 播放 */
    data class Stats(val favorite: Long, val reply: Long, val like: Long, val view: Long)

    fun stats(bvid: String, cookie: String = ""): Stats? = runCatching {
        val body = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", cookie)
        val st = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
            ?.jsonObject?.get("stat")?.jsonObject ?: return null
        fun n(k: String) = st[k]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        Stats(favorite = n("favorite"), reply = n("reply"), like = n("like"), view = n("view"))
    }.getOrNull()

    data class VideoSource(val url: String, val quality: String)

    /**
     * 视频模式取流：优先 dash 的最高画质视频轨（带音轨），没有 dash 就用 durl 的 mp4。
     */
    fun videoSource(bvid: String, cid: String, cookie: String = "", lowQuality: Boolean = false): VideoSource? = runCatching {
        // 先要 dash 的真画面（清晰度高、一定有视频轨），失败再退 html5 的 mp4
        runCatching {
            val body = get("https://api.bilibili.com/x/player/playurl?fnval=4048&fnver=0&fourk=1&bvid=$bvid&cid=$cid", cookie)
            data class Track(val url: String, val id: Int, val band: Long, val codec: String)
            val tracks = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
                ?.jsonObject?.get("dash")?.jsonObject?.get("video")?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val u = (o["baseUrl"] ?: o["base_url"])?.jsonPrimitive?.content ?: return@mapNotNull null
                    Track(u, o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        o["bandwidth"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        o["codecs"]?.jsonPrimitive?.content ?: "")
                } ?: emptyList()
            val cap = if (lowQuality) 32 else 80
            val best = tracks.filter { it.codec.startsWith("avc1") && it.id <= cap }.maxByOrNull { it.band }
                ?: tracks.filter { it.id <= cap }.maxByOrNull { it.band }
            if (best != null && probe(best.url, cookie) in 200..299) {
                return@runCatching VideoSource(best.url, qualityLabelOf(best.id))
            }
        }
        // html5 的 mp4 同时含画面与声音，且签名在本机可用
        html5Mp4(bvid, cid, cookie, if (lowQuality) 16 else 80)?.let { (url, label) ->
            if (probe(url, cookie) in 200..299) return@runCatching VideoSource(url, label)
        }
        runCatching {
            val body = get("https://api.bilibili.com/x/player/playurl?fnval=4048&fnver=0&fourk=1&bvid=$bvid&cid=$cid", cookie)
            val videos = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
                ?.jsonObject?.get("dash")?.jsonObject?.get("video")?.jsonArray
            data class Track(val url: String, val id: Int, val band: Long, val codec: String)
            val tracks = videos?.mapNotNull { el ->
                val o = el.jsonObject
                val url = (o["baseUrl"] ?: o["base_url"])?.jsonPrimitive?.content ?: return@mapNotNull null
                Track(url,
                    o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    o["bandwidth"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    o["codecs"]?.jsonPrimitive?.content ?: "")
            } ?: emptyList()
            // H.264 兼容性最好（模拟器/低端机都能解），优先 AVC + ≤1080P；都不满足再退让
            val cap = if (lowQuality) 32 else 80
            val best = tracks.filter { it.codec.startsWith("avc1") && it.id <= cap }.maxByOrNull { it.band }
                ?: tracks.filter { it.id <= cap }.maxByOrNull { it.band }
                ?: tracks.maxByOrNull { it.band }
            if (best != null) {
                val label = when (best.id) {
                    6, 16 -> "360P"; 32 -> "480P"; 64, 74 -> "720P"; 80 -> "1080P"; 112, 116, 120 -> "1080P+"
                    else -> "视频"
                }
                return@runCatching VideoSource(best.url, label + if (best.codec.startsWith("avc1")) "" else " · " + best.codec.take(9))
            }
        }
        runCatching {
            val body = get("https://api.bilibili.com/x/player/playurl?fnval=1&fnver=0&fourk=1&bvid=$bvid&cid=$cid", cookie)
            val durl = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]
                ?.jsonObject?.get("durl")?.jsonArray?.firstOrNull()?.jsonObject
            durl?.get("url")?.jsonPrimitive?.content?.let { VideoSource(it, "MP4") }
        }.getOrNull()
    }.getOrNull()

    /** 给 B站 评论点赞/取消（action=1 赞，0 取消），需要登录 + csrf */
    fun likeComment(aid: String, rpid: String, like: Boolean, cookie: String, csrf: String): Boolean = runCatching {
        val c = (java.net.URL("https://api.bilibili.com/x/v2/reply/action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "oid=$aid&type=1&rpid=$rpid&action=" + (if (like) 1 else 0) + "&csrf=" + csrf
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content == "0"
    }.getOrDefault(false)


    // —— 以下取流链路移植自 Bilight（MIT）core/BiliRepository.kt 的 playback/parsedDashManifest ——
    data class Track(val url: String, val id: Int, val codecs: String, val bandwidth: Long,
                     val indexRange: String, val initialization: String, val width: Int = 0, val height: Int = 0)

    data class Plan(val video: Track?, val audio: Track?, val mp4: String?, val durationSec: Double, val quality: String)

    private fun parseTrack(o: kotlinx.serialization.json.JsonObject): Track? {
        val url = (o["baseUrl"] ?: o["base_url"])?.jsonPrimitive?.content ?: return null
        val seg = runCatching { o["SegmentBase"]?.jsonObject }.getOrNull()
            ?: runCatching { o["segment_base"]?.jsonObject }.getOrNull()
        val indexRange = seg?.get("indexRange")?.jsonPrimitive?.content
            ?: seg?.get("index_range")?.jsonPrimitive?.content ?: ""
        val initObj = runCatching { seg?.get("Initialization")?.jsonObject }.getOrNull()
        val init = initObj?.get("range")?.jsonPrimitive?.content
            ?: runCatching { seg?.get("initialization")?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return Track(
            url = url,
            id = o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            codecs = o["codecs"]?.jsonPrimitive?.content ?: "",
            bandwidth = o["bandwidth"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            indexRange = indexRange,
            initialization = init,
            width = o["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            height = o["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    /** wbi 签名的 playurl：优先 dash 的 avc 视频 + mp4a 音频，失败退 fnval=1 的整段 mp4 */
    fun plan(bvid: String, cid: String, preferred: Int = 80, cookie: String = ""): Plan? {
        fun read(fnval: String): kotlinx.serialization.json.JsonObject? {
            val body = BiliWbi.get("/x/player/wbi/playurl",
                mapOf("bvid" to bvid, "cid" to cid, "qn" to preferred.toString(),
                    "fnver" to "0", "fourk" to "1", "fnval" to fnval,
                    "platform" to if (fnval == "1") "html5" else "pc"), cookie) ?: return null
            return runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            }.getOrNull()
        }

        fun fromDash(data: kotlinx.serialization.json.JsonObject): Plan? {
            val dash = data["dash"]?.jsonObject ?: return null
            val videos = dash["video"]?.jsonArray?.mapNotNull { parseTrack(it.jsonObject) }
                ?.filter { it.codecs.startsWith("avc") } ?: emptyList()
            if (videos.isEmpty()) return null
            val audio = dash["audio"]?.jsonArray?.mapNotNull { parseTrack(it.jsonObject) }
                ?.filter { it.codecs.startsWith("mp4a") && it.indexRange.isNotBlank() && it.initialization.isNotBlank() }
                ?.maxByOrNull { it.bandwidth }
            val video = videos.filter { it.indexRange.isNotBlank() && it.initialization.isNotBlank() }
                .maxByOrNull { it.bandwidth } ?: return null
            val quality = when (video.id) {
                6, 16 -> "360P"; 32 -> "480P"; 64, 74 -> "720P"; 80 -> "1080P"; 112, 116, 120 -> "1080P+"
                else -> "视频"
            }
            return Plan(video, audio, null, dash["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0, quality)
        }

        fun fromMp4(data: kotlinx.serialization.json.JsonObject): Plan? {
            val durl = data["durl"]?.jsonArray ?: return null
            val url = durl.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content ?: return null
            if (!url.contains(".mp4")) return null
            val q = data["quality"]?.jsonPrimitive?.content?.toIntOrNull()
            val label = when (q) { 6, 16 -> "360P"; 32 -> "480P"; 64, 74 -> "720P"; 80 -> "1080P"; else -> "MP4" }
            return Plan(null, null, url, 0.0, label)
        }

        // 先要带画面的整段 mp4（html5 通道，实测能出画），拿不到才退 dash
        read("1")?.let { d -> fromMp4(d)?.let { return it } }
        read("4048")?.let { d -> fromDash(d)?.let { return it } }
        return null
    }

    /** 把分离的视频/音频轨拼成静态 MPD，交给 DashMediaSource（Bilight 的做法） */
    fun manifestOf(plan: Plan): String {
        fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        fun adaptation(t: Track, video: Boolean): String {
            val dims = if (video) "width=\"${t.width}\" height=\"${t.height}\"" else "audioSamplingRate=\"48000\""
            val segBase = if (t.indexRange.isNotBlank() && t.initialization.isNotBlank())
                "<SegmentBase indexRange=\"${t.indexRange}\"><Initialization range=\"${t.initialization}\"/></SegmentBase>"
            else ""
            val mime = if (video) "video/mp4" else "audio/mp4"
            val kind = if (video) "video" else "audio"
            return "<AdaptationSet mimeType=\"$mime\" contentType=\"$kind\" segmentAlignment=\"true\">" +
                "<Representation id=\"${t.id}\" codecs=\"${xml(t.codecs)}\" bandwidth=\"${t.bandwidth}\" $dims>" +
                "<BaseURL>${xml(t.url)}</BaseURL>$segBase</Representation></AdaptationSet>"
        }
        val dur = plan.durationSec.takeIf { it > 0 } ?: 0.0
        val video = plan.video?.let { adaptation(it, true) }.orEmpty()
        val audio = plan.audio?.let { adaptation(it, false) }.orEmpty()
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" minBufferTime=\"PT1.5S\" " +
            "mediaPresentationDuration=\"PT${dur}S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">" +
            "<Period duration=\"PT${dur}S\">$video$audio</Period></MPD>"
    }

    data class Comment(val id: String, val user: String, val avatar: String, val content: String,
                       val likes: Long, val timeSec: Long, val level: Int = 0,
                       val userId: Long = 0, val likedByMe: Boolean = false, val replyCount: Int = 0)
    data class CommentPage(val comments: List<Comment>, val total: Long, val more: Boolean,
        val nextCursor: String? = null)

    private fun commentPage(body: String, page: Int, size: Int): CommentPage {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.content?.toIntOrNull()?.let { it != 0 } == true)
            return CommentPage(emptyList(), 0, false)
        val data = root["data"]?.jsonObject ?: return CommentPage(emptyList(), 0, false)
        val replies = data["replies"]?.jsonArray.orEmpty()
        val comments = replies.mapNotNull { el ->
            val o = el.jsonObject
            val content = o["content"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val member = o["member"]?.jsonObject
            Comment(
                id = o["rpid"]?.jsonPrimitive?.content ?: o["rpid_str"]?.jsonPrimitive?.content.orEmpty(),
                user = member?.get("uname")?.jsonPrimitive?.content ?: "B站用户",
                avatar = (member?.get("avatar")?.jsonPrimitive?.content ?: "")
                    .let { if (it.startsWith("//")) "https:$it" else it },
                content = decodeEntities(content).trim(),
                likes = o["like"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                timeSec = o["ctime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                level = member?.get("level_info")?.jsonObject?.get("current_level")
                    ?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                userId = member?.get("mid")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                likedByMe = o["action"]?.jsonPrimitive?.content?.toIntOrNull() == 1,
                replyCount = o["rcount"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }
        val cursor = data["cursor"]?.jsonObject
        if (cursor != null) {
            val next = cursor["next"]?.jsonPrimitive?.content?.toLongOrNull()
            val hasMore = cursor["is_end"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false &&
                next != null && next > 0
            return CommentPage(comments,
                cursor["all_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: comments.size.toLong(),
                hasMore, next?.toString())
        }
        val pageInfo = data["page"]?.jsonObject
        val total = pageInfo?.get("count")?.jsonPrimitive?.content?.toLongOrNull()
            ?: data["page"]?.jsonObject?.get("acount")?.jsonPrimitive?.content?.toLongOrNull()
            ?: comments.size.toLong()
        val more = replies.isNotEmpty() && (page * size < total || replies.size >= size)
        return CommentPage(comments, total, more, if (more) (page + 1).toString() else null)
    }

    /** 评论：type=1 视频，oid=aid；sort=2 按热度，sort=0 按时间。 */
    fun comments(aid: String, hot: Boolean, page: Int = 1, size: Int = 20, cookie: String = ""): CommentPage = runCatching {
        val offset = if (page <= 1) "" else page.toString()
        val params = mapOf(
            "oid" to aid,
            "type" to "1",
            "mode" to if (hot) "3" else "2",
            "pagination_str" to "{\"offset\":\"$offset\"}",
            "plat" to "1",
            "web_location" to "1315875",
        )
        val modern = BiliWbi.get("/x/v2/reply/wbi/main", params, cookie)
        if (!modern.isNullOrBlank()) {
            val result = commentPage(modern, page, size)
            if (result.total > 0 || result.comments.isNotEmpty()) return result
        }
        val sort = if (hot) 2 else 0
        val body = get("https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&sort=$sort&ps=$size&pn=$page", cookie)
        commentPage(body, page, size)
    }.getOrDefault(CommentPage(emptyList(), 0, false))

    fun replies(aid: String, rootId: String, page: Int = 1, size: Int = 20,
        cookie: String = ""): CommentPage = runCatching {
        val body = get("https://api.bilibili.com/x/v2/reply/reply?type=1&oid=$aid" +
            "&root=$rootId&ps=$size&pn=$page", cookie)
        commentPage(body, page, size)
    }.getOrDefault(CommentPage(emptyList(), 0, false))

    private fun postComment(path: String, cookie: String, csrf: String, fields: Map<String, String>): Boolean =
        runCatching {
            val connection = (URL("https://api.bilibili.com$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = (fields + ("csrf" to csrf)).entries.joinToString("&") { (key, value) ->
                "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["code"]
                ?.jsonPrimitive?.content?.toIntOrNull() == 0
        }.getOrDefault(false)

    fun sendComment(aid: String, content: String, replyTo: String?, cookie: String, csrf: String): Boolean {
        if (cookie.isBlank() || csrf.isBlank()) return false
        val fields = linkedMapOf(
            "oid" to aid,
            "type" to "1",
            "message" to content,
            "plat" to "1",
        )
        if (!replyTo.isNullOrBlank()) {
            fields["root"] = replyTo
            fields["parent"] = replyTo
        }
        return postComment("/x/v2/reply/add", cookie, csrf, fields)
    }

    fun deleteComment(aid: String, rpid: String, cookie: String, csrf: String): Boolean {
        if (cookie.isBlank() || csrf.isBlank()) return false
        return postComment("/x/v2/reply/del", cookie, csrf,
            linkedMapOf("oid" to aid, "type" to "1", "rpid" to rpid))
    }
}
