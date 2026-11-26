// SPDX-License-Identifier: GPL-3.0-only
// QQ 音乐登录态：扫码成功后要跟随后续跳转拿到 skey/p_skey，再换取 qm_keyst，
// 之后所有「需要登录」的接口（歌单/我喜欢/每日推荐/会员曲取流）都靠这套 Cookie。
package io.github.currencortex.music.core.alt

import android.content.Context

data class QqAccount(val uin: String, val nickname: String = "", val avatar: String = "") {
    val display get() = if (nickname.isBlank()) "QQ $uin" else nickname
}

class QqSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prom_qq_session", Context.MODE_PRIVATE)

    var cookies: Map<String, String>
        get() = prefs.getString("cookies", null)
            ?.split('\n')
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap() ?: emptyMap()
        set(value) { prefs.edit().putString("cookies", value.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply() }

    var account: QqAccount?
        get() = prefs.getString("uin", null)?.let { uin ->
            QqAccount(uin, prefs.getString("nickname", "").orEmpty(), prefs.getString("avatar", "").orEmpty())
        }
        set(value) = prefs.edit().apply {
            putString("uin", value?.uin)
            putString("nickname", value?.nickname)
            putString("avatar", value?.avatar)
        }.apply()

    val loggedIn get() = uin.isNotBlank() && cookies.isNotEmpty()

    val nativeAuthenticated get() = prefs.getBoolean("native_auth", false)

    val needsNativeLogin get() = loggedIn && !nativeAuthenticated

    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 当前登录 uin（会员曲 vkey 必填） */
    val uin: String get() = account?.uin?.takeIf(String::isNotBlank)
        ?: QqAuth.resolveUin(cookies)

    val loginType: Int get() = QqAuth.loginType(cookies)

    /** Cache key scope changes whenever account or music key changes. */
    val authScope: String get() {
        val musicKey = cookies["qqmusic_key"] ?: cookies["qm_keyst"].orEmpty()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$uin\n$musicKey".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }.take(20)
    }

    val nativeCredential: QqMusicCredential? get() {
        if (!nativeAuthenticated) return null
        val key = cookies["qqmusic_key"] ?: cookies["qm_keyst"].orEmpty()
        if (uin.isBlank() || key.isBlank()) return null
        return QqMusicCredential(
            uin = uin,
            key = key,
            loginType = loginType,
            nickname = account?.nickname.orEmpty(),
            avatar = account?.avatar.orEmpty(),
            openId = prefs.getString("openid", "").orEmpty(),
            refreshToken = prefs.getString("refresh_token", "").orEmpty(),
            unionId = prefs.getString("unionid", "").orEmpty(),
            refreshKey = prefs.getString("refresh_key", "").orEmpty(),
            encryptUin = cookies["encrypt_uin"].orEmpty(),
        )
    }

    fun saveCredential(credential: QqMusicCredential) {
        cookies = QqNativeAuth.cookiesOf(credential)
        account = QqAccount(credential.uin, credential.nickname, credential.avatar)
        prefs.edit()
            .putBoolean("native_auth", true)
            .putString("openid", credential.openId)
            .putString("refresh_token", credential.refreshToken)
            .putString("unionid", credential.unionId)
            .putString("refresh_key", credential.refreshKey)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
