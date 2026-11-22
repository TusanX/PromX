// SPDX-License-Identifier: GPL-3.0-only
// 备用音源接入层（B站 / QQ音乐）。
// 端点均已实测可用（无需登录）：
//   B站搜索  GET https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=..
//   B站播放  GET https://api.bilibili.com/x/player/playurl?bvid=..&cid=..&fnval=16
//   QQ 搜索  GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=..&format=json
//   QQ 播放  POST https://u.y.qq.com/cgi-bin/musicu.fcg  (vkey.GetVkeyServer, 无需签名)
package io.github.currencortex.music.core.alt

import java.net.HttpURLConnection
import kotlinx.serialization.json.*
import java.net.URL

/** 一条来自备用音源的搜索结果 */
data class AltTrack(
    val source: AltSource,
    val id: String,          // B站 bvid / QQ songmid
    val title: String,
    val artist: String,
    val cover: String,
    val extra: String = "",  // B站 cid / QQ albummid
    val mediaId: String = id, // QQ 的 media_mid；取流文件名必须使用它
)

enum class AltSource(val label: String) { BILIBILI("哔哩哔哩"), QQ("QQ音乐") }

/** 取流地址的结果：B站与 QQ 都需要 Referer，播放时必须由播放器补头 */
data class AltStream(val url: String, val referer: String)

data class QqPlayable(val url: String, val format: String, val level: String,
    val validForSeconds: Long = 600, val durationMs: Long = 0, val preview: Boolean = false)

object AltSources {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    /** HTTP 结果：把状态码和正文都带回来，页面才能显示真实原因 */
    data class Raw(val code: Int, val body: String)

    private fun get(url: String, referer: String, cookie: String = ""): Raw {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        // B站需要 buvid3 cookie，否则搜索只返回 v_voucher（风控）
        val guest = "buvid3=1A2B3C4D-5E6F-7A8B-9C0D-1E2F3A4B5C6D12345infoc; b_nut=1700000000"
        c.setRequestProperty("Cookie", listOf(cookie, guest).filter(String::isNotBlank).joinToString("; "))
        c.connectTimeout = 12000; c.readTimeout = 15000
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return Raw(code, stream.bufferedReader().use { it.readText() })
    }

    private fun post(url: String, referer: String, body: String): Raw {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("content-type", "application/json")
        c.doOutput = true
        c.connectTimeout = 12000; c.readTimeout = 15000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return Raw(code, stream.bufferedReader().use { it.readText() })
    }

    /** 原始 JSON 返回（由上层用 kotlinx.serialization 解析，避免重复建模） */
    /** B站搜索（音乐向）：多取一页，过滤后仍能有足够结果 */
    fun searchBilibili(keyword: String, page: Int = 1, cookie: String = ""): Raw =
        get("https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=${page.coerceAtLeast(1)}&keyword=" +
            java.net.URLEncoder.encode(keyword, "UTF-8"), "https://www.bilibili.com/", cookie)

