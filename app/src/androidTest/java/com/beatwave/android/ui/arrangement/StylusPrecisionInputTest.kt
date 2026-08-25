package com.beatwave.android.ui.arrangement

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3 (S-Pen precision input, 2026-08-18 device-adaptive-layouts spec)
 * exit-criterion verification: proves the ACTUAL production
 * `observeStylusPrecisionInput` modifier (`LoopBlockEditor.kt`, the exact
 * function [StylusPrecisionSlider]/[StylusPrecisionRangeSlider] use)
 * correctly reads a real [android.view.MotionEvent]'s tool type and
 * pressure -- reporting `null` for an ordinary finger touch and the real
 * (coerced 0f..1f) pressure for a stylus contact -- rather than just
 * compiling/branching correctly in isolation.
 *
 * Deliberately targets the modifier directly via a minimal, otherwise-inert
 * `Box`, rather than driving a real Material3 `Slider`'s own drag gesture:
 * `Slider` has its own internal gesture-recognition state machine (touch
 * slop, snapping, drag thresholds) that isn't part of what this feature
 * actually adds -- fighting it to get a precise, reproducible pixel-to-
 * value mapping turned out to be genuinely flaky across dispatch-timing
 * variations during development of this test, for reasons unrelated to
 * whether the precision-input mechanism itself works. The delta-scaling
 * MATH this modifier's pressure feeds into is separately, thoroughly
 * covered by [StylusPrecisionRatioTest] (a plain JVM test, no device
 * needed); what genuinely needs real on-device proof is specifically
 * "does Compose correctly translate a real MotionEvent's tool type/
 * pressure the way this code assumes" -- which is exactly what a minimal,
 * uncontested pointerInput host can answer far more reliably than
 * reverse-engineering Slider's internal drag state.
 *
 * Dispatches real [MotionEvent]s directly to the Activity's decor view via
 * [View.dispatchTouchEvent] -- Compose's own
 * `ComposeTestRule.performTouchInput{}` DSL only ever injects
 * `PointerType.Touch` (finger) events and has no way to specify tool type
 * or pressure at all, so it can't exercise the stylus branch. Dispatching
 * a real MotionEvent directly is in-process (no INJECT_EVENTS permission
 * or system input-pipeline dependency -- this is just calling a public
 * View method), and Compose's AndroidComposeView translates a real
 * MotionEvent's tool type/pressure into its own PointerType/pressure
 * faithfully -- this exercises the exact same translation a genuine
 * physical S Pen touch would drive.
 *
 * What this test CANNOT prove: whether the resulting sensitivity
 * difference is genuinely PERCEPTIBLE to a human hand versus a fingertip
 * -- that's an inherently physical, subjective judgment this automated
 * test can't make. Combined with [StylusPrecisionRatioTest]'s coverage of
 * the ratio math, this test proves the mechanism is real, correctly
 * wired end-to-end from a real MotionEvent through to a ratio-ready
 * pressure value; a person holding the actual device's S Pen is still the
 * right final check for "does it feel right."
 */
@RunWith(AndroidJUnit4::class)
class StylusPrecisionInputTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stylusContactReportsRealPressure_fingerContactReportsNull() {
        val capturedPressure = AtomicReference<Float?>(SENTINEL)

        composeTestRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("precision_input_probe")
                    .observeStylusPrecisionInput(
                        onGestureStart = { capturedPressure.set(SENTINEL) },
                        onStylusPressureChanged = { capturedPressure.set(it) }
                    )
            )
        }

        val bounds = composeTestRule.onNodeWithTag("precision_input_probe").fetchSemanticsNode().boundsInRoot
        val x = bounds.center.x
        val y = bounds.center.y

        var decorView: View? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            decorView = activity.window.decorView
        }
        val view = decorView ?: error("expected Activity.window.decorView to be available")

        // --- Finger contact: the modifier's own contract (and everything
        // built on top of it, per stylusPrecisionRatio's ratio=1f-for-
        // finger design) depends on this being null, not just "some low
        // number". ---
        dispatchDown(view, x, y, MotionEvent.TOOL_TYPE_FINGER, pressure = 1f)
        composeTestRule.waitForIdle()
        assertNull(
            "expected a finger contact to report null (not a stylus), got ${capturedPressure.get()}",
            capturedPressure.get()
        )
        dispatchUp(view, x, y, MotionEvent.TOOL_TYPE_FINGER, pressure = 1f)
        composeTestRule.waitForIdle()

        // --- Stylus, firm press: reports a real, non-null pressure close
        // to what was actually sent. ---
        dispatchDown(view, x, y, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.9f)
        composeTestRule.waitForIdle()
        val firmPressure = capturedPressure.get()
        assertEquals(
            "expected a firm-press stylus contact to report a pressure close to 0.9",
            0.9f, firmPressure ?: error("expected non-null pressure for a stylus contact"), 0.05f
        )
        dispatchUp(view, x, y, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.9f)
        composeTestRule.waitForIdle()

        // --- Stylus, light press: a DIFFERENT real pressure value,
        // proving this reads the actual per-event pressure rather than
        // returning some fixed/stale number regardless of input. ---
        dispatchDown(view, x, y, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.1f)
        composeTestRule.waitForIdle()
        val lightPressure = capturedPressure.get()
        assertEquals(
            "expected a light-press stylus contact to report a pressure close to 0.1",
            0.1f, lightPressure ?: error("expected non-null pressure for a stylus contact"), 0.05f
        )
        dispatchUp(view, x, y, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.1f)
    }

    private fun dispatchDown(view: View, x: Float, y: Float, toolType: Int, pressure: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val event = buildEvent(MotionEvent.ACTION_DOWN, x, y, toolType, pressure, time, time)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun dispatchUp(view: View, x: Float, y: Float, toolType: Int, pressure: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val event = buildEvent(MotionEvent.ACTION_UP, x, y, toolType, pressure, time, time)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun buildEvent(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int,
        pressure: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 1f
        })
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    companion object {
        // A sentinel distinct from both null (finger/no-contact) and any
        // real pressure value (always in 0f..1f), so a test failure that
        // leaves capturedPressure completely untouched is obviously
        // distinguishable from a genuine null reading.
        private val SENTINEL = -1f
    }
}
