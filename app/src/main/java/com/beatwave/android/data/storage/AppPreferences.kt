package com.beatwave.android.data.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny app-wide preference: which [com.beatwave.android.data.model.Project]
 * id was last opened, so relaunching the app reopens the same project
 * instead of always defaulting to one fixed id (multiple-projects upgrade).
 *
 * Deliberately a single-purpose wrapper, not a general preferences
 * abstraction -- add fields here only if/when another genuinely app-wide
 * preference shows up, per this codebase's existing "narrow, single-purpose
 * collaborator" pattern (see [ProjectRepository]/[com.beatwave.android.data.library.ImportedSampleIndex]).
 */
class AppPreferences(private val prefs: SharedPreferences) {

    /** The id of the project to reopen on next launch, or null if none has
     *  been recorded yet (fresh install, or an install from before this
     *  preference existed -- callers should fall back to the legacy default
     *  project id in that case; see [ArrangementViewModel.PROJECT_ID][com.beatwave.android.ui.arrangement.ArrangementViewModel]). */
    var lastActiveProjectId: String?
        get() = prefs.getString(KEY_LAST_ACTIVE_PROJECT_ID, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_ACTIVE_PROJECT_ID, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "beatwave_prefs"
        private const val KEY_LAST_ACTIVE_PROJECT_ID = "last_active_project_id"

        /** Factory for real Android use, mirroring [ProjectRepository.forContext]. */
        fun forContext(context: Context): AppPreferences =
            AppPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
