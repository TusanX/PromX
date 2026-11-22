// SPDX-License-Identifier: GPL-3.0-only
// Prom 账号：完全独立的本地账号体系，不依赖任何 CurrentMusic 服务。
// 设计与云同步接口解耦：现在纯本地可用，后续把 syncBaseUrl 指向腾讯云 SCF 即可开启同步。
package io.github.currencortex.music.feature.promaccount

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.security.MessageDigest

data class PromProfile(val uid: String, val name: String, val createdAt: Long)

object PromAccountStore {
    private const val PREF = "prom_account"
    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    val state = mutableStateOf<PromProfile?>(null)

    fun load(c: Context) {
        val p = sp(c)
        val name = p.getString("name", null)
        state.value = if (name.isNullOrBlank()) null else PromProfile(
            uid = p.getString("uid", "") ?: "", name = name, createdAt = p.getLong("createdAt", 0L))
    }

    fun exists(c: Context) = !sp(c).getString("name", null).isNullOrBlank()

    fun hash(salt: String, password: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + password).toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 首次使用即「注册」，之后按昵称+密码校验；密码只存加盐哈希，明文不落盘。 */
    fun loginOrCreate(c: Context, name: String, password: String): Result<PromProfile> {
        val trimmed = name.trim()
        if (trimmed.length < 2) return Result.failure(IllegalArgumentException("昵称至少 2 个字"))
        if (password.length < 4) return Result.failure(IllegalArgumentException("密码至少 4 位"))
        val p = sp(c)
        val savedName = p.getString("name", null)
        if (savedName != null && savedName != trimmed) return Result.failure(IllegalArgumentException("本机已有账号「$savedName」，请用它登录"))
        if (savedName != null) {
            val saved = p.getString("passHash", "") ?: ""
            if (hash(p.getString("salt", "") ?: "", password) != saved) return Result.failure(IllegalArgumentException("密码不对"))
        }
        val uid = p.getString("uid", null)?.takeIf { it.isNotBlank() } ?: "prom-" + System.currentTimeMillis().toString(36)
        val salt = p.getString("salt", null)?.takeIf { it.isNotBlank() } ?: (System.nanoTime().toString(36))
        p.edit().putString("name", trimmed).putString("uid", uid).putString("salt", salt)
            .putString("passHash", hash(salt, password))
            .putLong("createdAt", p.getLong("createdAt", 0L).takeIf { it > 0 } ?: System.currentTimeMillis())
            .apply()
        val profile = PromProfile(uid, trimmed, p.getLong("createdAt", 0L))
        state.value = profile
        return Result.success(profile)
    }

    /** 退出只清会话，保留账号与本地数据。 */
    fun logout(c: Context) {
        sp(c).edit().putBoolean("signedOut", true).apply()
        state.value = null
    }

    fun rename(c: Context, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.length < 2) return false
        sp(c).edit().putString("name", trimmed).apply()
        state.value = state.value?.copy(name = trimmed)
        return true
    }
}

/** 云端登录后写入本地会话（token 与服务地址一起存，重启仍有效） */
fun PromAccountStore.applyRemoteLogin(c: Context, base: String, token: String, name: String) {
    val p = c.getSharedPreferences("prom_account", Context.MODE_PRIVATE)
    val uid = "prom-" + java.util.UUID.nameUUIDFromBytes(token.toByteArray()).toString().take(8)
    p.edit().putString("name", name).putString("uid", uid)
        .putString("token", token).putString("base", base).apply()
    PromServerConfig.set(c, base)
    state.value = PromProfile(uid, name, System.currentTimeMillis())
}
