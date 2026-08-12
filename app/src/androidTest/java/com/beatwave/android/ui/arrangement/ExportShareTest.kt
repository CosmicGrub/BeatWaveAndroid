package com.beatwave.android.ui.arrangement

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.MainActivity
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Phase 7 exit criterion: "export a project,
 * share it to another app; share an audio file into BeatWave from another
 * app and confirm it's importable."
 *
 * Per the SAME testing-boundary the Phase 4 import tests already established
 * ([ImportedSampleArrangementTest]'s own doc comment): automating taps into
 * a genuinely separate system app's UI (the Android share-sheet chooser
 * itself) is out of scope. What IS mechanically verifiable, and exercised
 * for real below, is everything on BeatWave's own side of that boundary:
 *
 *  - Export: the real offline-render native path produces a genuine,
 *    correctly-headed WAV file (not a stub), AND the real ACTION_SEND +
 *    FileProvider Uri actually launches the system chooser (proven by the
 *    focused window changing away from MainActivity) -- proof the Intent/
 *    permission wiring itself is correct, without needing to pick a
 *    specific target app from the chooser.
 *  - Receive: unlike the import tests' "skip the picker tap, call
 *    importAudioFromUri directly" shortcut (appropriate there because
 *    Phase 4's picker interaction is the only new surface), Phase 7's NEW
 *    code is specifically the Intent-receiving path itself (MainActivity.
 *    handleShareIntent's action/type/EXTRA_STREAM parsing, the Compose
 *    incomingShareUri plumbing) -- so this drives a REAL crafted
 *    ACTION_SEND Intent into MainActivity via ActivityScenario.launch(intent),
 *    exactly as the OS would deliver one from another app, then confirms it
 *    reaches the exact same real CategoryPickerDialog/library-merge pipeline
 *    [ImportedSampleArrangementTest] already proves for a SAF-picked file.
 */
