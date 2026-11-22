// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐原生扫码登录：CreateQRCode → MQTT 5 状态推送 → LoginServer 凭证交换。
// 兼容旧网页 Cookie 刷新，但新扫码不再经过 QQ 互联网页登录。
package io.github.currencortex.music.core.alt

import java.net.HttpURLConnection
import java.net.URL

object QqAuth {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    /** 一次原生 QQ 音乐扫码会话。旧网页协议方法保留用于兼容。 */
    class QrSession internal constructor(val image: ByteArray, internal val native: QqNativeAuth.Session,
        val expiresIn: Int) {
        fun close() = native.close()
    }

    enum class Poll { WAITING, SCANNED, EXPIRED, SUCCESS, UNKNOWN }

    data class PollResult(val state: Poll, val message: String = "", val redirect: String = "",
        val cookies: Map<String, String> = emptyMap(), val credential: QqMusicCredential? = null)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://xui.ptlogin2.qq.com/")
            connectTimeout = 12000; readTimeout = 15000
        }

    fun newQr(): QrSession? = runCatching {
        val qr = QqNativeAuth.createQr() ?: return null
        val listener = QqNativeAuth.listen(qr) ?: return null
        QrSession(qr.image, listener, qr.expiresIn)
    }.getOrNull()

    /** 用 skey/p_skey 换 qm_keyst（QQ 音乐自己的登录态） */
    fun exchangeKeyst(cookies: Map<String, String>, uin: String): Map<String, String> = runCatching {
        val pskey = cookies["p_skey"] ?: cookies["skey"] ?: return@runCatching cookies
        val gtk = gTk(pskey)
        fun attempt(param: String): String? {
            val body = "{\"comm\":{\"ct\":24,\"cv\":0,\"uin\":\"" + uin + "\",\"format\":\"json\"},\"req\":{" + param + "}}"
            val c = open("https://u.y.qq.com/cgi-bin/musicu.fcg").apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cookie", cookies.entries.joinToString("; ") { it.key + "=" + it.value })
                setRequestProperty("Referer", "https://y.qq.com/")
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return Regex("\"qm_keyst\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?: Regex("\"musickey\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        }
        // 两种参数形态都试：不同版本 QQ 音乐要求的字段略有差异
        val key = attempt("\"module\":\"music.login.LoginServer\",\"method\":\"Login\",\"param\":{\"uin\":\"" + uin + "\",\"musicid\":" + uin + ",\"musickey\":\"\",\"refresh_token\":\"\",\"loginType\":1,\"g_tk\":" + gtk + "}")
            ?: attempt("\"module\":\"music.login.LoginServer\",\"method\":\"Login\",\"param\":{\"uin\":\"" + uin + "\",\"refresh_token\":\"\",\"g_tk\":" + gtk + "}")
        if (key.isNullOrBlank()) cookies else cookies + mapOf("qm_keyst" to key)
    }.getOrDefault(cookies)

    /**
     * 用 QQ 互联的 skey 换 qm_keyst（QQ 音乐自己的登录态）。
     * 走 ptlogin2 的 jump 接口，它会依次下发 qm_keyst / qqmusic_key / uin 等 Cookie。
     * 会员曲的 vkey 必须有这个 key，否则只能拿到免费试听。
     */
    fun ssoKeyst(cookies: Map<String, String>, uin: String): Map<String, String> = runCatching {
        val clientKey = cookies["p_skey"] ?: cookies["skey"] ?: return@runCatching cookies
        if (cookies["qm_keyst"] != null || cookies["qqmusic_key"] != null) return@runCatching cookies
        val collected = cookies.toMutableMap()
        var url = "https://ptlogin2.qq.com/jump?ptlang=2052&clientuin=$uin&clientkey=$clientKey" +
            "&u1=" + java.net.URLEncoder.encode("https://y.qq.com/portal/profile.html", "UTF-8")
        var hops = 0
        while (hops < 8) {
            hops++
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                setRequestProperty("Cookie", collected.entries.joinToString("; ") { it.key + "=" + it.value })
                setRequestProperty("Referer", "https://y.qq.com/")
                connectTimeout = 12_000; readTimeout = 15_000
            }
            c.headerFields["Set-Cookie"].orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                val idx = pair.indexOf('=')
                if (idx > 0) collected[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
            }
            val location = c.getHeaderField("Location") ?: break
            if (c.responseCode !in 300..399) break
            url = if (location.startsWith("http")) location
            else java.net.URI(url).resolve(location).toString()
            if (collected["qm_keyst"] != null || collected["qqmusic_key"] != null) break
        }
        collected
    }.getOrDefault(cookies)

    /** A membership check can arrive after the saved music key expired. Refresh it once before accepting a preview. */
    fun refreshKeyst(cookies: Map<String, String>, uin: String): Map<String, String> = runCatching {
        if (uin.isBlank()) return@runCatching cookies
        val withoutMusicKey = cookies.filterKeys { it != "qm_keyst" && it != "qqmusic_key" }
        val refreshed = exchangeKeyst(ssoKeyst(withoutMusicKey, uin), uin)
        val newKey = refreshed["qqmusic_key"] ?: refreshed["qm_keyst"].orEmpty()
        val oldKey = cookies["qqmusic_key"] ?: cookies["qm_keyst"].orEmpty()
        if (newKey.isBlank() || newKey == oldKey) cookies else cookies + mapOf(
            "qm_keyst" to newKey,
            "qqmusic_key" to newKey,
        )
    }.getOrDefault(cookies)

    fun refreshSession(session: QqSessionStore): Map<String, String> {
        val refreshed = session.nativeCredential?.let(QqNativeAuth::refresh)
        if (refreshed != null) {
            session.saveCredential(refreshed)
            return session.cookies
        }
        val cookies = refreshKeyst(session.cookies, session.uin)
        if (cookies != session.cookies) session.cookies = cookies
        return cookies
    }

    /** 从 Cookie 里解析 QQ 号：uin/p_uin 形如 o0123456789，需去掉前缀 o */
    fun resolveUin(cookies: Map<String, String>): String {
        val candidates = listOf("uin", "p_uin", "qqmusic_uin", "ptui_loginuin", "qm_uin", "wxuin", "luin")
        for (name in candidates) {
            val raw = cookies[name] ?: continue
            val digits = raw.removePrefix("o").removePrefix("O").filter { it.isDigit() }
            if (digits.isNotBlank()) return digits
        }
        return ""
    }

    /** QQ 音乐凭据返回的 QQ 账号类型为 2，微信账号为 1。 */
    fun loginType(cookies: Map<String, String>): Int {
        cookies["tmeLoginType"]?.toIntOrNull()?.takeIf { it in 1..8 }?.let { return it }
        val wechat = cookies.keys.any { it == "wxuin" || it == "wxopenid" || it == "wxrefresh_token" }
        return if (wechat && cookies.keys.none { it == "uin" || it == "p_uin" || it == "qqmusic_uin" }) 1 else 2
    }

    /** QQ 的 bkn/g_tk 算法 */
    fun gTk(skey: String): Long {
        var hash = 5381L
        for (ch in skey) hash += (hash shl 5) + ch.code
        return hash and 0x7FFFFFFFL
    }

    fun poll(session: QrSession): PollResult = runCatching {
        val event = session.native.nextEvent(1_200) ?: return PollResult(Poll.WAITING)
        when (event.type) {
            "waiting" -> PollResult(Poll.WAITING)
            "scanned", "confirmed" -> PollResult(Poll.SCANNED, "已扫码，请在手机上确认")
            "cookies", "authorized" -> {
                val credential = QqNativeAuth.exchangeCredential(session.native, event)
                if (credential == null) PollResult(Poll.UNKNOWN, "QQ 音乐授权信息交换失败")
                else PollResult(Poll.SUCCESS, "登录成功", cookies = QqNativeAuth.cookiesOf(credential),
                    credential = credential).also { session.close() }
            }
            "timeout", "canceled", "loginFailed" ->
                PollResult(Poll.EXPIRED, "二维码已失效或登录已取消").also { session.close() }
            else -> PollResult(Poll.WAITING)
        }
    }.getOrElse { PollResult(Poll.UNKNOWN, it.message ?: "网络错误") }

}
