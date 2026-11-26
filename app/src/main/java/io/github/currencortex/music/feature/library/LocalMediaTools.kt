// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.currencortex.music.data.song.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class LocalTags(val title: String, val artist: String, val album: String, val lyrics: String,
    val track: String = "", val year: String = "")

object LocalTagEditor {
    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "track.mp3"

    private suspend fun materialize(context: Context, uri: Uri): Pair<File, Boolean> {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            require(file.isFile) { "本地文件不存在" }
            return file to false
        }
        val name = displayName(context, uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File.createTempFile("promx-tag-", "-$name", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
            ?: error("无法读取本地音频")
        return file to true
    }

    private fun read(file: File): LocalTags {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag ?: return LocalTags("", "", "", "")
        fun field(key: FieldKey) = runCatching { tag.getFirst(key).orEmpty() }.getOrDefault("")
        return LocalTags(field(FieldKey.TITLE), field(FieldKey.ARTIST), field(FieldKey.ALBUM),
            field(FieldKey.LYRICS), field(FieldKey.TRACK), field(FieldKey.YEAR))
    }

    suspend fun load(context: Context, song: Song): LocalTags {
        require(song.localUri.isNotBlank()) { "歌曲没有可编辑的本地文件" }
        val (file, temporary) = materialize(context, Uri.parse(song.localUri))
        return try { read(file) } finally { if (temporary) file.delete() }
    }

    suspend fun save(context: Context, song: Song, tags: LocalTags) {
        require(song.localUri.isNotBlank()) { "歌曲没有可编辑的本地文件" }
        val uri = Uri.parse(song.localUri)
        val (file, temporary) = materialize(context, uri)
        try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }
            tag.setField(FieldKey.TITLE, tags.title.trim())
            tag.setField(FieldKey.ARTIST, tags.artist.trim())
            tag.setField(FieldKey.ALBUM, tags.album.trim())
            tag.setField(FieldKey.LYRICS, tags.lyrics)
            if (tags.track.isNotBlank()) tag.setField(FieldKey.TRACK, tags.track)
            if (tags.year.isNotBlank()) tag.setField(FieldKey.YEAR, tags.year)
            AudioFileIO.write(audio)
            if (temporary) {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                    FileInputStream(file).use { it.copyTo(output) }
                } ?: error("系统未授予该歌曲的写入权限")
            }
        } finally {
            if (temporary) file.delete()
        }
    }
}

object LocalAudioFingerprint {
    data class DuplicateGroup(val fingerprint: String, val songs: List<Song>)

    fun fingerprint(context: Context, song: Song): String? = runCatching {
        val uri = Uri.parse(song.localUri)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            while (total < 256 * 1024) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    fun findDuplicates(context: Context, songs: List<Song>): List<DuplicateGroup> =
        songs.mapNotNull { song -> fingerprint(context, song)?.let { it to song } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (fingerprint, matches) -> DuplicateGroup(fingerprint, matches) }
}
