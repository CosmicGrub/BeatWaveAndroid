package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * A placement of a [Sample] onto a [Track]'s timeline, with its own
 * position/length in the arrangement grid plus per-block trim/volume/pitch
 * edits (spec's "Per-loop editing" feature).
 */
@Serializable
data class LoopBlock(
    val id: String,
    val sampleId: String,
    val startGridUnit: Int,
    val lengthGridUnits: Int,
    val volume: Float = 1.0f,
    val trimStartMs: Long = 0,
    val trimEndMs: Long? = null,
    val pitchSemitones: Float = 0f,
    // Grid-sequencer redesign (2026-08-24 spec), Tier 0: the grid row this
    // block was placed on. Null for a block created before this field
    // existed, or one whose track is a drum kit (there the row selects
    // WHICH assigned sample plays, not a pitch offset -- see
    // Track.assignedSampleIds). Non-null on a melodic track, pitchSemitones
    // is derived from this rather than set independently -- see the grid
    // screen's row-computation function (Tier 1).
    val pitchRow: Int? = null
)
