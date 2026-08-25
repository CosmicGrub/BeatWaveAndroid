package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * One of a [Project]'s fixed 8 track slots (per the spec's fixed 8-track
 * limit), holding an ordered set of [LoopBlock] placements.
 */
@Serializable
data class Track(
    val slot: Int,
    val loopBlocks: List<LoopBlock> = emptyList(),
    // Grid-sequencer redesign (2026-08-24 spec), Tier 0: which Sample(s)
    // this track's grid is built around. A melodic-category (BASS/SYNTH/
    // VOCAL) sample means exactly one entry, and the grid's rows are pitch
    // offsets of it; one or more DRUMS-category samples means one named
    // row per entry, in this list's order, no pitch-shifting -- see
    // migrateTrackAssignedSampleIds for how existing saved projects (which
    // predate this field) get it populated automatically at load time.
    val assignedSampleIds: List<String> = emptyList()
) {
    init {
        require(slot in 1..8) { "Track.slot must be within 1..8 (fixed 8-track limit), was $slot" }
    }
}
