package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.Track

// Grid-sequencer redesign (2026-08-24 spec), Tier 1 (Walking Skeleton):
// melodic tracks only. Drum-kit multi-row support (Tier 2.1), drag-to-
// stretch (2.2), the long-press editor (2.3), the Sounds picker (2.4), and
// the scale system (2.5) are all deliberately deferred -- see the
// implementation plan's own reasoning for why this slice is scoped this
// small. This file is NOT wired into MainActivity's real navigation yet
// (that's Tier 3) -- verified via direct composable hosting
// (createAndroidComposeRule<ComponentActivity>()), matching the pattern
// this project's own Phase 3 S-Pen work established.

private val MELODIC_CATEGORIES = setOf(SampleCategory.BASS, SampleCategory.SYNTH, SampleCategory.VOCAL)

/** What kind of grid [gridTrackKind] should render for a given track. */
internal enum class GridTrackKind {
    /** No assigned instrument yet -- show a prompt instead of a grid. */
    UNASSIGNED,

    /** Exactly one assigned sample, melodic category -- Tier 1's only
     *  fully-supported case: chromatic pitch-offset rows. */
    MELODIC,

    /** A DRUMS-category assignment (needs Tier 2.1's multi-row support), a
     *  multi-sample assignment, or an assignedSampleIds entry that no
     *  longer resolves against the sample library -- Tier 1 shows a
     *  "not yet supported" placeholder rather than guessing or crashing. */
    NOT_YET_SUPPORTED
}

/** Pure, unit-testable without Compose -- see [GridTrackKindTest]. */
internal fun gridTrackKind(track: Track, samples: Map<String, Sample>): GridTrackKind {
    if (track.assignedSampleIds.isEmpty()) return GridTrackKind.UNASSIGNED
    if (track.assignedSampleIds.size != 1) return GridTrackKind.NOT_YET_SUPPORTED
    val sample = samples[track.assignedSampleIds.first()] ?: return GridTrackKind.NOT_YET_SUPPORTED
    return if (sample.category in MELODIC_CATEGORIES) GridTrackKind.MELODIC else GridTrackKind.NOT_YET_SUPPORTED
}

/**
 * The pitch-offset rows for a melodic track's grid, top-to-bottom (+12
 * semitones at the top, -12 at the bottom -- matches how a piano roll
 * conventionally reads, higher pitch = higher on screen). Chromatic only;
 * Tier 2.5's scale-constrained filter isn't applied here. A row's Int
 * value IS the semitone offset directly -- see [LoopBlock.pitchRow]'s doc
 * comment and [ArrangementViewModel.toggleGridCell].
 */
internal fun chromaticPitchRows(): List<Int> = (12 downTo -12).toList()

// How many grid-unit columns the grid renders. A plain fixed span (not
// GridConstants.maxSongLengthGridUnits's full ~4-minute range) -- wide
// enough to be genuinely useful for this tier's own on-device interaction
// check without paying to compose thousands of cells eagerly (this
// Column/Row layout is NOT virtualized/Lazy, a deliberate Tier 1
// simplification -- revisit if/when a longer grid is actually needed).
private const val GRID_COLUMNS = 64

private val ROW_HEIGHT: Dp = 28.dp
private val ROW_LABEL_WIDTH: Dp = 48.dp
private val CELL_WIDTH: Dp = 28.dp

/**
 * Tier 1 entry point. Not yet reachable from the app's real navigation
 * (MainActivity still launches [ArrangementScreen]) -- hosted directly by
 * this tier's own instrumented tests instead.
 */
