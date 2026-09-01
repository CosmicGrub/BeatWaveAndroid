package com.beatwave.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [GridConstants] -- no device/Robolectric needed.
 * Post-v1 audits/upgrades backlog item A3 (unit test coverage expansion):
 * despite being the ONE shared source of truth for every grid-to-time
 * conversion in the app (mirrored exactly by the native ScoreBuilder, per
 * this object's own class doc), none of its functions -- including the
 * Phase 8 max-song-length addition -- had direct unit coverage before this
 * file. RecordingGridAlignmentTest (instrumented) exercises
 * startGridUnitForFrame/lengthGridUnitsForFrameCount indirectly through a
 * real recording round-trip; these tests instead pin down each formula's
 * exact behavior in isolation, including edge cases a full recording
 * round-trip wouldn't easily hit (frame 0, zero-length spans, empty
 * tracks, etc).
 */
class GridConstantsTest {

    // --- framesPerGridUnit ---

    @Test
    fun framesPerGridUnit_matchesNativeScoreBuilderFormula() {
        // 120 bpm, 48000 Hz: (60 / 120 / 4) * 48000 = 0.125 * 48000 = 6000.
        val perUnit = GridConstants.framesPerGridUnit(bpm = 120, sampleRateHz = 48000)
        assertEquals(6000.0, perUnit, TOLERANCE)
    }

    @Test
    fun framesPerGridUnit_scalesInverselyWithBpm() {
        val slow = GridConstants.framesPerGridUnit(bpm = 60, sampleRateHz = 44100)
        val fast = GridConstants.framesPerGridUnit(bpm = 120, sampleRateHz = 44100)
        // Doubling bpm halves the real-world duration (and hence frame
        // count) of one grid unit.
        assertEquals(slow / 2.0, fast, TOLERANCE)
    }

    // --- startGridUnitForFrame ---

    @Test
    fun startGridUnitForFrame_frameZero_isGridUnitZero() {
        assertEquals(0, GridConstants.startGridUnitForFrame(frame = 0, bpm = 90, sampleRateHz = 48000))
    }

    @Test
    fun startGridUnitForFrame_flooredRatherThanRounded() {
        val perUnit = GridConstants.framesPerGridUnit(bpm = 90, sampleRateHz = 48000)
        // One frame short of the second grid unit must still floor DOWN
        // to grid unit 0, not round up to 1.
        val justBefore = perUnit.toLong() * 2 - 1
        assertEquals(1, GridConstants.startGridUnitForFrame(justBefore, bpm = 90, sampleRateHz = 48000))
    }

    @Test
    fun startGridUnitForFrame_neverNegative() {
        assertEquals(0, GridConstants.startGridUnitForFrame(frame = -100, bpm = 90, sampleRateHz = 48000))
    }

    // --- lengthGridUnitsForFrameCount ---

    @Test
    fun lengthGridUnitsForFrameCount_exactMultiple_noOverRounding() {
        val perUnit = GridConstants.framesPerGridUnit(bpm = 100, sampleRateHz = 44100)
        val exactlyThreeUnits = (perUnit * 3).toLong()
        assertEquals(3, GridConstants.lengthGridUnitsForFrameCount(exactlyThreeUnits, bpm = 100, sampleRateHz = 44100))
    }

    @Test
    fun lengthGridUnitsForFrameCount_roundsUpRatherThanTruncating() {
        val perUnit = GridConstants.framesPerGridUnit(bpm = 100, sampleRateHz = 44100)
        // One frame past two whole units must round UP to 3 -- a recorded
        // block must never be truncated shorter than what was captured.
        val justPastTwoUnits = (perUnit * 2).toLong() + 1
        assertEquals(3, GridConstants.lengthGridUnitsForFrameCount(justPastTwoUnits, bpm = 100, sampleRateHz = 44100))
    }

    @Test
    fun lengthGridUnitsForFrameCount_zeroFrames_isAtLeastOne() {
        assertEquals(1, GridConstants.lengthGridUnitsForFrameCount(0, bpm = 100, sampleRateHz = 44100))
    }

    // --- maxSongLengthGridUnits ---

    @Test
    fun maxSongLengthGridUnits_matchesFourMinutesAtGivenBpm() {
        // 120 bpm, 4 grid units/beat, 240s cap: 240 * 120 * 4 / 60 = 1920.
        assertEquals(1920, GridConstants.maxSongLengthGridUnits(bpm = 120))
    }

    @Test
    fun maxSongLengthGridUnits_scalesWithBpm() {
        val slow = GridConstants.maxSongLengthGridUnits(bpm = 60)
        val fast = GridConstants.maxSongLengthGridUnits(bpm = 120)
        assertEquals(slow * 2, fast)
    }

    // --- defaultStartGridUnit ---

    @Test
    fun defaultStartGridUnit_emptyTrack_isZero() {
        assertEquals(0, GridConstants.defaultStartGridUnit(emptyList()))
    }

    @Test
    fun defaultStartGridUnit_singleBlock_isItsEnd() {
        assertEquals(16, GridConstants.defaultStartGridUnit(listOf(16)))
    }

