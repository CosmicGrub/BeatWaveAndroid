package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 cutover: the grid-screen replacement for the old
 * ArrangementScreenPlaybackTest's transport proof -- the real Play/Pause/Stop
 * buttons (the same PlaybackControlBar both screens share) driving the real
 * native engine, read back through AudioEngineBridge independently of the UI.
 *
 * Once playback is running only the bare transport buttons are touched
 * through Compose (never a full re-query of the grid), see the recording
 * test's note on why.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenTransportTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun freshProject() {
        ProjectRepository.forContext(context).delete("current")
    }

    @After
    fun tearDown() {
        (context.applicationContext as BeatWaveApplication).playbackEngine.stop()
        Thread.sleep(TEARDOWN_SETTLE_MS)
    }

    @Test
    fun playPauseStop_throughTheRealButtons_driveTheRealEngine() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntilSafely(INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_empty_track_prompt").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.placeKickNoteOnTrack1()

        assertEquals("expected transport to start at frame 0", 0L, AudioEngineBridge.getCurrentFrame())

        composeTestRule.performClickSafely("play_pause_button")
        Thread.sleep(SETTLE_MS)
        val playing = AudioEngineBridge.getCurrentFrame()
        assertTrue("expected the transport to advance after Play, got $playing", playing > 0L)

        composeTestRule.performClickSafely("play_pause_button")
        Thread.sleep(SETTLE_MS)
        val paused = AudioEngineBridge.getCurrentFrame()
        Thread.sleep(PAUSED_INTERVAL_MS)
        val drift = Math.abs(AudioEngineBridge.getCurrentFrame() - paused)
        // Same evidence-based tolerance as FullIntegrationWalkthroughTest
        // (project memory finding #30): real continued playback would drift
        // by ~19,200 frames over this window.
        assertTrue("expected the transport to stay frozen after Pause, drifted $drift frames", drift <= FREEZE_TOLERANCE_FRAMES)

        composeTestRule.performClickSafely("stop_button")
        Thread.sleep(SETTLE_MS)
        assertEquals("expected Stop to return the transport to frame 0", 0L, AudioEngineBridge.getCurrentFrame())
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val SETTLE_MS = 500L
        private const val PAUSED_INTERVAL_MS = 400L
        private const val FREEZE_TOLERANCE_FRAMES = 500L
        private const val TEARDOWN_SETTLE_MS = 300L
    }
}
