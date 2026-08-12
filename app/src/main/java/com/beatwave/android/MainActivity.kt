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
 * getting re-delivered a new Intent (onNewIntent, requires
 * launchMode="singleTask" -- see the manifest) -- funnel through
 * [handleShareIntent] into the SAME [incomingShareUri] Compose state, so
 * [ArrangementScreen] only needs to handle one case.
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
     *  other Intent (including a plain LAUNCHER re-launch, which also
     *  reaches onNewIntent under singleTask) is silently ignored, leaving
     *  [incomingShareUri] untouched. */
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
