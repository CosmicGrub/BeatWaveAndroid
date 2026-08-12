package com.beatwave.android.ui.arrangement

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.audio.GridConstants
import com.beatwave.android.audio.PlaybackEngine
import com.beatwave.android.data.library.AssetLoopLibrary
import com.beatwave.android.data.library.AudioImporter
import com.beatwave.android.data.library.ImportedSampleIndex
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil

/** Identifies a specific loop block currently open in the editor sheet. */
data class TrackBlockRef(val trackSlot: Int, val blockId: String)

/**
 * A successfully-decoded import (Phase 4 design item 3/4) waiting on the
 * user's category choice before [ArrangementViewModel.confirmPendingImport]
 * finalizes it into a [Sample]. [filePath] is the absolute filesystem path
 * of the WAV file [AudioImporter] already wrote into app-private storage.
 */
data class PendingImportSample(
    val filePath: String,
    val displayName: String,
    val durationMs: Long
)

/**
 * A successfully-stopped recording (Phase 5 design item 10) waiting on the
 * user's category choice before [ArrangementViewModel.confirmPendingRecording]
 * finalizes it into a [Sample] AND auto-places a [LoopBlock] on the armed
 * track -- mirrors [PendingImportSample]'s Phase 4 shape, but additionally
 * carries the placement ([trackSlot]/[startGridUnit]/[lengthGridUnits])
 * already computed (via [GridConstants]) at the moment recording stopped,
 * since a recording -- unlike a plain library import -- always produces a
 * placed block, never just a library entry.
 */
data class PendingRecordingSample(
    val trackSlot: Int,
    val filePath: String,
    val displayName: String,
    val startGridUnit: Int,
    val lengthGridUnits: Int,
    val durationMs: Long
)

/**
 * All UI-observable state for [ArrangementScreen] and its children. [project]
 * is null only during the brief initial load in [ArrangementViewModel.init].
 *
 * [samples]/[sampleList] hold the ONE combined loop library shown in
 * [LoopLibraryBottomSheet] -- bundled samples from [AssetLoopLibrary] merged
 * with imported samples from [ImportedSampleIndex] (Phase 4 design item 6).
 * An imported sample is indistinguishable from a bundled one anywhere this
 * state flows: same map, same list, same placement path.
 *
 * [isPlaying]/[currentFrame]/[sampleRate]/[recordedFrameCount] mirror
 * [PlaybackEngine.state] (Phase 6) -- this class no longer owns the native
 * transport directly, it just reflects the app-wide [PlaybackEngine]
 * singleton's state into UI-observable form (see the class doc below).
 */
data class ArrangementUiState(
    val project: Project? = null,
    val samples: Map<String, Sample> = emptyMap(),
    val sampleList: List<Sample> = emptyList(),
    val selectedTrackSlot: Int? = null,
    val isPlaying: Boolean = false,
    val currentFrame: Long = 0L,
    val sampleRate: Int = 0,
    val editingBlock: TrackBlockRef? = null,
    val pendingImport: PendingImportSample? = null,
    /** The track slot currently armed+recording, or null if no recording is
     *  in progress. Only one recording can be in flight at a time -- the
     *  native engine has a single pre-allocated capture buffer, not one per
     *  track -- so this is Kotlin-side bookkeeping of which track "owns"
     *  the in-progress recording; the native engine itself has no notion of
     *  tracks during capture. */
    val recordingTrackSlot: Int? = null,
    /** Live progress (frames captured so far) for [recordingTrackSlot]'s
     *  indicator, polled the same way [currentFrame] already is. */
    val recordedFrameCount: Long = 0L,
    val pendingRecording: PendingRecordingSample? = null,
    val message: String? = null,
    /** True while [ArrangementViewModel.exportProject] has an offline render
     *  in flight -- lets the UI disable the Export button/show a spinner
     *  rather than let a second tap start a redundant concurrent render. */
    val isExporting: Boolean = false,
    /** Set by [ArrangementViewModel.exportProject] the moment a render
     *  finishes successfully; the absolute path of the freshly-written WAV
     *  file the UI should hand to Android's share sheet (Phase 7 design item
     *  "Export/Share"). One-shot -- consumed via
     *  [ArrangementViewModel.shareFileConsumed] the same way [pendingImport]/
     *  [pendingRecording] are consumed by their own confirm/cancel calls. */
    val pendingShareFilePath: String? = null
)

