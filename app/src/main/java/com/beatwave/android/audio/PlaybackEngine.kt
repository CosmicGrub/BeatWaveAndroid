package com.beatwave.android.audio

import android.content.Context
import android.content.Intent
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot of [PlaybackEngine]'s transport/recording/session state -- the
 * single StateFlow both [com.beatwave.android.ui.arrangement.ArrangementViewModel]
 * (via delegation) and [BeatWavePlaybackService]'s Media3 player observe, so
 * the in-app UI and the lock-screen/notification surface always agree on
 * what's actually playing.
 */
data class PlaybackEngineState(
    val engineStarted: Boolean = false,
    val isPlaying: Boolean = false,
    /** True once transport has been explicitly [PlaybackEngine.stop]ped --
     *  distinct from merely paused -- so callers (notably
     *  [BeatWavePlaybackService]) can tell "paused, still resumable" (show a
     *  paused notification, per real media apps' UX) apart from "stopped"
     *  (safe to drop foreground/notification). Starts true (nothing has
     *  played yet, which is a stopped-at-zero state). */
    val isStopped: Boolean = true,
    val currentFrame: Long = 0L,
    val sampleRate: Int = 0,
    val isRecording: Boolean = false,
    val recordedFrameCount: Long = 0L,
    /** Mirrors [AudioEngineBridge.isRecordingCapReached]: becomes true once
     *  an in-progress recording auto-stopped after hitting the native
     *  buffer cap. Reset to false at the start of the next [PlaybackEngine.startRecording]. */
    val recordingCapReached: Boolean = false,
    /** Plain display metadata pushed by whoever owns the current [Project]
     *  (today, ArrangementViewModel) via [PlaybackEngine.updateProjectMetadata] --
     *  kept here (rather than read back from the UI layer) so
     *  [BeatWavePlaybackService] can build lock-screen MediaMetadata with no
     *  dependency on ArrangementViewModel, per the Phase 6 same-process
     *  singleton architecture. */
    val projectName: String? = null,
    val durationFrames: Long = 0L
)

/** Result of [PlaybackEngine.stopRecording]: the transport frame recording
 *  began at (read before the native stop call, per its own ordering
 *  contract) and the number of frames actually captured. */
data class RecordingCaptureResult(val startFrame: Long, val framesWritten: Long)

/**
 * Application-scoped singleton (see [com.beatwave.android.BeatWaveApplication])
 * that owns the native audio engine's lifecycle, transport, and recording --
 * everything [com.beatwave.android.ui.arrangement.ArrangementViewModel] used
 * to own directly through Phase 5. Extracted in Phase 6 so
 * [BeatWavePlaybackService]'s MediaSession can drive the SAME engine
 * instance the app UI drives, making lock-screen/notification controls and
 * in-app controls two views onto one playback session rather than two
 * independent engines.
 *
 * THREADING / SERIALIZATION: [engineMutex] is the direct successor to
 * ArrangementViewModel's old companion-object engineMutex (see that class's
 * Phase 3-5 doc comments) -- every call that touches [AudioEngineBridge]'s
 * engine-lifecycle or schedule-building natives ([initialize], [loadProject],
 * [startRecording], [stopRecording], [shutdown]) holds it for the duration
 * of those native calls, guaranteeing at most one such sequence is ever in
 * flight. Because this class is now itself a true process-wide singleton
 * (rather than a companion object shared across ArrangementViewModel
 * instances), an ordinary instance-scoped Mutex preserves the exact same
 * guarantee -- AudioEngineBridge is still a process-wide singleton either way.
 *
 * [play]/[pause]/[stop]/[seekToFrame] remain cheap/synchronous (matching
 * [AudioEngineBridge]'s own contract) and are safe to call from any thread,
 * including Media3's session/player callbacks.
 */
class PlaybackEngine(context: Context) {

    private val appContext = context.applicationContext
    private val controller = ProjectPlaybackController(appContext)
    private val engineMutex = Mutex()

