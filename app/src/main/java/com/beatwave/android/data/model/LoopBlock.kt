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
    val pitchSemitones: Float = 0f
)
