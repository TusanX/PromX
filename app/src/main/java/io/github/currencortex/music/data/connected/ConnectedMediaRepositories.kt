// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.data.connected

import android.util.Xml
import io.github.currencortex.music.core.media.AudioQuality
import io.github.currencortex.music.core.network.ApiException
import io.github.currencortex.music.core.network.ErrorKind
import io.github.currencortex.music.core.network.RequestSession
import io.github.currencortex.music.core.security.TokenStore
import io.github.currencortex.music.data.song.AudioSource
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.data.song.SongExternalIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ConnectedEntry(
    val id: String,
    val title: String,
    val path: String,
    val directory: Boolean,
    val artist: String = "",
    val album: String = "",
    val mime: String = "",
    val size: Long = 0,
)

data class WebDavAccount(val server: String = "", val username: String = "", val configured: Boolean = false,
    val identity: String = "")

data class MusicAssistantAccount(val server: String = "", val configured: Boolean = false, val identity: String = "",
    val userName: String = "")

data class ConnectedTrack(val song: Song, val uri: String, val provider: String = "")

private val connectedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private fun String.normalizedServer(): String {
    val trimmed = trim().trimEnd('/')
    require(trimmed.isNotBlank()) { "请输入服务器地址" }
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    URI(withScheme).let { uri ->
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "服务器地址格式不正确" }
    }
    return withScheme
}

private fun stableId(identity: String, key: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest((identity + "\n" + key).toByteArray())
    return java.nio.ByteBuffer.wrap(digest).long
}

private fun String.xmlText(): String = replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'")

