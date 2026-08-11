package com.beatwave.android.audio

/**
 * The single shared definition of BeatWave's arrangement grid: one grid
 * unit = one 16th note, i.e. 4 grid units per quarter-note beat.
 *
 * The native engine (see app/src/main/cpp/ScoreBuilder.cpp's
 * ScoreBuilder::begin, mandate 1 of the Phase 2 plan) computes
 * `framesPerGridUnit = (60.0 / bpm / 4.0) * sampleRateHz` once at
 * schedule-build time. [framesPerGridUnit] below mirrors that exact
 * formula so any Kotlin-side code -- notably the Phase 3 timeline UI -- can
 * reason about grid-to-time conversion without redefining "4" (or the
 * formula) somewhere else and risking drift between Kotlin and native.
 */
object GridConstants {
    /** Grid units per quarter-note beat (1 grid unit = one 16th note). */
    const val GRID_UNITS_PER_BEAT: Int = 4

    /** Mirrors the native ScoreBuilder's mandate-1 computation exactly. */
    fun framesPerGridUnit(bpm: Int, sampleRateHz: Int): Double =
        (60.0 / bpm.toDouble() / GRID_UNITS_PER_BEAT.toDouble()) * sampleRateHz.toDouble()

    // --- Phase 5 additions: recording-placement grid math -----------------
    // Extracted out of ArrangementViewModel.stopRecording() (rather than
    // left inline there) so this is the ONE shared implementation used by
    // both the production placement code and the RecordingGridAlignmentTest
    // instrumented test (mandate 11a explicitly requires exercising "the
    // REAL production conversion code, not reimplemented in the test").

    /** Which grid unit an absolute transport [frame] falls in, via
     *  [framesPerGridUnit]. Floored, never negative. */
    fun startGridUnitForFrame(frame: Long, bpm: Int, sampleRateHz: Int): Int {
        val perUnit = framesPerGridUnit(bpm, sampleRateHz)
        return (frame.toDouble() / perUnit).toInt().coerceAtLeast(0)
    }

    /** How many whole grid units a span of [frameCount] frames occupies,
     *  via [framesPerGridUnit]. Rounded UP (a block must never be truncated
     *  shorter than what was actually recorded/imported) and always at
     *  least 1. */
    fun lengthGridUnitsForFrameCount(frameCount: Long, bpm: Int, sampleRateHz: Int): Int {
        val perUnit = framesPerGridUnit(bpm, sampleRateHz)
        return kotlin.math.ceil(frameCount.toDouble() / perUnit).toInt().coerceAtLeast(1)
    }
}
