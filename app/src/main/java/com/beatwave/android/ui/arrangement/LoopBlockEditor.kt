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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
                // Post-v1 audit A4: each label+control pair below is wrapped
                // in a mergeDescendants Column -- without it, the label Text
                // and the slider's own (newly-added) contentDescription/
                // value semantics would read as two separate, redundant
                // TalkBack stops for the same control.
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text("Volume: ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    // Slider's own semantics already speak a percentage, but
                    // nothing identified WHICH slider that was --
                    // contentDescription names it so it's distinguishable
                    // from the Pitch slider below.
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.semantics { contentDescription = "Volume" }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Column(Modifier.semantics(mergeDescendants = true) {}) {
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
                    // comment. Already excluded from accessibility
                    // (clearAndSetSemantics inside WaveformView itself), so
                    // it contributes nothing to this merge.
                    WaveformView(
                        peaks = sample.waveformPeaks,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        highlightRange = (trimStart / maxDurationMs)..(trimEnd / maxDurationMs),
                        highlightColor = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    // Corrected during this audit's adversarial-review pass:
                    // an earlier version of this comment incorrectly claimed
                    // the pinned compose-bom lacked per-thumb
                    // contentDescriptions -- checked against the actual
                    // Material3 1.2.x RangeSlider source: its shared internal
                    // impl has always set distinct framework-localized
                    // descriptions ("range start"/"range end") on each
                    // thumb's own semantics node, even via this plain
                    // two-arg overload. What those built-in per-thumb labels
                    // DON'T say is what's being trimmed -- this outer
                    // contentDescription is a section heading ahead of them
                    // ("Trim range", then "range start"/"range end" per
                    // thumb), not a replacement for them. No mergeDescendants
                    // here, so it can't suppress or collapse the two thumb
                    // nodes underneath; confirm the full announcement
                    // sequence sounds right on the real-device TalkBack pass
                    // rather than trusting the diff, per the backlog item's
                    // own explicit requirement.
                    RangeSlider(
                        value = trimStart..trimEnd,
                        onValueChange = { range ->
                            trimStart = range.start
                            trimEnd = range.endInclusive
                        },
                        valueRange = 0f..maxDurationMs,
                        modifier = Modifier.semantics { contentDescription = "Trim range" }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        "Pitch: ${if (pitch >= 0) "+" else ""}${pitch.toInt()} semitones",
                        style = MaterialTheme.typography.labelMedium
                    )
                    // Slider's default percentage-of-range readout (0% =
                    // -12, 100% = +12) doesn't map intuitively onto
                    // semitones -- stateDescription overrides it with the
                    // same signed-semitone phrasing already shown in the
                    // visible label above.
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = -12f..12f,
                        steps = 23,
                        modifier = Modifier.semantics {
                            contentDescription = "Pitch"
                            stateDescription = "${if (pitch >= 0) "+" else ""}${pitch.toInt()} semitones"
                        }
                    )
                }

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
