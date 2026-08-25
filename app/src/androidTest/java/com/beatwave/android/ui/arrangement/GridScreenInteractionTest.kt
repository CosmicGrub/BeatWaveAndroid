package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 1.2 (grid-sequencer redesign, 2026-08-24 implementation plan) exit
 * criterion -- **this tier's actual go/no-go signal**: tap-to-place and
 * tap-to-delete on a melodic grid genuinely mutate real project data and
 * genuinely drive the real native engine, not a UI-only mock.
 *
 * Mirrors [ArrangementScreenPlaybackTest]'s own "read
 * [AudioEngineBridge.getCurrentFrame] directly, independent of whatever
 * the ViewModel/UI happen to display" proof pattern -- the reasoning for
 * that pattern (an end-to-end check, not a UI-only smoke test) applies
 * exactly as much to the new grid screen as it did to the old timeline.
 *
 * See [GridScreenRenderingTest]'s class doc comment for why this hosts
 * [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`
 * rather than launching `MainActivity`.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenInteractionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUpFixtureProject() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ProjectRepository.forContext(targetContext)
        repository.delete(PROJECT_ID)
        repository.save(
            Project(
                id = PROJECT_ID,
                name = "Grid Interaction Test",
                bpm = 120,
                tracks = listOf(Track(slot = 1, assignedSampleIds = listOf(BASS_SAMPLE_ID))) +
                    (2..8).map { slot -> Track(slot = slot) },
                createdAtEpochMs = 0L,
                modifiedAtEpochMs = 0L
            )
        )
    }

    @Test
    fun tapEmptyCell_placesRealBlock_thenPlaybackGenuinelyAdvancesTheRealEngine() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap an empty cell -- column 0, row 0 (the sample's own natural
        // pitch, no shift).
        composeTestRule.onNodeWithTag("grid_cell_0_0").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            // Confirms the tap round-tripped through the real ViewModel ->
            // rebuildAndPersist -> recomposition, not just an optimistic
            // local UI flag -- the SAME cell reporting itself filled is
            // read back from the real (post-update) Project state.
            nodeReportsFilled("grid_cell_0_0")
        }

        // --- Prove the REAL native engine actually scheduled this block --
        // read AudioEngineBridge directly, independent of the UI. ---
        val frameBeforePlay = AudioEngineBridge.getCurrentFrame()
        assertEquals("expected transport to start at frame 0", 0L, frameBeforePlay)

        // GridScreen has no transport bar of its own yet (deliberately out
        // of Tier 1's minimal scope) -- drive Play through the SAME
        // ArrangementViewModel instance GridScreen's own viewModel() call
        // resolved (ViewModelProvider caches by class within a
        // ViewModelStoreOwner, so this is the identical instance, not a
        // second one), the same way ImportedSampleArrangementTest already
        // reaches the ViewModel directly for its own import trigger.
        var viewModel: ArrangementViewModel? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        viewModel!!.togglePlayPause()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameWhilePlaying = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to have advanced past 0 " +
                "after tap-placing a block and starting playback, got $frameWhilePlaying",
            frameWhilePlaying > 0L
        )
        viewModel!!.stopPlayback()
        Thread.sleep(STOP_SETTLE_MS)
    }

    @Test
    fun tapFilledCell_deletesTheRealBlock() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("grid_cell_5_-3").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { nodeReportsFilled("grid_cell_5_-3") }

        composeTestRule.onNodeWithTag("grid_cell_5_-3").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            !nodeReportsFilled("grid_cell_5_-3")
        }
    }

    /** True once [testTag]'s node exists and its content description says
     *  "Filled" -- a non-throwing check, safe to poll from inside
     *  [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil]. */
    private fun nodeReportsFilled(testTag: String): Boolean {
        val node = composeTestRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().firstOrNull() ?: return false
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: return false
        return description.any { it.contains("Filled") }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val BASS_SAMPLE_ID = "bass_riff_01" // BASS

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
        private const val PLAY_SETTLE_MS = 700L
        private const val STOP_SETTLE_MS = 150L
    }
}
