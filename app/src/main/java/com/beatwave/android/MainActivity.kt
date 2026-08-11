package com.beatwave.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Phase 0 entry point.
 *
 * This screen only exists to prove the app installs, launches, and that the
 * native audio module (see src/main/cpp) loads and opens a stream without
 * crashing. The real arrangement UI (timeline + bottom-sheet loop library)
 * is built in Phase 3.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Phase0StatusScreen()
                }
            }
        }
    }
}

@Composable
private fun Phase0StatusScreen() {
    val engineStatus = remember { mutableStateOf("Starting native audio engine…") }

    LaunchedEffect(Unit) {
        engineStatus.value = try {
            val opened = AudioEngineBridge.startEngine()
            if (opened) {
                "Native audio engine: stream opened OK (silence passthrough)"
            } else {
                "Native audio engine: failed to open stream"
            }
        } catch (t: Throwable) {
            "Native audio engine: not loaded (${t.message})"
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "BeatWave — Phase 0 scaffold\n\n${engineStatus.value}")
    }
}
