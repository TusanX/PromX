package io.github.currencortex.music.core.alt

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.eclipse.paho.mqttv5.common.packet.MqttProperties
import org.eclipse.paho.mqttv5.common.packet.UserProperty

/** Native QQ Music App QR login and credential refresh, following qq-music-api 3.3.6. */
internal object QqNativeAuth {
    private const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val MQTT_URL = "wss://mu.y.qq.com/ws/handshake"
    internal const val CLIENT_UA = "QQMusic 14090008(android 15)"
    internal const val QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614"
    internal const val DEVICE_ID = "abcdef1234567890abcdef1234567890"

    data class Qr(val image: ByteArray, val qrcodeId: String, val expiresIn: Int,
        internal val uid: String = "", internal val sid: String = "")
    data class Event(val type: String, val payload: JsonObject? = null)

    class Session internal constructor(val qr: Qr, internal val client: MqttAsyncClient,
        internal val events: LinkedBlockingQueue<Event>) {
        private val closed = AtomicBoolean(false)
        fun nextEvent(timeoutMs: Long): Event? = events.poll(timeoutMs, TimeUnit.MILLISECONDS)
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { client.disconnectForcibly(500, 500, false) }
            runCatching { client.close(true) }
        }
    }

    fun createQr(): Qr? = runCatching {
        val session = getAnonymousSession()
        val data = call("music.login.LoginServer", "CreateQRCode", buildJsonObject {
            put("tmeAppID", "qqmusic")
            put("ct", 11)
            put("cv", 14090008)
        }, uid = session.first, sid = session.second, ct = 23, cv = 0)
        val qrcodeId = data?.get("qrcodeID")?.jsonPrimitive?.contentOrNull.orEmpty()
        val encoded = data?.get("qrcode")?.jsonPrimitive?.contentOrNull.orEmpty().substringAfter(',')
        val image = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        require(qrcodeId.isNotBlank() && image.size > 8 && image.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        Qr(image, qrcodeId, data?.get("expiresIn")?.jsonPrimitive?.intOrNull ?: 900,
            uid = session.first, sid = session.second)
    }.getOrNull()

    fun listen(qr: Qr): Session? {
        var connected: MqttAsyncClient? = null
        return try {
        val events = LinkedBlockingQueue<Event>()
        val clientId = System.currentTimeMillis().toString() + Random.nextInt(1000, 10000)
        val client = MqttAsyncClient(MQTT_URL, clientId, MemoryPersistence())
        connected = client
        client.setCallback(object : MqttCallback {
            override fun disconnected(response: MqttDisconnectResponse) {
                events.offer(Event("loginFailed"))
            }
            override fun mqttErrorOccurred(exception: MqttException) {
                events.offer(Event("loginFailed"))
            }
            override fun messageArrived(topic: String?, message: MqttMessage) {
                val payload = runCatching { Json.parseToJsonElement(message.payload.decodeToString()).jsonObject }
                    .getOrNull()
                val type = message.properties?.userProperties?.firstOrNull { it.key == "type" }?.value
                    ?: payload?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
                events.offer(Event(type, payload))
            }
            override fun deliveryComplete(token: IMqttToken?) = Unit
            override fun connectComplete(reconnect: Boolean, serverURI: String?) = Unit
            override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) = Unit
        })
        val options = MqttConnectionOptions().apply {
            isCleanStart = true
            keepAliveInterval = 45
            connectionTimeout = 20
            isAutomaticReconnect = false
            authMethod = "pass"
            userProperties = listOf(
                UserProperty("tmeAppID", "qqmusic"),
                UserProperty("business", "management"),
                UserProperty("hashTag", qr.qrcodeId),
                UserProperty("clientTag", "management.user"),
                UserProperty("userID", qr.qrcodeId),
            )
        }
        client.connect(options).waitForCompletion(20_000)
        val properties = MqttProperties().apply {
            userProperties = listOf(UserProperty("authorization", "tmelogin"), UserProperty("pubsub", "unicast"))
        }
        client.subscribe(
            arrayOf(MqttSubscription("management.qrcode_login/${qr.qrcodeId}", 0)),
            null,
            null,
            properties,
        ).waitForCompletion(20_000)
        Session(qr, client, events)
        } catch (_: Exception) {
            runCatching { connected?.disconnectForcibly(500, 500, false) }
            runCatching { connected?.close(true) }
            null
        }
    }

    fun exchangeCredential(session: Session, event: Event): QqMusicCredential? = runCatching {
        val cookies = event.payload?.get("cookies")?.jsonObject ?: return null
        fun cookie(name: String): String =
            cookies[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
        val uin = cookie("qqmusic_uin")
        val mqttKey = cookie("qqmusic_key")
        val mqttLoginType = cookie("tmeLoginType").toIntOrNull()
        require(uin.isNotBlank() && mqttKey.isNotBlank())
        val loginType = if (mqttLoginType == 1) 1 else 6
        val data = runCatching {
            call("music.login.LoginServer", "Login", buildJsonObject {
                val id = uin.toLongOrNull()
                if (id != null) put("musicid", id) else put("musicid", uin)
                put("qrCodeID", session.qr.qrcodeId)
                put("token", mqttKey)
            }, uid = session.qr.uid, sid = session.qr.sid, loginType = 6)
        }.getOrNull()
        val credential = credentialFrom(data, uin, mqttKey, loginType)
            ?: QqMusicCredential(uin, mqttKey, loginType)
        if (validate(credential)) credential else null
    }.getOrNull()

    fun refresh(credential: QqMusicCredential): QqMusicCredential? = runCatching {
        if (credential.refreshToken.isBlank() && credential.refreshKey.isBlank()) return null
        val data = call("music.login.LoginServer", "Login", buildJsonObject {
            put("openid", credential.openId)
            put("refresh_token", credential.refreshToken)
            put("str_musicid", credential.uin)
            put("musickey", credential.key)
            put("unionid", credential.unionId)
            put("refresh_key", credential.refreshKey)
            put("loginMode", 2)
        }, credential = credential, loginType = credential.loginType) ?: return null
        credentialFrom(data, credential.uin, credential.key, credential.loginType)?.let { refreshed ->
            refreshed.copy(
                nickname = refreshed.nickname.ifBlank { credential.nickname },
                avatar = refreshed.avatar.ifBlank { credential.avatar },
                openId = refreshed.openId.ifBlank { credential.openId },
                refreshToken = refreshed.refreshToken.ifBlank { credential.refreshToken },
                unionId = refreshed.unionId.ifBlank { credential.unionId },
                refreshKey = refreshed.refreshKey.ifBlank { credential.refreshKey },
            )
        }?.takeIf(::validate)
    }.getOrNull()

    fun cookiesOf(credential: QqMusicCredential): Map<String, String> = mapOf(
        "uin" to credential.uin,
        "qqmusic_uin" to credential.uin,
        "qm_keyst" to credential.key,
        "qqmusic_key" to credential.key,
        "tmeLoginType" to credential.loginType.toString(),
    ) + listOfNotNull(credential.encryptUin.takeIf(String::isNotBlank)?.let { "encrypt_uin" to it }).toMap()

    fun cookieHeaderOf(credential: QqMusicCredential): String =
        cookiesOf(credential).entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun credentialFrom(data: JsonObject?, fallbackUin: String, fallbackKey: String,
        fallbackLoginType: Int): QqMusicCredential? {
        val value = data ?: return null
        val uin = (value["musicid"]?.jsonPrimitive?.contentOrNull
            ?: value["str_musicid"]?.jsonPrimitive?.contentOrNull).orEmpty().ifBlank { fallbackUin }
        val key = value["musickey"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { fallbackKey }
        if (uin.isBlank() || key.isBlank()) return null
        return QqMusicCredential(
            uin = uin,
            key = key,
            loginType = value["loginType"]?.jsonPrimitive?.intOrNull ?: fallbackLoginType,
            nickname = value["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            avatar = value["avatarUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            openId = value["openid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = value["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            unionId = value["unionid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshKey = value["refresh_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            encryptUin = listOf("encryptUin", "encrypt_uin", "encryptedUin")
                .firstNotNullOfOrNull { value[it]?.jsonPrimitive?.contentOrNull }.orEmpty(),
        )
    }

    private fun validate(credential: QqMusicCredential): Boolean = runCatching {
        call("music.UserInfo.userInfoServer", "GetLoginUserInfo", buildJsonObject {}, credential = credential)
    }.isSuccess

    private fun getAnonymousSession(): Pair<String, String> = runCatching {
        val data = call("music.getSession.session", "GetSession", buildJsonObject {
            put("uid", ""); put("vkey", 0); put("caller", 0)
        }) ?: return@runCatching "" to ""
        val session = data["session"]?.jsonObject
        (session?.get("uid")?.jsonPrimitive?.contentOrNull.orEmpty()) to
            (session?.get("sid")?.jsonPrimitive?.contentOrNull.orEmpty())
    }.getOrDefault("" to "")

    internal fun musicuBody(module: String, method: String, param: JsonObject,
        credential: QqMusicCredential? = null, uid: String = "", sid: String = "",
        loginType: Int? = null, ct: Int = 11, cv: Int = 14090008): JsonObject =
        buildJsonObject {
            put("comm", buildJsonObject {
                put("ct", ct)
                put("cv", cv)
                put("v", 14090008)
                put("chid", "10003505")
                put("tmeAppID", "qqmusic")
                put("QIMEI", "")
                put("QIMEI36", QIMEI36)
                put("OpenUDID", DEVICE_ID)
                put("udid", DEVICE_ID)
                put("OpenUDID2", DEVICE_ID)
                put("aid", DEVICE_ID.substring(0, 16))
                put("os_ver", "10")
                put("phonetype", "MI 6")
                put("devicelevel", "29")
                put("newdevicelevel", "29")
                put("rom", "xiaomi/iarim/sagit:10/eomam.200122.001/1234567:user/release-keys")
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                if (uid.isNotBlank()) put("uid", uid)
                if (sid.isNotBlank()) put("sid", sid)
                if (credential != null) {
                    put("qq", credential.uin)
                    put("authst", credential.key)
                    put("tmeLoginType", credential.loginType)
                } else if (loginType != null) {
                    put("tmeLoginType", loginType)
                }
            })
            put("req_0", buildJsonObject {
                put("module", module)
                put("method", method)
                put("param", param)
            })
        }

    private fun call(module: String, method: String, param: JsonObject,
        credential: QqMusicCredential? = null, uid: String = "", sid: String = "",
        loginType: Int? = null, ct: Int = 11, cv: Int = 14090008): JsonObject? {
        val body = musicuBody(module, method, param, credential, uid, sid, loginType, ct, cv)
        val connection = URL(MUSICU_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("User-Agent", CLIENT_UA)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Referer", "https://y.qq.com/")
        if (credential != null) connection.setRequestProperty("Cookie", cookieHeaderOf(credential))
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            ?: return null
        val root = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        if (root["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        val node = root["req_0"]?.jsonObject ?: return null
        if (node["code"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) return null
        return node["data"]?.jsonObject
    }
}

data class QqMusicCredential(
    val uin: String,
    val key: String,
    val loginType: Int,
    val nickname: String = "",
    val avatar: String = "",
    val openId: String = "",
    val refreshToken: String = "",
    val unionId: String = "",
    val refreshKey: String = "",
    val encryptUin: String = "",
)
