package com.beatwave.android.ui.arrangement

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 (grid-sequencer cutover) exit-criterion coverage for the two
 * pieces of GridScreen wiring that have no Tier 0-2 equivalent to lean
 * on: recording (a genuinely new addition -- Tiers 0-2's own tests always
 * used pre-assigned fixture tracks, never exercising a fresh recording's
 * interaction with gridTrackKind/assignedSampleIds at all) and project
 * switching/create/rename/delete (ported from ArrangementScreen's own
 * top-bar picker, mirroring [MultipleProjectsTest]'s own real-UI-taps
 * philosophy for GridScreen instead).
 *
 * See [GridScreenRenderingTest]'s class doc comment for why this hosts
 * [GridScreen] directly via `createAndroidComposeRule<ComponentActivity>`
 * rather than launching MainActivity (which still launches
 * [ArrangementScreen] until the actual Tier 3 cutover commit).
 */
@RunWith(AndroidJUnit4::class)
class GridScreenTier3CapabilityTest {

    // RECORD_AUDIO auto-granted before the test runs, same established
    // pattern as RecordingLiveHardwareCaptureTest -- avoids needing to
    // drive a real system permission dialog (a separate window entirely,
    // see this project's own AlertDialog/separate-window findings).
    @get:Rule
    val grantRecordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val repository: ProjectRepository
        get() = ProjectRepository.forContext(context)

    private fun projectsDir(): File = File(context.filesDir, "projects")
    private fun prefsFile() = context.getSharedPreferences("beatwave_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        // Wipe the whole projects/ directory (not just one id) and the
        // last-active-project pointer -- the project-picker test creates
        // ADDITIONAL projects with fresh UUIDs, same reasoning as
        // MultipleProjectsTest's own @Before.
        projectsDir().deleteRecursively()
        prefsFile().edit().clear().commit()
        repository.save(
            Project(
                id = PROJECT_ID, name = "Tier 3 Capability Test", bpm = 120,
                tracks = (1..8).map { Track(slot = it) },
                createdAtEpochMs = 0L, modifiedAtEpochMs = 0L
            )
        )
    }

    @After
    fun tearDown() {
        Thread.sleep(TEARDOWN_SETTLE_MS)
        projectsDir().deleteRecursively()
        prefsFile().edit().clear().commit()
    }

    @Test
    fun recordOntoAnUnassignedTrack_capturesRealAudio_andBecomesARealVisibleMelodicBlock() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntilSafely(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("record_button_1").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("record_button_1").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("stop_record_button_1").fetchSemanticsNodes().isNotEmpty()
        }
        // Capture a real, short take of actual (silent or ambient) hardware
        // audio -- mirrors RecordingLiveHardwareCaptureTest's own real
        // recording duration.
        Thread.sleep(RECORDING_DURATION_MS)
        composeTestRule.onNodeWithTag("stop_record_button_1").performClick()

        // Stopping a recording routes through CategoryPickerDialog, the
        // exact same dialog/testTags a device import uses.
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_option_${SampleCategory.VOCAL.name}").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("category_option_${SampleCategory.VOCAL.name}").performClick()
        composeTestRule.onNodeWithTag("category_confirm_button").performClick()

