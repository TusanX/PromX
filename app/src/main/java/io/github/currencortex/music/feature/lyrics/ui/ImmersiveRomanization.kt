package io.github.currencortex.music.feature.lyrics.ui

import io.github.currencortex.music.feature.lyrics.model.LyricLine
import io.github.currencortex.music.feature.lyrics.model.LyricWord
import io.github.currencortex.music.feature.lyrics.model.LyricsDocument

/** Replaces the vocal text while keeping the original cue, translations and word-motion timing. */
internal fun LyricsDocument.withImmersiveRomanization(): LyricsDocument =
    copy(lines = lines.map(LyricLine::withImmersiveRomanization))

private fun LyricLine.withImmersiveRomanization(): LyricLine {
    val renderedBackgroundVocals = backgroundVocals.map(LyricLine::withImmersiveRomanization)
    if (text.isBlank()) return copy(backgroundVocals = renderedBackgroundVocals)

    val rendered = romanizedWordsFromTimingWords() ?: romanizedWordsFromLine()
        ?: return copy(backgroundVocals = renderedBackgroundVocals)
    return copy(
        text = rendered.text,
        words = rendered.words,
        romanization = "",
        romanizations = emptyList(),
        ruby = emptyList(),
        wordsEstimated = false,
        backgroundVocals = renderedBackgroundVocals,
    )
}

private data class RomanizedLine(val text: String, val words: List<LyricWord>)
private data class TimedRomanization(val text: String, val source: LyricWord)

private fun LyricLine.romanizedWordsFromTimingWords(): RomanizedLine? {
    if (words.isEmpty()) return null
    val pieces = mutableListOf<TimedRomanization>()
    words.forEach { word ->
        val reading = word.romanization.trim()
        val punctuation = word.text.trim().takeIf { value ->
            value.isNotEmpty() && value.none(Char::isLetterOrDigit)
        }.orEmpty()
        val rendered = reading.takeIf(String::isNotEmpty) ?: punctuation
        when {
            rendered.isNotEmpty() -> pieces += TimedRomanization(rendered, word)
            word.text.any(Char::isLetterOrDigit) -> return null
        }
    }
    if (pieces.none { it.text.any(Char::isLetterOrDigit) }) return null

    val output = StringBuilder()
    val renderedWords = mutableListOf<LyricWord>()
    var previousSourceEnd = 0
    val safeLineEnd = endTimeMs.coerceAtLeast(startTimeMs)
    pieces.forEach { piece ->
        val sourceWord = piece.source
        if (output.isNotEmpty()) {
            val gapStart = previousSourceEnd.coerceIn(0, text.length)
            val gapEnd = sourceWord.startOffset.coerceIn(gapStart, text.length)
            if (text.substring(gapStart, gapEnd).any(Char::isWhitespace)) output.append(' ')
        }
        val startOffset = output.length
        output.append(piece.text)
        renderedWords += LyricWord(
            text = piece.text,
            startTimeMs = sourceWord.startTimeMs.coerceIn(startTimeMs, safeLineEnd),
            endTimeMs = sourceWord.endTimeMs.coerceIn(startTimeMs, safeLineEnd),
            startOffset = startOffset,
            endOffset = output.length,
            agent = sourceWord.agent,
            roles = sourceWord.roles,
        )
        previousSourceEnd = sourceWord.endOffset
    }
    return RomanizedLine(output.toString(), renderedWords)
}

private fun LyricLine.romanizedWordsFromLine(): RomanizedLine? {
    val reading = romanization.trim().ifBlank {
        romanizations.firstOrNull { it.text.isNotBlank() }?.text.orEmpty().trim()
    }
    if (reading.isEmpty()) return null

    val tokens = Regex("\\S+").findAll(reading).map { it.range.first to it.value }.toList()
    if (tokens.isEmpty()) return null
    val duration = (endTimeMs - startTimeMs).coerceAtLeast(0)
    val totalWeight = tokens.sumOf { it.second.length.coerceAtLeast(1) }
    var consumedWeight = 0
    val renderedWords = tokens.map { (offset, token) ->
        val tokenWeight = token.length.coerceAtLeast(1)
        val tokenStart = startTimeMs + duration * consumedWeight / totalWeight
        consumedWeight += tokenWeight
        val tokenEnd = startTimeMs + duration * consumedWeight / totalWeight
        LyricWord(
            text = token,
            startTimeMs = tokenStart,
            endTimeMs = tokenEnd.coerceAtLeast(tokenStart),
            startOffset = offset,
            endOffset = offset + token.length,
        )
    }
    return RomanizedLine(reading, renderedWords)
}