    fun searchQQ(keyword: String, page: Int = 1): Raw =
        get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=20&p=${page.coerceAtLeast(1)}&w=" +
            java.net.URLEncoder.encode(keyword, "UTF-8"), "https://y.qq.com/")

    fun suggestionsBilibili(keyword: String, cookie: String = ""): List<String> = runCatching {
        val body = get("https://s.search.bilibili.com/main/suggest?term=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") + "&main_ver=v1", "https://www.bilibili.com/", cookie).body
        val result = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
        val tags = result?.get("tag")?.jsonArray.orEmpty()
        tags.mapNotNull { tag ->
            val o = tag.jsonObject
            (o["value"] ?: o["name"] ?: o["term"])?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().take(8)
    }.getOrDefault(emptyList())

    fun suggestionsQQ(keyword: String): List<String> = runCatching {
        val body = get("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&key=" +
            java.net.URLEncoder.encode(keyword, "UTF-8"), "https://y.qq.com/").body
        val data = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return emptyList()
        val songs = data["song"]?.jsonObject?.get("itemlist")?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        }
        val singers = data["singer"]?.jsonObject?.get("itemlist")?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        }
        (songs + singers).distinct().take(8)
    }.getOrDefault(emptyList())

    /** B站音频流：返回 dash.audio 的第一条 */
    fun bilibiliAudio(bvid: String, cid: String): Raw =
        get("https://api.bilibili.com/x/player/playurl?fnval=16&fourk=1&bvid=$bvid&cid=$cid",
            "https://www.bilibili.com/")

    /** QQ 音乐榜单（无需登录）：topid 26=热歌，4=新歌，27=欧美 */
    fun qqToplist(topId: Int = 26, limit: Int = 20): Raw =
        get("https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid=$topId&format=json&page=detail&type=top&song_begin=0&song_num=$limit",
            "https://y.qq.com/")

    /** B站音乐区排行原始 JSON */
    fun bilibiliRanking(): String =
        get("https://api.bilibili.com/x/web-interface/ranking/v2?rid=3&type=all", "https://www.bilibili.com/").body

    /** B站：bvid → cid（播放必须） */
    fun bilibiliCid(bvid: String, cookie: String = ""): String? = runCatching {
        val raw = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", "https://www.bilibili.com/", cookie)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(raw.body).jsonObject
        root["data"]?.jsonObject?.get("cid")?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * B站取流：先 dash 纯音频，没有音轨就回退 durl 的 mp4（ExoPlayer 能解出音轨）。
     * 两条都拿不到时才返回 null（付费/大会员/地区限制）。
     */
    fun bilibiliPlayable(bvid: String, cid: String, cookie: String = ""): String? =
        BiliPlayback.playable(bvid, cid, cookie)

    /** QQ音乐：vkey 拼直链。播放器统一补 Referer。 */
    fun qqPlayable(songmid: String, mediaId: String = songmid,
        cookie: String = "", uin: String = "0"): QqPlayable? = runCatching {
        playableFromVkey(qqVkey(songmid, mediaId, cookie, uin)) ?: qqPreview(songmid, cookie, uin)
    }.getOrNull()

    private fun playableFromVkey(raw: Raw, preview: Boolean = false,
        durationMs: Long = 0): QqPlayable? {
        val root = Json.parseToJsonElement(raw.body).jsonObject
        val data = root["req_0"]?.jsonObject?.get("data")?.jsonObject ?: return null
        // QQ 有时返回 purl 但 sip 为空。sjy6 与 dl 都是实测可用的兼容 CDN。
        val sip = data["sip"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.firstOrNull { it.isNotBlank() && !it.startsWith("http://ws") }
            ?: "http://sjy6.stream.qqmusic.qq.com/"
        val purl = data["midurlinfo"]?.jsonArray.orEmpty().asSequence().mapNotNull {
            it.jsonObject["purl"]?.jsonPrimitive?.contentOrNull
        }.firstOrNull(String::isNotBlank) ?: return null
        val format = purl.substringBefore('?').substringAfterLast('.', "").lowercase()
            .ifBlank { if (preview) "mp3" else "m4a" }
        val level = if (preview) "preview" else when (purl.take(4)) {
            "F000" -> "lossless"
            "M800" -> "exhigh"
            "M500" -> "higher"
            else -> "standard"
        }
        val expiration = data["expiration"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: 600
        return QqPlayable(sip + purl, format, level, expiration, durationMs, preview)
    }

    private data class TrackDetail(val mediaId: String?, val sizeTry: Long, val previewMs: Long)

    private fun trackDetail(songmid: String, cookie: String, uin: String): TrackDetail? {
        val data = QqLibrary.musicuData(
            "music.pf_song_detail_svr",
            "get_song_detail_yqq",
            buildJsonObject { put("song_type", 0); put("song_mid", songmid) },
            cookie,
            uin,
        )
        val file = data?.get("track_info")?.jsonObject?.get("file")?.jsonObject ?: return null
        val end = file["e_30s"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        val start = file["b_30s"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0
        return TrackDetail(
            mediaId = file["media_mid"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.matches(Regex("[0-9A-Za-z]{14}")) },
            sizeTry = file["size_try"]?.jsonPrimitive?.longOrNull ?: 0,
            previewMs = end?.let { if (start < it) it - start else it } ?: 0,
        )
    }

    /** 会员/完整文件取不到时播放 QQ 官方 RS02 试听片段。 */
    private fun qqPreview(songmid: String, cookie: String, uin: String): QqPlayable? {
        val detail = trackDetail(songmid, cookie, uin) ?: return null
        if (detail.sizeTry <= 0 || detail.mediaId == null) return null
        val body = buildJsonObject {
            putJsonObject("comm") { put("ct", 24); put("cv", 4747474); put("platform", "yqq.json") }
            putJsonObject("req_0") {
                put("module", "music.vkey.GetVkey")
                put("method", "UrlGetVkey")
                putJsonObject("param") {
                    putJsonArray("filename") { add("RS02${detail.mediaId}.mp3") }
                    put("guid", "10000")
                    putJsonArray("songmid") { add(songmid) }
                    putJsonArray("songtype") { add(0) }
                    put("uin", uin); put("ctx", 0)
                }
            }
        }
        val raw = postWithCookie("https://u.y.qq.com/cgi-bin/musicu.fcg",
            "https://y.qq.com/", body.toString(), cookie)
        return playableFromVkey(raw, preview = true, durationMs = detail.previewMs)
    }

    /**
     * QQ 音乐 vkey：会员曲必须带登录态（uin + Cookie）才可能拿到 purl。
     * 一次性请求多个文件档位（m4a / mp3 / flac），取第一个非空。
     */
    fun qqVkey(songmid: String, mediaId: String = songmid,
        cookie: String = "", uin: String = "0"): Raw {
        require(songmid.matches(Regex("[0-9A-Za-z]{10,20}"))) { "QQ 音乐歌曲 ID 无效" }
        val effectiveMediaId = mediaId.takeIf { it.matches(Regex("[0-9A-Za-z]{10,20}")) } ?: songmid
        val guid = "10000"
        // QQ 的 filename 是「档位 + songmid + media_mid + 扩展名」。漏掉扩展名时接口
        // 仍可能返回 purl，但 CDN 会以 file not exist 404 拒绝，播放器只能看到播放失败。
        val files = listOf(
            "C400" to "m4a",
            "M500" to "mp3",
            "M800" to "mp3",
            "F000" to "flac",
        ).map { (prefix, extension) -> "$prefix$songmid$effectiveMediaId.$extension" }
        val authst = cookie.split(';')
            .map(String::trim)
            .firstOrNull { it.startsWith("qm_keyst=") || it.startsWith("qqmusic_key=") }
            ?.substringAfter('=')
            .orEmpty()
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("uin", uin)
                put("format", "json")
                put("ct", 24)
                put("cv", 0)
                if (authst.isNotBlank()) put("authst", authst)
            }
            putJsonObject("req_0") {
                put("module", "vkey.GetVkeyServer")
                put("method", "CgiGetVkey")
                putJsonObject("param") {
                    put("guid", guid)
                    putJsonArray("songmid") { add(songmid) }
                    putJsonArray("songtype") { add(0) }
                    put("uin", uin)
                    put("loginflag", 1)
                    put("platform", "20")
                    putJsonArray("filename") { files.forEach(::add) }
                }
            }
        }.toString()
        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg?-=getplaysongvkey&g_tk=5381" +
            "&loginUin=$uin&hostUin=0&format=json&platform=yqq.json&needNewCode=0"
        return postWithCookie(url, "https://y.qq.com/", body, cookie)
    }

    private fun postWithCookie(url: String, referer: String, body: String, cookie: String): Raw {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("content-type", "application/json")
        if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
        c.doOutput = true
        c.connectTimeout = 12000; c.readTimeout = 15000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return Raw(code, stream.bufferedReader().use { it.readText() })
    }
}

/** QQ 音乐的歌词与评论（都不需要登录，已用真实曲目验证） */
object QqExtras {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    private fun get(url: String): String {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://y.qq.com/")
            connectTimeout = 12000; readTimeout = 15000
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun unescape(s: String): String = s
        .replace("&#58;", ":").replace("&#10;", "\n").replace("&#46;", ".")
        .replace("&#45;", "-").replace("&#40;", "(").replace("&#41;", ")")
        .replace("&#32;", " ").replace("&amp;", "&").replace("&#39;", "'")

    /** 返回 LRC 文本（[mm:ss.xx] 行），失败返回 null */
    fun lyric(songmid: String): String? = runCatching {
        val raw = get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric.fcg?format=json&nobase64=1" +
            "&songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&platform=yqq&needNewCode=0")
        val body = raw.substringAfter('(', raw).substringBeforeLast(')')
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        val lyric = obj["lyric"]?.jsonPrimitive?.content ?: return null
        unescape(lyric).takeIf { it.isNotBlank() }
    }.getOrNull()

    data class Comment(val id: String, val content: String, val nick: String, val avatar: String,
        val likes: Long, val timeSeconds: Long, val likedByMe: Boolean, val replyCount: Int,
        val canDelete: Boolean)
    data class CommentPage(val comments: List<Comment>, val total: Long, val more: Boolean)

    /** 对于旧数据才用歌名回查，并用 songmid 排除搜索结果里的同名版本。 */
    fun resolveSongId(title: String, artist: String, songmid: String? = null): Long? = runCatching {
        val q = java.net.URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val raw = get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=$q&format=json")
        val list = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject["data"]
            ?.jsonObject?.get("song")?.jsonObject?.get("list")?.jsonArray ?: return null
        val exact = songmid?.let { mid -> list.firstOrNull {
            it.jsonObject["songmid"]?.jsonPrimitive?.contentOrNull == mid
        } }
        exact?.jsonObject?.get("songid")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }.getOrNull()

    private fun commentOf(el: JsonElement): Comment? = runCatching {
        val o = el.jsonObject
        val id = o["CmId"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = o["Content"]?.jsonPrimitive?.contentOrNull ?: return null
        Comment(
            id = id,
            content = content,
            nick = o["Nick"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 用户" },
            avatar = o["Avatar"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace("http://", "https://"),
            likes = o["PraiseNum"]?.jsonPrimitive?.longOrNull ?: 0,
            timeSeconds = o["PubTime"]?.jsonPrimitive?.longOrNull ?: 0,
            likedByMe = o["IsPraised"]?.jsonPrimitive?.intOrNull == 1,
            replyCount = (o["ReplyCnt"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0),
            canDelete = o["IsSelf"]?.jsonPrimitive?.intOrNull == 1 ||
                (o["Permission"]?.jsonPrimitive?.intOrNull ?: 0) >= 512,
        )
    }.getOrNull()

    private fun commentPage(data: JsonObject): CommentPage {
        val list = data["CommentList"]?.jsonObject
        val comments = list?.get("Comments")?.jsonArray.orEmpty().mapNotNull(::commentOf)
        val total = list?.get("Total")?.jsonPrimitive?.longOrNull
            ?: data["TotalCmNum"]?.jsonPrimitive?.longOrNull ?: comments.size.toLong()
        val more = list?.get("HasMore")?.jsonPrimitive?.booleanOrNull == true ||
            list?.get("HasMore")?.jsonPrimitive?.intOrNull == 1
        return CommentPage(comments, total, more)
    }

    fun comments(songId: Long, latest: Boolean = false, page: Int = 1,
        pageSize: Int = 20): CommentPage = runCatching {
        val data = QqLibrary.musicuData(
            "music.globalComment.CommentRead",
            if (latest) "GetNewCommentList" else "GetHotCommentList",
            buildJsonObject {
                put("BizType", 1)
                put("BizId", songId.toString())
                put("LastCommentSeqNo", "")
                put("PageSize", pageSize.coerceIn(1, 50))
                put("PageNum", (page - 1).coerceAtLeast(0))
                put("PicEnable", 1)
                if (latest) {
                    put("FromCommentId", "")
                    put("WithHot", 0)
                } else {
                    put("HotType", 1)
                    put("WithAirborne", 0)
                }
            },
        ) ?: return CommentPage(emptyList(), 0, false)
        commentPage(data)
    }.getOrDefault(CommentPage(emptyList(), 0, false))

    fun replies(rootCommentId: String, page: Int = 1, pageSize: Int = 20): CommentPage = runCatching {
        val data = QqLibrary.musicuData(
            "music.globalComment.CommentRead",
            "GetReplyCommentList",
            buildJsonObject {
                put("RootCmId", rootCommentId)
                put("LastCommentSeqNo", "")
                put("PageSize", pageSize.coerceIn(1, 50))
                put("LastRankScore", "")
                put("RankType", 1)
                put("PicEnable", 1)
                put("PageNum", (page - 1).coerceAtLeast(0))
            },
        ) ?: return CommentPage(emptyList(), 0, false)
        commentPage(data)
    }.getOrDefault(CommentPage(emptyList(), 0, false))

    fun sendComment(songId: Long, content: String, repliedCommentId: String?,
        cookies: Map<String, String>, uin: String): Boolean = runCatching {
        require(content.isNotBlank() && content.trim().length <= 300 && cookies.isNotEmpty())
        val result = QqLibrary.musicu(
            "music.globalComment.CommentWriteServer",
            "AddComment",
            buildJsonObject {
                put("BizType", 1)
                put("BizId", songId.toString())
                put("Content", content.trim())
                put("RepliedCmId", repliedCommentId.orEmpty())
                put("BgCardId", JsonNull)
            },
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            uin,
        )
        // 20015 is what the official web comment box treats as a successful submission.
        result.code == 0 || result.code == 20015
    }.getOrDefault(false)

    fun likeComment(commentId: String, liked: Boolean,
        cookies: Map<String, String>, uin: String): Boolean = runCatching {
        if (cookies.isEmpty()) return false
        val result = QqLibrary.musicu(
            "GlobalComment.GlobalCommentWriteServer",
            "UpdateHotComment",
            buildJsonObject {
                put("comment_id", commentId)
                put("type", if (liked) 3 else 4)
                put("uin", uin)
            },
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            uin,
        )
        if (!result.ok) return false
        val data = result.data ?: return true
        (data["code"]?.jsonPrimitive?.intOrNull ?: 0) == 0 &&
            (data["subcode"]?.jsonPrimitive?.intOrNull ?: 0) == 0
    }.getOrDefault(false)

    fun deleteComment(commentId: String,
        cookies: Map<String, String>, uin: String): Boolean = runCatching {
        if (cookies.isEmpty()) return false
        val result = QqLibrary.musicu(
            "music.globalComment.CommentWriteServer",
            "DelComment",
            buildJsonObject { put("CommentId", commentId) },
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            uin,
        )
        if (!result.ok) return false
        val data = result.data ?: return true
        (data["Subcode"]?.jsonPrimitive?.intOrNull ?: 0) == 0 &&
            (data["Code"]?.jsonPrimitive?.intOrNull ?: 0) == 0
    }.getOrDefault(false)
}

/** 各「模式」下主页推荐的数据源（都是公开接口，无需登录） */
object AltFilter {
    /** "mm:ss" / "h:mm:ss" / "419:2" → 秒；解析失败返回 -1 */
    fun seconds(duration: String?): Int {
        if (duration.isNullOrBlank()) return -1
        val parts = duration.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> -1
        }
    }

    private val videoWords = listOf("解说", "挑战", "教程", "教学", "采访", "直播", "整活", "搞笑",
        "vlog", "Vlog", "测评", "reaction", "Reaction", "盘点", "鬼畜", "广告", "花絮", "纪录片")
    private val musicWords = listOf("音乐", "MV", "mv", "歌曲", "歌", "曲", "纯享", "无损", "演奏",
        "翻唱", "cover", "Cover", "VOCALOID", "原创", "专辑", "现场", "Live", "live", "Remix", "伴奏")

    /** 是不是「一首歌」而不是视频：时长 45s–10min，标题像歌，且不含视频类关键词 */
    fun looksLikeSong(duration: String?, typename: String? = null, title: String? = null): Boolean {
        val sec = seconds(duration)
        // 不再限制上限：循环歌单/合集是用户要的；只挡掉过短的片头
        if (sec in 1..20) return false
        val t = title.orEmpty()
        if (videoWords.any { t.contains(it) }) return false
        if (!typename.isNullOrBlank() && typename.contains("音乐")) return true
        return musicWords.any { t.contains(it) }
    }
}

object AltRecommend {
    /**
     * B站 首页个性化推荐（wbi 签名 + 登录态）。
     * 这是每天会变的那条流，比固定榜单更适合做「每日推荐」。
     */
    fun bilibiliRcmd(cookie: String, limit: Int = 30): List<AltTrack> = runCatching {
        val body = BiliWbi.get("/x/web-interface/wbi/index/top/feed/rcmd", mapOf(
            "ps" to limit.toString(), "fresh_type" to "4", "fresh_idx" to "1", "fresh_idx_1h" to "1",
            "fetch_row" to "1", "brush" to "0", "homepage_ver" to "1", "plat" to "1",
        ), cookie) ?: return emptyList()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        val items = root["data"]?.jsonObject?.get("item")?.jsonArray ?: return emptyList()
        items.mapNotNull { el ->
            val o = el.jsonObject
            val bvid = o["bvid"]?.jsonPrimitive?.content ?: o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!bvid.startsWith("BV")) return@mapNotNull null
            val pic = (o["pic"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it }
            val title = o["title"]?.jsonPrimitive?.content.orEmpty().replace(Regex("<[^>]+>"), "")
            val duration = o["duration"]?.jsonPrimitive?.content
            if (!AltFilter.looksLikeSong(duration, o["tname"]?.jsonPrimitive?.content, title)) return@mapNotNull null
            AltTrack(AltSource.BILIBILI, bvid,
                title,
                o["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                pic,
                extra = duration.orEmpty())
        }
    }.getOrDefault(emptyList())

    /** B站音乐区排行（rid=3 音乐区） */
    fun bilibiliHot(limit: Int = 30): List<AltTrack> = runCatching {
        val raw = AltSources.bilibiliRanking()
        val list = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .jsonObject["data"]?.jsonObject?.get("list")?.jsonArray ?: return emptyList()
        list.asSequence().filter { el ->
            val o = el.jsonObject
            AltFilter.looksLikeSong(o["duration"]?.jsonPrimitive?.content, o["tname"]?.jsonPrimitive?.content,
                o["title"]?.jsonPrimitive?.content)
        }.take(limit).mapNotNull { el ->
            val o = el.jsonObject
            val bvid = o["bvid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            AltTrack(AltSource.BILIBILI, bvid,
                o["title"]?.jsonPrimitive?.content ?: "",
                o["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                (o["pic"]?.jsonPrimitive?.content ?: "").let { if (it.startsWith("//")) "https:$it" else it })
        }.toList()
    }.getOrDefault(emptyList())

    private fun qqChart(topId: Int, limit: Int): List<AltTrack> = runCatching {
        val raw = io.github.currencortex.music.core.alt.AltSources.qqToplist(topId, limit)
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw.body)
            .jsonObject["songlist"]?.jsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
            val mid = o["songmid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val album = o["albummid"]?.jsonPrimitive?.content ?: ""
            AltTrack(AltSource.QQ, mid,
                o["songname"]?.jsonPrimitive?.content ?: "",
                (o["singer"] as? kotlinx.serialization.json.JsonArray)?.joinToString(" / ") {
                    it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                } ?: "",
                if (album.isNotBlank()) "https://y.qq.com/music/photo_new/T002R300x300M000$album.jpg" else "",
                extra = o["songid"]?.jsonPrimitive?.content ?: "",
                mediaId = o["media_mid"]?.jsonPrimitive?.contentOrNull
                    ?: o["strMediaMid"]?.jsonPrimitive?.contentOrNull ?: mid)
        }
    }.getOrDefault(emptyList())

    /** QQ 热歌榜 */
    fun qqHot(limit: Int = 30): List<AltTrack> = qqChart(26, limit)

    /** QQ 新歌榜 */
    fun qqNew(limit: Int = 30): List<AltTrack> = qqChart(4, limit)
}
