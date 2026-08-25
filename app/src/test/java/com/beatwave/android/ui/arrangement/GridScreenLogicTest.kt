package com.beatwave.android.ui.arrangement

import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grid-sequencer redesign (2026-08-24 spec): pure-JVM unit tests for
 * [gridTrackKind]/[chromaticPitchRows] -- no device/emulator needed.
 * [GridScreenRenderingTest] (instrumented) covers the same logic through
 * real Compose rendering on real hardware.
 */
class GridScreenLogicTest {

    private fun sample(id: String, category: SampleCategory) = Sample(
        id = id,
        name = id,
        category = category,
        source = SampleSource.BundledAsset("loops/$id.wav"),
        durationMs = 1000
    )

    @Test
    fun `unassigned track (empty assignedSampleIds) is UNASSIGNED`() {
        val track = Track(slot = 1)
        assertEquals(GridTrackKind.UNASSIGNED, gridTrackKind(track, emptyMap()))
    }

    @Test
    fun `one melodic sample (BASS, SYNTH, or VOCAL) is MELODIC`() {
        for (category in listOf(SampleCategory.BASS, SampleCategory.SYNTH, SampleCategory.VOCAL)) {
            val s = sample("s1", category)
            val track = Track(slot = 1, assignedSampleIds = listOf(s.id))
            assertEquals(
                "expected category $category to be treated as melodic",
                GridTrackKind.MELODIC, gridTrackKind(track, mapOf(s.id to s))
            )
        }
    }

    @Test
    fun `one DRUMS sample is DRUM_KIT (Tier 2_1)`() {
        val s = sample("kick", SampleCategory.DRUMS)
        val track = Track(slot = 1, assignedSampleIds = listOf(s.id))
        assertEquals(GridTrackKind.DRUM_KIT, gridTrackKind(track, mapOf(s.id to s)))
    }

    @Test
    fun `multiple all-DRUMS samples is DRUM_KIT, in assignedSampleIds order (Tier 2_1)`() {
        val kick = sample("kick", SampleCategory.DRUMS)
        val snare = sample("snare", SampleCategory.DRUMS)
        val track = Track(slot = 1, assignedSampleIds = listOf(kick.id, snare.id))
        assertEquals(GridTrackKind.DRUM_KIT, gridTrackKind(track, mapOf(kick.id to kick, snare.id to snare)))
    }

