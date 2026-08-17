package com.beatwave.android

import android.app.Application
import com.beatwave.android.audio.PlaybackEngine
import com.beatwave.android.diagnostics.CrashLogger
import java.util.concurrent.atomic.AtomicLong

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

    /** Post-v1 audit A1 (import size/DoS hardening): app-wide, NOT
     *  per-ViewModel-instance, because a per-instance guard alone wouldn't
     *  close the gap this exists for. MainActivity deliberately uses
     *  standard (not singleTask) launch mode -- see its own class doc
     *  comment -- so a burst of ACTION_SEND share intents from another app
     *  (Phase 7's "less-trusted than the user's own SAF pick" intake) can
     *  spin up SEPARATE MainActivity/ArrangementViewModel instances, each
     *  with its own independent AudioImporter, rather than being serialized
     *  through one. A single app-wide flag is what actually guarantees at
     *  most one import decodes at a time regardless of which instance
     *  requested it -- mirrors [playbackEngine]'s own app-wide-singleton
     *  rationale for cross-instance coordination. A per-file byte cap
     *  ([com.beatwave.android.data.library.AudioImporter.maxDecodedPcmBytes])
     *  alone doesn't bound N concurrent imports summing to N x that ceiling.
     *
     *  A SELF-EXPIRING lease (epoch-ms timestamp the current import claimed
     *  it at, or 0L if free), deliberately NOT a plain boolean -- found
     *  during this audit's own adversarial-review pass: AudioImporter's
     *  underlying MediaExtractor/MediaCodec calls are native and blocking,
     *  and `import()`'s own withTimeout+runInterruptible wrapper around
     *  them is best-effort (Thread.interrupt() is not guaranteed to
     *  actually preempt a stuck native call). A plain boolean guard,
     *  released only in a `finally` block INSIDE the import coroutine,
     *  would never be released at all if that coroutine's underlying
     *  decode call genuinely never returns -- permanently wedging EVERY
     *  future import app-wide until process restart, a regression strictly
     *  worse than having no guard at all. This lease instead lets a later
     *  caller reclaim it once it's older than a generous max duration,
     *  regardless of whether the original holder ever released it -- see
     *  [com.beatwave.android.ui.arrangement.ArrangementViewModel.importAudioFromUri]
     *  for the acquire/reclaim/release logic itself. */
    val importLeaseClaimedAtMs = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        crashLogger.install()
    }
}
