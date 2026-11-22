// SPDX-License-Identifier: GPL-3.0-only
// Prom 账号云端接口（腾讯云 SCF 契约）：不配置地址就用本机账号，配置了就切云端。
package io.github.currencortex.music.feature.promaccount

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object PromServerConfig {
    /** 已部署的 Prom 账号服务（腾讯云 SCF 函数 URL）。留空则用本机账号。 */
    const val DEFAULT_BASE = "https://1522632969-6f39qidpa3.ap-guangzhou.tencentscf.com"
    private const val PREF = "prom_server"
    fun base(c: Context): String {
        val saved = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("base", null)
        return (saved ?: DEFAULT_BASE).trimEnd('/')
    }
    fun set(c: Context, base: String) =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("base", base.trim().trimEnd('/')).apply()
}

object PromRemoteApi {
    private fun call(base: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val c = URL("$base$path").openConnection() as HttpURLConnection
        c.requestMethod = if (body == null) "GET" else "POST"
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("content-type", "application/json; charset=utf-8")
        token?.let { c.setRequestProperty("authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"
        return JSONObject(text)
    }

    /** 邮箱 + 密码：register=true 走注册，false 走登录 */
    fun loginEmail(base: String, email: String, password: String, register: Boolean): Result<Pair<String, String>> = runCatching {
        val path = if (register) "/auth/register" else "/auth/login-email"
        val r = call(base, path, JSONObject().put("email", email).put("password", password), null)
        val token = r.optString("token")
        if (token.isBlank()) error(r.optString("error", "登录失败"))
        token to (r.optJSONObject("user")?.optString("name") ?: email.substringBefore('@'))
    }

    fun sendCode(base: String, phone: String): Result<Unit> = runCatching {
        val r = call(base, "/auth/send-code", JSONObject().put("phone", phone), null)
        if (!r.optBoolean("ok")) error(r.optString("error", "发送失败"))
    }

    /** 返回 token + 昵称 */
    fun login(base: String, phone: String, code: String): Result<Pair<String, String>> = runCatching {
        val r = call(base, "/auth/login", JSONObject().put("phone", phone).put("code", code), null)
        val token = r.optString("token"); if (token.isBlank()) error(r.optString("error", "登录失败"))
        token to (r.optJSONObject("user")?.optString("name") ?: "Prom 用户")
    }

    fun push(base: String, token: String, data: JSONObject): Result<Unit> = runCatching {
        call(base, "/sync/push", JSONObject().put("data", data), token); Unit
    }
    fun pull(base: String, token: String): Result<JSONObject> = runCatching {
        call(base, "/sync/pull", null, token).optJSONObject("data") ?: JSONObject()
    }
}