    @Test
    fun defaultStartGridUnit_multipleBlocks_isTheLatestEndRegardlessOfOrder() {
        // Blocks aren't necessarily supplied in position order (e.g. an
        // earlier block edited to extend past a later one's start) -- the
        // next free slot must be the MAX end, not the last list entry.
        assertEquals(40, GridConstants.defaultStartGridUnit(listOf(8, 40, 20)))
    }

    // --- defaultLengthGridUnits ---

    @Test
    fun defaultLoopRepeats_isFour() {
        // Pinned directly since it's now a public constant other code
        // (and this whole test class) reasons about by value.
        assertEquals(4, GridConstants.DEFAULT_LOOP_REPEATS)
    }

    @Test
    fun defaultLengthGridUnits_isDefaultLoopRepeatsTimesOneRepeat() {
        // 90 bpm, 4 units/beat: msPerGridUnit = 60000/90/4 = 166.67ms.
        // A 500ms sample -> ceil(500/166.67) = 3 grid units for one
        // repeat -> 3 * DEFAULT_LOOP_REPEATS(4) = 12.
        val result = GridConstants.defaultLengthGridUnits(sampleDurationMs = 500L, bpm = 90)
        assertEquals(12, result)
    }

    @Test
    fun defaultLengthGridUnits_roundsUpOneRepeatBeforeMultiplying() {
        // 120 bpm: msPerGridUnit = 60000/120/4 = 125ms. A 130ms sample
        // needs ceil(130/125) = 2 grid units for one repeat, not 1 --
        // multiplying by DEFAULT_LOOP_REPEATS(4) gives 8, not 4.
        val result = GridConstants.defaultLengthGridUnits(sampleDurationMs = 130L, bpm = 120)
        assertEquals(8, result)
    }

    @Test
    fun defaultLengthGridUnits_vanishinglyShortSample_isAtLeastOneBeat() {
        val result = GridConstants.defaultLengthGridUnits(sampleDurationMs = 0L, bpm = 90)
        assertTrue(
            "expected at least GRID_UNITS_PER_BEAT (${GridConstants.GRID_UNITS_PER_BEAT}), got $result",
            result >= GridConstants.GRID_UNITS_PER_BEAT
        )
    }

    @Test
    fun defaultLengthGridUnits_longSample_scalesUpAccordingly() {
        // A long sample's default length must still be a whole multiple of
        // one repeat's grid-unit length, and strictly longer than a short
        // sample's default at the same bpm.
        val short = GridConstants.defaultLengthGridUnits(sampleDurationMs = 200L, bpm = 90)
        val long = GridConstants.defaultLengthGridUnits(sampleDurationMs = 5000L, bpm = 90)
        assertTrue("expected long sample's default ($long) > short sample's ($short)", long > short)
    }

    // --- clampStretchLength (Tier 2.2: grid-sequencer drag-to-stretch) ---

    @Test
    fun clampStretchLength_noCollision_usesTheDesiredLengthAsIs() {
        // Drag from column 3 to (exclusive) column 7 -- desired length 4,
        // nothing occupied anywhere on the row.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = emptyList(),
            dragStartGridUnit = 3,
            desiredEndGridUnitExclusive = 7
        )
        assertEquals(4, result)
    }

    @Test
    fun clampStretchLength_collisionAhead_clampsToStopJustBeforeIt() {
        // Existing block starts at column 10. Dragging from column 3 toward
        // column 20 must clamp to stop at column 10 (exclusive), i.e.
        // length 7 (columns 3..9), never reaching or overlapping column 10.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = listOf(10),
            dragStartGridUnit = 3,
            desiredEndGridUnitExclusive = 20
        )
        assertEquals(7, result)
    }

    @Test
    fun clampStretchLength_usesTheNearestCollisionAhead_notJustAny() {
        // Two blocks ahead, at columns 10 and 15 -- must clamp to the
        // NEARER one (10), not the farther one.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = listOf(15, 10),
            dragStartGridUnit = 3,
            desiredEndGridUnitExclusive = 20
        )
        assertEquals(7, result)
    }

    @Test
    fun clampStretchLength_occupiedCellAtOrBehindDragStart_isIgnored() {
        // A block starting AT or BEFORE the drag's own start column is
        // irrelevant to this drag -- only strictly-ahead occupancy clamps.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = listOf(3, 1),
            dragStartGridUnit = 3,
            desiredEndGridUnitExclusive = 8
        )
        assertEquals(5, result)
    }

    @Test
    fun clampStretchLength_desiredEndAtOrBeforeStart_stillReturnsAtLeastOne() {
        // A drag released with no real forward movement (or a backward
        // drag already clamped to the start column by the caller) must
        // never produce a zero/negative-length block.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = emptyList(),
            dragStartGridUnit = 5,
            desiredEndGridUnitExclusive = 5
        )
        assertEquals(1, result)
    }

    @Test
    fun clampStretchLength_immediatelyAdjacentCollision_clampsToLengthOne() {
        // Existing block starts at the very next column -- the new block
        // must still get its full column at the drag start (length 1), not
        // be squeezed out entirely.
        val result = GridConstants.clampStretchLength(
            occupiedStartGridUnits = listOf(4),
            dragStartGridUnit = 3,
            desiredEndGridUnitExclusive = 10
        )
        assertEquals(1, result)
    }

    companion object {
        private const val TOLERANCE = 0.0001
    }
}
