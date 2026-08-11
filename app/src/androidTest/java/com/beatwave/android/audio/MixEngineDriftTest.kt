package com.beatwave.android.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the Phase 2 exit criterion: "loop boundaries stay
 * phase-locked across tracks over several minutes of playback (no drift)".
 *
 * This drives the offline/test-only native engine (mandate 10 -- see
 * AudioEngineBridge's nativeTest* methods) through several simulated
 * minutes of audio-callback-sized advances via nativeTestAdvanceOffline,
 * which internally calls the exact same renderScore/nonNegativeMod code
 * path the live Oboe callback uses (see AudioEngine::renderOffline,
 * MixEngine.cpp). At regular checkpoints throughout, it cross-checks the
 * engine's own internally-derived loopLocalFrame (mandate 6's absolute-
 * position derivation, exposed via nativeTestGetLoopLocalFrame) against an
 * independently-computed expected value using plain Kotlin Long
 * arithmetic. The engine's blockStartFrame and loopContentLengthFrames are
 * read back from the engine itself (nativeTestGetBlockStartFrame /
 * nativeTestGetLoopContentLengthFrames) rather than recomputed from scratch
 * here -- this test is specifically checking that repeatedly deriving
 * position from the single absolute transport counter never drifts from a
 * ground truth this test tracks itself (totalFramesAdvanced), not whether
 * two independent roundings happen to agree.
 *
 * Every loop block in every test below is scheduled with a block window
 * (lengthGridUnits = BLOCK_LENGTH_GRID_UNITS) deliberately longer than the
 * whole simulated duration, so the block is continuously active for the
 * entire test and nativeTestGetLoopLocalFrame never needs to report "not
 * active" (-1) -- keeping the per-checkpoint assertion exactly the formula
 * the Phase 2 plan specifies:
 *   framesSinceStart = totalFramesAdvanced - blockStartFrame
 *   expectedLoopLocalFrame = Math.floorMod(framesSinceStart, loopContentLengthFrames)
 */
