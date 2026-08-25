package com.beatwave.android.ui.arrangement

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
    onImport: (Uri) -> Unit,
    // Grid-sequencer redesign (2026-08-24 spec), Tier 2.4: which sample ids
    // are currently assigned to the caller's focused track, so an
    // already-assigned card can show "Remove" instead of "Add". Defaults to
    // empty so the old timeline screen's call site (which has no assignment
    // concept -- onAdd there always means "place a loop block") is
    // completely unaffected: every card there keeps showing "Add", exactly
    // today's behavior.
    assignedSampleIds: Set<String> = emptySet()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            // Device-adaptive layouts (2026-08-18 spec), Phase 0: the sheet
            // is naturally viewport-bounded already (ModalBottomSheet caps
            // its own height), but the original 420.dp cap on the card list
            // below was specific to sitting comfortably inside a partially-
            // expanded sheet -- reproduced here at the wrapper level so
            // LoopLibraryContent itself stays free of a sheet-specific
            // assumption the persistent-panel wrapper (LoopLibraryPanel)
            // doesn't want.
            Box(Modifier.heightIn(max = 420.dp)) {
                LoopLibraryContent(
                    samples = samples,
                    selectedTrackSlot = selectedTrackSlot,
                    onPreview = onPreview,
                    onAdd = onAdd,
                    onImport = onImport,
                    assignedSampleIds = assignedSampleIds
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Device-adaptive layouts (2026-08-18 spec), Phase 0: the loop library's
 * actual browsing/filtering body -- "Import from device", category filter
 * chips, and the filtered card list -- extracted out of
 * [LoopLibraryBottomSheet] so it's reused verbatim by both the bottom-sheet
 * wrapper (compact width) and [LoopLibraryPanel] (medium/expanded width, a
 * persistent side panel rather than a modal sheet). Neither wrapper
 * duplicates this browsing/filtering logic; each only supplies its own
 * chrome (title/close for the sheet, a pane title for the panel) and its
 * own height-constraint policy for the card list below.
 */
@Composable
fun LoopLibraryContent(
    samples: List<Sample>,
    selectedTrackSlot: Int?,
    onPreview: (Sample) -> Unit,
    onAdd: (Sample) -> Unit,
    onImport: (Uri) -> Unit,
    // See LoopLibraryBottomSheet's doc comment on the same parameter.
    assignedSampleIds: Set<String> = emptySet()
) {
    var selectedCategory by remember { mutableStateOf<SampleCategory?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImport(uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
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

        // fillMaxWidth() + weight(1f) rather than the sheet's original
        // heightIn(max = 420.dp): in the persistent-panel context this
        // should fill whatever height the panel actually has, not a
        // sheet-tuned constant. The sheet wrapper above reproduces its own
        // 420.dp cap by bounding the whole LoopLibraryContent call instead.
        val filtered = samples.filter { selectedCategory == null || it.category == selectedCategory }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(filtered, key = { it.id }) { sample ->
                LoopLibraryCard(
                    sample = sample,
                    hasSelectedTrack = selectedTrackSlot != null,
                    isAssigned = sample.id in assignedSampleIds,
                    onPreview = { onPreview(sample) },
                    onAdd = { onAdd(sample) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Device-adaptive layouts (2026-08-18 spec), Phase 0: the persistent-panel
 * counterpart to [LoopLibraryBottomSheet] for medium/expanded window size
 * classes -- same [LoopLibraryContent] body, no modal chrome (no dismiss
 * button; the panel is always visible in this layout, unlike the sheet
 * which is explicitly opened/closed), a [paneTitle] instead of an audible
 * sheet-open announcement, and no sheet-specific height cap -- fills
 * whatever height its container (the two-pane Row in ArrangementScreen)
 * actually gives it.
 */
@Composable
fun LoopLibraryPanel(
    samples: List<Sample>,
    selectedTrackSlot: Int?,
    onPreview: (Sample) -> Unit,
    onAdd: (Sample) -> Unit,
    onImport: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .padding(16.dp)
            .semantics { paneTitle = "Loop Library" }
            .testTag("loop_library_panel")
    ) {
        Text("Loop Library", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LoopLibraryContent(
            samples = samples,
            selectedTrackSlot = selectedTrackSlot,
            onPreview = onPreview,
            onAdd = onAdd,
            onImport = onImport
        )
    }
}

@Composable
private fun LoopLibraryCard(
    sample: Sample,
    hasSelectedTrack: Boolean,
    // Grid-sequencer redesign (2026-08-24 spec), Tier 2.4: false for every
    // call site except the grid screen's own Sounds picker. When true, the
    // single onAdd callback below is relabeled "Remove" -- the CALLER (not
    // this card) decides what that tap actually means (replace/add/remove),
    // exactly as it already decides everything else a tap on this button
    // does; this card only ever renders what it's told.
    isAssigned: Boolean = false,
    onPreview: () -> Unit,
    onAdd: () -> Unit
) {
    // Device-adaptive layouts (2026-08-18 spec), Phase 1: originally a
    // single Row cramming a swatch + name/category + two text-labeled
    // buttons into one line -- fine at full phone-sheet width, but
    // discovered on real Tab S9 FE and Fold 5 hardware to wrap the name
    // and even the "Add" button's own label down to one character per
    // line in the narrower two-pane panel (~34% of the screen). Split
    // into two rows instead of introducing icon buttons (this app has no
    // icon-button precedent anywhere, and no material-icons dependency
    // today -- text-only buttons are this project's established
    // convention) so the name/category row gets the card's FULL width to
    // itself, uncontested by the buttons. Applied uniformly to both the
    // sheet and panel contexts (not conditioned on width) to match this
    // codebase's existing preference for one shared layout over
    // per-context special-casing -- the modest extra vertical height per
    // card is an acceptable, deliberate trade against a real hard-to-read
    // regression that only appeared on hardware wider single-row testing
    // never exercised.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                            contentDescription = when {
                                isAssigned -> "Remove ${sample.name} from track"
                                hasSelectedTrack -> "Add ${sample.name} to track"
                                else -> "Add ${sample.name}. Select a track on the timeline first."
                            }
                        }
                        .testTag(if (isAssigned) "remove_loop_${sample.id}" else "add_loop_${sample.id}")
                ) { Text(if (isAssigned) "Remove" else "Add") }
            }
        }
    }
}
