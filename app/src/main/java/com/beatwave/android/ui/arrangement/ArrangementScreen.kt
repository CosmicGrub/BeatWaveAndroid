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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
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

private val HEADER_WIDTH: Dp = 84.dp

// Phase 2 (Fold branch) finding: 72dp fit the unselected 2-line header
// ("Track N" + Record) comfortably, but confirmed on-device to clip the
// 3rd line ("Selected") that appears when a track is actually selected --
// the exact state a real interaction check (not just a static layout
// screenshot) surfaces immediately. Not Fold-specific: HEADER_WIDTH/
// TRACK_ROW_HEIGHT are fixed dp constants shared by every device, so this
// was already clipping everywhere selection was checked closely; simply
// never caught before this pass. 80dp gives the 3-line case real headroom
// while the extra 8dp × 8 tracks is negligible against any of this
// project's real screens' height.
private val TRACK_ROW_HEIGHT: Dp = 80.dp
private val PIXELS_PER_GRID_UNIT: Dp = 12.dp
private const val MIN_TIMELINE_GRID_UNITS = 128

// Device-adaptive layouts (2026-08-18 spec), Phase 0: how much of a
// medium/expanded window the persistent Loop Library panel claims. Applies
// identically regardless of which device/orientation triggered the
// two-pane layout.
private const val LOOP_LIBRARY_PANEL_WEIGHT = 0.34f

// Phase 2 (Fold branch) finding: a device with less absolute width in its
// Medium/Expanded window than the Tab (e.g. the Fold 5 unfolded, ~690dp,
// versus the Tab's ~823dp) can end up with a panel too narrow for its own
// content even at the SAME weight above -- confirmed on-device: "Import
// from device" wrapped to two lines on the Fold at the weight-only width
// (~235dp) but not on the Tab (~280dp). Rather than forking a second,
// per-device weight constant, floor the panel's absolute width at
// whatever's already verified to look right on the Tab, so any device
// gets at least that much room; the weight above still governs on wider
// windows where it naturally produces more than this floor.
private val LOOP_LIBRARY_PANEL_MIN_WIDTH: Dp = 280.dp

