package com.beatwave.android.ui.arrangement

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.MainActivity
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
 * Instrumented test for the multiple-saved-projects upgrade: real UI taps
 * throughout (same testing philosophy as every other UI test in this
 * suite), driving create -> switch -> per-project isolation -> rename ->
 * delete through [ProjectPickerSheet] as an actual user would, never by
 * calling [ArrangementViewModel] functions directly.
 *
 * The load-bearing assertion isn't just "the picker's actions don't crash"
 * -- it's that two DISTINCT projects' timelines never leak into each other:
 * a block added to project A must still be there (and project B's block
 * must NOT be visible) after switching away and back, proving
 * [ArrangementViewModel.switchToProject] genuinely reloads a fresh score
 * into the shared [com.beatwave.android.audio.PlaybackEngine] singleton
 * rather than merely relabeling the UI.
 */
@RunWith(AndroidJUnit4::class)
class MultipleProjectsTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetState() {
        // Unlike every other test in this suite (which only ever deletes
        // the single "current" project), this test creates ADDITIONAL
        // projects with fresh UUIDs -- wipe the whole projects/ directory,
        // not just one id, and clear the last-active-project pointer so a
        // stale id from a previous run of THIS test can't leak in.
        projectsDir().deleteRecursively()
        prefsFile().edit().clear().commit()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        Thread.sleep(TEARDOWN_SETTLE_MS)
        projectsDir().deleteRecursively()
        prefsFile().edit().clear().commit()
    }

    @Test
    fun createSwitchRenameDelete_realFlowThroughUi_neverLeaksBetweenProjects() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        val originalProjectId = viewModel!!.uiState.value.project!!.id
        assertEquals(
            "expected the very first project (no AppPreferences pointer set yet) to use the legacy default id",
            LEGACY_DEFAULT_PROJECT_ID, originalProjectId
        )

        // --- (1) Add a block to the original/default project. ---
        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (2) Open the project picker (tap the title) and create a
        // second, brand-new project. ---
        composeTestRule.onNodeWithTag("project_picker_open_button").performClick()
        val pickerOpened = runCatching {
            composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithTag("new_project_button").fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!pickerOpened) {
            throw AssertionError(
                "expected tapping project_picker_open_button to open ProjectPickerSheet within " +
                    "${LIBRARY_TIMEOUT_MS}ms. Diagnostics: showProjectPicker=${viewModel!!.uiState.value.showProjectPicker}, " +
                    "projectSummaries=${viewModel!!.uiState.value.projectSummaries}\n" +
                    composeTestRule.onRoot().printToString(maxDepth = 30)
            )
        }
        composeTestRule.onNodeWithTag("new_project_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("project_name_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("project_name_input").performTextInput(SECOND_PROJECT_NAME)
        composeTestRule.onNodeWithTag("project_name_confirm_button").performClick()

        // --- (3) Confirm we've genuinely switched to a NEW, distinct,
        // empty project -- not just renamed the same one. ---
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            viewModel!!.uiState.value.project?.name == SECOND_PROJECT_NAME
        }
        val secondProjectId = viewModel!!.uiState.value.project!!.id
        assertTrue(
            "expected the new project to have a distinct id from the original",
            secondProjectId != originalProjectId
        )
        assertTrue(
            "expected the new project to start with no loop blocks of its own",
            viewModel!!.uiState.value.project!!.tracks.all { it.loopBlocks.isEmpty() }
        )
        assertTrue(
            "expected no Basic Kick block visible after switching to the new empty project " +
                "(the timeline must reflect the NEW project, not the old one)",
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isEmpty()
        )

        // --- (4) Add a DIFFERENT bundled loop to Track 1 of the new project. ---
        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$SNARE_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$SNARE_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$SNARE_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (5) Switch back to the original project via the picker --
        // its block must still be there, and the new project's block must
        // NOT leak into this view either. ---
        composeTestRule.onNodeWithTag("project_picker_open_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("open_project_$originalProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("open_project_$originalProjectId").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            viewModel!!.uiState.value.project?.id == originalProjectId
        }
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "expected the second project's Snare block to NOT be visible after switching back " +
                "to the original project",
            composeTestRule.onAllNodesWithTag("loop_block_1_$SNARE_SAMPLE_ID").fetchSemanticsNodes().isEmpty()
        )

        // --- (6) Rename the second project via the picker, verify it
        // actually persists (checked independently via ProjectRepository,
        // not just re-reading the same ViewModel state that set it). ---
        composeTestRule.onNodeWithTag("project_picker_open_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("rename_project_$secondProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("rename_project_$secondProjectId").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("project_name_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("project_name_input").performTextClearance()
        composeTestRule.onNodeWithTag("project_name_input").performTextInput(RENAMED_PROJECT_NAME)
        composeTestRule.onNodeWithTag("project_name_confirm_button").performClick()
        val repository = ProjectRepository.forContext(context)
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(secondProjectId)?.name == RENAMED_PROJECT_NAME
        }

        // --- (7) Close the picker (it auto-refreshed mid-flow above, but
        // wasn't reopened after the rename dialog), then delete the second
        // (renamed) project -- the file must genuinely be gone from disk,
        // not just hidden from the picker's list. ---
        composeTestRule.onNodeWithTag("project_picker_open_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("delete_project_$secondProjectId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("delete_project_$secondProjectId").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("confirm_delete_project_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirm_delete_project_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            repository.load(secondProjectId) == null
        }
        assertNull("expected the deleted project to be gone from disk", repository.load(secondProjectId))
    }

    private fun projectsDir(): File = File(context.filesDir, "projects")

    private fun prefsFile() = context.getSharedPreferences("beatwave_prefs", Context.MODE_PRIVATE)

    companion object {
        // Matches ArrangementViewModel's PROJECT_ID companion constant --
        // duplicated here rather than made internal/exposed, same rationale
        // ExportShareTest gives for its own EXPORTS_DIR_NAME duplication: a
        // test asserting against the real on-disk/default-id contract
        // should independently encode what it expects.
        private const val LEGACY_DEFAULT_PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack manifest.
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val SNARE_SAMPLE_ID = "snare_basic_01" // DRUMS

        private const val SECOND_PROJECT_NAME = "Second Project"
        private const val RENAMED_PROJECT_NAME = "Renamed Project"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
        private const val TEARDOWN_SETTLE_MS = 300L
    }
}
