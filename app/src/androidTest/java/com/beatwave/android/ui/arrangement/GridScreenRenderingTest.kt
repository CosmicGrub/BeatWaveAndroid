package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 1.1 (grid-sequencer redesign, 2026-08-24 implementation plan) exit
 * criterion: correct row labels/count and existing (migrated) blocks
 * rendering at the right position for a melodic track, plus the
 * unassigned/not-yet-supported prompt states.
 *
 * Hosts [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`
 * -- [GridScreen] isn't wired into `MainActivity`'s real navigation until
 * Tier 3. This is the same pattern this project's own Phase 3 S-Pen work
 * established, for the same reason: a real, fully-functional
 * `ArrangementViewModel` (backed by the real `BeatWaveApplication`/
 * `PlaybackEngine`/`ProjectRepository` singleton machinery -- Android's
 * `Application` object is process-wide, not tied to which Activity class
 * hosts the Compose content) without touching `MainActivity` yet.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenRenderingTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUpFixtureProject() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ProjectRepository.forContext(targetContext)
        repository.delete(PROJECT_ID)
        repository.save(fixtureProject())
    }

    @Test
    fun melodicTrack_rendersChromaticRowsAndExistingBlock() {
        composeTestRule.setContent { GridScreen() }

        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        // 25 chromatic rows, +12 down to -12.
        composeTestRule.onNodeWithTag("grid_row_label_12").assertExists()
        composeTestRule.onNodeWithTag("grid_row_label_0").assertExists()
        composeTestRule.onNodeWithTag("grid_row_label_-12").assertExists()

        // The fixture's existing block (column=3, row=2, migrated/placed
        // pre-test via ProjectRepository directly, not through the UI)
        // renders filled; its row-1 neighbor at the same column does not.
        // substring = true: the API's default is an EXACT list-membership
        // match against the full contentDescription string, not a
        // substring check -- GridCell's actual description is the full
        // sentence "Filled cell, tap to remove" / "Empty cell, tap to
        // place", so the default would always fail here.
        composeTestRule.onNodeWithTag("grid_cell_3_2")
            .assertContentDescriptionContains("Filled", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_3_1")
            .assertContentDescriptionContains("Empty", substring = true)
    }

    @Test
    fun unassignedTrack_showsChooseInstrumentPrompt() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_pill_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("track_pill_2").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_empty_track_prompt").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun drumAssignedTrack_showsNotYetSupportedPrompt() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_pill_3").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("track_pill_3").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_not_yet_supported_prompt").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fixtureProject(): Project = Project(
        id = PROJECT_ID,
        name = "Grid Test Project",
        bpm = 120,
        tracks = listOf(
            // Track 1: melodic, one existing block at (column=3, row=2) --
            // this is what "existing migrated blocks render at the right
            // position" actually checks.
            Track(
                slot = 1,
                loopBlocks = listOf(
                    LoopBlock(
                        id = "existing-block",
                        sampleId = BASS_SAMPLE_ID,
                        startGridUnit = 3,
                        lengthGridUnits = 1,
                        pitchRow = 2,
                        pitchSemitones = 2f
                    )
                ),
                assignedSampleIds = listOf(BASS_SAMPLE_ID)
            ),
            // Track 2: unassigned.
            Track(slot = 2),
            // Track 3: DRUMS-assigned -- Tier 1's "not yet supported" case.
            Track(slot = 3, assignedSampleIds = listOf(KICK_SAMPLE_ID))
        ) + (4..8).map { slot -> Track(slot = slot) },
        createdAtEpochMs = 0L,
        modifiedAtEpochMs = 0L
    )

    companion object {
        private const val PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val BASS_SAMPLE_ID = "bass_riff_01" // BASS
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
    }
}
