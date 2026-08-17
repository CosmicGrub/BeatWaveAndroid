package com.beatwave.android

import android.app.Application
import com.beatwave.android.audio.PlaybackEngine
import com.beatwave.android.diagnostics.CrashLogger

/**
 * Application-scoped entry point (Phase 6, design item 2): hosts the single
 * process-wide [PlaybackEngine] instance so [com.beatwave.android.ui.arrangement.ArrangementViewModel]
 * (via `getApplication<BeatWaveApplication>().playbackEngine`) and
 * [com.beatwave.android.audio.BeatWavePlaybackService]'s MediaSession both
 * drive the SAME engine -- making lock-screen/notification controls and the
 * in-app UI two views onto one playback session, per the Phase 6 same-
 * process-singleton architecture (no MediaController/MediaBrowser IPC split
 * needed, since this app has no separate process or cross-app consumers).
 *
 * Post-v1 audit A2 (crash resilience): also hosts the process-wide
 * [CrashLogger], installed as early as possible in [onCreate] so it's in
 * place for the entire process lifetime -- any uncaught exception on any
 * thread, from here on, gets a report written before the OS's own crash
 * handling takes over (see [CrashLogger.install]'s doc comment).
 */
class BeatWaveApplication : Application() {

    val playbackEngine: PlaybackEngine by lazy { PlaybackEngine(this) }
    val crashLogger: CrashLogger by lazy { CrashLogger.forContext(this) }

    override fun onCreate() {
        super.onCreate()
        crashLogger.install()
    }
}
