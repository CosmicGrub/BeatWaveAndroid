package com.beatwave.android.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for Phase 5's grid-alignment exit criterion: "a recorded
 * take lands aligned to the grid relative to concurrently playing tracks (no
 * drift)".
 *
 * Per mandate 11's testing boundary, a real human voice can't be scripted, so
 * this asserts nothing about WHAT was recorded -- only the deterministic
 * grid-alignment MATH, via the offline/test-only native path (mandate 8):
 * [AudioEngineBridge.nativeTestStartRecording] captures the offline engine's
 * CURRENT simulated transport frame (deliberately advanced to a non-grid-
 * aligned position first) as recordingStartFrame, [AudioEngineBridge.nativeTestAdvanceOffline]
 * feeds silence through the exact same mandate-4 captureRecordingFrames
 * derivation the live callback uses (see AudioEngine.cpp), and
 * [AudioEngineBridge.nativeTestStopRecording] writes the captured (silent)
 * frames out via the same production WavWriter path as a real recording.
 *
 * The resulting startGridUnit/lengthGridUnits are computed by the REAL
 * production conversion code -- [GridConstants.startGridUnitForFrame] /
 * [GridConstants.lengthGridUnitsForFrameCount], the exact functions
 * ArrangementViewModel.stopRecording() calls to place a just-recorded take's
 * LoopBlock -- never reimplemented here. The test verifies they round-trip
 * back through [GridConstants.framesPerGridUnit] to within one grid unit of
 * the actual simulated frame range, proving alignment holds even for an
 * arbitrary, non-grid-aligned start time.
 *
 * Two scenarios are covered (both required by the exit criterion's
 * "relative to concurrently playing tracks" wording):
 *  - [recordingAtNonGridAlignedFrame_roundTripsWithinOneGridUnit_withNoOtherActivity]:
 *    baseline, nothing else scheduled.
 *  - [recordingAtNonGridAlignedFrame_roundTripsWithinOneGridUnit_withConcurrentReferenceBlock]:
 *    a reference loop block is scheduled and continuously active on another
 *    track throughout the simulated recording, using the exact same
 *    cross-check technique as [MixEngineDriftTest] to prove the reference
 *    block's own loopLocalFrame derivation isn't perturbed by a concurrent
 *    recording either. Asserts the SAME startGridUnit/lengthGridUnits result
 *    as the no-activity baseline, proving the recording's alignment doesn't
 *    depend on whether anything else happens to be scheduled at the same
 *    time.
 */
