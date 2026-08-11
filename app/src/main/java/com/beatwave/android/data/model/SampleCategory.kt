package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * The four bundled-loop-pack categories used throughout the app (timeline
 * color-coding, bottom-sheet loop library sections, etc.). See the v1 design
 * spec's "High-Level Data Model" section.
 */
@Serializable
enum class SampleCategory {
    DRUMS,
    BASS,
    SYNTH,
    VOCAL
}
