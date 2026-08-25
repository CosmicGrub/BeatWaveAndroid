package com.beatwave.android.data.model

/**
 * Grid-sequencer redesign (2026-08-24 spec), Tier 0: existing saved
 * projects predate [Track.assignedSampleIds] -- a track used to just hold
 * whatever mix of different samples' [LoopBlock]s the user dropped onto
 * it, with no explicit instrument/kit assignment at all. Applied once per
 * track at load time (see `ProjectRepository`) so every existing project
 * becomes immediately usable in the new grid screen with no explicit
 * migration screen and no data loss -- the track's own placed loop blocks
 * are the source of truth for what it should be considered assigned to.
 *
 * A no-op if [track] already has a real assignment: covers both re-running
 * this safely on already-migrated data, and a freshly created track under
 * the new model that already has its assignment set some other way (e.g.
 * via the Tier 2 Sounds picker) before ever being saved.
 */
fun migrateTrackAssignedSampleIds(track: Track): Track {
    if (track.assignedSampleIds.isNotEmpty()) return track
    val derivedSampleIds = track.loopBlocks.map { it.sampleId }.distinct()
    return track.copy(assignedSampleIds = derivedSampleIds)
}

/** Applies [migrateTrackAssignedSampleIds] to every track in [project]. */
fun migrateProjectAssignedSampleIds(project: Project): Project =
    project.copy(tracks = project.tracks.map(::migrateTrackAssignedSampleIds))
