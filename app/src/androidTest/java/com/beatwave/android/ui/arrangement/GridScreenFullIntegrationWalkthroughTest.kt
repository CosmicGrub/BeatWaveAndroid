package com.beatwave.android.ui.arrangement

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 (Cutover) grid-screen equivalent of [FullIntegrationWalkthroughTest]
 * -- same exit criterion ("a complete user flow -- build an arrangement from
 * bundled + imported loops, play it back, background it, control it from
 * lock screen -- works without issues"), same continuous-single-project
 * proof shape, driven entirely through [GridScreen]'s own UI instead of
 * ArrangementScreen's TrackRow/loop-library-sheet flow.
 *
 * Two things distinguish the grid path from the original test, both real
 * product differences rather than test-authoring choices:
 *  - GridScreen's "Sounds" flow ([GridScreenSoundsPickerTest]) only ever
 *    assigns a sample to a track's `assignedSampleIds` -- unlike
 *    ArrangementScreen's "add from library", it never auto-places a block.
 *    A grid cell must be tapped afterward ([GridScreenInteractionTest]) to
 *    actually place a note.
 *  - Track selection/focus is `track_pill_$slot`, not `track_header_$slot`,
 *    and opening the sample picker is the dedicated `grid_sounds_button`
 *    rather than tapping the track itself.
 *
 * Everything downstream of "a note is placed and Play is tapped" -- the real
 * engine, [BeatWavePlaybackService] background survival, lock-screen media-
 * button control, `dumpsys media_session`/`dumpsys notification` proof -- is
 * wired at the ViewModel/PlaybackEngine layer (see
 * [com.beatwave.android.audio.PlaybackEngine.start]'s own `startService`
 * call), entirely independent of which Activity/Compose screen is hosting
 * the UI -- so hosting [GridScreen] directly via
 * `createAndroidComposeRule<ComponentActivity>()` (the same pattern every
 * other Grid*Test in this suite already uses) exercises the exact same real
 * background-service/notification/media-session mechanics
 * [FullIntegrationWalkthroughTest] proves via `ActivityScenario.launch
 * (MainActivity::class.java)`.
 *
 * NOT yet verified on real hardware as of authoring (both project test
 * devices were occupied by an unrelated foreground app at the time) --
 * compiled and structurally reviewed against the established tag/flow
 * contracts of [GridScreenSoundsPickerTest], [GridScreenInteractionTest],
 * [ImportedSampleArrangementTest], and [FullIntegrationWalkthroughTest]
 * itself, but its correctness is not yet settled the way this project's
 * other tests are before being relied on.
 */
@RunWith(AndroidJUnit4::class)
class GridScreenFullIntegrationWalkthroughTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetToFreshProject() {
        ProjectRepository.forContext(context).delete(PROJECT_ID)
        // Same rationale as FullIntegrationWalkthroughTest's own @Before:
        // a distinct-UUID'd library entry from a past run of this test's
        // import step otherwise piles up in ImportedSampleIndex's persisted
        // storage across runs.
        importedSamplesDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        shell("input keyevent KEYCODE_HOME")
        (context.applicationContext as BeatWaveApplication).playbackEngine.stop()
        Thread.sleep(TEARDOWN_SETTLE_MS)
        importedSamplesDir().deleteRecursively()
    }

    @Test
    fun buildFromBundledAndImportedLoops_throughGridScreen_thenPlayAndBackground_bothSurviveTogether() {
        composeTestRule.setContent { GridScreen() }
        composeTestRule.waitUntilSafely(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_empty_track_prompt").fetchSemanticsNodes().isNotEmpty()
        }

        var viewModel: ArrangementViewModel? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }

        // --- (1) Bundled kick onto Track 1 (default-focused) via Sounds,
        // then placed by tapping a drum cell -- same two-step contract
        // GridScreenSoundsPickerTest's unassigned-track case establishes. ---
        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("remove_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_drum_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        val kickCell = "grid_cell_0_$KICK_SAMPLE_ID"
        composeTestRule.onNodeWithTag(kickCell).performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            nodeReportsFilled(kickCell)
        }

        // --- (2) Imported vocal onto Track 2 -- same "skip only the system
        // picker tap" shortcut ImportedSampleArrangementTest/
        // FullIntegrationWalkthroughTest establish, driven via the real
        // ViewModel/CategoryPickerDialog/library-merge pipeline from there
        // on, then assigned via Sounds and placed by tapping a melodic
        // cell. ---
        composeTestRule.onNodeWithTag("track_pill_2").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_empty_track_prompt").fetchSemanticsNodes().isNotEmpty()
        }

        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, IMPORTED_FIXTURE_NAME)
        viewModel!!.importAudioFromUri(Uri.fromFile(fixtureFile))
        composeTestRule.waitUntilSafely(timeoutMillis = IMPORT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_option_VOCAL").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(DIALOG_FOCUS_SETTLE_MS)
        composeTestRule.onNodeWithTag("category_option_VOCAL").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("category_confirm_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.sampleList.any { it.name == IMPORTED_FIXTURE_NAME }
        }
        val importedSample = viewModel!!.uiState.value.sampleList.first { it.name == IMPORTED_FIXTURE_NAME }

        composeTestRule.onNodeWithTag("grid_sounds_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_${importedSample.id}").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(DIALOG_FOCUS_SETTLE_MS)
        // IMPORTED_FIXTURE_NAME is deliberately "AAA_"-prefixed (sorts
        // before every bundled VOCAL card) -- same reasoning as
        // FullIntegrationWalkthroughTest's own fixture name. performScrollTo
        // is still applied defensively, matching every other add_loop tap
        // site in this suite.
        composeTestRule.onNodeWithTag("add_loop_${importedSample.id}").performScrollTo().performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("remove_loop_${importedSample.id}").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("grid_melodic_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        val importedCell = "grid_cell_0_0"
        composeTestRule.onNodeWithTag(importedCell).performClick()
        composeTestRule.waitUntilSafely(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            nodeReportsFilled(importedCell)
        }

        // Both blocks -- bundled and imported -- must coexist on the SAME
        // project, same as FullIntegrationWalkthroughTest's own assertion.
        val project = viewModel!!.uiState.value.project!!
        assertTrue("expected a block on Track 1 (bundled)", project.tracks.first { it.slot == 1 }.loopBlocks.isNotEmpty())
        assertTrue("expected a block on Track 2 (imported)", project.tracks.first { it.slot == 2 }.loopBlocks.isNotEmpty())

        // Snackbar settle, same as the original test's own comment.
        Thread.sleep(SNACKBAR_CLEAR_TIMEOUT_MS)

        // --- (3) Play the combined arrangement -- real engine proof. ---
        val frameBeforePlay = AudioEngineBridge.getCurrentFrame()
        assertEquals("expected transport to start at frame 0", 0L, frameBeforePlay)
        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameAfterPlay = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to advance after Play, got $frameAfterPlay",
            frameAfterPlay > 0L
        )

        // --- (4) Background it, and confirm both survival AND lock-screen/
        // notification session state, mirroring
        // FullIntegrationWalkthroughTest/BackgroundPlaybackServiceTest. ---
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(BACKGROUND_SETTLE_MS)
        val frameWhileBackgrounded = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the transport to keep advancing while backgrounded ($frameAfterPlay -> $frameWhileBackgrounded)",
            frameWhileBackgrounded > frameAfterPlay
        )
        val sessionDump = shell("dumpsys media_session")
        assertTrue(
            "expected dumpsys media_session to mention $PACKAGE_NAME while backgrounded and playing",
            sessionDump.contains(PACKAGE_NAME)
        )
        val notificationDump = shell("dumpsys notification --noredact")
        assertTrue(
            "expected a notification posted for $PACKAGE_NAME while backgrounded and playing",
            notificationDump.contains("pkg=$PACKAGE_NAME")
        )

        // Real lock-screen/hardware media-button control while backgrounded.
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        Thread.sleep(PAUSE_SETTLE_MS)
        val frameAtPause = AudioEngineBridge.getCurrentFrame()
        Thread.sleep(PAUSED_INTERVAL_MS)
        val frameAfterPausedInterval = AudioEngineBridge.getCurrentFrame()
        // Same evidence-based tolerance as FullIntegrationWalkthroughTest's
        // own assertion (project memory finding #30) -- a real, measured
        // hardware settle delay after a media-button pause on some devices,
        // not app-code jitter.
        val frameDriftAfterPause = Math.abs(frameAfterPausedInterval - frameAtPause)
        assertTrue(
            "expected the transport to stay frozen (within a $PAUSE_FREEZE_TOLERANCE_FRAMES-frame " +
                "tolerance) after a real media-button pause while backgrounded, but drifted by " +
                "$frameDriftAfterPause frames ($frameAtPause -> $frameAfterPausedInterval)",
            frameDriftAfterPause <= PAUSE_FREEZE_TOLERANCE_FRAMES
        )
    }

    /** Same helper contract as GridScreenInteractionTest's own. */
    private fun nodeReportsFilled(testTag: String): Boolean {
        return runCatching {
            composeTestRule.onNodeWithTag(testTag).assertContentDescriptionContains("Filled", substring = true)
            true
        }.getOrDefault(false)
    }

    private fun importedSamplesDir(): File =
        File(context.filesDir, com.beatwave.android.data.library.AudioImporter.IMPORTED_SAMPLES_DIR_NAME)

    private fun copyAssetToCache(assetPath: String, destFileName: String): File {
        val destFile = File(context.cacheDir, destFileName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        return destFile
    }

    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val PACKAGE_NAME = "com.beatwave.android"

        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val FIXTURE_ASSET_PATH = "loops/vocal_ah_01.wav" // VOCAL, reused as the "imported" fixture
        private const val IMPORTED_FIXTURE_NAME = "AAA_Grid_Imported_Vocal.wav"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
        private const val IMPORT_TIMEOUT_MS = 10_000L

        private const val PLAY_SETTLE_MS = 700L
        private const val BACKGROUND_SETTLE_MS = 2_000L
        private const val PAUSE_SETTLE_MS = 300L
        private const val PAUSED_INTERVAL_MS = 400L
        private const val PAUSE_FREEZE_TOLERANCE_FRAMES = 500L

        private const val DIALOG_FOCUS_SETTLE_MS = 300L
        private const val TEARDOWN_SETTLE_MS = 300L
        private const val SNACKBAR_CLEAR_TIMEOUT_MS = 6_000L
    }
}
