package com.beatwave.android.ui.arrangement

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.MainActivity
import com.beatwave.android.data.library.AudioImporter
import com.beatwave.android.data.storage.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the post-v1 audits/upgrades backlog item A1
 * (import size/DoS hardening): [BeatWaveApplication.importLeaseClaimedAtMs]
 * genuinely serializes concurrent [ArrangementViewModel.importAudioFromUri]
 * calls, against the real production ViewModel/guard, not a
 * reimplementation -- mirrors [ImportedSampleArrangementTest]'s "skip only
 * the system-picker tap, drive the real ViewModel method" pattern.
 *
 * Both [ArrangementViewModel.importAudioFromUri] calls in the first test
 * below happen inside ONE [ActivityScenario.onActivity] block, i.e.
 * synchronously on the same (main) thread, back to back.
 * [ArrangementViewModel.importAudioFromUri]'s `importLeaseClaimedAtMs.get()`+
 * `compareAndSet` runs synchronously, BEFORE `viewModelScope.launch` ever
 * schedules the decode coroutine -- so the second call is GUARANTEED to
 * observe the claim the first call already made, regardless of how fast the
 * underlying decode actually runs. This is a deterministic exercise of
 * "second call while first is in flight," not a timing-dependent race,
 * matching this codebase's low tolerance for flaky instrumented tests.
 *
 * The second test proves the fix for a real bug this audit's own
 * adversarial-review pass found: a plain (non-expiring) guard would stay
 * wedged "in progress" forever if a decode's underlying native call never
 * returns (AudioImporter's MediaExtractor/MediaCodec calls aren't reliably
 * interruptible -- see [AudioImporter]'s own class doc comment), permanently
 * blocking every future import app-wide. [BeatWaveApplication.importLeaseClaimedAtMs]
 * is a SELF-EXPIRING lease specifically to avoid that -- this test directly
 * plants a stale claim (bypassing importAudioFromUri entirely, simulating
 * "the coroutine that claimed this never got to release it") and confirms a
 * later call successfully reclaims it rather than being rejected forever.
 */
@RunWith(AndroidJUnit4::class)
class ImportConcurrencyGuardTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetState() {
        ProjectRepository.forContext(context).delete(PROJECT_ID)
        importedSamplesDir().deleteRecursively()
        // Defensive: a prior test/run that crashed mid-import (or was
        // killed) could conceivably leave this app-wide lease stuck
        // claimed, which would make EVERY import in this test spuriously
        // rejected.
        applicationImportLease().set(0L)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        Thread.sleep(TEARDOWN_SETTLE_MS)
        importedSamplesDir().deleteRecursively()
        // Same defensive reset on the way out, so a failure partway through
        // this test can't leak a claimed lease into whatever instrumented
        // test class runs next in this same am instrument process.
        applicationImportLease().set(0L)
    }

    @Test
    fun importAudioFromUri_secondCallWhileFirstInFlight_isRejectedButFirstStillSucceeds() {
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, "concurrency_fixture.wav")
        val fixtureUri = Uri.fromFile(fixtureFile)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
            viewModel!!.importAudioFromUri(fixtureUri)
            viewModel!!.importAudioFromUri(fixtureUri)
        }

        assertEquals(
            "expected the second, concurrent import call to be rejected with a clear message",
            "Another import is already in progress -- please wait for it to finish.",
            viewModel!!.uiState.value.message
        )

        // The guard must reject the SECOND call only -- the first one is
        // still genuinely running and must complete normally.
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.pendingImport != null
        }
        assertNotNull(
            "expected the first (non-concurrent) import to still succeed normally",
            viewModel!!.uiState.value.pendingImport
        )

        // The guard must release once the in-flight import completes, so a
        // THIRD, genuinely-sequential call isn't permanently locked out.
        viewModel!!.cancelPendingImport()
        viewModel!!.importAudioFromUri(fixtureUri)
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.pendingImport != null
        }
        assertNotNull(
            "expected a later, non-concurrent import to succeed once the guard was released",
            viewModel!!.uiState.value.pendingImport
        )
    }

    @Test
    fun importAudioFromUri_reclaimsAnExpiredLease_ratherThanStayingLockedForever() {
        // Plants a stale claim directly, simulating "an earlier import's
        // coroutine claimed this lease and then its underlying decode call
        // never returned, so its own finally-block release never ran" --
        // without actually needing to construct a real hung decode.
        val staleClaimedAt = System.currentTimeMillis() - (IMPORT_LEASE_MAX_MS_MIRROR + 5_000L)
        applicationImportLease().set(staleClaimedAt)

        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, "expired_lease_fixture.wav")
        val fixtureUri = Uri.fromFile(fixtureFile)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
            viewModel!!.importAudioFromUri(fixtureUri)
        }

        // Must NOT be rejected -- the planted claim is older than the
        // lease's max duration, so this call must have reclaimed it rather
        // than treating it as still "in progress" forever.
        composeTestRule.waitUntil(timeoutMillis = IMPORT_TIMEOUT_MS) {
            viewModel!!.uiState.value.pendingImport != null
        }
        assertNotNull(
            "expected a call against an EXPIRED lease to succeed (reclaim it), not stay rejected forever",
            viewModel!!.uiState.value.pendingImport
        )
    }

    private fun applicationImportLease() =
        (context.applicationContext as BeatWaveApplication).importLeaseClaimedAtMs

    private fun importedSamplesDir(): File = File(context.filesDir, AudioImporter.IMPORTED_SAMPLES_DIR_NAME)

    private fun copyAssetToCache(assetPath: String, destFileName: String): File {
        val destFile = File(context.cacheDir, destFileName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        return destFile
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val FIXTURE_ASSET_PATH = "loops/vocal_ah_01.wav"

        // Mirrors ArrangementViewModel's own private IMPORT_LEASE_MAX_MS --
        // duplicated here (rather than exposed just for testing) since a
        // test asserting against the real production contract should
        // independently encode what it expects, matching this codebase's
        // existing ExportShareTest precedent for the same reasoning.
        private const val IMPORT_LEASE_MAX_MS_MIRROR = 90_000L

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val IMPORT_TIMEOUT_MS = 10_000L
        private const val TEARDOWN_SETTLE_MS = 200L
    }
}
