package com.beatwave.android.ui.arrangement

import android.content.Context
import android.net.Uri
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
import com.beatwave.android.data.library.AudioImporter
import com.beatwave.android.data.library.ImportedSampleIndex
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Phase 4 exit criterion: "an imported file
 * behaves identically to a bundled loop in the arrangement UI".
 *
 * Per the implementation plan's testing-boundary note, automating taps into
 * Android's SYSTEM document-picker UI (a different app/process entirely) is
 * out of scope and not attempted anywhere below. Instead, every test here
 * drives [AudioImporter] directly with a [Uri] pointing at a real audio
 * fixture -- a copy of one of the bundled Phase 1 loop WAVs, placed under
 * this app's own cache dir and referenced via a plain `file://` Uri, which
 * MediaExtractor/MediaCodec can decode exactly as they would any
 * SAF-picked content Uri -- i.e. this skips ONLY the literal system-picker
 * tap; everything downstream of "a Uri was picked" runs for real.
 *
 * [importPipeline_decodesWritesPersistsAndLoadsThroughNativeFilePath] is a
 * fast, non-UI test that exercises AudioImporter's decode, the WAV writer,
 * ImportedSampleIndex persistence, and the native filesystem-path decode
 * extension (SampleBank/WavDecoder) in isolation, via the same offline-engine
 * diagnostic natives (nativeTest-prefixed) [MixEngineDriftTest] uses.
 *
 * [importedSample_placedAndPlayedThroughRealUi_behavesLikeABundledLoop]
 * then proves the full, real, end-to-end path: real category-prompt dialog,
 * real library merge, real select-track-then-add-from-library UI flow (same
 * pattern as [ArrangementScreenPlaybackTest]), and real engine playback --
 * the same style of proof (an independent AudioEngineBridge.getCurrentFrame()
 * read after tapping Play) [ArrangementScreenPlaybackTest] already
 * established for bundled loops.
 */
