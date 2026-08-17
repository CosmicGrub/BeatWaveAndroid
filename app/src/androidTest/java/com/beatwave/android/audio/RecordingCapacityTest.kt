package com.beatwave.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression coverage for post-v1 audit/bugfix B1 (2026-08-17
 * engine-upgrades backlog): the native recording buffer used to be sized for
 * a hardcoded 180-second cap ([AudioEngine.h]'s old kMaxRecordingSeconds),
 * silently undershooting the app's own real max song length
 * ([GridConstants.MAX_SONG_LENGTH_SECONDS], 240 seconds) by a full minute.
 * The fix makes the caller supply the cap explicitly at
 * [AudioEngineBridge.startRecording]/[AudioEngineBridge.nativeTestStartRecording]
 * time instead of duplicating it natively.
 *
 * Drives the offline/test-only native engine (mandate 8) through the exact
 * same [AudioEngineBridge.nativeTestAdvanceOffline]/captureRecordingFrames
 * derivation a live recording uses, and proves BOTH halves of the fix on
 * real device hardware:
 *  1. Recording no longer auto-stops at the old, wrong 180-second mark.
 *  2. Recording DOES correctly auto-stop at the real 240-second cap, with
 *     the recorded frame count clamped exactly there (never past it).
 */
@RunWith(AndroidJUnit4::class)
class RecordingCapacityTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recordingCap_matchesRealMaxSongLength_notTheOldHardcoded180Seconds() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            // Recording begins at the engine's initial transport frame (0),
            // so recordingStartFrame = 0 and recordFrameIndex tracks total
            // frames advanced exactly -- no grid-alignment math needed here,
            // that's RecordingGridAlignmentTest's concern.
            AudioEngineBridge.nativeTestStartRecording(handle, GridConstants.MAX_SONG_LENGTH_SECONDS)
            assertTrue("expected a recording to be in progress", AudioEngineBridge.nativeTestIsRecording(handle))
            assertFalse(
                "expected the cap not to be reached immediately after starting",
                AudioEngineBridge.nativeTestIsRecordingCapReached(handle)
            )

            val oldWrongCapacityFrames = SAMPLE_RATE_HZ.toLong() * OLD_HARDCODED_CAP_SECONDS
            val realCapacityFrames = SAMPLE_RATE_HZ.toLong() * GridConstants.MAX_SONG_LENGTH_SECONDS

            // Past where the OLD (wrong) 180-second cap would have fired --
            // this is the actual regression check for the bug: recording
            // must still be running here.
            advanceOffline(handle, oldWrongCapacityFrames + CHECKPOINT_MARGIN_FRAMES)
            assertFalse(
                "expected recording to still be running past the OLD hardcoded 180s cap " +
                    "(this would fail before the B1 fix)",
                AudioEngineBridge.nativeTestIsRecordingCapReached(handle)
            )

            // Just short of the REAL 240s cap -- still running.
            advanceOffline(handle, realCapacityFrames - CHECKPOINT_MARGIN_FRAMES - (oldWrongCapacityFrames + CHECKPOINT_MARGIN_FRAMES))
            assertFalse(
                "expected recording to still be running just short of the real 240s cap",
                AudioEngineBridge.nativeTestIsRecordingCapReached(handle)
            )

            // Past the REAL 240s cap -- must now have stopped capturing.
            advanceOffline(handle, 2 * CHECKPOINT_MARGIN_FRAMES)
            assertTrue(
                "expected the cap to be reached shortly after the real 240s mark",
                AudioEngineBridge.nativeTestIsRecordingCapReached(handle)
            )
            assertEquals(
                "expected the recorded frame count to be clamped exactly at the real capacity, never past it",
                realCapacityFrames, AudioEngineBridge.nativeTestGetRecordedFrameCount(handle)
            )
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    /** Advances the offline engine at [handle] by exactly [totalFrames],
     *  in realistic Oboe-callback-sized bursts. */
    private fun advanceOffline(handle: Long, totalFrames: Long) {
        var remaining = totalFrames
        while (remaining > 0) {
            val burst = minOf(BURST_FRAMES.toLong(), remaining).toInt()
            AudioEngineBridge.nativeTestAdvanceOffline(handle, burst)
            remaining -= burst
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48000
        private const val BURST_FRAMES = 960

        // The pre-B1 native constant this test proves no longer applies.
        private const val OLD_HARDCODED_CAP_SECONDS = 180L

        // 2 seconds' worth of frames -- comfortably larger than one burst,
        // small enough to keep checkpoints tight against the real boundary.
        private const val CHECKPOINT_MARGIN_FRAMES = 2L * SAMPLE_RATE_HZ
    }
}
