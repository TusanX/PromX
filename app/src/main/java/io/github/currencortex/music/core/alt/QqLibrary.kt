// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐账号数据：我的歌单、我喜欢、歌单详情。全部走 y.qq.com 的 web 接口，
// 必须带登录 Cookie（qqmusic_key / uin），否则只能读到公开数据。
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.*

object QqLibrary {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
    private const val PLAYLIST_PAGE_SIZE = 500

    data class Folder(val id: Long, val title: String, val cover: String, val count: Int,
        val dirId: Long = 0, val dirType: Int = 0) {
        val writable get() = dirId in 1..199 && dirType == 0
    }
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
        val mvVid: String? = null,
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

    data class MusicuResult(val code: Int?, val data: JsonObject?) {
        val ok get() = code == 0
    }

    /** 当前 musicu RPC 的统一入口。保留业务码，写操作需要区分成功与登录失效。 */
    internal fun musicu(module: String, method: String, param: JsonObject,
        cookie: String = "", uin: String = "0"): MusicuResult {
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
        val root = postMusicu(body, cookie) ?: return MusicuResult(null, null)
        val outer = root["code"]?.jsonPrimitive?.intOrNull
        if (outer != null && outer != 0) return MusicuResult(outer, null)
        val request = root["req_0"]?.jsonObject ?: return MusicuResult(null, null)
        return MusicuResult(request["code"]?.jsonPrimitive?.intOrNull,
            request["data"]?.jsonObject)
    }

    /** 只读接口成功时返回 req_0.data。 */
    internal fun musicuData(module: String, method: String, param: JsonObject,
        cookie: String = "", uin: String = "0"): JsonObject? =
        musicu(module, method, param, cookie, uin).takeIf { it.ok }?.data

    /** 诊断：把几个关键接口的返回码/条目数打出来，方便判断账号态是否可用 */
    fun probe(cookies: Map<String, String>, uin: String): String {
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val folders = ownedPlaylists(uin, cookie)
        val liked = folders.firstOrNull(::isLikedFolder)
        val likedTracks = liked?.let { playlistDetail(it.id, cookie, uin)?.tracks?.size }
        return listOf(
            "我的歌单：${folders.count { !isLikedFolder(it) }} 个",
            "我喜欢：${likedTracks ?: 0} 首",
        ).joinToString("\n")
    }

