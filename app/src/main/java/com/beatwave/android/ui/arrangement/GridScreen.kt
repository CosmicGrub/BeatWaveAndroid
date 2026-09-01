package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
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
import kotlin.math.abs
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

// Tier 2.2: MelodicGrid's own ruler height (see HorizontalScrubStrip).
private val SCRUB_STRIP_HEIGHT: Dp = 16.dp

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
                onLongPressBlock = { block -> viewModel.openBlockEditor(focusedTrackSlot, block.id) },
                onStretchPlace = { gridColumn, pitchRow, length ->
                    viewModel.placeStretchedBlock(focusedTrackSlot, gridColumn, pitchRow, length)
                }
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
    onLongPressBlock: (LoopBlock) -> Unit,
    onStretchPlace: (gridColumn: Int, pitchRow: Int, length: Int) -> Unit
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
            onLongPressBlock = onLongPressBlock,
            onStretchPlace = onStretchPlace
        )
        // Tier 2.2 (drag-to-stretch) is melodic-only -- a drum one-shot hit
        // has no "stretch" concept, so DrumGrid keeps its Tier 2.1 gesture
        // surface (combinedClickable + direct-drag-to-pan on its own cells)
        // completely unchanged; no scrub strip needed there either, since
        // nothing on its cells competes with panning the way MelodicGrid's
        // new stretch-drag does.
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

/** Tier 2.2: an in-progress drag-to-stretch gesture's live state, shared
 *  across every row of [MelodicGrid] since only one drag can be active at
 *  once. [currentColumn] never goes below [startColumn] -- backward
 *  (leftward) drags are clamped to the start, not supported as a way to
 *  grow a block backward (see the implementation plan's own scoping). */
private data class DragPreview(val pitchRow: Int, val startColumn: Int, val currentColumn: Int)

