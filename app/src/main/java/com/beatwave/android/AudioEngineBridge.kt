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
 * Recording (Phase 5) is added as native methods here too, when it lands --
 * this file stays the single narrow surface for all of it.
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

    external fun nativeTestDestroyOfflineEngine(handle: Long)
}
