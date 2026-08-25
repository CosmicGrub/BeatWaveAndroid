package com.beatwave.android.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Light sanity checks on the domain model's default values and basic shape.
 * The important end-to-end coverage lives in
 * [com.beatwave.android.data.storage.ProjectRepositoryTest] and
 * [com.beatwave.android.data.library.LoopManifestParserTest].
 */
class DataModelSanityTest {

    @Test
    fun `LoopBlock defaults match the spec`() {
        val block = LoopBlock(
            id = "b1",
            sampleId = "s1",
            startGridUnit = 0,
            lengthGridUnits = 4
        )

        assertEquals(1.0f, block.volume)
        assertEquals(0L, block.trimStartMs)
        assertNull(block.trimEndMs)
        assertEquals(0f, block.pitchSemitones)
        assertNull(block.pitchRow)
    }

    @Test
    fun `Track defaults to an empty list of loop blocks`() {
        val track = Track(slot = 1)

        assertTrue(track.loopBlocks.isEmpty())
    }

    @Test
    fun `Track defaults to an empty assignedSampleIds list`() {
        // Grid-sequencer redesign (2026-08-24 spec), Tier 0: a freshly
        // constructed Track (as opposed to one loaded through
        // ProjectRepository, which applies migrateTrackAssignedSampleIds)
        // has no assignment until something explicitly sets one.
        val track = Track(slot = 1)

        assertTrue(track.assignedSampleIds.isEmpty())
    }

    @Test
    fun `Track slots span the fixed 8-track range`() {
        val tracks = (1..8).map { slot -> Track(slot = slot) }

        assertEquals((1..8).toList(), tracks.map { it.slot })
    }

    @Test
    fun `Track rejects slots outside the fixed 1 to 8 range`() {
        for (invalidSlot in listOf(-1, 0, 9, 999)) {
            try {
                Track(slot = invalidSlot)
                throw AssertionError("expected Track(slot = $invalidSlot) to be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected: slot is outside the fixed 8-track range
            }
        }
    }

    @Test
    fun `Project rejects more than 8 tracks`() {
        val tooManyTracks = (1..8).map { slot -> Track(slot = slot) } + Track(slot = 8)
        try {
            Project(
                id = "p1",
                name = "Too Many Tracks",
                bpm = 120,
                tracks = tooManyTracks,
                createdAtEpochMs = 0L,
                modifiedAtEpochMs = 0L
            )
            throw AssertionError("expected Project construction with 9 tracks to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected: tracks exceeds the fixed 8-track limit
        }
    }

    @Test
    fun `Sample defaults waveformPeaks to empty until Phase 3`() {
        val sample = Sample(
            id = "s1",
            name = "Test",
            category = SampleCategory.DRUMS,
            source = SampleSource.BundledAsset("loops/x.wav"),
            durationMs = 1000
        )

        assertTrue(sample.waveformPeaks.isEmpty())
    }

    @Test
    fun `SampleSource variants carry the expected payload`() {
        val bundled = SampleSource.BundledAsset("loops/kick_basic_01.wav")
        val imported = SampleSource.ImportedFile("content://com.beatwave/imported/1")

        assertEquals("loops/kick_basic_01.wav", bundled.assetPath)
        assertEquals("content://com.beatwave/imported/1", imported.uri)
    }

    @Test
    fun `Sample with a SampleSource round-trips through kotlinx-serialization's polymorphic JSON`() {
        val json = Json { ignoreUnknownKeys = true }

        val bundledSample = Sample(
            id = "s1",
            name = "Bundled",
            category = SampleCategory.DRUMS,
            source = SampleSource.BundledAsset("loops/kick_basic_01.wav"),
            durationMs = 2000
        )
        val importedSample = Sample(
            id = "s2",
            name = "Imported",
            category = SampleCategory.VOCAL,
            source = SampleSource.ImportedFile("content://com.beatwave/imported/1"),
            durationMs = 3000
        )

        val decodedBundled = json.decodeFromString<Sample>(json.encodeToString(bundledSample))
        val decodedImported = json.decodeFromString<Sample>(json.encodeToString(importedSample))

        assertEquals(bundledSample, decodedBundled)
        assertEquals(importedSample, decodedImported)
        assertTrue(decodedBundled.source is SampleSource.BundledAsset)
        assertTrue(decodedImported.source is SampleSource.ImportedFile)
    }

    @Test
    fun `SampleCategory has exactly the four spec categories`() {
        assertEquals(
            setOf("DRUMS", "BASS", "SYNTH", "VOCAL"),
            SampleCategory.values().map { it.name }.toSet()
        )
    }
}
