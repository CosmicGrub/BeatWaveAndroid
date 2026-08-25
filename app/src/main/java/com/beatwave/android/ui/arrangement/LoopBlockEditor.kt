package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Sample

// Phase 3 (S-Pen precision input, 2026-08-18 device-adaptive-layouts spec):
// Material3's Slider/RangeSlider don't expose a drag-sensitivity hook, so
// precision input is implemented as delta-scaling in onValueChange rather
// than reconfiguring the sliders themselves. [observeStylusPrecisionInput]
// taps the SAME pointer-event stream the Slider/RangeSlider it's chained
// onto already consumes -- PointerEventPass.Initial, and this modifier
// never calls .consume() -- so it's a pure observer, the underlying
// control's own drag handling is completely untouched. It reports the
// active pointer's real Compose PointerType.Stylus + pressure (Compose's
// own pointer-input API already wraps MotionEvent.getToolType()/
// getPressure() for this -- no need to drop to android.view.MotionEvent or
// any proprietary Samsung SDK to get genuine stylus detection).
//
// Deliberately general, not gated to "is this the Tab": a real stylus
// digitizer reporting PointerType.Stylus works identically on any device,
// matching this project's established RAM-scaled-cache/WindowSizeClass
// precedent of responding to real hardware capability rather than
// hardcoding per-device branches. It happens to land on the Tab branch
// first only because that's the device this project currently has an S
// Pen to verify with.
//
// internal (not private): StylusPrecisionInputTest exercises this
// directly against a minimal host, rather than fighting Material3
// Slider's own internal drag-gesture state machine to prove the same
// thing indirectly -- see that test's class doc comment.
internal fun Modifier.observeStylusPrecisionInput(
    onGestureStart: () -> Unit,
    onStylusPressureChanged: (pressure: Float?) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        onGestureStart()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull()
            onStylusPressureChanged(
                if (change != null && change.type == PointerType.Stylus) {
                    change.pressure.coerceIn(0f, 1f)
                } else {
                    null
                }
            )
        } while (event.changes.any { it.pressed })
        onStylusPressureChanged(null)
    }
}

// A real S Pen contact gets AT LEAST STYLUS_MAX_RATIO (a firm press) and as
// fine as STYLUS_MIN_RATIO (the lightest detectable touch) of a finger's
// 1:1 drag sensitivity -- pressing harder moves faster/coarser, a light
// touch is finest, the same way a real pen naturally behaves. A fingertip
// has no meaningful pressure signal on this hardware, so this is a real S
// Pen-specific capability, not just "any stylus is slower." Ratio of 1f
// (finger, or no active pointer) is mathematically a no-op: with the
// delta-tracking below, ratio=1f always reproduces the raw slider value
// exactly, so ordinary finger dragging is completely unchanged.
//
// internal (not private): StylusPrecisionInputTest exercises this directly
// via a plain-JVM unit test (no device/emulator needed for the math itself).
internal const val STYLUS_MIN_RATIO = 0.2f
internal const val STYLUS_MAX_RATIO = 0.6f

internal fun stylusPrecisionRatio(stylusPressure: Float?): Float {
    if (stylusPressure == null) return 1f
    return STYLUS_MIN_RATIO + (STYLUS_MAX_RATIO - STYLUS_MIN_RATIO) * stylusPressure
}

/**
 * A [Slider] with S-Pen precision input applied (Phase 3): a real stylus
 * contact scales down the value-change per unit of raw drag distance (see
 * [stylusPrecisionRatio]); an ordinary finger touch is mathematically
 * unaffected -- see that function's own doc comment for why.
 *
 * Extracted out of [LoopBlockEditorDialog] (rather than left inline) for
 * two reasons: it's identical shared logic between this and
 * [StylusPrecisionRangeSlider] below, and -- just as importantly -- it
 * gives instrumented tests a way to host and drive the REAL production
 * precision-input code directly (`createAndroidComposeRule<ComponentActivity>().
 * setContent { StylusPrecisionSlider(...) }`), without needing to fight
 * [AlertDialog]'s own separate Android Window (a raw MotionEvent dispatched
 * from a test can't reach a Slider living inside a Dialog's window by
 * targeting the host Activity's decor view -- they're different windows).
 *
 * internal, not private: needs to be visible to StylusPrecisionInputTest in
 * app/src/androidTest, the same Gradle module.
 */
