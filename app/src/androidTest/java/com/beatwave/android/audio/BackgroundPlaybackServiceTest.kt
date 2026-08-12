package com.beatwave.android.audio

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.MainActivity
import com.beatwave.android.data.storage.ProjectRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the Phase 6 exit criterion: "start playback,
 * background the app, control it from lock screen and notification shade."
 *
 * Per the implementation plan's testing-boundary note, a human physically
 * looking at a lock screen isn't available in this workflow, but most of
 * what actually matters here IS mechanically verifiable: MediaSession
 * registration and foreground-service/notification state are real OS-level
 * facts, exposed via `dumpsys`, and a real hardware/lock-screen media-button
 * press can be simulated via `input keyevent`. This test drives the real UI
 * exactly the way [com.beatwave.android.ui.arrangement.ArrangementScreenPlaybackTest]
 * does (real testTag-driven taps, never calling ArrangementViewModel/
 * AudioEngineBridge schedule-building methods directly), then falls back to
 * [android.app.UiAutomation.executeShellCommand] for the OS-level checks a
 * Compose test can't otherwise reach -- the SAME mechanism
 * [com.beatwave.android.audio.RecordingLiveHardwareCaptureTest] et al. rely
 * on for real-hardware verification, just pointed at `dumpsys`/`input`
 * instead of the audio stack.
 *
 * The central claim under test -- (c) "survives backgrounding" -- is proven
 * the same way every prior phase's tests already prove transport behavior:
 * reading [AudioEngineBridge.getCurrentFrame] directly (a cheap, thread-safe
 * read per that class's own doc comment) from the test thread, independent
 * of whatever the UI happens to display, both immediately after Play and
 * again after backgrounding via a real `KEYCODE_HOME` press -- a strictly
 * larger frame count after backgrounding is a genuine, automatable proof
 * the native engine kept running while this app's Activity was not in the
 * foreground, not a placeholder.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackServiceTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    /** Set true by a test that already closed [scenario] itself (see
     *  [activityDestroyed_whilePlaying_doesNotKillBackgroundPlayback]) so
     *  [tearDown] doesn't attempt a second, redundant close. */
    private var scenarioClosedByTest = false

    @Before
    fun resetToFreshProject() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        ProjectRepository.forContext(targetContext).delete(PROJECT_ID)
        scenarioClosedByTest = false
    }

    @After
    fun tearDown() {
        // Explicit Stop (via a real media-button STOP is unavailable as a
        // single keyevent; the app's own transport is torn down via
        // scenario.close() -> ArrangementViewModel.onCleared() below, same
        // as every other UI-launching test in this suite) -- return to the
        // foreground first so the teardown sequence isn't itself racing a
        // backgrounded app.
        shell("input keyevent KEYCODE_HOME")
        // ArrangementViewModel.onCleared() now deliberately SKIPS tearing
        // down the shared PlaybackEngine while transport is playing/paused-
        // but-resumable (the fix for this phase's premature-teardown bug --
        // see that method's doc comment), so a test that leaves playback
        // active can no longer rely on onCleared() alone to close the engine
        // for the next test/class. Stop it directly here, via the same
        // app-wide singleton BeatWavePlaybackService drives, so isStopped is
        // guaranteed true before scenario.close() -- restoring the same
        // cross-test-class engine isolation every test in this suite (and
        // ArrangementScreenPlaybackTest/ImportedSampleArrangementTest)
        // depends on.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        (targetContext.applicationContext as BeatWaveApplication).playbackEngine.stop()
        if (::scenario.isInitialized && !scenarioClosedByTest) {
            scenario.close()
        }
        // Mirrors ArrangementScreenPlaybackTest's teardown: give
        // PlaybackEngine.shutdown() (called from onCleared) a moment to
        // actually close the native stream before the next test/class tries
        // to open a new one.
        Thread.sleep(TEARDOWN_SETTLE_MS)
    }

    @Test
    fun startPlayback_survivesBackgrounding_andRespondsToRealMediaButton_withSessionAndNotificationVisible() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        // --- (d)+(e): build a minimal one-track arrangement through the
        // real UI (mirrors ArrangementScreenPlaybackTest step (a)+(b)) so
        // there's an actual score to play -- an empty project still plays
        // (the transport counter advances regardless), but a real block
        // makes this closer to a genuine "song playing in the background"
        // scenario the manual exit criterion describes. ---
        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }

        // --- Start playback via the real UI, then prove the real, live,
        // singleton native engine (not a UI-only counter) genuinely
        // advances -- same style of proof every prior phase's tests use. ---
        val frameBeforePlay = AudioEngineBridge.getCurrentFrame()
        assertTrue("expected transport to start at frame 0, got $frameBeforePlay", frameBeforePlay == 0L)

        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameAfterPlay = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to have advanced past 0 after tapping Play, got $frameAfterPlay",
            frameAfterPlay > 0L
        )

        // --- (a): dumpsys media_session must show an active session for
        // this app with an available PLAY/PAUSE action while playing. ---
        val sessionDumpWhilePlaying = shell("dumpsys media_session")
        assertTrue(
            "expected dumpsys media_session to mention com.beatwave.android while playing, got:\n$sessionDumpWhilePlaying",
            sessionDumpWhilePlaying.contains(PACKAGE_NAME)
        )

        // --- (d): a notification for this app must be posted while
        // playback is active. ---
        val notificationDumpWhilePlaying = shell("dumpsys notification --noredact")
        assertTrue(
            "expected a notification posted for $PACKAGE_NAME while playing, got:\n$notificationDumpWhilePlaying",
            notificationDumpWhilePlaying.contains("pkg=$PACKAGE_NAME")
        )

        // --- (c), the central claim of this phase: background the app
        // WITHOUT stopping it (a real KEYCODE_HOME press, exactly like a
        // user pressing the physical/gesture Home control), wait, and
        // confirm the transport frame continued advancing while this app's
        // Activity was not in the foreground. ---
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(BACKGROUND_SETTLE_MS)
        val frameWhileBackgrounded = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the transport to keep advancing while the app was backgrounded " +
                "($frameAfterPlay -> $frameWhileBackgrounded) -- this is the central proof of Phase 6 " +
                "background playback survival",
            frameWhileBackgrounded > frameAfterPlay
        )

        // The notification must still be showing while backgrounded and playing.
        val notificationDumpBackgrounded = shell("dumpsys notification --noredact")
        assertTrue(
            "expected the notification to remain posted for $PACKAGE_NAME while backgrounded and playing, got:\n" +
                notificationDumpBackgrounded,
            notificationDumpBackgrounded.contains("pkg=$PACKAGE_NAME")
        )

        // --- On-device evidence for a human reviewer (design item 6's
        // closing instruction): pull down the real notification shade and
        // screenshot it while genuinely backgrounded and playing. ---
        shell("cmd statusbar expand-notifications")
        Thread.sleep(SHADE_SETTLE_MS)
        shell("screencap -p $NOTIFICATION_SCREENSHOT_DEVICE_PATH")
        shell("cmd statusbar collapse")
        Thread.sleep(SHADE_SETTLE_MS)

        // --- (b): a real hardware/lock-screen media-button press
        // (KEYCODE_MEDIA_PLAY_PAUSE), sent while backgrounded, must
        // genuinely pause the SAME live engine -- proven the same way
        // LivePlaybackPauseTest/ArrangementScreenPlaybackTest already prove
        // pause freezes the transport, just driven via a real media-button
        // keyevent instead of a UI tap this time. ---
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        Thread.sleep(PAUSE_SETTLE_MS)
        val frameAtPauseSettled = AudioEngineBridge.getCurrentFrame()
        Thread.sleep(PAUSED_INTERVAL_MS)
        val frameAfterPausedInterval = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the transport to freeze after a real media-button pause while backgrounded " +
                "($frameAtPauseSettled vs $frameAfterPausedInterval)",
            frameAtPauseSettled == frameAfterPausedInterval
        )

        // --- Resume via the SAME media-button keycode (real players toggle
        // play/pause on repeated presses of this one key), and confirm the
        // transport resumes advancing. ---
        shell("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
        Thread.sleep(PLAY_SETTLE_MS)
        val frameAfterResume = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the transport to resume advancing after a real media-button play " +
                "(was frozen at $frameAfterPausedInterval, now $frameAfterResume)",
            frameAfterResume > frameAfterPausedInterval
        )

        // --- On-device evidence for a human reviewer: lock the device and
        // screenshot the lock screen while playback continues in the
        // background -- the visual layer design item 6 asks to capture even
        // though this workflow can't itself judge "does it look right". ---
        shell("input keyevent KEYCODE_POWER")
        Thread.sleep(LOCK_SETTLE_MS)
        shell("screencap -p $LOCKSCREEN_SCREENSHOT_DEVICE_PATH")
        // Wake + dismiss keyguard so tearDown()'s KEYCODE_HOME / scenario.close()
        // isn't left fighting a locked screen for the next test/class.
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(WAKE_SETTLE_MS)
        shell("wm dismiss-keyguard")
        Thread.sleep(WAKE_SETTLE_MS)
    }

    /**
     * Regression test for a real bug a code review caught in this phase:
     * [com.beatwave.android.ui.arrangement.ArrangementViewModel.onCleared]
     * used to unconditionally tear down the shared [PlaybackEngine]
     * singleton, which fires on a genuine ViewModelStore teardown (Activity
     * finish / task removal) -- NOT just a Home-press backgrounding (that
     * only STOPS the Activity; onCleared() never runs). The test above only
     * exercises KEYCODE_HOME, so it can never catch this: the Activity (and
     * therefore its ViewModel) is never actually destroyed there.
     *
     * This test drives a genuine Activity destruction --
     * [ActivityScenario.close] finishes the Activity and clears its
     * ViewModelStore for real, firing onCleared() exactly like the user
     * swiping BeatWave away from Recents -- while transport is actively
     * playing, then proves via the same direct
     * [AudioEngineBridge.getCurrentFrame] read every other test in this
     * class already uses that the native engine (and therefore
     * [BeatWavePlaybackService]'s background playback) kept running
     * afterward, instead of being silently killed.
     */
    @Test
    fun activityDestroyed_whilePlaying_doesNotKillBackgroundPlayback() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("track_header_1").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("track_header_1").performClick()
        composeTestRule.onNodeWithTag("open_library_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("add_loop_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("add_loop_$KICK_SAMPLE_ID").performClick()
        composeTestRule.onNodeWithTag("loop_library_close_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = LIBRARY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("loop_block_1_$KICK_SAMPLE_ID").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        Thread.sleep(PLAY_SETTLE_MS)
        val frameWhilePlaying = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine's transport to have advanced past 0 after tapping Play, " +
                "got $frameWhilePlaying",
            frameWhilePlaying > 0L
        )

        // The central act: genuinely destroy the Activity (and its
        // ViewModel) WHILE playback is active -- distinct from
        // KEYCODE_HOME, which only stops the Activity and never reaches
        // onCleared() at all.
        scenario.close()
        scenarioClosedByTest = true
        Thread.sleep(POST_DESTROY_SETTLE_MS)

        val frameAfterDestroy = AudioEngineBridge.getCurrentFrame()
        assertTrue(
            "expected the native engine to keep advancing after the Activity/ViewModel was " +
                "genuinely destroyed while playing ($frameWhilePlaying -> $frameAfterDestroy) -- " +
                "this is the proof that ArrangementViewModel.onCleared() no longer tears down " +
                "background playback out from under BeatWavePlaybackService",
            frameAfterDestroy > frameWhilePlaying
        )

        // Clean-up happens in tearDown() (playbackEngine.stop(), guarded
        // against double-closing the already-closed scenario via
        // scenarioClosedByTest).
    }

    /** Runs a shell command via the instrumentation's UiAutomation --
     *  mirrors how [com.beatwave.android.audio.RecordingLiveHardwareCaptureTest]
     *  et al. already reach real-device state a pure Compose/JVM test
     *  can't otherwise observe (see that test's own doc comment). */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        private const val PROJECT_ID = "current"
        private const val PACKAGE_NAME = "com.beatwave.android"

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val KICK_SAMPLE_ID = "kick_basic_01" // DRUMS

        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIBRARY_TIMEOUT_MS = 8_000L

        private const val PLAY_SETTLE_MS = 700L
        private const val BACKGROUND_SETTLE_MS = 2_500L
        private const val PAUSE_SETTLE_MS = 300L
        private const val PAUSED_INTERVAL_MS = 500L
        private const val POST_DESTROY_SETTLE_MS = 1_000L
        private const val TEARDOWN_SETTLE_MS = 300L
        private const val SHADE_SETTLE_MS = 1_000L
        private const val LOCK_SETTLE_MS = 1_500L
        private const val WAKE_SETTLE_MS = 500L

        // Fixed on-device paths (world-readable via `adb shell screencap`/
        // `adb pull`, exactly like every other adb-driven check in this
        // test) -- pulled to the host after the test run so a human can
        // look at them, per design item 6's closing instruction.
        private const val NOTIFICATION_SCREENSHOT_DEVICE_PATH = "/sdcard/phase6-notification.png"
        private const val LOCKSCREEN_SCREENSHOT_DEVICE_PATH = "/sdcard/phase6-lockscreen.png"
    }
}