class WebDavRepository(private val vault: TokenStore, scope: CoroutineScope) {
    data class Access(val server: String, val username: String, val password: String) {
        val identity = MessageDigest.getInstance("SHA-256").digest((server + "\n" + username).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private val lock = Mutex()
    @Volatile private var access: Access? = null
    val state = MutableStateFlow(WebDavAccount())
    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                vault.read()?.let(::decode)?.let { saved -> access = saved; publish(saved) }
            } catch (_: Exception) {
                vault.clear(); access = null; publish(null)
            } finally { ready.complete(Unit) }
        }
    }

    private fun decode(raw: String): Access? = runCatching {
        val value = Json.parseToJsonElement(raw).jsonObject
        Access(value["server"]!!.jsonPrimitive.content, value["username"]!!.jsonPrimitive.content,
            value["password"]!!.jsonPrimitive.content)
    }.getOrNull()

    private fun publish(value: Access?) {
        state.value = if (value == null) WebDavAccount() else WebDavAccount(value.server, value.username, true, value.identity)
    }

    suspend fun connect(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        ready.await()
        val normalized = server.normalizedServer()
        val candidate = Access(normalized, username.trim(), password)
        require(candidate.username.isNotBlank()) { "请输入 WebDAV 用户名" }
        require(password.isNotEmpty()) { "请输入 WebDAV 密码" }
        lock.withLock {
            propfind(candidate, normalized + "/", depth = "0")
            vault.write(buildJsonObject {
                put("server", normalized); put("username", candidate.username); put("password", password)
            }.toString())
            access = candidate; publish(candidate)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        ready.await(); lock.withLock { vault.clear(); access = null; publish(null) }
    }

    fun current(): Access? = access
    fun session(): RequestSession = state.value.let { RequestSession(it.server, it.identity.takeIf { _ -> it.configured }) }
    fun accepts(song: Song) = song.musicSource == MusicSource.WEBDAV && state.value.configured &&
        song.externalIds.webDavPath?.startsWith(state.value.server + "/") == true

    suspend fun browse(path: String = state.value.server + "/"): List<ConnectedEntry> = withContext(Dispatchers.IO) {
        ready.await(); val value = access ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先连接 WebDAV")
        val root = path.takeIf(String::isNotBlank) ?: value.server + "/"
        val response = propfind(value, root, depth = "1")
        parseWebDavEntries(response, value.server).filter { it.path.trimEnd('/') != root.trimEnd('/') }
            .sortedWith(compareByDescending<ConnectedEntry> { it.directory }.thenBy { it.title.lowercase() })
    }

    fun prospectiveSong(entry: ConnectedEntry): Song {
        val value = access ?: throw ApiException(ErrorKind.Unauthorized, userMessage = "请先连接 WebDAV")
        return Song(
            id = stableId(value.identity, entry.path), name = entry.title, artists = entry.artist,
            album = entry.album, musicSource = MusicSource.WEBDAV, localUri = entry.path,
            externalIds = SongExternalIds(webDavPath = entry.path),
        )
    }

    fun source(song: Song, quality: AudioQuality): AudioSource {
        val path = song.externalIds.webDavPath?.takeIf(String::isNotBlank)
            ?: throw ApiException(ErrorKind.NotFound, userMessage = "WebDAV 歌曲地址缺失")
        return AudioSource(path, "WebDAV 原音质", 0, 0, source = "webdav", validForSeconds = 600)
    }

    fun mediaClient(client: OkHttpClient): OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val value = access
        val request = if (value != null && chain.request().url.host == URI(value.server).host) {
            chain.request().newBuilder().header("Authorization", Credentials.basic(value.username, value.password)).build()
        } else chain.request()
        chain.proceed(request)
    }.build()

    private fun propfind(value: Access, path: String, depth: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/>
            <d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>""".trimIndent()
        val request = Request.Builder().url(path).method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .header("Depth", depth).header("Authorization", Credentials.basic(value.username, value.password)).build()
        connectedHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(if (response.code == 401 || response.code == 403)
                ErrorKind.Unauthorized else ErrorKind.Server, response.code, "WebDAV 连接失败（" + response.code + "）")
            return response.body?.string().orEmpty()
        }
    }

    private fun parseWebDavEntries(xml: String, server: String): List<ConnectedEntry> {
        val parser = Xml.newPullParser().apply { setInput(xml.byteInputStream(), "UTF-8") }
        val result = mutableListOf<ConnectedEntry>()
        var href = ""; var display = ""; var mime = ""; var size = 0L; var directory = false
        var insideResponse = false
        var currentTag = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(':').lowercase()
                    when (currentTag) {
                        "response" -> { insideResponse = true; href = ""; display = ""; mime = ""; size = 0; directory = false }
                        "collection" -> if (insideResponse) directory = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (!insideResponse) { event = parser.next(); continue }
                    when (currentTag) {
                        "href" -> href += parser.text.xmlText()
                        "displayname" -> display += parser.text.xmlText()
                        "getcontenttype" -> mime += parser.text.xmlText()
                        "getcontentlength" -> size += parser.text.trim().toLongOrNull() ?: 0L
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfter(':').equals("response", true)) {
                        insideResponse = false
                        if (href.isNotBlank()) {
                            val cleanHref = href.trim()
                            val absolute = if (cleanHref.startsWith("http")) cleanHref else URI(server).resolve(cleanHref).toString()
                            val path = URLDecoder.decode(absolute, "UTF-8")
                            val title = display.trim().ifBlank { path.substringAfterLast('/').ifBlank { "曲库" } }
                            result += ConnectedEntry(path, title, absolute, directory, mime = mime.trim(), size = size)
                        }
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return result
    }
}

class MusicAssistantRepository(private val vault: TokenStore, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val responses = Channel<JsonObject>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var socket: WebSocket? = null
    @Volatile private var credentials: Credentials? = null
    val state = MutableStateFlow(MusicAssistantAccount())
    private val ready = CompletableDeferred<Unit>()

    data class Credentials(val server: String, val token: String) {
        val identity: String = MessageDigest.getInstance("SHA-256").digest((server + "\n" + token).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                vault.read()?.let { raw ->
                    val value = json.parseToJsonElement(raw).jsonObject
                    Credentials(value["server"]!!.jsonPrimitive.content, value["token"]!!.jsonPrimitive.content)
                }?.let { saved ->
                    credentials = saved
                    state.value = MusicAssistantAccount(saved.server, true, saved.identity)
                }
            } catch (_: Exception) { vault.clear(); credentials = null } finally { ready.complete(Unit) }
        }
        scope.launch { for (message in responses) dispatch(message) }
    }

    private fun dispatch(message: JsonObject) {
        val id = message["message_id"]?.jsonPrimitive?.contentOrNull ?: return
        pending.remove(id)?.complete(message)
    }

    private fun wsUrl(server: String): String {
        val normalized = server.normalizedServer()
        return normalized.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/') + "/ws"
    }

    suspend fun connect(server: String, token: String) = withContext(Dispatchers.IO) {
        ready.await()
        val normalized = server.normalizedServer()
        require(token.trim().isNotBlank()) { "请输入 Music Assistant 长期访问令牌" }
        lock.withLock { open(Credentials(normalized, token.trim()), persist = true) }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        ready.await(); lock.withLock {
            socket?.close(1000, "PromX disconnected"); socket = null
            vault.clear(); credentials = null; state.value = MusicAssistantAccount()
        }
    }

    fun current(): Credentials? = credentials
    fun close() { socket?.close(1000, "PromX closing"); socket = null }
    fun accepts(song: Song) = song.musicSource == MusicSource.MUSIC_ASSISTANT && state.value.configured &&
        !song.externalIds.musicAssistantUri.isNullOrBlank()
    fun session(): RequestSession = state.value.let { RequestSession(it.server, it.identity.takeIf { _ -> it.configured }) }

    suspend fun tracks(limit: Int = 80): List<ConnectedTrack> {
        ensureConnected()
        val answer = request("music/tracks/library_items", buildJsonObject {
            put("limit", limit); put("offset", 0)
        })
        return parseItems(answer["result"]).mapNotNull(::toTrack)
    }

    suspend fun search(query: String): List<ConnectedTrack> {
        ensureConnected()
        val answer = request("music/search", buildJsonObject {
            put("search_query", query); put("media_types", JsonArray(listOf(JsonPrimitive("track"))))
            put("limit", 50); put("library_only", false)
        })
        return parseItems(answer["result"]).mapNotNull(::toTrack)
    }

    suspend fun remotePlayers(): List<Pair<String, String>> {
        ensureConnected()
        val answer = request("players/all")
        return parseItems(answer["result"]).mapNotNull { item ->
            val id = item["player_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: item["display_name"]?.jsonPrimitive?.contentOrNull ?: id
            id to name
        }
    }

    suspend fun playRemote(track: ConnectedTrack, playerId: String) {
        ensureConnected()
        request("player_queues/play_media", buildJsonObject {
            put("media", JsonArray(listOf(JsonPrimitive(track.uri))))
            put("queue_id", playerId); put("option", "replace"); put("radio_mode", false)
        })
    }

    private fun toTrack(value: JsonObject): ConnectedTrack? {
        if (value["media_type"]?.jsonPrimitive?.contentOrNull != "track") return null
        val itemId = value["item_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val provider = value["provider"]?.jsonPrimitive?.contentOrNull ?: ""
        val uri = value["uri"]?.jsonPrimitive?.contentOrNull ?: (provider + "://track/" + itemId)
        val album = value["album"] as? JsonObject
        val artists = (value["artists"] as? JsonArray)?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty().joinToString(" / ")
        val cover = value["image"]?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val configured = credentials ?: return null
        val image = when {
            cover.isBlank() -> ""
            cover.startsWith("http") -> cover
            else -> configured.server + "/imageproxy/" + cover.trimStart('/')
        }
        val song = Song(
            id = stableId(configured.identity, uri), name = value["name"]?.jsonPrimitive?.contentOrNull ?: "未知歌曲",
            artists = artists, album = album?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            cover = image, durationMs = ((value["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            musicSource = MusicSource.MUSIC_ASSISTANT,
            externalIds = SongExternalIds(musicAssistantUri = uri, musicAssistantProvider = provider, musicAssistantItemId = itemId),
        )
        return ConnectedTrack(song, uri, provider)
    }

    private suspend fun ensureConnected() {
        ready.await()
        if (credentials == null) throw ApiException(ErrorKind.Unauthorized, userMessage = "请先连接 Music Assistant")
        if (socket == null) lock.withLock { credentials?.let { open(it, persist = false) } }
    }

    private suspend fun open(value: Credentials, persist: Boolean) {
        socket?.close(1000, "Reconnecting")
        socket = null
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(wsUrl(value.server)).build()
        socket = connectedHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { responses.trySend(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
            }
        })
        withTimeout(15_000) { opened.await() }
        val auth = request("auth", buildJsonObject {
            put("token", value.token); put("device_name", "PromX Android")
        })
        if (auth["error_code"] != null) throw ApiException(ErrorKind.Unauthorized,
            userMessage = auth["error"]?.jsonPrimitive?.contentOrNull ?: "Music Assistant 登录失败")
        credentials = value
        if (persist) vault.write(buildJsonObject { put("server", value.server); put("token", value.token) }.toString())
        val user = auth["result"]?.jsonObject?.get("user")?.jsonObject
        state.value = MusicAssistantAccount(value.server, true, value.identity,
            user?.get("username")?.jsonPrimitive?.contentOrNull ?: user?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    private suspend fun request(command: String, args: JsonObject? = null): JsonObject {
        val webSocket = socket ?: throw ApiException(ErrorKind.Network, userMessage = "Music Assistant 未连接")
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        val payload = buildJsonObject {
            put("message_id", id); put("command", command); args?.let { put("args", it) }
        }
        if (!webSocket.send(payload.toString())) {
            pending.remove(id)
            throw ApiException(ErrorKind.Network, userMessage = "Music Assistant 请求发送失败")
        }
        return try { withTimeout(20_000) { reply.await() } } finally { pending.remove(id) }
    }

    private fun parseItems(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> (element["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: (element["results"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        else -> emptyList()
    }
}
