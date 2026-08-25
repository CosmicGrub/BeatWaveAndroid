package com.beatwave.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Grid-sequencer redesign (2026-08-24 spec), Tier 0: pure-JVM unit tests
 * for [migrateTrackAssignedSampleIds]/[migrateProjectAssignedSampleIds] in
 * isolation. [com.beatwave.android.data.storage.ProjectRepositoryTest]'s
 * own "pre-Tier-0 project JSON" test covers the same logic through the
 * real load() path end-to-end; this file pins down the function's exact
 * behavior on its own, including the already-populated no-op case a
 * JSON-decode test wouldn't naturally exercise.
 */
class TrackMigrationTest {

    private fun block(id: String, sampleId: String) =
        LoopBlock(id = id, sampleId = sampleId, startGridUnit = 0, lengthGridUnits = 1)

    @Test
    fun `already-populated assignedSampleIds is left untouched`() {
        val track = Track(
            slot = 1,
            loopBlocks = listOf(block("b1", "kick_basic_01")),
            assignedSampleIds = listOf("some_other_sample_id")
        )

        val migrated = migrateTrackAssignedSampleIds(track)

        assertEquals(listOf("some_other_sample_id"), migrated.assignedSampleIds)
    }

    @Test
    fun `single-sample track migrates to a one-entry assignedSampleIds`() {
        val track = Track(
            slot = 1,
            loopBlocks = listOf(
                block("b1", "kick_basic_01"),
                block("b2", "kick_basic_01") // same sample placed twice
            )
        )

        val migrated = migrateTrackAssignedSampleIds(track)

        assertEquals(listOf("kick_basic_01"), migrated.assignedSampleIds)
    }

    @Test
    fun `multi-sample track migrates to all distinct sample ids in first-seen order`() {
        val track = Track(
            slot = 1,
            loopBlocks = listOf(
                block("b1", "kick_basic_01"),
                block("b2", "snare_basic_01"),
                block("b3", "kick_basic_01"), // repeat -- shouldn't duplicate
                block("b4", "clap_basic_01")
            )
        )

        val migrated = migrateTrackAssignedSampleIds(track)

        assertEquals(
            listOf("kick_basic_01", "snare_basic_01", "clap_basic_01"),
            migrated.assignedSampleIds
        )
    }

    @Test
    fun `empty track (no blocks) migrates to an empty assignedSampleIds`() {
        val track = Track(slot = 1)

        val migrated = migrateTrackAssignedSampleIds(track)

        assertEquals(emptyList<String>(), migrated.assignedSampleIds)
    }

    @Test
    fun `migrateProjectAssignedSampleIds applies the same migration to every track`() {
        val project = Project(
            id = "p1",
            name = "Test",
            bpm = 120,
            tracks = listOf(
                Track(slot = 1, loopBlocks = listOf(block("b1", "kick_basic_01"))),
                Track(slot = 2, loopBlocks = listOf(block("b2", "bass_riff_01"))),
                Track(slot = 3) // empty, stays empty
            ),
            createdAtEpochMs = 0L,
            modifiedAtEpochMs = 0L
        )

        val migrated = migrateProjectAssignedSampleIds(project)

        assertEquals(listOf("kick_basic_01"), migrated.tracks[0].assignedSampleIds)
        assertEquals(listOf("bass_riff_01"), migrated.tracks[1].assignedSampleIds)
        assertEquals(emptyList<String>(), migrated.tracks[2].assignedSampleIds)
    }

    @Test
    fun `migrating an already-fully-migrated project is a stable no-op`() {
        // Running migration twice (e.g. if a caller ever loaded, then
        // re-saved, then re-loaded the same data) must not change anything
        // further or throw -- the "already-populated" no-op rule applies.
        val project = Project(
            id = "p1",
            name = "Test",
            bpm = 120,
            tracks = listOf(Track(slot = 1, loopBlocks = listOf(block("b1", "kick_basic_01")))),
            createdAtEpochMs = 0L,
            modifiedAtEpochMs = 0L
        )

        val onceMigrated = migrateProjectAssignedSampleIds(project)
        val twiceMigrated = migrateProjectAssignedSampleIds(onceMigrated)

        assertEquals(onceMigrated, twiceMigrated)
    }

    @Test
    fun `migrateTrackAssignedSampleIds returns the identical instance for an already-migrated track`() {
        // Not load-bearing behavior on its own, but confirms the no-op
        // path really is a no-op (returns the same reference) rather than
        // reconstructing an equal-but-new Track every time this runs.
        val track = Track(slot = 1, assignedSampleIds = listOf("kick_basic_01"))

        assertSame(track, migrateTrackAssignedSampleIds(track))
    }
}
