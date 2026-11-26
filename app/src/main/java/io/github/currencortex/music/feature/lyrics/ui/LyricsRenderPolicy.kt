package io.github.currencortex.music.feature.lyrics.ui

import io.github.currencortex.music.data.settings.LyricsDisplayOptions
import io.github.currencortex.music.data.settings.LyricsRenderQuality

/** Resolve the saved preference against capabilities that are known before a frame is drawn. */
internal fun effectiveLyricsRenderQuality(selected: LyricsRenderQuality, lowRam: Boolean, sdk: Int): LyricsRenderQuality =
    when (selected) {
        LyricsRenderQuality.AUTO -> if (lowRam || sdk < 31) LyricsRenderQuality.POWER_SAVER else LyricsRenderQuality.BALANCED
        else -> selected
    }

internal fun LyricsDisplayOptions.withRenderQuality(quality: LyricsRenderQuality): LyricsDisplayOptions = when (quality) {
    LyricsRenderQuality.FULL, LyricsRenderQuality.AUTO -> this
    LyricsRenderQuality.BALANCED -> copy(blur = true, glow = true, wordLift = true, smoke = false, particles = false)
    LyricsRenderQuality.POWER_SAVER -> copy(blur = false, glow = false, wordLift = false, smoke = false, particles = false,
        stagger = false, karaokeScope = io.github.currencortex.music.data.settings.KaraokeScope.CURRENT,
        appleMusic = appleMusic.copy(blurStrength = 0f))
}
