package com.beatwave.android.ui.arrangement

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory

/**
 * Bottom-sheet loop library (design item 3): category filter chips, a list
 * of loop cards sourced from [AssetLoopLibrary][com.beatwave.android.data.library.AssetLoopLibrary]
 * via the ViewModel. Each card has an independent one-shot "Preview" action
 * and an "Add" action that places the loop on whichever track is currently
 * selected on the timeline (see [ArrangementViewModel.addLoopToSelectedTrack]).
 *
 * Phase 4 adds an "Import from device" trigger: launches the system
 * [ActivityResultContracts.OpenDocument] picker restricted to audio MIME
 * types, and forwards the picked [Uri] to [onImport] (see [ArrangementViewModel.importAudioFromUri])
 * to drive the decode/category-prompt/persist pipeline. No persistable URI
 * permission is taken -- the file is decoded and copied immediately, so no
 * long-term access to the original Uri is needed afterward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopLibraryBottomSheet(
    samples: List<Sample>,
    selectedTrackSlot: Int?,
    onDismiss: () -> Unit,
    onPreview: (Sample) -> Unit,
    onAdd: (Sample) -> Unit,
    onImport: (Uri) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by remember { mutableStateOf<SampleCategory?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImport(uri)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Post-v1 audit A4: announces the sheet's identity the
                // instant it opens, rather than requiring a swipe to the
                // title text like any other row.
                .semantics { paneTitle = "Loop Library" }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Loop Library", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("loop_library_close_button")) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("audio/*")) },
                modifier = Modifier.testTag("import_from_device_button")
            ) {
                Text("Import from device")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (selectedTrackSlot != null) {
                    "Adding to Track $selectedTrackSlot"
                } else {
                    "Select a track on the timeline first"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedTrackSlot != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") },
                    modifier = Modifier.testTag("category_filter_ALL")
                )
                Spacer(Modifier.width(8.dp))
                for (category in SampleCategory.entries) {
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.name) },
                        modifier = Modifier.testTag("category_filter_${category.name}")
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))

            val filtered = samples.filter { selectedCategory == null || it.category == selectedCategory }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(filtered, key = { it.id }) { sample ->
                    LoopLibraryCard(
                        sample = sample,
                        hasSelectedTrack = selectedTrackSlot != null,
                        onPreview = { onPreview(sample) },
                        onAdd = { onAdd(sample) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LoopLibraryCard(
    sample: Sample,
    hasSelectedTrack: Boolean,
    onPreview: () -> Unit,
    onAdd: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(CategoryColors.forCategory(sample.category))
            )
            Spacer(Modifier.width(12.dp))
            // Post-v1 audit A4: without merging, TalkBack announces the
            // name and category as two disconnected stops.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(sample.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    sample.category.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Post-v1 audit A4: in a scrolling list of many cards, a bare
            // "Preview"/"Add" announcement gives no indication which loop a
            // given button targets without backing up to re-hear the name.
            TextButton(
                onClick = onPreview,
                modifier = Modifier
                    .semantics { contentDescription = "Preview ${sample.name}" }
                    .testTag("preview_loop_${sample.id}")
            ) {
                Text("Preview")
            }
            // Found during this audit's adversarial-review pass: the plain
            // "Add {name} to track" description gave no non-visual signal
            // that tapping Add will no-op when no track is selected yet --
            // the sighted-only hint above the list ("Select a track on the
            // timeline first") is a separate, easily-scrolled-away stop not
            // wired into each card's own Add button.
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .semantics {
                        contentDescription = if (hasSelectedTrack) {
                            "Add ${sample.name} to track"
                        } else {
                            "Add ${sample.name}. Select a track on the timeline first."
                        }
                    }
                    .testTag("add_loop_${sample.id}")
            ) { Text("Add") }
        }
    }
}
