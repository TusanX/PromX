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
    data class ListeningMonth(val month: Int, val count: Long)
    data class ListeningCard(val key: String, val title: String, val value: String,
        val summary: String, val image: String)
    data class ListeningReport(val nickname: String, val avatar: String, val signature: String,
        val months: List<ListeningMonth>, val cards: List<ListeningCard>, val description: String)
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
        val albumMid: String = "",
        val albumId: Long? = null,
        val artistMids: List<String> = emptyList(),
        val artistIds: List<Long> = emptyList(),
    )
    data class CatalogItem(val key: String, val id: Long, val name: String, val cover: String,
        val subtitle: String = "")
    data class CategoryGroup(val id: Long, val name: String, val categories: List<Category>)
    data class Category(val id: Long, val name: String)
    data class ChartPreview(val rank: Int, val title: String, val artist: String)
    data class Chart(val id: Long, val title: String, val intro: String, val cover: String,
        val period: String, val total: Int, val listenCount: Long, val preview: List<ChartPreview>)
    data class ChartGroup(val id: Long, val name: String, val charts: List<Chart>)
    data class ChartDetail(val title: String, val intro: String, val cover: String,
        val period: String, val total: Int, val songs: List<Track>)
    data class PlaylistPreview(val id: Long, val title: String, val description: String,
        val cover: String, val playCount: Long?, val creator: String)
    data class AlbumInfo(val mid: String, val id: Long, val name: String, val cover: String,
        val artists: String, val description: String, val publishTime: Long, val songs: List<Track>)
    data class ArtistInfo(val mid: String, val id: Long, val name: String, val cover: String,
        val description: String, val songTotal: Int, val albumTotal: Int, val fans: Long)
    data class ArtistPage(val artist: ArtistInfo, val songs: List<Track>, val albums: List<AlbumInfo>,
        val more: Boolean, val moreAlbums: Boolean)

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

    private fun postMusicu(body: kotlinx.serialization.json.JsonObject, cookie: String,
        userAgent: String = UA): kotlinx.serialization.json.JsonObject? =
        runCatching {
            val connection = java.net.URL("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", userAgent)
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

    /** Account writes require QQ's native Android comm. Public catalogue reads keep the web comm. */
    internal fun musicuRequestBody(module: String, method: String, param: JsonObject,
        cookie: String, uin: String): JsonObject {
        val cookieMap = cookie.split(';').map(String::trim).mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val authst = cookieMap["qqmusic_key"] ?: cookieMap["qm_keyst"].orEmpty()
        val resolvedUin = uin.ifBlank { "0" }
        if (authst.isNotBlank() && resolvedUin != "0") {
            return QqNativeAuth.musicuBody(
                module, method, param,
                credential = QqMusicCredential(
                    uin = resolvedUin,
                    key = authst,
                    loginType = (cookieMap["tmeLoginType"]?.toIntOrNull() ?: QqAuth.loginType(cookieMap))
                        .takeIf { it > 0 } ?: 6,
                    encryptUin = cookieMap["encrypt_uin"].orEmpty(),
                ),
            )
        }
        var gtkHash = 5381L
        for (char in authst) gtkHash = (gtkHash + (gtkHash shl 5) + char.code) and 0x7fffffff
        return buildJsonObject {
            putJsonObject("comm") {
                put("ct", 24)
                put("cv", 4747474)
                put("platform", "yqq.json")
                put("format", "json")
                put("uin", resolvedUin)
                put("g_tk", gtkHash)
                if (authst.isNotBlank()) {
                    put("qq", resolvedUin)
                    put("authst", authst)
                    put("tmeLoginType", QqAuth.loginType(cookieMap))
                }
            }
            putJsonObject("req_0") {
                put("module", module)
                put("method", method)
                put("param", param)
            }
        }
    }

    /** 当前 musicu RPC 的统一入口。保留业务码，写操作需要区分成功与登录失效。 */
    internal fun musicu(module: String, method: String, param: JsonObject,
        cookie: String = "", uin: String = "0"): MusicuResult {
        val cookieMap = cookie.split(';').map(String::trim).mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val authst = cookieMap["qqmusic_key"] ?: cookieMap["qm_keyst"].orEmpty()
        val authenticated = authst.isNotBlank() && uin.ifBlank { "0" } != "0"
        val body = musicuRequestBody(module, method, param, cookie, uin)
        val root = postMusicu(body, cookie,
            if (authenticated) QqNativeAuth.CLIENT_UA else UA) ?: return MusicuResult(null, null)
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

    /** Folia's Lite anonymous client is required by public album/singer search. */
    private fun liteMusicuData(module: String, method: String, param: JsonObject): JsonObject? = runCatching {
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 11)
                put("cv", "1003006")
                put("v", "1003006")
                put("tmeAppID", "qqmusiclight")
                put("nettype", "NETWORK_WIFI")
                put("udid", "0")
                put("uid", "0")
            }
            putJsonObject("req_0") {
                put("module", module)
                put("method", method)
                put("param", param)
            }
        }
        val root = postMusicu(body, "tmeLoginType=-1", "okhttp/3.14.9") ?: return null
        if (root["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        val request = root["req_0"]?.jsonObject ?: return null
        if (request["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        request["data"]?.jsonObject
    }.getOrNull()

    /** 诊断：把几个关键接口的返回码/条目数打出来，方便判断账号态是否可用 */
    fun probe(cookies: Map<String, String>, uin: String): String {
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val folders = ownedPlaylists(uin, cookie)
        val liked = folders.firstOrNull(::isLikedFolder)
        val likedTracks = liked?.let { playlistDetail(it.id, cookie, uin)?.tracks?.size }
        val vip = runCatching {
            musicuData("VipLogin.VipLoginInter", "vip_login_base", buildJsonObject {}, cookie, uin)
        }.getOrNull()
        val vipLevel = when {
            vip == null -> "未能确认"
            (vip["superVip"]?.jsonPrimitive?.longOrNull ?: 0) > 0 -> "超级会员"
            (vip["greenVip"]?.jsonPrimitive?.longOrNull ?: 0) > 0 -> "绿钻会员"
            else -> vip["vipLevel"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "未检测到会员"
        }
        return listOf(
            "登录类型：${if (QqAuth.loginType(cookies) == 2) "QQ 账号" else "微信账号"}",
            "账号权限：$vipLevel",
            "我的歌单：${folders.count { !isLikedFolder(it) }} 个",
            "我喜欢：${likedTracks ?: 0} 首",
        ).joinToString("\n")
    }

    /** QQ 音乐“音乐基因/听歌报告”，公开页使用脱敏加密账号也能读取。 */
    fun listeningReport(cookies: Map<String, String>, uin: String): ListeningReport? = runCatching {
        if (uin.isBlank()) return null
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        // qq-music-api reads the signed-in user's report with no business parameters. Forwarding
        // VisitAccount or another user's encrypted id is rejected or returns the public shell.
        val data = musicuData(
            "music.recommend.UserProfileSettingSvr",
            "GetProfileReport",
            buildJsonObject {},
            cookie,
            uin,
        ) ?: return null
        fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        fun JsonElement.visitObjects(block: (JsonObject) -> Unit) {
            when (this) {
                is JsonObject -> {
                    block(this)
                    values.forEach { it.visitObjects(block) }
                }
                is JsonArray -> forEach { it.visitObjects(block) }
                else -> Unit
            }
        }

        val objects = mutableListOf<JsonObject>()
        data.visitObjects { objects += it }
        fun firstText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            objects.firstNotNullOfOrNull { it.text(key) }
        }.orEmpty()
        fun firstObject(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { key ->
            objects.firstNotNullOfOrNull { it[key] as? JsonObject }
        }

        val user = firstObject("UserInfoCard", "UserInfo", "AccountInfo")
        val monthRows = objects.asSequence().flatMap { objectValue ->
            val arrays = objectValue.values.filterIsInstance<JsonArray>()
            arrays.asSequence().flatMap { it.asSequence() }.mapNotNull { it as? JsonObject }
        }.mapNotNull { row ->
            val month = listOf("Month", "MonthNo", "month", "month_no").firstNotNullOfOrNull { row.long(it) }
                ?.takeIf { it in 1L..12L }?.toInt() ?: return@mapNotNull null
            val count = listOf("Num", "Count", "count", "ListenNum", "play_num")
                .firstNotNullOfOrNull { row.long(it) } ?: 0L
            ListeningMonth(month, count)
        }.distinctBy { it.month }.sortedBy { it.month }.toList()

        fun cardFrom(base: JsonObject?, fallbackKey: String): ListeningCard? {
            if (base == null) return null
            val value = base["TypeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (value.isBlank()) return null
            return ListeningCard(
                key = base.text("KeyWord") ?: fallbackKey,
                title = base["KeyWord"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                value = value,
                summary = base["Slogan"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                image = base["Pic"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("http://", "https://"),
            )
        }
        val cards = objects.mapNotNull { objectValue ->
            cardFrom(objectValue["Base"] as? JsonObject, objectValue.text("Type") ?: "profile")
        }.distinctBy { it.value to it.title }.take(9)
        val description = firstText("Description", "MainDescription", "ReportDescription")
        if (monthRows.isEmpty() && cards.isEmpty()) return null
        ListeningReport(
            nickname = user?.text("NickName") ?: firstText("NickName", "Nickname", "nick"),
            avatar = (user?.text("HeadUrl") ?: firstText("HeadUrl", "Avatar", "avatar"))
                .replace("http://", "https://"),
            signature = user?.text("Signature") ?: firstText("Signature", "signature"),
            months = monthRows,
            cards = cards,
            description = description,
        )
    }.getOrNull()

    /** 全站收藏数，用于播放页红心旁的数字；歌曲能否播放仍由实际取流结果决定。 */
    fun songFavoriteCount(songId: Long, cookies: Map<String, String>, uin: String): Long? = runCatching {
        if (songId <= 0) return null
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.musicasset.SongFavRead",
            "GetSongFansNumberById",
            buildJsonObject { putJsonArray("v_songId") { add(songId) } },
            cookie,
            uin.ifBlank { "0" },
        ) ?: return null
        val numbers = data["m_numbers"]?.jsonObject
        val display = data["m_show"]?.jsonObject
        numbers?.get(songId.toString())?.jsonPrimitive?.longOrNull
            ?: numbers?.get(songId.toString())?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: display?.get(songId.toString())?.jsonPrimitive?.contentOrNull
                ?.filter(Char::isDigit)?.toLongOrNull()
    }.getOrNull()

    /** 登录账号的今日 30 首，与 QQ 音乐 App 每日推荐使用同一类目。 */
    fun dailyRecommendations(cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.srfDissInfo.aiDissInfo",
            "uniform_get_Dissinfo",
            buildJsonObject {
                put("dirid", 202)
                put("optype", 3)
                put("tag", 1)
                put("userinfo", 0)
                put("pic_dpi", 300)
            },
            cookie,
            uin,
        ) ?: return emptyList()
        data["songlist"]?.jsonArray.orEmpty().mapNotNull(::trackOf)
    }.getOrDefault(emptyList())

    /** Folia keyword search. Album and singer entries keep the QQ mid used by detail routes. */
    fun searchCatalog(query: String, albums: Boolean, page: Int = 1, limit: Int = 20): List<CatalogItem> = runCatching {
        val keyword = query.trim().take(60)
        if (keyword.isBlank()) return emptyList()
        val type = if (albums) 2 else 1
        val data = liteMusicuData(
            "music.search.SearchCgiService",
            "DoSearchForQQMusicLite",
            buildJsonObject {
                val random = kotlin.random.Random
                put("search_id", buildString {
                    append(random.nextInt(1, 10))
                    repeat(17) { append(random.nextInt(0, 10)) }
                })
                put("remoteplace", "search.android.keyboard")
                put("query", keyword)
                put("search_type", type)
                put("num_per_page", limit.coerceIn(1, 20))
                put("page_num", page.coerceAtLeast(1))
                put("highlight", 0)
                put("nqc_flag", 0)
                put("page_id", 1)
                put("grp", 1)
            },
        ) ?: return emptyList()
        searchCatalogItems(data, albums)
    }.getOrDefault(emptyList())

    internal fun searchCatalogItems(data: JsonObject, albums: Boolean): List<CatalogItem> {
        val body = data["body"]?.jsonObject ?: return emptyList()
        val rows = body[if (albums) "item_album" else "singer"]?.jsonArray.orEmpty()
        return rows.mapNotNull { row ->
            val item = row.jsonObject
            val mid = listOf("mid", "album_mid", "albumMid", "albummid", "singer_mid", "singerMid", "singerMID")
                .firstNotNullOfOrNull { item[it]?.jsonPrimitive?.contentOrNull }
                ?.takeIf { it.matches(Regex("[0-9A-Za-z]{1,64}")) } ?: return@mapNotNull null
            val id = listOf("id", "albumID", "album_id", "singer_id", "singerID")
                .firstNotNullOfOrNull { item[it]?.jsonPrimitive?.longOrNull }
                ?: stableId(mid)
            val name = listOf("name", "title", "albumName", "singerName")
                .firstNotNullOfOrNull { item[it]?.jsonPrimitive?.contentOrNull }
                .orEmpty().ifBlank { if (albums) "QQ 专辑" else "QQ 歌手" }
            val cover = listOf("pic", "cover", "albumPic", "avatar", "singerPic")
                .firstNotNullOfOrNull { item[it]?.jsonPrimitive?.contentOrNull }
                .orEmpty().replace("http://", "https://")
            val subtitle = item["subtitle"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: if (albums) artistsOf(item) else
                listOf("country", "otherName", "alias").firstNotNullOfOrNull {
                    item[it]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
            CatalogItem(mid, id, name, cover, subtitle)
        }.distinctBy(CatalogItem::key)
    }

    fun playlistCategories(): List<CategoryGroup> = runCatching {
        val data = musicuData("music.web_category_svr", "get_hot_category",
            buildJsonObject { put("qq", "") }) ?: return emptyList()
        playlistCategoryGroups(data)
    }.getOrDefault(emptyList())

    internal fun playlistCategoryGroups(data: JsonObject): List<CategoryGroup> =
        data["category"]?.jsonArray.orEmpty().mapNotNull { row ->
            val group = row.jsonObject
            val id = group["group_id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val categories = group["items"]?.jsonArray.orEmpty().mapNotNull { itemRow ->
                val item = itemRow.jsonObject
                val categoryId = item["item_id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                Category(categoryId, item["item_name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.filter { it.name.isNotBlank() }
            CategoryGroup(id, group["group_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), categories)
        }.filter { it.categories.isNotEmpty() }

    /** Folia's public QQ chart catalog. Discovery cards and detail pages read anonymously. */
    fun chartGroups(): List<ChartGroup> = runCatching {
        val data = musicuData("musicToplist.ToplistInfoServer", "GetAll", buildJsonObject {})
            ?: return emptyList()
        chartGroups(data)
    }.getOrDefault(emptyList())

    internal fun chartGroups(data: JsonObject): List<ChartGroup> =
        data["group"]?.jsonArray.orEmpty().mapNotNull { row ->
            val group = row.jsonObject
            val groupId = group["groupId"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            val charts = group["toplist"]?.jsonArray.orEmpty().mapNotNull(::chartOf)
            if (charts.isEmpty()) null else ChartGroup(
                groupId,
                group["groupName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                charts,
            )
        }

    private fun chartOf(row: JsonElement): Chart? {
        val item = row.jsonObject
        val id = item["topId"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val preview = item["song"]?.jsonArray.orEmpty().mapNotNull { previewRow ->
            val preview = previewRow.jsonObject
            val rank = preview["rank"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                ?: return@mapNotNull null
            ChartPreview(
                rank = rank,
                title = preview["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = preview["singerName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return Chart(
            id = id,
            title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 音乐榜单" },
            intro = item["intro"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cover = listOf("headPicUrl", "frontPicUrl", "musichallPicUrl").firstNotNullOfOrNull {
                item[it]?.jsonPrimitive?.contentOrNull
            }.orEmpty().replace("http://", "https://"),
            period = item["period"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            total = item["totalNum"]?.jsonPrimitive?.intOrNull ?: 0,
            listenCount = item["listenNum"]?.jsonPrimitive?.longOrNull ?: 0L,
            preview = preview,
        )
    }

    fun chart(topId: Long, period: String = ""): ChartDetail? = runCatching {
        if (topId <= 0) return null
        val safePeriod = period.takeIf { it.matches(Regex("[0-9_-]{0,20}")) }.orEmpty()
        val data = musicuData(
            "musicToplist.ToplistInfoServer",
            "GetDetail",
            buildJsonObject {
                put("topid", topId)
                put("offset", 0)
                put("num", 100)
                put("period", safePeriod)
            },
        ) ?: return null
        chartDetail(data, topId)
    }.getOrNull()

    internal fun chartDetail(data: JsonObject, fallbackId: Long = 0): ChartDetail? {
        val info = data["data"]?.jsonObject ?: return null
        val songs = data["songInfoList"]?.jsonArray.orEmpty().mapNotNull(::trackOf).distinctBy(Track::mid)
        if (songs.isEmpty()) return null
        return ChartDetail(
            title = info["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { if (fallbackId > 0) "QQ 音乐榜单" else "QQ 排行榜" },
            intro = info["intro"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cover = listOf("headPicUrl", "frontPicUrl", "musichallPicUrl").firstNotNullOfOrNull {
                info[it]?.jsonPrimitive?.contentOrNull
            }.orEmpty().replace("http://", "https://").ifBlank { songs.first().cover },
            period = info["period"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            total = info["totalNum"]?.jsonPrimitive?.intOrNull ?: songs.size,
            songs = songs,
        )
    }

    fun categoryPlaylists(categoryId: Long, page: Int = 0, size: Int = 20):
        Pair<List<PlaylistPreview>, Boolean> = runCatching {
        if (categoryId <= 0) return emptyList<PlaylistPreview>() to false
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 60)
        val data = musicuData(
            "music.playlist.PlayListCategory",
            "get_category_content",
            buildJsonObject {
                put("caller", "0"); put("category_id", categoryId)
                put("page", safePage); put("size", safeSize); put("use_page", 1)
            },
        ) ?: return emptyList<PlaylistPreview>() to false
        categoryPlaylistPreviews(data, safePage, safeSize)
    }.getOrDefault(emptyList<PlaylistPreview>() to false)

    internal fun categoryPlaylistPreviews(data: JsonObject, page: Int, size: Int):
        Pair<List<PlaylistPreview>, Boolean> {
        if (data["retcode"]?.jsonPrimitive?.intOrNull != 0) return emptyList<PlaylistPreview>() to false
        val content = data["content"]?.jsonObject ?: return emptyList<PlaylistPreview>() to false
        val rows = content["v_item"]?.jsonArray.orEmpty()
        val playlists = rows.mapNotNull { row -> categoryPlaylist(row.jsonObject["basic"]?.jsonObject) }
        val total = content["total_cnt"]?.jsonPrimitive?.intOrNull ?: playlists.size
        return playlists to (page + 1L < (total + size - 1L) / size && rows.isNotEmpty())
    }

    private fun categoryPlaylist(item: JsonObject?): PlaylistPreview? {
        if (item == null) return null
        val id = item["tid"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val cover = item["cover"] as? JsonObject
        val creator = item["creator"] as? JsonObject
        return PlaylistPreview(
            id = id,
            title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 音乐歌单" },
            description = item["desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            cover = listOf("big_url", "medium_url", "small_url", "default_url").firstNotNullOfOrNull {
                cover?.get(it)?.jsonPrimitive?.contentOrNull
            }.orEmpty().replace("http://", "https://"),
            playCount = item["play_cnt"]?.jsonPrimitive?.longOrNull,
            creator = creator?.get("nick")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun newSongs(area: Int = 5): List<Track> = runCatching {
        val data = musicuData("newsong.NewSongServer", "get_new_song_info",
            buildJsonObject { put("type", area.coerceAtLeast(1)) }) ?: return emptyList()
        data["songlist"]?.jsonArray.orEmpty().mapNotNull(::trackOf).distinctBy(Track::mid)
    }.getOrDefault(emptyList())

    fun album(mid: String, cookie: String = "", uin: String = "0"): AlbumInfo? = runCatching {
        if (!mid.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
        val data = musicuData(
            "music.musichallAlbum.AlbumSongList",
            "GetAlbumSongList",
            buildJsonObject {
                put("albumMid", mid)
                put("albumID", 0)
                put("begin", 0)
                put("num", 300)
                put("order", 2)
            },
            cookie,
            uin,
        ) ?: return null
        val songs = data["songList"]?.jsonArray.orEmpty().mapNotNull { row ->
            row.jsonObject["songInfo"]?.let(::trackOf) ?: trackOf(row)
        }.distinctBy(Track::mid)
        if (songs.isEmpty()) return null
        val first = songs.first()
        AlbumInfo(
            mid = mid,
            id = first.albumId ?: stableId(mid),
            name = first.album.ifBlank { "QQ 专辑" },
            cover = first.cover,
            artists = first.artists,
            description = "QQ 音乐专辑 · ${songs.size} 首",
            publishTime = 0,
            songs = songs,
        )
    }.getOrNull()

    fun artist(mid: String, offset: Int = 0, cookie: String = "", uin: String = "0"): ArtistPage? = runCatching {
        if (!mid.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
        val detail = musicuData(
            "music.musichallSinger.SingerInfoInter",
            "GetSingerDetail",
            buildJsonObject {
                putJsonArray("singer_mids") { add(mid) }
                put("pic", 1); put("group_singer", 1); put("wiki_singer", 1); put("ex_singer", 1)
            },
            cookie,
            uin,
        )
        val info = detail?.get("singer_list")?.jsonArray?.firstOrNull()?.jsonObject
        val basic = info?.get("basic_info")?.jsonObject
        val extra = info?.get("ex_info")?.jsonObject
        val pic = info?.get("pic")?.jsonObject
        val name = basic?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 歌手" }
        val singerId = basic?.get("singer_id")?.jsonPrimitive?.longOrNull ?: stableId(mid)
        val cover = listOf("pic", "big_black", "big_white").firstNotNullOfOrNull {
            pic?.get(it)?.jsonPrimitive?.contentOrNull
        }.orEmpty().ifBlank {
            "https://y.gtimg.cn/music/photo_new/T001R300x300M000${mid}_11.jpg"
        }.replace("http://", "https://")
        val songData = musicuData(
            "musichall.song_list_server",
            "GetSingerSongList",
            buildJsonObject {
                put("singerMid", mid); put("order", 1); put("number", 30); put("begin", offset.coerceAtLeast(0))
            },
            cookie,
            uin,
        )
        val songs = songData?.get("songList")?.jsonArray.orEmpty().mapNotNull { row ->
            row.jsonObject["songInfo"]?.let(::trackOf) ?: trackOf(row)
        }.distinctBy(Track::mid)
        val songTotal = songData?.get("totalNum")?.jsonPrimitive?.intOrNull ?: songs.size
        val albums = artistAlbums(mid, 0, cookie, uin).first
        val albumTotal = artistAlbumsTotal(mid, cookie, uin) ?: albums.size
        val homepage = runCatching {
            musicu(
                "music.UnifiedHomepage.UnifiedHomepageSrv",
                "GetHomepageHeader",
                buildJsonObject { put("SingerMid", mid) },
                cookie,
                uin,
            ).takeIf { it.code in setOf(0, 10000) }?.data?.get("Info")?.jsonObject
        }.getOrNull()
        val fanValue = homepage?.get("FansNum")?.jsonObject?.get("Num")?.jsonPrimitive
        val fans = fanValue?.longOrNull ?: fanValue?.contentOrNull?.toLongOrNull() ?: 0L
        ArtistPage(
            artist = ArtistInfo(mid, singerId, name, cover,
                listOfNotNull(extra?.get("desc")?.jsonPrimitive?.contentOrNull,
                    info?.get("wiki")?.jsonPrimitive?.contentOrNull).firstOrNull().orEmpty(),
                songTotal, albumTotal, fans),
            songs = songs,
            albums = albums,
            more = offset + songs.size < songTotal && songs.isNotEmpty(),
            moreAlbums = albums.size < albumTotal && albums.isNotEmpty(),
        )
    }.getOrNull()

    fun artistAlbums(mid: String, offset: Int = 0, cookie: String = "", uin: String = "0"):
        Pair<List<AlbumInfo>, Boolean> = runCatching {
        if (!mid.matches(Regex("[0-9A-Za-z]{1,64}"))) return emptyList<AlbumInfo>() to false
        val data = musicuData(
            "music.musichallAlbum.AlbumListServer",
            "GetAlbumList",
            buildJsonObject {
                put("sort", 5); put("singermid", mid)
                put("begin", offset.coerceAtLeast(0)); put("num", 30)
            },
            cookie,
            uin,
        ) ?: return emptyList<AlbumInfo>() to false
        val rows = (data["albumList"] ?: data["list"])?.jsonArray.orEmpty()
        val albums = rows.mapNotNull { row -> albumOf(row.jsonObject) }
        val total = data["total"]?.jsonPrimitive?.intOrNull ?: albums.size
        albums to (offset + rows.size < total && rows.isNotEmpty())
    }.getOrDefault(emptyList<AlbumInfo>() to false)

    private fun artistAlbumsTotal(mid: String, cookie: String, uin: String): Int? = runCatching {
        musicuData(
            "music.musichallAlbum.AlbumListServer",
            "GetAlbumList",
            buildJsonObject { put("sort", 5); put("singermid", mid); put("begin", 0); put("num", 1) },
            cookie,
            uin,
        )?.get("total")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    fun followedSingers(hostUin: String, cookie: String, uin: String): List<ArtistInfo> = runCatching {
        if (hostUin.isBlank() || cookie.isBlank()) return emptyList()
        val data = musicuData(
            "music.concern.RelationList",
            "GetFollowSingerList",
            buildJsonObject {
                put("HostUin", hostUin); put("MID", ""); put("From", 0); put("Size", 50); put("FromPos", "")
            },
            cookie,
            uin,
        ) ?: return emptyList()
        if ((data["LockFlag"]?.jsonPrimitive?.intOrNull ?: 0) != 0) return emptyList()
        data["List"]?.jsonArray.orEmpty().mapNotNull { row ->
            val item = row.jsonObject
            val mid = item["MID"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .takeIf { it.matches(Regex("[0-9A-Za-z]{1,64}")) } ?: return@mapNotNull null
            ArtistInfo(mid, stableId(mid), item["Name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 歌手" },
                item["AvatarUrl"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("http://", "https://"),
                item["Desc"]?.jsonPrimitive?.contentOrNull.orEmpty(), 0, 0,
                item["FanNum"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.distinctBy(ArtistInfo::mid)
    }.getOrDefault(emptyList())

    private fun artistsOf(item: JsonObject): String {
        val raw = item["singer"] ?: item["singers"] ?: item["artist"] ?: return ""
        return when (raw) {
            is JsonArray -> raw.mapNotNull { row ->
                row.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    ?: row.jsonPrimitive.contentOrNull
            }.joinToString(" / ")
            is JsonObject -> raw["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> raw.jsonPrimitive.contentOrNull.orEmpty()
        }
    }

    private fun albumOf(item: JsonObject): AlbumInfo? {
        val mid = listOf("album_mid", "albumMid", "mid").firstNotNullOfOrNull {
            item[it]?.jsonPrimitive?.contentOrNull
        }?.takeIf { it.matches(Regex("[0-9A-Za-z]{1,64}")) } ?: return null
        return AlbumInfo(
            mid = mid,
            id = listOf("album_id", "albumID", "id").firstNotNullOfOrNull {
                item[it]?.jsonPrimitive?.longOrNull
            } ?: stableId(mid),
            name = listOf("album_name", "albumName", "name", "title").firstNotNullOfOrNull {
                item[it]?.jsonPrimitive?.contentOrNull
            }.orEmpty().ifBlank { "QQ 专辑" },
            cover = listOf("album_pic", "pic", "cover").firstNotNullOfOrNull {
                item[it]?.jsonPrimitive?.contentOrNull
            }.orEmpty().ifBlank {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"
            }.replace("http://", "https://"),
            artists = artistsOf(item).ifBlank { item["singerName"]?.jsonPrimitive?.contentOrNull.orEmpty() },
            description = item["album_desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishTime = listOf("publish_date", "publishTime", "pubtime").firstNotNullOfOrNull {
                item[it]?.jsonPrimitive?.longOrNull
            }?.let { if (it in 1..99_999_999_999L) it * 1000 else it }
                ?: listOf("publishDate", "publish_date").firstNotNullOfOrNull {
                    item[it]?.jsonPrimitive?.contentOrNull
                }?.let { runCatching { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli() }.getOrNull() } ?: 0,
            songs = emptyList(),
        )
    }

    private fun stableId(value: String): Long =
        value.hashCode().toLong().let { if (it == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it) }

    /** 登录账号的猜你喜欢，单次通常返回约 5 首。 */
    fun guessYouLike(cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.radioProxy.MbTrackRadioSvr",
            "get_radio_track",
            buildJsonObject {
                put("id", 99)
                put("firstplay", 1)
                put("num", 15)
            },
            cookie,
            uin,
        ) ?: return emptyList()
        data["tracks"]?.jsonArray.orEmpty().mapNotNull(::trackOf)
    }.getOrDefault(emptyList())

    /** TrackRelationServer.GetRadarSong：QQ 的刷歌雷达，登录后与私人 FM 共用同一协议。 */
    fun radar(page: Int = 1, cookies: Map<String, String> = emptyMap(), uin: String = "0"): List<Track> = runCatching {
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.recommend.TrackRelationServer",
            "GetRadarSong",
            buildJsonObject {
                put("Page", page.coerceIn(1, 100))
                put("ReqType", 0)
                putJsonArray("FavSongs") {}
                putJsonArray("EntranceSongs") {}
            },
            cookie,
            uin.ifBlank { "0" },
        ) ?: return emptyList()
        radarTracks(data)
    }.getOrDefault(emptyList())

    /** qq-music-api 3.3.6 的私人 FM：必须登录，每页一批，不保存跨请求批次状态。 */
    fun personalFm(cookies: Map<String, String>, uin: String, page: Int = 1): List<Track> {
        if (uin.isBlank()) return emptyList()
        return radar(page, cookies, uin)
    }

    /** PlaylistSquare.GetRecommendFeed：推荐歌单可匿名读取，登录后带账号偏好。 */
    fun recommendedPlaylists(limit: Int = 20, cookies: Map<String, String> = emptyMap(),
        uin: String = "0"): List<Folder> = runCatching {
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.playlist.PlaylistSquare",
            "GetRecommendFeed",
            buildJsonObject { put("From", 0); put("Size", limit.coerceIn(1, 60)) },
            cookie,
            uin.ifBlank { "0" },
        ) ?: return emptyList()
        recommendedFolders(data, limit)
    }.getOrDefault(emptyList())

    internal fun radarTracks(data: JsonObject): List<Track> =
        data["VecSongs"]?.jsonArray.orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.get("Track")?.let(::trackOf)
        }.distinctBy(Track::mid)

    internal fun recommendedFolders(data: JsonObject, limit: Int): List<Folder> =
        data["List"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val basic = ((entry as? JsonObject)?.get("Playlist") as? JsonObject)
                ?.get("basic") as? JsonObject ?: return@mapNotNull null
            val id = basic["tid"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val cover = basic["cover"] as? JsonObject
            Folder(
                id = id,
                title = basic["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ 推荐歌单" },
                cover = listOf("default_url", "big_url", "medium_url", "small_url")
                    .firstNotNullOfOrNull { key -> cover?.get(key)?.jsonPrimitive?.contentOrNull }
                    .orEmpty().replace("http://", "https://"),
                count = basic["song_cnt"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.distinctBy(Folder::id).take(limit.coerceIn(1, 60))

    /** AddDislike / CancelDislike. A successful Retcode is the only write acknowledgement. */
    fun setDisliked(songId: Long, disliked: Boolean, cookies: Map<String, String>, uin: String): Boolean = runCatching {
        if (songId <= 0 || uin.isBlank()) return false
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val songs = if (disliked) {
            buildJsonArray { add(buildJsonObject { put("ID", songId.toString()) }) }
        } else {
            val entry = dislikedEntry(songId, cookie, uin) ?: return true
            buildJsonArray { add(entry) }
        }
        val param = buildJsonObject {
            put("Songs", songs)
            if (!disliked) put("OnlyAdd", 1)
        }
        val result = musicu("music.feedback.FeedbackBlack", if (disliked) "AddDislike" else "CancelDislike",
            param, cookie, uin)
        result.code == 0 && result.data?.get("Retcode")?.jsonPrimitive?.intOrNull == 0
    }.getOrDefault(false)

    /** The read route exposes only the first page, matching qq-music-api's documented coverage. */
    fun isDisliked(songId: Long, cookies: Map<String, String>, uin: String): Boolean? = runCatching {
        if (songId <= 0 || uin.isBlank()) return null
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData("music.feedback.FeedbackBlack", "GetDislikeList", buildJsonObject {
            put("Cmd", 3); put("Page", 1); put("SongLastid", 0); put("SingersLastid", 0)
        }, cookie, uin) ?: return null
        if (data["Retcode"]?.jsonPrimitive?.intOrNull != 0) return null
        data["Songs"]?.jsonArray.orEmpty().any { it.jsonObject["ID"]?.jsonPrimitive?.contentOrNull == songId.toString() }
    }.getOrNull()

    private fun dislikedEntry(songId: Long, cookie: String, uin: String): JsonObject? = runCatching {
        val data = musicuData("music.feedback.FeedbackBlack", "GetDislikeList", buildJsonObject {
            put("Cmd", 3); put("Page", 1); put("SongLastid", 0); put("SingersLastid", 0)
        }, cookie, uin) ?: return null
        data["Songs"]?.jsonArray.orEmpty().map { it.jsonObject }
            .firstOrNull { it["ID"]?.jsonPrimitive?.contentOrNull == songId.toString() }
    }.getOrNull()

    /** 当前账号近一周的常听歌曲，参数与 Folia 的 ListenReportSvr.GetRank 一致。 */
    fun weeklyListening(cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.individuation.ListenReportSvr",
            "GetRank",
            buildJsonObject {
                put("type", 2)
                put("uin", "")
                put("version", "v2")
                put("token", "")
            },
            cookie,
            uin,
        ) ?: return emptyList()
        data["rank"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["track"]?.jsonObject?.let(::trackOf)
        }.distinctBy(Track::mid).take(30)
    }.getOrDefault(emptyList())

    /** Folia 已实测的云端最近播放，顺序保持 QQ 返回的最近收听顺序。 */
    internal fun recentTracks(data: JsonObject): List<Track> =
        data["data"]?.jsonObject?.get("songList")?.jsonArray.orEmpty()
            .mapNotNull { item -> item.jsonObject["track"]?.jsonObject?.let(::trackOf) }
            .distinctBy(Track::mid)

    fun recentlyPlayed(cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.musicasset.PlayRecentlyRead",
            "GetPlayRecentlyInfo",
            buildJsonObject {
                put("type", 2)
                put("updateTime", 0)
            },
            cookie,
            uin,
        ) ?: return emptyList()
        recentTracks(data)
    }.getOrDefault(emptyList())

    internal fun dailyHistoryDates(data: JsonObject): List<String> =
        data["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val row = item.jsonObject
            val year = row["year"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val month = row["month"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val day = row["day"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            runCatching { java.time.LocalDate.of(year, month, day).toString() }.getOrNull()
        }.distinct()

    internal fun dailyHistoryTracks(data: JsonObject): List<Track> =
        data["songlist"]?.jsonArray.orEmpty()
            .mapNotNull { item -> item.jsonObject["track"]?.jsonObject?.let(::trackOf) }
            .distinctBy(Track::mid)

    internal fun similarTracks(data: JsonObject): List<Track> =
        data["vecSongNew"]?.jsonArray.orEmpty().flatMap { group ->
            group.jsonObject["songs"]?.jsonArray.orEmpty().mapNotNull { row ->
                row.jsonObject["track"]?.jsonObject?.let(::trackOf)
            }
        }.distinctBy(Track::mid)

    /** Folia song-relations route. Anonymous QQ sessions return an empty list. */
    fun similarSongs(songId: Long, cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (songId <= 0 || uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.recommend.TrackRelationServer",
            "GetSimilarSongs",
            buildJsonObject { put("songid", songId) },
            cookie,
            uin,
        ) ?: return emptyList()
        similarTracks(data)
    }.getOrDefault(emptyList())

    /** Folia daily-history route: recent dates available for this QQ account. */
    fun dailyRecommendationDates(cookies: Map<String, String>, uin: String): List<String> = runCatching {
        if (uin.isBlank()) return emptyList()
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.srfDissInfo.aiHistory",
            "get_aiHistoryinfo",
            buildJsonObject {
                put("tid", 0)
                put("dirid", 202)
                putJsonObject("spec") {}
            },
            cookie,
            uin,
        ) ?: return emptyList()
        dailyHistoryDates(data)
    }.getOrDefault(emptyList())

    /** Folia daily-history route: songs that were recommended on one prior date. */
    fun dailyRecommendationSongs(date: String, cookies: Map<String, String>, uin: String): List<Track> = runCatching {
        if (uin.isBlank()) return emptyList()
        val local = java.time.LocalDate.parse(date)
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val data = musicuData(
            "music.srfDissInfo.aiHistory",
            "get_aiHistoryinfo",
            buildJsonObject {
                put("tid", 0)
                put("dirid", 202)
                putJsonObject("spec") {
                    put("year", local.year)
                    put("month", local.monthValue)
                    put("day", local.dayOfMonth)
                    put("bSet", true)
                    put("optime", 0)
                }
            },
            cookie,
            uin,
        ) ?: return emptyList()
        dailyHistoryTracks(data)
    }.getOrDefault(emptyList())

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
        val values = mids.filter { it.matches(Regex("[0-9A-Za-z]{1,64}")) }.distinct()
        if (values.isEmpty()) return emptyMap()
        val data = musicuData(
            "music.musicasset.SongFavRead",
            "IsSongFanByMid",
            buildJsonObject { put("v_songMid", buildJsonArray { values.forEach(::add) }) },
            cookie,
            uin,
        ) ?: return emptyMap()
        parseLikedMids(data, values)
    }.getOrDefault(emptyMap())

    internal fun parseLikedMids(data: JsonObject, mids: Collection<String>): Map<String, Boolean> {
        val fans = data["m_fan"]?.jsonObject ?: return emptyMap()
        fun JsonPrimitive.flag(): Boolean? = booleanOrNull ?: when (contentOrNull?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        return mids.distinct().mapNotNull { mid ->
            val value = fans[mid] ?: return@mapNotNull null
            val liked = when (value) {
                is JsonPrimitive -> value.flag()
                is JsonObject -> (value["v_fan"] as? JsonPrimitive)?.flag()
                else -> null
            }
            liked?.let { mid to it }
        }.toMap()
    }

    fun isLiked(mid: String, cookie: String, uin: String): Boolean? =
        likedMids(listOf(mid), cookie, uin)[mid]

    internal fun favoriteReadConfirmed(observed: Boolean?, expected: Boolean): Boolean =
        observed == expected

    internal fun favoriteWriteAccepted(data: JsonObject): Boolean {
        if (data["retCode"]?.jsonPrimitive?.intOrNull != 0) return false
        val result = data["result"]?.jsonObject ?: return false
        val dirId = result["dirId"]?.jsonPrimitive?.intOrNull
        val tid = result["tid"]?.jsonPrimitive?.longOrNull
        return (dirId == 201 && tid != null && tid > 0) ||
            (dirId == 0 && tid == 0L)
    }

    /** 数字 songId 写入「我喜欢」，写入后按 songmid 读取确认。 */
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
                // Folia's verified write uses only dirId=201. Passing a playlist tid
                // here can redirect the write away from “我喜欢”.
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
        if (!favoriteWriteAccepted(data)) return false
        repeat(4) { attempt ->
            val confirmed = isLiked(mid, cookie, uin)
            if (favoriteReadConfirmed(confirmed, liked)) return true
            if (attempt < 3) Thread.sleep(250)
        }
        false
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
        val singerRows = o["singer"]?.jsonArray.orEmpty().map { it.jsonObject }
        val artistMids = singerRows.mapNotNull { it["mid"]?.jsonPrimitive?.contentOrNull }
            .filter { it.matches(Regex("[0-9A-Za-z]{1,64}")) }
        val artistIds = singerRows.mapNotNull { it["id"]?.jsonPrimitive?.longOrNull }
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
            albumMid = album?.get("mid")?.jsonPrimitive?.contentOrNull.orEmpty(),
            albumId = album?.get("id")?.jsonPrimitive?.longOrNull,
            artistMids = artistMids,
            artistIds = artistIds,
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