    /** Long-lived, never cancelled -- this singleton outlives any one
     *  Activity/ViewModel, including the playhead-polling coroutine, which
     *  must keep running while the app is backgrounded (the whole point of
     *  Phase 6's background playback). */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlaybackEngineState())
    val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    private var playheadJob: Job? = null

    // --- Engine lifecycle + schedule building ---

    /** Opens the native engine (idempotent -- a no-op beyond reloading the
     *  score if the engine is already open, e.g. because
     *  [BeatWavePlaybackService] kept it running across an Activity
     *  recreation) and builds+commits the initial score for
     *  [project]/[samples]. Mirrors ArrangementViewModel.init's original
     *  startEngine+nativeInit+loadProject sequence exactly, atomically under
     *  [engineMutex]. Returns true if the engine is open, whether it was
     *  just opened or already was. */
    suspend fun initialize(project: Project, samples: Map<String, Sample>): Boolean = engineMutex.withLock {
        if (!_state.value.engineStarted) {
            val started = AudioEngineBridge.startEngine()
            if (started) {
                controller.nativeInit()
            }
            _state.update { it.copy(engineStarted = started) }
        }
        if (_state.value.engineStarted) {
            controller.loadProject(project, samples)
            _state.update { it.copy(sampleRate = controller.getSampleRate()) }
        }
        _state.value.engineStarted
    }

    /** Rebuilds+recommits the score for [project]/[samples] -- the Phase 6
     *  home for what was ArrangementViewModel.rebuildAndPersist's
     *  engine-touching half, engineMutex-guarded exactly as before. */
    suspend fun loadProject(project: Project, samples: Map<String, Sample>) {
        engineMutex.withLock {
            controller.loadProject(project, samples)
        }
    }

    /** Pushes plain display metadata for the current project -- see
     *  [PlaybackEngineState.projectName]/[PlaybackEngineState.durationFrames]. */
    fun updateProjectMetadata(name: String, durationFrames: Long) {
        _state.update { it.copy(projectName = name, durationFrames = durationFrames) }
    }

    /** Best-effort teardown mirroring the pre-Phase-6
     *  ArrangementViewModel.onCleared() sequence exactly (discard any
     *  in-flight recording, then close the native audio stream) -- called
     *  from ArrangementViewModel.onCleared() so every existing Phase 3-5
     *  instrumented test's cross-test-class engine isolation assumption
     *  stays intact (see e.g. ArrangementScreenPlaybackTest's teardown
     *  comment). Deliberately NOT invoked just because the app is
     *  backgrounded -- backgrounding (Home) leaves the Activity STOPPED,
     *  not destroyed, so onCleared() (and therefore this) never runs; only
     *  a genuine ViewModelStore teardown (Activity finish/task removal, or
     *  process death) reaches here. [discardRecordingFile] is a throwaway
     *  path to write any in-flight recording's frames to before discarding
     *  the file -- the caller owns cleanup of that file afterward. */
    suspend fun shutdown(discardRecordingFile: File) {
        engineMutex.withLock {
            playheadJob?.cancel()
            if (AudioEngineBridge.isRecording()) {
                AudioEngineBridge.stopRecording(discardRecordingFile.absolutePath)
            }
            AudioEngineBridge.stopEngine()
            _state.value = PlaybackEngineState()
        }
    }

    // --- Transport (cheap/safe from any thread, per AudioEngineBridge's own contract) ---

    fun play() {
        controller.play()
        _state.update { it.copy(isPlaying = true, isStopped = false) }
        ensurePlayheadPolling()
        startForegroundSession()
    }

    fun pause() {
        controller.pause()
        playheadJob?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    fun stop() {
        controller.stop()
        playheadJob?.cancel()
        _state.update { it.copy(isPlaying = false, isStopped = true, currentFrame = 0L) }
    }

    fun seekToFrame(frame: Long) {
        controller.seekToFrame(frame)
        _state.update { it.copy(currentFrame = frame) }
    }

    // --- Recording ---

    /** Begins capture and (mirroring the original arm-and-play UX) starts/
     *  continues transport playback in the same engineMutex-guarded call. */
    suspend fun startRecording(): Boolean = engineMutex.withLock {
        val ok = controller.startRecording()
        if (ok) {
            controller.play()
            _state.update {
                it.copy(
                    isPlaying = true,
                    isStopped = false,
                    isRecording = true,
                    recordedFrameCount = 0L,
                    recordingCapReached = false
                )
            }
            ensurePlayheadPolling()
            startForegroundSession()
        }
        ok
    }

    /** Reads the recording start frame BEFORE stopping (its validity after
     *  the native side closes the input stream is unspecified) and stops
     *  capture, both atomically under [engineMutex] -- mirrors
     *  ArrangementViewModel.finalizeRecording's original ordering exactly. */
    suspend fun stopRecording(outputFilePath: String): RecordingCaptureResult = engineMutex.withLock {
        val startFrame = controller.getRecordingStartFrame()
        val framesWritten = controller.stopRecording(outputFilePath)
        _state.update { it.copy(isRecording = false, recordedFrameCount = 0L, recordingCapReached = false) }
        RecordingCaptureResult(startFrame, framesWritten)
    }

    fun getInputLatencyMillis(): Double = controller.getInputLatencyMillis()
    fun getOutputLatencyMillis(): Double = controller.getOutputLatencyMillis()

    // --- Playhead polling (moved verbatim from ArrangementViewModel.startPlayheadPolling) ---

    private fun ensurePlayheadPolling() {
        if (playheadJob?.isActive == true) return
        playheadJob = engineScope.launch {
            while (isActive && _state.value.isPlaying) {
                val frame = controller.getCurrentFrame()
                val recording = _state.value.isRecording
                val recordedFrames = if (recording) controller.getRecordedFrameCount() else 0L
                val capReachedNow = recording && controller.isRecordingCapReached()
                _state.update {
                    it.copy(
                        currentFrame = frame,
                        recordedFrameCount = recordedFrames,
                        recordingCapReached = it.recordingCapReached || capReachedNow
                    )
                }
                delay(PLAYHEAD_POLL_INTERVAL_MS)
            }
        }
    }

    /** Starts [BeatWavePlaybackService] so background playback + remote
     *  controls become available the moment playback begins (design item
     *  5); the service itself promotes to a foreground service with the
     *  lock-screen/notification surface once Media3's own
     *  MediaNotificationManager connects and determines playback is
     *  ongoing. The service reads its state from THIS singleton (see
     *  [com.beatwave.android.BeatWaveApplication]), so no data needs to be
     *  passed via the Intent. Safe to call repeatedly -- starting an
     *  already-running started service just redelivers onStartCommand.
     *
     *  Deliberately a plain [Context.startService] rather than
     *  [ContextCompat.startForegroundService]: the latter requires
     *  [android.app.Service.startForeground] to be called within a short,
     *  strict OS-enforced window (a
     *  [android.app.ForegroundServiceDidNotStartInTimeException]/
     *  RemoteServiceException otherwise) -- but Media3's own
     *  MediaNotificationManager promotes the service asynchronously (it
     *  connects an internal MediaController to the session first), with no
     *  such hard deadline attached to a plain startService() call. This is
     *  always called from code running as a direct result of user
     *  interaction in this app's own foreground UI (a Play tap, or a
     *  lock-screen/notification media action once the service is already
     *  running), so the "can't start a service from the background"
     *  restriction doesn't apply here either. */
    private fun startForegroundSession() {
        runCatching {
            appContext.startService(Intent(appContext, BeatWavePlaybackService::class.java))
        }
    }

    companion object {
        private const val PLAYHEAD_POLL_INTERVAL_MS = 50L
    }
}
