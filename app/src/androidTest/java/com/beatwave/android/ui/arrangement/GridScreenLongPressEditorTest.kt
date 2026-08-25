package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 * Tier 2.3 (grid-sequencer redesign, 2026-08-24 implementation plan) exit
 * criterion: long-press on a filled cell opens the real
 * [LoopBlockEditorDialog] with the tapped block's real data, and a plain
 * tap on the same cell still works (long-press must not steal or break
 * Tier 1's own tap gesture).
 *
 * There's no built-in `performLongClick()` in this project's pinned
 * Compose test library version -- [SemanticsActions.OnLongClick] is
 * invoked directly via `performSemanticsAction`, which is exactly what a
 * real long-press ultimately triggers through [combinedClickable]'s own
 * semantics wiring (see [GridCell]).
 *
 * See [GridScreenRenderingTest]'s class doc comment for why this hosts
 * [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenLongPressEditorTest {

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
                name = "Long Press Test",
                bpm = 120,
                tracks = listOf(
                    Track(
                        slot = 1,
                        loopBlocks = listOf(
                            LoopBlock(
                                id = "existing-block",
                                sampleId = BASS_SAMPLE_ID,
                                startGridUnit = 5,
                                lengthGridUnits = 1,
                                pitchRow = 1,
                                pitchSemitones = 1f
                            )
                        ),
                        assignedSampleIds = listOf(BASS_SAMPLE_ID)
                    )
                ) + (2..8).map { slot -> Track(slot = slot) },
                createdAtEpochMs = 0L,
                modifiedAtEpochMs = 0L
            )
        )
    }

    @Test
    fun longPressFilledCell_opensEditorDialog_withTheRealBlocksSample() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("grid_cell_5_1")
            .performSemanticsAction(SemanticsActions.OnLongClick)

        // The real LoopBlockEditorDialog shows the sample's own name as
        // its title (see LoopBlockEditor.kt) -- confirms the dialog opened
        // with the tapped block's real, resolved data, not a stub.
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Bass Riff One").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tapStillWorks_afterCombinedClickableReplacesPlainClickable() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap an EMPTY cell -- places a block, same as Tier 1's own proof.
        composeTestRule.onNodeWithTag("grid_cell_0_0").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { nodeReportsFilled("grid_cell_0_0") }

        composeTestRule.onNodeWithTag("grid_cell_0_0").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { !nodeReportsFilled("grid_cell_0_0") }
    }

    private fun nodeReportsFilled(testTag: String): Boolean {
        val node = composeTestRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().firstOrNull() ?: return false
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: return false
        return description.any { it.contains("Filled") }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val BASS_SAMPLE_ID = "bass_riff_01" // BASS, name "Bass Riff One"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
    }
}
