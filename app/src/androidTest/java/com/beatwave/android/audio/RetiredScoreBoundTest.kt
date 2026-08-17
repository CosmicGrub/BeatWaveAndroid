package com.beatwave.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for post-v1 audit/bugfix B3 (2026-08-17
 * engine-upgrades backlog): AudioEngine's mRetiredScores used to retain
 * every committed PlaybackScore for the engine's whole lifetime -- correct,
 * but genuinely unbounded across a long session with many arrangement
 * edits (independently flagged twice: once by the original engine-upgrades
 * scoping pass, and again as a nit during D1's own adversarial review).
 * mRetiredScores is now bounded to the newest kRetainedScoreCount entries,
 * with reclaiming an old one made provably safe via a quiescence wait
 * mirroring the codebase's already-established mInputReadInFlight pattern
 * (see AudioEngine.h's mScore/mScoreReadInFlight doc comments for the full
 * safety argument).
 *
 * Drives the offline/test-only native engine (mandate 10) through many more
 * commits than the retention bound to verify, on real device hardware:
 *  1. mRetiredScores never grows past the bound, however many commits land.
 *  2. The reclaim logic never corrupts or interferes with whichever score
 *     is CURRENTLY published -- every commit's block resolves correctly
 *     immediately after that commit, even deep into a long run of reclaims.
 */
@RunWith(AndroidJUnit4::class)
class RetiredScoreBoundTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun retiredScores_stayBoundedAcrossManyCommits_andEachCommittedScoreResolvesCorrectly() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)

            // Comfortably more commits than the retention bound, so this
            // genuinely exercises many reclaim cycles, not just the initial
            // ramp-up to the bound.
            val totalCommits = RETAINED_SCORE_COUNT * 4
            val framesPerGridUnit = GridConstants.framesPerGridUnit(BPM, SAMPLE_RATE_HZ)
            for (i in 0 until totalCommits) {
                // ScoreBuilder::build() (called inside commitProject())
                // moves its ResolvedTrack list out from under the builder --
                // re-add the track every iteration so this commit's single
                // block has a track to resolve against (same gotcha as
                // SampleBankEvictionTest; see beatwave-android-project
                // memory finding #22).
                AudioEngineBridge.nativeTestAddTrack(handle, 1)
                // A DIFFERENT startGridUnit every iteration (rather than a
                // fixed 0) so each commit's block resolves to a distinct,
                // predictable blockStartFrame -- an adversarial-review pass
                // caught that identical block params on every iteration
                // made the original version of this test unable to
                // distinguish "correctly read the just-published score"
                // from "incorrectly read a stale/reclaimed one," since both
                // would look identical.
                val startGridUnit = i * BLOCK_LENGTH_GRID_UNITS
                val scheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                    handle, 1, ASSET_PATH, startGridUnit, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f
                )
                assertTrue("expected block #$i to be scheduled", scheduled)
                AudioEngineBridge.nativeTestCommitProject(handle)

                // Pins the EXACT expected trajectory at every single commit
                // (not just "<= the bound") -- ramp-up (i+1) until the bound
                // is first reached, then pinned exactly at the bound for
                // every commit after. Catches under-retention (a stale
                // score reclaimed too early) AND over-aggressive reclaim
                // (e.g. a hypothetical future bug that reclaims two at a
                // time) on the very first commit either would occur, not
                // just eventually or by chance.
                val expectedRetiredCount = minOf(i + 1, RETAINED_SCORE_COUNT)
                val retiredCount = AudioEngineBridge.nativeTestGetRetiredScoreCount(handle)
                assertEquals(
                    "expected mRetiredScores' size to exactly match the retention trajectory at commit #$i",
                    expectedRetiredCount, retiredCount
                )

                // The block just committed (always index 0 of a freshly
                // re-added track) must resolve to the EXACT expected
                // position for this iteration -- not just "some non-
                // negative value" -- proving the reclaim logic never
                // touches or corrupts the CURRENTLY published score, even
                // mid-way through a long run of reclaims of OLDER ones.
                val expectedBlockStartFrame = Math.round(startGridUnit.toDouble() * framesPerGridUnit)
                val blockStartFrame = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, 1, 0)
                val loopContentLengthFrames = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 1, 0)
                assertEquals(
                    "expected commit #$i's block to resolve to its own distinct blockStartFrame",
                    expectedBlockStartFrame, blockStartFrame
                )
                assertTrue(
                    "expected commit #$i's block to resolve a positive loop content length",
                    loopContentLengthFrames > 0
                )
            }
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48000
        private const val BPM = 90
        private const val BLOCK_LENGTH_GRID_UNITS = 2000

        // Must match AudioEngine.h's kRetainedScoreCount.
        private const val RETAINED_SCORE_COUNT = 8

        private const val ASSET_PATH = "loops/kick_basic_01.wav"
    }
}
