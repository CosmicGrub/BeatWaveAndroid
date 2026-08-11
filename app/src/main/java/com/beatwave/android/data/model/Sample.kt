package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * A single loop/sound asset that can be placed onto a [Track] via a
 * [LoopBlock]. Either bundled with the app or imported by the user (see
 * [SampleSource]).
 */
@Serializable
data class Sample(
    val id: String,
    val name: String,
    val category: SampleCategory,
    val source: SampleSource,
    val durationMs: Long,
    // Populated by Phase 3 UI work (waveform rendering); left empty until then.
    val waveformPeaks: List<Float> = emptyList()
)
