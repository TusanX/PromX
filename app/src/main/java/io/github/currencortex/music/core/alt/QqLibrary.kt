// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐账号数据：我的歌单、我喜欢、歌单详情。全部走 y.qq.com 的 web 接口，
// 必须带登录 Cookie（qqmusic_key / uin），否则只能读到公开数据。
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.*

object QqLibrary {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Folder(val id: Long, val title: String, val cover: String, val count: Int)
    data class PlaylistDetail(val id: Long, val title: String, val cover: String, val count: Int, val tracks: List<Track>)
    data class Track(
        val mid: String,
        val name: String,
        val artists: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
        val mediaId: String = mid,
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

    /** 用户创建的歌单（含「我喜欢」以外的自建歌单） */
    fun folders(uin: String, cookie: String): List<Folder> = runCatching {
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json", cookie)
        parse(body)?.get("data")?.jsonObject?.get("disslist")?.jsonArray
            ?.mapNotNull(::folderOf).orEmpty()
    }.getOrDefault(emptyList()).ifEmpty { profileFolders(uin, cookie) }

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

    /** 把一首歌加入/移出「我喜欢」歌单（dirid 201）。需要 g_tk，由 p_skey/skey 计算。 */
    fun setLiked(mid: String, liked: Boolean, cookies: Map<String, String>, uin: String,
        dirid: Long = 201L): Boolean = runCatching {
        val key = cookies["qqmusic_key"] ?: cookies["qm_keyst"]
            ?: cookies["p_skey"] ?: cookies["skey"] ?: return false
        var hash = 5381L
        for (ch in key) hash = (hash * 33 + ch.code) and 0x7fffffff
        val gtk = hash
        val add = if (liked) "add" else "del"
        val body = "midlist=" + java.net.URLEncoder.encode(mid, "UTF-8") +
            "&dirid=$dirid&addtype=" + add + "&srctype=1&uin=" + uin + "&format=json&g_tk=" + gtk
        val c = java.net.URL("https://c.y.qq.com/splcloud/fcgi-bin/fcg_music_add2songdir.fcg?g_tk=$gtk&format=json")
            .openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://y.qq.com/")
        c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)!!
            .bufferedReader().use { it.readText() }
        text.contains("\"code\":0") || text.contains("\"subcode\":0")
    }.getOrDefault(false)

    /** 当前网页版歌单详情接口。旧 fcg_ucc_getcdinfo_byids_cp 已返回 4000。 */
    fun playlistDetail(id: Long, cookie: String): PlaylistDetail? = runCatching {
        if (id <= 0) return null
        val body = kotlinx.serialization.json.buildJsonObject {
            put("comm", kotlinx.serialization.json.buildJsonObject {
                put("ct", 24); put("cv", 0); put("format", "json")
            })
            put("req_1", kotlinx.serialization.json.buildJsonObject {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                put("param", kotlinx.serialization.json.buildJsonObject {
                    put("disstid", id)
                    put("userinfo", 1); put("tag", 1); put("orderlist", 1)
                    put("song_begin", 0); put("song_num", 1000); put("onlysonglist", 0)
                })
            })
        }
        val req = postMusicu(body, cookie)?.get("req_1")?.jsonObject ?: return null
        if (req["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != 0) return null
        val data = req["data"]?.jsonObject ?: return null
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
