// SPDX-License-Identifier: GPL-3.0-only
// B站 本地缓存：列出已下载的视频（Movies/PromX）与音频缓存目录里的文件。
package io.github.currencortex.music.feature.bili

import android.content.Context
import android.os.Environment
import java.io.File

data class BiliCacheItem(
    val file: File?,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val isVideo: Boolean,
)

object BiliCacheStore {
    /** 已下载的 B站 视频：统一在 App 目录，播放走 file://（content:// 交给 OkHttp 会报 Source error） */
    fun videos(context: Context): List<BiliCacheItem> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PromX")
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.map { file ->
                BiliCacheItem(file, "file://" + file.absolutePath, file.name, file.length(),
                    file.lastModified(), true)
            }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    /** PromX 的音频缓存目录（边听边缓存），只展示已经完整的文件 */
    fun audios(context: Context): List<BiliCacheItem> = runCatching {
        val root = File(context.cacheDir, "audio")
        val files = root.walkTopDown().filter { it.isFile && it.length() > 64 * 1024 }.toList()
        files.map { file ->
            BiliCacheItem(file, file.absolutePath, file.name, file.length(), file.lastModified(), false)
        }.sortedByDescending { it.addedAt }.take(200)
    }.getOrDefault(emptyList())

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
