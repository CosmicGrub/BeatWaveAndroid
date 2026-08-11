package com.beatwave.android

/**
 * Kotlin/JNI bridge to the native audio engine (src/main/cpp), built on
 * Oboe. Kept intentionally narrow per the implementation plan's note that
 * this is the highest-risk interface in the app.
 *
 * Phase 0: just proves the native library loads and can open/close a
 * full-duplex-capable stream. Real mixing (Phase 2), playback control
 * (Phase 3), and recording (Phase 5) are added as native methods here as
 * those phases land — this file is the single narrow surface for all of it.
 */
object AudioEngineBridge {
    init {
        System.loadLibrary("beatwave_audio")
    }

    /** Opens the native audio stream. Returns true if it opened successfully. */
    external fun startEngine(): Boolean

    /** Closes the native audio stream, releasing device resources. */
    external fun stopEngine()
}
