package com.beatwave.android

import android.content.res.AssetManager

/**
 * Kotlin/JNI bridge to the native audio engine (src/main/cpp), built on
 * Oboe. Kept intentionally narrow per the implementation plan's note that
 * this is the highest-risk interface in the app.
 *
 * Phase 0: just proves the native library loads and can open/close a
 * full-duplex-capable stream (startEngine/stopEngine).
 *
 * Phase 2 adds the real multi-track, sample-accurate, drift-free loop
 * mixing engine's controls: building a playback schedule (nativeInit/
 * beginProject/addTrack/addLoopBlock/commitProject) and transport control
 * (play/pause/stop/seekToFrame/getCurrentFrame/getSampleRate), plus
 * test-only diagnostic natives that drive an offline engine instance
 * without a real Oboe stream (see the instrumented sync test).
 *
 * THREADING CONTRACT: nativeInit, beginProject, addTrack, addLoopBlock,
 * commitProject, and every nativeTest* schedule-building method do asset
 * I/O, heap allocation, and (addLoopBlock) sample decode/resample -- call
 * these ONLY from a background thread, never the main/UI thread. See
 * com.beatwave.android.audio.ProjectPlaybackController for the intended
 * call sequence. play/pause/stop/seekToFrame/getCurrentFrame/getSampleRate
 * are cheap atomic reads/writes and safe from any thread.
 *
 * Recording (Phase 5) is added as native methods here too -- see the
 * "Recording" section below. This file stays the single narrow surface for
 * all of it.
 */
object AudioEngineBridge {
    init {
        System.loadLibrary("beatwave_audio")
    }

    /** Opens the native audio stream. Returns true if it opened successfully. */
    external fun startEngine(): Boolean

    /** Closes the native audio stream, releasing device resources. */
    external fun stopEngine()

    // --- Schedule building (background thread only -- see class doc) ---

    /** Must be called once, from a background thread, before any other
     *  schedule-building or addLoopBlock call -- gives the native engine an
     *  AAssetManager to decode bundled/imported WAV assets through. */
    external fun nativeInit(assetManager: AssetManager)

    /** Starts building a new score off the audio thread. Call start() (to
     *  open the real stream) before this, so the engine's real negotiated
     *  sample rate is known. */
    external fun beginProject(bpm: Int)

    external fun addTrack(slot: Int)

    /** Triggers decode+resample+cache of sampleAssetPath (if not already
     *  cached) and schedules the block into the in-progress score. Pass -1
     *  for [trimEndMs] to mean "null / to the end of the sample". Returns
     *  true if the block was scheduled. */
    external fun addLoopBlock(
        trackSlot: Int,
        sampleAssetPath: String,
        startGridUnit: Int,
        lengthGridUnits: Int,
        volume: Float,
        trimStartMs: Long,
        trimEndMs: Long,
        pitchSemitones: Float
    ): Boolean

    /** Finalizes and atomically publishes the new score to the realtime
     *  mixing callback. */
    external fun commitProject()

    // --- Transport controls (safe from any thread) ---

    external fun play()
    external fun pause()

    /** Stops transport playback and resets the transport counter to 0. */
    external fun stop()

    external fun seekToFrame(frame: Long)
    external fun getCurrentFrame(): Long

    /** The engine's real negotiated output sample rate, or 0 if not yet known. */
    external fun getSampleRate(): Int

    // --- Recording (Phase 5, safe from any thread) ---
    //
    // Full-duplex capture against the SAME live engine instance already
    // driving playback: the input stream is opened lazily (only on the
    // first startRecording() call, only after RECORD_AUDIO is confirmed
    // granted -- see com.beatwave.android.ui.arrangement.ArrangementScreen's
    // permission flow) and read synchronously, once per output-callback
    // invocation, from inside the existing onAudioReady callback (per the
    // Phase 5 design doc). Recording position is derived every callback
    // from the SAME absolute transport counter that already drives
    // playback (mirroring mandate 6's derivation philosophy), so a
    // recorded take is time-aligned with whatever tracks are already
    // playing by construction -- no separate sync problem to solve.
    //
    // Like the transport controls above these are cheap lifecycle/atomic
    // calls safe from any thread, but callers should still route them
    // through com.beatwave.android.ui.arrangement.ArrangementViewModel's
    // companion-object engineMutex like every other engine-touching call
    // (see that class's SERIALIZATION note) -- see
    // com.beatwave.android.audio.ProjectPlaybackController for the intended
    // call sequence.

