package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatwave.android.audio.GridConstants
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.Track
import kotlin.math.roundToInt

// Grid-sequencer redesign (2026-08-24 spec). Tier 1 (Walking Skeleton)
// shipped melodic-only tap-place/tap-delete. Tier 2 adds the rest of the
// spec's Full Capability, each item independent of the others: drum-kit
// multi-row support (2.1, this file), drag-to-stretch (2.2), the
// long-press editor (2.3), the Sounds picker (2.4), and the scale system
// (2.5) are tracked in the implementation plan. This file is NOT wired
// into MainActivity's real navigation yet (that's Tier 3) -- verified via
// direct composable hosting (createAndroidComposeRule<ComponentActivity>()),
// matching the pattern this project's own Phase 3 S-Pen work established.

private val MELODIC_CATEGORIES = setOf(SampleCategory.BASS, SampleCategory.SYNTH, SampleCategory.VOCAL)

/** What kind of grid [gridTrackKind] should render for a given track. */
internal enum class GridTrackKind {
    /** No assigned instrument yet -- show a prompt instead of a grid. */
    UNASSIGNED,

    /** Exactly one assigned sample, melodic category -- chromatic
     *  pitch-offset rows. */
    MELODIC,

    /** One or more DRUMS-category assigned samples (Tier 2.1) -- one
     *  named row per assigned sample, in assignedSampleIds order. */
    DRUM_KIT,

    /** A mixed melodic+drum assignment, or an assignedSampleIds entry that
     *  no longer resolves against the sample library -- shows a "not yet
     *  supported" placeholder rather than guessing or crashing. */
    NOT_YET_SUPPORTED
}

/** Pure, unit-testable without Compose -- see [GridScreenLogicTest]. */
internal fun gridTrackKind(track: Track, samples: Map<String, Sample>): GridTrackKind {
    if (track.assignedSampleIds.isEmpty()) {
        // No current instrument assignment. Tier 2.4: a track can reach
        // this state with orphaned drum notes still sitting in loopBlocks
        // (every sample removed from a drum kit via the Sounds picker,
        // notes never deleted) -- render it as a drum kit rather than
        // falling back to the "choose an instrument" prompt, which would
        // silently hide real placed data behind a state that looks empty.
        val orphanedIds = drumKitRowSampleIds(track)
        if (orphanedIds.isEmpty()) return GridTrackKind.UNASSIGNED
        val resolved = orphanedIds.map { samples[it] }
        return if (resolved.all { it != null && it.category == SampleCategory.DRUMS }) {
            GridTrackKind.DRUM_KIT
        } else {
            GridTrackKind.NOT_YET_SUPPORTED
        }
    }
    val resolved = track.assignedSampleIds.map { samples[it] }
    if (resolved.any { it == null }) return GridTrackKind.NOT_YET_SUPPORTED
    if (track.assignedSampleIds.size == 1) {
        return when (resolved[0]!!.category) {
            in MELODIC_CATEGORIES -> GridTrackKind.MELODIC
            SampleCategory.DRUMS -> GridTrackKind.DRUM_KIT
            else -> GridTrackKind.NOT_YET_SUPPORTED
        }
    }
    // Multiple assigned samples only ever makes sense as a drum kit --
    // require every one of them to actually be DRUMS before trusting that,
    // rather than assuming size > 1 implies it.
    return if (resolved.all { it!!.category == SampleCategory.DRUMS }) {
        GridTrackKind.DRUM_KIT
    } else {
        GridTrackKind.NOT_YET_SUPPORTED
    }
}

/**
 * Tier 2.4: all sample ids a drum-kit grid should render a row for --
 * every currently-assigned id (in [Track.assignedSampleIds] order), plus
 * any id still referenced by an existing drum-style ([LoopBlock.pitchRow]
 * == null) block even after being unassigned via the Sounds picker.
 * Matches the spec's "never silently delete placed data" rule: removing a
 * sample from a kit only ever changes assignedSampleIds, never touches
 * loopBlocks (see [ArrangementViewModel.removeSampleFromTrack]), so an
 * orphaned id's row must keep rendering here for its notes to stay
 * visible/editable -- re-adding the same sample later reunites it with
 * the notes still sitting there. Orphaned ids are appended after the
 * assigned ones, in first-appearance order within loopBlocks.
 */
