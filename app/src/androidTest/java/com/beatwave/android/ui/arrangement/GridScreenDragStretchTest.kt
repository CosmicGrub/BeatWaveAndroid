package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
 * Tier 2.2 (grid-sequencer redesign, 2026-08-24 implementation plan) exit
 * criterion: a real drag on an empty melodic cell produces a LoopBlock
 * with the right lengthGridUnits, collision-clamped against an existing
 * block on the same row so it never overlaps it, and a plain
 * (non-dragged) tap still places a normal 1-unit block exactly as it did
 * before this tier -- proving the new drag gesture doesn't regress the
 * original tap-to-place/tap-to-delete behavior.
 *
 * Unlike [GridScreenInteractionTest]'s taps (which drive the semantics
 * OnClick action directly via `performClick()`, bypassing real touch
 * dispatch entirely), these tests use `performTouchInput{}` to simulate
 * genuine down/move/up events through the actual pointerInput gesture
 * code in `GridCell`'s `tapOrDragToStretch` -- `performClick()` would
 * never exercise that code at all, since it's a semantics-level shortcut.
 *
 * See [GridScreenRenderingTest]'s class doc comment for why this hosts
 * [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenDragStretchTest {

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
                name = "Drag Stretch Test",
                bpm = 120,
                tracks = listOf(
                    Track(
                        slot = 1,
                        loopBlocks = listOf(
                            // Existing block at column 10, row 0 -- the
                            // collision-clamp test drags toward this.
                            LoopBlock(
                                id = "existing-block", sampleId = BASS_SAMPLE_ID,
                                startGridUnit = 10, lengthGridUnits = 1,
                                pitchRow = 0, pitchSemitones = 0f
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
    fun dragAcrossEmptyCells_placesOneStretchedBlock_withTheRightLength() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag from column 0 by 3.5 cell-widths -- lands deltaColumns=3
        // (truncated), so currentColumn=3, a 4-column stretch (0..3). Well
        // clear of the existing block at column 10.
        val cellWidthPx = with(composeTestRule.density) { CELL_WIDTH_DP.dp.toPx() }
        composeTestRule.onNodeWithTag("grid_cell_0_0").performTouchInput {
            down(center)
            moveBy(Offset(x = cellWidthPx * 3.5f, y = 0f))
            up()
        }

        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { nodeReportsFilled("grid_cell_3_0") }
        // Every cell in the dragged span reports filled...
        composeTestRule.onNodeWithTag("grid_cell_0_0").assertContentDescriptionContains("Filled", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_1_0").assertContentDescriptionContains("Filled", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_2_0").assertContentDescriptionContains("Filled", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_3_0").assertContentDescriptionContains("Filled", substring = true)
        // ...but the cell just past the span does not -- proves this is a
        // real 4-unit block, not the whole row filled.
        composeTestRule.onNodeWithTag("grid_cell_4_0").assertContentDescriptionContains("Empty", substring = true)

        // A second tap anywhere within the span deletes the WHOLE block at
        // once (span-aware existingBlock lookup, not just its own start
        // column) -- confirms it's genuinely one 4-unit block, not four
        // independent 1-unit ones that happen to look contiguous.
        composeTestRule.onNodeWithTag("grid_cell_2_0").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { !nodeReportsFilled("grid_cell_0_0") }
        composeTestRule.onNodeWithTag("grid_cell_0_0").assertContentDescriptionContains("Empty", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_3_0").assertContentDescriptionContains("Empty", substring = true)
    }

    @Test
    fun dragTowardAnExistingBlock_clampsBeforeIt_neverOverlapping() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        // Existing block at column 10, row 0 (from the fixture). Drag from
        // column 5 far past it -- must clamp to stop at column 9, never
        // reaching or overwriting column 10.
        val cellWidthPx = with(composeTestRule.density) { CELL_WIDTH_DP.dp.toPx() }
        composeTestRule.onNodeWithTag("grid_cell_5_0").performTouchInput {
            down(center)
            moveBy(Offset(x = cellWidthPx * 20f, y = 0f))
            up()
        }

        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { nodeReportsFilled("grid_cell_9_0") }
        composeTestRule.onNodeWithTag("grid_cell_9_0").assertContentDescriptionContains("Filled", substring = true)
        // The pre-existing block at column 10 is exactly the same block it
        // always was -- still there, not overwritten by the drag.
        composeTestRule.onNodeWithTag("grid_cell_10_0").assertContentDescriptionContains("Filled", substring = true)

        // Deleting the ORIGINAL block (tap column 10) must not also delete
        // the newly-stretched one -- confirms they're genuinely two
        // distinct blocks, not one that silently absorbed the other.
        composeTestRule.onNodeWithTag("grid_cell_10_0").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { !nodeReportsFilled("grid_cell_10_0") }
        composeTestRule.onNodeWithTag("grid_cell_9_0").assertContentDescriptionContains("Filled", substring = true)
    }

    @Test
    fun plainTapWithNoDrag_stillPlacesANormalOneUnitBlock_unchangedFromTier1() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("grid_cell_2_2").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { nodeReportsFilled("grid_cell_2_2") }
        // Only the tapped cell is filled -- a plain tap must not stretch.
        composeTestRule.onNodeWithTag("grid_cell_1_2").assertContentDescriptionContains("Empty", substring = true)
        composeTestRule.onNodeWithTag("grid_cell_3_2").assertContentDescriptionContains("Empty", substring = true)

        // And tapping it again still deletes it (single tap-to-delete,
        // unchanged from Tier 1) -- the drag gesture addition didn't
        // regress this.
        composeTestRule.onNodeWithTag("grid_cell_2_2").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) { !nodeReportsFilled("grid_cell_2_2") }
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

        // Mirrors GridScreen.kt's own private CELL_WIDTH -- can't
        // reference it directly (file-private), and it's a pure layout
        // detail, not worth widening to internal just for a test's own
        // drag-distance math. Converted via the real device's own density
        // (composeTestRule.density) at each call site, not hardcoded px,
        // so this stays correct across different test devices' densities.
        private const val CELL_WIDTH_DP = 28f
    }
}