@Composable
internal fun StylusPrecisionSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    // "Raw target" tracks where the Slider's own undamped 1:1 mapping says
    // the thumb should be, reset to the current displayed value at the
    // start of each new gesture so a fresh drag always begins scaling from
    // wherever the thumb currently visually sits, not from a stale
    // position left over from a previous gesture.
    var rawTarget by remember { mutableFloatStateOf(value) }
    var stylusPressure by remember { mutableStateOf<Float?>(null) }

    Slider(
        value = value,
        onValueChange = { rawValue ->
            // At ratio=1f (finger) this always reduces to exactly
            // onValueChange(rawValue), unchanged from a plain Slider.
            val ratio = stylusPrecisionRatio(stylusPressure)
            val rawDelta = rawValue - rawTarget
            rawTarget = rawValue
            onValueChange((value + rawDelta * ratio).coerceIn(valueRange.start, valueRange.endInclusive))
        },
        valueRange = valueRange,
        steps = steps,
        modifier = modifier.observeStylusPrecisionInput(
            onGestureStart = { rawTarget = value },
            onStylusPressureChanged = { stylusPressure = it }
        )
    )
}

/**
 * The [RangeSlider] counterpart to [StylusPrecisionSlider] -- same
 * precision-damping idea, applied independently to each thumb (each
 * thumb's own raw delta scaled by the same ratio, so dragging one thumb
 * never affects the other's tracked position).
 */
@Composable
internal fun StylusPrecisionRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    var startRawTarget by remember { mutableFloatStateOf(value.start) }
    var endRawTarget by remember { mutableFloatStateOf(value.endInclusive) }
    var stylusPressure by remember { mutableStateOf<Float?>(null) }

    RangeSlider(
        value = value,
        onValueChange = { range ->
            val ratio = stylusPrecisionRatio(stylusPressure)
            val startDelta = range.start - startRawTarget
            val endDelta = range.endInclusive - endRawTarget
            startRawTarget = range.start
            endRawTarget = range.endInclusive
            var newStart = (value.start + startDelta * ratio).coerceIn(valueRange.start, valueRange.endInclusive)
            var newEnd = (value.endInclusive + endDelta * ratio).coerceIn(valueRange.start, valueRange.endInclusive)
            // Defensive: RangeSlider's own raw value always keeps
            // start<=end, but damping each thumb independently could in
            // principle let the damped pair cross -- never let that reach
            // onValueChange/RangeSlider's own value= below.
            if (newStart > newEnd) newStart = newEnd
            onValueChange(newStart..newEnd)
        },
        valueRange = valueRange,
        modifier = modifier.observeStylusPrecisionInput(
            onGestureStart = {
                startRawTarget = value.start
                endRawTarget = value.endInclusive
            },
            onStylusPressureChanged = { stylusPressure = it }
        )
    )
}

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
                        style = MaterialTheme.typography.labelMedium,
                        // Test-support only: gives instrumented tests (e.g.
                        // Phase 3's stylus-precision-input verification) an
                        // exact, unambiguous readout, rather than parsing
                        // merged mergeDescendants semantics text.
                        modifier = Modifier.testTag("trim_label_text")
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
                    StylusPrecisionRangeSlider(
                        value = trimStart..trimEnd,
                        onValueChange = { range ->
                            trimStart = range.start
                            trimEnd = range.endInclusive
                        },
                        valueRange = 0f..maxDurationMs,
                        modifier = Modifier
                            // Test-support only, see trim_label_text's
                            // comment above -- lets a test find this exact
                            // Slider's own bounds without ambiguity from the
                            // enclosing mergeDescendants Column.
                            .testTag("trim_range_slider")
                            .semantics { contentDescription = "Trim range" }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        "Pitch: ${if (pitch >= 0) "+" else ""}${pitch.toInt()} semitones",
                        style = MaterialTheme.typography.labelMedium,
                        // Test-support only, see trim_label_text's comment above.
                        modifier = Modifier.testTag("pitch_label_text")
                    )
                    // Slider's default percentage-of-range readout (0% =
                    // -12, 100% = +12) doesn't map intuitively onto
                    // semitones -- stateDescription overrides it with the
                    // same signed-semitone phrasing already shown in the
                    // visible label above.
                    StylusPrecisionSlider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        valueRange = -12f..12f,
                        steps = 23,
                        modifier = Modifier
                            // Test-support only, see trim_range_slider's
                            // comment above.
                            .testTag("pitch_slider")
                            .semantics {
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