internal fun drumKitRowSampleIds(track: Track): List<String> {
    if (track.loopBlocks.none { it.pitchRow == null }) return track.assignedSampleIds
    val orphaned = track.loopBlocks
        .asSequence()
        .filter { it.pitchRow == null }
        .map { it.sampleId }
        .distinct()
        .filterNot { it in track.assignedSampleIds }
        .toList()
    return track.assignedSampleIds + orphaned
}

/**
 * What a grid-cell tap identifies -- threaded from whichever grid
 * composable is active down into [ArrangementViewModel.toggleGridCell].
 * A melodic row's Int IS the semitone offset directly (see
 * [LoopBlock.pitchRow]'s doc comment); a drum row IS a specific sample --
 * there is no Int that means "this sample," so this is a sealed type
 * rather than overloading Int to mean two different things.
 */
internal sealed interface GridCellTarget {
    /** Melodic track: [LoopBlock.pitchRow] is this offset directly. */
    data class PitchOffset(val semitones: Int) : GridCellTarget

    /** Drum track: this row IS [sampleId]; [LoopBlock.pitchRow] stays
     *  null (see its doc comment). */
    data class DrumSample(val sampleId: String) : GridCellTarget
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

// Tier 2.1: drum rows show a real sample name ("Basic Kick", "Basic
// Snare"), not MelodicGrid's short "+12"/"-5" offsets -- ROW_LABEL_WIDTH
// alone truncated both to an indistinguishable "Basic" with no ellipsis,
// caught via a real on-device screenshot. Wide enough for this app's own
// bundled DRUMS sample names at labelSmall size; maxLines=1 still clips
// (without an ellipsis marker) a name genuinely longer than this, which
// is an accepted, deliberate limit for this tier, not attempted here.
private val DRUM_ROW_LABEL_WIDTH: Dp = 96.dp

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
    var showSounds by remember { mutableStateOf(false) }
    // Tier 2.5: session-only, like focusedTrackSlot/showSounds above --
    // not persisted on the Project, matching the spec's own requirement.
    var scaleSelection by remember { mutableStateOf(ScaleType.CHROMATIC) }
    var showScalePicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp).weight(1f).testTag("grid_project_title")
            )
            TextButton(onClick = { showSounds = true }, modifier = Modifier.testTag("grid_sounds_button")) {
                Text("Sounds")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrackSwitcher(
                tracks = project.tracks,
                samples = uiState.samples,
                selectedSlot = focusedTrackSlot,
                onSelectSlot = { focusedTrackSlot = it },
                modifier = Modifier.weight(1f)
            )
            ScaleChip(selection = scaleSelection, onClick = { showScalePicker = true })
        }
        if (showScalePicker) {
            ScalePickerDialog(
                current = scaleSelection,
                onDismiss = { showScalePicker = false },
                onConfirm = { selected ->
                    scaleSelection = selected
                    showScalePicker = false
                }
            )
        }
        val focusedTrack = project.tracks.firstOrNull { it.slot == focusedTrackSlot }
        if (focusedTrack != null) {
            // Tier 2.3: playhead position in grid-unit space, mirroring
            // ArrangementScreen's own currentFrame -> grid-unit conversion
            // (same GridConstants.framesPerGridUnit, same currentFrame/
            // sampleRate state PlaybackControlBar already reads off this
            // ViewModel) -- null while sampleRate hasn't been reported yet.
            val framesPerGridUnit = if (uiState.sampleRate > 0) {
                GridConstants.framesPerGridUnit(project.bpm, uiState.sampleRate)
            } else {
                0.0
            }
            val playheadGridUnitPosition = if (framesPerGridUnit > 0) {
                (uiState.currentFrame / framesPerGridUnit).toFloat()
            } else {
                null
            }
            GridBody(
                track = focusedTrack,
                samples = uiState.samples,
                scaleSelection = scaleSelection,
                playheadGridUnitPosition = playheadGridUnitPosition,
                onCellTap = { gridColumn, target ->
                    viewModel.toggleGridCell(focusedTrackSlot, gridColumn, target)
                },
                onLongPressBlock = { block -> viewModel.openBlockEditor(focusedTrackSlot, block.id) }
            )
        }

        // Tier 2.3: long-press-a-filled-cell opens the existing
        // LoopBlockEditorDialog completely unchanged (including Phase 3's
        // S-Pen precision-input work on device/galaxy-tab-s9fe -- that
        // branch only changes the dialog's internal sliders, not this call
        // site). Mirrors ArrangementScreen's own editingBlock-resolution
        // pattern: only an id pair is kept in uiState, re-resolved against
        // the live project/samples every recomposition rather than caching
        // a snapshot that could go stale.
        val editingRef = uiState.editingBlock
        if (editingRef != null) {
            val editingTrack = project.tracks.firstOrNull { it.slot == editingRef.trackSlot }
            val editingBlockData = editingTrack?.loopBlocks?.firstOrNull { it.id == editingRef.blockId }
            val editingSample = editingBlockData?.let { uiState.samples[it.sampleId] }
            if (editingBlockData != null && editingSample != null) {
                LoopBlockEditorDialog(
                    block = editingBlockData,
                    sample = editingSample,
                    onDismiss = viewModel::closeBlockEditor,
                    onSave = { volume, trimStartMs, trimEndMs, pitchSemitones ->
                        viewModel.updateBlock(editingRef.trackSlot, editingRef.blockId, volume, trimStartMs, trimEndMs, pitchSemitones)
                    },
                    onDelete = { viewModel.deleteBlock(editingRef.trackSlot, editingRef.blockId) }
                )
            }
        }

        // Tier 2.4: repurposes the existing LoopLibraryBottomSheet/Content
        // (unchanged internals) as the focused track's instrument-assignment
        // picker. A single-pane modal sheet -- the Tab/Fold two-pane
        // treatment for this screen is explicitly out of scope per the
        // design spec's own phasing (see GridScreen.kt's top-of-file note).
        if (showSounds && focusedTrack != null) {
            val kind = gridTrackKind(focusedTrack, uiState.samples)
            LoopLibraryBottomSheet(
                samples = uiState.sampleList,
                selectedTrackSlot = focusedTrackSlot,
                onDismiss = { showSounds = false },
                onPreview = { sample -> viewModel.previewSample(sample) },
                assignedSampleIds = focusedTrack.assignedSampleIds.toSet(),
                onAdd = { sample ->
                    // Which behavior a tap means depends on the track's
                    // CURRENT kind. An unassigned track has no kind yet --
                    // the first tapped sample's own category decides
                    // whether this track becomes melodic or a drum kit.
                    // NOT_YET_SUPPORTED (mixed/unresolvable) tracks fall
                    // back to melodic-replace, the safer of the two
                    // (a single clean reassignment rather than appending
                    // to an already-inconsistent list).
                    val isDrumBehavior = when (kind) {
                        GridTrackKind.DRUM_KIT -> true
                        GridTrackKind.MELODIC -> false
                        GridTrackKind.UNASSIGNED -> sample.category == SampleCategory.DRUMS
                        GridTrackKind.NOT_YET_SUPPORTED -> false
                    }
                    if (isDrumBehavior) {
                        if (sample.id in focusedTrack.assignedSampleIds) {
                            viewModel.removeSampleFromTrack(focusedTrackSlot, sample.id)
                        } else {
                            viewModel.addSampleToTrack(focusedTrackSlot, sample.id)
                        }
                    } else {
                        viewModel.assignSampleToTrack(focusedTrackSlot, sample.id)
                    }
                },
                onImport = { uri -> viewModel.importAudioFromUri(uri) }
            )
        }
    }
}

