// SPDX-License-Identifier: GPL-3.0-only
// B站 视频下载：取 html5 mp4（含画面与声音），流式写入 Movies 目录。
package io.github.currencortex.music.feature.bili

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.currencortex.music.core.alt.BiliPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BiliDownloader {
    sealed interface Result {
        data class Ok(val path: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun download(
        context: Context,
        bvid: String,
        title: String,
        cookie: String,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            val info = BiliPlayback.videoInfo(bvid, cookie) ?: return@runCatching Result.Failed("取不到视频信息")
            val plan = BiliPlayback.plan(bvid, info.cid, 80, cookie)
            // 优先整段 mp4（带画面）；拿不到就退音频轨，至少保证有可用缓存
            val audioOnly = plan?.mp4.isNullOrBlank()
            val resolved = if (audioOnly) BiliPlayback.resolve(bvid, info.cid, cookie) else null
            val url = plan?.mp4 ?: resolved?.url
                ?: return@runCatching Result.Failed("这条视频没有可下载的源")
            val ext = if (audioOnly) "m4a" else "mp4"
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60).ifBlank { bvid }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                setRequestProperty("Referer", "https://www.bilibili.com/video/$bvid")
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                connectTimeout = 15_000; readTimeout = 30_000
            }
            val total = conn.contentLengthLong
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PromX").apply { mkdirs() }
            val file = File(dir, "$safe.$ext")
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var read: Long = 0
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        out.write(buffer, 0, n); read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            Result.Ok(file.absolutePath)
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }
}
