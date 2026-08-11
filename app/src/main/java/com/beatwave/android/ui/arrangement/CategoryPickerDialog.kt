package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                for (category in SampleCategory.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = category }
                            .testTag("category_option_${category.name}")
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == category, onClick = { selected = category })
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(CategoryColors.forCategory(category))
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
