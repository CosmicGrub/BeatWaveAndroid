package com.beatwave.android.data.model

import kotlinx.serialization.Serializable

/**
 * A full BeatWave arrangement: tempo, tracks, and their loop-block
 * placements. Persisted locally as JSON via
 * `com.beatwave.android.data.storage.ProjectRepository`.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val bpm: Int,
    val tracks: List<Track>,
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long
) {
    init {
        require(tracks.size <= 8) { "Project.tracks must not exceed the fixed 8-track limit, had ${tracks.size}" }
    }
}