/**
 * Main arrangement screen: fixed 8-track vertical list, each with a
 * horizontally scrollable timeline of loop blocks (all tracks share one
 * scroll position), a ruler for tap-to-seek, and playback controls in the
 * bottom bar. The loop library and per-block editor are surfaced as modal
 * overlays driven by [ArrangementViewModel]'s state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangementScreen(
    viewModel: ArrangementViewModel = viewModel(),
    /** Phase 7: an audio [Uri] shared INTO BeatWave from another app via
     *  ACTION_SEND (see [com.beatwave.android.MainActivity]'s intent
     *  handling), or null if the app was launched normally. Passed down as
     *  plain state rather than this Composable reading the Activity's Intent
     *  itself, so the same one LaunchedEffect(key) below handles both a cold
     *  launch (onCreate) and an already-running app receiving a new share
     *  (onNewIntent) identically. */
    incomingShareUri: Uri? = null,
    /** Called once [incomingShareUri] has been handed to
     *  [ArrangementViewModel.importAudioFromUri] below, so the caller
     *  (MainActivity) can clear its own state and not re-import the same
     *  Uri again on the next recomposition. */
    onIncomingShareUriConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLibrary by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Device-adaptive layouts (2026-08-18 spec), Phase 0: computed fresh
    // from LocalConfiguration every recomposition, so this is a genuinely
    // live, runtime switch -- it responds correctly to a fold/unfold,
    // rotation, or DeX window resize while the app is running, not just at
    // launch. Compact (the default on every phone-sized device, including
    // the Fold 5's cover screen) keeps today's existing single-column
    // layout unchanged below; Medium/Expanded switches to a two-pane
    // layout with a persistent Loop Library panel.
    val configuration = LocalConfiguration.current
    val windowSizeClass = WindowSizeClass(
        configuration.screenWidthDp.toFloat(),
        configuration.screenHeightDp.toFloat()
    )
    val isTwoPane = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    // null in two-pane mode: PlaybackControlBar hides the "Loop Library"
    // button entirely when this is null, since the persistent panel is
    // already always visible there.
    val onOpenLibraryAction: (() -> Unit)? = if (isTwoPane) null else ({ showLibrary = true })

    // RECORD_AUDIO runtime permission flow (design item 9): requested
    // lazily, only when the user first taps a track's Record button --
    // never proactively at app launch. pendingRecordTrackSlot remembers
    // WHICH track triggered the request so the launcher's callback (which
    // only receives a Boolean) knows where to route the result.
    var pendingRecordTrackSlot by remember { mutableStateOf<Int?>(null) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val trackSlot = pendingRecordTrackSlot
        pendingRecordTrackSlot = null
        if (trackSlot != null) {
            if (granted) viewModel.startRecording(trackSlot) else viewModel.recordingPermissionDenied()
        }
    }
    val onRecordTap: (Int) -> Unit = { trackSlot ->
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            viewModel.startRecording(trackSlot)
        } else {
            pendingRecordTrackSlot = trackSlot
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // POST_NOTIFICATIONS runtime permission flow (Phase 6 design item 4):
    // requested lazily, only the first time playback actually starts --
    // never proactively at app launch -- mirroring the RECORD_AUDIO pattern
    // above exactly. Denial is handled gracefully: the foreground service
    // and playback itself are unaffected (see BeatWavePlaybackService), only
    // the lock-screen/notification surface won't be visible, and the user is
    // told this once via the existing Snackbar-style message mechanism
    // rather than being blocked or re-prompted on every subsequent Play tap.
    var hasRequestedNotificationPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.notificationPermissionDenied()
        }
    }
    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying &&
            !hasRequestedNotificationPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            hasRequestedNotificationPermission = true
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.messageShown()
        }
    }

    // Phase 7, receive side: reuses the EXACT SAME SAF import pipeline
    // (importAudioFromUri -> pendingImport -> CategoryPickerDialog) a
    // device-picked file already goes through -- see importAudioFromUri's
    // own doc comment. Keyed on the Uri itself (not just non-null) so a
    // second distinct share while the first is still mid-decode still
    // triggers correctly.
    LaunchedEffect(incomingShareUri) {
        if (incomingShareUri != null) {
            viewModel.importAudioFromUri(incomingShareUri)
            onIncomingShareUriConsumed()
        }
    }

    // Phase 7, send side: fires the moment ArrangementViewModel.exportProject
    // finishes rendering. FileProvider (not a raw file:// Uri, blocked by
    // FileUriExposedException on API 24+) grants the receiving app temporary
    // read access to the exported WAV under cacheDir/exports/ -- see the
    // manifest's <provider> entry and res/xml/file_paths.xml for the
    // matching cache-path declaration.
    LaunchedEffect(uiState.pendingShareFilePath) {
        val path = uiState.pendingShareFilePath
        if (path != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share BeatWave project"))
            viewModel.shareFileConsumed()
        }
    }

    // Post-v1 audit A2: same FileProvider-backed share flow as export above,
    // just for a plain-text crash report (res/xml/file_paths.xml's
    // files-path/name="crash_logs" entry is what makes filesDir/crash_logs/
    // shareable this way) instead of a WAV under cacheDir/exports/.
    LaunchedEffect(uiState.pendingShareCrashLogPath) {
        val path = uiState.pendingShareCrashLogPath
        if (path != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share crash log"))
            viewModel.crashLogShareConsumed()
        }
    }

    val project = uiState.project

    Scaffold(
        topBar = {
            TopAppBar(
                // Multiple projects: the current project's name doubles as
                // the picker's entry point (tap to switch/rename/delete/
                // create) -- avoids cramming a second button into an already
                // tight `actions` row (Export) on this project's small-screen
                // test device, and matches a common "tap the doc title to
                // switch documents" pattern.
                title = {
                    Text(
                        project?.name ?: "BeatWave",
                        // Post-v1 audit A4 (accessibility): plain clickable
                        // Text gets a click action for free, but with no
                        // role/label a screen reader has no way to discover
                        // this is a button, let alone what it does --
                        // contentDescription overrides the bare project name
                        // with an explanation of the action too.
                        modifier = Modifier
                            .clickable(onClickLabel = "Switch project", role = Role.Button) {
                                viewModel.openProjectPicker()
                            }
                            .semantics {
                                contentDescription =
                                    "Project: ${project?.name ?: "BeatWave"}. Double tap to switch, rename, or delete projects."
                            }
                            .testTag("project_picker_open_button")
                    )
                },
                actions = {
                    // Post-v1 audit A2: a small, always-visible entry point
                    // to the crash-log diagnostics sheet -- same "always
                    // shown regardless of whether there's anything to act
                    // on yet" precedent as Export (which is shown even with
                    // an empty timeline; CrashLogsSheet shows an empty-state
                    // message the same way).
                    TextButton(
                        onClick = viewModel::openCrashLogs,
                        modifier = Modifier
                            .semantics { contentDescription = "View crash logs" }
                            .testTag("crash_logs_button")
                    ) {
                        Text("Logs")
                    }
                    if (uiState.isExporting) {
                        // Post-v1 audit A4: without this, the Export button
                        // simply vanishes (replaced by a bare spinner) with
                        // no announcement -- a screen reader user gets no
                        // confirmation an export even started.
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(horizontal = 16.dp)
                                .semantics { contentDescription = "Exporting project" },
                            strokeWidth = 2.dp
                        )
                    } else {
                        OutlinedButton(
                            onClick = viewModel::exportProject,
                            modifier = Modifier.padding(horizontal = 8.dp).testTag("export_button")
                        ) {
                            Text("Export")
                        }
                    }
                }
            )
        },
        bottomBar = {
            PlaybackControlBar(
                isPlaying = uiState.isPlaying,
                isRecording = uiState.recordingTrackSlot != null,
                currentFrame = uiState.currentFrame,
                sampleRate = uiState.sampleRate,
                onTogglePlayPause = viewModel::togglePlayPause,
                onStop = viewModel::stopPlayback,
                // Device-adaptive layouts (2026-08-18 spec), Phase 0: null
                // in two-pane mode hides the button entirely rather than
                // leaving a dead/redundant control -- the Loop Library
                // panel is already always visible, so there's nothing left
                // for it to open.
                onOpenLibrary = onOpenLibraryAction
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (project == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading project" })
            }
        } else {
            val maxBlockEnd = project.tracks.flatMap { it.loopBlocks }
                .maxOfOrNull { it.startGridUnit + it.lengthGridUnits } ?: 0
            val totalGridUnits = maxOf(maxBlockEnd + GridConstants.GRID_UNITS_PER_BEAT * 4, MIN_TIMELINE_GRID_UNITS)
            val timelineWidthDp = PIXELS_PER_GRID_UNIT * totalGridUnits
            val framesPerGridUnit = if (uiState.sampleRate > 0) {
                GridConstants.framesPerGridUnit(project.bpm, uiState.sampleRate)
            } else 0.0
            val playheadGridUnitPosition = if (framesPerGridUnit > 0) {
                (uiState.currentFrame / framesPerGridUnit).toFloat()
            } else 0f

            // Device-adaptive layouts (2026-08-18 spec), Phase 0: identical
            // content in both layouts -- only what surrounds it (a bare Box
            // vs. a two-pane Row with a persistent library alongside it)
            // differs below. Keeping this as one shared lambda, rather than
            // writing it out twice, is exactly what avoids the two layouts'
            // arrangement/timeline logic drifting apart over time.
            val arrangementTimelineContent: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    TimelineRuler(
                        scrollState = scrollState,
                        timelineWidthDp = timelineWidthDp,
                        totalGridUnits = totalGridUnits,
                        onSeekGridUnit = viewModel::seekToGridUnit
                    )
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(project.tracks, key = { it.slot }) { track ->
                            TrackRow(
                                track = track,
                                samples = uiState.samples,
                                isSelected = uiState.selectedTrackSlot == track.slot,
                                isRecording = uiState.recordingTrackSlot == track.slot,
                                isAnotherTrackRecording = uiState.recordingTrackSlot != null &&
                                    uiState.recordingTrackSlot != track.slot,
                                recordedFrameCount = uiState.recordedFrameCount,
                                sampleRate = uiState.sampleRate,
                                scrollState = scrollState,
                                timelineWidthDp = timelineWidthDp,
                                playheadGridUnitPosition = playheadGridUnitPosition,
                                onSelectTrack = { viewModel.selectTrack(track.slot) },
                                onBlockTap = { blockId -> viewModel.openBlockEditor(track.slot, blockId) },
                                onRecordTap = { onRecordTap(track.slot) },
                                onStopRecordTap = { viewModel.stopRecording() }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (isTwoPane) {
                // See LOOP_LIBRARY_PANEL_MIN_WIDTH above: floor the panel's
                // absolute width rather than relying on the weight alone,
                // so a narrower-dp device (Fold unfolded) doesn't get a
                // skinnier panel than a wider one (Tab) at the same ratio.
                val panelWidth = maxOf(
                    LOOP_LIBRARY_PANEL_MIN_WIDTH,
                    (configuration.screenWidthDp * LOOP_LIBRARY_PANEL_WEIGHT).dp
                )
                Row(Modifier.fillMaxSize().padding(padding)) {
                    Surface(
                        modifier = Modifier.fillMaxHeight().width(panelWidth),
                        tonalElevation = 1.dp
                    ) {
                        LoopLibraryPanel(
                            samples = uiState.sampleList,
                            selectedTrackSlot = uiState.selectedTrackSlot,
                            onPreview = viewModel::previewSample,
                            onAdd = { sample -> viewModel.addLoopToSelectedTrack(sample) },
                            onImport = { uri -> viewModel.importAudioFromUri(uri) }
                        )
                    }
                    // The only weighted child left in this Row -- fills
                    // whatever width panelWidth above didn't claim.
                    Box(Modifier.fillMaxHeight().weight(1f)) {
                        arrangementTimelineContent()
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    arrangementTimelineContent()
                }
            }
        }
    }

    // Device-adaptive layouts (2026-08-18 spec), Phase 0: the sheet is
    // redundant once the persistent LoopLibraryPanel is already always
    // visible in the two-pane layout above -- showLibrary can still flip
    // true (PlaybackControlBar's own "Loop Library" button is hidden in
    // two-pane mode below, but nothing stops it being true already from a
    // window resize that happened while the sheet was open), so this is a
    // real guard, not just a redundant optimization.
    if (showLibrary && !isTwoPane) {
        LoopLibraryBottomSheet(
            samples = uiState.sampleList,
            selectedTrackSlot = uiState.selectedTrackSlot,
            onDismiss = { showLibrary = false },
            onPreview = viewModel::previewSample,
            onAdd = { sample -> viewModel.addLoopToSelectedTrack(sample) },
            onImport = { uri -> viewModel.importAudioFromUri(uri) }
        )
    }

    if (uiState.showProjectPicker) {
        ProjectPickerSheet(
            projects = uiState.projectSummaries,
            activeProjectId = project?.id,
            onDismiss = viewModel::closeProjectPicker,
            onOpen = viewModel::switchToProject,
            onCreate = viewModel::createNewProject,
            onRename = viewModel::renameProject,
            onDelete = viewModel::deleteProject
        )
    }

    if (uiState.showCrashLogs) {
        CrashLogsSheet(
            logs = uiState.crashLogSummaries,
            onDismiss = viewModel::closeCrashLogs,
            onShare = viewModel::shareCrashLog
        )
    }

    // Only one of these two pending-category prompts can be meaningfully
    // shown at once (an import and a recording can't both be mid-flow from
    // a single tap), but guard with else-if regardless so two AlertDialogs
    // never stack in the unlikely event both are non-null simultaneously.
    val pendingImport = uiState.pendingImport
    val pendingRecording = uiState.pendingRecording
    if (pendingImport != null) {
        CategoryPickerDialog(
            fileName = pendingImport.displayName,
            onDismiss = viewModel::cancelPendingImport,
            onConfirm = { category -> viewModel.confirmPendingImport(category) }
        )
    } else if (pendingRecording != null) {
        CategoryPickerDialog(
            fileName = pendingRecording.displayName,
            onDismiss = viewModel::cancelPendingRecording,
            onConfirm = { category -> viewModel.confirmPendingRecording(category) }
        )
    }

    val editingRef = uiState.editingBlock
    if (editingRef != null && project != null) {
        val track = project.tracks.firstOrNull { it.slot == editingRef.trackSlot }
        val block = track?.loopBlocks?.firstOrNull { it.id == editingRef.blockId }
        val sample = block?.let { uiState.samples[it.sampleId] }
        if (block != null && sample != null) {
            LoopBlockEditorDialog(
                block = block,
                sample = sample,
                onDismiss = viewModel::closeBlockEditor,
                onSave = { volume, trimStart, trimEnd, pitch ->
                    viewModel.updateBlock(editingRef.trackSlot, editingRef.blockId, volume, trimStart, trimEnd, pitch)
                },
                onDelete = { viewModel.deleteBlock(editingRef.trackSlot, editingRef.blockId) }
            )
        }
    }
}

@Composable
private fun PlaybackControlBar(
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
    // Phase 2 (Fold branch) finding: on this device/OS configuration,
    // Android's large-screen system Taskbar (a persistent bottom dock,
    // distinct from the plain 3-button/gesture nav bar) overlapped this
    // bar's own Stop button and elapsed-time text -- confirmed via
    // `dumpsys window windows` showing a genuine TaskbarWindow, not an
    // app rendering bug. navigationBarsPadding() is the standard Compose
    // handling for exactly this class of bottom system chrome.
    Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
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
                // Phase 2 (Fold branch) finding: at the cover screen's own
                // narrow width (~344dp), this button was left with less
                // remaining Row space than "Loop Library" needs on one
                // line -- confirmed on-device: the button's own Text
                // wrapped across three lines, overlapping the position
                // text next to it. "Library" alone reads the same in
                // context (nothing else on this bar could be mistaken for
                // a library); tighter horizontal content padding than
                // Material3's default (24dp/side) reclaims real width
                // rather than fighting the squeeze with copy alone, and
                // maxLines/overflow stays as a backstop so an even
                // narrower future device degrades to an ellipsis instead
                // of a multi-line wrap either way.
                Button(
                    onClick = onOpenLibrary,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("open_library_button")
                ) {
                    Text("Library", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun formatPosition(frame: Long, sampleRate: Int): String {
    if (sampleRate <= 0) return "0:00"
    val totalSeconds = frame / sampleRate.toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun TimelineRuler(
    scrollState: androidx.compose.foundation.ScrollState,
    timelineWidthDp: Dp,
    totalGridUnits: Int,
    onSeekGridUnit: (Int) -> Unit
) {
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Spacer(Modifier.width(HEADER_WIDTH))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState)
        ) {
            Box(
                Modifier
                    .width(timelineWidthDp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val pxPerUnit = with(density) { PIXELS_PER_GRID_UNIT.toPx() }
                            val gridUnit = (offset.x / pxPerUnit).toInt().coerceAtLeast(0)
                            onSeekGridUnit(gridUnit)
                        }
                    }
                    // Post-v1 audit A4 (accessibility): a raw pointerInput/
                    // detectTapGestures never touches the semantics tree --
                    // unlike Modifier.clickable, it registers no
                    // accessibility click action at all, so before this the
                    // ruler was completely un-focusable and un-operable by
                    // TalkBack (not just unclear -- the exact "timeline
                    // ruler have none" gap the backlog names). Exact-pixel
                    // tap-to-seek isn't reproducible via TalkBack's touch
                    // exploration, so this offers two coarse but concrete
                    // anchor points instead: double-tap seeks to the start,
                    // and a custom action seeks to the end. The bar-number
                    // Text labels and decorative tick marks below are
                    // cleared (see clearAndSetSemantics on each) rather than
                    // merged in -- their per-pixel position isn't
                    // independently useful once seeking is anchor-based, and
                    // the current position is already available via
                    // PlaybackControlBar's own position text.
                    .semantics {
                        contentDescription = "Timeline ruler"
                        onClick(label = "Seek to start") {
                            onSeekGridUnit(0)
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Seek to end") {
                                onSeekGridUnit(totalGridUnits)
                                true
                            }
                        )
                    }
            ) {
                val beats = totalGridUnits / GridConstants.GRID_UNITS_PER_BEAT
                for (beat in 0..beats) {
                    val isBar = beat % 4 == 0
                    val xOffset = PIXELS_PER_GRID_UNIT * (beat * GridConstants.GRID_UNITS_PER_BEAT)
                    Box(
                        Modifier
                            .padding(start = xOffset)
                            .width(1.dp)
                            .fillMaxHeight(if (isBar) 1f else 0.5f)
                            .align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.outline)
                            .clearAndSetSemantics {}
                    )
                    if (isBar) {
                        Text(
                            text = "${(beat / 4) + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = xOffset + 2.dp).clearAndSetSemantics {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    samples: Map<String, Sample>,
    isSelected: Boolean,
    isRecording: Boolean,
    isAnotherTrackRecording: Boolean,
    recordedFrameCount: Long,
    sampleRate: Int,
    scrollState: androidx.compose.foundation.ScrollState,
    timelineWidthDp: Dp,
    playheadGridUnitPosition: Float,
    onSelectTrack: () -> Unit,
    onBlockTap: (String) -> Unit,
    onRecordTap: () -> Unit,
    onStopRecordTap: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(TRACK_ROW_HEIGHT)) {
        Column(
            Modifier
                .width(HEADER_WIDTH)
                .fillMaxHeight()
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // Deliberately scoped to JUST the label text (not the whole
            // header column) -- this used to wrap RecordAffordance too,
            // which meant a tap anywhere in this Column's bounds could land
            // on the nested Record hit-target instead of selecting the
            // track. TRACK_ROW_HEIGHT (72dp) centers this whole stack
            // vertically, so the geometric center of a full-height clickable
            // column sat right on/near the Record label below -- a real
            // on-device instrumented test caught this by driving an actual
            // tap at that computed center and landing on the Record button
            // (started an unintended recording) instead of selecting Track
            // 1. Splitting the clickable+testTag to only this label sub-
            // column removes the overlap for both real taps and tests,
            // without changing the visual layout at all.
            Column(
                // Post-v1 audit A4 (accessibility, the backlog's own
                // explicit top priority for this item): selection state was
                // previously conveyed only as an incidental side effect of
                // the conditionally-rendered "Selected" Text below getting
                // swept into clickable's default merge-descendants
                // announcement -- never a real `selected` semantics
                // property, so TalkBack couldn't use its own "selected"
                // announcement idiom and accessibility tooling couldn't
                // recognize this as a proper selectable item. Modifier.
                // selectable sets both the click action AND the selected
                // property together (and merges descendants), replacing the
                // plain clickable. Visible "Selected" text kept below for
                // sighted users, but the semantics property is now the
                // actual source of truth, not a side effect of it.
                Modifier
                    .testTag("track_header_${track.slot}")
                    .selectable(selected = isSelected, onClick = onSelectTrack, role = Role.Button)
            ) {
                Text("Track ${track.slot}", style = MaterialTheme.typography.labelMedium)
                if (isSelected) {
                    Text(
                        "Selected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        // Found during this audit's adversarial-review pass:
                        // Modifier.selectable's `selected` property already
                        // makes TalkBack announce "Selected" natively as a
                        // real state, independent of visible text -- leaving
                        // this Text's own semantics live meant it ALSO got
                        // pulled into the merged announcement (selectable
                        // merges descendants), so a selected track read as
                        // "Track 1, Selected... Selected" -- doubled.
                        // Purely a sighted-user affordance now.
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            RecordAffordance(
                trackSlot = track.slot,
                isRecording = isRecording,
                isDisabled = isAnotherTrackRecording,
                recordedFrameCount = recordedFrameCount,
                sampleRate = sampleRate,
                onRecordTap = onRecordTap,
                onStopRecordTap = onStopRecordTap
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState)
        ) {
            Box(Modifier.width(timelineWidthDp).fillMaxHeight()) {
                // Keyed by block.id (stable per placed instance, unlike the
                // sample id two blocks can share) so Compose doesn't
                // misattribute recomposition/animation state across blocks
                // when a track has two placements of the same sample.
                for (block in track.loopBlocks) {
                    key(block.id) {
                        BlockView(
                            trackSlot = track.slot,
                            block = block,
                            sample = samples[block.sampleId],
                            onTap = { onBlockTap(block.id) }
                        )
                    }
                }
                Box(
                    // Post-v1 audit A4: purely decorative -- the same
                    // playback position is already announced via
                    // PlaybackControlBar's own position Text, so this bar
                    // is explicitly excluded rather than left as an
                    // accidental (if currently harmless) semantics gap.
                    Modifier
                        .padding(start = PIXELS_PER_GRID_UNIT * playheadGridUnitPosition)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.error)
                        .clearAndSetSemantics {}
                )
            }
        }
    }
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
@Composable
private fun RecordAffordance(
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

@Composable
private fun BlockView(
    trackSlot: Int,
    block: LoopBlock,
    sample: Sample?,
    onTap: () -> Unit
) {
    val color = sample?.let { CategoryColors.forCategory(it.category) } ?: MaterialTheme.colorScheme.outline
    val widthDp = (PIXELS_PER_GRID_UNIT * block.lengthGridUnits).let { if (it < 4.dp) 4.dp else it }
    val startDp = PIXELS_PER_GRID_UNIT * block.startGridUnit
    // Post-v1 audit A4: a block's position/length on the timeline is
    // otherwise conveyed PURELY spatially (x-offset/width in pixels) --
    // exactly the "timeline/track-row structure... currently unreadable by
    // TalkBack" gap the backlog names. Mirrors TimelineRuler's own
    // beat-to-bar math (GRID_UNITS_PER_BEAT, 4 beats/bar) so a block's
    // announced position lines up with the ruler's own bar numbers.
    val startBar = block.startGridUnit / GridConstants.GRID_UNITS_PER_BEAT / 4 + 1
    // Found during this audit's adversarial-review pass: plain integer
    // division here floored to 0 for any block shorter than one full beat
    // (e.g. a short recorded one-shot, whose length is only floored to 1
    // grid unit, not a whole beat -- see GridConstants.lengthGridUnitsForFrameCount)
    // and announced a real, audible, visually-rendered block (BlockView
    // itself coerces a minimum on-screen width so it stays visible/tappable)
    // as "0 beats long". Rounding to the NEAREST beat, floored at 1, never
    // announces a nonsensical zero -- exact sub-beat precision isn't the
    // point of this description any more than exact pixel width is for a
    // sighted user glancing at the timeline.
    val lengthBeats = (block.lengthGridUnits.toDouble() / GridConstants.GRID_UNITS_PER_BEAT)
        .roundToInt()
        .coerceAtLeast(1)
    val blockDescription = buildString {
        append(sample?.name ?: "Unknown")
        if (sample != null) append(", ${sample.category} loop") else append(" loop")
        append(", starts at bar $startBar, $lengthBeats beat${if (lengthBeats == 1) "" else "s"} long")
    }

    Box(
        Modifier
            .padding(start = startDp, top = 6.dp, bottom = 6.dp)
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .testTag("loop_block_${trackSlot}_${block.sampleId}")
            .clickable(role = Role.Button) { onTap() }
            .semantics(mergeDescendants = true) { contentDescription = blockDescription }
            .padding(4.dp)
    ) {
        // Waveform-visualization upgrade: the block's TRIMMED region only
        // (what's actually audible), not the full sample -- unlike
        // LoopBlockEditorDialog's full-waveform-plus-highlight view.
        // Deliberately a simple stretch-to-fill-the-block-width rendering,
        // not tiled per loop repeat (a block can repeat its trimmed content
        // several times to fill its length): shows real waveform SHAPE at a
        // glance without the added complexity of computing exact
        // repeat-tile boundaries against grid/pitch math -- a v1 scope cut,
        // not an oversight. No-ops for a sample with no peaks yet (older
        // imports/recordings predating this upgrade) -- see WaveformView's
        // own doc comment.
        if (sample != null && sample.waveformPeaks.isNotEmpty()) {
            WaveformView(
                peaks = trimmedPeaksForBlock(sample, block),
                modifier = Modifier.matchParentSize(),
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        Text(
            text = sample?.name ?: "Unknown",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Found during this audit's adversarial-review pass: the parent
            // Box's explicit contentDescription (blockDescription, which
            // already includes this sample name) takes precedence over a
            // merged child's own text for TalkBack's announcement, so this
            // likely wasn't double-read in practice -- but every OTHER
            // redundant-child case in this same diff (RecordAffordance's
            // dot/timer, LoopLibraryCard, CrashLogsSheet) is explicit about
            // it via clearAndSetSemantics rather than relying on that
            // precedence behavior implicitly. Matches that same discipline
            // here for consistency.
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** The sub-range of [sample]'s full waveform peaks corresponding to
 *  [block]'s own trim window ([LoopBlock.trimStartMs]/[LoopBlock.trimEndMs]
 *  as a fraction of [Sample.durationMs]) -- what [BlockView] actually draws,
 *  since that's the region genuinely audible for this block. Falls back to
 *  the full peaks list if the computed sub-range is degenerate (e.g. a trim
 *  window narrower than one peak bucket). */
private fun trimmedPeaksForBlock(sample: Sample, block: LoopBlock): List<Float> {
    val peaks = sample.waveformPeaks
    if (peaks.isEmpty()) return peaks
    val maxDurationMs = sample.durationMs.toFloat().coerceAtLeast(1f)
    val trimStartFraction = (block.trimStartMs / maxDurationMs).coerceIn(0f, 1f)
    val trimEndFraction = ((block.trimEndMs?.toFloat() ?: sample.durationMs.toFloat()) / maxDurationMs).coerceIn(0f, 1f)
    val startIdx = (trimStartFraction * peaks.size).toInt().coerceIn(0, peaks.size)
    val endIdx = (trimEndFraction * peaks.size).toInt().coerceIn(startIdx, peaks.size)
    return if (endIdx > startIdx) peaks.subList(startIdx, endIdx) else peaks
}