/**
 * Owns the current [Project] arrangement and mediates between the Compose
 * UI, [ProjectRepository] (persistence), [AssetLoopLibrary] (bundled loop
 * metadata), and -- as of Phase 6 -- the app-wide [PlaybackEngine] singleton
 * (native playback engine transport/recording), per the Phase 6 design.
 *
 * PHASE 6 REFACTOR: through Phase 5, this class owned the native engine's
 * lifecycle (nativeInit/startEngine/stopEngine), the engineMutex
 * serializing every native-touching call, transport (play/pause/stop/seek),
 * the playhead-polling coroutine, and recording start/stop directly. All of
 * that moved OUT of this class and INTO [PlaybackEngine] (an application-
 * scoped singleton exposed via [BeatWaveApplication.playbackEngine]) so
 * [com.beatwave.android.audio.BeatWavePlaybackService]'s MediaSession can
 * drive the exact same engine instance this ViewModel drives -- lock-screen/
 * notification controls and in-app controls are now two views onto one
 * playback session. This class now DELEGATES every engine-touching
 * operation to [playbackEngine] and combines its [PlaybackEngine.state]
 * StateFlow into [uiState], rather than owning the engine directly. Every
 * behavior a Phase 3-5 instrumented test depends on (play/pause/stop/seek/
 * record semantics, transport values read back via
 * [com.beatwave.android.AudioEngineBridge]) is preserved exactly -- only
 * *where* the engine lifecycle/mutex/polling loop lives has changed.
 *
 * SCHEDULE-BUILDING STILL LIVES HERE: [rebuildAndPersist] (loop block add/
 * edit/delete) still calls [PlaybackEngine.loadProject] -- that native call
 * is engineMutex-guarded *inside* PlaybackEngine now, preserving the same
 * "at most one beginProject/.../commitProject (or startEngine/stopEngine)
 * sequence in flight" guarantee the Phase 3 doc originally described,
 * without this class needing direct access to the mutex.
 *
 * PLACEMENT INTERACTION (documented per the implementation plan's request):
 * the user first taps a track row to select it (highlighted), then opens the
 * loop library and taps "Add" on a loop card. [addLoopToSelectedTrack] is a
 * no-op (with a user-facing message) if no track is currently selected.
 *
 * DEFAULT BLOCK LENGTH: a newly added block is sized to 4x the sample's
 * natural loop length, rounded up to a whole grid unit (see
 * [defaultLengthGridUnits]) -- long enough to be immediately useful without
 * further editing, short enough not to dominate the timeline. Its start
 * position defaults to the next free grid unit after the track's last
 * existing block (or 0 if the track is empty) -- see [defaultStartGridUnit].
 */
class ArrangementViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository.forContext(application)
    private val assetLoopLibrary = AssetLoopLibrary(application)

    /** The app-wide singleton every engine-touching call below delegates
     *  to -- see the class doc's PHASE 6 REFACTOR note. */
    private val playbackEngine: PlaybackEngine =
        (application as BeatWaveApplication).playbackEngine

    // Phase 4: SAF import pipeline collaborators. Neither touches the
    // native engine directly -- decode/persistence only -- so neither needs
    // to go through playbackEngine; see importAudioFromUri/confirmPendingImport below.
    private val audioImporter = AudioImporter(application)
    private val importedSampleIndex = ImportedSampleIndex.forContext(application)

    private val _uiState = MutableStateFlow(ArrangementUiState())
    val uiState: StateFlow<ArrangementUiState> = _uiState.asStateFlow()

    private var previewPlayer: MediaPlayer? = null

    init {
        // Reflect PlaybackEngine's transport/recording state into uiState
        // for the lifetime of this ViewModel -- this is what replaces the
        // old direct playheadJob-driven _uiState.update calls (Phase 3-5).
        viewModelScope.launch {
            playbackEngine.state.collect { engineState ->
                _uiState.update {
                    it.copy(
                        isPlaying = engineState.isPlaying,
                        currentFrame = engineState.currentFrame,
                        sampleRate = engineState.sampleRate,
                        recordedFrameCount = engineState.recordedFrameCount
                    )
                }
            }
        }
        // Mandate 3 (Phase 5): auto-finalize a recording that hit the
        // native buffer cap, exactly as the old in-ViewModel polling loop
        // did -- reacts only on the rising edge (distinctUntilChanged) so a
        // burst of state emissions while finalize is in flight doesn't
        // re-trigger the message/finalize repeatedly.
        viewModelScope.launch {
            playbackEngine.state.map { it.recordingCapReached }.distinctUntilChanged().collect { capReached ->
                if (capReached && _uiState.value.recordingTrackSlot != null) {
                    _uiState.update { it.copy(message = "Recording reached the maximum length and was stopped automatically.") }
                    stopRecording()
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            val loadedProject = repository.load(PROJECT_ID)
            // Merge bundled + imported samples into the ONE combined library
            // (design item 6) -- background-dispatched same as the rest of
            // this init block. importedSampleIndex.load() is plain JSON file
            // I/O, not a native engine call, so it does NOT need to go
            // through playbackEngine.
            val bundledSamples = assetLoopLibrary.loadSamples()
            val importedSamples = importedSampleIndex.load()
            val samples = (bundledSamples + importedSamples).associateBy { it.id }
            val project = loadedProject ?: Project(
                id = PROJECT_ID,
                name = "My Project",
                bpm = DEFAULT_BPM,
                tracks = (1..MAX_TRACKS).map { slot -> Track(slot = slot) },
                createdAtEpochMs = System.currentTimeMillis(),
                modifiedAtEpochMs = System.currentTimeMillis()
            )

            val started = playbackEngine.initialize(project, samples)
            pushProjectMetadata(project)

            _uiState.update {
                it.copy(
                    project = project,
                    samples = samples,
                    sampleList = samples.values.sortedBy { sample -> sample.name },
                    sampleRate = playbackEngine.state.value.sampleRate,
                    message = if (started) null else "Audio engine failed to start"
                )
            }
        }
    }

    // --- Track selection ---

    fun selectTrack(slot: Int) {
        _uiState.update {
            it.copy(selectedTrackSlot = if (it.selectedTrackSlot == slot) null else slot)
        }
    }

    // --- Loop placement ---

    fun addLoopToSelectedTrack(sample: Sample) {
        val current = _uiState.value
        val project = current.project ?: return
        val trackSlot = current.selectedTrackSlot
        if (trackSlot == null) {
            _uiState.update { it.copy(message = "Select a track first, then add a loop.") }
            return
        }
        val track = project.tracks.firstOrNull { it.slot == trackSlot } ?: return

        val newBlock = LoopBlock(
            id = UUID.randomUUID().toString(),
            sampleId = sample.id,
            startGridUnit = defaultStartGridUnit(track),
            lengthGridUnits = defaultLengthGridUnits(sample, project.bpm)
        )
        val newTracks = project.tracks.map { t ->
            if (t.slot == trackSlot) t.copy(loopBlocks = t.loopBlocks + newBlock) else t
        }
        rebuildAndPersist(project.copy(tracks = newTracks, modifiedAtEpochMs = System.currentTimeMillis()))
    }

    /** Next free grid unit after the track's last existing block, or 0 if empty. */
    private fun defaultStartGridUnit(track: Track): Int =
        track.loopBlocks.maxOfOrNull { it.startGridUnit + it.lengthGridUnits } ?: 0

    /** 4x the sample's natural length in grid units, rounded up, minimum one beat. */
    private fun defaultLengthGridUnits(sample: Sample, bpm: Int): Int {
        val msPerGridUnit = 60000.0 / bpm.toDouble() / GridConstants.GRID_UNITS_PER_BEAT.toDouble()
        val oneRepeatGridUnits = ceil(sample.durationMs.toDouble() / msPerGridUnit).toInt().coerceAtLeast(1)
        return (oneRepeatGridUnits * DEFAULT_LOOP_REPEATS).coerceAtLeast(GridConstants.GRID_UNITS_PER_BEAT)
    }

    // --- Loop block editing ---

    fun openBlockEditor(trackSlot: Int, blockId: String) {
        _uiState.update { it.copy(editingBlock = TrackBlockRef(trackSlot, blockId)) }
    }

    fun closeBlockEditor() {
        _uiState.update { it.copy(editingBlock = null) }
    }

    fun updateBlock(
        trackSlot: Int,
        blockId: String,
        volume: Float,
        trimStartMs: Long,
        trimEndMs: Long?,
        pitchSemitones: Float
    ) {
        val project = _uiState.value.project ?: return
        val newTracks = project.tracks.map { t ->
            if (t.slot != trackSlot) t else t.copy(
                loopBlocks = t.loopBlocks.map { b ->
                    if (b.id != blockId) b else b.copy(
                        volume = volume,
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        pitchSemitones = pitchSemitones
                    )
                }
            )
        }
        rebuildAndPersist(project.copy(tracks = newTracks, modifiedAtEpochMs = System.currentTimeMillis()))
        closeBlockEditor()
    }

    fun deleteBlock(trackSlot: Int, blockId: String) {
        val project = _uiState.value.project ?: return
        val newTracks = project.tracks.map { t ->
            if (t.slot != trackSlot) t else t.copy(loopBlocks = t.loopBlocks.filterNot { it.id == blockId })
        }
        rebuildAndPersist(project.copy(tracks = newTracks, modifiedAtEpochMs = System.currentTimeMillis()))
        closeBlockEditor()
    }

    /** Rebuilds+recommits the native playback score and auto-saves, both off
     *  the main thread, per the threading contract. [PlaybackEngine.loadProject]
     *  is internally engineMutex-guarded (see that class's SERIALIZATION
     *  note), so two rebuilds fired in quick succession (e.g. two "Add"
     *  taps) never race on the native ScoreBuilder's unsynchronized staging
     *  state -- exactly the guarantee this method provided directly through
     *  Phase 5. */
    private fun rebuildAndPersist(newProject: Project) {
        _uiState.update { it.copy(project = newProject) }
        val samples = _uiState.value.samples
        pushProjectMetadata(newProject)
        viewModelScope.launch(Dispatchers.Default) {
            playbackEngine.loadProject(newProject, samples)
            repository.save(newProject)
        }
    }

    /** Pushes the current project's display name and duration (max loop
     *  block end, converted to frames) into [PlaybackEngine.state] so
     *  [com.beatwave.android.audio.BeatWavePlaybackService] can build
     *  lock-screen MediaMetadata without depending on this ViewModel (Phase
     *  6 design item 3). Best-effort: if the sample rate isn't known yet
     *  (engine not started), duration is left at 0 -- refreshed on the next
     *  project mutation or engine (re)initialization. */
    private fun pushProjectMetadata(project: Project) {
        val sampleRate = playbackEngine.state.value.sampleRate
        val maxBlockEndGridUnit = project.tracks.flatMap { it.loopBlocks }
            .maxOfOrNull { it.startGridUnit + it.lengthGridUnits } ?: 0
        val durationFrames = if (sampleRate > 0) {
            (maxBlockEndGridUnit * GridConstants.framesPerGridUnit(project.bpm, sampleRate)).toLong()
        } else {
            0L
        }
        playbackEngine.updateProjectMetadata(project.name, durationFrames)
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    // --- Playback transport (cheap/safe from any thread, per PlaybackEngine's own contract) ---

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            // Defense-in-depth guard mirroring the UI's own disabled state
            // (see ArrangementScreen's PlaybackControlBar): pausing mid-
            // recording would stop draining the still-open, still-capturing
            // input stream (onAudioReady returns early on !mPlaying, before
            // it ever reaches the recording-capture block), letting real
            // hardware audio silently accumulate/overflow in Oboe's own
            // buffer until playback resumes -- corrupting the take's
            // alignment. Only the per-track Stop-record affordance and the
            // global Stop button (stopPlayback, which finalizes an
            // in-progress recording) may end a recording session.
            if (_uiState.value.recordingTrackSlot != null) return
            playbackEngine.pause()
        } else {
            playbackEngine.play()
        }
    }

    fun stopPlayback() {
        viewModelScope.launch(Dispatchers.Default) {
            // A live recording has no valid "finished take" if the transport
            // is about to be reset to 0 out from under it. AWAIT (don't
            // fire-and-forget) the SAME finalize path the per-track Stop
            // button uses (see [ensureRecordingFinalizing]/[finalizeRecording])
            // before touching the native transport below -- both paths touch
            // the SAME engine state (mRecording/mRecordingStartFrame/the
            // input stream) via PlaybackEngine's engineMutex, and must not
            // interleave. A fast Stop-then-Play could otherwise resume
            // native capture into a not-yet-finalized recording buffer,
            // indexed against a transport that's already been reset to 0.
            ensureRecordingFinalizing()?.join()
            playbackEngine.stop()
        }
    }

    fun seekToGridUnit(gridUnit: Int) {
        // Guard against a discontinuous transport jump corrupting an
        // in-progress recording: captureRecordingFrames (native) derives
        // every frame's recording-buffer index from transportFrame -
        // recordingStartFrame every callback (mandate 4) -- an intervening
        // seek would jump-cut the recording's own timeline (skipped frames
        // going forward, an overlapping rewrite going backward) with no
        // warning to the user. Only the per-track Stop-record affordance and
        // the global Stop button may end a recording session.
        if (_uiState.value.recordingTrackSlot != null) {
            _uiState.update { it.copy(message = "Can't seek while recording -- stop the recording first.") }
            return
        }
        val project = _uiState.value.project ?: return
        val sampleRate = playbackEngine.state.value.sampleRate
        if (sampleRate <= 0) return
        val framesPerGridUnit = GridConstants.framesPerGridUnit(project.bpm, sampleRate)
        val frame = (gridUnit.coerceAtLeast(0) * framesPerGridUnit).toLong()
        playbackEngine.seekToFrame(frame)
    }

    // --- One-shot loop preview (independent of the arrangement engine) ---

    fun previewSample(sample: Sample) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                previewPlayer?.apply {
                    setOnCompletionListener(null)
                    release()
                }
                previewPlayer = null
                val player = MediaPlayer()
                when (val source = sample.source) {
                    is SampleSource.BundledAsset -> {
                        getApplication<Application>().assets.openFd(source.assetPath).use { afd ->
                            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                    }
                    is SampleSource.ImportedFile -> {
                        // Phase 4: an absolute filesystem path (see the
                        // ImportedFile doc comment), which MediaPlayer can
                        // open directly via the String overload.
                        player.setDataSource(source.uri)
                    }
                }
                player.setOnCompletionListener { mp -> mp.release() }
                player.prepare()
                player.start()
                previewPlayer = player
            } catch (t: Throwable) {
                // Preview is a non-critical convenience; swallow failures.
            }
        }
    }

    // --- SAF import pipeline (Phase 4 design items 3-6) ---
    //
    // Neither importAudioFromUri nor confirmPendingImport touches the native
    // engine -- decode is pure Kotlin (AudioImporter), and persistence is a
    // plain JSON file write (ImportedSampleIndex) -- so neither needs
    // playbackEngine. The merged sample only reaches the engine later,
    // through the exact same addLoopToSelectedTrack -> rebuildAndPersist
    // path every bundled sample already uses -- no special-casing needed
    // there.

    /** Kicks off the decode pipeline for a SAF-picked [uri] (see
     *  [LoopLibraryBottomSheet]'s "Import from device" button). Runs off the
     *  main thread per [AudioImporter]'s contract. On success, stashes the
     *  decoded file as [ArrangementUiState.pendingImport] so the UI can show
     *  [CategoryPickerDialog]; on failure, surfaces the error via the
     *  existing [ArrangementUiState.message] Snackbar path instead of
     *  crashing. */
    fun importAudioFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            audioImporter.import(uri).fold(
                onSuccess = { imported ->
                    _uiState.update {
                        it.copy(
                            pendingImport = PendingImportSample(
                                filePath = imported.file.absolutePath,
                                displayName = imported.displayName,
                                durationMs = imported.durationMs
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(message = error.message ?: "Failed to import audio file.") }
                }
            )
        }
    }

    /** Dismisses [ArrangementUiState.pendingImport] without adding it to the
     *  library (user cancelled the category prompt). The already-decoded WAV
     *  file is no longer referenced by anything once dismissed, so it is
     *  deleted off the main thread to avoid leaving an orphaned file under
     *  `filesDir/imported_samples/` on every cancel. Best-effort: a failed
     *  delete here isn't user-visible and isn't worth surfacing. */
    fun cancelPendingImport() {
        val pending = _uiState.value.pendingImport
        _uiState.update { it.copy(pendingImport = null) }
        if (pending != null) {
            viewModelScope.launch(Dispatchers.IO) {
                File(pending.filePath).delete()
            }
        }
    }

    /** Finalizes [ArrangementUiState.pendingImport] into a [Sample] tagged
     *  with the user's chosen [category], persists it to
     *  [ImportedSampleIndex], and merges it into the SAME [samples]/
     *  [sampleList] state bundled loops already flow through -- this is what
     *  makes it show up, be tappable, and be placeable identically to a
     *  bundled loop (design item 6). */
    fun confirmPendingImport(category: SampleCategory) {
        val pending = _uiState.value.pendingImport ?: return
        _uiState.update { it.copy(pendingImport = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val sample = Sample(
                id = UUID.randomUUID().toString(),
                name = pending.displayName,
                category = category,
                source = SampleSource.ImportedFile(uri = pending.filePath),
                durationMs = pending.durationMs
            )
            importedSampleIndex.add(sample)
            _uiState.update { current ->
                val mergedSamples = current.samples + (sample.id to sample)
                current.copy(
                    samples = mergedSamples,
                    sampleList = mergedSamples.values.sortedBy { s -> s.name },
                    message = "Imported \"${sample.name}\""
                )
            }
        }
    }

    // --- Export/Share (Phase 7) ---
    //
    // Renders the current project offline to a WAV file (via
    // [PlaybackEngine.exportToFile], a throwaway offline engine entirely
    // separate from the live one -- exporting never interrupts ongoing
    // playback) and stashes the result as [ArrangementUiState.pendingShareFilePath]
    // for [ArrangementScreen] to hand to Android's native share sheet -- the
    // same one-shot-state-then-consume shape [pendingImport]/[pendingRecording]
    // already use, just consumed by firing an Intent instead of showing a
    // dialog. Written under `cacheDir/exports/` (not filesDir, unlike
    // recordings/imports): an export is a disposable rendering of data that
    // already lives in the project, not source-of-truth data itself, so it's
    // fine for the OS to reclaim this directory under storage pressure.

    /** Renders the current project to a shareable WAV file. No-ops (with a
     *  user-facing message) if there's nothing placed on the timeline yet,
     *  or if a previous export is still in flight. */
    fun exportProject() {
        val current = _uiState.value
        val project = current.project
        if (current.isExporting) return
        if (project == null || project.tracks.all { it.loopBlocks.isEmpty() }) {
            _uiState.update { it.copy(message = "Add some loops to the timeline before exporting.") }
            return
        }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val exportsDir = File(getApplication<Application>().cacheDir, EXPORTS_DIR_NAME)
            if (!exportsDir.exists()) exportsDir.mkdirs()
            val outputFile = File(exportsDir, "${sanitizeFileName(project.name)}.wav")

            val success = playbackEngine.exportToFile(project, current.samples, outputFile.absolutePath)
            _uiState.update {
                it.copy(
                    isExporting = false,
                    pendingShareFilePath = if (success) outputFile.absolutePath else null,
                    message = if (success) null else "Export failed. Please try again."
                )
            }
        }
    }

    /** Consumes [ArrangementUiState.pendingShareFilePath] once
     *  [ArrangementScreen] has fired the share Intent for it -- mirrors
     *  [cancelPendingImport]/[confirmPendingImport] clearing their own
     *  one-shot state after acting on it. */
    fun shareFileConsumed() {
        _uiState.update { it.copy(pendingShareFilePath = null) }
    }

    /** Loop block names/BPM are free text (see [Project.name]); a share
     *  target's filename can't contain path separators or most punctuation.
     *  Keeps only alphanumerics/spaces/dashes/underscores, collapses
     *  anything else to "_", and falls back to a fixed name if that leaves
     *  nothing usable (e.g. an emoji-only project name). */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c -> if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_') c else '_' }
            .joinToString("")
            .trim()
        return cleaned.ifEmpty { "BeatWave Project" }
    }

    // --- Recording (Phase 5 design items 9-10) ---
    //
    // Mirrors the SAF import pipeline's pending-then-confirm shape
    // (PendingImportSample/confirmPendingImport, Phase 4) but for a
    // freshly recorded take: [stopRecording] stashes a
    // [PendingRecordingSample] so the UI can show the SAME
    // [CategoryPickerDialog], and [confirmPendingRecording] finalizes it
    // into a Sample AND auto-places a LoopBlock on the armed track via the
    // EXISTING [rebuildAndPersist] path every other project mutation in
    // this class already uses. Android permission handling (RECORD_AUDIO)
    // lives in ArrangementScreen, not here -- these methods assume the
    // caller has already confirmed the permission is granted before calling
    // [startRecording].

    /** Job for the currently in-flight (or most recently launched) recording
     *  finalize coroutine -- see [ensureRecordingFinalizing]/
     *  [finalizeRecording]. [Volatile] because it's read/written from both
     *  the main thread (a direct [stopRecording] tap) and from
     *  [stopPlayback]'s own background coroutine, which needs to reliably
     *  see a finalize [stopRecording] just kicked off so it can await the
     *  SAME job rather than racing a second, duplicate finalize attempt. */
    @Volatile
    private var recordingFinalizeJob: Job? = null

    /** Requests the native engine to begin capture for [trackSlot] (the
     *  track the user tapped Record on). Also starts/continues arrangement
     *  playback per the design spec's arm-and-play UX, via
     *  [PlaybackEngine.startRecording] -- never duplicating its logic.
     *  No-ops (with a message) if a recording is already in progress on
     *  another track, since the native engine supports only one recording
     *  at a time. */
    fun startRecording(trackSlot: Int) {
        val current = _uiState.value
        if (current.recordingTrackSlot != null) {
            _uiState.update { it.copy(message = "A recording is already in progress on another track.") }
            return
        }
        if (current.project == null) return
        // Optimistically claim the recording slot synchronously, BEFORE the
        // async engine call completes -- otherwise a rapid double-tap on the
        // SAME track's Record button would have both taps read
        // recordingTrackSlot == null (the first tap's coroutine hasn't
        // updated state yet) and both pass the guard above, racing two
        // native startRecording() calls (the native side rejects the
        // second, surfacing a misleading "microphone unavailable" message
        // even though a recording is in fact already running). Mirrors how
        // [finalizeRecording] already updates recordingTrackSlot synchronously
        // relative to its own native call.
        _uiState.update { it.copy(recordingTrackSlot = trackSlot, recordedFrameCount = 0L) }
        viewModelScope.launch(Dispatchers.Default) {
            val started = playbackEngine.startRecording()
            if (!started) {
                _uiState.update {
                    it.copy(recordingTrackSlot = null, message = "Couldn't start recording -- microphone unavailable.")
                }
            }
        }
    }

    /** Called by ArrangementScreen when the user declines the RECORD_AUDIO
     *  permission prompt -- surfaces a clear message via the existing
     *  Snackbar-style [ArrangementUiState.message] field rather than
     *  silently no-opping. */
    fun recordingPermissionDenied() {
        _uiState.update { it.copy(message = "Microphone permission is needed to record.") }
    }

    /** Called by ArrangementScreen when the user declines the (Phase 6,
     *  API 33+) POST_NOTIFICATIONS permission prompt shown the first time
     *  playback starts -- mirrors [recordingPermissionDenied] exactly.
     *  Playback and the background foreground service are unaffected by
     *  this denial (see BeatWavePlaybackService); only the lock-screen/
     *  notification surface won't be visible, which this message makes
     *  clear, once, rather than silently no-opping. */
    fun notificationPermissionDenied() {
        _uiState.update { it.copy(message = "Notification permission is needed to show playback controls on the lock screen.") }
    }

    /** Starts finalizing the in-progress recording if one hasn't already
     *  been kicked off (idempotent -- returns the existing in-flight job
     *  instead of launching a duplicate), or returns null if nothing is
     *  currently recording. Callers that need the native transport left in
     *  a consistent state relative to the finalize (see [stopPlayback])
     *  MUST await the returned [Job] before touching engine state
     *  themselves -- see [finalizeRecording]'s doc comment for the
     *  regression this closes. */
    private fun ensureRecordingFinalizing(): Job? {
        recordingFinalizeJob?.takeIf { it.isActive }?.let { return it }
        if (_uiState.value.recordingTrackSlot == null) return null
        val job = viewModelScope.launch(Dispatchers.Default) { finalizeRecording() }
        recordingFinalizeJob = job
        return job
    }

    /** Called by ArrangementScreen when the user taps a track's Stop-record
     *  affordance. Fire-and-forget from the UI's perspective (matches the
     *  original signature/call site), but internally routes through
     *  [ensureRecordingFinalizing] so a subsequent [stopPlayback] can await
     *  the SAME job instead of racing a second finalize attempt. */
    fun stopRecording() {
        ensureRecordingFinalizing()
    }

    /** Stops the in-progress recording (see [startRecording]), reads back
     *  the real captured start frame + frame count via
     *  [PlaybackEngine.stopRecording] (which uses the SAME native
     *  derivation the live callback used), and either discards a too-short
     *  accidental take or stashes it as [ArrangementUiState.pendingRecording]
     *  so the UI shows [CategoryPickerDialog] (reused verbatim from Phase
     *  4) before finalizing it (see [confirmPendingRecording]).
     *
     *  [ArrangementUiState.recordingTrackSlot] is deliberately left non-null
     *  until AFTER [PlaybackEngine.stopRecording] actually completes
     *  (rather than being nulled synchronously up front) -- this keeps
     *  every recordingTrackSlot-gated guard (the global Play/Pause button's
     *  disabled state, [togglePlayPause]'s pause guard, [seekToGridUnit]'s
     *  seek guard) accurate for the WHOLE finalize window, not just until
     *  this function happens to be entered. Only reachable via
     *  [ensureRecordingFinalizing], which dedupes concurrent callers via
     *  [recordingFinalizeJob] so this never runs twice for the same
     *  recording. Always call via [ensureRecordingFinalizing] -- never
     *  launch this directly. */
    private suspend fun finalizeRecording() {
        val current = _uiState.value
        val trackSlot = current.recordingTrackSlot ?: return
        val project = current.project ?: return
        val sampleRate = current.sampleRate

        val recordingsDir = File(getApplication<Application>().filesDir, RECORDINGS_DIR_NAME)
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
        val outputFile = File(recordingsDir, "${UUID.randomUUID()}.wav")

        val result = playbackEngine.stopRecording(outputFile.absolutePath)
        val startFrame = result.startFrame
        val framesWritten = result.framesWritten

        // Only now -- after the native engine has actually stopped
        // capturing and closed the input stream -- is it safe to treat this
        // track as "not recording" again (see this function's doc comment).
        _uiState.update { it.copy(recordingTrackSlot = null) }

        if (framesWritten <= 0L || sampleRate <= 0) {
            outputFile.delete()
            _uiState.update { it.copy(message = "Recording failed -- nothing was captured.") }
            return
        }

        val durationMs = framesWritten * 1000L / sampleRate.toLong()
        if (durationMs < MIN_RECORDING_DURATION_MS) {
            outputFile.delete()
            _uiState.update { it.copy(message = "Recording too short -- discarded.") }
            return
        }

        // Grid-alignment math (mandates 4/10): both values derived via
        // the SAME GridConstants conversion functions
        // RecordingGridAlignmentTest (mandate 11a) exercises directly --
        // never a separately invented/duplicated formula.
        val startGridUnit = GridConstants.startGridUnitForFrame(startFrame, project.bpm, sampleRate)
        val lengthGridUnits = GridConstants.lengthGridUnitsForFrameCount(framesWritten, project.bpm, sampleRate)

        _uiState.update {
            it.copy(
                pendingRecording = PendingRecordingSample(
                    trackSlot = trackSlot,
                    filePath = outputFile.absolutePath,
                    displayName = "Recording ${recordingTimestampLabel()}",
                    startGridUnit = startGridUnit,
                    lengthGridUnits = lengthGridUnits,
                    durationMs = durationMs
                )
            )
        }
    }

    /** Dismisses [ArrangementUiState.pendingRecording] without adding it to
     *  the library (mirrors [cancelPendingImport] exactly) -- the WAV file
     *  is no longer referenced by anything once dismissed, so it's deleted
     *  off the main thread. Best-effort: a failed delete here isn't
     *  user-visible and isn't worth surfacing. */
    fun cancelPendingRecording() {
        val pending = _uiState.value.pendingRecording
        _uiState.update { it.copy(pendingRecording = null) }
        if (pending != null) {
            viewModelScope.launch(Dispatchers.IO) {
                File(pending.filePath).delete()
            }
        }
    }

    /** Finalizes [ArrangementUiState.pendingRecording] into a [Sample]
     *  tagged with the user's chosen [category] (mirrors
     *  [confirmPendingImport]), persists it via the EXISTING
     *  [ImportedSampleIndex] (a recording and an import are structurally
     *  identical Sample entries from the model's perspective), merges it
     *  into the SAME combined samples/sampleList state, AND -- unlike a
     *  plain import -- auto-places a new [LoopBlock] on the armed track at
     *  the grid position already computed in [stopRecording], via the
     *  EXISTING [rebuildAndPersist] path every other project mutation in
     *  this class already uses. */
    fun confirmPendingRecording(category: SampleCategory) {
        val pending = _uiState.value.pendingRecording ?: return
        val project = _uiState.value.project
        _uiState.update { it.copy(pendingRecording = null) }
        if (project == null) return
        viewModelScope.launch(Dispatchers.Default) {
            val sample = Sample(
                id = UUID.randomUUID().toString(),
                name = pending.displayName,
                category = category,
                source = SampleSource.ImportedFile(uri = pending.filePath),
                durationMs = pending.durationMs
            )
            importedSampleIndex.add(sample)

            val newBlock = LoopBlock(
                id = UUID.randomUUID().toString(),
                sampleId = sample.id,
                startGridUnit = pending.startGridUnit,
                lengthGridUnits = pending.lengthGridUnits
            )
            val newTracks = project.tracks.map { t ->
                if (t.slot == pending.trackSlot) t.copy(loopBlocks = t.loopBlocks + newBlock) else t
            }
            val newProject = project.copy(tracks = newTracks, modifiedAtEpochMs = System.currentTimeMillis())

            // Merge the new sample into the ONE combined library BEFORE
            // rebuildAndPersist reads _uiState.value.samples (it resolves
            // every block's sample id against that map) -- mirrors
            // confirmPendingImport's merge step exactly.
            _uiState.update { current ->
                val mergedSamples = current.samples + (sample.id to sample)
                current.copy(
                    samples = mergedSamples,
                    sampleList = mergedSamples.values.sortedBy { s -> s.name },
                    message = "Recorded \"${sample.name}\""
                )
            }
            rebuildAndPersist(newProject)
        }
    }

    private fun recordingTimestampLabel(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    override fun onCleared() {
        super.onCleared()
        previewPlayer?.release()
        previewPlayer = null
        // viewModelScope is already cancelled by the time onCleared runs, and
        // teardown (when it happens at all -- see the guard below) must be
        // deterministic (not fire-and-forget) so a fast subsequent
        // ArrangementViewModel's playbackEngine.initialize() can never
        // overlap with this shutdown() -- block on it via PlaybackEngine's
        // own internal engineMutex. Blocking here is an acceptable tradeoff
        // since onCleared is not a hot path.
        //
        // NOTE: this deliberately does NOT run just because the app is
        // backgrounded (Home) -- the Activity is only STOPPED, not
        // destroyed, so onCleared() never fires from that alone; only a
        // genuine ViewModelStore teardown (Activity finish/task removal, or
        // process death) reaches here.
        //
        // GUARD (fixes a real Phase 6 regression a code review caught): a
        // genuine ViewModelStore teardown can ALSO happen while transport is
        // still playing -- e.g. the user swipes the app away from Recents,
        // or finishes the root Activity, while music is playing. That is
        // very different from "Home-press backgrounding": ComponentActivity.
        // onDestroy()/ViewModelStore.clear() DO run in that case, so an
        // unconditional shutdown() here would tear down the one shared
        // native engine out from under BeatWavePlaybackService, which is
        // still running as an independent foreground service on the SAME
        // PlaybackEngine singleton -- silently killing "background playback"
        // for exactly the scenario Phase 6 exists to support. So: only tear
        // the engine down here when transport is genuinely STOPPED
        // ([PlaybackEngineState.isStopped]) -- the same condition
        // BeatWavePlaybackService's own stateObserverJob already uses to
        // decide it's safe to stopSelf(). If transport is playing, or merely
        // paused-but-resumable (isStopped == false), leave the engine (and
        // Service) alone; a subsequent ArrangementViewModel's
        // playbackEngine.initialize() is written to be idempotent against an
        // already-open engine, and the Service's own state collector will
        // eventually tear things down once transport genuinely stops. This
        // still preserves every existing Phase 3-5 instrumented test's
        // cross-test-class engine isolation assumption, because each of
        // those tests explicitly stops transport (via the UI's Stop button)
        // before its ActivityScenario.close() -- so isStopped is already
        // true by the time onCleared() runs for them.
        if (!playbackEngine.state.value.isStopped) return
        runBlocking(Dispatchers.Default) {
            val recordingsDir = File(getApplication<Application>().filesDir, RECORDINGS_DIR_NAME)
            if (!recordingsDir.exists()) recordingsDir.mkdirs()
            val discardFile = File(recordingsDir, ".discard-${UUID.randomUUID()}.wav")
            playbackEngine.shutdown(discardFile)
            discardFile.delete()
        }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val DEFAULT_BPM = 90
        private const val MAX_TRACKS = 8
        private const val DEFAULT_LOOP_REPEATS = 4

        /** Sibling of [com.beatwave.android.data.library.AudioImporter]'s
         *  `imported_samples` directory -- see design item 10. */
        private const val RECORDINGS_DIR_NAME = "recordings"

        /** Below this, a stopped recording is treated as an accidental tap
         *  and discarded rather than becoming a degenerate near-zero block
         *  (design item 10). */
        private const val MIN_RECORDING_DURATION_MS = 250L

        /** Phase 7: where rendered export WAVs are written, under
         *  `cacheDir` rather than `filesDir` -- see the Export/Share
         *  section's doc comment for why. */
        private const val EXPORTS_DIR_NAME = "exports"
    }
}
