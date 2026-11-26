package io.github.currencortex.music.core.media

import io.github.currencortex.music.core.network.ApiJson
import io.github.currencortex.music.data.song.AudioSource
import kotlinx.serialization.Serializable

/** Public playback metadata only: never place signed URLs or credentials in a media session. */
@Serializable data class AudioStreamInfo(
    val label: String,
    val matched: Boolean = false,
    val format: String = "",
    val bitrate: Long = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val source: String = "",
) {
    val isPreview get() = label == "试听" || source == "qq-preview"
    val isComplete get() = !isPreview
    val providerLabel get() = when (source) {
        "qq", "qq-preview" -> "QQ 音乐"
        "bilibili", "bilibili-cache", "bilibili-video" -> "哔哩哔哩"
        "local", "cached" -> "本地音乐"
        "emby" -> "Emby"
        "navidrome" -> "Navidrome"
        "lx", "lx-source" -> "落雪音源"
        "leiz", "leiz-audio" -> "备用音源"
        "netease-match" -> "匹配音源"
        "video" -> "MV"
        "radio-browser" -> "公开电台"
        else -> ""
    }
    val disclosure: String? get() = when {
        isPreview -> "这是官方试听片段。完整音轨仍由对应音乐服务决定，试听不会写入完整歌曲缓存。"
        matched -> "官方音源暂不可用，正在播放匹配音源。版本与音质以实际文件为准。"
        source == "bilibili" || source == "bilibili-cache" || source == "bilibili-video" ->
            "当前音频来自哔哩哔哩公开视频音轨。版本、时长和音质以实际视频为准。"
        source == "local" || source == "cached" -> "当前音频来自本机文件，不依赖网易云或 QQ 音乐会员状态。"
        source == "emby" || source == "navidrome" -> "当前音频来自你连接的自建音乐服务器。"
        source == "radio-browser" -> "当前播放的是 Radio Browser 索引的公开网络电台，节目内容与可用性由电台提供方决定。"
        else -> null
    }
    val summary get() = listOfNotNull(
        providerLabel.takeIf { it.isNotBlank() },
        label,
        format.uppercase().takeIf { it.isNotBlank() },
        bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        sampleRate.takeIf { it > 0 }?.let { "$it Hz" }, channels.takeIf { it > 0 }?.let { "$it 声道" })
        .distinct()
        .joinToString(" · ")
    companion object {
        fun from(source: AudioSource) = AudioStreamInfo(
            label = source.qualityLabel,
            matched = source.matched,
            format = source.format,
            bitrate = source.bitrate,
            sampleRate = source.sampleRate,
            channels = source.channelCount,
            source = source.source,
        )
        fun decode(value: String?): AudioStreamInfo? = value?.let {
            runCatching { ApiJson.decodeFromString(serializer(), it) }.getOrNull()
        }
    }
}
