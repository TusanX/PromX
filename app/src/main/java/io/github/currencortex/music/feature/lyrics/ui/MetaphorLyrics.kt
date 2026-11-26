package io.github.currencortex.music.feature.lyrics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.data.settings.LyricsDisplayOptions
import io.github.currencortex.music.data.settings.LyricsRenderQuality
import io.github.currencortex.music.data.settings.LyricsTypography
import io.github.currencortex.music.data.settings.LyricsWeight
import io.github.currencortex.music.feature.lyrics.domain.LyricsSynchronizer
import io.github.currencortex.music.feature.lyrics.model.LyricLine
import io.github.currencortex.music.feature.lyrics.model.LyricWord
import io.github.currencortex.music.feature.lyrics.model.LyricsDocument
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class MetaphorUnit(val text: String, val start: Long, val end: Long, val emphasis: Boolean)

/**
 * A native Compose interpretation of Folia's Metaphor lyric style. It keeps the visual language
 * of ink, halftone paper, grain and hard-cut camera moves, while using the same playback cursor,
 * word timings, translations, romanization and seek contract as every other lyric renderer.
 */
@Composable internal fun MetaphorLyrics(document: LyricsDocument, position: State<Long>, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, canSeek: Boolean = true, translation: Boolean = true,
    romanization: Boolean = false, wordAnimation: Boolean = true, fontSize: Float = 30f,
    weightMode: LyricsWeight = LyricsWeight.CURRENT, lyricsOffsetMs: Long = 0,
    display: LyricsDisplayOptions = LyricsDisplayOptions(),
    cover: String = "", minimal: Boolean = false) {
    val timeline = remember(document) { LyricsSynchronizer(document) }
    val effectivePosition = remember(position, lyricsOffsetMs) { derivedStateOf {
        LyricsSynchronizer.lyricPosition(position.value, lyricsOffsetMs)
    } }
    val target by remember(timeline, effectivePosition) { derivedStateOf {
        timeline.scrollTarget(effectivePosition.value)
    } }
    val line = document.lines.getOrNull(target)
    val now = effectivePosition.value
    val palette = rememberLyricsSmokeColors(cover)
    val fullSize = LyricsTypography.normalize(fontSize)
    val heroSize = remember(fullSize, line?.text?.length ?: 0, minimal) {
        val pressure = ((line?.text?.length ?: 0) / 18f).coerceIn(0f, 1.35f)
        (fullSize * (1f - pressure * .18f) * if (minimal) .86f else 1f).coerceIn(19f, fullSize)
    }
    val lowPower = display.renderQuality == LyricsRenderQuality.POWER_SAVER
    val systemMotion = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    val reducedMotion = !display.metaphorMotion || lowPower || !systemMotion
    val ink = Color(0xFFF4F0E8)
    val accent = if (display.metaphorKeywordColors)
        palette?.secondary ?: Color(0xFF89B9AF) else ink
    val progress = line?.let { cue ->
        ((now - cue.startTimeMs).toFloat() / (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L))
            .coerceIn(0f, 1f)
    } ?: 0f

    Box(modifier.fillMaxSize().testTag("lyrics_metaphor_panel"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val shot = if (line == null) 0L else ((now - line.startTimeMs).coerceAtLeast(0L) / 1_850L)
            drawFoliaInkField(seed = shot.hashCode() xor (line?.text?.hashCode() ?: 0),
                timeSeconds = now / 1000f, progress = progress, ink = ink, accent = accent,
                paper = palette?.primary ?: Color(0xFF9A9B95), lowPower = lowPower,
                reducedMotion = reducedMotion, halftone = display.metaphorHalftone,
                grain = display.metaphorGrain, quality = display.renderQuality)
            drawEditorialMarks(ink, accent, progress)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (document.lines.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(if (document.metadata.instrumental == true) "纯音乐" else "暂无歌词",
                        style = TextStyle(color = ink, fontSize = heroSize.sp,
                            fontWeight = lyricsFontWeight(weightMode, true, display.fontWeight),
                            fontFamily = currentLyricsFontFamily(), textAlign = TextAlign.Center),
                        modifier = Modifier.testTag("lyrics_metaphor_empty"))
                    BasicText(if (document.metadata.instrumental == true) "静听此刻" else "仍可继续播放，稍后可在歌词管理中匹配歌词",
                        style = TextStyle(color = ink.copy(alpha = .52f), fontSize = 13.sp,
                            textAlign = TextAlign.Center, fontFamily = currentLyricsFontFamily()))
                }
            } else if (line == null) {
                BasicText("·  ·  ·", Modifier.align(Alignment.Center).testTag("lyrics_metaphor_interlude"),
                    style = TextStyle(color = ink.copy(alpha = .55f), fontSize = 24.sp,
                        letterSpacing = 4.sp, textAlign = TextAlign.Center))
            } else {
                MetaphorCompositionCanvas(line, now, heroSize, weightMode, display, ink, accent,
                    canSeek, reducedMotion, onSeek, Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = if (minimal) 32.dp else 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (display.translation && line.translation.isNotBlank()) {
                        MetaphorAuxiliary(line.translation, position, "lyrics_metaphor_translation", heroSize, .68f)
                    }
                    if (display.romanization && line.romanization.isNotBlank()) {
                        MetaphorAuxiliary(line.romanization, position, "lyrics_metaphor_romanization", heroSize, .52f)
                    }
                }
            }
            if (!minimal) {
                val next = document.lines.getOrNull(target + 1)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                    .padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                    if (next != null) BasicText(next.text, Modifier.graphicsLayer { alpha = .30f }
                        .clickable(enabled = canSeek, role = Role.Button) { onSeek(next.startTimeMs) }
                        .padding(horizontal = 8.dp).testTag("lyrics_metaphor_next"),
                        style = TextStyle(color = ink, fontSize = (heroSize * .42f).coerceAtLeast(11f).sp,
                            textAlign = TextAlign.Center, fontFamily = currentLyricsFontFamily()),
                        maxLines = 1)
                }
            }
        }
    }
}

