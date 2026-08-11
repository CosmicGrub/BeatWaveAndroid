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
}
