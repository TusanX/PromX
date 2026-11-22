package io.github.currencortex.music.data.library

import io.github.currencortex.music.core.network.*
import io.github.currencortex.music.data.song.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Server data is authoritative. Like toggles are serialized and rollback on failure. */
class LibraryRepository(private val api: ApiClient, private val accountId: () -> Long,
    private val session: () -> RequestSession,
    private val sourceOf: () -> io.github.currencortex.music.data.song.MusicSource = {
        io.github.currencortex.music.data.song.MusicSource.NETEASE
    },
    private val biliSession: (() -> io.github.currencortex.music.core.alt.BiliSessionStore?)? = null,
    private val qqSession: (() -> io.github.currencortex.music.core.alt.QqSessionStore?)? = null) {
    val statuses = MutableStateFlow<Map<Long, SongStatus>>(emptyMap())
    val revision = MutableStateFlow(0L)
    private val mutationMutex = Mutex()
    private val reads = SessionReadCache(session, { revision.value })
    val hasCurrentMusicAccount get() = accountId() > 0 && session().token != null
    fun clearReads() = reads.clear()
    fun invalidate() { reads.clear(); revision.update { it + 1 } }
    fun clearSession() { reads.clear(); statuses.value = emptyMap(); revision.update { it + 1 } }
    private suspend inline fun <reified T> read(path: String, expected: RequestSession, query: Map<String, String> = emptyMap()): T =
        api.decode(api.request("GET", path, query, authenticated = true, expectedSession = expected))
    private suspend fun <T : Any> backgroundRead(key: String, fresh: Boolean = false, load: suspend (RequestSession) -> T): T =
        withContext(Dispatchers.Default) { reads.read(key, fresh, load) }
    private fun altSourceActive(): Boolean =
        sourceOf() != io.github.currencortex.music.data.song.MusicSource.NETEASE

    private fun mode(): io.github.currencortex.music.data.song.MusicSource = sourceOf()

    /** AltTrack → Song，供其它模式复用 */
    private fun trackSong(t: io.github.currencortex.music.core.alt.AltTrack): Song {
        val id = t.id.hashCode().toLong().let { if (it < 0) -it else it }
        return if (t.source == io.github.currencortex.music.core.alt.AltSource.BILIBILI) {
            Song(id = id, name = t.title, artists = t.artist + " · 哔哩哔哩", cover = t.cover,
                musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                externalIds = io.github.currencortex.music.data.song.SongExternalIds(bilibiliBvid = t.id))
        } else {
            Song(id = id, name = t.title, artists = t.artist + " · QQ音乐", cover = t.cover,
                musicSource = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
                externalIds = io.github.currencortex.music.data.song.SongExternalIds(
                    qqMusicId = t.id, qqMediaId = t.mediaId.takeIf(String::isNotBlank),
                    qqNumericId = t.extra.ifBlank { null }))
        }
    }

    private fun qqSong(track: io.github.currencortex.music.core.alt.QqLibrary.Track): Song =
        Song(
            id = track.mid.hashCode().toLong().let { if (it < 0) -it else it },
            name = track.name,
            artists = track.artists,
            album = track.album,
            cover = track.cover,
            durationMs = track.durationMs,
            musicSource = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            externalIds = io.github.currencortex.music.data.song.SongExternalIds(
                qqMusicId = track.mid,
                qqMediaId = track.mediaId.takeIf(String::isNotBlank),
                qqNumericId = track.numericId?.toString(),
            ),
        )

    suspend fun daily(fresh: Boolean = false): Daily = backgroundRead("daily", fresh) { expected ->
        // 模式不是网易云时，用该模式自己的公开榜单作推荐，绝不混用网易云数据
        when (sourceOf()) {
            io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            io.github.currencortex.music.data.song.MusicSource.BILIBILI -> {
                val modeNow = sourceOf()
                fun fetchDaily() = if (modeNow == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
                    io.github.currencortex.music.core.alt.AltRecommend.qqHot(24)
                } else {
                    val cookie = biliSession?.invoke()?.let { if (it.loggedIn) it.cookieHeader() else "" } ?: ""
                    io.github.currencortex.music.core.alt.AltRecommend.bilibiliRcmd(cookie)
                        .ifEmpty { io.github.currencortex.music.core.alt.AltRecommend.bilibiliHot() }
                }
                fun fetchForYou() = if (modeNow == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC)
                    io.github.currencortex.music.core.alt.AltRecommend.qqNew(24)
                else io.github.currencortex.music.core.alt.AltRecommend.bilibiliHot(24)
                var dailyTracks = withContext(Dispatchers.IO) { fetchDaily() }
                if (dailyTracks.isEmpty()) {
                    // 首屏网络未就绪时会拿空结果；重试一次，避免把空结果缓存住
                    delay(700)
                    dailyTracks = withContext(Dispatchers.IO) { fetchDaily() }
                }
                val discovery = withContext(Dispatchers.IO) { fetchForYou() }
                val songs = dailyTracks.map(::trackSong)
                val forYou = (discovery + dailyTracks).distinctBy { "${it.source}:${it.id}" }.take(24).map(::trackSong)
                return@backgroundRead Daily(songs, if (forYou.isEmpty()) songs else forYou, emptyList())
            }
            else -> Unit
        }
        val d = api.netease?.takeIf { api.nativeMediaEnabled || api.hasLocalNeteaseLogin || expected.token == null }
            ?.let { api.decode<DailyDto>(it.daily(expected.neteaseRevision)) } ?: read<DailyDto>("daily", expected)
        val daily = d.daily.map(SongDto::toDomain)
        val recommended = d.forYou.map(SongDto::toDomain).distinctBy { it.id }
        // Preserve FM/server order; fill a short batch from the already loaded daily list.
        // This also covers legacy servers that only provide three forYou songs.
        val forYou = if (recommended.size >= 12) recommended else
            (recommended + daily).distinctBy { it.id }.take(12)
        Daily(daily, forYou, d.artists)
    }
    suspend fun recent(fresh: Boolean = false): List<Song> {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke() ?: return emptyList()
            if (!bili.loggedIn) return emptyList()
            val items = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.history(bili.cookieHeader())
            }
            return items.map { item ->
                Song(
                    id = item.bvid.hashCode().toLong().let { if (it < 0) -it else it },
                    name = item.title,
                    artists = (item.owner.ifBlank { "哔哩哔哩" }) + " · 哔哩哔哩",
                    cover = item.cover,
                    durationMs = item.durationSec * 1000L,
                    musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                    externalIds = io.github.currencortex.music.data.song.SongExternalIds(bilibiliBvid = item.bvid),
                )
            }
        }
        // 非网易云模式：最近播放取本机记录里属于该模式的曲目
        val mode = sourceOf()
        if (mode != io.github.currencortex.music.data.song.MusicSource.NETEASE) return emptyList()
        if (accountId() == 0L) {
            if (!api.hasLocalNeteaseLogin) return emptyList()
            val native = api.netease ?: return emptyList()
            return runCatching {
                ApiJson.decodeFromJsonElement(SongListDto.serializer(), native.recent(50)).songs.map(SongDto::toDomain)
            }.getOrDefault(emptyList())
        }
        return backgroundRead("recent", fresh) {
        // Project play reports are already uploaded separately from NetEase scrobbles.
        // A project login must read the same cross-device history that the web client uses.
        (if (it.token != null && !api.nativeMediaEnabled) read<SongListDto>("plays/recent", it, mapOf("limit" to "50"))
        else api.netease?.let { native -> api.decode<SongListDto>(native.recent(expectedRevision = it.neteaseRevision)) }
            ?: read<SongListDto>("plays/recent", it, mapOf("limit" to "50"))).songs.map(SongDto::toDomain)
    }
    }
    /**
     * 没有 Prom 账号时，音乐库改用设备上的网易云登录态：喜欢列表走原生 ncmbind/likelist，
     * 歌单走原生 playlist 接口；两者都不可用就返回空列表（页面显示空态，而不是报错）。
     */
    suspend fun likedSongs(fresh: Boolean = false): List<Song> {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke() ?: return emptyList()
            if (!qq.loggedIn) return emptyList()
            val cookie = qq.cookieHeader(); val uin = qq.uin
            val tid = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.likedFolderId(uin, cookie)
            }
            val tracks = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.playlistDetail(tid, cookie, uin)?.tracks.orEmpty()
            }
            return tracks.map(::qqSong)
        }
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            // B站：读名为「我喜欢」的收藏夹（没有就返回空，写入时自动新建）
            val bili = biliSession?.invoke() ?: return emptyList()
            if (!bili.loggedIn) return emptyList()
            return withContext(Dispatchers.IO) {
                val cookie = bili.cookieHeader()
                val folders = io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(cookie, bili.profile!!.mid)
                val folder = folders.firstOrNull { it.title.contains("我喜欢") } ?: folders.firstOrNull()
                if (folder == null) return@withContext emptyList()
                // 收藏夹分页：一次 20 条，最多取 5 页，保持与常见歌单入口一致。
                (1..5).flatMap { page ->
                    val items = io.github.currencortex.music.core.alt.BiliAuth.favoriteItems(cookie, folder.id, page)
                    if (items.isEmpty()) emptyList() else items
                }
            }.map { item ->
                Song(id = item.bvid.hashCode().toLong().let { if (it < 0) -it else it },
                    name = item.title, artists = item.author + " · 哔哩哔哩", cover = item.cover,
                    durationMs = item.durationMs,
                    musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                    externalIds = io.github.currencortex.music.data.song.SongExternalIds(bilibiliBvid = item.bvid))
            }
        }
        val account = accountId()
        if (account == 0L) {
            if (!api.hasLocalNeteaseLogin) return emptyList()
            val native = api.netease ?: return emptyList()
            return runCatching {
                val likes = native.request("POST", "ncmbind/likelist", emptyMap(), null) as kotlinx.serialization.json.JsonObject
                val ids = (likes["ids"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
                    ?: emptyList()
                if (ids.isEmpty()) return@runCatching emptyList()
                ids.take(200).chunked(100).flatMap { chunk ->
                    val dto = native.request("GET", "ncm/songs/detail",
                        mapOf("ids" to chunk.joinToString(",")), null) as kotlinx.serialization.json.JsonObject
                    ApiJson.decodeFromJsonElement(SongListDto.serializer(), dto).songs.map(SongDto::toDomain)
                }
            }.getOrDefault(emptyList())
        }
        return backgroundRead("likes", fresh) { read<SongListDto>("likes/mine", it).songs.map(SongDto::toDomain) }
    }
    /** 没有 Prom 账号时，歌单直接读设备上的网易云账号（ncm/user/playlist）。 */
    suspend fun playlists(): List<Playlist> {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke() ?: return emptyList()
            if (!qq.loggedIn) return emptyList()
            val folders = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.folders(qq.uin, qq.cookieHeader())
            }
            return folders.map { folder ->
                Playlist(id = folder.id, name = folder.title, description = "", cover = folder.cover,
                    source = "qq", ownerId = 0L, count = folder.count, songs = emptyList(),
                    writable = folder.writable)
            }
        }
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke() ?: return emptyList()
            if (!bili.loggedIn) return emptyList()
            val folders = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(
                    bili.cookieHeader(), bili.profile!!.mid)
            }
            // 收藏夹自身 often 没有 cover：用第一条视频封面兜底（只补前 8 个、并发生效，避免 16 次串行请求）
            val fallbackCovers = withContext(Dispatchers.IO) {
                val targets = folders.filter { it.cover.isBlank() && it.count > 0 }.take(8)
                coroutineScope {
                    targets.map { f ->
                        async {
                            f.id to (io.github.currencortex.music.core.alt.BiliAuth
                                .favoriteItems(bili.cookieHeader(), f.id, 1, 1)
                                .firstOrNull()?.cover.orEmpty())
                        }
                    }.awaitAll().toMap()
                }
            }
            return folders.map { f ->
                val cover = f.cover.ifBlank { fallbackCovers[f.id].orEmpty() }
                Playlist(id = f.id, name = f.title, description = "", cover = cover, source = "bilibili",
                    ownerId = 0L, count = f.count, songs = emptyList())
            }
        }
        if (accountId() == 0L) {
            if (!api.hasLocalNeteaseLogin) return emptyList()
            val native = api.netease ?: return emptyList()
            val uid = native.sessions.state.value.profile?.uid ?: return emptyList()
            return runCatching {
                val dto = native.request("GET", "ncm/user/playlist", mapOf("uid" to uid.toString(), "limit" to "100"), null)
                ApiJson.decodeFromJsonElement(PlaylistsDto.serializer(), dto).playlists.map(PlaylistDto::domain)
            }.getOrDefault(emptyList())
        }
        return backgroundRead("playlists") { read<PlaylistsDto>("playlists", it).playlists.map(PlaylistDto::domain) }
    }
    suspend fun playlist(id: Long, fresh: Boolean = false): Playlist {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke() ?: throw ApiException(ErrorKind.NeteaseBindingRequired,
                userMessage = "请先在「我的 → QQ 音乐」登录")
            val cookie = qq.cookieHeader()
            val detail = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.playlistDetail(id, cookie, qq.uin)
            }
            val songs = detail?.tracks.orEmpty().map(::qqSong)
            return Playlist(id = id, name = detail?.title ?: "QQ 歌单", description = "",
                cover = detail?.cover.orEmpty().ifBlank { songs.firstOrNull()?.cover.orEmpty() },
                source = "qq", ownerId = 0L, count = detail?.count ?: songs.size, songs = songs,
                writable = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.QqLibrary.ownedFolder(
                        id, cookie, qq.uin) != null
                })
        }
        // B站：收藏夹 id 就是列表里的 media_id，内容用 fav/resource/list 拉
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()
            if (bili != null && bili.loggedIn) {
                val (title, items) = withContext(Dispatchers.IO) {
                    val folders = io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(
                        bili.cookieHeader(), bili.profile!!.mid)
                    val name = folders.firstOrNull { it.id == id }?.title.orEmpty()
                    name to (1..5).flatMap { page ->
                        io.github.currencortex.music.core.alt.BiliAuth.favoriteItems(bili.cookieHeader(), id, page, 20)
                    }.distinctBy { it.bvid }
                }
                val songs = items.map { item ->
                    Song(id = item.bvid.hashCode().toLong().let { if (it < 0) -it else it },
                        name = item.title, artists = item.author + " · 哔哩哔哩", cover = item.cover,
                        durationMs = item.durationMs,
                        musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                        externalIds = io.github.currencortex.music.data.song.SongExternalIds(bilibiliBvid = item.bvid))
                }
                return Playlist(id = id, name = title.ifBlank { "哔哩哔哩收藏夹" }, description = "B站 收藏夹",
                    cover = songs.firstOrNull()?.cover.orEmpty(), source = "bilibili",
                    ownerId = 0L, count = songs.size, songs = songs)
            }
            return Playlist(id = id, name = "", description = "", cover = "", source = "bilibili",
                ownerId = 0L, count = 0, songs = emptyList())
        }
        return backgroundRead("playlist/$id", fresh) { read<PlaylistDto>("playlists/$id", it).domain() }
    }
    suspend fun refreshStatus(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return
            val likedMids = runCatching {
                withContext(Dispatchers.IO) {
                    val folder = io.github.currencortex.music.core.alt.QqLibrary.likedFolderId(qq.uin, qq.cookieHeader())
                    io.github.currencortex.music.core.alt.QqLibrary.playlist(
                        folder, qq.cookieHeader(), qq.uin).map { it.mid }.toSet()
                }
            }.getOrNull() ?: return
            // Search/library song ids are stable hashes of the QQ mid.
            val hashIds = likedMids.map { mid -> mid.hashCode().toLong().let { if (it < 0) -it else it } }.toSet()
            statuses.update { old -> old + ids.associateWith { id ->
                old[id]?.takeIf { it.pending } ?: SongStatus(liked = id in hashIds)
            } }
            return
        }
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()?.takeIf { it.loggedIn } ?: return
            val likedBvids = runCatching {
                withContext(Dispatchers.IO) {
                    val cookie = bili.cookieHeader()
                    val folders = io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(cookie, bili.profile!!.mid)
                    val folder = folders.firstOrNull { it.title.contains("我喜欢") } ?: folders.firstOrNull() ?: return@withContext emptySet<String>()
                    (1..5).flatMap { page -> io.github.currencortex.music.core.alt.BiliAuth.favoriteItems(cookie, folder.id, page) }
                        .map { it.bvid }.toSet()
                }
            }.getOrNull() ?: return
            val hashIds = likedBvids.associateBy { bvid -> bvid.hashCode().toLong().let { if (it < 0) -it else it } }
            statuses.update { old -> old + ids.associateWith { id ->
                old[id]?.takeIf { it.pending } ?: SongStatus(liked = id in hashIds)
            } }
            return
        }
        if (accountId() == 0L) return
        val expected = session()
        ids.distinct().chunked(100).forEach { chunk ->
            if (expected != session()) return
            val d = api.get<SongStatusDto>("songs/status", mapOf("ids" to chunk.joinToString(",")), true)
            if (expected == session()) statuses.update { old -> old + chunk.associateWith { id ->
                old[id]?.takeIf { it.pending } ?: SongStatus(id in d.liked, id in d.faved)
            } }
        }
    }

    /** 用当前歌曲的 songmid 批量读 QQ 红心状态，避免每次重读整个「我喜欢」。 */
    suspend fun refreshSongStatuses(songs: List<Song>) {
        if (mode() != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            refreshStatus(songs.map { it.id })
            return
        }
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return
        val tracks = songs.filter { it.musicSource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC }
            .mapNotNull { song -> song.externalIds.qqMusicId?.let { it to song.id } }
            .distinctBy { it.second }
        if (tracks.isEmpty()) return
        val liked = withContext(Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqLibrary.likedMids(
                tracks.map { it.first }, qq.cookieHeader(), qq.uin)
        }
        if (liked.isEmpty()) return
        statuses.update { old -> old + tracks.associate { (mid, id) ->
            id to (old[id]?.takeIf { it.pending } ?: SongStatus(liked = liked[mid] == true))
        } }
    }
    suspend fun toggleLike(song: Song): AppResult<Unit> = changeLike(song, null)
    suspend fun setLiked(song: Song, liked: Boolean): AppResult<Unit> = changeLike(song, liked)
    private suspend fun changeLike(song: Song, desired: Boolean?): AppResult<Unit> = mutationMutex.withLock {
        // QQ 音乐：收藏 = 写入「我喜欢」歌单（dirid 201）
        if (song.musicSource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke() ?: return@withLock AppResult.Failure(ErrorKind.Unauthorized)
            val mid = song.externalIds.qqMusicId ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
            val numericId = song.externalIds.qqNumericId?.toLongOrNull()
                ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
            val target = desired ?: !(statuses.value[song.id]?.liked ?: false)
            return@withLock appResult {
                val ok = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.QqLibrary.setLiked(
                        numericId, mid, target, qq.cookies, qq.uin)
                }
                if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐收藏失败，请确认账号仍有效")
                statuses.update { current ->
                    val now = current[song.id] ?: SongStatus()
                    current + (song.id to now.copy(liked = target))
                }
            }
        }
        // B站模式：点赞 = 加入「我喜欢」收藏夹
        if (song.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()
            if (bili == null || !bili.loggedIn) return@withLock AppResult.Failure(ErrorKind.Unauthorized)
            val bvid = song.externalIds.bilibiliBvid
                ?: return@withLock AppResult.Failure(ErrorKind.NotFound).also {
                }
            return@withLock appResult {
                val cookie = bili.cookieHeader()
                // B站 这个接口要的是 bili_jct 原值（换算成 bkn 反而报 CSRF 校验失败）
                val csrf = bili.cookies["bili_jct"].orEmpty()
                // 必须切到 IO：这里是主线程协程，直接发网络请求会抛 NetworkOnMainThread
                val folders = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.BiliAuth.favoriteFolders(cookie, bili.profile!!.mid)
                }
                // 优先写「我喜欢」收藏夹；没有就新建一个；新建失败再退回默认收藏夹
                val folderId = folders.firstOrNull { it.title.contains("我喜欢") }?.id
                    ?: withContext(Dispatchers.IO) {
                        io.github.currencortex.music.core.alt.BiliAuth.createFolder(cookie, "我喜欢", csrf)
                    }
                    ?: folders.firstOrNull()?.id
                    ?: throw ApiException(ErrorKind.Server, userMessage = "B站 账号下没有可用收藏夹")
                // 服务端真实归属：本地状态可能过期，直接问一次收藏夹
                val aid = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.BiliPlayback.videoInfo(bvid, cookie)?.aid.orEmpty()
                }
                // 以「翻收藏夹内容」为准：collected/list 被风控时返回空，会永远判成“没收藏过”
                val inside = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.BiliAuth.isInFolder(cookie, folderId, bvid)
                }
                val add = desired ?: !inside
                val ok = withContext(Dispatchers.IO) {
                    if (add) io.github.currencortex.music.core.alt.BiliAuth.addToFolder(cookie, bvid, folderId, csrf)
                    else io.github.currencortex.music.core.alt.BiliAuth.removeFromFolder(cookie, bvid, folderId, csrf)
                }
                if (!ok) throw ApiException(ErrorKind.Server,
                    userMessage = if (add) "B站收藏失败" else "B站取消收藏失败")
                statuses.update { it + (song.id to SongStatus(liked = add)) }
                revision.update { it + 1 }
            }
        }
        if (accountId() == 0L) return@withLock AppResult.Failure(ErrorKind.Unauthorized)
        val expected = session()
        // Read before toggling: a default false must not invert an existing server true.
        val loaded = appResult { refreshStatus(listOf(song.id)) }
        if (loaded is AppResult.Failure) return@withLock loaded
        if (expected != session()) return@withLock AppResult.Failure(ErrorKind.Unauthorized)
        val before = statuses.value[song.id] ?: SongStatus()
        if (desired != null && before.liked == desired) return@withLock AppResult.Success(Unit)
        statuses.update { it + (song.id to before.copy(liked = !before.liked, pending = true)) }
        val result = try { appResult {
            api.request("POST", "likes/${song.id}", body = metadata(song), authenticated = true, expectedSession = expected)
        } } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (expected == session()) statuses.update { it + (song.id to before) }
            throw cancelled
        }
        if (expected == session()) {
            statuses.update { it + (song.id to if (result is AppResult.Success) (it[song.id] ?: before).copy(
                liked = (result.value as? JsonObject)?.get("on")?.jsonPrimitive?.booleanOrNull ?: !before.liked, pending = false) else before) }
            if (result is AppResult.Success) revision.update { it + 1 }
        }
        when (result) { is AppResult.Success -> AppResult.Success(Unit); is AppResult.Failure -> result }
    }
    suspend fun create(name: String, description: String) {
        require(name.trim().isNotEmpty())
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: throw ApiException(ErrorKind.Unauthorized)
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.createPlaylist(
                    name, qq.cookieHeader(), qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐歌单创建失败")
            revision.update { it + 1 }
            return
        }
        val expected = session()
        api.request("POST", "playlists", body = buildJsonObject { put("name", name.trim()); put("description", description) }, authenticated = true, expectedSession = expected)
        revision.update { it + 1 }
    }
    private suspend fun editable(id: Long, expected: RequestSession) {
        if (expected != session() || !playlist(id, fresh = true).editable(accountId()) || expected != session()) throw ApiException(ErrorKind.Forbidden)
    }
    suspend fun rename(id: Long, name: String) {
        require(name.trim().isNotEmpty())
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: throw ApiException(ErrorKind.Unauthorized)
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.renamePlaylist(
                    id, name, qq.cookieHeader(), qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐歌单重命名失败")
            revision.update { it + 1 }
            return
        }
        val expected = session(); editable(id, expected)
        api.request("PUT", "playlists/$id", body = buildJsonObject { put("name", name.trim()) }, authenticated = true, expectedSession = expected)
        revision.update { it + 1 }
    }
    suspend fun delete(id: Long) {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: throw ApiException(ErrorKind.Unauthorized)
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.deletePlaylist(
                    id, qq.cookieHeader(), qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐歌单删除失败")
            revision.update { it + 1 }
            return
        }
        val expected = session(); editable(id, expected); api.request("DELETE", "playlists/$id", authenticated = true, expectedSession = expected); revision.update { it + 1 }
    }
    suspend fun add(id: Long, songs: List<Song>) {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: throw ApiException(ErrorKind.Unauthorized)
            val ids = songs.mapNotNull { it.externalIds.qqNumericId?.toLongOrNull() }
            if (ids.size != songs.size || ids.isEmpty()) throw ApiException(ErrorKind.NotFound)
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.editPlaylistTracks(
                    id, ids, add = true, qq.cookieHeader(), qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "加入 QQ 音乐歌单失败")
            revision.update { it + 1 }
            return
        }
        val expected = session(); editable(id, expected)
        api.request("POST", "playlists/$id/tracks", body = buildJsonObject { put("songs", JsonArray(songs.map(::metadata))) }, authenticated = true, expectedSession = expected)
        revision.update { it + 1 }
    }
    suspend fun remove(id: Long, song: Song) {
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: throw ApiException(ErrorKind.Unauthorized)
            val songId = song.externalIds.qqNumericId?.toLongOrNull() ?: throw ApiException(ErrorKind.NotFound)
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.editPlaylistTracks(
                    id, listOf(songId), add = false, qq.cookieHeader(), qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "从 QQ 音乐歌单移出失败")
            revision.update { it + 1 }
            return
        }
        val expected = session(); editable(id, expected)
        api.request("DELETE", "playlists/$id/tracks", body = buildJsonObject { put("songs", JsonArray(listOf(metadata(song)))) }, authenticated = true, expectedSession = expected)
        revision.update { it + 1 }
    }
    suspend fun recordPlay(song: Song, expected: RequestSession = session()) {
        // Prom statistics must also be updated when the music library uses native Netease.
        if (accountId() != 0L && !song.video && !song.privateLibrary) {
            api.request("POST", "plays/${song.id}", body = metadata(song), authenticated = true, expectedSession = expected)
            revision.update { it + 1 }
        }
    }
    suspend fun listen(song: Song, milliseconds: Long, expected: RequestSession = session()) {
        if (accountId() != 0L && !song.video && !song.privateLibrary && milliseconds > 0) api.request("POST", "plays/${song.id}",
            body = buildJsonObject { put("ms", milliseconds) }, authenticated = true, expectedSession = expected)
    }
    suspend fun artist(id: Long, offset: Int = 0): Artist {
        val d = api.get<ArtistDto>("ncm/artist", mapOf("id" to "$id", "offset" to "$offset", "limit" to "100"))
        return Artist(id, d.name, d.pic.replace("http:", "https:"), d.alias, d.songs.map(SongDto::toDomain), d.more,
            d.albums.map(AlbumDto::domain), d.albumTotal, d.total, d.briefDesc, d.mvTotal)
    }
    suspend fun artistMvs(id: Long, offset: Int = 0): ArtistMvsDto =
        api.get<ArtistMvsDto>("ncm/artist/mv", mapOf("id" to "$id", "offset" to "$offset", "limit" to "20"))
    suspend fun artistProfile(id: Long): ArtistProfile = api.get("ncm/artist/profile", mapOf("id" to "$id"))
    suspend fun artistFans(id: Long): ArtistFans = api.get("ncm/artist/fans", mapOf("id" to "$id"))
    suspend fun artistOverview(id: Long): ArtistOverview {
        val d = api.get<ArtistOverviewDto>("ncm/artist/overview", mapOf("id" to "$id"))
        return ArtistOverview(d.songs.map(SongDto::toDomain).filter { it.id > 0 }.distinctBy { it.id },
            d.albums.map(AlbumDto::domain).filter { it.id > 0 }.distinctBy { it.id },
            d.artists.filter { it.id > 0 && it.id != id }.distinctBy { it.id }
                .map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.alias) },
            d.awardTitle, d.awardCount, d.awardImages)
    }
    suspend fun artistBiography(id: Long, fresh: Boolean = false): ArtistBiography = backgroundRead("artist-bio/$id", fresh) {
        api.get<ArtistBiography>("ncm/artist/desc", mapOf("id" to "$id")).also {
            if (it.code != 200) throw ApiException(if (it.code == 404) ErrorKind.NotFound else ErrorKind.Server, it.code)
        }
    }
    suspend fun artistAlbums(id: Long, offset: Int, order: String = "time"): Pair<List<Album>, Boolean> {
        require(order in setOf("time", "hot"))
        val d = api.get<ArtistAlbumsDto>("ncm/artist/albums", mapOf("id" to "$id", "offset" to "$offset", "limit" to "30", "order" to order))
        return d.albums.map(AlbumDto::domain) to d.more
    }
    suspend fun followedArtists(offset: Int = 0, limit: Int = 50): FollowedArtistsPage {
        val d = api.get<FollowedArtistsDto>("ncm/artist/followed", mapOf("offset" to "$offset", "limit" to "$limit"))
        return FollowedArtistsPage(d.artists.map {
            FollowedArtist(it.id, it.name, it.pic.replace("http:", "https:"), it.alias, it.total, it.albumTotal)
        }.filter { it.id > 0 }, d.more, d.total)
    }
    suspend fun album(id: Long) = api.get<AlbumDto>("ncm/album", mapOf("id" to "$id")).domain().let { album ->
        album.copy(id = id, songs = album.songs.map { it.copy(albumId = id) })
    }
    suspend fun catalog(query: String, albums: Boolean, offset: Int = 0): CatalogPage {
        val d = api.get<CatalogSearchDto>("ncm/search", mapOf("keywords" to query, "type" to if (albums) "album" else "artist", "limit" to "30", "offset" to "$offset"))
        return if (albums) CatalogPage(d.albums.map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.artist) }, d.hasMore.album, d.totals.album)
        else CatalogPage(d.artists.map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.alias) }, d.hasMore.artist, d.totals.artist)
    }
    suspend fun searchPlaylists(query: String, offset: Int = 0): CatalogPage {
        val d = api.get<CatalogSearchDto>("ncm/search", mapOf("keywords" to query, "type" to "playlist", "limit" to "30", "offset" to "$offset"))
        return CatalogPage(d.playlists.map { playlist ->
            CatalogEntry(playlist.id, playlist.name, playlist.pic.replace("http:", "https:"),
                listOfNotNull(playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                    playlist.creator.takeIf(String::isNotBlank)?.let { "创建者：$it" }).joinToString(" · "))
        }, d.hasMore.playlist, d.totals.playlist)
    }
    suspend fun mv(id: Long) = api.get<MvDetailDto>("ncm/mv/detail", mapOf("mvid" to "$id")).data
    suspend fun mvSource(id: Long, resolution: Int = 1080): String {
        require(resolution in listOf(240, 480, 720, 1080))
        val url = read<MvUrlDto>("ncm/mv/url", session(), mapOf("id" to "$id", "r" to "$resolution")).data.url
        return url?.toHttpUrlOrNull()?.toString() ?: throw ApiException(ErrorKind.NotFound)
    }
    companion object {
        fun metadata(song: Song) = buildJsonObject {
            put("ncm_id", song.id); put("name", song.name); put("artists", song.artists)
            put("album", song.album); put("pic", song.cover); put("duration", song.durationMs); put("mv", song.mv)
            put("artist_ids", JsonArray(song.artistIds.map(::JsonPrimitive)))
        }
    }
}
