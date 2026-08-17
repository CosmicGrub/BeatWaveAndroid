package com.beatwave.android.ui.arrangement

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.BeatWaveApplication
import com.beatwave.android.MainActivity
import com.beatwave.android.diagnostics.CrashLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the post-v1 audit A2 "Crash Logs" diagnostics
 * sheet -- the UI/share half of crash resilience. Uses the exact SAME
 * production [CrashLogger] instance [BeatWaveApplication.onCreate] installs
 * (via `(context.applicationContext as BeatWaveApplication).crashLogger`),
 * not a fake/reimplemented one, so a "planted" report here is written by the
 * real code path [ArrangementViewModel.openCrashLogs] reads from -- only
 * substituting "a crash already happened" for an actual uncaught exception.
 *
 * A REAL uncaught exception (the [Thread.setDefaultUncaughtExceptionHandler]
 * wiring [CrashLogger.install] itself performs) is deliberately NOT exercised
 * here: on a real device, letting one propagate chains through to Android's
 * own default handler, which kills the whole process -- including this
 * instrumentation's own process, since androidTest normally shares the
 * app's process. That would make this test self-destructing and unsafe to
 * run alongside the rest of the regression suite (the same reasoning this
 * codebase already applies when choosing manual over automated verification
 * for a disproportionately expensive/risky interaction -- see this project's
 * memory finding #8). That full real-crash -> process-death -> app-restart
 * -> log-retained -> shareable-via-UI path was instead verified by hand for
 * the A2 commit: a genuine uncaught exception was triggered on-device via a
 * throwaway instrumented test, confirmed via `adb shell run-as` to have
 * written a real crash_*.txt file, then the app was relaunched fresh and the
 * SAME log was confirmed visible and shareable through this exact UI.
 */
@RunWith(AndroidJUnit4::class)
class CrashLogsUiTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val crashLogger: CrashLogger
        get() = (context.applicationContext as BeatWaveApplication).crashLogger

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetState() {
        // A prior test run (or a genuine crash from unrelated manual
        // verification) could leave real logs on disk -- clear them so this
        // test's assertions are about exactly the log IT plants.
        crashLogger.listLogs().forEach { it.delete() }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        crashLogger.listLogs().forEach { it.delete() }
        Thread.sleep(TEARDOWN_SETTLE_MS)
    }

    @Test
    fun crashLogsSheet_listsARealPlantedLog_andSharingItFiresTheShareIntent() {
        val planted = crashLogger.logCrash(
            Thread.currentThread(),
            RuntimeException("Deliberate test crash for A2 UI verification")
        )
        assertTrue("expected the planted crash report to exist on disk", planted.exists())

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("crash_logs_button").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("crash_logs_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = SHEET_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("crash_log_row_${planted.name}").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("share_crash_log_${planted.name}").performClick()

        // Same "trust our own state, not the foreign chooser window" pattern
        // ExportShareTest already established: pendingShareCrashLogPath
        // returning to null is real, direct proof the ACTION_SEND +
        // FileProvider Uri (backed by res/xml/file_paths.xml's
        // files-path/name="crash_logs" entry) reached Android's Intent
        // system without throwing.
        var viewModel: ArrangementViewModel? = null
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ArrangementViewModel::class.java]
        }
        val deadline = System.currentTimeMillis() + SHARE_CONSUMED_TIMEOUT_MS
        while (viewModel!!.uiState.value.pendingShareCrashLogPath != null &&
            System.currentTimeMillis() < deadline
        ) {
            composeTestRule.waitForIdle()
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(
            "expected pendingShareCrashLogPath to be consumed (share Intent fired)",
            null, viewModel!!.uiState.value.pendingShareCrashLogPath
        )
        // Best-effort: dismiss whatever the share Intent opened, mirroring
        // ExportShareTest's own teardown-safety helper call.
        shell("input keyevent KEYCODE_BACK")
        Thread.sleep(CHOOSER_APPEAR_SETTLE_MS)
    }

    @Test
    fun crashLogsSheet_showsEmptyState_whenNoLogsExist() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitUntil(timeoutMillis = INIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("crash_logs_button").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("crash_logs_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = SHEET_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("crash_logs_empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Runs a shell command via the instrumentation's UiAutomation -- mirrors
     *  [ExportShareTest]'s own identical helper. */
    private fun shell(command: String): String {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val SHEET_TIMEOUT_MS = 8_000L
        private const val SHARE_CONSUMED_TIMEOUT_MS = 8_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val CHOOSER_APPEAR_SETTLE_MS = 1_000L
        private const val TEARDOWN_SETTLE_MS = 200L
    }
}
