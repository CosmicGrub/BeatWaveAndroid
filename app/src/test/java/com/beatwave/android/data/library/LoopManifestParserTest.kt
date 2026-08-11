package com.beatwave.android.data.library

import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [LoopManifestParser]: no Robolectric, no Android
 * framework classes. Reads the real bundled manifest straight off disk.
 */
class LoopManifestParserTest {

    // Gradle runs :app JVM unit tests with the module dir (app/) as the
    // working directory, so this resolves to the real bundled manifest.
    private val realManifestFile = File("src/main/assets/loops/manifest.json")

    @Test
    fun `parses the real bundled manifest into 8 samples`() {
        assertTrue("expected manifest at ${realManifestFile.absolutePath}", realManifestFile.exists())

        val samples = LoopManifestParser.parse(realManifestFile.readText())

        assertEquals(8, samples.size)
    }

    @Test
    fun `real manifest samples have expected ids and categories by count`() {
        val samples = LoopManifestParser.parse(realManifestFile.readText())
        val byId = samples.associateBy { it.id }

        assertEquals(2, samples.count { it.category == SampleCategory.DRUMS })
        assertEquals(2, samples.count { it.category == SampleCategory.BASS })
        assertEquals(2, samples.count { it.category == SampleCategory.SYNTH })
        assertEquals(2, samples.count { it.category == SampleCategory.VOCAL })

        val kick = byId.getValue("kick_basic_01")
        assertEquals("Basic Kick", kick.name)
        assertEquals(SampleCategory.DRUMS, kick.category)
        assertEquals(SampleSource.BundledAsset("loops/kick_basic_01.wav"), kick.source)
        assertTrue(kick.durationMs > 0)
    }

    @Test
    fun `every parsed sample has a bundled asset source pointing at an existing file`() {
        val samples = LoopManifestParser.parse(realManifestFile.readText())

        for (sample in samples) {
            val source = sample.source
            assertTrue("sample ${sample.id} should have a BundledAsset source", source is SampleSource.BundledAsset)
            val assetPath = (source as SampleSource.BundledAsset).assetPath
            val file = File("src/main/assets", assetPath)
            assertTrue("expected asset file to exist: ${file.absolutePath}", file.exists())
        }
    }

    @Test
    fun `parses a small embedded fixture with exact field mapping`() {
        val fixture = """
            [
              {
                "id": "test_sample_01",
                "name": "Test Sample",
                "category": "SYNTH",
                "assetPath": "loops/test_sample_01.wav",
                "durationMs": 1234,
                "bpm": 120
              }
            ]
        """.trimIndent()

        val samples = LoopManifestParser.parse(fixture)

        assertEquals(1, samples.size)
        val sample = samples.single()
        assertEquals("test_sample_01", sample.id)
        assertEquals("Test Sample", sample.name)
        assertEquals(SampleCategory.SYNTH, sample.category)
        assertEquals(SampleSource.BundledAsset("loops/test_sample_01.wav"), sample.source)
        assertEquals(1234L, sample.durationMs)
        assertTrue(sample.waveformPeaks.isEmpty())
    }

    @Test
    fun `parses via InputStream overload identically to the String overload`() {
        val fromString = LoopManifestParser.parse(realManifestFile.readText())
        val fromStream = realManifestFile.inputStream().use { LoopManifestParser.parse(it) }

        assertEquals(fromString, fromStream)
    }
}