    /** Begins capture against the live engine, opening the input stream if
     *  this is the first recording since [startEngine] (or since the last
     *  [stopRecording]). Returns false if permission hasn't been granted or
     *  the input stream can't be opened (e.g. no usable input device) --
     *  also requires the output stream to already be open, i.e.
     *  [startEngine] already called successfully. */
    external fun startRecording(): Boolean

    /** Stops capture, closes the input stream, and writes the valid
     *  captured portion of the pre-allocated recording buffer out to
     *  [outputFilePath] as a canonical 16-bit PCM RIFF/WAVE file. Returns
     *  the number of frames written, or -1 on failure (e.g. nothing was
     *  ever recorded). */
    external fun stopRecording(outputFilePath: String): Long

    external fun isRecording(): Boolean

    /** The absolute transport frame recording began at -- captured once, at
     *  the moment [startRecording] succeeded, from the SAME absolute
     *  transport counter mandate 6 (Phase 2) already maintains for
     *  playback. Used for grid-alignment math on the Kotlin side (see
     *  com.beatwave.android.audio.GridConstants). Read this BEFORE calling
     *  [stopRecording] -- its value after the input stream closes is
     *  unspecified. */
    external fun getRecordingStartFrame(): Long

    /** Frames captured so far in the current (or most recently finished)
     *  recording -- for a live UI progress indicator, polled the same way
     *  [getCurrentFrame] already is. */
    external fun getRecordedFrameCount(): Long

    /** Input stream latency in milliseconds (oboe::AudioStream::
     *  calculateLatencyMillis(), or the current Oboe API's equivalent), or
     *  -1.0 if the input stream isn't currently open. */
    external fun getInputLatencyMillis(): Double

    /** Output stream latency in milliseconds, or -1.0 if the output stream
     *  isn't currently open. */
    external fun getOutputLatencyMillis(): Double

    /** True once the current (or most recently finished) recording hit the
     *  native ~3-minute pre-allocated buffer cap and capture was stopped
     *  server-side (mandate 3). Poll this the same way [getRecordedFrameCount]
     *  is already polled and, on seeing it become true, auto-stop the
     *  recording gracefully with whatever was captured. Resets to false at
     *  the start of the next [startRecording]. */
    external fun isRecordingCapReached(): Boolean

    // --- Test-only diagnostic natives ---
    // Each operates on its own offline engine instance (opaque handle),
    // entirely separate from the live engine above, so an instrumented test
    // can build a score and advance it without a real live Oboe stream.
    // Internally these reuse the exact same scheduling/mixing code as the
    // methods above -- see AudioEngine.h's class doc comment.

    /** Creates a fresh offline engine instance at [sampleRate], not attached
     *  to any real Oboe stream. Returns an opaque handle for the other
     *  nativeTest* calls; release it with [nativeTestDestroyOfflineEngine]. */
    external fun nativeTestCreateOfflineEngine(assetManager: AssetManager, sampleRate: Int): Long

    external fun nativeTestBeginProject(handle: Long, bpm: Int)
    external fun nativeTestAddTrack(handle: Long, slot: Int)

    external fun nativeTestAddLoopBlock(
        handle: Long,
        trackSlot: Int,
        sampleAssetPath: String,
        startGridUnit: Int,
        lengthGridUnits: Int,
        volume: Float,
        trimStartMs: Long,
        trimEndMs: Long,
        pitchSemitones: Float
    ): Boolean

    external fun nativeTestCommitProject(handle: Long)

    /** Renders numFrames through the real mix function into a scratch
     *  buffer (discarded) and advances the handle's transport counter by
     *  numFrames -- exercises the same derivation path as live playback. */
    external fun nativeTestAdvanceOffline(handle: Long, numFrames: Int)

