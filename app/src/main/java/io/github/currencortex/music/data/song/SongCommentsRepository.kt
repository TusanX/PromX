package io.github.currencortex.music.data.song

import io.github.currencortex.music.core.network.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class CommentSort(val value: String, val label: String) {
    RECOMMENDED("recommended", "推荐"), HOT("hot", "最热"), LATEST("latest", "最新")
}

@Serializable internal data class CommentDto(val id: String, val userId: Long = 0, val nickname: String = "",
    val avatar: String = "", val content: String = "", val time: Long = 0, val liked: Long = 0,
    val likedByMe: Boolean = false, val beNickname: String = "", val beContent: String? = null, val replyCount: Int = 0) {
    fun toDomain() = NeteaseComment(id.toLongOrNull() ?: 0, content, time, liked,
        NeteaseCommentUser(nickname, avatar),
        if (beNickname.isNotBlank() || !beContent.isNullOrBlank()) listOf(NeteaseCommentReply(beContent, NeteaseCommentUser(beNickname))) else emptyList(),
        id, userId, likedByMe, replyCount)
}
@Serializable internal data class CommentPageDto(val comments: List<CommentDto>, val total: Long = 0,
    val more: Boolean = false, val nextCursor: String? = null, val meUid: Long = 0)
data class SongCommentsPage(val comments: List<NeteaseComment>, val total: Long, val more: Boolean,
    val nextCursor: String?, val meUid: Long)