@Composable
fun GridScreen(viewModel: ArrangementViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val project = uiState.project

    if (project == null) {
        Box(Modifier.fillMaxSize().testTag("grid_loading"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var focusedTrackSlot by remember { mutableStateOf(project.tracks.firstOrNull()?.slot ?: 1) }

    Column(Modifier.fillMaxSize()) {
        Text(
            project.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp).testTag("grid_project_title")
        )
        TrackSwitcher(
            tracks = project.tracks,
            samples = uiState.samples,
            selectedSlot = focusedTrackSlot,
            onSelectSlot = { focusedTrackSlot = it }
        )
        val focusedTrack = project.tracks.firstOrNull { it.slot == focusedTrackSlot }
        if (focusedTrack != null) {
            GridBody(
                track = focusedTrack,
                samples = uiState.samples,
                onCellTap = { gridColumn, pitchRow ->
                    viewModel.toggleGridCell(focusedTrackSlot, gridColumn, pitchRow)
                }
            )
        }
    }
}

@Composable
private fun TrackSwitcher(
    tracks: List<Track>,
    samples: Map<String, Sample>,
    selectedSlot: Int,
    onSelectSlot: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        for (track in tracks) {
            val label = trackPillLabel(track, samples)
            val selected = track.slot == selectedSlot
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("track_pill_${track.slot}")
                    .selectable(selected = selected, onClick = { onSelectSlot(track.slot) }, role = Role.Tab)
                    .semantics { contentDescription = "Track ${track.slot}: $label" },
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

private fun trackPillLabel(track: Track, samples: Map<String, Sample>): String {
    if (track.assignedSampleIds.isEmpty()) return "Empty"
    val firstSample = samples[track.assignedSampleIds.first()]
    return firstSample?.name ?: "Unknown"
}

@Composable
private fun GridBody(
    track: Track,
    samples: Map<String, Sample>,
    onCellTap: (gridColumn: Int, pitchRow: Int) -> Unit
) {
    when (gridTrackKind(track, samples)) {
        GridTrackKind.UNASSIGNED -> CenteredPrompt(
            text = "Tap Sounds to choose an instrument",
            testTag = "grid_empty_track_prompt"
        )
        GridTrackKind.NOT_YET_SUPPORTED -> CenteredPrompt(
            text = "This instrument type isn't supported by the grid yet",
            testTag = "grid_not_yet_supported_prompt"
        )
        GridTrackKind.MELODIC -> MelodicGrid(track = track, onCellTap = onCellTap)
    }
}

@Composable
private fun CenteredPrompt(text: String, testTag: String) {
    Box(Modifier.fillMaxSize().testTag(testTag), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MelodicGrid(
    track: Track,
    onCellTap: (gridColumn: Int, pitchRow: Int) -> Unit
) {
    val rows = remember { chromaticPitchRows() }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    // (gridColumn, pitchRow) -> whether a block occupies that exact cell.
    // Tier 1 blocks are always exactly 1 grid unit wide (no drag-to-stretch
    // yet), so a block occupies exactly one cell -- O(1) lookup per cell
    // while rendering, recomputed only when this track's blocks actually
    // change.
    val filledCells = remember(track.loopBlocks) {
        track.loopBlocks.filter { it.pitchRow != null }
            .map { it.startGridUnit to it.pitchRow }
            .toSet()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(verticalScrollState)
            .testTag("grid_melodic_canvas")
    ) {
        for (row in rows) {
            Row(Modifier.height(ROW_HEIGHT)) {
                Box(
                    Modifier.width(ROW_LABEL_WIDTH).fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        "${if (row >= 0) "+" else ""}$row",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp).testTag("grid_row_label_$row")
                    )
                }
                // Shared horizontalScrollState across every row -- all rows
                // scroll together, same technique ArrangementScreen's own
                // TrackRow already established for the old timeline.
                Row(Modifier.horizontalScroll(horizontalScrollState)) {
                    for (column in 0 until GRID_COLUMNS) {
                        val filled = (column to row) in filledCells
                        GridCell(
                            filled = filled,
                            onTap = { onCellTap(column, row) },
                            testTag = "grid_cell_${column}_$row"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(filled: Boolean, onTap: () -> Unit, testTag: String) {
    Box(
        Modifier
            .width(CELL_WIDTH)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics {
                contentDescription = if (filled) "Filled cell, tap to remove" else "Empty cell, tap to place"
            }
    )
}
