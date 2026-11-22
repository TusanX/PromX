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

    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 当前登录 uin（会员曲 vkey 必填） */
    val uin: String get() = account?.uin?.takeIf(String::isNotBlank)
        ?: QqAuth.resolveUin(cookies)

    fun clear() = prefs.edit().clear().apply()
}
