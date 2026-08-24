package com.beatwave.android.ui.arrangement

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.MainActivity
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
 * Phase 8 exit criterion: "a complete user flow -- build an arrangement from
 * bundled + imported + recorded loops, play it back, background it, control
 * it from lock screen, export and share it -- works without issues."
 *
 * Every prior phase's instrumented test proves its own feature in isolation
 * (its own fresh project, its own narrow flow). This test is different: ONE
 * continuous project, built from TWO loop sources (bundled + imported) in
 * the SAME arrangement, then carried through real playback and backgrounding
 * -- an integration combination no other test exercises, and exactly the
 * kind of gap a per-phase test suite can't catch by construction.
 *
 * The recorded-loop leg of the exit criterion, plus export/share on the
 * resulting combined project, is covered by a genuine hands-on MANUAL pass
 * instead of further automation here (see the Phase 8 session notes/commit
 * message for that pass's evidence): the Record button
 * (RecordAffordance's record_button_N/stop_record_button_N) reliably failed
 * to respond to any Compose test-framework click mechanism tried --
 * performClick(), performClick() after an explicit waitForIdle(), and a
 * real coordinate-based `adb shell input tap` at the exact center computed
 * from the button's own live semantics bounds -- specifically in this
 * test's combination of GrantPermissionRule + createEmptyComposeRule() +
 * real UI taps, a combination no other test in this suite exercises (every
 * other test either drives Compose UI with no GrantPermissionRule, or
 * drives RECORD_AUDIO-gated native calls directly with no Compose UI at
 * all). This was NOT a production bug: window focus was independently
 * confirmed on MainActivity throughout (dumpsys window windows), RECORD_AUDIO
 * was independently confirmed granted (dumpsys package), the semantics tree
 * showed a perfectly valid OnClick action at the expected position
 * (composeTestRule.onRoot().printToString()), and an identical manual `adb
 * shell input tap` at the very same coordinates -- run directly, outside
 * any test harness -- genuinely started a real recording (Track 3 showed
 * the live "● 0:00" recording indicator). Given production behavior was
 * independently proven correct and Phase 8's own plan entry is titled
 * "Polish & Full MANUAL Verification Pass", further automation attempts
 * here stopped being the better use of time than just doing that pass by
 * hand with the same rigor (screenshots, dumpsys, byte-level WAV checks)
 * every other verification in this project already uses.
 *
 * Same testing-boundary as every other UI test in this suite: real testTag
 * taps throughout, [AudioEngineBridge]/`dumpsys` reads only as independent
 * proof the UI's actions reached the real engine/OS -- see
 * [com.beatwave.android.audio.BackgroundPlaybackServiceTest] and
 * [ExportShareTest]'s own doc comments for the precedents this mirrors.
 */
@RunWith(AndroidJUnit4::class)
class FullIntegrationWalkthroughTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetToFreshProject() {
        ProjectRepository.forContext(context).delete(PROJECT_ID)
        // Mirrors ImportedSampleArrangementTest's own cleanup: without this,
        // a distinct-UUID'd library entry from every past run of THIS
        // test's import step (same display name, new id each time) piles up
        // in ImportedSampleIndex's persisted storage and, sorting ahead of
        // every bundled sample (see IMPORTED_FIXTURE_NAME's own comment),
        // eventually pushes "Basic Kick" off the first screen of the "All"
        // filter -- a real failure this test hit while under development,
        // purely from its own accumulated leftover state, not a production bug.
        importedSamplesDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        shell("input keyevent KEYCODE_HOME")
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        (targetContext.applicationContext as com.beatwave.android.BeatWaveApplication).playbackEngine.stop()
        if (::scenario.isInitialized) {
            scenario.close()
        }
        Thread.sleep(TEARDOWN_SETTLE_MS)
        importedSamplesDir().deleteRecursively()
    }

    @Test
    fun buildFromBundledAndImportedLoops_thenPlayAndBackground_bothSurviveTogether() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (1) Bundled loop onto Track 1 -- same flow as
        // ArrangementScreenPlaybackTest. ---
        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.ensureLoopLibraryOpen()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.ensureLoopLibraryClosed()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (2) Imported loop onto Track 2 -- same "skip only the system
        // picker tap" shortcut ImportedSampleArrangementTest establishes,
        // driven via the real ViewModel/CategoryPickerDialog/library-merge
        // pipeline from there on. ---
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, IMPORTED_FIXTURE_NAME)
        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
            viewModel!!.importAudioFromUri(Uri.fromFile(fixtureFile))
        }
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_option_VOCAL").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(DIALOG_FOCUS_SETTLE_MS)
        // VOCAL is the last of 4 rows inside CategoryPickerDialog's
        // height-capped, scrollable options Column -- must be scrolled into
        // view before tapping, exactly per ImportedSampleArrangementTest's
        // own note (skipping this silently leaves the dialog's default
        // selection, DRUMS, in effect instead -- discovered by this test
        // failing with the imported sample missing from the VOCAL filter,
        // then confirmed present under DRUMS instead).
        composeTestRule.onNodeWithTag("category_option_VOCAL").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("category_confirm_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.sampleList.any { it.name == IMPORTED_FIXTURE_NAME }
        }
        val importedSample = viewModel!!.uiState.value.sampleList.first { it.name == IMPORTED_FIXTURE_NAME }

        composeTestRule.onNodeWithTag("track_header_2").performClick()
        composeTestRule.ensureLoopLibraryOpen()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_filter_VOCAL").fetchSemanticsNodes().isNotEmpty()
        }
        // Device-adaptive layouts (2026-08-18 spec), Phase 0: the category
        // filter row no longer reliably fits all categories without
        // scrolling now that the Loop Library can render as a narrower
        // two-pane panel (~34% width) rather than always a full-width
        // sheet -- VOCAL, the last of 4 categories, can be scrolled off
        // the visible edge. performScrollTo() first mirrors the same
        // pattern CategoryPickerDialog's own category_option_VOCAL tap
        // already uses a few lines above, for the identical reason.
        composeTestRule.onNodeWithTag("category_filter_VOCAL").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_${importedSample.id}").fetchSemanticsNodes().isNotEmpty()
        }
        // IMPORTED_FIXTURE_NAME is deliberately chosen (leading "AAA_") to
        // sort (case-sensitive, see sampleList's sortedBy) BEFORE both
        // bundled VOCAL cards ("Vocal Ah"/"Vocal Oh"), landing as the 1st
        // card in this filtered list. A lowercase-leading name instead
        // sorts LAST, landing off-screen in this LazyColumn on this
        // device's short screen -- and, unlike CategoryPickerDialog's plain
        // Column.verticalScroll above, a LazyColumn item that isn't
        // composed at all yet can't be found by tag to scroll to in the
        // first place (performScrollTo() requires the node to already
        // exist), discovered the hard way while writing this test. Picking
        // a sort-first name sidesteps needing LazyColumn's index/key-based
        // scroll APIs entirely.
        composeTestRule.onNodeWithTag("add_loop_${importedSample.id}").performClick()
        composeTestRule.ensureLoopLibraryClosed()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_2_${importedSample.id}").fetchSemanticsNodes().isNotEmpty()
        }

        // Both blocks -- bundled and imported -- must coexist on the SAME
        // arrangement's timeline.
        val project = viewModel!!.uiState.value.project!!
        assertTrue("expected a block on Track 1 (bundled)", project.tracks.first { it.slot == 1 }.loopBlocks.isNotEmpty())
        assertTrue("expected a block on Track 2 (imported)", project.tracks.first { it.slot == 2 }.loopBlocks.isNotEmpty())

        // The "Imported ..." Snackbar (ArrangementViewModel.confirmPendingImport's
        // message) renders at the bottom of the screen and can still be
        // showing at this point -- a fixed settle comfortably longer than
        // Material3's SnackbarDuration.Short lets it clear before the next
        // real UI interaction.
        Thread.sleep(SNACKBAR_CLEAR_TIMEOUT_MS)

        // --- (3) Play the combined arrangement -- real engine proof, same
        // style every other phase's playback test already uses. ---
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
        // notification session state, mirroring BackgroundPlaybackServiceTest. ---
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
        assertEquals(
            "expected the transport to freeze after a real media-button pause while backgrounded",
            frameAtPause, AudioEngineBridge.getCurrentFrame()
        )
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

        // From the bundled Phase 1 loop pack manifest.
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val FIXTURE_ASSET_PATH = "loops/vocal_ah_01.wav" // VOCAL, reused as the "imported" fixture

        // Leading "AAA_" is deliberate -- sorts before the bundled VOCAL
        // cards ("Vocal Ah"/"Vocal Oh") so it's always the 1st (always
        // composed/visible) card in the VOCAL-filtered library list. See
        // the performClick() call site's comment for why this matters.
        private const val IMPORTED_FIXTURE_NAME = "AAA_Imported_Vocal.wav"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
        private const val IMPORT_TIMEOUT_MS = 10_000L

        private const val PLAY_SETTLE_MS = 700L
        private const val BACKGROUND_SETTLE_MS = 2_000L
        private const val PAUSE_SETTLE_MS = 300L
        private const val PAUSED_INTERVAL_MS = 400L
        private const val DIALOG_FOCUS_SETTLE_MS = 300L
        private const val TEARDOWN_SETTLE_MS = 300L

        // Comfortably past Material3's SnackbarDuration.Short default (~4s).
        private const val SNACKBAR_CLEAR_TIMEOUT_MS = 6_000L
    }
}