private enum class MetaphorComposition { BLOCK, STAIR, HERO, COLUMN, SCATTER, JUSTIFY }
private enum class MetaphorShotKind { WIDE, CLOSE, FOLLOW, MACRO, DUTCH, VOID, PULLBACK, PUSHIN }
private data class MeasuredMetaphorUnit(val unit: MetaphorUnit, val layout: TextLayoutResult)

private fun hash01(x: Int, y: Int, z: Int): Float {
    var value = x * 374761393 + y * 668265263 + z * 2147483647
    value = (value xor (value ushr 13)) * 1274126177
    return ((value xor (value ushr 16)) and 0x7fffffff) / 2147483647f
}

private fun DrawScope.drawFoliaInkField(seed: Int, timeSeconds: Float, progress: Float,
    ink: Color, accent: Color, paper: Color, lowPower: Boolean, reducedMotion: Boolean,
    halftone: Boolean, grain: Boolean, quality: LyricsRenderQuality) {
    drawRect(Color(0xFF050607))
    val shotPulse = if (reducedMotion) 0f else sin(timeSeconds * .21f) * .025f
    val grayCenter = Offset(size.width * (.26f + shotPulse), size.height * (.44f - shotPulse))
    val accentCenter = Offset(size.width * (.79f - shotPulse * .7f), size.height * (.64f + shotPulse))
    val voidCenter = Offset(size.width * (.54f + shotPulse * .3f), size.height * (.38f - shotPulse * .4f))
    val waveY = size.height * (.17f + sin(timeSeconds * .16f) * .012f)
    val spacing = when (quality) {
        LyricsRenderQuality.FULL -> 4.7.dp.toPx()
        LyricsRenderQuality.BALANCED -> 5.7.dp.toPx()
        LyricsRenderQuality.POWER_SAVER -> 10.dp.toPx()
        LyricsRenderQuality.AUTO -> 6.6.dp.toPx()
    }.coerceAtLeast(2f)
    val radius = (spacing * .23f).coerceIn(.55f, 2.2f)
    val inverted = ((seed ushr 7) and 7) == 2 || ((seed ushr 11) and 11) == 3
    val paperColor = if (inverted) Color(0xFFECE8DF) else ink
    val groundColor = if (inverted) Color(0xFFB8B2A7) else accent
    if (!lowPower) {
        drawCircle(Brush.radialGradient(listOf(paper.copy(alpha = .28f), paper.copy(alpha = .09f), Color.Transparent),
            center = grayCenter, radius = size.width * .48f), radius = size.width * .48f, center = grayCenter)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .72f), accent.copy(alpha = .28f), Color.Transparent),
            center = accentCenter, radius = size.width * .55f), radius = size.width * .55f, center = accentCenter)
    }
    var y = spacing * .55f
    var row = 0
    while (y < size.height) {
        var x = spacing * .55f + if (row % 2 == 0) 0f else spacing * .5f
        var column = 0
        while (x < size.width) {
            val grayDx = (x - grayCenter.x) / (size.width * .38f)
            val grayDy = (y - grayCenter.y) / (size.height * .38f)
            val gray = (1f - hypot(grayDx, grayDy)).coerceIn(0f, 1f)
            val accentDx = (x - accentCenter.x) / (size.width * .45f)
            val accentDy = (y - accentCenter.y) / (size.height * .35f)
            val salmon = (1f - hypot(accentDx, accentDy)).coerceIn(0f, 1f) * 1.25f
            val voidDx = (x - voidCenter.x) / (size.width * .3f)
            val voidDy = (y - voidCenter.y) / (size.height * .36f)
            val void = (1f - hypot(voidDx, voidDy)).coerceIn(0f, 1f)
            val waveDistance = abs(y - (waveY + sin(x / size.width * 5.8f + timeSeconds * .17f) * size.height * .016f))
            val wave = (1f - waveDistance / (size.height * .055f)).coerceIn(0f, 1f)
            val noise = hash01(column, row, floor(timeSeconds * (if (reducedMotion) .25f else 5f)).toInt())
            val fine = hash01(column + 17, row + 71, seed)
            val field = max(max(gray * .82f, wave), max(salmon * .7f, noise * .24f))
            val cut = (field - void * .74f).coerceIn(0f, 1f)
            val dot = if (halftone) {
                val threshold = .15f + noise * .35f
                if (cut >= threshold) .95f else .06f
            } else .35f
            val alpha = (dot * (if (halftone) .72f else .18f) + fine * .055f).coerceIn(0f, .88f)
            val color = when {
                inverted && cut > .35f -> Color(0xFF08090A)
                salmon > gray * 1.25f && salmon > .045f -> groundColor.copy(alpha = 1f)
                else -> paperColor.copy(alpha = 1f)
            }
            drawCircle(color.copy(alpha = if (inverted && cut > .35f) .9f else alpha),
                radius = radius * (.78f + noise * .44f), center = Offset(x, y))
            x += spacing
            column++
        }
        y += spacing
        row++
    }
    if (grain && !lowPower) repeat(if (quality == LyricsRenderQuality.FULL) 760 else 320) { index ->
        val x = hash01(index, 9, seed) * size.width
        val y = hash01(index, 41, seed ushr 3) * size.height
        drawCircle(ink.copy(alpha = .025f + (index % 7) * .008f),
            radius = (.25f + (index % 3) * .17f).dp.toPx(), center = Offset(x, y))
    }
    drawLine(accent.copy(alpha = .76f), Offset(size.width * .07f, size.height * .86f),
        Offset(size.width * (.07f + .86f * progress), size.height * .86f), 2.dp.toPx())
}

