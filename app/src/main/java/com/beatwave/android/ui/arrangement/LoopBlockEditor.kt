package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Sample

/**
 * Per-loop-block editor (design item 5): volume slider (0f..1f), a trim
 * range slider bounded by the sample's real duration, a pitch slider
 * (-12..+12 semitones), and a delete button. "Save" commits all four fields
 * at once via [onSave]; committing (or deleting) triggers the ViewModel's
 * rebuild-and-recommit-and-save flow.
 *
 * The body Column is wrapped in [androidx.compose.foundation.verticalScroll]
 * so that on short viewports (e.g. a small-screen device in landscape) where
 * the title + three labeled sliders + Delete button don't all fit under
 * AlertDialog's height budget, the overflowing content -- including the
 * Delete button -- scrolls into view instead of being silently clipped off
 * screen with no way to reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopBlockEditorDialog(
    block: LoopBlock,
    sample: Sample,
    onDismiss: () -> Unit,
    onSave: (volume: Float, trimStartMs: Long, trimEndMs: Long?, pitchSemitones: Float) -> Unit,
    onDelete: () -> Unit
) {
    val maxDurationMs = sample.durationMs.toFloat().coerceAtLeast(1f)

    var volume by remember(block.id) { mutableFloatStateOf(block.volume) }
    var trimStart by remember(block.id) {
        mutableFloatStateOf(block.trimStartMs.toFloat().coerceIn(0f, maxDurationMs))
    }
    var trimEnd by remember(block.id) {
        mutableFloatStateOf((block.trimEndMs?.toFloat() ?: maxDurationMs).coerceIn(0f, maxDurationMs))
    }
    var pitch by remember(block.id) { mutableFloatStateOf(block.pitchSemitones) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sample.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Volume: ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..1f)

                Spacer(Modifier.height(12.dp))
                Text(
                    "Trim: ${trimStart.toInt()}ms - ${trimEnd.toInt()}ms",
                    style = MaterialTheme.typography.labelMedium
                )
                // Waveform-visualization upgrade: the full sample's
                // waveform, with the current trim selection highlighted --
                // lets the user see what they're about to cut before
                // dragging the RangeSlider below, rather than trimming
                // blind by numbers alone. No-ops (draws nothing) for a
                // sample with no peaks yet -- see WaveformView's own doc
                // comment.
                WaveformView(
                    peaks = sample.waveformPeaks,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    highlightRange = (trimStart / maxDurationMs)..(trimEnd / maxDurationMs),
                    highlightColor = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                RangeSlider(
                    value = trimStart..trimEnd,
                    onValueChange = { range ->
                        trimStart = range.start
                        trimEnd = range.endInclusive
                    },
                    valueRange = 0f..maxDurationMs
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "Pitch: ${if (pitch >= 0) "+" else ""}${pitch.toInt()} semitones",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = -12f..12f,
                    steps = 23
                )

                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Block")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val resolvedTrimEnd = if (trimEnd >= maxDurationMs) null else trimEnd.toLong()
                onSave(volume, trimStart.toLong(), resolvedTrimEnd, pitch)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
