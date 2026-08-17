package com.beatwave.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.beatwave.android.ui.arrangement.ArrangementScreen

/**
 * Phase 3 entry point: hosts the real arrangement UI -- horizontal timeline
 * with the fixed 8 tracks, bottom-sheet loop library, per-block editor, and
 * playback controls -- replacing Phase 0's placeholder status screen.
 *
 * Native audio engine lifecycle (startEngine/nativeInit/loadProject on init,
 * stopEngine on teardown) is owned by
 * [com.beatwave.android.ui.arrangement.ArrangementViewModel], scoped to this
 * activity via the default `viewModel()` factory.
 *
 * Phase 7: also the entry point for receiving a shared audio file from
 * another app (design item "Share intents" -- the app can receive shared
 * audio files as import candidates). The manifest declares an ACTION_SEND/
 * audio-* intent-filter on this Activity, exported=true (same as the
 * LAUNCHER filter) so it's offered as a share target. Both ways such an
 * Intent can arrive -- a cold launch straight into this Activity
 * (onCreate), or the app already running in the foreground/background and
 * getting re-delivered a new Intent into the SAME instance (onNewIntent,
 * only reached if the sender happens to set FLAG_ACTIVITY_SINGLE_TOP, since
 * the manifest deliberately uses plain "standard" launch mode here, NOT
 * singleTask -- see the manifest's own comment for why) -- funnel through
 * [handleShareIntent] into the SAME [incomingShareUri] Compose state, so
 * [ArrangementScreen] only needs to handle one case. CAVEAT (found during
 * the post-v1 A1 import-hardening audit): because launch mode is standard,
 * a burst of ACTION_SEND intents typically spins up SEPARATE MainActivity/
 * ArrangementViewModel instances rather than being serialized through one
 * -- see [BeatWaveApplication.importLeaseClaimedAtMs] for how concurrent decodes
 * across those separate instances are still bounded app-wide.
 */
class MainActivity : ComponentActivity() {
    private val incomingShareUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ArrangementScreen(
                        incomingShareUri = incomingShareUri.value,
                        onIncomingShareUriConsumed = { incomingShareUri.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Recognizes only ACTION_SEND with an audio-family MIME type (matching the
     *  manifest's intent-filter exactly) and extracts EXTRA_STREAM -- any
     *  other Intent (including a plain LAUNCHER re-launch, on the rarer
     *  occasions one does reach onNewIntent -- see this class's own doc
     *  comment on when that actually happens under standard launch mode) is
     *  silently ignored, leaving [incomingShareUri] untouched. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("audio/") != true) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            incomingShareUri.value = uri
        }
    }
}