private fun DrawScope.drawEditorialMarks(ink: Color, accent: Color, progress: Float) {
    val crop = 18.dp.toPx()
    val gap = 12.dp.toPx()
    val hairline = .65.dp.toPx()
    repeat(4) { corner ->
        val x = if (corner % 2 == 0) gap else size.width - gap
        val y = if (corner < 2) gap else size.height - gap
        val sx = if (corner % 2 == 0) 1 else -1
        val sy = if (corner < 2) 1 else -1
        drawLine(ink.copy(alpha = .48f), Offset(x, y), Offset(x + crop * sx, y), hairline)
        drawLine(ink.copy(alpha = .48f), Offset(x, y), Offset(x, y + crop * sy), hairline)
    }
    drawLine(ink.copy(alpha = .1f), Offset(size.width * .07f, size.height * .12f),
        Offset(size.width * .93f, size.height * .12f), hairline)
    drawCircle(accent.copy(alpha = .72f), radius = 2.6.dp.toPx(),
        center = Offset(size.width * (.07f + .86f * progress), size.height * .86f))
}

@Composable private fun MetaphorCompositionCanvas(line: LyricLine, now: Long, fontSize: Float,
    weightMode: LyricsWeight, display: LyricsDisplayOptions, ink: Color, accent: Color,
    canSeek: Boolean, reducedMotion: Boolean, onSeek: (Long) -> Unit, modifier: Modifier) {
    val units = remember(line, display.wordAnimation) { metaphorUnits(line, display.wordAnimation) }
    val font = currentLyricsFontFamily()
    val weight = lyricsFontWeight(weightMode, true, display.fontWeight)
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.08f).sp,
        fontWeight = weight, fontFamily = font)
    val measured = remember(units, style) {
        units.mapNotNull { unit ->
            val text = unit.text.trim()
            if (text.isBlank()) null else MeasuredMetaphorUnit(unit,
                measurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1))
        }
    }
    val seed = remember(line) {
        (line.startTimeMs xor (line.text.hashCode().toLong() shl 32)).and(0x7fffffffL).toInt()
    }
    val composition = remember(seed) { MetaphorComposition.entries[seed % MetaphorComposition.entries.size] }
    Canvas(modifier.clickable(enabled = canSeek, role = Role.Button,
        onClickLabel = "从这一句播放") { onSeek(line.startTimeMs) }
        .semantics { contentDescription = line.text }.testTag("lyrics_metaphor_current")) {
        if (measured.isEmpty()) return@Canvas
        val maxTextWidth = size.width * .84f
        val rows = mutableListOf<MutableList<MeasuredMetaphorUnit>>()
        var row = mutableListOf<MeasuredMetaphorUnit>()
        var rowWidth = 0f
        measured.forEach { item ->
            val width = item.layout.size.width.toFloat()
            if (row.isNotEmpty() && rowWidth + width > maxTextWidth) {
                rows += row
                row = mutableListOf()
                rowWidth = 0f
            }
            row += item
            rowWidth += width
        }
        if (row.isNotEmpty()) rows += row
        val lineHeight = rows.maxOfOrNull { group -> group.maxOf { it.layout.size.height }.toFloat() }
            ?: size.height * .1f
        val gap = lineHeight * if (composition == MetaphorComposition.COLUMN) .38f else .16f
        val rawHeight = rows.size * lineHeight + (rows.size - 1).coerceAtLeast(0) * gap
        val widest = rows.maxOfOrNull { group -> group.sumOf { it.layout.size.width.toDouble() }.toFloat() }
            ?: maxTextWidth
        val fit = min(maxTextWidth / widest.coerceAtLeast(1f),
            (size.height * .54f) / rawHeight.coerceAtLeast(1f)).coerceIn(.56f, 1.28f)
        val compositionScale = when (composition) {
            MetaphorComposition.HERO -> 1.14f
            MetaphorComposition.COLUMN -> .88f
            MetaphorComposition.SCATTER -> .96f
            else -> 1f
        }
        val baseScale = fit * compositionScale
        val lineProgress = ((now - line.startTimeMs).toFloat() /
            (line.endTimeMs - line.startTimeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        val shotDurationMs = 1_650L + ((seed ushr 5) and 3) * 280L
        val elapsed = (now - line.startTimeMs).coerceAtLeast(0L)
        val shotIndex = (elapsed / shotDurationMs).toInt()
        val shotProgress = ((elapsed % shotDurationMs).toFloat() / shotDurationMs).coerceIn(0f, 1f)
        val shotOrder = MetaphorShotKind.entries
        val kind = shotOrder[((seed ushr 3) + shotIndex * 3).mod(shotOrder.size)]
        val shotSeed = seed xor (shotIndex * 7919)
        val activeIndex = measured.indexOfLast { now >= it.unit.start }.coerceAtLeast(0)
        val activeItem = measured[activeIndex]
        val placedRows = rows.mapIndexed { rowIndex, group ->
                val widths = group.map { it.layout.size.width.toFloat() }
                val rowWidth = widths.sum()
                val available = size.width * .84f
                val extraGap = if (composition == MetaphorComposition.JUSTIFY && group.size > 1)
                    ((available - rowWidth) / (group.size - 1)).coerceIn(0f, size.width * .07f) else 0f
                val rowTotal = rowWidth + extraGap * (group.size - 1).coerceAtLeast(0)
                val rowLeft = when (composition) {
                    MetaphorComposition.STAIR -> size.width * .08f + rowIndex * size.width * .055f
                    MetaphorComposition.BLOCK -> (size.width - rowTotal) * .5f + if (rowIndex % 2 == 0) -8f else 10f
                    MetaphorComposition.SCATTER -> (size.width - rowTotal) * .5f + sin((rowIndex + seed) * .7f) * size.width * .035f
                    else -> (size.width - rowTotal) * .5f
                }
            Triple(rowIndex, group, rowLeft to extraGap)
        }
        var activeX = size.width * .5f
        var activeY = rawHeight * .5f
        placedRows.forEach { (rowIndex, group, placement) ->
            var cursor = placement.first
            group.forEach { item ->
                if (item === activeItem) {
                    activeX = cursor + item.layout.size.width * .5f
                    activeY = rowIndex * (lineHeight + gap) + item.layout.size.height * .5f
                }
                cursor += item.layout.size.width + placement.second
            }
        }
        val wholeCenter = Offset(size.width * .5f, rawHeight * .5f)
        val focus = when (kind) {
            MetaphorShotKind.CLOSE, MetaphorShotKind.FOLLOW, MetaphorShotKind.MACRO, MetaphorShotKind.PUSHIN ->
                Offset(activeX, activeY)
            else -> wholeCenter
        }
        val jump = (shotProgress * 4f).toInt().coerceIn(0, 3) / 3f
        val zoom = baseScale * when (kind) {
            MetaphorShotKind.WIDE -> 1f
            MetaphorShotKind.CLOSE -> 1.35f
            MetaphorShotKind.FOLLOW -> 1.52f
            MetaphorShotKind.MACRO -> 2.15f
            MetaphorShotKind.DUTCH -> 1.14f
            MetaphorShotKind.VOID -> .76f
            MetaphorShotKind.PULLBACK -> .74f + jump * .54f
            MetaphorShotKind.PUSHIN -> 2.55f - jump * .9f
        }
        val roll = if (reducedMotion) 0f else when (kind) {
            MetaphorShotKind.MACRO -> ((shotSeed and 1) * 2 - 1) * .32f
            MetaphorShotKind.DUTCH -> (((shotSeed ushr 1) and 1) * 2 - 1) * .2f
            else -> 0f
        }
        val drift = if (reducedMotion) 0f else sin(now / 5300f + shotSeed) * 3.2f
        val stageCenter = Offset(size.width * (when (kind) {
            MetaphorShotKind.VOID -> if ((shotSeed and 1) == 0) .34f else .67f
            else -> .5f
        }) + drift, size.height * (when (composition) {
            MetaphorComposition.HERO -> .42f
            MetaphorComposition.COLUMN -> .47f
            else -> .46f
        }))

        drawConceptFigure(shotSeed, composition, lineProgress, size.width, size.height,
            ink, accent, reducedMotion, stageCenter, zoom / baseScale)
        withTransform({
            translate(stageCenter.x, stageCenter.y)
            rotate(roll, pivot = Offset.Zero)
            scale(zoom, zoom, pivot = Offset.Zero)
            translate(-focus.x, -focus.y)
        }) {
            placedRows.forEach { (rowIndex, group, placement) ->
                val extraGap = placement.second
                var x = placement.first
                group.forEachIndexed { unitIndex, item ->
                    val unit = item.unit
                    val duration = (unit.end - unit.start).coerceAtLeast(1L)
                    val progress = ((now - unit.start).toFloat() / duration).coerceIn(0f, 1f)
                    val eased = progress * progress * (3f - 2f * progress)
                    val active = now in unit.start until unit.end
                    val reveal = if (now >= unit.start) (.58f + .42f * eased) else .06f
                    val tilt = if (reducedMotion) 0f else when (composition) {
                        MetaphorComposition.SCATTER -> ((seed + unitIndex * 7) % 9 - 4) * .45f * (1f - eased)
                        MetaphorComposition.STAIR -> -1.0f + rowIndex * .6f
                        else -> 0f
                    }
                    val wordScale = 1f + if (active) .12f * (1f - eased) else 0f
                    val top = rowIndex * (lineHeight + gap)
                    val wordCenter = Offset(x + item.layout.size.width * .5f, top + item.layout.size.height * .5f)
                    withTransform({
                        rotate(tilt, pivot = wordCenter)
                        scale(wordScale, wordScale, pivot = wordCenter)
                    }) {
                        if (active && !reducedMotion) {
                            drawText(item.layout, color = accent.copy(alpha = .16f), topLeft = Offset(x - 5f, top))
                            drawText(item.layout, color = accent.copy(alpha = .1f), topLeft = Offset(x + 4f, top + 1f))
                            repeat(5) { fragment ->
                                val angle = hash01(seed, unitIndex, fragment) * 6.28318f
                                val distance = (7f + hash01(fragment, unitIndex, 7) * 18f) * (1f - eased)
                                drawCircle(accent.copy(alpha = .34f * (1f - eased)), radius = (1 + fragment % 2).dp.toPx(),
                                    center = Offset(wordCenter.x + cos(angle) * distance,
                                        wordCenter.y + sin(angle) * distance))
                            }
                        }
                        drawText(item.layout,
                            color = (if (unit.emphasis) accent else ink).copy(alpha = reveal * .94f),
                            topLeft = Offset(x, top))
                    }
                    x += item.layout.size.width + extraGap
                }
            }
        }
    }
}

private fun DrawScope.drawConceptFigure(seed: Int, composition: MetaphorComposition,
    progress: Float, width: Float, height: Float, ink: Color, accent: Color,
    reducedMotion: Boolean, anchor: Offset, cameraScale: Float) {
    val motion = if (reducedMotion) 0f else sin(progress * 2.1f) * 5f
    val center = Offset(anchor.x + motion, anchor.y - motion * .4f)
    val lineWidth = (1.1f / sqrt(cameraScale.coerceAtLeast(.6f))).dp.toPx()
    fun wave(yBase: Float, amplitude: Float, phase: Float, color: Color) {
        val path = Path()
        val left = width * .055f
        val right = width * .945f
        path.moveTo(left, yBase)
        repeat(42) { index ->
            val t = index / 41f
            val x = left + (right - left) * t
            val y = yBase + sin(t * 9.2f + phase) * amplitude +
                sin(t * 27f + phase * 1.7f) * amplitude * .2f
            path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(lineWidth, cap = StrokeCap.Round))
    }
    drawCircle(ink.copy(alpha = .1f), min(width, height) * .15f, center, style = Stroke(lineWidth))
    drawCircle(ink.copy(alpha = .07f), min(width, height) * .24f, center, style = Stroke(lineWidth))
    when ((seed and 3) % 4) {
        0 -> {
            wave(height * (.15f + (seed and 7) * .004f), height * .012f, seed * .03f, ink.copy(alpha = .3f))
            wave(height * .82f, height * .007f, seed * -.021f, ink.copy(alpha = .15f))
        }
        1 -> {
            wave(height * .2f, height * .018f, seed * .04f, ink.copy(alpha = .26f))
            repeat(5) { index ->
                val y = height * (.64f + index * .032f)
                drawLine(ink.copy(alpha = .12f - index * .015f), Offset(width * .08f, y),
                    Offset(width * (.48f + index * .085f), y + sin((index + seed).toFloat()) * 8f), lineWidth)
            }
        }
        2 -> {
            repeat(8) { index ->
                val y = height * (.19f + index * .075f)
                drawLine(ink.copy(alpha = .12f), Offset(width * .57f, y),
                    Offset(width * (1f - index * .018f), y), lineWidth)
            }
            wave(height * .78f, height * .015f, seed * .07f, ink.copy(alpha = .24f))
        }
        else -> {
            val path = Path().apply {
                moveTo(width * .06f, height * .72f)
                cubicTo(width * .26f, height * .48f, width * .45f, height * .94f, width * .7f, height * .58f)
                cubicTo(width * .82f, height * .4f, width * .9f, height * .55f, width * .96f, height * .37f)
            }
            drawPath(path, ink.copy(alpha = .22f), style = Stroke(lineWidth * 1.4f, cap = StrokeCap.Round))
        }
    }
    when (composition) {
        MetaphorComposition.HERO -> repeat(3) { index ->
            val radius = min(width, height) * (.19f + index * .085f)
            drawCircle(accent.copy(alpha = .11f - index * .022f), radius, center,
                style = Stroke(lineWidth))
        }
        MetaphorComposition.STAIR -> repeat(7) { index ->
            val y = height * (.28f + index * .072f)
            drawLine(accent.copy(alpha = .1f), Offset(width * .075f, y),
                Offset(width * (.43f + index * .052f), y + sin((index + seed).toFloat()) * 5f), lineWidth)
        }
        MetaphorComposition.COLUMN -> repeat(5) { index ->
            val x = width * (.17f + index * .165f)
            drawLine(accent.copy(alpha = .09f), Offset(x, height * .24f),
                Offset(x + sin((index + seed).toFloat()) * 4f, height * .76f), lineWidth)
        }
        MetaphorComposition.BLOCK -> repeat(4) { index ->
            val side = min(width, height) * (.08f + index * .026f)
            drawRect(accent.copy(alpha = .09f), topLeft = Offset(width * .07f + index * 12f,
                height * .24f + index * 18f),
                size = androidx.compose.ui.geometry.Size(side, side), style = Stroke(lineWidth))
        }
        MetaphorComposition.SCATTER -> repeat(12) { index ->
            val x = ((index * 73 + seed * 19) % 100) / 100f * width
            val y = ((index * 47 + seed * 11) % 100) / 100f * height
            drawCircle(if (index % 4 == 0) accent.copy(alpha = .24f) else ink.copy(alpha = .1f),
                radius = (.85f + index % 3 * .48f).dp.toPx(), center = Offset(x, y))
        }
        MetaphorComposition.JUSTIFY -> {
            drawLine(accent.copy(alpha = .22f), Offset(width * .07f, height * .23f),
                Offset(width * .93f, height * .23f), lineWidth)
            drawLine(ink.copy(alpha = .1f), Offset(width * .07f, height * .73f),
                Offset(width * .93f, height * .73f), lineWidth)
        }
    }
}

@Composable private fun MetaphorAuxiliary(text: String, position: State<Long>, tag: String,
    size: Float, opacity: Float) {
    LyricsAuxiliaryText(text, position, true, (size * .52f).coerceAtLeast(12f),
        FontWeight.Normal, TextAlign.Center, tag, opacity, reserveSpace = true)
}

private fun metaphorUnits(line: LyricLine, animate: Boolean): List<MetaphorUnit> {
    if (!animate || line.words.isEmpty()) return listOf(MetaphorUnit(line.text, line.startTimeMs,
        line.endTimeMs, line.roles.any(::emphasisRole)))
    return lyricDisplayGroups(line.text, line.words).mapNotNull { range ->
        val words = line.words.slice(range)
        if (words.isEmpty()) return@mapNotNull null
        val startOffset = words.minOf(LyricWord::startOffset)
        val endOffset = words.maxOf(LyricWord::endOffset)
        val text = if (startOffset >= 0 && endOffset > startOffset && endOffset <= line.text.length)
            line.text.substring(startOffset, endOffset)
        else words.joinToString("") { it.text }
        if (text.isBlank()) return@mapNotNull null
        MetaphorUnit(text, words.minOf(LyricWord::startTimeMs), words.maxOf(LyricWord::endTimeMs),
            words.any { word -> (word.roles + line.roles).any(::emphasisRole) })
    }.ifEmpty { listOf(MetaphorUnit(line.text, line.startTimeMs, line.endTimeMs,
        line.roles.any(::emphasisRole))) }
}

private fun emphasisRole(value: String): Boolean =
    value.lowercase() in setOf("chorus", "x-chorus", "emphasis", "x-emphasis", "hook")
