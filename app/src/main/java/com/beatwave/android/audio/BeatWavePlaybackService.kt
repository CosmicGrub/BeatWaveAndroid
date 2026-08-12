package com.beatwave.android.audio

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.beatwave.android.BeatWaveApplication
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 6 design item 3: wraps the app-wide [PlaybackEngine] singleton (see
 * [BeatWaveApplication.playbackEngine]) in a Media3 [MediaSessionService] so
 * lock-screen/notification transport controls and background playback work
 * without hand-rolling a notification/Service/BroadcastReceiver -- Media3
 * generates a correct MediaStyle notification from the [MediaSession] built
 * here, and manages the foreground-service lifecycle itself.
 *
 * Per the Phase 6 same-process-singleton architecture (design item 2), this
 * does NOT create a second engine or a MediaController/MediaBrowser IPC
 * split -- [EnginePlayer] below is a thin [SimpleBasePlayer] adapter whose
 * state/command handlers all delegate straight to the SAME [PlaybackEngine]
 * instance [com.beatwave.android.ui.arrangement.ArrangementViewModel] drives,
 * so a lock-screen Play/Pause and the in-app Play/Pause button control one
 * playback session, not two.
 */
@UnstableApi
class BeatWavePlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: EnginePlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val playbackEngine = (application as BeatWaveApplication).playbackEngine
        val enginePlayer = EnginePlayer(playbackEngine, Looper.getMainLooper())
        player = enginePlayer

        // A bare Player.COMMAND_STOP does NOT, by itself, produce a Stop
        // button in Media3's DefaultMediaNotificationProvider notification --
        // its getMediaButtons() only special-cases the play/pause and
        // previous/next command groups from the player's advertised
        // commands; every other button (this Stop button included) must
        // come from the session's customLayout as a CommandButton carrying a
        // custom SessionCommand, which is what this wires up (fixes a real
        // Phase 6 gap a code review caught: handleStop() was fully
        // implemented but unreachable from the notification's UI). Routed
        // straight to [PlaybackEngine.stop] -- no second lock, same as every
        // other handler here.
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setSessionCommand(CUSTOM_COMMAND_STOP)
            .setDisplayName("Stop")
            .build()

        val session = MediaSession.Builder(this, enginePlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(CUSTOM_COMMAND_STOP)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setCustomLayout(listOf(stopButton))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CUSTOM_COMMAND_STOP.customAction) {
                        playbackEngine.stop()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            })
            .build()
        mediaSession = session
        // Since this app has no MediaController ever connecting to this
        // service (per the Phase 6 same-process-singleton architecture --
        // the in-app UI reads PlaybackEngine directly, and this service is
        // not exported), nothing else would ever trigger the framework's
        // usual addSession() registration path. Registering explicitly here
        // is what wires this session into MediaNotificationManager so it
        // actually builds/posts the notification and promotes this service
        // to the foreground once playback state warrants it (design item 5).
        addSession(session)

        // Design item 5: let Media3's own notification/foreground-service
        // machinery react to every engine state change (invalidateState()
        // tells SimpleBasePlayer to re-read getState() and notify
        // listeners, which is what drives the notification's play/pause
        // icon and the lock-screen scrubber), and explicitly stop this
        // service once transport is genuinely STOPPED (not just paused) --
        // a paused-but-resumable session still shows a paused notification,
        // matching real media apps, per the design's item 5 note.
        stateObserverJob = serviceScope.launch {
            playbackEngine.state.collect { engineState ->
                enginePlayer.refreshState()
                if (engineState.isStopped) {
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        stateObserverJob?.cancel()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private companion object {
        /** Custom session command backing the notification's Stop button --
         *  see the getMediaButtons()-doesn't-special-case-COMMAND_STOP note
         *  in [onCreate]. */
        val CUSTOM_COMMAND_STOP = SessionCommand("com.beatwave.android.STOP", Bundle.EMPTY)
    }
}

/**
 * [SimpleBasePlayer] adapter over [PlaybackEngine] -- Media3's purpose-built
 * base class for wrapping a custom, non-ExoPlayer playback engine (per the
 * Phase 6 design's explicit call-out to prefer it over implementing the raw
 * [Player] interface from scratch). Every `handle*` override below routes
 * straight into [PlaybackEngine]'s existing, already engineMutex-guarded
 * methods -- no second lock is introduced here.
 */
@UnstableApi
private class EnginePlayer(
    private val engine: PlaybackEngine,
    looper: Looper
) : SimpleBasePlayer(looper) {

    override fun getState(): State {
        val es = engine.state.value
        val positionMs = framesToMs(es.currentFrame, es.sampleRate)
        val durationMs = framesToMs(es.durationFrames, es.sampleRate)

        val mediaItem = MediaItem.Builder()
            .setMediaId(MEDIA_ITEM_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(es.projectName ?: "BeatWave")
                    .setArtist("BeatWave")
                    .build()
            )
            .build()

        val itemDataBuilder = MediaItemData.Builder(MEDIA_ITEM_ID)
            .setMediaItem(mediaItem)
        if (durationMs > 0) {
            itemDataBuilder.setDurationUs(durationMs * 1000L)
        }

        // COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM (not just
        // COMMAND_SEEK_TO_DEFAULT_POSITION) is required for the lock-screen/
        // notification scrub bar to be interactive at all: Player.seekTo(
        // positionMs) gates on it, AND it's the specific command Media3's
        // legacy-PlaybackStateCompat bridge maps to ACTION_SEEK_TO --
        // COMMAND_SEEK_TO_DEFAULT_POSITION maps to no legacy action.
        // Confirmed by reading media3-session's PlayerWrapper.
        // convertCommandToPlaybackStateActions switch table (fixes a real
        // Phase 6 gap a code review caught: handleSeek() was fully
        // implemented but unreachable from either surface).
        val availableCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE
            )
            .build()

        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaybackState(if (es.engineStarted) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(es.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(itemDataBuilder.build()))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val sampleRate = engine.state.value.sampleRate
        val frame = if (sampleRate > 0) positionMs * sampleRate / 1000L else 0L
        engine.seekToFrame(frame)
        return Futures.immediateVoidFuture()
    }

    /** Tells SimpleBasePlayer to re-read [getState] and notify listeners --
     *  called from [BeatWavePlaybackService]'s [PlaybackEngine.state]
     *  collector on every engine state change, since nothing else pushes
     *  PlaybackEngine's updates into this Player otherwise. */
    fun refreshState() {
        invalidateState()
    }

    private fun framesToMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate > 0) frames * 1000L / sampleRate else 0L

    companion object {
        private const val MEDIA_ITEM_ID = "beatwave_arrangement"
    }
}