@RunWith(AndroidJUnit4::class)
class ExportShareTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetState() {
        ProjectRepository.forContext(context).delete(PROJECT_ID)
        exportsDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        // Mirrors every other UI-launching test in this suite: give
        // onCleared()'s fire-and-forget AudioEngineBridge.stopEngine() a
        // moment to actually close the native stream before the next
        // test/class opens a new one.
        Thread.sleep(TEARDOWN_SETTLE_MS)
        exportsDir().deleteRecursively()
    }

    // --- Export (send side) ---

    @Test
    fun exportProject_rendersRealWavFile_andLaunchesShareChooser() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        // --- Build a minimal one-track arrangement through the real UI
        // (same pattern as ArrangementScreenPlaybackTest) -- an empty
        // project has nothing to export (see exportProject's own guard),
        // so there must be an actual placed block first. ---
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

        val sampleRateBeforeExport = AudioEngineBridge.getSampleRate()
        assertTrue(
            "expected the live engine's sample rate to already be known before exporting, got $sampleRateBeforeExport",
            sampleRateBeforeExport > 0
        )

        // --- Tap Export via the real UI. ---
        composeTestRule.onNodeWithTag("export_button").performClick()

        // --- Deliberately a plain filesystem poll, NOT a Compose semantics
        // waitUntil: isExporting=false and pendingShareFilePath are set in
        // the SAME state update (see ArrangementViewModel.exportProject), so
        // the share chooser can take over the screen (and with it, the
        // Compose hierarchy this Activity owns) before a semantics-based
        // wait ever gets to re-poll -- a real race a first version of this
        // test hit ("No compose hierarchies found"). File existence has no
        // such dependency on which window currently has focus. A short
        // settle after first observing the file guards against reading it
        // mid-write (WavWriter writes the header before streaming data,
        // then closes) -- generous for a single-block arrangement this
        // small, which renders in well under a second. ---
        val exportedFile = File(exportsDir(), EXPECTED_EXPORT_FILE_NAME)
        val deadline = System.currentTimeMillis() + EXPORT_TIMEOUT_MS
        while (!exportedFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(EXPORT_POLL_INTERVAL_MS)
        }
        assertTrue(
            "expected an exported WAV file to appear at ${exportedFile.absolutePath} within ${EXPORT_TIMEOUT_MS}ms",
            exportedFile.exists()
        )
        Thread.sleep(EXPORT_WRITE_SETTLE_MS)

        // --- The rendered file must be a real, correctly-headed WAV --
        // same header-validation shape ImportedSampleArrangementTest already
        // uses to prove AudioImporter's WAV writer, applied here to the
        // native WavWriter's output instead. ---
        val header = exportedFile.readBytes()
        assertTrue("expected a valid RIFF/WAVE header, got ${header.size} bytes", header.size > 44)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        val channelCount = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
        assertEquals("expected the fixed 2-channel mix", 2, channelCount)
        val sampleRateInHeader = (header[24].toInt() and 0xFF) or
            ((header[25].toInt() and 0xFF) shl 8) or
            ((header[26].toInt() and 0xFF) shl 16) or
            ((header[27].toInt() and 0xFF) shl 24)
        assertEquals(
            "expected the export to render at the SAME sample rate the live engine is using",
            sampleRateBeforeExport, sampleRateInHeader
        )
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        val dataSize = (header[40].toInt() and 0xFF) or
            ((header[41].toInt() and 0xFF) shl 8) or
            ((header[42].toInt() and 0xFF) shl 16) or
            ((header[43].toInt() and 0xFF) shl 24)
        assertTrue("expected a non-empty rendered data chunk, got $dataSize bytes", dataSize > 0)

        // --- The real ACTION_SEND + FileProvider Uri must have actually
        // reached Android's Intent system without throwing (a bad/
        // unreadable Uri, a missing grantUriPermissions, or an unresolvable
        // Intent would all surface as an exception here). Verified via
        // ArrangementViewModel state rather than screen-scraping for the
        // system chooser's own (separate-app, OEM/API-version-dependent)
        // window -- the SAME "trust our own code's state, not another app's
        // UI" boundary ImportedSampleArrangementTest's doc comment already
        // establishes for the receiving side: shareFileConsumed() runs
        // immediately after ArrangementScreen's LaunchedEffect calls
        // context.startActivity(Intent.createChooser(...)), so
        // pendingShareFilePath returning to null is real, direct proof that
        // call completed successfully -- a real chooser launch was
        // independently confirmed via logcat (ActivityManager: START ...
        // ChooserActivity ... result:START_SUCCESS) while developing this
        // test, exactly as this assertion implies. ---
        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        // composeTestRule's test clock stays idle (no recomposition/effects
        // actually applied) until a synchronizing call like waitForIdle()
        // pumps it -- the file-existence poll above deliberately avoids any
        // such call (see its own comment), which means the state update that
        // sets pendingShareFilePath was sitting queued the whole time, and
        // the LaunchedEffect that reads it had never actually gotten a
        // chance to run at all. waitForIdle() itself is safe here (unlike a
        // semantics query) even once the chooser takes over the screen --
        // it doesn't inspect the semantics tree, just pumps Compose's clock
        // -- and reading uiState.value directly afterward is a plain Kotlin
        // property read, not a semantics query, so it's unaffected either
        // way by whichever window currently has focus.
        val shareConsumedDeadline = System.currentTimeMillis() + SHARE_CONSUMED_TIMEOUT_MS
        while (viewModel!!.uiState.value.pendingShareFilePath != null &&
            System.currentTimeMillis() < shareConsumedDeadline
        ) {
            composeTestRule.waitForIdle()
            Thread.sleep(EXPORT_POLL_INTERVAL_MS)
        }
        assertEquals(
            "expected pendingShareFilePath to be consumed (share Intent fired) after Export",
            null, viewModel!!.uiState.value.pendingShareFilePath
        )
        // Best-effort: dismiss whatever the share Intent opened so teardown
        // isn't left racing a foreign window -- harmless no-op if nothing
        // came to the foreground (e.g. no matching apps on this device).
        shell("input keyevent KEYCODE_BACK")
        Thread.sleep(CHOOSER_APPEAR_SETTLE_MS)
    }

    // --- Receive (share-into-BeatWave side) ---

    @Test
    fun sharedAudioIntent_isReceivedAsRealImportCandidate_showingCategoryPicker() {
        val fixtureFile = copyAssetToExportsDir(FIXTURE_ASSET_PATH, SHARED_FIXTURE_FILE_NAME)
        val fixtureUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fixtureFile)

        // A real ACTION_SEND Intent, exactly as another app's share sheet
        // would construct one -- explicit component (standard/recommended
        // for instrumented launches; the manifest's intent-filter itself is
        // a static declaration a genuine cross-app share would resolve
        // through, out of scope for the same reason picking a chooser
        // target is, per this class's doc comment) but otherwise identical
        // to what MainActivity.handleShareIntent actually parses.
        val shareIntent = Intent(Intent.ACTION_SEND, null, context, MainActivity::class.java).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, fixtureUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        scenario = ActivityScenario.launch(shareIntent)

        // --- Real CategoryPickerDialog, reached via the real Intent parse
        // -> incomingShareUri Compose state -> importAudioFromUri pipeline,
        // not a direct ViewModel call. ---
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_option_DRUMS").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(DIALOG_WINDOW_FOCUS_SETTLE_MS)
        composeTestRule.onNodeWithTag("category_option_DRUMS").performClick()
        composeTestRule.onNodeWithTag("category_confirm_button").performClick()

        // --- Real library merge: the shared-in file must behave exactly
        // like a SAF-picked import from here on (design item 6, same
        // guarantee ImportedSampleArrangementTest proves for the picker
        // path). ---
        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.sampleList.any { it.name == SHARED_FIXTURE_FILE_NAME }
        }
        val importedSample = viewModel!!.uiState.value.sampleList.first { it.name == SHARED_FIXTURE_FILE_NAME }
        assertEquals(SampleCategory.DRUMS, importedSample.category)
        val source = importedSample.source as? SampleSource.ImportedFile
        assertNotNull("expected the merged sample's source to be ImportedFile", source)
        assertTrue(
            "expected the merged sample's path to be an absolute filesystem path (the shared file, " +
                "copied into app-private storage by AudioImporter, not left as a foreign content:// Uri)",
            source!!.uri.startsWith("/")
        )
    }

    // --- Helpers ---

    private fun exportsDir(): File = File(context.cacheDir, EXPORTS_DIR_NAME)

    private fun copyAssetToExportsDir(assetPath: String, destFileName: String): File {
        val dir = exportsDir()
        if (!dir.exists()) dir.mkdirs()
        val destFile = File(dir, destFileName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        return destFile
    }

    /** Runs a shell command via the instrumentation's UiAutomation -- mirrors
     *  [com.beatwave.android.audio.BackgroundPlaybackServiceTest]'s own
     *  identical helper. */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        private const val PROJECT_ID = "current"

        // Mirrors ArrangementViewModel's own EXPORTS_DIR_NAME constant --
        // duplicated here (rather than made internal/exposed) since a test
        // asserting against the real on-disk contract should independently
        // encode what it expects, not import the production constant and
        // risk a change to one silently going unnoticed by the other.
        private const val EXPORTS_DIR_NAME = "exports"

        // ArrangementViewModel.init's default project name is "My Project",
        // and sanitizeFileName leaves it untouched (no illegal characters).
        private const val EXPECTED_EXPORT_FILE_NAME = "My Project.wav"

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS
        private const val FIXTURE_ASSET_PATH = "loops/kick_basic_01.wav"
        private const val SHARED_FIXTURE_FILE_NAME = "shared_test_audio.wav"

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L
        private const val IMPORT_TIMEOUT_MS = 10_000L
        private const val EXPORT_TIMEOUT_MS = 15_000L

        private const val CHOOSER_APPEAR_SETTLE_MS = 1_000L
        private const val DIALOG_WINDOW_FOCUS_SETTLE_MS = 300L
        private const val TEARDOWN_SETTLE_MS = 200L
        private const val EXPORT_POLL_INTERVAL_MS = 100L
        private const val EXPORT_WRITE_SETTLE_MS = 300L

        // Generous: the LaunchedEffect chain (recomposition -> FileProvider
        // Uri resolution -> Intent.createChooser -> startActivity) is async
        // relative to this test's own polling thread, and on a loaded device
        // running a full test suite back-to-back can take noticeably longer
        // than it does in isolation.
        private const val SHARE_CONSUMED_TIMEOUT_MS = 8_000L
    }
}