@RunWith(AndroidJUnit4::class)
class ImportedSampleArrangementTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetState() {
        ProjectRepository.forContext(context).delete(PROJECT_ID)
        importedSamplesDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        // Mirrors ArrangementScreenPlaybackTest's teardown: onCleared()'s
        // AudioEngineBridge.stopEngine() runs on its own short-lived scope,
        // give it a moment to actually close the native stream before the
        // next test tries to open a new one.
        Thread.sleep(TEARDOWN_SETTLE_MS)
        importedSamplesDir().deleteRecursively()
    }

    // --- Test 1: import pipeline in isolation (no UI) ---

    @Test
    fun importPipeline_decodesWritesPersistsAndLoadsThroughNativeFilePath() {
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, "pipeline_fixture.wav")
        val fixtureUri = Uri.fromFile(fixtureFile)

        // --- (1) AudioImporter's real decode + WAV writer ---
        val importResult = runBlocking { AudioImporter(context).import(fixtureUri) }
        assertTrue(
            "expected AudioImporter.import to succeed, got ${importResult.exceptionOrNull()}",
            importResult.isSuccess
        )
        val imported = importResult.getOrThrow()

        assertTrue(
            "expected the decoded WAV to exist at an absolute filesystem path",
            imported.file.exists() && imported.file.absolutePath.startsWith("/")
        )
        assertTrue(
            "expected a positive real decoded duration, got ${imported.durationMs}",
            imported.durationMs > 0
        )
        // The bundled fixture (see manifest.json) is ~2667ms; allow codec
        // priming/padding slack rather than asserting an exact frame count.
        assertTrue(
            "expected decoded duration (${imported.durationMs}ms) to be close to the " +
                "source's manifest duration (${FIXTURE_MANIFEST_DURATION_MS}ms)",
            Math.abs(imported.durationMs - FIXTURE_MANIFEST_DURATION_MS) < DURATION_TOLERANCE_MS
        )

        val header = imported.file.readBytes()
        assertTrue("expected a valid RIFF/WAVE header", header.size >= 44)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        val audioFormat = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
        assertEquals("expected PCM format (1)", 1, audioFormat)
        val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        assertEquals("expected 16-bit PCM output regardless of source bit depth", 16, bitsPerSample)
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))

        // --- (2) ImportedSampleIndex persistence (round-trip through disk) ---
        val index = ImportedSampleIndex.forContext(context)
        val sample = Sample(
            id = UUID.randomUUID().toString(),
            name = imported.displayName,
            category = SampleCategory.VOCAL,
            source = SampleSource.ImportedFile(uri = imported.file.absolutePath),
            durationMs = imported.durationMs
        )
        index.add(sample)
        val reloaded = index.load()
        val persisted = reloaded.firstOrNull { it.id == sample.id }
        assertNotNull("expected the imported sample to round-trip through ImportedSampleIndex", persisted)
        assertEquals(sample.durationMs, persisted!!.durationMs)
        val persistedSource = persisted.source as? SampleSource.ImportedFile
        assertNotNull("expected the persisted source to still be ImportedFile", persistedSource)
        assertEquals(imported.file.absolutePath, persistedSource!!.uri)
        assertTrue(
            "expected the persisted path to follow the leading-'/' filesystem-path convention",
            persistedSource.uri.startsWith("/")
        )

        // --- (3) The native filesystem-path decode extension itself:
        // SampleBank::getOrLoad dispatching to WavDecoder::decodeFile for a
        // leading-'/' path, via the same nativeTest* offline-engine
        // diagnostics MixEngineDriftTest uses for bundled asset paths. ---
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, OFFLINE_SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, OFFLINE_BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)
            val scheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                handle, 1, imported.file.absolutePath,
                /* startGridUnit = */ 0,
                /* lengthGridUnits = */ OFFLINE_BLOCK_LENGTH_GRID_UNITS,
                /* volume = */ 1.0f,
                /* trimStartMs = */ 0L,
                /* trimEndMs = */ -1L,
                /* pitchSemitones = */ 0.0f
            )
            assertTrue(
                "expected the native engine to decode+schedule the imported WAV via its " +
                    "absolute filesystem path (SampleBank/WavDecoder's filesystem-path extension)",
                scheduled
            )
            AudioEngineBridge.nativeTestCommitProject(handle)
            val contentLengthFrames = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 1, 0)
            assertTrue(
                "expected a resolved positive loop content length after loading the imported " +
                    "sample natively, got $contentLengthFrames",
                contentLengthFrames > 0
            )
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    // --- Test 2: full real UI flow (category dialog, library merge, placement, playback) ---

    @Test
    fun importedSample_placedAndPlayedThroughRealUi_behavesLikeABundledLoop() {
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, UI_FIXTURE_FILE_NAME)
        val fixtureUri = Uri.fromFile(fixtureFile)

        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        // --- Skip ONLY the system-picker tap: drive the real ViewModel
        // method a real "Import from device" tap would have triggered,
        // with a Uri standing in for the one the system picker would have
        // returned. Everything downstream (AudioImporter.import,
        // pendingImport state, CategoryPickerDialog, confirmPendingImport,
        // the library merge) is the exact same production code path. ---
        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
            viewModel!!.importAudioFromUri(fixtureUri)
        }

        // --- Real category-prompt dialog (design item 4). The dialog is a
        // genuinely separate platform Window (androidx.compose.material3.
        // AlertDialog), so a short settle wait after its content first
        // appears in the semantics tree (composition done) gives the OS
        // time to actually transfer input focus to that new window before
        // the first tap -- without it, performClick() can race ahead of the
        // focus transfer and fail to inject touch input. ---
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_option_VOCAL").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(DIALOG_WINDOW_FOCUS_SETTLE_MS)
        // VOCAL is the last of 4 rows inside CategoryPickerDialog's
        // height-capped, scrollable options Column -- it may not be within
        // the initially-visible viewport, so scroll it into view first
        // (performScrollTo) before tapping; otherwise the tap coordinate
        // can resolve outside the actual scrolled position and miss the row.
        composeTestRule.onNodeWithTag("category_option_VOCAL").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("category_confirm_button").performClick()

        // --- Real library merge (design item 6): wait for the imported
        // sample to land in the ONE combined uiState.sampleList bundled
        // samples already flow through. ---
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.sampleList.any { it.name == UI_FIXTURE_FILE_NAME }
        }
        val importedSample = viewModel!!.uiState.value.sampleList.first { it.name == UI_FIXTURE_FILE_NAME }
        assertEquals(SampleCategory.VOCAL, importedSample.category)
        val source = importedSample.source as? SampleSource.ImportedFile
        assertNotNull("expected the merged sample's source to be ImportedFile", source)
        assertTrue(
            "expected the merged sample's path to be an absolute filesystem path",
            source!!.uri.startsWith("/")
        )

        // --- Real select-track-then-add-from-library UI flow (design item
        // 6/7), the exact same testTag-driven flow ArrangementScreenPlaybackTest
        // uses for bundled samples -- no special-casing for the fact this
        // sample came from an import. ---
        // The track list is a LazyColumn: on this device's short screen,
        // only the first few track rows are composed at all (uncomposed
        // lazy items can't be resolved by tag, so performScrollTo can't
        // reach them either) -- TARGET_TRACK_SLOT is deliberately low
        // enough to already be within that initial visible range (mirrors
        // ArrangementScreenPlaybackTest's use of track_header_1/2, already
        // proven to work on this device).
        composeTestRule.onNodeWithTag("track_header_$TARGET_TRACK_SLOT").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        // The library list is a LazyColumn -- with 8 bundled + 1 imported
        // sample it may not fully fit this device's short screen, and an
        // uncomposed (off-screen) lazy item can't be found by tag at all
        // (unlike CategoryPickerDialog's plain, always-fully-composed
        // Column). Filter to VOCAL first so only 2 cards need to be
        // composed/visible -- the bundled "Vocal Ah" and this imported
        // sample -- guaranteeing "Add" is reachable without lazy-list
        // scrolling.
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("category_filter_VOCAL").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("category_filter_VOCAL").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_${importedSample.id}").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_${importedSample.id}").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()

        val placedBlockTag = "loop_block_${TARGET_TRACK_SLOT}_${importedSample.id}"
        val blockAppeared = runCatching {
            composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithTag(placedBlockTag).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!blockAppeared) {
            val diag = viewModel!!.uiState.value
            val track = diag.project?.tracks?.firstOrNull { it.slot == TARGET_TRACK_SLOT }
            throw AssertionError(
                "expected the imported sample's block to be visible on Track $TARGET_TRACK_SLOT's " +
                    "timeline row within ${LIBRARY_TIMEOUT_MS}ms, but it never appeared. Diagnostics: " +
                    "selectedTrackSlot=${diag.selectedTrackSlot}, message=${diag.message}, " +
                    "track$TARGET_TRACK_SLOT.loopBlocks=${track?.loopBlocks}, " +
                    "sampleList.ids=${diag.sampleList.map { it.id }}"
            )
        }
        composeTestRule.onNodeWithTag(placedBlockTag).assertExists(
            "expected the imported sample's block to be visible on Track $TARGET_TRACK_SLOT's timeline row"
        )

        // --- Real playback: prove the real, live, singleton native engine
        // (not a UI-only counter) genuinely advances once this
        // imported-and-placed block is played -- same style of proof
        // ArrangementScreenPlaybackTest already established for a bundled
        // loop. ---
        val frameBeforePlay = AudioEngineBridge.getCurrentFrame()
        assertEquals("expected transport to start at frame 0", 0L, frameBeforePlay)

        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameWhilePlaying = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to have advanced past 0 after tapping " +
                "Play with an imported-and-placed block, got $frameWhilePlaying",
            frameWhilePlaying > 0L
        )

        composeTestRule.onNodeWithTag("stop_button").performClick()
        Thread.sleep(STOP_SETTLE_MS)
    }

    // --- Helpers ---

    private fun importedSamplesDir(): File = File(context.filesDir, AudioImporter.IMPORTED_SAMPLES_DIR_NAME)

    private fun copyAssetToCache(assetPath: String, destFileName: String): File {
        val destFile = File(context.cacheDir, destFileName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    companion object {
        private const val PROJECT_ID = "current"

        // From the bundled Phase 1 loop pack (app/src/main/assets/loops/ +
        // manifest.json): 44.1kHz mono 16-bit PCM, manifest durationMs=2667.
        private const val FIXTURE_ASSET_PATH = "loops/vocal_ah_01.wav"
        private const val FIXTURE_MANIFEST_DURATION_MS = 2667L
        private const val DURATION_TOLERANCE_MS = 250L
        private const val UI_FIXTURE_FILE_NAME = "phase4_ui_fixture.wav"

        // Deliberately low: on this device's short screen a LazyColumn only
        // composes the first few track rows, and uncomposed rows can't be
        // scrolled to by tag (see the track-selection comment above).
        private const val TARGET_TRACK_SLOT = 1

        private const val OFFLINE_SAMPLE_RATE_HZ = 48000
        private const val OFFLINE_BPM = 90
        private const val OFFLINE_BLOCK_LENGTH_GRID_UNITS = 64

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val IMPORT_TIMEOUT_MS = 10_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L

        private const val PLAY_SETTLE_MS = 700L
        private const val STOP_SETTLE_MS = 150L
        private const val TEARDOWN_SETTLE_MS = 200L
        private const val DIALOG_WINDOW_FOCUS_SETTLE_MS = 300L
    }
}
