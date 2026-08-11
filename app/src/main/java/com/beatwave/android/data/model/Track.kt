package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * One of a [Project]'s fixed 8 track slots (per the spec's fixed 8-track
 * limit), holding an ordered set of [LoopBlock] placements.
 */
@Serializable
data class Track(
    val slot: Int,
    val loopBlocks: List<LoopBlock> = emptyList()
) {
    init {
        require(slot in 1..8) { "Track.slot must be within 1..8 (fixed 8-track limit), was $slot" }
    }
}