@Composable
private fun TrackSwitcher(
    tracks: List<Track>,
    samples: Map<String, Sample>,
    selectedSlot: Int,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
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
    scaleSelection: ScaleType,
    playheadGridUnitPosition: Float?,
    onCellTap: (gridColumn: Int, target: GridCellTarget) -> Unit,
    onLongPressBlock: (LoopBlock) -> Unit
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
        GridTrackKind.MELODIC -> MelodicGrid(
            track = track,
            scaleSelection = scaleSelection,
            playheadGridUnitPosition = playheadGridUnitPosition,
            onCellTap = onCellTap,
            onLongPressBlock = onLongPressBlock
        )
        GridTrackKind.DRUM_KIT -> DrumGrid(
            track = track,
            samples = samples,
            playheadGridUnitPosition = playheadGridUnitPosition,
            onCellTap = onCellTap,
            onLongPressBlock = onLongPressBlock
        )
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
    scaleSelection: ScaleType,
    playheadGridUnitPosition: Float?,
    onCellTap: (gridColumn: Int, target: GridCellTarget) -> Unit,
    onLongPressBlock: (LoopBlock) -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    // (gridColumn, pitchRow) -> the block occupying that exact cell, if
    // any. Tier 1 blocks are always exactly 1 grid unit wide (no drag-to-
    // stretch yet), so a block occupies exactly one cell -- O(1) lookup per
    // cell while rendering, recomputed only when this track's blocks
    // actually change. Keyed by the actual block (not just a filled
    // boolean) since Tier 2.3's long-press needs the real block id.
    val blocksByCell = remember(track.loopBlocks) {
        track.loopBlocks.filter { it.pitchRow != null }
            .associateBy { it.startGridUnit to it.pitchRow }
    }

    // Tier 2.5: show every row in the chosen scale, PLUS any row with an
    // existing block even if out-of-scale -- never hide a real placed
    // note. CHROMATIC's own interval set is all 12, so this is a no-op
    // filter for it (scaleVisibleSemitones already returns every offset).
    val rows = remember(scaleSelection, blocksByCell) {
        val visible = scaleVisibleSemitones(scaleSelection)
        chromaticPitchRows().filter { offset -> offset in visible || blocksByCell.keys.any { it.second == offset } }
    }

    Box(Modifier.fillMaxSize()) {
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
                    // Shared horizontalScrollState across every row -- all
                    // rows scroll together, same technique ArrangementScreen's
                    // own TrackRow already established for the old timeline.
                    Row(Modifier.horizontalScroll(horizontalScrollState)) {
                        for (column in 0 until GRID_COLUMNS) {
                            val block = blocksByCell[column to row]
                            GridCell(
                                filled = block != null,
                                onTap = { onCellTap(column, GridCellTarget.PitchOffset(row)) },
                                onLongPress = block?.let { b -> { onLongPressBlock(b) } },
                                testTag = "grid_cell_${column}_$row"
                            )
                        }
                    }
                }
            }
        }
        if (playheadGridUnitPosition != null) {
            PlayheadOverlay(playheadGridUnitPosition, horizontalScrollState, ROW_LABEL_WIDTH)
        }
    }
}

