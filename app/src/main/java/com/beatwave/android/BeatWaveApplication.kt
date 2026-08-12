package com.beatwave.android

import android.app.Application
import com.beatwave.android.audio.PlaybackEngine

/**
 * Application-scoped entry point (Phase 6, design item 2): hosts the single
 * process-wide [PlaybackEngine] instance so [com.beatwave.android.ui.arrangement.ArrangementViewModel]
 * (via `getApplication<BeatWaveApplication>().playbackEngine`) and
 * [com.beatwave.android.audio.BeatWavePlaybackService]'s MediaSession both
 * drive the SAME engine -- making lock-screen/notification controls and the
 * in-app UI two views onto one playback session, per the Phase 6 same-
 * process-singleton architecture (no MediaController/MediaBrowser IPC split
 * needed, since this app has no separate process or cross-app consumers).
 */
class BeatWaveApplication : Application() {

    val playbackEngine: PlaybackEngine by lazy { PlaybackEngine(this) }
}