/** Local NetEase credentials stay on-device; otherwise the project uses its saved binding. */
class SongCommentsRepository(private val api: ApiClient, private val session: () -> RequestSession,
    private val qqSession: (() -> io.github.currencortex.music.core.alt.QqSessionStore?)? = null,
    private val biliSession: (() -> io.github.currencortex.music.core.alt.BiliSessionStore?)? = null) {
    private suspend fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonElement {
        val owner = session()
        val native = api.hasLocalNeteaseLogin || api.nativeMediaEnabled
        val result = api.request(method, path, query, body, authenticated = method != "GET" || owner.token != null,
            expectedSession = owner, projectOnly = !native, retryConnection = method == "GET", callTimeoutMillis = 15_000)
        if (owner != session()) throw ApiException(ErrorKind.NeteaseSessionChanged)
        val code = (result as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull
        if (method == "POST" && code == null) throw ApiException(ErrorKind.Parse)
        if (code != null && code != 200) throw ApiException(when (code) {
            301, 401 -> ErrorKind.NeteaseBindingRequired
            403 -> ErrorKind.Forbidden
            429 -> ErrorKind.RateLimited
            else -> ErrorKind.Server
        }, code)
        if ((result as? JsonObject)?.get("ok")?.jsonPrimitive?.booleanOrNull == false) throw ApiException(ErrorKind.Server)
        return result
    }
    private suspend fun page(path: String, query: Map<String, String>): SongCommentsPage {
        val dto = api.decode<CommentPageDto>(call("GET", path, query))
        return SongCommentsPage(dto.comments.map(CommentDto::toDomain), dto.total, dto.more, dto.nextCursor, dto.meUid)
    }
    private fun biliComment(comment: io.github.currencortex.music.core.alt.BiliPlayback.Comment): NeteaseComment = NeteaseComment(
        commentId = comment.id.toLongOrNull() ?: 0L,
        content = comment.content,
        time = comment.timeSec * 1000,
        likedCount = comment.likes,
        user = NeteaseCommentUser(comment.user, comment.avatar),
        id = comment.id,
        userId = comment.userId,
        likedByMe = comment.likedByMe,
        replyCount = comment.replyCount,
    )

    private suspend fun biliAid(song: Song, cookie: String): String? {
        val bvid = song.externalIds.bilibiliBvid ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.currencortex.music.core.alt.BiliPlayback.videoInfo(bvid, cookie)?.aid
        }
    }

    /** 按歌曲来源分派：QQ 音乐走自己的评论接口（无需登录） */
    suspend fun comments(song: Song, sort: CommentSort, page: Int = 1): SongCommentsPage {
        // B站：按 aid 查视频评论（type=1）
        if (song.musicSource == MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()
            val cookie = bili?.takeIf { it.loggedIn }?.cookieHeader().orEmpty()
            val aid = biliAid(song, cookie) ?: return SongCommentsPage(emptyList(), 0, false, null, 0)
            val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.comments(
                    aid, hot = sort != CommentSort.LATEST, page = page, cookie = cookie)
            }
            val me = bili?.profile?.mid ?: 0L
            return SongCommentsPage(raw.comments.map(::biliComment), raw.total,
                raw.more, raw.nextCursor, me)
        }
        if (song.musicSource != MusicSource.QQ_MUSIC) return comments(song.id, sort, page)
        // 歌单、红心和榜单映射现在会保留数字 songid；旧队列条目才按标题和 songmid 回查。
        val numeric = song.externalIds.qqNumericId?.toLongOrNull()
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.QqExtras.resolveSongId(
                    song.name, song.artists, song.externalIds.qqMusicId)
            }
            ?: return SongCommentsPage(emptyList(), 0, false, null, 0)
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
        val result = io.github.currencortex.music.core.alt.QqExtras.comments(
            numeric, latest = sort == CommentSort.LATEST, page = page,
            cookies = qq?.cookies.orEmpty(), uin = qq?.uin.orEmpty().ifBlank { "0" })
        val list = result.comments.map { comment ->
            NeteaseComment(0, comment.content, comment.timeSeconds * 1000, comment.likes,
                NeteaseCommentUser(comment.nick, comment.avatar), emptyList(), comment.id,
                if (comment.canDelete) 1 else 0, comment.likedByMe, comment.replyCount)
        }
        val nextPage = if (result.more) (page + 1).toString() else null
        return SongCommentsPage(list, result.total, result.more, nextPage, 1)
    }

    suspend fun replies(song: Song, commentId: String, cursor: String? = null): SongCommentsPage {
        if (song.musicSource == MusicSource.BILIBILI) {
            val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val bili = biliSession?.invoke()
            val cookie = bili?.takeIf { it.loggedIn }?.cookieHeader().orEmpty()
            val aid = biliAid(song, cookie) ?: return SongCommentsPage(emptyList(), 0, false, null, 0)
            val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.replies(aid, commentId, page, cookie = cookie)
            }
            val me = bili?.profile?.mid ?: 0L
            return SongCommentsPage(raw.comments.map(::biliComment), raw.total, raw.more,
                raw.nextCursor ?: if (raw.more) (page + 1).toString() else null, me)
        }
        if (song.musicSource != MusicSource.QQ_MUSIC) {
            return SongCommentsPage(emptyList(), 0, false, null, 0)
        }
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqExtras.replies(
                commentId, page, cookies = qq?.cookies.orEmpty(), uin = qq?.uin.orEmpty().ifBlank { "0" })
        }
        val list = result.comments.map { comment ->
            NeteaseComment(0, comment.content, comment.timeSeconds * 1000, comment.likes,
                NeteaseCommentUser(comment.nick, comment.avatar), emptyList(), comment.id,
                if (comment.canDelete) 1 else 0, comment.likedByMe, comment.replyCount)
        }
        return SongCommentsPage(list, result.total, result.more,
            if (result.more) (page + 1).toString() else null, 1)
    }

    suspend fun comments(id: Long, sort: CommentSort, page: Int = 1, cursor: String? = null): SongCommentsPage =
        page("ncm/comments/page", buildMap {
            put("type", "0"); put("id", "$id"); put("sort", sort.value); put("page", "$page"); put("limit", "20")
            if (sort == CommentSort.LATEST && page > 1 && cursor != null) put("cursor", cursor)
        })
    suspend fun replies(id: Long, commentId: String, cursor: String? = null): SongCommentsPage =
        page("ncm/comments/replies", buildMap {
            put("type", "0"); put("id", "$id"); put("commentId", commentId); put("limit", "20")
            cursor?.let { put("cursor", it) }
        })
    suspend fun like(id: Long, commentId: String, liked: Boolean) {
        call("POST", "ncm/comment/like", body = buildJsonObject {
            put("id", id); put("type", 0); put("cid", commentId); put("t", if (liked) 1 else 0); put("confirm", 1)
        })
    }
    suspend fun send(id: Long, content: String, replyTo: String? = null) {
        require(content.isNotBlank() && content.trim().length <= 1000)
        call("POST", "ncm/comment", body = buildJsonObject {
            put("id", id); put("type", 0); put("t", if (replyTo == null) 1 else 2); put("confirm", 1)
            put("content", content.trim()); replyTo?.let { put("commentId", it) }
        })
    }
    suspend fun delete(id: Long, commentId: String) {
        call("POST", "ncm/comment", body = buildJsonObject {
            put("id", id); put("type", 0); put("t", 0); put("confirm", 1); put("commentId", commentId)
        })
    }

    suspend fun send(song: Song, content: String, replyTo: String? = null) {
        if (song.musicSource == MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()?.takeIf { it.loggedIn }
                ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 B站")
            val cookie = bili.cookieHeader()
            val aid = biliAid(song, cookie) ?: throw ApiException(ErrorKind.NotFound)
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.sendComment(
                    aid, content, replyTo, cookie, bili.cookies["bili_jct"].orEmpty())
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "B站评论发送失败")
            return
        }
        if (song.musicSource != MusicSource.QQ_MUSIC) {
            val id = song.externalIds.neteaseId?.toLongOrNull() ?: song.id
            send(id, content, replyTo)
            return
        }
        val numeric = song.externalIds.qqNumericId?.toLongOrNull()
            ?: throw ApiException(ErrorKind.NotFound)
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
            ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 QQ 音乐")
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqExtras.sendComment(
                numeric, content, replyTo, qq.cookies, qq.uin)
        }
        if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐评论发送失败")
    }

    suspend fun like(song: Song, commentId: String, liked: Boolean) {
        if (song.musicSource == MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()?.takeIf { it.loggedIn }
                ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 B站")
            val cookie = bili.cookieHeader()
            val aid = biliAid(song, cookie) ?: throw ApiException(ErrorKind.NotFound)
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.likeComment(
                    aid, commentId, liked, cookie, bili.cookies["bili_jct"].orEmpty())
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "B站评论点赞失败")
            return
        }
        if (song.musicSource != MusicSource.QQ_MUSIC) {
            like(song.externalIds.neteaseId?.toLongOrNull() ?: song.id, commentId, liked)
            return
        }
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
            ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 QQ 音乐")
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqExtras.likeComment(
                commentId, liked, qq.cookies, qq.uin)
        }
        if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐评论点赞失败")
    }

    suspend fun delete(song: Song, commentId: String) {
        if (song.musicSource == MusicSource.BILIBILI) {
            val bili = biliSession?.invoke()?.takeIf { it.loggedIn }
                ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 B站")
            val cookie = bili.cookieHeader()
            val aid = biliAid(song, cookie) ?: throw ApiException(ErrorKind.NotFound)
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.currencortex.music.core.alt.BiliPlayback.deleteComment(
                    aid, commentId, cookie, bili.cookies["bili_jct"].orEmpty())
            }
            if (!ok) throw ApiException(ErrorKind.Server, userMessage = "B站评论删除失败")
            return
        }
        if (song.musicSource != MusicSource.QQ_MUSIC) {
            delete(song.externalIds.neteaseId?.toLongOrNull() ?: song.id, commentId)
            return
        }
        val qq = qqSession?.invoke()?.takeIf { it.loggedIn }
            ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先登录 QQ 音乐")
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.currencortex.music.core.alt.QqExtras.deleteComment(
                commentId, qq.cookies, qq.uin)
        }
        if (!ok) throw ApiException(ErrorKind.Server, userMessage = "QQ 音乐评论删除失败")
    }
}