/**
 * Tier 2.1: one named row per assigned DRUMS sample, in
 * [Track.assignedSampleIds] order -- structurally the same layout as
 * [MelodicGrid] (outer vertical-scroll Column, per-row Row sharing one
 * horizontal-scroll state), keyed by sampleId instead of a pitch-offset
 * Int. [gridTrackKind] already guarantees every id in assignedSampleIds
 * resolves against [samples] before DRUM_KIT is reached, so no
 * null-fallback lookup is needed here (contrast [trackPillLabel]'s
 * "Unknown" fallback, which handles the general case elsewhere).
 */
@Composable
private fun DrumGrid(
    track: Track,
    samples: Map<String, Sample>,
    playheadGridUnitPosition: Float?,
    onCellTap: (gridColumn: Int, target: GridCellTarget) -> Unit,
    onLongPressBlock: (LoopBlock) -> Unit
) {
    // drumKitRowSampleIds (Tier 2.4) includes orphaned ids -- a sample no
    // longer in assignedSampleIds but still referenced by an existing
    // block, so its row/notes stay visible rather than silently
    // disappearing. mapNotNull rather than getValue: an id that somehow
    // doesn't resolve against the sample library (never expected in
    // practice -- there's no delete-a-sample feature -- but this project's
    // convention throughout is to stay safe rather than crash) just isn't
    // rendered as a row; its underlying block data is untouched either way.
    val rows = remember(track.assignedSampleIds, track.loopBlocks, samples) {
        drumKitRowSampleIds(track).mapNotNull { id -> samples[id]?.let { id to it.name } }
    }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    // (gridColumn, sampleId) -> the block occupying that exact cell, if
    // any. A drum-track block always has pitchRow == null (see
    // LoopBlock.pitchRow's doc comment) -- the opposite filter from
    // MelodicGrid's blocksByCell, which is exactly why a drum row can't
    // reuse an Int row identity.
    val blocksByCell = remember(track.loopBlocks) {
        track.loopBlocks.filter { it.pitchRow == null }
            .associateBy { it.startGridUnit to it.sampleId }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .testTag("grid_drum_canvas")
        ) {
            for ((sampleId, sampleName) in rows) {
                Row(Modifier.height(ROW_HEIGHT)) {
                    Box(
                        Modifier.width(DRUM_ROW_LABEL_WIDTH).fillMaxHeight(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            sampleName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp).testTag("grid_row_label_$sampleId")
                        )
                    }
                    Row(Modifier.horizontalScroll(horizontalScrollState)) {
                        for (column in 0 until GRID_COLUMNS) {
                            val block = blocksByCell[column to sampleId]
                            GridCell(
                                filled = block != null,
                                onTap = { onCellTap(column, GridCellTarget.DrumSample(sampleId)) },
                                onLongPress = block?.let { b -> { onLongPressBlock(b) } },
                                testTag = "grid_cell_${column}_$sampleId"
                            )
                        }
                    }
                }
            }
        }
        if (playheadGridUnitPosition != null) {
            PlayheadOverlay(playheadGridUnitPosition, horizontalScrollState, DRUM_ROW_LABEL_WIDTH)
        }
    }
}

