package io.github.currencortex.music.core.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import io.github.currencortex.music.data.song.Song

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaItemFactory {
    fun create(song: Song, url: String, cacheKey: String? = null, source: io.github.currencortex.music.data.song.AudioSource? = null): MediaItem {
        val builder = MediaItem.Builder().setMediaId(song.id.toString()).setUri(url)
            // A DASH MediaItem expands into manifest and segment requests. Reusing the single-track
            // audio cache key makes CacheDataSource try to open the inline data: MPD with OkHttp.
            // Let DefaultDataSource handle the manifest and let child requests use their own URIs.
            .setCustomCacheKey(cacheKey.takeUnless { source?.format == "dash" })
        if (source?.format == "dash") builder.setMimeType(MimeTypes.APPLICATION_MPD)
        return builder.setMediaMetadata(MediaMetadata.Builder().setExtras(source?.let { android.os.Bundle().apply {
            putString("cm.audio.info", io.github.currencortex.music.core.network.ApiJson.encodeToString(
                AudioStreamInfo.serializer(), AudioStreamInfo.from(it)))
        } }).setTitle(song.name).setArtist(song.artists).setAlbumTitle(song.album)
            .setArtworkUri(song.cover.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()).build()
    }
}
