package com.beatwave.android.ui.arrangement

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.beatwave.android.audio.GridConstants
import com.beatwave.android.data.model.LoopBlock
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.Track
import java.io.File
import kotlin.math.roundToInt

// Tier 3 cutover: moved verbatim out of ArrangementScreen.kt (about to be
// deleted) so the transport bar and per-track record affordance GridScreen
// reuses have a permanent home. No behavior change.

// Tier 3 (grid-sequencer cutover): widened from private to internal so
// GridScreen.kt can reuse this composable as-is, per the implementation
// plan's own explicit instruction ("Do not remove... PlaybackControlBar...
// all explicitly reused per the spec"). No behavior change to
// ArrangementScreen itself.
@Composable
internal fun PlaybackControlBar(
    isPlaying: Boolean,
    isRecording: Boolean,
    currentFrame: Long,
    sampleRate: Int,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    // Device-adaptive layouts (2026-08-18 spec), Phase 0: null in two-pane
    // mode, where the Loop Library is already a persistent, always-visible
    // panel -- this button would be a dead/redundant control there.
    onOpenLibrary: (() -> Unit)?
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Disabled while a recording is in progress: pausing mid-recording
            // would stop draining the still-open, still-capturing input
            // stream (onAudioReady returns early on !mPlaying, before it ever
            // reaches the recording-capture block), letting real hardware
            // audio silently accumulate/overflow in Oboe's own buffer until
            // playback resumes -- corrupting the take's alignment. Per the
            // design's arm-and-play UX, only the per-track Stop-record
            // affordance and the global Stop button (which finalizes an
            // in-progress recording, see ArrangementViewModel.stopPlayback)
            // may end a recording session.
            Button(
                onClick = onTogglePlayPause,
                enabled = !isRecording,
                // Post-v1 audit A4: Compose already announces the disabled
                // state itself, but not WHY -- a screen reader user can't
                // see the pulsing record dot elsewhere on screen that
                // explains it visually.
                modifier = Modifier
                    .then(
                        if (isRecording) {
                            Modifier.semantics { stateDescription = "Disabled while recording" }
                        } else {
                            Modifier
                        }
                    )
                    .testTag("play_pause_button")
            ) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onStop,
                // Post-v1 audit A4: this button also finalizes an
                // in-progress recording (see the class doc comment above) --
                // a distinct, higher-stakes action than a plain playback
                // stop, worth calling out explicitly.
                modifier = Modifier
                    .semantics { contentDescription = if (isRecording) "Stop and finish recording" else "Stop" }
                    .testTag("stop_button")
            ) {
                Text("Stop")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatPosition(currentFrame, sampleRate),
                style = MaterialTheme.typography.bodySmall,
                // Post-v1 audit A4: deliberately just a contentDescription,
                // NOT a liveRegion -- this value updates continuously during
                // playback, and an automatic live-region announcement on
                // every tick would be a disruptive, unstoppable stream of
                // TalkBack speech. On-demand reading (swipe-to-focus) is the
                // right interaction model for a continuously-changing value
                // like this.
                modifier = Modifier
                    .semantics { contentDescription = "Playback position " + formatPosition(currentFrame, sampleRate) }
                    .testTag("position_text")
            )
            Spacer(Modifier.weight(1f))
            if (onOpenLibrary != null) {
                Button(onClick = onOpenLibrary, modifier = Modifier.testTag("open_library_button")) {
                    Text("Loop Library")
                }
            }
        }
    }
}

// Tier 3: widened to internal -- shared by PlaybackControlBar/
// RecordAffordance here AND GridScreen.kt's reuse of both.
internal fun formatPosition(frame: Long, sampleRate: Int): String {
    if (sampleRate <= 0) return "0:00"
    val totalSeconds = frame / sampleRate.toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Per-track record affordance (design item 10): a "Record" label when
 * idle, or a pulsing red dot + live elapsed time (driven by
 * [ArrangementUiState.recordedFrameCount], polled the same way the
 * timeline's playhead already polls [ArrangementUiState.currentFrame])
 * while this track is the one currently recording. Stays visible but
 * disabled (rather than hidden) while a DIFFERENT track is recording, so
 * the user can see why -- the native engine supports only one recording at
 * a time.
 */
// Tier 3: widened to internal so GridScreen.kt can reuse this exact,
// already-accessibility-audited (Post-v1 A4) affordance for the focused
// track's own Record control, rather than re-authoring it.
@Composable
internal fun RecordAffordance(
    trackSlot: Int,
    isRecording: Boolean,
    isDisabled: Boolean,
    recordedFrameCount: Long,
    sampleRate: Int,
    onRecordTap: () -> Unit,
    onStopRecordTap: () -> Unit
) {
    if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(label = "record_pulse")
        val dotAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "record_pulse_alpha"
        )
        Row(
            // Post-v1 audit A4 (accessibility): the exact "icon-only
            // affordance... currently unreadable by TalkBack" case the
            // backlog names directly -- a pulsing colored dot plus a bare
            // elapsed-time number conveyed NOTHING to a screen reader about
            // what this control is or does. contentDescription is
            // deliberately static (not built from the live-updating
            // recordedFrameCount below) -- embedding a continuously-changing
            // value here risks a spammy re-announcement stream; the numeric
            // timer stays a sighted-only supplementary detail.
            Modifier
                .testTag("stop_record_button_$trackSlot")
                .clickable(role = Role.Button) { onStopRecordTap() }
                .semantics { contentDescription = "Recording. Double tap to stop." },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = dotAlpha))
                    .clearAndSetSemantics {}
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = formatPosition(recordedFrameCount, sampleRate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                // Sighted-only detail -- excluded (not merged) so the
                // Row's own static contentDescription above is the ENTIRE
                // accessible content of this control, unaffected by this
                // text changing many times per second while recording.
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    } else {
        Text(
            text = "Record",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDisabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
            modifier = Modifier
                .testTag("record_button_$trackSlot")
                .clickable(enabled = !isDisabled, role = Role.Button) { onRecordTap() }
                // Post-v1 audit A4: Compose already marks a disabled
                // clickable as unavailable to TalkBack automatically, but
                // gives no reason -- a sighted user infers it from the
                // dimmed color plus another track's visible pulsing dot
                // elsewhere on screen, neither of which a screen reader
                // user has access to. stateDescription (not a full
                // contentDescription override) layers the reason on top of
                // the existing "Record" name, matching the SAME "disabled,
                // here's why" pattern PlaybackControlBar's Play/Pause button
                // uses above -- found during this audit's adversarial-review
                // pass as an inconsistency worth unifying.
                .then(
                    if (isDisabled) {
                        Modifier.semantics {
                            stateDescription = "Unavailable -- another track is currently recording"
                        }
                    } else {
                        Modifier
                    }
                )
        )
    }
}
