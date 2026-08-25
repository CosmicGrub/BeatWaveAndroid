package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Grid-sequencer redesign (2026-08-24 spec), Tier 2.5: which pitch-offset
 * rows a melodic track's grid shows. [intervals] are scale-degree
 * semitone offsets from a root (standard music theory), applied directly
 * against [chromaticPitchRows]'s own offset space via [scaleVisibleSemitones]
 * -- root selection is deliberately NOT part of this control (see that
 * function's doc comment for why).
 */
internal enum class ScaleType(val label: String, val intervals: Set<Int>) {
    MAJOR("Major", setOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Minor", setOf(0, 2, 3, 5, 7, 8, 10)),
    MAJOR_PENTATONIC("Pentatonic Major", setOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Pentatonic Minor", setOf(0, 3, 5, 7, 10)),
    CHROMATIC("Chromatic", (0..11).toSet())
}

/**
 * Tier 2.5: the pitch-offset rows [scaleType] makes visible, out of
 * [chromaticPitchRows]'s full +12..-12 set. Root is deliberately not a
 * parameter here: [chromaticPitchRows]'s own doc comment establishes that
 * offset 0 already IS the sample's own pitch (not a fixed absolute note,
 * and [com.beatwave.android.data.model.Sample] carries no base-pitch
 * metadata to shift against) -- a scale's interval pattern is already
 * defined relative to its root, and offset 0 already is that root by
 * construction, so every possible root would produce this identical
 * filtered set for a given [scaleType]. Shipping a root selector that
 * doesn't change the result would look broken; this control is scale
 * TYPE only, matching that.
 */
internal fun scaleVisibleSemitones(scaleType: ScaleType): Set<Int> =
    chromaticPitchRows().filter { offset -> Math.floorMod(offset, 12) in scaleType.intervals }.toSet()

@Composable
internal fun ScaleChip(selection: ScaleType, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .testTag("scale_chip")
            .selectable(selected = false, onClick = onClick, role = Role.Button)
            .semantics { contentDescription = "Scale: ${selection.label}. Tap to change." },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            "${selection.label} ▾",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Same AlertDialog/selectableGroup/selectable(Role.RadioButton) idiom as
 *  [CategoryPickerDialog] -- the established accessible-radio-row pattern
 *  in this codebase (post-v1 audit A4). */
@Composable
internal fun ScalePickerDialog(current: ScaleType, onDismiss: () -> Unit, onConfirm: (ScaleType) -> Unit) {
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scale") },
        text = {
            Column(Modifier.selectableGroup()) {
                for (type in ScaleType.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == type,
                                onClick = { selected = type },
                                role = Role.RadioButton
                            )
                            .testTag("scale_option_${type.name}")
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == type, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(type.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, modifier = Modifier.testTag("scale_confirm_button")) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("scale_cancel_button")) {
                Text("Cancel")
            }
        }
    )
}
