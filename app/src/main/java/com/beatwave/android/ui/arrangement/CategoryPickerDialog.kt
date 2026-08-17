package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.beatwave.android.data.model.SampleCategory

/**
 * Category prompt (Phase 4 design item 4): a freshly-imported file has no
 * inherent [SampleCategory], so this is shown after a successful decode
 * (see [ArrangementViewModel.confirmPendingImport]) to let the user pick one
 * of the fixed four categories before the [com.beatwave.android.data.model.Sample]
 * is finalized and added to the library. Swatches match [CategoryColors] for
 * visual consistency with the timeline and loop-library cards.
 *
 * The options column is height-capped and scrollable ([verticalScroll]) so
 * all four categories stay reachable even on a short screen where the title
 * + four rows + two action buttons wouldn't otherwise fit inside the
 * dialog's available height (e.g. this project's small-screen handheld test
 * device, ~436dp tall) -- without this, the lowest category row(s) could be
 * laid out beyond the dialog's visible/tappable area.
 */
@Composable
fun CategoryPickerDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (SampleCategory) -> Unit
) {
    var selected by remember { mutableStateOf(SampleCategory.DRUMS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categorize \"$fileName\"") },
        text = {
            // Post-v1 audit A4: selectableGroup() lets TalkBack announce
            // group position ("2 of 4") for the radio rows below.
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                for (category in SampleCategory.entries) {
                    // Post-v1 audit A4: previously a bare clickable Row
                    // wrapping an INDEPENDENTLY clickable/focusable
                    // RadioButton -- two competing click targets/semantics
                    // nodes with no Role.RadioButton or `selected` property
                    // tying them together, so TalkBack read an unlabeled
                    // clickable row, then a nameless radio button, then
                    // plain "DRUMS" text as three disconnected stops.
                    // Modifier.selectable is the documented accessible-
                    // radio-row pattern: it sets the click action AND the
                    // selected/Role.RadioButton semantics together and
                    // merges descendants, so the RadioButton's own
                    // onClick is set to null -- it's now purely a visual
                    // indicator, not a second independent target.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == category,
                                onClick = { selected = category },
                                role = Role.RadioButton
                            )
                            .testTag("category_option_${category.name}")
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == category, onClick = null)
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(CategoryColors.forCategory(category))
                                .clearAndSetSemantics {}
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, modifier = Modifier.testTag("category_confirm_button")) {
                Text("Add to Library")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("category_cancel_button")) {
                Text("Cancel")
            }
        }
    )
}
