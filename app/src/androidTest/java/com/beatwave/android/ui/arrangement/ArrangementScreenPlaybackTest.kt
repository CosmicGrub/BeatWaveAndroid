package com.beatwave.android.ui.arrangement

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.MainActivity
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test for the Phase 3 exit criterion: "can build a
 * multi-track arrangement from the bundled pack entirely through the UI and
 * play it back correctly".
 *
 * Every interaction below goes through the real Compose UI ([ArrangementScreen]
 * as hosted by [MainActivity]) -- node lookups by testTag, real performClick()
 * taps -- never by calling [ArrangementViewModel] or [AudioEngineBridge]
 * schedule-building methods directly. The one exception, by design, is the
 * playback-position assertions in steps (d)/(e): those call
 * [AudioEngineBridge.getCurrentFrame] directly from the test thread (a cheap,
 * thread-safe transport read per AudioEngineBridge's own doc comment) as the
 * *proof* that the UI's Play/Pause/Stop taps really drove the single live
 * native engine instance -- not a disconnected UI-only counter. Because
 * AudioEngineBridge is a singleton wrapping one native engine, this is the
 * same engine instance ArrangementViewModel commands; reading it independently
 * is what makes this an end-to-end check rather than a UI-only smoke test.
 *
 * Uses [createEmptyComposeRule] (not createAndroidComposeRule<MainActivity>())
 * specifically so the "current" project file can be deleted -- guaranteeing a
 * fresh/empty starting project, per design item 7 -- *before* MainActivity
 * (and therefore ArrangementViewModel.init's project load) is launched. A
 * JUnit @Rule's "before" logic runs ahead of @Before methods, so an
 * auto-launching activity rule would load the project before this cleanup
 * could run; launching manually inside the @Test method after @Before's
 * cleanup avoids that ordering trap.
 */
@RunWith(AndroidJUnit4::class)
class ArrangementScreenPlaybackTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetToFreshProject() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        ProjectRepository.forContext(targetContext).delete(PROJECT_ID)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        // onCleared()'s AudioEngineBridge.stopEngine() call is fire-and-forget
        // on its own short-lived scope (see ArrangementViewModel.onCleared);
        // give it a moment to actually close the native stream before the
        // next test (or test class) tries to open a new one.
        Thread.sleep(TEARDOWN_SETTLE_MS)
    }

    @Test
    fun buildTwoTrackArrangementThroughUi_thenPlayPauseStop_drivesRealEngine() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // --- Wait for ArrangementViewModel's background init (project load +
        // sample manifest load + native engine start) to finish and the
        // timeline to render. ---
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (a)+(b): select Track 1, open the loop library, add "Basic
        // Kick" (DRUMS) to it. ---
        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        val track1KickBlockTag = "loop_block_1_$KICK_SAMPLE_ID"
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(track1KickBlockTag).fetchSemanticsNodes().isNotEmpty()
        }

        // --- (b) continued: select Track 2 (a different track), open the
        // library again, add "Bass Riff One" (BASS) to it. ---
        composeTestRule.onNodeWithTag("track_header_2").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$BASS_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$BASS_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        val track2BassBlockTag = "loop_block_2_$BASS_SAMPLE_ID"
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(track2BassBlockTag).fetchSemanticsNodes().isNotEmpty()
        }

        // --- (c): with the library closed, both blocks must be visibly
        // represented in the real semantics tree, on their two distinct
        // tracks (the trackSlot is baked into each block's testTag). ---
        composeTestRule.onNodeWithTag(track1KickBlockTag).assertExists(
            "expected the Basic Kick block to be visible on Track 1's timeline row"
        )
        composeTestRule.onNodeWithTag(track2BassBlockTag).assertExists(
            "expected the Bass Riff One block to be visible on Track 2's timeline row"
        )

        // --- (d): trigger play via the real UI control, then prove the
        // *actual native engine* (not just some UI-only counter) genuinely
        // advanced -- read AudioEngineBridge directly, independent of
        // whatever the ViewModel/UI happen to display. ---
        val frameBeforePlay = AudioEngineBridge.getCurrentFrame()
        assertEquals("expected transport to start at frame 0", 0L, frameBeforePlay)

        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameWhilePlaying = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to have advanced past 0 after " +
                "tapping Play and waiting ${PLAY_SETTLE_MS}ms, got $frameWhilePlaying",
            frameWhilePlaying > 0L
        )

        // --- (e): pause via the UI, and confirm the transport freezes
        // (mirrors the live-engine pause contract also covered by
        // LivePlaybackPauseTest, but driven here through the real UI tap). ---
        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PAUSE_SETTLE_MS)
        val frameAtPauseSettled = AudioEngineBridge.getCurrentFrame()
        Thread.sleep(PAUSED_INTERVAL_MS)
        val frameAfterPausedInterval = AudioEngineBridge.getCurrentFrame()
        assertEquals(
            "expected the transport to stay frozen while paused via the UI's Pause tap",
            frameAtPauseSettled, frameAfterPausedInterval
        )
        assertTrue(
            "sanity: the frozen paused frame ($frameAfterPausedInterval) should still be " +
                "past the frame recorded right after Play ($frameWhilePlaying, before any settle wait)",
            frameAfterPausedInterval >= frameWhilePlaying
        )

        // --- (e) continued: stop via the UI, and confirm the transport
        // resets to 0, per AudioEngineBridge.stop()'s documented contract. ---
        composeTestRule.onNodeWithTag("stop_button").performClick()
        Thread.sleep(STOP_SETTLE_MS)
        val frameAfterStop = AudioEngineBridge.getCurrentFrame()
        assertEquals(
            "expected the transport to reset to 0 after tapping Stop via the UI",
            0L, frameAfterStop
        )

        // The UI's own position readout should reflect the reset too --
        // confirms the UI state genuinely tracks the engine, not just that
        // the engine independently reset.
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("position_text").assertTextEquals("0:00")
    }

    companion object {
        private const val PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val BASS_SAMPLE_ID = "bass_riff_01" // BASS

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L

        private const val PLAY_SETTLE_MS = 700L
        private const val PAUSE_SETTLE_MS = 150L
        private const val PAUSED_INTERVAL_MS = 300L
        private const val STOP_SETTLE_MS = 150L
        private const val TEARDOWN_SETTLE_MS = 200L
    }
}
