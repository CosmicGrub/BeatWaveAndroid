package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Waveform-visualization upgrade: draws [peaks] (a
 * [com.beatwave.android.data.model.Sample.waveformPeaks]-shaped list of
 * normalized `[0, 1]` amplitudes -- see
 * [com.beatwave.android.audio.WaveformPeaksExtractor]) as a bar-style
 * waveform filling the available Canvas size. A no-op (draws nothing) when
 * [peaks] is empty -- e.g. a sample imported/recorded before this upgrade
 * existed, whose persisted entry has no peaks and won't get any until
 * re-imported; degrades gracefully rather than showing a broken/flat
 * placeholder.
 *
 * [highlightRange], if given, is a `[0, 1]` fraction-of-[peaks] window drawn
 * in [highlightColor] instead of [color] -- used by [LoopBlockEditorDialog]
 * to show the current trim selection against the sample's full waveform.
 * Leave null (the default) to draw every bar in [color] uniformly, as
 * [BlockView] does for an already-trimmed peaks sub-list on the timeline.
 */
@Composable
fun WaveformView(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    highlightRange: ClosedFloatingPointRange<Float>? = null,
    highlightColor: Color = color
) {
    Canvas(modifier) {
        if (peaks.isEmpty()) return@Canvas
        val barCount = peaks.size
        val barWidth = size.width / barCount
        val centerY = size.height / 2f
        val strokeWidth = (barWidth * 0.7f).coerceAtLeast(1f)

        for (i in 0 until barCount) {
            val amplitude = peaks[i].coerceIn(0f, 1f)
            val barHeight = (amplitude * size.height).coerceAtLeast(MIN_BAR_HEIGHT_PX)
            val x = i * barWidth + barWidth / 2f
            val fraction = (i + 0.5f) / barCount
            val inHighlight = highlightRange == null || fraction in highlightRange

            drawLine(
                color = if (inHighlight) highlightColor else color,
                start = Offset(x = x, y = centerY - barHeight / 2f),
                end = Offset(x = x, y = centerY + barHeight / 2f),
                strokeWidth = strokeWidth
            )
        }
    }
}

/** Always visible even for a near-silent peak, so a quiet passage still
 *  reads as "there is audio here" rather than a gap indistinguishable from
 *  [peaks] being empty. */
private const val MIN_BAR_HEIGHT_PX = 2f