    /** 当前账号自建歌单。GetPlaylistByUin 同时提供 tid 和 dirId，写「我喜欢」时不会再猜目录号。 */
    fun ownedPlaylists(uin: String, cookie: String): List<Folder> = runCatching {
        val data = musicuData(
            "music.musicasset.PlaylistBaseRead",
            "GetPlaylistByUin",
            buildJsonObject {
                put("uin", uin)
                put("bWithoutStatus", false)
            },
            cookie,
            uin,
        ) ?: return emptyList()
        data["v_playlist"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val id = listOf("tid", "dissid", "disstid", "id").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            } ?: return@mapNotNull null
            val dirId = o["dirId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val dirType = o["dirType"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            Folder(
                id = id,
                title = listOf("dirName", "diss_name", "dissname", "title")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull }
                    .orEmpty().ifBlank { if (dirId == 201L) "我喜欢" else "歌单 $id" },
                cover = listOf("picUrl", "dirCover", "diss_cover", "logo", "cover")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull }.orEmpty(),
                count = listOf("songNum", "song_cnt", "songnum", "song_num")
                    .firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
                    ?: 0,
                dirId = dirId,
                dirType = dirType,
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
            dirId = listOf("dirId", "dirid").firstNotNullOfOrNull { key ->
                o[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            } ?: 0L,
            dirType = o["dirType"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
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
        ownedPlaylists(uin, cookie).firstOrNull(::isLikedFolder)?.id?.let { return it }
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

    private fun isLikedFolder(folder: Folder): Boolean =
        folder.dirId == 201L || folder.title.trim().equals("我喜欢", true) ||
            folder.title.contains("我喜欢") || folder.title.contains("喜欢的音乐") ||
            folder.title.equals("Liked Songs", true)

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
        val detail = musicuData(
            "music.pf_song_detail_svr",
            "get_song_detail_yqq",
            buildJsonObject { put("song_type", 0); put("song_mid", mid) },
            cookie,
            uin,
        )?.get("track_info")?.jsonObject ?: return false
        if (detail["id"]?.jsonPrimitive?.longOrNull != songId ||
            detail["mid"]?.jsonPrimitive?.contentOrNull != mid) return false
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
    fun playlistDetail(id: Long, cookie: String, uin: String = "0"): PlaylistDetail? = runCatching {
        if (id <= 0) return null
        val tracksByMid = linkedMapOf<String, Track>()
        var info: JsonObject? = null
        var expectedCount: Int? = null
        var begin = 0
        var requests = 0
        while (requests < 200) {
            val data = musicuData(
                "music.srfDissInfo.aiDissInfo",
                "uniform_get_Dissinfo",
                buildJsonObject {
                    put("disstid", id)
                    put("userinfo", 1); put("tag", 1); put("orderlist", 1)
                    put("song_begin", begin); put("song_num", PLAYLIST_PAGE_SIZE)
                    put("onlysonglist", 0)
                },
                cookie,
                uin,
            ) ?: if (begin == 0) return null else break
            requests++
            if (info == null) {
                info = data["dirinfo"]?.jsonObject
                expectedCount = info?.get("songnum")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: data["songnum"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }
            val page = data["songlist"]?.jsonArray.orEmpty().mapNotNull(::trackOf)
            val before = tracksByMid.size
            page.forEach { tracksByMid[it.mid] = it }
            if (page.isEmpty()) break
            if (tracksByMid.size == before) break
            val total = expectedCount
            if (total != null && tracksByMid.size >= total) break
            if (total == null && page.size < PLAYLIST_PAGE_SIZE) break
            begin += page.size
        }
        val tracks = tracksByMid.values.toList()
        PlaylistDetail(
            id = id,
            title = info?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 歌单" },
            cover = info?.get("picurl")?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { tracks.firstOrNull()?.cover.orEmpty() },
            count = expectedCount ?: tracks.size,
            tracks = tracks,
        )
    }.getOrNull()

    private fun trackOf(el: JsonElement): Track? {
        val o = el.jsonObject
        val mid = o["mid"]?.jsonPrimitive?.content ?: return null
        val singers = o["singer"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }?.joinToString(" / ").orEmpty()
        val album = o["album"]?.jsonObject
        return Track(
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
            mvVid = o["mv"]?.jsonObject?.get("vid")?.jsonPrimitive?.contentOrNull
                ?: o["vid"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun playlist(id: Long, cookie: String, uin: String = "0"): List<Track> =
        playlistDetail(id, cookie, uin)?.tracks.orEmpty()

    fun ownedFolder(playlistId: Long, cookie: String, uin: String): Folder? =
        ownedPlaylists(uin, cookie).firstOrNull { it.id == playlistId && it.writable }

    /** 普通自建歌单的加歌/删歌。系统目录（我喜欢等）由独立红心协议处理。 */
    fun editPlaylistTracks(playlistId: Long, songIds: List<Long>, add: Boolean,
        cookie: String, uin: String): Boolean = runCatching {
        val folder = ownedFolder(playlistId, cookie, uin) ?: return false
        val songInfo = songIds.distinct().filter { it > 0 }.map { id ->
            buildJsonObject { put("songId", id); put("songType", 0) }
        }
        if (songInfo.isEmpty()) return false
        val data = musicuData(
            "music.musicasset.PlaylistDetailWrite",
            if (add) "AddSonglist" else "DelSonglist",
            buildJsonObject {
                put("dirId", folder.dirId)
                put("v_songInfo", JsonArray(songInfo))
            },
            cookie,
            uin,
        ) ?: return false
        data["retCode"]?.jsonPrimitive?.intOrNull == 0
    }.getOrDefault(false)

    fun createPlaylist(name: String, cookie: String, uin: String): Boolean = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.codePointCount(0, clean.length) <= 20)
        val data = musicuData(
            "music.musicasset.PlaylistBaseWrite",
            "AddPlaylist",
            buildJsonObject { put("dirName", clean) },
            cookie,
            uin,
        ) ?: return false
        val result = data["result"]?.jsonObject ?: return false
        (result["tid"]?.jsonPrimitive?.longOrNull ?: 0) > 0 &&
            (result["dirId"]?.jsonPrimitive?.longOrNull ?: 0) in 1..199
    }.getOrDefault(false)

    fun renamePlaylist(playlistId: Long, name: String, cookie: String, uin: String): Boolean = runCatching {
        val folder = ownedFolder(playlistId, cookie, uin) ?: return false
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.codePointCount(0, clean.length) <= 20)
        val edit = musicuData(
            "music.srfDissInfo.aiDissInfo",
            "uniform_get_Dissinfo",
            buildJsonObject {
                put("dirid", folder.dirId)
                put("optype", 3); put("tag", 1); put("userinfo", 0); put("pic_dpi", 300)
            },
            cookie,
            uin,
        ) ?: return false
        val info = edit["dirinfo"]?.jsonObject ?: return false
        val tags = info["tag"]?.jsonArray?.mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.longOrNull
        }?.joinToString(",").orEmpty()
        val data = musicuData(
            "music.musicasset.PlaylistBaseWrite",
            "EditPlaylist",
            buildJsonObject {
                put("dirId", folder.dirId)
                put("mask", 95)
                put("dirNewName", clean)
                put("dirNewDesc", info["desc"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("dirNewPicUrl", info["picurl"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("dirNewPicUrl2", "")
                put("dirNewtaglist", tags)
                put("dirShow", info["dir_show"]?.jsonPrimitive?.intOrNull ?: 1)
            },
            cookie,
            uin,
        ) ?: return false
        data["retCode"]?.jsonPrimitive?.intOrNull == 0
    }.getOrDefault(false)

    fun deletePlaylist(playlistId: Long, cookie: String, uin: String): Boolean = runCatching {
        val folder = ownedFolder(playlistId, cookie, uin) ?: return false
        val data = musicuData(
            "music.musicasset.PlaylistBaseWrite",
            "DelPlaylist",
            buildJsonObject { put("dirId", folder.dirId) },
            cookie,
            uin,
        ) ?: return false
        data["retCode"]?.jsonPrimitive?.intOrNull == 0 &&
            ownedFolder(playlistId, cookie, uin) == null
    }.getOrDefault(false)
}
