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

    // --- Phase 8: max song length (design spec's "max song length ~2-4
    // minutes", and explicitly listed under "Out of Scope for v1": "Unlimited
    // tracks or song length"). Track count was already hard-capped at 8
    // (ArrangementViewModel.MAX_TRACKS) since Phase 1, but nothing capped
    // overall arrangement length -- ArrangementViewModel.addLoopToSelectedTrack
    // always appended after a track's existing content with no bound, so
    // repeatedly tapping "Add" could grow a song indefinitely. Expressed
    // directly in grid units (independent of sample rate -- a grid unit's
    // real-world duration is purely a function of bpm, see
    // [framesPerGridUnit]'s formula), so callers never need a sample rate
    // just to check this. ---

    /** Spec's stated upper bound. */
    const val MAX_SONG_LENGTH_SECONDS: Int = 240 // 4 minutes

    /** The grid-unit position at/beyond which a song has reached
     *  [MAX_SONG_LENGTH_SECONDS] at [bpm]. */
    fun maxSongLengthGridUnits(bpm: Int): Int =
        (MAX_SONG_LENGTH_SECONDS * bpm.toDouble() * GRID_UNITS_PER_BEAT.toDouble() / 60.0).toInt()

    // --- Post-v1 audits/upgrades backlog, item A3 (unit test coverage
    // expansion): default loop-placement math, extracted out of
    // ArrangementViewModel.addLoopToSelectedTrack() (where it lived as two
    // private functions) so it's directly unit-testable without
    // instantiating an AndroidViewModel/Context -- same rationale as the
    // Phase 5 extraction of startGridUnitForFrame/lengthGridUnitsForFrameCount
    // above. Kept in terms of primitives (not the Track/LoopBlock/Sample
    // domain types) so this object stays dependency-free of data.model, same
    // as every other function here.

    /** How many times a newly-placed loop repeats by default, before any
     *  manual trim/edit. */
    const val DEFAULT_LOOP_REPEATS: Int = 4

    /** Next free grid unit after a track's existing blocks, given each
     *  existing block's end position (its startGridUnit + lengthGridUnits).
     *  0 if the track is empty. */
    fun defaultStartGridUnit(existingBlockEndGridUnits: Collection<Int>): Int =
        existingBlockEndGridUnits.maxOrNull() ?: 0

    /** [DEFAULT_LOOP_REPEATS]x a sample's natural length in grid units,
     *  rounded up, minimum one beat. */
    fun defaultLengthGridUnits(sampleDurationMs: Long, bpm: Int): Int {
        val msPerGridUnit = 60000.0 / bpm.toDouble() / GRID_UNITS_PER_BEAT.toDouble()
        val oneRepeatGridUnits =
            kotlin.math.ceil(sampleDurationMs.toDouble() / msPerGridUnit).toInt().coerceAtLeast(1)
        return (oneRepeatGridUnits * DEFAULT_LOOP_REPEATS).coerceAtLeast(GRID_UNITS_PER_BEAT)
    }

    // --- Grid-sequencer redesign (2026-08-24 spec), Tier 2.2: drag-to-
    // stretch placement math. Kept here rather than in ArrangementViewModel
    // for the same reason as every other function in this object: pure,
    // primitive-typed, directly unit-testable without a Compose/ViewModel
    // harness -- see GridConstantsTest.

    /** Clamps a drag-to-stretch block's length so it never overlaps an
     *  already-occupied cell on the same row. [occupiedStartGridUnits] are
     *  that row's OTHER existing blocks' own start columns (the caller
     *  pre-filters to one row -- this function stays dependency-free of the
     *  data.model domain, same as every other function here). Only the
     *  nearest occupied start STRICTLY AHEAD of [dragStartGridUnit] can
     *  clamp it; anything at or behind the drag's own start is irrelevant.
     *  Always returns at least 1 (never a degenerate/zero-length block),
     *  even for a drag released with no real forward movement. */
    fun clampStretchLength(
        occupiedStartGridUnits: Collection<Int>,
        dragStartGridUnit: Int,
        desiredEndGridUnitExclusive: Int
    ): Int {
        val nearestOccupiedAhead = occupiedStartGridUnits.filter { it > dragStartGridUnit }.minOrNull()
        val clampedEnd = if (nearestOccupiedAhead != null) {
            minOf(desiredEndGridUnitExclusive, nearestOccupiedAhead)
        } else {
            desiredEndGridUnitExclusive
        }
        return (clampedEnd - dragStartGridUnit).coerceAtLeast(1)
    }
}
