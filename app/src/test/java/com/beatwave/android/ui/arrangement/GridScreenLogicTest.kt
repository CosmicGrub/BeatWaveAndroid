package com.beatwave.android.ui.arrangement

import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grid-sequencer redesign (2026-08-24 spec), Tier 1: pure-JVM unit tests
 * for [gridTrackKind]/[chromaticPitchRows] -- no device/emulator needed.
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
    fun `one DRUMS sample is NOT_YET_SUPPORTED (Tier 2_1 territory)`() {
        val s = sample("kick", SampleCategory.DRUMS)
        val track = Track(slot = 1, assignedSampleIds = listOf(s.id))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, mapOf(s.id to s)))
    }

    @Test
    fun `multiple assigned samples is NOT_YET_SUPPORTED even if all melodic`() {
        val s1 = sample("s1", SampleCategory.SYNTH)
        val s2 = sample("s2", SampleCategory.BASS)
        val track = Track(slot = 1, assignedSampleIds = listOf(s1.id, s2.id))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, mapOf(s1.id to s1, s2.id to s2)))
    }

    @Test
    fun `an assignedSampleIds entry that no longer resolves is NOT_YET_SUPPORTED, not a crash`() {
        val track = Track(slot = 1, assignedSampleIds = listOf("deleted_sample_id"))
        assertEquals(GridTrackKind.NOT_YET_SUPPORTED, gridTrackKind(track, emptyMap()))
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
