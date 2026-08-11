package com.beatwave.android.ui.arrangement

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.audio.GridConstants
import com.beatwave.android.audio.ProjectPlaybackController
import com.beatwave.android.data.library.AssetLoopLibrary
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/** Identifies a specific loop block currently open in the editor sheet. */
data class TrackBlockRef(val trackSlot: Int, val blockId: String)

/**
 * All UI-observable state for [ArrangementScreen] and its children. [project]
 * is null only during the brief initial load in [ArrangementViewModel.init].
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
    val message: String? = null
)

/**
 * Owns the current [Project] arrangement, mediates between the Compose UI,
 * [ProjectRepository] (persistence), [AssetLoopLibrary] (bundled loop
 * metadata), and [ProjectPlaybackController]/[AudioEngineBridge] (native
 * playback engine), per the Phase 3 design.
 *
 * THREADING: every call that rebuilds/commits the native playback schedule
 * (initial load, add/edit/delete of a loop block) is dispatched via
 * `viewModelScope.launch(Dispatchers.Default)`, per AudioEngineBridge's
 * threading contract -- those calls do asset I/O, allocation, and sample
 * decode/resample and must never run on the main thread. Transport controls
 * (play/pause/stop/seek/getCurrentFrame/getSampleRate) are cheap atomic ops
 * and are called directly from UI event handlers.
 *
 * SERIALIZATION: the native engine's schedule-building calls
 * (nativeInit/beginProject/addTrack/addLoopBlock/commitProject) stage into a
 * single unsynchronized ScoreBuilder that is only safe for one background
 * caller at a time -- it is NOT safe for two of those sequences to run
 * concurrently. Because `startEngine`/`stopEngine` also touch that same
 * singleton native engine, [engineMutex] is a *companion-object* (process-
 * wide, shared across every ArrangementViewModel instance) Mutex that every
 * background coroutine touching the engine -- [init], [rebuildAndPersist],
 * and [onCleared]'s teardown -- must hold for the duration of its native
 * calls. This guarantees at most one beginProject/.../commitProject (or
 * startEngine/stopEngine) sequence is ever in flight, even across rapid
 * back-to-back UI actions or a fast ViewModel recreation. Coroutine
 * cancellation is deliberately NOT used to abort an in-flight rebuild --
 * the JNI calls are synchronous/blocking and can't be interrupted
 * mid-call -- so this queues via the mutex instead of cancel-and-relaunch.
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
    private val playbackController = ProjectPlaybackController(application)

    private val _uiState = MutableStateFlow(ArrangementUiState())
    val uiState: StateFlow<ArrangementUiState> = _uiState.asStateFlow()

    private var playheadJob: Job? = null
    private var previewPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val loadedProject = repository.load(PROJECT_ID)
            val samples = assetLoopLibrary.loadSamples().associateBy { it.id }
            val project = loadedProject ?: Project(
                id = PROJECT_ID,
                name = "My Project",
                bpm = DEFAULT_BPM,
                tracks = (1..MAX_TRACKS).map { slot -> Track(slot = slot) },
                createdAtEpochMs = System.currentTimeMillis(),
                modifiedAtEpochMs = System.currentTimeMillis()
            )

            val started = engineMutex.withLock {
                val ok = AudioEngineBridge.startEngine()
                playbackController.nativeInit()
                playbackController.loadProject(project, samples)
                ok
            }

            _uiState.update {
                it.copy(
                    project = project,
                    samples = samples,
                    sampleList = samples.values.sortedBy { sample -> sample.name },
                    sampleRate = playbackController.getSampleRate(),
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
     *  the main thread, per the threading contract. Serialized via
     *  [engineMutex] against every other engine-touching coroutine (see the
     *  class doc's SERIALIZATION note) so two rebuilds fired in quick
     *  succession (e.g. two "Add" taps) never race on the native
     *  ScoreBuilder's unsynchronized staging state. */
    private fun rebuildAndPersist(newProject: Project) {
        _uiState.update { it.copy(project = newProject) }
        val samples = _uiState.value.samples
        viewModelScope.launch(Dispatchers.Default) {
            engineMutex.withLock {
                playbackController.loadProject(newProject, samples)
                repository.save(newProject)
            }
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    // --- Playback transport (cheap/safe from any thread, per AudioEngineBridge doc) ---

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            playbackController.pause()
            playheadJob?.cancel()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            playbackController.play()
            _uiState.update { it.copy(isPlaying = true) }
            startPlayheadPolling()
        }
    }

    fun stopPlayback() {
        playbackController.stop()
        playheadJob?.cancel()
        _uiState.update { it.copy(isPlaying = false, currentFrame = 0L) }
    }

    fun seekToGridUnit(gridUnit: Int) {
        val project = _uiState.value.project ?: return
        val sampleRate = playbackController.getSampleRate()
        if (sampleRate <= 0) return
        val framesPerGridUnit = GridConstants.framesPerGridUnit(project.bpm, sampleRate)
        val frame = (gridUnit.coerceAtLeast(0) * framesPerGridUnit).toLong()
        playbackController.seekToFrame(frame)
        _uiState.update { it.copy(currentFrame = frame) }
    }

    private fun startPlayheadPolling() {
        playheadJob?.cancel()
        playheadJob = viewModelScope.launch {
            while (isActive && _uiState.value.isPlaying) {
                val frame = playbackController.getCurrentFrame()
                _uiState.update { it.copy(currentFrame = frame) }
                delay(PLAYHEAD_POLL_INTERVAL_MS)
            }
        }
    }

    // --- One-shot loop preview (independent of the arrangement engine) ---

    fun previewSample(sample: Sample) {
        val bundled = sample.source as? SampleSource.BundledAsset ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                previewPlayer?.apply {
                    setOnCompletionListener(null)
                    release()
                }
                previewPlayer = null
                val player = MediaPlayer()
                getApplication<Application>().assets.openFd(bundled.assetPath).use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
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

    override fun onCleared() {
        super.onCleared()
        playheadJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        // viewModelScope is already cancelled by the time onCleared runs, and
        // teardown must be deterministic (not fire-and-forget) so a fast
        // subsequent ArrangementViewModel's startEngine() can never overlap
        // with this stopEngine() -- block on it via the same companion-wide
        // engineMutex used by every other engine-touching coroutine. Blocking
        // here is an acceptable tradeoff since onCleared is not a hot path.
        runBlocking(Dispatchers.Default) {
            engineMutex.withLock {
                AudioEngineBridge.stopEngine()
            }
        }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val DEFAULT_BPM = 90
        private const val MAX_TRACKS = 8
        private const val DEFAULT_LOOP_REPEATS = 4
        private const val PLAYHEAD_POLL_INTERVAL_MS = 50L

        /** Serializes every coroutine that touches [AudioEngineBridge]'s
         *  engine-lifecycle or schedule-building calls -- shared across all
         *  ArrangementViewModel instances (companion-object scope) because
         *  AudioEngineBridge itself is a process-wide singleton. See the
         *  class doc's SERIALIZATION note. */
        private val engineMutex = Mutex()
    }
}
