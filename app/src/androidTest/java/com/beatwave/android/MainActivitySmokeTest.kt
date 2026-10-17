package com.beatwave.android

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.data.storage.ProjectRepository
import com.beatwave.android.ui.arrangement.waitUntilSafely
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 cutover exit criterion: the app's REAL launch (MainActivity, not a
 * composable hosted by a test rule) shows the grid screen, and keeps showing
 * it -- with its transport bar -- after a configuration-style recreation
 * (rotation/fold), which is what the share-intent and rememberSaveable gates
 * exist for.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshProject() {
        ProjectRepository.forContext(InstrumentationRegistry.getInstrumentation().targetContext).delete("current")
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        Thread.sleep(300)
    }

    private fun gridIsShown(): Boolean =
        composeTestRule.onAllNodesWithTag("grid_project_title").fetchSemanticsNodes().isNotEmpty() &&
            composeTestRule.onAllNodesWithTag("play_pause_button").fetchSemanticsNodes().isNotEmpty() &&
            composeTestRule.onAllNodesWithTag("grid_sounds_button").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun realLaunch_showsTheGridScreen_andSurvivesRecreation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntilSafely(15_000L) { gridIsShown() }
        // the old timeline screen must be gone
        assertTrue(composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isEmpty())

        // open the project picker, then recreate: the real project survives
        // and the grid comes back (picker state is rememberSaveable-free by
        // design -- it lives in the ViewModel, which survives recreation).
        composeTestRule.onNodeWithTag("grid_project_title").performClick()
        scenario.recreate()
        composeTestRule.waitUntilSafely(15_000L) { gridIsShown() }
    }
}
