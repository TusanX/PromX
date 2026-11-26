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

data class QqDailyHistory(val date: String, val songs: List<Song>)
data class LibraryChart(val title: String, val intro: String, val cover: String,
    val period: String, val total: Int, val songs: List<Song>)

/** Server data is authoritative. Like toggles are serialized and rollback on failure. */
class LibraryRepository(private val api: ApiClient, private val accountId: () -> Long,
    private val session: () -> RequestSession,
    private val sourceOf: () -> io.github.currencortex.music.data.song.MusicSource = {
        io.github.currencortex.music.data.song.MusicSource.NETEASE
    },
    private val biliSession: (() -> io.github.currencortex.music.core.alt.BiliSessionStore?)? = null,
    private val qqSession: (() -> io.github.currencortex.music.core.alt.QqSessionStore?)? = null) {
    val statuses = MutableStateFlow<Map<Long, SongStatus>>(emptyMap())
    private val likeStatusReconciliation = LikeStatusReconciliation()
    val revision = MutableStateFlow(0L)
    private val mutationMutex = Mutex()
    private val reads = SessionReadCache(session, { revision.value })
    @Volatile private var biliDailyBatch: Int = 0
    val hasCurrentMusicAccount get() = accountId() > 0 && session().token != null
    fun clearReads() {
        biliDailyBatch++
        reads.clear()
    }
    fun invalidate() { reads.clear(); revision.update { it + 1 } }
    fun clearSession() { reads.clear(); likeStatusReconciliation.reset(); statuses.value = emptyMap(); revision.update { it + 1 } }
    private fun applyRemoteStatuses(remote: Map<Long, Boolean>, observed: Map<Long, Long>) {
        if (remote.isEmpty()) return
        statuses.update { old ->
            val next = old.toMutableMap()
            remote.forEach { (id, remoteLiked) ->
                val existing = old[id]
                if (existing?.pending == true) return@forEach
                val observedVersion = observed[id] ?: return@forEach
                val liked = likeStatusReconciliation.resolve(id, remoteLiked, observedVersion) ?: return@forEach
                next[id] = existing?.copy(liked = liked) ?: SongStatus(liked = liked)
            }
            next
        }
    }
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
                durationMs = t.durationMs, video = true, viewCount = t.views, likeCount = t.likes,
                category = t.category,
                musicSource = io.github.currencortex.music.data.song.MusicSource.BILIBILI,
                externalIds = io.github.currencortex.music.data.song.SongExternalIds(bilibiliBvid = t.id))
        } else {
            Song(id = id, name = t.title, artists = t.artist + " · QQ音乐", cover = t.cover,
                musicSource = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
                externalIds = io.github.currencortex.music.data.song.SongExternalIds(
                    qqMusicId = t.id, qqMediaId = t.mediaId.takeIf(String::isNotBlank),
                    qqNumericId = t.extra.ifBlank { null }, qqMvVid = t.mvVid))
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
                qqMvVid = track.mvVid,
                qqAlbumMid = track.albumMid.takeIf(String::isNotBlank),
                qqArtistMids = track.artistMids,
            ),
            artistIds = track.artistIds,
            albumId = track.albumId ?: 0L,
        )

    suspend fun qqCatalog(query: String, albums: Boolean, offset: Int = 0): CatalogPage =
        withContext(Dispatchers.IO) {
            val page = offset / 20 + 1
            val entries = io.github.currencortex.music.core.alt.QqLibrary.searchCatalog(
                query, albums, page = page, limit = 20)
            CatalogPage(entries.map { entry ->
                CatalogEntry(entry.id, entry.name, entry.cover, entry.subtitle,
                    io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC, entry.key)
            }, entries.size == 20, offset + entries.size)
        }

    suspend fun qqAlbum(mid: String): Album = withContext(Dispatchers.IO) {
        val qq = qqSession?.invoke()
        val data = io.github.currencortex.music.core.alt.QqLibrary.album(
            mid, qq?.takeIf { it.loggedIn }?.cookieHeader().orEmpty(), qq?.uin?.ifBlank { "0" } ?: "0")
            ?: throw ApiException(ErrorKind.NotFound)
        Album(data.id, data.name, data.cover, data.artists, data.description, data.songs.map(::qqSong),
            data.publishTime, data.songs.size, source = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            remoteKey = data.mid)
    }

    suspend fun qqArtist(mid: String, offset: Int = 0): Artist = withContext(Dispatchers.IO) {
        val qq = qqSession?.invoke()
        val data = io.github.currencortex.music.core.alt.QqLibrary.artist(
            mid, offset, qq?.takeIf { it.loggedIn }?.cookieHeader().orEmpty(), qq?.uin?.ifBlank { "0" } ?: "0")
            ?: throw ApiException(ErrorKind.NotFound)
        Artist(data.artist.id, data.artist.name, data.artist.cover, data.artist.description,
            data.songs.map(::qqSong), data.more,
            data.albums.map { qqAlbumModel(it) }, data.artist.albumTotal,
            data.artist.songTotal, data.artist.description,
            source = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            remoteKey = data.artist.mid, fans = data.artist.fans)
    }

    suspend fun qqArtistAlbums(mid: String, offset: Int): Pair<List<Album>, Boolean> =
        withContext(Dispatchers.IO) {
            val qq = qqSession?.invoke()
            io.github.currencortex.music.core.alt.QqLibrary.artistAlbums(
                mid, offset, qq?.takeIf { it.loggedIn }?.cookieHeader().orEmpty(), qq?.uin?.ifBlank { "0" } ?: "0")
                .let { (albums, more) -> albums.map(::qqAlbumModel) to more }
        }

    suspend fun qqFollowedArtists(): FollowedArtistsPage = withContext(Dispatchers.IO) {
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
            ?: return@withContext FollowedArtistsPage(emptyList(), false, 0)
        val hostUin = listOf("encrypt_uin", "encryptUin", "p_uin", "ptui_loginuin", "luin")
            .firstNotNullOfOrNull { qq.cookies[it]?.takeIf(String::isNotBlank) } ?: qq.uin
        val artists = io.github.currencortex.music.core.alt.QqLibrary.followedSingers(
            hostUin, qq.cookieHeader(), qq.uin)
        FollowedArtistsPage(artists.map {
            FollowedArtist(it.id, it.name, it.cover, it.description, it.songTotal, it.albumTotal, it.mid)
        }, false, artists.size)
    }

    suspend fun qqNewSongs(area: Int = 5): List<Song> = withContext(Dispatchers.IO) {
        io.github.currencortex.music.core.alt.QqLibrary.newSongs(area).map(::qqSong)
    }

    suspend fun qqChart(id: Long, period: String): LibraryChart = withContext(Dispatchers.IO) {
        val detail = io.github.currencortex.music.core.alt.QqLibrary.chart(id, period)
            ?: throw ApiException(ErrorKind.NotFound)
        LibraryChart(detail.title, detail.intro, detail.cover, detail.period,
            detail.total, detail.songs.map(::qqSong))
    }

    private fun qqAlbumModel(data: io.github.currencortex.music.core.alt.QqLibrary.AlbumInfo) =
        Album(data.id, data.name, data.cover, data.artists, data.description, data.songs.map(::qqSong),
            data.publishTime, data.songs.size, source = io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
            remoteKey = data.mid)

    suspend fun daily(fresh: Boolean = false): Daily {
        val today = java.time.LocalDate.now()
        val modeNow = sourceOf()
        val biliIdentity = if (modeNow == io.github.currencortex.music.data.song.MusicSource.BILIBILI)
            biliSession?.invoke()?.profile?.mid?.toString().orEmpty() else ""
        val batch = if (fresh) ++biliDailyBatch else biliDailyBatch
        return backgroundRead("daily:${modeNow.name}:$today:bili:$biliIdentity", fresh) { expected ->
            when (modeNow) {
                io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC,
                io.github.currencortex.music.data.song.MusicSource.BILIBILI -> {
                    fun fetchDaily(): List<Song> = if (modeNow == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
                        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
                        val tracks = if (qq != null) io.github.currencortex.music.core.alt.QqLibrary.dailyRecommendations(
                            qq.cookies, qq.uin) else emptyList()
                        val radar = io.github.currencortex.music.core.alt.QqLibrary.radar(1,
                            qq?.cookies.orEmpty(), qq?.uin ?: "0")
                        if (tracks.isNotEmpty() || radar.isNotEmpty()) (tracks + radar).map(::qqSong)
                        else io.github.currencortex.music.core.alt.AltRecommend.qqHot(24).map(::trackSong)
                    } else {
                        val cookie = biliSession?.invoke()?.let { if (it.loggedIn) it.cookieHeader() else "" } ?: ""
                        io.github.currencortex.music.core.alt.AltRecommend.bilibiliRcmd(
                            cookie = cookie,
                            limit = 36,
                            seed = (today.toEpochDay() and Int.MAX_VALUE.toLong()).toInt(),
                            batch = batch,
                        ).ifEmpty { io.github.currencortex.music.core.alt.AltRecommend.bilibiliHot() }
                            .map(::trackSong)
                    }
                    fun fetchForYou(): List<Song> = if (modeNow == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
                        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
                        val personalFm = qq?.let { io.github.currencortex.music.core.alt.QqLibrary.personalFm(it.cookies, it.uin) }.orEmpty()
                        val guess = qq?.let { io.github.currencortex.music.core.alt.QqLibrary.guessYouLike(it.cookies, it.uin) }.orEmpty()
                        val radar = io.github.currencortex.music.core.alt.QqLibrary.radar(1,
                            qq?.cookies.orEmpty(), qq?.uin ?: "0")
                        val tracks = (personalFm + radar + guess).distinctBy { it.mid }
                        if (tracks.isNotEmpty()) tracks.map(::qqSong)
                        else io.github.currencortex.music.core.alt.AltRecommend.qqNew(24).map(::trackSong)
                    } else {
                        val cookie = biliSession?.invoke()?.let { if (it.loggedIn) it.cookieHeader() else "" } ?: ""
                        io.github.currencortex.music.core.alt.AltRecommend.bilibiliRcmd(
                            cookie = cookie,
                            limit = 24,
                            seed = (today.toEpochDay() and Int.MAX_VALUE.toLong()).toInt() + 7,
                            batch = batch + 1,
                        ).ifEmpty { io.github.currencortex.music.core.alt.AltRecommend.bilibiliHot(24) }
                            .map(::trackSong)
                    }
                    var dailySongs = withContext(Dispatchers.IO) { fetchDaily() }
                    if (dailySongs.isEmpty()) {
                        // Do not cache an empty first screen while the network is still starting.
                        delay(700)
                        dailySongs = withContext(Dispatchers.IO) { fetchDaily() }
                    }
                    val discovery = withContext(Dispatchers.IO) { fetchForYou() }
                    fun Song.identity() = "${musicSource}:${externalIds.qqMusicId ?: externalIds.bilibiliBvid ?: id}"
                    val songs = dailySongs.distinctBy { it.identity() }
                    val forYou = (discovery + dailySongs).distinctBy { it.identity() }.take(24)
                    return@backgroundRead Daily(songs, if (forYou.isEmpty()) songs else forYou, emptyList())
                }
                else -> Unit
            }
            val d = api.netease?.takeIf { api.nativeMediaEnabled || api.hasLocalNeteaseLogin || expected.token == null }
                ?.let { api.decode<DailyDto>(it.daily(expected.neteaseRevision)) } ?: read<DailyDto>("daily", expected)
            val daily = d.daily.map(SongDto::toDomain)
            val recommended = d.forYou.map(SongDto::toDomain).distinctBy { it.id }
            val forYou = if (recommended.size >= 12) recommended else
                (recommended + daily).distinctBy { it.id }.take(12)
            Daily(daily, forYou, d.artists)
        }
    }
    suspend fun recommendedPlaylists(fresh: Boolean = false): List<Playlist> {
        if (mode() != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) return emptyList()
        val qq = qqSession?.invoke()
        val cookies = qq?.takeIf { it.loggedIn }?.cookies.orEmpty()
        val uin = qq?.takeIf { it.loggedIn }?.uin ?: "0"
        return withContext(Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqLibrary.recommendedPlaylists(20, cookies, uin).map { folder ->
                Playlist(id = folder.id, name = folder.title, description = "QQ 音乐为你推荐", cover = folder.cover,
                    source = "qq-recommended", ownerId = 0L, count = folder.count, songs = emptyList(), writable = false)
            }
        }
    }

    /** Folia daily-history endpoint. The newest available prior date is enough for the QQ home. */
    suspend fun qqDailyHistory(): QqDailyHistory? {
        if (mode() != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) return null
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return null
        return withContext(Dispatchers.IO) {
            val dates = io.github.currencortex.music.core.alt.QqLibrary.dailyRecommendationDates(qq.cookies, qq.uin)
            val date = dates.firstOrNull() ?: return@withContext null
            val songs = io.github.currencortex.music.core.alt.QqLibrary.dailyRecommendationSongs(
                date, qq.cookies, qq.uin).map(::qqSong)
            songs.takeIf(List<Song>::isNotEmpty)?.let { QqDailyHistory(date, it) }
        }
    }

    suspend fun qqSimilarSongs(song: Song): List<Song> {
        if (song.musicSource != MusicSource.QQ_MUSIC) return emptyList()
        val numericId = song.externalIds.qqNumericId?.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqLibrary.similarSongs(
                numericId, qq.cookies, qq.uin).map(::qqSong)
        }
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
        if (mode() == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return emptyList()
            return withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.recentlyPlayed(qq.cookies, qq.uin)
                    .ifEmpty { io.github.currencortex.music.core.alt.QqLibrary.weeklyListening(qq.cookies, qq.uin) }
                    .map(::qqSong)
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
            // 哔哩哔哩：读名为「我喜欢」的收藏夹（没有就返回空，写入时自动新建）
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
        // 哔哩哔哩：收藏夹 id 就是列表里的 media_id，内容用 fav/resource/list 拉
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
                return Playlist(id = id, name = title.ifBlank { "哔哩哔哩收藏夹" }, description = "哔哩哔哩 收藏夹",
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
        val distinctIds = ids.distinct()
        val observed = likeStatusReconciliation.snapshot(distinctIds)
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
            applyRemoteStatuses(distinctIds.associateWith { it in hashIds }, observed)
            return
        }
        // 哔哩哔哩红心属于视频关系状态，只能按歌曲的 bvid 读取，不能从收藏夹推断。
        if (mode() == io.github.currencortex.music.data.song.MusicSource.BILIBILI) return
        if (accountId() == 0L) return
        val expected = session()
        distinctIds.chunked(100).forEach { chunk ->
            if (expected != session()) return
            val d = api.get<SongStatusDto>("songs/status", mapOf("ids" to chunk.joinToString(",")), true)
            if (expected == session()) applyRemoteStatuses(
                chunk.associateWith { it in d.liked }, observed,
            )
        }
    }

    /** 用当前歌曲的稳定外部编号读取 QQ / 哔哩哔哩红心，避免整库刷新覆盖刚写入的状态。 */
    suspend fun refreshSongStatuses(songs: List<Song>) {
        val distinct = songs.distinctBy { it.id }
        qqSession?.invoke()?.takeIf { it.loggedIn }?.let { qq ->
            val tracks = distinct.filter { it.musicSource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC }
                .mapNotNull { song -> song.externalIds.qqMusicId?.let { it to song.id } }
                .distinctBy { it.second }
            if (tracks.isNotEmpty()) {
                val observed = likeStatusReconciliation.snapshot(tracks.map { it.second })
                val liked = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.QqLibrary.likedMids(
                        tracks.map { it.first }, qq.cookieHeader(), qq.uin)
                }
                if (liked.isNotEmpty()) applyRemoteStatuses(
                    tracks.mapNotNull { (mid, id) -> liked[mid]?.let { id to it } }.toMap(), observed)
            }
        }
        biliSession?.invoke()?.takeIf { it.loggedIn }?.let { bili ->
            val tracks = distinct.filter { it.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI }
                .mapNotNull { song -> song.externalIds.bilibiliBvid?.let { it to song.id } }
                .distinctBy { it.second }
            if (tracks.isNotEmpty()) {
                val observed = likeStatusReconciliation.snapshot(tracks.map { it.second })
                val remote = withContext(Dispatchers.IO) {
                    tracks.mapNotNull { (bvid, id) ->
                        io.github.currencortex.music.core.alt.BiliAuth.isLiked(bili.cookieHeader(), bvid)?.let { id to it }
                    }.toMap()
                }
                if (remote.isNotEmpty()) applyRemoteStatuses(remote, observed)
            }
        }
        val legacy = distinct.filter { it.musicSource == io.github.currencortex.music.data.song.MusicSource.NETEASE }
        if (legacy.isNotEmpty()) refreshStatus(legacy.map { it.id })
    }
    suspend fun toggleLike(song: Song): AppResult<Unit> = changeLike(song, null)
    suspend fun setLiked(song: Song, liked: Boolean): AppResult<Unit> = changeLike(song, liked)
    suspend fun dislikeState(song: Song): AppResult<Boolean> {
        if (song.musicSource != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC)
            return AppResult.Failure(ErrorKind.NotFound)
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn } ?: return AppResult.Failure(ErrorKind.Unauthorized)
        val id = song.externalIds.qqNumericId?.toLongOrNull() ?: return AppResult.Failure(ErrorKind.NotFound)
        return appResult {
            withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.isDisliked(id, qq.cookies, qq.uin)
                    ?: throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐不喜欢状态读取失败")
            }
        }
    }
    suspend fun setDisliked(song: Song, disliked: Boolean): AppResult<Unit> = mutationMutex.withLock {
        if (song.musicSource != io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC)
            return@withLock AppResult.Failure(ErrorKind.NotFound)
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
            ?: return@withLock AppResult.Failure(ErrorKind.Unauthorized)
        val id = song.externalIds.qqNumericId?.toLongOrNull()
            ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
        appResult {
            val ok = withContext(Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqLibrary.setDisliked(id, disliked, qq.cookies, qq.uin)
            }
            if (!ok) throw ApiException(ErrorKind.Server,
                userMessage = if (disliked) "QQ 音乐未能记录不喜欢" else "QQ 音乐未能撤销不喜欢")
        }
    }
    private suspend fun changeLike(song: Song, desired: Boolean?): AppResult<Unit> = mutationMutex.withLock {
        // QQ 音乐：收藏 = 写入「我喜欢」歌单（dirid 201）
        if (song.musicSource == io.github.currencortex.music.data.song.MusicSource.QQ_MUSIC) {
            val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
                ?: return@withLock AppResult.Failure(ErrorKind.Unauthorized)
            val mid = song.externalIds.qqMusicId ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
            val numericId = song.externalIds.qqNumericId?.toLongOrNull()?.takeIf { it > 0 }
                ?: withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.QqExtras.resolveSongId(song.name, song.artists, mid)
                }
                ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
            val target = desired ?: !(statuses.value[song.id]?.liked ?: false)
            likeStatusReconciliation.beginWrite(song.id)
            return@withLock appResult {
                val ok = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.QqLibrary.setLiked(
                        numericId, mid, target, qq.cookies, qq.uin)
                }
                if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐收藏失败，请确认账号仍有效")
                likeStatusReconciliation.confirm(song.id, target)
                statuses.update { current ->
                    val now = current[song.id] ?: SongStatus()
                    current + (song.id to now.copy(liked = target, pending = false))
                }
                revision.update { it + 1 }
            }
        }
        // 哔哩哔哩红心：使用视频点赞关系；收藏夹由独立入口维护。
        if (song.musicSource == io.github.currencortex.music.data.song.MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()
            if (bili == null || !bili.loggedIn) return@withLock AppResult.Failure(ErrorKind.Unauthorized)
            val bvid = song.externalIds.bilibiliBvid
                ?: return@withLock AppResult.Failure(ErrorKind.NotFound)
            val target = desired ?: !(statuses.value[song.id]?.liked ?: false)
            val csrf = bili.cookies["bili_jct"].orEmpty()
            if (csrf.isBlank()) return@withLock AppResult.Failure(ErrorKind.Unauthorized)
            return@withLock appResult {
                likeStatusReconciliation.beginWrite(song.id)
                val ok = withContext(Dispatchers.IO) {
                    io.github.currencortex.music.core.alt.BiliAuth.setLiked(
                        bili.cookieHeader(), bvid, target, csrf)
                }
                if (!ok) throw ApiException(ErrorKind.Server,
                    userMessage = if (target) "哔哩哔哩点赞失败" else "哔哩哔哩取消点赞失败")
                likeStatusReconciliation.confirm(song.id, target)
                statuses.update { current -> current + (song.id to (current[song.id] ?: SongStatus()).copy(
                    liked = target, pending = false)) }
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
        likeStatusReconciliation.beginWrite(song.id)
        statuses.update { it + (song.id to before.copy(liked = !before.liked, pending = true)) }
        val result = try { appResult {
            api.request("POST", "likes/${song.id}", body = metadata(song), authenticated = true, expectedSession = expected)
        } } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (expected == session()) statuses.update { it + (song.id to before) }
            throw cancelled
        }
        if (expected == session()) {
            statuses.update { it + (song.id to if (result is AppResult.Success) {
                val confirmed = (result.value as? JsonObject)?.get("on")?.jsonPrimitive?.booleanOrNull ?: !before.liked
                likeStatusReconciliation.confirm(song.id, confirmed)
                (it[song.id] ?: before).copy(liked = confirmed, pending = false)
            } else before) }
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