/**
 * Tier 2.3: a vertical line marking the current playback position, in the
 * same grid-unit space every row's columns already use. GridScreen has no
 * single scrollable content box spanning the whole grid the way
 * ArrangementScreen's TrackRow does (each pitch/drum row here owns its own
 * Row(horizontalScroll(...)), sharing one [horizontalScrollState] -- see
 * MelodicGrid/DrumGrid) -- so the offset is computed by hand instead of
 * riding a scrollable container's own layout, combining the fixed row-
 * label width, the column position in px, and the current scroll offset.
 * [rowLabelWidth] varies by which grid is calling this -- MelodicGrid's
 * short offset labels use [ROW_LABEL_WIDTH], DrumGrid's real sample names
 * use the wider [DRUM_ROW_LABEL_WIDTH].
 */
@Composable
private fun PlayheadOverlay(gridUnitPosition: Float, horizontalScrollState: ScrollState, rowLabelWidth: Dp) {
    val density = LocalDensity.current
    val rowLabelWidthPx = with(density) { rowLabelWidth.toPx() }
    val cellWidthPx = with(density) { CELL_WIDTH.toPx() }
    val xPx = rowLabelWidthPx + cellWidthPx * gridUnitPosition - horizontalScrollState.value
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.error)
            .testTag("grid_playhead")
            .clearAndSetSemantics {}
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(filled: Boolean, onTap: () -> Unit, onLongPress: (() -> Unit)?, testTag: String) {
    Box(
        Modifier
            .width(CELL_WIDTH)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .testTag(testTag)
            .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .semantics {
                contentDescription = if (filled) {
                    "Filled cell, tap to remove, long-press to edit"
                } else {
                    "Empty cell, tap to place"
                }
            }
    )
}