@RunWith(AndroidJUnit4::class)
class RecordingGridAlignmentTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class RecordingResult(
        val recordingStartFrame: Long,
        val framesWritten: Long,
        val startGridUnit: Int,
        val lengthGridUnits: Int
    )

    @Test
    fun recordingAtNonGridAlignedFrame_roundTripsWithinOneGridUnit_withNoOtherActivity() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestCommitProject(handle)

            val result = runRecordingScenario(handle)
            assertAlignedWithinOneGridUnit(result)
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    @Test
    fun recordingAtNonGridAlignedFrame_roundTripsWithinOneGridUnit_withConcurrentReferenceBlock() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)

            // A reference block, scheduled from grid unit 0 with a window
            // (BLOCK_LENGTH_GRID_UNITS) that comfortably outlasts the whole
            // simulated run, so it's continuously "playing" on track 1 the
            // entire time the recording is captured on the (separate,
            // engine-level) input path -- mirrors MixEngineDriftTest's setup.
            val scheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                handle, 1, KICK_ASSET_PATH,
                0, BLOCK_LENGTH_GRID_UNITS,
                1.0f, 0L, -1L, 0.0f
            )
            assertTrue("expected the reference loop block to be scheduled", scheduled)
            AudioEngineBridge.nativeTestCommitProject(handle)

            val blockStartFrame = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, 1, 0)
            val loopContentLengthFrames = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 1, 0)
            assertTrue("expected a resolved blockStartFrame", blockStartFrame >= 0)
            assertTrue("expected a resolved positive loopContentLengthFrames", loopContentLengthFrames > 0)

            var framesAdvancedSoFar = 0L
            val result = runRecordingScenario(handle) { totalAdvanced ->
                framesAdvancedSoFar = totalAdvanced
                // Cross-check the reference block's own loopLocalFrame
                // derivation (mandate 6) at each burst -- proving a
                // concurrent recording doesn't corrupt/perturb the ordinary
                // mix/schedule path, the same technique MixEngineDriftTest
                // uses for playback-only scenarios.
                val framesSinceBlockStart = totalAdvanced - blockStartFrame
                val expectedLoopLocalFrame = Math.floorMod(framesSinceBlockStart, loopContentLengthFrames)
                val actualLoopLocalFrame = AudioEngineBridge.nativeTestGetLoopLocalFrame(handle, 1, 0)
                assertEquals(
                    "reference block's loopLocalFrame drifted while a recording was concurrently active " +
                            "(totalAdvanced=$totalAdvanced)",
                    expectedLoopLocalFrame, actualLoopLocalFrame
                )
            }
            assertTrue("expected at least one burst to have run", framesAdvancedSoFar > 0)

            assertAlignedWithinOneGridUnit(result)

            // The load-bearing claim: the SAME non-grid-aligned start/length
            // math the no-activity baseline test produced, proving the
            // recording's own alignment doesn't depend on whether anything
            // else happens to be scheduled at the same time.
            assertEquals(
                "recordingStartFrame must be identical to the no-activity baseline " +
                        "(both engines are advanced through the exact same frame counts)",
                PRE_ADVANCE_FRAMES, result.recordingStartFrame
            )
            assertEquals(EXPECTED_START_GRID_UNIT, result.startGridUnit)
            assertEquals(EXPECTED_LENGTH_GRID_UNITS, result.lengthGridUnits)
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    /**
     * Advances [handle]'s offline transport by [PRE_ADVANCE_FRAMES] --
     * deliberately NOT a multiple of framesPerGridUnit (8000.0 exactly at
     * 90bpm/48kHz, see companion object), so recording begins at a genuinely
     * non-grid-aligned position -- then starts a test recording, advances a
     * further [RECORDING_FRAMES] (also not grid-aligned) in realistic
     * Oboe-callback-sized bursts, and stops it, writing a real WAV file to a
     * throwaway path in the test app's cache dir.
     *
     * [onBurst], if given, is invoked after every burst with the total
     * frames advanced since the start of this scenario (i.e. including
     * [PRE_ADVANCE_FRAMES]) -- used by the concurrent-reference-block test to
     * cross-check the reference block's own derivation at each step.
     */
    private fun runRecordingScenario(
        handle: Long,
        onBurst: ((totalFramesAdvanced: Long) -> Unit)? = null
    ): RecordingResult {
        var totalFramesAdvanced = 0L
        fun advance(target: Long) {
            while (totalFramesAdvanced < target) {
                val burst = minOf(BURST_FRAMES.toLong(), target - totalFramesAdvanced).toInt()
                AudioEngineBridge.nativeTestAdvanceOffline(handle, burst)
                totalFramesAdvanced += burst
                onBurst?.invoke(totalFramesAdvanced)
            }
        }

        advance(PRE_ADVANCE_FRAMES)
        assertEquals(PRE_ADVANCE_FRAMES, totalFramesAdvanced)
        assertTrue(
            "PRE_ADVANCE_FRAMES must itself be non-grid-aligned for this test to be meaningful",
            PRE_ADVANCE_FRAMES % FRAMES_PER_GRID_UNIT.toLong() != 0L
        )

        AudioEngineBridge.nativeTestStartRecording(handle)
        assertTrue("expected a recording to be in progress", AudioEngineBridge.nativeTestIsRecording(handle))

        advance(PRE_ADVANCE_FRAMES + RECORDING_FRAMES)

        // Read the start frame BEFORE stopping, mirroring the production
        // ArrangementViewModel.stopRecording() ordering (see its doc
        // comment) even though the offline path doesn't strictly require it.
        val recordingStartFrame = AudioEngineBridge.nativeTestGetRecordingStartFrame(handle)
        assertEquals(
            "recordingStartFrame must be the exact non-grid-aligned transport frame recording began at",
            PRE_ADVANCE_FRAMES, recordingStartFrame
        )

        val outputFile = File(context.cacheDir, "grid_alignment_test_${System.nanoTime()}.wav")
        val framesWritten = AudioEngineBridge.nativeTestStopRecording(handle, outputFile.absolutePath)
        assertFalse("expected the recording to have stopped", AudioEngineBridge.nativeTestIsRecording(handle))
        assertTrue("expected a real WAV file to be written", outputFile.exists())
        assertEquals(
            "expected exactly RECORDING_FRAMES to have been captured (well under the ~3-minute cap)",
            RECORDING_FRAMES, framesWritten
        )
        outputFile.delete()

        val startGridUnit = GridConstants.startGridUnitForFrame(recordingStartFrame, BPM, SAMPLE_RATE_HZ)
        val lengthGridUnits = GridConstants.lengthGridUnitsForFrameCount(framesWritten, BPM, SAMPLE_RATE_HZ)

        return RecordingResult(recordingStartFrame, framesWritten, startGridUnit, lengthGridUnits)
    }

    /**
     * The load-bearing alignment assertion: round-tripping [result]'s
     * startGridUnit/lengthGridUnits back through
     * [GridConstants.framesPerGridUnit] must land within one grid unit of
     * the actual simulated [recordingStartFrame, recordingStartFrame +
     * framesWritten) range -- proving the production placement math stays
     * aligned even though the underlying recording itself began at an
     * arbitrary, non-grid-aligned frame.
     */
    private fun assertAlignedWithinOneGridUnit(result: RecordingResult) {
        val perUnit = GridConstants.framesPerGridUnit(BPM, SAMPLE_RATE_HZ)
        val actualEndFrame = result.recordingStartFrame + result.framesWritten

        val roundTrippedStartFrame = result.startGridUnit * perUnit
        assertTrue(
            "startGridUnit round-trip (frame=$roundTrippedStartFrame) must be within one grid unit " +
                    "($perUnit frames) of the actual recordingStartFrame (${result.recordingStartFrame})",
            Math.abs(roundTrippedStartFrame - result.recordingStartFrame) < perUnit
        )

        val roundTrippedEndFrame = (result.startGridUnit + result.lengthGridUnits) * perUnit
        assertTrue(
            "the placed block (grid [${result.startGridUnit}, ${result.startGridUnit + result.lengthGridUnits})) " +
                    "must fully cover the actual recorded frame range -- got roundTrippedEndFrame=" +
                    "$roundTrippedEndFrame, actualEndFrame=$actualEndFrame",
            roundTrippedEndFrame >= actualEndFrame
        )
        assertTrue(
            "the placed block must not overshoot the actual recorded frame range by more than one grid unit " +
                    "($perUnit frames) -- got roundTrippedEndFrame=$roundTrippedEndFrame, actualEndFrame=$actualEndFrame",
            roundTrippedEndFrame - actualEndFrame < perUnit
        )
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48000
        private const val BPM = 90

        // A realistic Oboe audio-callback burst size (~20ms @ 48kHz), matching
        // MixEngineDriftTest's own choice.
        private const val BURST_FRAMES = 960

        // framesPerGridUnit at 90bpm/48kHz = (60/90/4)*48000 = 8000.0 exactly.
        private const val FRAMES_PER_GRID_UNIT = 8000.0

        // Deliberately NOT a multiple of FRAMES_PER_GRID_UNIT (8000*3=24000,
        // +137 lands mid-grid-unit) so recording begins at a genuinely
        // non-grid-aligned transport frame, per the exit criterion's "an
        // arbitrary, non-grid-aligned start time" requirement.
        private const val PRE_ADVANCE_FRAMES = 24_137L

        // Also deliberately non-grid-aligned (5 whole grid units + 400
        // frames), so the length-rounding-up behavior is exercised too, not
        // just the start-frame flooring.
        private const val RECORDING_FRAMES = 40_400L

        // floor(24137 / 8000) = 3
        private const val EXPECTED_START_GRID_UNIT = 3

        // ceil(40400 / 8000) = 6
        private const val EXPECTED_LENGTH_GRID_UNITS = 6

        private const val BLOCK_LENGTH_GRID_UNITS = 2000

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val KICK_ASSET_PATH = "loops/kick_basic_01.wav"
    }
}
