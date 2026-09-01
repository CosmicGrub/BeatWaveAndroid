package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * Tier 2.4 (grid-sequencer redesign, 2026-08-24 implementation plan) exit
 * criterion: melodic-replace and drum-add/remove through the real Sounds
 * picker (repurposed [LoopLibraryContent]/[LoopLibraryBottomSheet]),
 * including "existing notes survive a reassignment" -- an orphaned drum
 * row (its sample removed via Sounds) must keep rendering, with its notes
 * still there, rather than silently disappearing.
 *
 * See [GridScreenRenderingTest]'s class doc comment for why this hosts
 * [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenSoundsPickerTest {

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
                name = "Sounds Picker Test",
                bpm = 120,
                tracks = listOf(
                    // Track 1: melodic, one existing block.
                    Track(
                        slot = 1,
                        loopBlocks = listOf(
                            LoopBlock(
                                id = "melodic-block",
                                sampleId = BASS_SAMPLE_ID,
                                startGridUnit = 2,
                                lengthGridUnits = 1,
                                pitchRow = 4,
                                pitchSemitones = 4f
                            )
                        ),
                        assignedSampleIds = listOf(BASS_SAMPLE_ID)
                    ),
                    // Track 2: unassigned -- for the drum-add case.
                    Track(slot = 2),
                    // Track 3: drum kit, kick + snare, one note on each row.
                    Track(
                        slot = 3,
                        loopBlocks = listOf(
                            LoopBlock(id = "kick-block", sampleId = KICK_SAMPLE_ID, startGridUnit = 0, lengthGridUnits = 1, pitchRow = null),
                            LoopBlock(id = "snare-block", sampleId = SNARE_SAMPLE_ID, startGridUnit = 6, lengthGridUnits = 1, pitchRow = null)
                        ),
                        assignedSampleIds = listOf(KICK_SAMPLE_ID, SNARE_SAMPLE_ID)
                    )
                ) + (4..8).map { slot -> Track(slot = slot) },
                createdAtEpochMs = 0L,
                modifiedAtEpochMs = 0L
            )
        )
    }

    @Test
    fun melodicTrack_soundsAdd_replacesAssignment_andExistingBlockSurvivesUnderNewSample() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        // The existing block renders before any reassignment.
        composeTestRule.onNodeWithTag("grid_cell_2_4").assertContentDescriptionContains("Filled", substring = true)

        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_library_card_list").fetchSemanticsNodes().isNotEmpty()
        }
        // The bundled samples sort alphabetically by NAME ("Basic Kick",
        // "Basic Snare", "Bass Riff One", "Bass Riff Two", "Synth Arp", ...)
        // -- "Synth Arp" is the 5th of 8, genuinely outside the sheet's
        // initial composed viewport (a 420dp-capped sheet only comfortably
        // shows ~4 cards), unlike KICK_SAMPLE_ID (2nd) which every OTHER
        // test in this file targets. onAllNodesWithTag can't find a node
        // that was never composed in the first place -- a plain waitUntil
        // here would poll forever, since nothing about the app's own state
        // ever changes to make it appear on its own. performScrollToNode
        // scrolls loop_library_card_list until the target IS composed, the
        // same real-hardware gap this project's own history already found
        // and fixed the same way for an off-screen loop-library card (see
        // ImportedSampleArrangementTest/FullIntegrationWalkthroughTest).
        composeTestRule.onNodeWithTag("loop_library_card_list")
            .performScrollToNode(hasTestTag("add_loop_$SYNTH_SAMPLE_ID"))
        composeTestRule.onNodeWithTag("add_loop_$SYNTH_SAMPLE_ID").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            // The sheet re-renders once assignedSampleIds updates -- the
            // just-assigned sample's card now offers "Remove".
            composeTestRule.onAllNodesWithTag("remove_loop_$SYNTH_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        // The pre-existing block at the same cell is untouched -- it now
        // plays the newly-assigned sample at the same pitchRow.
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("grid_cell_2_4").assertContentDescriptionContains("Filled", substring = true)
        composeTestRule.onNodeWithTag("track_pill_1").assertContentDescriptionContains("Synth", substring = true)
    }

    @Test
    fun unassignedTrack_soundsAddDrumSample_becomesADrumKitRow() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_pill_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("track_pill_2").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_empty_track_prompt").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("remove_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_drum_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("grid_row_label_$KICK_SAMPLE_ID").assertExists()
    }

    @Test
    fun drumTrack_soundsRemove_hidesFromPicker_butRowAndExistingNoteStayVisibleInGrid() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_pill_3").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("track_pill_3").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_drum_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        // The kick row's existing note, before removal.
        composeTestRule.onNodeWithTag("grid_cell_0_$KICK_SAMPLE_ID")
            .assertContentDescriptionContains("Filled", substring = true)

        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("remove_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("remove_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            // The card flips back to "Add" once kick is out of assignedSampleIds.
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        // Exit criterion: the kick row (now orphaned -- no longer in
        // assignedSampleIds) still renders in the grid, and its
        // pre-existing note is still there, not silently deleted.
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_drum_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("grid_row_label_$KICK_SAMPLE_ID").assertExists()
        composeTestRule.onNodeWithTag("grid_cell_0_$KICK_SAMPLE_ID")
            .assertContentDescriptionContains("Filled", substring = true)
        // The still-assigned snare row is unaffected.
        composeTestRule.onNodeWithTag("grid_row_label_$SNARE_SAMPLE_ID").assertExists()
    }

    companion object {
        private const val PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val BASS_SAMPLE_ID = "bass_riff_01" // BASS
        private const val SYNTH_SAMPLE_ID = "synth_arp_01" // SYNTH
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val SNARE_SAMPLE_ID = "snare_basic_01" // DRUMS

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
    }
}
