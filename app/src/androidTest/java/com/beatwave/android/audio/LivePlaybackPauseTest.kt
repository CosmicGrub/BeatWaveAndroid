package com.beatwave.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beatwave.android.AudioEngineBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for the live-engine pause behavior called out
 * in Phase 2 review: [AudioEngine.onAudioReady] (see AudioEngine.cpp)
 * intentionally does NOT advance the mandate-6 master transport counter
 * while paused (mPlaying == false) -- a deliberate divergence from mandate
 * 6's literal "incremented every single audio callback" wording, made so
 * that pausing freezes the arrangement position instead of letting it drift
 * forward silently while nothing is audible (see the design spec's
 * "play/pause the full arrangement" and the comment at the top of
 * AudioEngine::onAudioReady's paused branch).
 *
 * This is specifically NOT covered by [MixEngineDriftTest]: that suite only
 * drives the offline/test engine via nativeTestAdvanceOffline, which has no
 * play/pause concept and always advances unconditionally (see
 * AudioEngine::renderOffline's doc comment). This test instead drives the
 * real live engine (nativeInit/startEngine/play/pause/getCurrentFrame)
 * against the real Oboe output stream on the attached device, so it
 * exercises the actual onAudioReady gating this review flagged as untested.
 *
 * No project/score needs to be built for this test -- getCurrentFrame()
 * reflects the master transport counter regardless of whether a
 * PlaybackScore has been committed, and onAudioReady's play/pause gate is
 * evaluated before any score is even read.
 */
@RunWith(AndroidJUnit4::class)
class LivePlaybackPauseTest {

    @Test
    fun pause_freezesTransport_andPlayResumesIt() {
        val started = AudioEngineBridge.startEngine()
        assertTrue("expected the live Oboe stream to open on this device", started)
        try {
            AudioEngineBridge.play()
            Thread.sleep(PLAY_SETTLE_MS)
            val framePlaying = AudioEngineBridge.getCurrentFrame()
            assertTrue(
                "expected the transport to have advanced while playing, got $framePlaying",
                framePlaying > 0
            )

            AudioEngineBridge.pause()
            // Let any single audio callback that was already in flight when
            // pause() flipped the flag finish, so the two reads below both
            // land strictly inside the paused interval.
            Thread.sleep(PAUSE_SETTLE_MS)
            val frameAtPauseSettled = AudioEngineBridge.getCurrentFrame()

            Thread.sleep(PAUSED_INTERVAL_MS)
            val frameAfterPausedInterval = AudioEngineBridge.getCurrentFrame()
            assertEquals(
                "transport must not advance while paused (mandate-6 divergence under test)",
                frameAtPauseSettled, frameAfterPausedInterval
            )

            AudioEngineBridge.play()
            Thread.sleep(PLAY_SETTLE_MS)
            val frameAfterResume = AudioEngineBridge.getCurrentFrame()
            assertTrue(
                "expected the transport to resume advancing after play(), " +
                        "was $frameAfterPausedInterval, now $frameAfterResume",
                frameAfterResume > frameAfterPausedInterval
            )
        } finally {
            AudioEngineBridge.stop()
            AudioEngineBridge.stopEngine()
        }
    }

    companion object {
        private const val PLAY_SETTLE_MS = 300L
        private const val PAUSE_SETTLE_MS = 100L
        private const val PAUSED_INTERVAL_MS = 300L
    }
}
