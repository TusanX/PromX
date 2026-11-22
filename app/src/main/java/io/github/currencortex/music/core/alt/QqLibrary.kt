// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐账号数据：我的歌单、我喜欢、歌单详情。全部走 y.qq.com 的 web 接口，
// 必须带登录 Cookie（qqmusic_key / uin），否则只能读到公开数据。
package io.github.currencortex.music.core.alt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object QqLibrary {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    data class Folder(val id: Long, val title: String, val cover: String, val count: Int)
    data class Track(
        val mid: String,
        val name: String,
        val artists: String,
        val album: String,
        val cover: String,
        val durationMs: Long,
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
            report("我喜欢", "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&disstid=" +
                likedFolderId(uin, cookie) + "&format=json") { body ->
                parse(body)?.get("cdlist")?.jsonArray?.firstOrNull()?.jsonObject?.get("songlist")?.jsonArray?.size ?: 0
            },
        ).joinToString("\n")
    }

    /** 用户创建的歌单（含「我喜欢」以外的自建歌单） */
    fun folders(uin: String, cookie: String): List<Folder> = runCatching {
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json", cookie)
        val data = parse(body)?.get("data")?.jsonObject ?: return emptyList()
        val list = data["disslist"]?.jsonArray ?: return emptyList()
        list.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["tid"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: o["dissid"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            if (id <= 0) return@mapNotNull null
            Folder(
                id = id,
                title = o["diss_name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "歌单 $id" },
                cover = o["diss_cover"]?.jsonPrimitive?.content.orEmpty(),
                count = o["song_cnt"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    /** 我喜欢：QQ 的「我喜欢」是特殊歌单，tid 固定为 201（不同账号可能是 dissid） */
    fun likedFolderId(uin: String, cookie: String): Long = runCatching {
        val body = get("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostuin=$uin&sin=0&size=200&format=json", cookie)
        val list = parse(body)?.get("data")?.jsonObject?.get("disslist")?.jsonArray ?: return 201L
        list.firstOrNull { el ->
            val name = el.jsonObject["diss_name"]?.jsonPrimitive?.content.orEmpty()
            name.contains("我喜欢") || name.equals("喜欢的音乐", true)
        }?.jsonObject?.get("tid")?.jsonPrimitive?.content?.toLongOrNull() ?: 201L
    }.getOrDefault(201L)

    /** 把一首歌加入/移出「我喜欢」歌单（dirid 201）。需要 g_tk，由 p_skey/skey 计算。 */
    fun setLiked(mid: String, liked: Boolean, cookies: Map<String, String>, uin: String,
        dirid: Long = 201L): Boolean = runCatching {
        val key = cookies["p_skey"] ?: cookies["skey"] ?: return false
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

    /** 歌单详情（含歌曲） */
    fun playlist(id: Long, cookie: String): List<Track> = runCatching {
        val body = get("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
            "?type=1&json=1&utf8=1&onlysong=0&disstid=$id&format=json", cookie)
        val root = parse(body) ?: return emptyList()
        val cd = root["cdlist"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val songs = cd["songlist"]?.jsonArray ?: return emptyList()
        songs.mapNotNull { el ->
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
            )
        }
    }.getOrDefault(emptyList())
}
