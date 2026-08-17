package com.beatwave.android.data.library

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the post-v1 audits/upgrades backlog item A1 (import
 * size/DoS hardening): [AudioImporter.maxDecodedPcmBytes] actually rejects
 * an oversized import with [AudioImporter.ImportError.TooLarge], via the
 * REAL decode pipeline (MediaExtractor/MediaCodec against a real file),
 * exactly like [com.beatwave.android.ui.arrangement.ImportedSampleArrangementTest]'s
 * own "no UI" test 1 drives [AudioImporter] directly -- mirrors that file's
 * `Uri.fromFile` fixture pattern rather than introducing a new one.
 *
 * Uses an artificially tiny [AudioImporter.maxDecodedPcmBytes] (the
 * constructor param this audit added specifically to make this
 * deterministically testable without needing a multi-hundred-megabyte real
 * fixture) rather than [AudioImporter.DEFAULT_MAX_DECODED_PCM_BYTES] --
 * the bundled fixture's decoded PCM is on the order of a couple hundred KB,
 * so a 100-byte ceiling is exceeded regardless of exactly which of the two
 * enforcement mechanisms (the early ContentResolver size-hint pre-check, or
 * the running byte count inside the decode loop) catches it first for THIS
 * particular fixture/Uri combination -- both are already covered logically
 * by code review and the A1 adversarial-review pass; this test's job is to
 * prove SOME real enforcement genuinely fires end-to-end on real hardware,
 * not to isolate exactly which of the two paths does so for this input.
 */
@RunWith(AndroidJUnit4::class)
class AudioImporterSizeCapTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun import_exceedingTinyMaxDecodedPcmBytes_failsWithTooLarge() {
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, "size_cap_fixture.wav")
        val fixtureUri = Uri.fromFile(fixtureFile)
        val importer = AudioImporter(context, maxDecodedPcmBytes = TINY_CEILING_BYTES)

        val result = runBlocking { importer.import(fixtureUri) }

        assertTrue("expected import to fail against a $TINY_CEILING_BYTES-byte ceiling", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected an AudioImporter.ImportError.TooLarge, got $error",
            error is AudioImporter.ImportError.TooLarge
        )
    }

    @Test
    fun import_underADefaultSizedCeiling_stillSucceeds() {
        // Regression-safety companion to the test above: the same real
        // fixture, through the same real decode pipeline, but with the
        // production DEFAULT ceiling (no override) -- confirms this audit's
        // hardening didn't accidentally tighten enforcement for ordinary,
        // legitimate imports the way the tiny-ceiling test above
        // deliberately does.
        val fixtureFile = copyAssetToCache(FIXTURE_ASSET_PATH, "size_cap_control_fixture.wav")
        val fixtureUri = Uri.fromFile(fixtureFile)
        val importer = AudioImporter(context)

        val result = runBlocking { importer.import(fixtureUri) }

        assertTrue("expected import to succeed under the default ceiling, got ${result.exceptionOrNull()}", result.isSuccess)
    }

    private fun copyAssetToCache(assetPath: String, destFileName: String): File {
        val destFile = File(context.cacheDir, destFileName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }
        return destFile
    }

    companion object {
        // From the bundled Phase 1 loop pack: 44.1kHz mono 16-bit PCM,
        // ~2667ms -- decodes to roughly 235KB of PCM, comfortably over any
        // tiny ceiling and comfortably under the real 64MiB default.
        private const val FIXTURE_ASSET_PATH = "loops/vocal_ah_01.wav"
        private const val TINY_CEILING_BYTES = 100L
    }
}
