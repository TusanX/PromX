// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐账号数据：我的歌单、我喜欢、歌单详情。全部走 y.qq.com 的 web 接口，
// 必须带登录 Cookie（qqmusic_key / uin），否则只能读到公开数据。
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.*

object QqLibrary {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Folder(val id: Long, val title: String, val cover: String, val count: Int,
        val dirId: Long = 0)
    data class PlaylistDetail(val id: Long, val title: String, val cover: String, val count: Int, val tracks: List<Track>)
    data class Track(
        val mid: String,
        val name: String,
        val artists: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
        val mediaId: String = mid,
        val numericId: Long? = null,
    )

    private fun get(url: String, cookie: String): String {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://y.qq.com/")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            connectTimeout = 12_000; readTimeout = 15_000
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parse(body: String): kotlinx.serialization.json.JsonObject? =
        runCatching {
            val trimmed = body.trim().removePrefix("callback(").removeSuffix(")").removeSuffix(";")
            Json.parseToJsonElement(trimmed).jsonObject
        }.getOrNull()

    private fun postMusicu(body: kotlinx.serialization.json.JsonObject, cookie: String): kotlinx.serialization.json.JsonObject? =
        runCatching {
            val connection = java.net.URL("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Referer", "https://y.qq.com/")
            connection.setRequestProperty("Content-Type", "application/json")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                ?: return null
            parse(stream.bufferedReader().use { it.readText() })
        }.getOrNull()

    /** 当前 musicu RPC 的统一入口。成功时返回 req_0.data。 */
    internal fun musicuData(module: String, method: String, param: JsonObject,
        cookie: String = "", uin: String = "0"): JsonObject? {
        val authst = cookie.split(';').map(String::trim)
            .firstOrNull { it.startsWith("qm_keyst=") || it.startsWith("qqmusic_key=") }
            ?.substringAfter('=').orEmpty()
        var gtkHash = 5381L
        for (char in authst) gtkHash = (gtkHash + (gtkHash shl 5) + char.code) and 0x7fffffff
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 24)
                put("cv", 4747474)
                put("platform", "yqq.json")
                put("format", "json")
                put("uin", uin.ifBlank { "0" })
                put("g_tk", gtkHash)
                if (authst.isNotBlank()) put("authst", authst)
            }
            putJsonObject("req_0") {
                put("module", module)
                put("method", method)
                put("param", param)
            }
        }
        val root = postMusicu(body, cookie) ?: return null
        if (root["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        val request = root["req_0"]?.jsonObject ?: return null
        if (request["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        return request["data"]?.jsonObject
    }

    /** 诊断：把几个关键接口的返回码/条目数打出来，方便判断账号态是否可用 */
    fun probe(cookies: Map<String, String>, uin: String): String {
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        fun report(label: String, url: String, read: (String) -> Int): String = runCatching {
            val body = get(url, cookie)
            label + ": " + runCatching { read(body) }.getOrDefault(-1) + " 条"
        }.getOrElse { label + ": 失败 " + it.javaClass.simpleName }
        return listOf(
            report("我创建的歌单", "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json") { body ->
                parse(body)?.get("data")?.jsonObject?.get("disslist")?.jsonArray?.size ?: 0
            },
            "我喜欢（歌单 ${likedFolderId(uin, cookie)}）: ${playlistDetail(likedFolderId(uin, cookie), cookie)?.tracks?.size ?: 0} 首",
        ).joinToString("\n")
    }

    /** 当前账号自建歌单。GetPlaylistByUin 同时提供 tid 和 dirId，写「我喜欢」时不会再猜目录号。 */
    fun ownedPlaylists(uin: String, cookie: String): List<Folder> = runCatching {
        val data = musicuData(
            "music.musicasset.PlaylistBaseRead",
            "GetPlaylistByUin",
            buildJsonObject { put("uin", uin) },
            cookie,
            uin,
        ) ?: return emptyList()
        data["v_playlist"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val id = listOf("tid", "dissid", "disstid", "id").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            } ?: return@mapNotNull null
            val dirId = o["dirId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            Folder(
                id = id,
                title = listOf("dirName", "diss_name", "dissname", "title")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull }
                    .orEmpty().ifBlank { if (dirId == 201L) "我喜欢" else "歌单 $id" },
                cover = listOf("dirCover", "diss_cover", "logo", "cover")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull }.orEmpty(),
                count = listOf("songNum", "song_cnt", "songnum", "song_num")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
                    ?: 0,
                dirId = dirId,
            )
        }
    }.getOrDefault(emptyList())

    /** 用户创建的歌单（优先当前接口，旧接口只保留为兼容回退） */
    fun folders(uin: String, cookie: String): List<Folder> = ownedPlaylists(uin, cookie).ifEmpty {
        runCatching {
            val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json", cookie)
            parse(body)?.get("data")?.jsonObject?.get("disslist")?.jsonArray
                ?.mapNotNull(::folderOf).orEmpty()
        }.getOrDefault(emptyList()).ifEmpty { profileFolders(uin, cookie) }
    }

    private fun folderOf(el: JsonElement): Folder? {
        val o = el.jsonObject
        val id = listOf("tid", "dissid", "disstid", "id").firstNotNullOfOrNull { key ->
            o[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
        } ?: return null
        return Folder(
            id = id,
            title = listOf("diss_name", "dissname", "title").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull
            }.orEmpty().ifBlank { "歌单 $id" },
            cover = listOf("diss_cover", "logo", "cover").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull
            }.orEmpty(),
            count = listOf("song_cnt", "songnum", "song_num").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            } ?: 0,
        )
    }

    private fun profileFolders(uin: String, cookie: String): List<Folder> = runCatching {
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
            "?cid=205360838&userid=$uin&reqfrom=1&format=json", cookie)
        parse(body)?.get("data")?.jsonObject?.get("mydiss")?.jsonArray
            ?.mapNotNull(::folderOf).orEmpty()
    }.getOrDefault(emptyList())

    /** 我喜欢：QQ 的「我喜欢」是特殊歌单，tid 固定为 201（不同账号可能是 dissid） */
    fun likedFolderId(uin: String, cookie: String): Long = runCatching {
        ownedPlaylists(uin, cookie).firstOrNull { it.dirId == 201L }?.id?.let { return it }
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json", cookie)
        val list = parse(body)?.get("data")?.jsonObject?.get("disslist")?.jsonArray ?: return 201L
        list.firstOrNull { el ->
            val name = listOf("diss_name", "dissname", "title")
                .firstNotNullOfOrNull { key -> el.jsonObject[key]?.jsonPrimitive?.contentOrNull }.orEmpty()
            name.contains("我喜欢") || name.equals("喜欢的音乐", true)
        }?.jsonObject?.let { favorite ->
            listOf("tid", "dissid", "disstid").firstNotNullOfOrNull { key ->
                favorite[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            }
        } ?: profileFavoriteId(uin, cookie) ?: 201L
    }.getOrDefault(201L)

    private fun profileFavoriteId(uin: String, cookie: String): Long? = runCatching {
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
            "?cid=205360838&userid=$uin&reqfrom=1&format=json", cookie)
        parse(body)?.get("data")?.jsonObject?.get("mymusic")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > 0 }
    }.getOrNull()

    /** 批量读取红心状态。该接口只认 songmid，比重读整个「我喜欢」歌单稳定。 */
    fun likedMids(mids: List<String>, cookie: String, uin: String): Map<String, Boolean> = runCatching {
        val values = mids.filter { it.matches(Regex("[0-9A-Za-z]{10,20}")) }.distinct()
        if (values.isEmpty()) return emptyMap()
        val data = musicuData(
            "music.musicasset.SongFavRead",
            "IsSongFanByMid",
            buildJsonObject { put("v_songMid", buildJsonArray { values.forEach(::add) }) },
            cookie,
            uin,
        ) ?: return emptyMap()
        data["m_fan"]?.jsonObject.orEmpty().mapNotNull { (mid, value) ->
            value.jsonPrimitive.booleanOrNull?.let { mid to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun isLiked(mid: String, cookie: String, uin: String): Boolean? =
        likedMids(listOf(mid), cookie, uin)[mid]

    /** 当前 musicu 写入协议：数字 songId 写入 dirId=201，再用 songmid 读回确认。 */
    fun setLiked(songId: Long, mid: String, liked: Boolean,
        cookies: Map<String, String>, uin: String): Boolean = runCatching {
        require(songId > 0)
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (isLiked(mid, cookie, uin) == liked) return true
        val data = musicuData(
            "music.musicasset.PlaylistDetailWrite",
            if (liked) "AddSonglist" else "DelSonglist",
            buildJsonObject {
                put("dirId", 201)
                putJsonArray("v_songInfo") {
                    addJsonObject {
                        put("songId", songId)
                        put("songType", 0)
                    }
                }
            },
            cookie,
            uin,
        ) ?: return false
        if (data["retCode"]?.jsonPrimitive?.intOrNull?.let { it != 0 } != false) return false
        isLiked(mid, cookie, uin) == liked
    }.getOrDefault(false)

    /** 当前网页版歌单详情接口。旧 fcg_ucc_getcdinfo_byids_cp 已返回 4000。 */
    fun playlistDetail(id: Long, cookie: String): PlaylistDetail? = runCatching {
        if (id <= 0) return null
        val data = musicuData(
            "music.srfDissInfo.aiDissInfo",
            "uniform_get_Dissinfo",
            buildJsonObject {
                put("disstid", id)
                put("userinfo", 1); put("tag", 1); put("orderlist", 1)
                put("song_begin", 0); put("song_num", 1000); put("onlysonglist", 0)
            },
            cookie,
        ) ?: return null
        val info = data["dirinfo"]?.jsonObject
        val tracks = data["songlist"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val mid = o["mid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val singers = o["singer"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }?.joinToString(" / ").orEmpty()
            val album = o["album"]?.jsonObject
            Track(
                mid = mid,
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                artists = singers,
                album = album?.get("name")?.jsonPrimitive?.content.orEmpty(),
                cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" +
                    (album?.get("mid")?.jsonPrimitive?.content.orEmpty()) + ".jpg",
                durationMs = (o["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 1000,
                mediaId = o["file"]?.jsonObject?.get("media_mid")?.jsonPrimitive?.contentOrNull
                    ?: o["media_mid"]?.jsonPrimitive?.contentOrNull
                    ?: o["strMediaMid"]?.jsonPrimitive?.contentOrNull ?: mid,
                numericId = listOf("id", "songid")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.longOrNull },
            )
        }
        PlaylistDetail(
            id = id,
            title = info?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 歌单" },
            cover = info?.get("picurl")?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { tracks.firstOrNull()?.cover.orEmpty() },
            count = info?.get("songnum")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: tracks.size,
            tracks = tracks,
        )
    }.getOrNull()

    fun playlist(id: Long, cookie: String): List<Track> =
        playlistDetail(id, cookie)?.tracks.orEmpty()
}
