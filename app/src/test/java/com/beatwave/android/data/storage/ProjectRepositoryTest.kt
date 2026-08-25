package com.beatwave.android.data.storage

import com.beatwave.android.data.library.LoopManifestParser
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 1 exit criterion: create a [Project] with tracks + loop blocks that
 * reference bundled samples, save it via [ProjectRepository], load it back
 * by id, and verify full equality -- end to end, on the plain JVM.
 */
class ProjectRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val realManifestFile = File("src/main/assets/loops/manifest.json")

    private fun bundledSamples(): List<Sample> =
        LoopManifestParser.parse(realManifestFile.readText())

    @Test
    fun `create, save, and load a project with tracks and loop blocks referencing bundled samples`() {
        val samples = bundledSamples()
        val kick = samples.first { it.id == "kick_basic_01" }
        val bassRiff = samples.first { it.id == "bass_riff_01" }
        val synthChord = samples.first { it.id == "synth_chord_01" }

        val loopBlockOnTrack1a = LoopBlock(
            id = "block-1",
            sampleId = kick.id,
            startGridUnit = 0,
            lengthGridUnits = 4,
            volume = 0.9f,
            trimStartMs = 0,
            trimEndMs = 1500,
            pitchSemitones = 0f
        )
        val loopBlockOnTrack1b = LoopBlock(
            id = "block-2",
            sampleId = bassRiff.id,
            startGridUnit = 4,
            lengthGridUnits = 8
        )
        val loopBlockOnTrack2 = LoopBlock(
            id = "block-3",
            sampleId = synthChord.id,
            startGridUnit = 0,
            lengthGridUnits = 16,
            volume = 0.75f,
            pitchSemitones = -2f
        )

        val track1 = Track(slot = 1, loopBlocks = listOf(loopBlockOnTrack1a, loopBlockOnTrack1b))
        val track2 = Track(slot = 2, loopBlocks = listOf(loopBlockOnTrack2))
        val track3EmptySlot = Track(slot = 3)

        val original = Project(
            id = "project-round-trip-1",
            name = "Round Trip Demo",
            bpm = 90,
            tracks = listOf(track1, track2, track3EmptySlot),
            createdAtEpochMs = 1_700_000_000_000L,
            modifiedAtEpochMs = 1_700_000_100_000L
        )

        val repository = ProjectRepository(tempFolder.newFolder("projects"))

        repository.save(original)
        val loaded = repository.load(original.id)

        assertNotNull("expected the saved project to load back", loaded)
        // Grid-sequencer redesign (2026-08-24 spec), Tier 0: load() now
        // migrates any track with no explicit assignedSampleIds, deriving
        // it from that track's own loop blocks -- so `loaded` is no
        // longer byte-identical to `original` for a track that never set
        // this field explicitly (none of this fixture's tracks do).
        // Compare against the expected POST-migration shape instead of a
        // blind original==loaded check, so this test still proves the
        // real round-trip AND the migration together, not less than
        // before.
        val expectedAfterMigration = original.copy(
            tracks = original.tracks.map { track ->
                track.copy(assignedSampleIds = track.loopBlocks.map { it.sampleId }.distinct())
            }
        )
        assertEquals(expectedAfterMigration, loaded)

        // Spot-check the sampleId references actually resolve against the
        // bundled manifest, since that's the point of this test.
        val sampleIds = samples.map { it.id }.toSet()
        for (track in loaded!!.tracks) {
            for (block in track.loopBlocks) {
                assertTrue(
                    "loop block ${block.id} should reference a bundled sample id",
                    block.sampleId in sampleIds
                )
            }
        }
    }

    @Test
    fun `load reads pre-Tier-0 project JSON missing assignedSampleIds and pitchRow, migrating it`() {
        // Grid-sequencer redesign (2026-08-24 spec), Tier 0: hand-written
        // JSON shaped exactly like a project saved before this phase --
        // no "assignedSampleIds" key on the track, no "pitchRow" key on
        // the loop block at all. kotlinx.serialization's own default-value
        // handling (not a special migration-specific decoder) is what
        // makes this parse successfully; migrateProjectAssignedSampleIds
        // then derives the real assignment from the track's one block.
        val projectsDir = tempFolder.newFolder("projects")
        val repository = ProjectRepository(projectsDir)
        val legacyJson = """
            {
              "id": "legacy-project",
              "name": "Pre-Tier-0 Project",
              "bpm": 100,
              "tracks": [
                {
                  "slot": 1,
                  "loopBlocks": [
                    { "id": "b1", "sampleId": "kick_basic_01", "startGridUnit": 0, "lengthGridUnits": 4 }
                  ]
                },
                { "slot": 2 }
              ],
              "createdAtEpochMs": 1700000000000,
              "modifiedAtEpochMs": 1700000100000
            }
        """.trimIndent()
        File(projectsDir, "legacy-project.json").writeText(legacyJson)

        val loaded = repository.load("legacy-project")

        assertNotNull("expected legacy-format JSON (missing new Tier 0 fields) to still parse", loaded)
        assertEquals(0f, loaded!!.tracks[0].loopBlocks[0].pitchSemitones)
        assertNull(
            "expected pitchRow to default to null for a block predating this field",
            loaded.tracks[0].loopBlocks[0].pitchRow
        )
        assertEquals(
            "expected Track 1's assignedSampleIds to be MIGRATED (derived) from its own block, not left empty",
            listOf("kick_basic_01"), loaded.tracks[0].assignedSampleIds
        )
        assertTrue(
            "expected Track 2 (no blocks at all) to migrate to an empty assignedSampleIds, not throw",
            loaded.tracks[1].assignedSampleIds.isEmpty()
        )
    }

    @Test
    fun `load returns null for an id that was never saved`() {
        val repository = ProjectRepository(tempFolder.newFolder("projects"))

        assertNull(repository.load("does-not-exist"))
    }

    @Test
    fun `load returns null instead of throwing for a corrupted project file`() {
        val projectsDir = tempFolder.newFolder("projects")
        val repository = ProjectRepository(projectsDir)

        // Simulate a process kill mid-save leaving a truncated/corrupted file.
        File(projectsDir, "corrupted.json").writeText("{ \"id\": \"corrupted\", \"name\": tru")

        assertNull(repository.load("corrupted"))
    }

    @Test
    fun `list skips a corrupted project file rather than throwing`() {
        val projectsDir = tempFolder.newFolder("projects")
        val repository = ProjectRepository(projectsDir)

        repository.save(emptyProject(id = "good"))
        File(projectsDir, "corrupted.json").writeText("not valid json at all")

        val listed = repository.list().map { it.id }.toSet()
        assertEquals(setOf("good"), listed)
    }

    @Test
    fun `list returns every saved project and delete removes it`() {
        val repository = ProjectRepository(tempFolder.newFolder("projects"))
        val projectA = emptyProject(id = "a")
        val projectB = emptyProject(id = "b")

        repository.save(projectA)
        repository.save(projectB)

        val listed = repository.list().map { it.id }.toSet()
        assertEquals(setOf("a", "b"), listed)

        repository.delete("a")
        assertNull(repository.load("a"))
        assertEquals(setOf("b"), repository.list().map { it.id }.toSet())
    }

    @Test
    fun `save overwrites an existing project file for the same id`() {
        val repository = ProjectRepository(tempFolder.newFolder("projects"))
        val original = emptyProject(id = "overwrite-me").copy(name = "Original Name")
        repository.save(original)

        val updated = original.copy(name = "Updated Name", modifiedAtEpochMs = original.modifiedAtEpochMs + 1)
        repository.save(updated)

        val loaded = repository.load("overwrite-me")
        assertEquals("Updated Name", loaded?.name)
        assertEquals(1, repository.list().size)
    }

    private fun emptyProject(id: String): Project = Project(
        id = id,
        name = "Empty Project $id",
        bpm = 120,
        tracks = emptyList(),
        createdAtEpochMs = 0L,
        modifiedAtEpochMs = 0L
    )

    // NOTE: Project/Track/LoopBlock only ever hold a sampleId String, never
    // an embedded Sample/SampleSource, so this repository's Json instance
    // never actually serializes a SampleSource. The polymorphic JSON
    // round-trip for SampleSource is instead exercised directly in
    // com.beatwave.android.data.model.DataModelSanityTest
    // ("Sample with a SampleSource round-trips through kotlinx-serialization's
    // polymorphic JSON"). This test only checks that LoopManifestParser
    // resolves bundled manifest entries to the BundledAsset variant.
    @Test
    fun `bundled sample source is a BundledAsset variant`() {
        val kick = bundledSamples().first { it.id == "kick_basic_01" }
        assertTrue(kick.source is SampleSource.BundledAsset)
    }
}
