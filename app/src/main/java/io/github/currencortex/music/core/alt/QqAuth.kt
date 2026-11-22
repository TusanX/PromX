// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐扫码登录：走 QQ 互联的 ptlogin 二维码流程（与网页版一致）。
// 流程：ptqrshow 拿二维码图片 + qrsig → ptqrtoken=hash33(qrsig) → 轮询 ptqrlogin。
package io.github.currencortex.music.core.alt

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QqAuth {
    private const val APPID = "716027609"
    private const val DAID = "383"
    private const val THIRD_AID = "100497308"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    /** 一次扫码会话：二维码字节、cookie（qrsig）与派生 token */
    data class QrSession(val image: ByteArray, val cookies: Map<String, String>, val token: Long)

    enum class Poll { WAITING, SCANNED, EXPIRED, SUCCESS, UNKNOWN }

    data class PollResult(val state: Poll, val message: String = "", val redirect: String = "", val cookies: Map<String, String> = emptyMap())

    /** hash33：QQ 的 ptqrtoken 算法 */
    private fun hash33(s: String): Long {
        var e = 0L
        for (c in s) e = (e shl 5) + e + c.code
        return e and 0x7FFFFFFFL
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://xui.ptlogin2.qq.com/")
            connectTimeout = 12000; readTimeout = 15000
        }

    fun newQr(): QrSession? = runCatching {
        val t = System.currentTimeMillis()
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=$APPID&e=2&l=M&s=3&d=72&v=4&t=$t&daid=$DAID&pt_3rd_aid=$THIRD_AID"
        val c = open(url)
        val bytes = c.inputStream.use { it.readBytes() }
        val cookies = mutableMapOf<String, String>()
        c.headerFields.forEach { (k, v) ->
            if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                val pair = raw.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty()) cookies[name] = value
            }
        }
        val qrsig = cookies["qrsig"] ?: return null
        QrSession(bytes, cookies, hash33(qrsig))
    }.getOrNull()

    /** 扫码成功后跟随后续跳转，抓取 skey/p_skey/uin 等 Cookie */
    fun completeLogin(session: QrSession, redirect: String): Map<String, String> = runCatching {
        val cookie = session.cookies.toMutableMap()
        var url = redirect
        var hops = 0
        while (hops < 10 && url.isNotBlank()) {
            hops++
            val c = open(url)
            c.setRequestProperty("Cookie", cookie.entries.joinToString("; ") { it.key + "=" + it.value })
            c.instanceFollowRedirects = false
            val code = runCatching { c.responseCode }.getOrDefault(0)
            c.headerFields.forEach { (k, v) ->
                if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookie[name] = value
                }
            }
            val location = c.getHeaderField("Location")
            runCatching { c.inputStream?.close() }
            url = when {
                location.isNullOrBlank() || code !in 300..399 -> ""
                location.startsWith("http") -> location
                location.startsWith("//") -> "https:$location"
                else -> "https://ssl.ptlogin2.qq.com/" + location
            }
        }
        // 关键：落地到 y.qq.com 领取 QQ 音乐的会话 Cookie（qm_keyst 只在这个域下发）
        for (seed in listOf("https://y.qq.com/", "https://y.qq.com/n/ryqq/profile")) {
            runCatching {
                val c = open(seed)
                c.setRequestProperty("Cookie", cookie.entries.joinToString("; ") { it.key + "=" + it.value })
                c.instanceFollowRedirects = true
                runCatching { c.inputStream?.close() }
                c.headerFields.forEach { (k, v) ->
                    if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                        val pair = raw.substringBefore(';')
                        val name = pair.substringBefore('=').trim()
                        val value = pair.substringAfter('=', "").trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) cookie[name] = value
                    }
                }
            }
        }
        cookie
    }.getOrDefault(session.cookies)

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

    /** QQ 的 bkn/g_tk 算法 */
    fun gTk(skey: String): Long {
        var hash = 5381L
        for (ch in skey) hash += (hash shl 5) + ch.code
        return hash and 0x7FFFFFFFL
    }

    fun poll(session: QrSession): PollResult = runCatching {
        val ts = System.currentTimeMillis()
        val u1 = URLEncoder.encode("https://graph.qq.com/oauth2.0/login?response_type=code&client_id=100497308", "UTF-8")
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=$u1&ptqrtoken=${session.token}" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-$ts&js_ver=10233&js_type=1" +
            "&login_sig=&pt_uistyle=40&aid=$APPID&daid=$DAID&pt_3rd_aid=$THIRD_AID&has_onekey=1"
        val c = open(url)
        c.setRequestProperty("Cookie", session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val body = c.inputStream.bufferedReader().use { it.readText() }
        // ptuiCB('65','0','','0','二维码已失效', '')
        val fields = Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
        val code = fields.getOrNull(0) ?: ""
        val msg = fields.getOrNull(4) ?: ""
        val redirect = fields.getOrNull(2) ?: ""
        val newCookies = mutableMapOf<String, String>()
        c.headerFields.forEach { (k, v) ->
            if (k.equals("Set-Cookie", ignoreCase = true)) v.forEach { raw ->
                val pair = raw.substringBefore(';')
                newCookies[pair.substringBefore('=').trim()] = pair.substringAfter('=', "").trim()
            }
        }
        when (code) {
            "66" -> PollResult(Poll.WAITING, msg.ifBlank { "等待扫码" })
            "67" -> PollResult(Poll.SCANNED, msg.ifBlank { "已扫码，请在手机上确认" })
            "65" -> PollResult(Poll.EXPIRED, msg.ifBlank { "二维码已失效" })
            "0" -> PollResult(Poll.SUCCESS, msg, redirect, newCookies)
            else -> PollResult(Poll.UNKNOWN, msg)
        }
    }.getOrElse { PollResult(Poll.UNKNOWN, it.message ?: "网络错误") }
}