@RunWith(AndroidJUnit4::class)
class MixEngineDriftTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** One block's resolved (engine-reported) identity, cross-checked at every checkpoint. */
    private data class TrackedBlock(
        val label: String,
        val trackSlot: Int,
        val blockIndex: Int,
        val blockStartFrame: Long,
        val loopContentLengthFrames: Long
    )

    @Test
    fun singleTrack_loopLocalFrameStaysExactOverFiveMinutes() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)

            // kick_basic_01.wav: manifest durationMs=2667, bpm=90. Full
            // sample, no trim, no pitch shift. lengthGridUnits =
            // BLOCK_LENGTH_GRID_UNITS resolves to 16,000,000 timeline
            // frames, comfortably outlasting the 14,400,000-frame
            // simulation, so the ~128k-frame loop content repeats 100+
            // times over the run while the block stays continuously active.
            val scheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                handle, 1, KICK_ASSET_PATH,
                /* startGridUnit = */ 0,
                /* lengthGridUnits = */ BLOCK_LENGTH_GRID_UNITS,
                /* volume = */ 1.0f,
                /* trimStartMs = */ 0L,
                /* trimEndMs = */ -1L,
                /* pitchSemitones = */ 0.0f
            )
            assertTrue("expected the kick loop block to be scheduled", scheduled)
            AudioEngineBridge.nativeTestCommitProject(handle)

            val kick = trackedBlock("kick(track1, no trim/pitch)", handle, trackSlot = 1, blockIndex = 0)

            val elapsedMs = simulateAndCrossCheck(handle, listOf(kick))
            assertTrue(
                "5-minute offline simulation took ${elapsedMs}ms, over the ${WALL_CLOCK_BUDGET_MS}ms sanity budget",
                elapsedMs < WALL_CLOCK_BUDGET_MS
            )
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    @Test
    fun twoConcurrentTracks_withTrimAndPitch_stayExactOverFiveMinutes() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)
            AudioEngineBridge.nativeTestAddTrack(handle, 2)

            // Track 1: kick_basic_01.wav, full sample, no trim, no pitch shift.
            val kickScheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                handle, 1, KICK_ASSET_PATH,
                0, BLOCK_LENGTH_GRID_UNITS,
                1.0f, 0L, -1L, 0.0f
            )
            assertTrue("expected the kick loop block to be scheduled", kickScheduled)

            // Track 2: bass_riff_01.wav (manifest durationMs=5333, bpm=90),
            // trimmed to [500ms, 3000ms) -- a non-trivial 2500ms sub-region
            // -- and pitched up 5 semitones, concurrently with track 1, to
            // prove drift-freedom holds under both trim and pitch at once,
            // not just the trivial untrimmed/unpitched single-loop case.
            val bassScheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                handle, 2, BASS_ASSET_PATH,
                0, BLOCK_LENGTH_GRID_UNITS,
                0.8f, 500L, 3000L, 5.0f
            )
            assertTrue("expected the bass loop block to be scheduled", bassScheduled)

            AudioEngineBridge.nativeTestCommitProject(handle)

            val kick = trackedBlock("kick(track1, no trim/pitch)", handle, trackSlot = 1, blockIndex = 0)
            val bass = trackedBlock("bass(track2, trim 500-3000ms, +5 semitones)", handle, trackSlot = 2, blockIndex = 0)

            // Sanity: the trim+pitch block really did resolve to a different
            // loop content length than the untrimmed/unpitched one -- if
            // this ever failed it would mean ScoreBuilder isn't applying
            // trim/pitch at all, silently turning this into a duplicate of
            // the first test.
            assertTrue(
                "expected the trimmed+pitched bass block's content length (${bass.loopContentLengthFrames}) " +
                        "to differ from the untrimmed kick's (${kick.loopContentLengthFrames})",
                bass.loopContentLengthFrames != kick.loopContentLengthFrames
            )

            val elapsedMs = simulateAndCrossCheck(handle, listOf(kick, bass))
            assertTrue(
                "5-minute offline simulation took ${elapsedMs}ms, over the ${WALL_CLOCK_BUDGET_MS}ms sanity budget",
                elapsedMs < WALL_CLOCK_BUDGET_MS
            )
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    /** Reads back the engine's own resolved blockStartFrame/loopContentLengthFrames for one block. */
    private fun trackedBlock(label: String, handle: Long, trackSlot: Int, blockIndex: Int): TrackedBlock {
        val blockStartFrame = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, trackSlot, blockIndex)
        val loopContentLengthFrames = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, trackSlot, blockIndex)
        assertTrue("[$label] expected a resolved blockStartFrame, got $blockStartFrame", blockStartFrame >= 0)
        assertTrue(
            "[$label] expected a resolved positive loopContentLengthFrames, got $loopContentLengthFrames",
            loopContentLengthFrames > 0
        )
        return TrackedBlock(label, trackSlot, blockIndex, blockStartFrame, loopContentLengthFrames)
    }

    /**
     * Advances the offline engine at [handle] through TOTAL_SIMULATED_FRAMES
     * frames in BURST_FRAMES-sized bursts (matching a realistic Oboe
     * callback size), and every CHECKPOINT_EVERY_N_BURSTS bursts, for every
     * block in [blocks], asserts nativeTestGetLoopLocalFrame's result
     * exactly equals an independently-tracked expected value. Returns
     * wall-clock elapsed milliseconds for the whole simulation (mandate 10 /
     * plan step (g)'s real-time-safety smoke check: this would blow well
     * past the sanity budget if any accidental O(n^2) behavior or
     * per-callback allocation/locking slowdown crept into the mix path).
     */
    private fun simulateAndCrossCheck(handle: Long, blocks: List<TrackedBlock>): Long {
        var totalFramesAdvanced = 0L
        var burstsSinceCheckpoint = 0
        var checkpointCount = 0
        val startNanos = System.nanoTime()

        while (totalFramesAdvanced < TOTAL_SIMULATED_FRAMES) {
            AudioEngineBridge.nativeTestAdvanceOffline(handle, BURST_FRAMES)
            totalFramesAdvanced += BURST_FRAMES
            burstsSinceCheckpoint++

            if (burstsSinceCheckpoint >= CHECKPOINT_EVERY_N_BURSTS) {
                burstsSinceCheckpoint = 0
                checkpointCount++
                for (block in blocks) {
                    val framesSinceStart = totalFramesAdvanced - block.blockStartFrame
                    val expected = Math.floorMod(framesSinceStart, block.loopContentLengthFrames)
                    val actual = AudioEngineBridge.nativeTestGetLoopLocalFrame(handle, block.trackSlot, block.blockIndex)
                    assertEquals(
                        "[${block.label}] loopLocalFrame drifted at totalFramesAdvanced=$totalFramesAdvanced " +
                                "(checkpoint #$checkpointCount, blockStartFrame=${block.blockStartFrame}, " +
                                "loopContentLengthFrames=${block.loopContentLengthFrames})",
                        expected, actual
                    )
                }
            }
        }

        assertEquals(TOTAL_SIMULATED_FRAMES, totalFramesAdvanced)
        assertTrue("expected at least one checkpoint to have run", checkpointCount > 0)

        return (System.nanoTime() - startNanos) / 1_000_000L
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48000
        private const val BPM = 90

        // A realistic Oboe audio-callback burst size (~20ms @ 48kHz), not an
        // arbitrary chunk -- see Phase 0's logged framesPerBurst.
        private const val BURST_FRAMES = 960

        // 5 minutes @ 48kHz. 14,400,000 / 960 = 15,000 bursts exactly.
        private const val TOTAL_SIMULATED_FRAMES = 5L * 60L * SAMPLE_RATE_HZ

        // Checkpoint every 10 bursts = 9,600 frames (~200ms) -- 1,500
        // checkpoints across the run: a continuous check, not just an
        // end-of-run spot check.
        private const val CHECKPOINT_EVERY_N_BURSTS = 10

        private const val WALL_CLOCK_BUDGET_MS = 30_000L

        // framesPerGridUnit at 90bpm/48kHz = (60/90/4)*48000 = 8000.0
        // exactly, so 2000 grid units = 16,000,000 timeline frames --
        // longer than TOTAL_SIMULATED_FRAMES, keeping every block in this
        // file continuously active (never "not started yet" / "already
        // ended") for the whole simulated run.
        private const val BLOCK_LENGTH_GRID_UNITS = 2000

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val KICK_ASSET_PATH = "loops/kick_basic_01.wav" // durationMs=2667, bpm=90
        private const val BASS_ASSET_PATH = "loops/bass_riff_01.wav" // durationMs=5333, bpm=90
    }
}