    /** The resolved (post-schedule-build) blockStartFrame actually used
     *  internally, or -1 if trackSlot/blockIndex doesn't resolve. */
    external fun nativeTestGetBlockStartFrame(handle: Long, trackSlot: Int, blockIndex: Int): Long

    /** The resolved (post-trim, post-resample, post-pitch) loopContentLengthFrames
     *  actually used internally, or -1 if trackSlot/blockIndex doesn't resolve. */
    external fun nativeTestGetLoopContentLengthFrames(handle: Long, trackSlot: Int, blockIndex: Int): Long

    /** The CURRENT loopLocalFrame for that block, derived fresh from the
     *  handle's current transport position via the real derivation code, or
     *  -1 if it doesn't resolve / isn't currently active. */
    external fun nativeTestGetLoopLocalFrame(handle: Long, trackSlot: Int, blockIndex: Int): Long

    // -- Offline recording diagnostics (Phase 5, mandate 8) --------------
    // Mirror the live startRecording/stopRecording/isRecording/
    // getRecordingStartFrame/getRecordedFrameCount natives above, but
    // operate on an offline handle and are driven by nativeTestAdvanceOffline
    // (silence in place of real hardware input) rather than a live Oboe
    // input stream. Reuses the exact same mandate-4 derivation function as
    // the live path -- see AudioEngine.h's class doc comment.

    /** Begins an offline recording against [handle] at its current transport
     *  frame (captured as recordingStartFrame), mirroring startRecording(). */
    external fun nativeTestStartRecording(handle: Long)

    /** Stops the offline recording and writes the captured (silent) frames
     *  to a real WAV file at [outputFilePath], mirroring stopRecording().
     *  Returns the number of frames written, or -1 on failure. */
    external fun nativeTestStopRecording(handle: Long, outputFilePath: String): Long

    external fun nativeTestIsRecording(handle: Long): Boolean

    /** The transport frame the offline recording began at -- for verifying
     *  the mandate-4 derivation against a deliberately non-grid-aligned
     *  simulated start position. */
    external fun nativeTestGetRecordingStartFrame(handle: Long): Long

    external fun nativeTestGetRecordedFrameCount(handle: Long): Long

    external fun nativeTestDestroyOfflineEngine(handle: Long)

    // --- Offline export (Phase 7, background thread only) ---
    // Functionally identical to the nativeTest* offline-engine family above
    // (same underlying AudioEngine offline-mode construction/scheduling) --
    // kept as separate entry points purely so production export code (see
    // com.beatwave.android.audio.PlaybackEngine.exportToFile) never calls
    // anything named "Test". See audio_engine_jni.cpp's Phase 7 section for
    // the full rationale.

    /** Creates a fresh offline engine instance at [sampleRate] for
     *  rendering, not attached to any real Oboe stream. Returns an opaque
     *  handle for the other nativeExport* calls; release it with
     *  [nativeExportDestroyEngine]. */
    external fun nativeExportCreateEngine(assetManager: AssetManager, sampleRate: Int): Long

    external fun nativeExportBeginProject(handle: Long, bpm: Int)
    external fun nativeExportAddTrack(handle: Long, slot: Int)

    external fun nativeExportAddLoopBlock(
        handle: Long,
        trackSlot: Int,
        sampleAssetPath: String,
        startGridUnit: Int,
        lengthGridUnits: Int,
        volume: Float,
        trimStartMs: Long,
        trimEndMs: Long,
        pitchSemitones: Float
    ): Boolean

    external fun nativeExportCommitProject(handle: Long)

    /** Renders the full [totalFrames]-frame arrangement already committed on
     *  [handle] and writes it to [outputFilePath] as a canonical 16-bit PCM
     *  RIFF/WAVE file. Returns [totalFrames] on success, or -1 on failure. */
    external fun nativeExportRenderToFile(handle: Long, totalFrames: Long, outputFilePath: String): Long

    external fun nativeExportDestroyEngine(handle: Long)
}
