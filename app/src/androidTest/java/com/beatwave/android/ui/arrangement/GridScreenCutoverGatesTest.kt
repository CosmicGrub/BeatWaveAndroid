package com.beatwave.android.ui.arrangement

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.audio.GridConstants
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Track
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 cutover gates (audit item ARCH-3a), each proven through the real
 * GridScreen on real hardware: legacy melodic blocks become visible, the
 * grid widens past 64 columns, the ruler seeks, a vertical scroll no
 * longer places a note, multi-unit drum blocks fill their whole span,
 * melodic reassignment moves existing notes to the new sample, a
 * drum<->melodic Sounds assignment is refused, and every cell announces a
 * distinct position.
 *
 * Hosted directly via createAndroidComposeRule like every Grid*Test.
 * Never queries Compose while the transport is playing (see the
 * recording test's note) -- nothing here starts playback.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenCutoverGatesTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUpFixtureProject() {
        val repository = ProjectRepository.forContext(InstrumentationRegistry.getInstrumentation().targetContext)
        repository.delete(PROJECT_ID)
        repository.save(
            Project(
                id = PROJECT_ID, name = "Cutover Gates", bpm = 120,
                tracks = listOf(
                    // Track 1: melodic, a LEGACY block (pitchRow null, as saved
                    // before the grid existed) plus one far past column 64.
                    Track(
                        slot = 1,
                        loopBlocks = listOf(
                            LoopBlock("legacy", BASS_ID, startGridUnit = 3, lengthGridUnits = 1, pitchSemitones = 7f, pitchRow = null)
                        ),
                        assignedSampleIds = listOf(BASS_ID)
                    ),
                    // Track 2: drum kit with one 3-unit-long kick block.
                    Track(
                        slot = 2,
                        loopBlocks = listOf(LoopBlock("longkick", KICK_ID, startGridUnit = 4, lengthGridUnits = 3, pitchRow = null)),
                        assignedSampleIds = listOf(KICK_ID)
                    )
                ) + (3..8).map { Track(slot = it) },
                createdAtEpochMs = 0L, modifiedAtEpochMs = 0L
            )
        )
    }

    private fun startGrid() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntilSafely(INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun viewModel(): ArrangementViewModel {
        var vm: ArrangementViewModel? = null
        composeTestRule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[ArrangementViewModel::class.java] }
        return vm!!
    }

    private fun description(tag: String): String =
        composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""

    @Test
    fun wideBlock_pastTheOldColumnCeiling_isReachable() {
        // Kept out of the shared fixture: a 128-column melodic grid is ~3,200
        // eagerly-composed cells, which makes every OTHER test slow on an
        // emulator (known limitation, see the audit's UX-1 item).
        val repository = ProjectRepository.forContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val base = repository.load(PROJECT_ID)!!
        repository.save(base.copy(tracks = base.tracks.map { t ->
            if (t.slot != 1) t else t.copy(loopBlocks = t.loopBlocks + LoopBlock("far", BASS_ID, 100, 2, pitchSemitones = 0f, pitchRow = 0))
        }))
        startGrid()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { description("grid_cell_101_0").startsWith("Filled") }
    }

    @Test
    fun legacyMelodicBlock_isVisibleAndDeletable() {
        startGrid()
        // legacy block: pitchRow was null, now backfilled to its +7 semitones.
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { description("grid_cell_3_7").startsWith("Filled") }
        // and it is deletable through the normal tap path.
        composeTestRule.onNodeWithTag("grid_cell_3_7").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { description("grid_cell_3_7").startsWith("Empty") }
    }

    @Test
    fun cells_announceDistinctPositions() {
        startGrid()
        val a = description("grid_cell_0_0")
        val b = description("grid_cell_1_0")
        val c = description("grid_cell_0_5")
        assertTrue("expected a position-aware description, got '$a'", a.contains("bar 1 beat 1 step 1"))
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun verticalSwipeStartingOnAnEmptyCell_doesNotPlaceANote() {
        startGrid()
        composeTestRule.onNodeWithTag("grid_cell_5_4").performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.1f) }
        composeTestRule.waitForIdle()
        assertTrue("a vertical scroll gesture must not place a note", description("grid_cell_5_4").startsWith("Empty"))
        // a real tap still places one (the gate must not break tap-to-place).
        composeTestRule.onNodeWithTag("grid_cell_5_4").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { description("grid_cell_5_4").startsWith("Filled") }
    }

    @Test
    fun rulerTap_seeksTheRealTransport() {
        startGrid()
        val sampleRate = AudioEngineBridge.getSampleRate()
        assertTrue("engine sample rate unavailable", sampleRate > 0)
        val cellPx = androidx.compose.ui.unit.Density(composeTestRule.activity.resources.displayMetrics.density).run { 28.dp.toPx() }
        composeTestRule.onNodeWithTag("grid_scrub_strip").performTouchInput { click(Offset(cellPx * 8.5f, 4f)) }
        val expected = (8 * GridConstants.framesPerGridUnit(120, sampleRate)).toLong()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { AudioEngineBridge.getCurrentFrame() == expected }
        assertEquals(expected, AudioEngineBridge.getCurrentFrame())
    }

    @Test
    fun multiUnitDrumBlock_fillsItsWholeSpan() {
        startGrid()
        composeTestRule.onNodeWithTag("track_pill_2").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_drum_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        for (column in 4..6) {
            composeTestRule.onNodeWithTag("grid_cell_${column}_$KICK_ID").assertContentDescriptionContains("Filled", substring = true)
        }
        composeTestRule.onNodeWithTag("grid_cell_7_$KICK_ID").assertContentDescriptionContains("Empty", substring = true)
    }

    @Test
    fun melodicReassign_movesExistingNotesToTheNewSample_andCrossFamilyIsRefused() {
        startGrid()
        val vm = viewModel()
        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_library_card_list").fetchSemanticsNodes().isNotEmpty()
        }
        // A DRUM sample on this melodic track must be refused, assignment unchanged.
        composeTestRule.onNodeWithTag("add_loop_$KICK_ID").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) { vm.uiState.value.message?.contains("melodic") == true }
        assertEquals(listOf(BASS_ID), vm.uiState.value.project!!.tracks.first { it.slot == 1 }.assignedSampleIds)

        // A melodic sample replaces the instrument AND the existing notes follow it.
        composeTestRule.onNodeWithTag("loop_library_card_list").performScrollToNode(hasTestTag("add_loop_$SYNTH_ID"))
        composeTestRule.onNodeWithTag("add_loop_$SYNTH_ID").performClick()
        composeTestRule.waitUntilSafely(LIBRARY_TIMEOUT_MS) {
            vm.uiState.value.project!!.tracks.first { it.slot == 1 }.assignedSampleIds == listOf(SYNTH_ID)
        }
        val blocks = vm.uiState.value.project!!.tracks.first { it.slot == 1 }.loopBlocks
        assertTrue("every melodic block must now play the new sample: $blocks", blocks.all { it.sampleId == SYNTH_ID })
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val BASS_ID = "bass_riff_01"
        private const val SYNTH_ID = "synth_arp_01"
        private const val KICK_ID = "kick_basic_01"
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
    }
}