        // Real proof, read back independently rather than trusting a UI
        // flag: a genuinely new Sample was persisted, Track 1 got a real
        // LoopBlock referencing it, AND -- the actual Tier 3 integration
        // gap this test exists to prove is fixed -- the track's
        // assignedSampleIds now names that sample (a VOCAL/melodic
        // category), with the block's pitchRow set so it's a real,
        // visible MelodicGrid row, not an invisible or misclassified one.
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(PROJECT_ID)?.tracks?.firstOrNull { it.slot == 1 }?.loopBlocks?.isNotEmpty() == true
        }
        val finalProject = repository.load(PROJECT_ID)!!
        val track1 = finalProject.tracks.first { it.slot == 1 }
        assertEquals("expected exactly one recorded block on Track 1", 1, track1.loopBlocks.size)
        val recordedBlock = track1.loopBlocks.single()
        assertEquals(
            "expected Track 1 to become assigned to the just-recorded sample",
            listOf(recordedBlock.sampleId), track1.assignedSampleIds
        )
        assertEquals(
            "expected a melodic-category recording's block to get a real pitchRow (0), " +
                "not null -- MelodicGrid only renders pitchRow != null blocks",
            0, recordedBlock.pitchRow
        )

        // And it's genuinely VISIBLE in the real rendered grid, not just
        // correct in the persisted data -- switch focus to Track 1 (already
        // focused by default here) and confirm the cell renders filled.
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("grid_cell_${recordedBlock.startGridUnit}_0").assertContentDescriptionContainsFilled()
    }

    @Test
    fun createSwitchRenameDelete_realFlowThroughGridScreen_neverLeaksBetweenProjects() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntilSafely(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_project_title").fetchSemanticsNodes().isNotEmpty()
        }

        var viewModel: ArrangementViewModel? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            viewModel = androidx.lifecycle.ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        val originalProjectId = viewModel!!.uiState.value.project!!.id
        assertEquals(PROJECT_ID, originalProjectId)

        // --- (1) Assign a sample to Track 1 of the original project (so
        // there's real per-project state to prove isolation with). ---
        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(originalProjectId)?.tracks?.firstOrNull { it.slot == 1 }
                ?.assignedSampleIds?.contains(KICK_SAMPLE_ID) == true
        }

        // --- (2) Open the project picker (tap the title) and create a
        // second, brand-new project. ---
        composeTestRule.onNodeWithTag("grid_project_title").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("new_project_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("new_project_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("project_name_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("project_name_input").performTextInput(SECOND_PROJECT_NAME)
        composeTestRule.onNodeWithTag("project_name_confirm_button").performClick()

        // --- (3) Confirm we've genuinely switched to a NEW, distinct,
        // empty project. ---
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            viewModel!!.uiState.value.project?.name == SECOND_PROJECT_NAME
        }
        val secondProjectId = viewModel!!.uiState.value.project!!.id
        assertTrue("expected the new project to have a distinct id", secondProjectId != originalProjectId)
        assertTrue(
            "expected the new project to start with no track assignments of its own",
            viewModel!!.uiState.value.project!!.tracks.all { it.assignedSampleIds.isEmpty() }
        )

        // --- (4) Assign a DIFFERENT sample to Track 1 of the new project. ---
        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$SNARE_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$SNARE_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(secondProjectId)?.tracks?.firstOrNull { it.slot == 1 }
                ?.assignedSampleIds?.contains(SNARE_SAMPLE_ID) == true
        }

        // --- (5) Switch back to the original project -- its assignment
        // must still be there, and the new project's must NOT leak in. ---
        composeTestRule.onNodeWithTag("grid_project_title").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("open_project_$originalProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("open_project_$originalProjectId").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            viewModel!!.uiState.value.project?.id == originalProjectId
        }
        assertEquals(
            "expected the original project's Track 1 assignment to survive the round trip",
            listOf(KICK_SAMPLE_ID), viewModel!!.uiState.value.project!!.tracks.first { it.slot == 1 }.assignedSampleIds
        )

        // --- (6) Rename the second project via the picker, verify it
        // persists (checked independently via ProjectRepository). ---
        composeTestRule.onNodeWithTag("grid_project_title").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("rename_project_$secondProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("rename_project_$secondProjectId").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("project_name_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("project_name_input").performTextClearance()
        composeTestRule.onNodeWithTag("project_name_input").performTextInput(RENAMED_PROJECT_NAME)
        composeTestRule.onNodeWithTag("project_name_confirm_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(secondProjectId)?.name == RENAMED_PROJECT_NAME
        }

        // --- (7) Delete the second (renamed) project -- must genuinely be
        // gone from disk. ---
        composeTestRule.onNodeWithTag("grid_project_title").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("delete_project_$secondProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("delete_project_$secondProjectId").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("confirm_delete_project_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirm_delete_project_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(secondProjectId) == null
        }
        assertNull("expected the deleted project to be gone from disk", repository.load(secondProjectId))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertContentDescriptionContainsFilled() {
        assertContentDescriptionContains("Filled", substring = true)
    }

    companion object {
        private const val PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack manifest.
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val SNARE_SAMPLE_ID = "snare_basic_01" // DRUMS

        private const val SECOND_PROJECT_NAME = "Second Grid Project"
        private const val RENAMED_PROJECT_NAME = "Renamed Grid Project"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 10_000L
        private const val RECORDING_DURATION_MS = 1_500L
        private const val TEARDOWN_SETTLE_MS = 300L
    }
}
