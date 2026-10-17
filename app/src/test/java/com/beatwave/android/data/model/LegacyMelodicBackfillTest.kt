package com.beatwave.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tier 3 cutover gate: [backfillLegacyMelodicPitchRows]. */
class LegacyMelodicBackfillTest {

    private fun block(id: String, sampleId: String, semis: Float, row: Int? = null) =
        LoopBlock(id, sampleId, startGridUnit = 0, lengthGridUnits = 1, pitchSemitones = semis, pitchRow = row)

    private fun project(vararg tracks: Track) = Project(
        id = "p", name = "p", bpm = 90,
        tracks = tracks.toList() + (tracks.size + 1..8).map { Track(slot = it) },
        createdAtEpochMs = 0L, modifiedAtEpochMs = 0L
    )

    private val melodic = { id: String -> id == "bass" }

    @Test
    fun melodicTrack_nullRowBlocksGetRoundedSemitoneRow() {
        val p = project(Track(1, listOf(block("a", "bass", 7f), block("b", "bass", -2.4f)), listOf("bass")))
        val rows = backfillLegacyMelodicPitchRows(p, melodic).tracks[0].loopBlocks.map { it.pitchRow }
        assertEquals(listOf(7, -2), rows)
    }

    @Test
    fun existingRowsDrumKitsAndUnassignedAreUntouched() {
        val withRow = Track(1, listOf(block("a", "bass", 3f, row = 3)), listOf("bass"))
        val drums = Track(2, listOf(block("k", "kick", 0f)), listOf("kick"))
        val loose = Track(3, listOf(block("x", "bass", 5f)), emptyList())
        val p = project(withRow, drums, loose)
        val out = backfillLegacyMelodicPitchRows(p, melodic)
        assertSame(withRow, out.tracks[0])
        assertNull(out.tracks[1].loopBlocks[0].pitchRow)
        assertNull(out.tracks[2].loopBlocks[0].pitchRow)
    }

    @Test
    fun isIdempotent() {
        val p = project(Track(1, listOf(block("a", "bass", 4f)), listOf("bass")))
        val once = backfillLegacyMelodicPitchRows(p, melodic)
        assertEquals(once, backfillLegacyMelodicPitchRows(once, melodic))
    }
}