@Composable
private fun MelodicGrid(
    track: Track,
    scaleSelection: ScaleType,
    playheadGridUnitPosition: Float?,
    onCellTap: (gridColumn: Int, target: GridCellTarget) -> Unit,
    onLongPressBlock: (LoopBlock) -> Unit,
    onStretchPlace: (gridColumn: Int, pitchRow: Int, length: Int) -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var activeDrag by remember { mutableStateOf<DragPreview?>(null) }

    // (gridColumn, pitchRow) -> the block covering that cell, if any.
    // Tier 2.2: a block can now be >1 grid unit wide (drag-to-stretch), so
    // EVERY column within a block's [startGridUnit, startGridUnit +
    // lengthGridUnits) span maps to it, not just its own start column --
    // both for rendering the whole span filled and so a tap/long-press
    // anywhere within it resolves to the right block.
    val blocksByCell = remember(track.loopBlocks) {
        val map = mutableMapOf<Pair<Int, Int>, LoopBlock>()
        for (block in track.loopBlocks) {
            val row = block.pitchRow ?: continue
            for (column in block.startGridUnit until block.startGridUnit + block.lengthGridUnits) {
                map[column to row] = block
            }
        }
        map
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
        Column(Modifier.fillMaxSize()) {
            // Tier 2.2: horizontal panning moves here -- any drag directly
            // on a grid cell now means stretch-place instead (see each
            // row's Row below, horizontalScroll(..., enabled = false)).
            HorizontalScrubStrip(horizontalScrollState)
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
                        // enabled = false: the scroll POSITION still applies
                        // (driven by the shared horizontalScrollState, moved
                        // by HorizontalScrubStrip's own drag above), but this
                        // Row no longer recognizes a direct drag on its own
                        // content as a pan gesture -- that gesture now
                        // belongs to GridCell's stretch-drag handling below.
                        Row(Modifier.horizontalScroll(horizontalScrollState, enabled = false)) {
                            for (column in 0 until GRID_COLUMNS) {
                                val block = blocksByCell[column to row]
                                // Known, accepted limitation: this live
                                // preview shows the RAW drag extent, not
                                // collision-clamped against an existing
                                // block the way the actual commit (via
                                // ArrangementViewModel.placeStretchedBlock,
                                // GridConstants.clampStretchLength) always
                                // is -- dragging past an existing block
                                // previews a longer span than what will
                                // actually be placed. Harmless (an existing
                                // block's own cell already renders filled
                                // regardless), just a cosmetic rough edge
                                // during the drag itself, not at rest.
                                val previewed = activeDrag?.let {
                                    it.pitchRow == row && column in it.startColumn..it.currentColumn
                                } ?: false
                                GridCell(
                                    filled = block != null || previewed,
                                    onTap = { onCellTap(column, GridCellTarget.PitchOffset(row)) },
                                    onLongPress = block?.let { b -> { onLongPressBlock(b) } },
                                    stretchDrag = if (block == null) {
                                        StretchDragHandlers(
                                            onDragStart = { activeDrag = DragPreview(row, column, column) },
                                            onDragUpdate = { deltaColumns ->
                                                activeDrag = activeDrag?.copy(
                                                    currentColumn = (column + deltaColumns).coerceAtLeast(column)
                                                )
                                            },
                                            onDragEnd = {
                                                activeDrag?.let { drag ->
                                                    onStretchPlace(
                                                        drag.startColumn,
                                                        drag.pitchRow,
                                                        drag.currentColumn - drag.startColumn + 1
                                                    )
                                                }
                                                activeDrag = null
                                            }
                                        )
                                    } else {
                                        null
                                    },
                                    testTag = "grid_cell_${column}_$row"
                                )
                            }
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
 * Tier 2.2: a lightweight ruler -- one tick per grid column, a taller tick
 * every [GridConstants.GRID_UNITS_PER_BEAT] columns (a full beat) -- that
 * drives [MelodicGrid]'s horizontal panning via its own drag gesture,
 * reusing [horizontalScrollState] so its content shifts the grid rows
 * below in lockstep. Exists because the grid's own cells now use a direct
 * drag to mean stretch-place instead (see the row Modifier in
 * [MelodicGrid] with `horizontalScroll(..., enabled = false)`); this is
 * the deliberate resolution to that gesture conflict, decided explicitly
 * rather than guessed.
 */
@Composable
private fun HorizontalScrubStrip(horizontalScrollState: ScrollState) {
    Row(Modifier.fillMaxWidth().padding(start = ROW_LABEL_WIDTH)) {
        Row(
            Modifier
                .horizontalScroll(horizontalScrollState)
                .testTag("grid_scrub_strip")
        ) {
            for (column in 0 until GRID_COLUMNS) {
                Box(Modifier.width(CELL_WIDTH).height(SCRUB_STRIP_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    val tall = column % GridConstants.GRID_UNITS_PER_BEAT == 0
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1.dp)
                            .height(if (tall) SCRUB_STRIP_HEIGHT else SCRUB_STRIP_HEIGHT / 2)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }
            }
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
                                stretchDrag = null, // Tier 2.2 is melodic-only
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

/** Tier 2.2: [onDragStart]/[onDragUpdate]/[onDragEnd] for an empty
 *  melodic cell's drag-to-stretch gesture -- all three fire together
 *  (never a subset), see [tapOrDragToStretch]. */
private class StretchDragHandlers(
    val onDragStart: () -> Unit,
    val onDragUpdate: (deltaColumns: Int) -> Unit,
    val onDragEnd: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    filled: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    stretchDrag: StretchDragHandlers?,
    testTag: String
) {
    Box(
        Modifier
            .width(CELL_WIDTH)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .testTag(testTag)
            .then(
                if (stretchDrag != null) {
                    Modifier.tapOrDragToStretch(onTap = onTap, handlers = stretchDrag)
                } else {
                    Modifier.combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
                }
            )
            .semantics {
                contentDescription = if (filled) {
                    "Filled cell, tap to remove, long-press to edit"
                } else {
                    "Empty cell, tap to place"
                }
            }
    )
}

/**
 * Tier 2.2: a single gesture recognizer for an empty melodic cell,
 * replacing [combinedClickable] there -- deliberately NOT stacked
 * alongside a second, independent pointerInput block (Tier 2.3's own
 * research explicitly flagged that risk: two gesture recognizers racing
 * over the same event stream can starve or double-fire each other; see
 * [GridCell]'s own branch between this and combinedClickable for how that
 * risk is avoided -- exactly one recognizer per cell state, never both).
 *
 * A plain press-and-release that never exceeds touch slop means tap
 * (delegates to [onTap] -- 100% unchanged behavior from Tier 1's original
 * tap-to-place, verified by this project's own existing tests still
 * passing unmodified). A press that moves past touch slop before
 * releasing means drag-to-stretch, live-previewed via [handlers] and
 * committed on release. A release that crossed slop by only a
 * sub-cell-width amount naturally produces a 1-column "stretch" through
 * [handlers]'s onDragEnd -- behaviorally identical to a plain tap, so no
 * special-case fallback is needed for that edge case.
 *
 * `combinedClickable`/`clickable` register a real semantics OnClick
 * action for free; a raw pointerInput block does not, so one is added by
 * hand here -- otherwise `performClick()` in tests and TalkBack would
 * both stop working for cells built on this gesture.
 */
private fun Modifier.tapOrDragToStretch(onTap: () -> Unit, handlers: StretchDragHandlers): Modifier =
    this
        .pointerInput(Unit) {
            val cellWidthPx = CELL_WIDTH.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragging = false
                var totalDx = 0f
                var lastReportedDelta = 0
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (dragging) handlers.onDragEnd() else onTap()
                        break
                    }
                    totalDx += change.positionChange().x
                    if (!dragging) {
                        if (abs(totalDx) <= slop) continue
                        // Crossing slop and this event's own delta being
                        // large enough to represent real movement past a
                        // whole cell width are NOT mutually exclusive --
                        // e.g. a fast real drag, or any programmatic/test
                        // touch injection that doesn't emit many small
                        // incremental moves the way a slow physical finger
                        // does, can deliver ONE event whose own delta both
                        // crosses slop AND is the entire drag distance.
                        // Falling through below (not `continue`ing here)
                        // ensures that SAME event's delta still gets
                        // reported as a drag update -- skipping it would
                        // silently lose it, since the next event might be
                        // the release with zero further movement.
                        dragging = true
                        handlers.onDragStart()
                    }
                    change.consume()
                    val deltaColumns = (totalDx / cellWidthPx).toInt()
                    if (deltaColumns != lastReportedDelta) {
                        lastReportedDelta = deltaColumns
                        handlers.onDragUpdate(deltaColumns)
                    }
                }
            }
        }
        .semantics { onClick(label = "Place") { onTap(); true } }