    @Test
    fun `multiple assigned samples is NOT_YET_SUPPORTED if not all DRUMS`() {
        val s1 = sample("s1", SampleCategory.SYNTH)
        val s2 = sample("s2", SampleCategory.BASS)
        val track = Track(slot = 1, assignedSampleIds = listOf(s1.id, s2.id))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, mapOf(s1.id to s1, s2.id to s2)))
    }

    @Test
    fun `a mixed melodic+drum assignment is NOT_YET_SUPPORTED`() {
        val bass = sample("bass", SampleCategory.BASS)
        val kick = sample("kick", SampleCategory.DRUMS)
        val track = Track(slot = 1, assignedSampleIds = listOf(bass.id, kick.id))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, mapOf(bass.id to bass, kick.id to kick)))
    }

    @Test
    fun `an assignedSampleIds entry that no longer resolves is NOT_YET_SUPPORTED, not a crash`() {
        val track = Track(slot = 1, assignedSampleIds = listOf("deleted_sample_id"))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, emptyMap()))
    }

    // --- Tier 2.4: drumKitRowSampleIds / orphaned-row visibility ---

    private fun drumBlock(sampleId: String, startGridUnit: Int) = LoopBlock(
        id = "$sampleId-$startGridUnit",
        sampleId = sampleId,
        startGridUnit = startGridUnit,
        lengthGridUnits = 1,
        pitchRow = null
    )

    @Test
    fun `drumKitRowSampleIds is just assignedSampleIds when nothing is orphaned`() {
        val track = Track(
            slot = 1,
            assignedSampleIds = listOf("kick", "snare"),
            loopBlocks = listOf(drumBlock("kick", 0), drumBlock("snare", 4))
        )
        assertEquals(listOf("kick", "snare"), drumKitRowSampleIds(track))
    }

    @Test
    fun `drumKitRowSampleIds appends an orphaned id after the assigned ones`() {
        // "hihat" has an existing block but was removed from assignedSampleIds.
        val track = Track(
            slot = 1,
            assignedSampleIds = listOf("kick"),
            loopBlocks = listOf(drumBlock("kick", 0), drumBlock("hihat", 2))
        )
        assertEquals(listOf("kick", "hihat"), drumKitRowSampleIds(track))
    }

    @Test
    fun `drumKitRowSampleIds ignores melodic (pitchRow non-null) blocks entirely`() {
        val track = Track(
            slot = 1,
            assignedSampleIds = emptyList(),
            loopBlocks = listOf(LoopBlock(id = "b1", sampleId = "bass", startGridUnit = 0, lengthGridUnits = 1, pitchRow = 2))
        )
        assertEquals(emptyList<String>(), drumKitRowSampleIds(track))
    }

    @Test
    fun `a fully-unassigned track with an orphaned drum note is DRUM_KIT, not UNASSIGNED`() {
        val kick = sample("kick", SampleCategory.DRUMS)
        // Both samples removed from assignedSampleIds via Sounds, but a
        // note on the kick row was never deleted -- must not fall back to
        // the "choose an instrument" prompt, which would hide it.
        val track = Track(slot = 1, assignedSampleIds = emptyList(), loopBlocks = listOf(drumBlock("kick", 3)))
        assertEquals(GridTrackKind.DRUM_KIT, gridTrackKind(track, mapOf(kick.id to kick)))
    }

    @Test
    fun `a fully-unassigned track with no orphaned notes stays UNASSIGNED`() {
        val track = Track(slot = 1, assignedSampleIds = emptyList(), loopBlocks = emptyList())
        assertEquals(GridTrackKind.UNASSIGNED, gridTrackKind(track, emptyMap()))
    }

    // --- Tier 2.5: scaleVisibleSemitones ---

    @Test
    fun `CHROMATIC scale hides nothing -- every chromaticPitchRows offset is visible`() {
        assertEquals(chromaticPitchRows().toSet(), scaleVisibleSemitones(ScaleType.CHROMATIC))
    }

    @Test
    fun `MAJOR scale visible offsets are exactly the major scale degrees, both directions from 0`() {
        // Major: 0,2,4,5,7,9,11 (mod 12). Within -12..+12, every offset
        // whose (offset mod 12) is one of those degrees.
        val expected = (-12..12).filter { Math.floorMod(it, 12) in setOf(0, 2, 4, 5, 7, 9, 11) }.toSet()
        assertEquals(expected, scaleVisibleSemitones(ScaleType.MAJOR))
    }

    @Test
    fun `MINOR_PENTATONIC has exactly 5 degrees per octave`() {
        val visible = scaleVisibleSemitones(ScaleType.MINOR_PENTATONIC)
        val degreesPerOctave = visible.map { Math.floorMod(it, 12) }.toSet()
        assertEquals(setOf(0, 3, 5, 7, 10), degreesPerOctave)
    }

    @Test
    fun `every scale includes offset 0 (the sample's own root pitch)`() {
        for (type in ScaleType.entries) {
            assertTrue("expected offset 0 to be visible for $type", 0 in scaleVisibleSemitones(type))
        }
    }

    @Test
    fun `chromaticPitchRows spans plus12 to minus12, highest first`() {
        val rows = chromaticPitchRows()
        assertEquals(25, rows.size)
        assertEquals(12, rows.first())
        assertEquals(-12, rows.last())
        assertEquals((12 downTo -12).toList(), rows)
    }
}
