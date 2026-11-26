// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.bili.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Tap/double-tap and horizontal scrubbing for the video surface. */
@Composable
internal fun Modifier.biliPlayerGestures(
    positionMs: Long,
    durationMs: Long,
    onTap: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
): Modifier {
    val latestPosition by rememberUpdatedState(positionMs.coerceAtLeast(0L))
    val latestDuration by rememberUpdatedState(durationMs.coerceAtLeast(0L))
    val latestTap by rememberUpdatedState(onTap)
    val latestToggle by rememberUpdatedState(onTogglePlayback)
    val latestSeekBy by rememberUpdatedState(onSeekBy)
    val latestPreview by rememberUpdatedState(onSeekPreview)
    val latestCommit by rememberUpdatedState(onSeekCommit)
    return pointerInput(durationMs) {
        detectTapGestures(
            onTap = { latestTap() },
            onDoubleTap = { offset ->
                when {
                    offset.x < size.width * .36f -> latestSeekBy(-10_000L)
                    offset.x > size.width * .64f -> latestSeekBy(10_000L)
                    else -> latestToggle()
                }
            },
        )
    }.pointerInput(durationMs) {
        if (latestDuration <= 0L) return@pointerInput
        val width = size.width.toFloat().coerceAtLeast(1f)
        var startPosition = latestPosition
        var accumulated = 0f
        var target = startPosition
        detectHorizontalDragGestures(
            onDragStart = {
                startPosition = latestPosition
                accumulated = 0f
                target = startPosition
                latestPreview(target)
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                accumulated += amount
                target = (startPosition + (accumulated / width * latestDuration).toLong())
                    .coerceIn(0L, latestDuration)
                latestPreview(target)
            },
            onDragEnd = { latestCommit(target) },
            onDragCancel = { latestCommit(target) },
        )
    }
}
