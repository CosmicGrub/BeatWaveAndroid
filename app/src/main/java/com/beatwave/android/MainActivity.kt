package com.beatwave.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ArrangementScreen()
                }
            }
        }
    }
}
