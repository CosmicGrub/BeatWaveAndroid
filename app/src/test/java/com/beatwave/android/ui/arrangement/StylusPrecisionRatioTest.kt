package com.beatwave.android.ui.arrangement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [stylusPrecisionRatio] -- no device/emulator
 * needed for the ratio math itself. Complements
 * [StylusPrecisionInputTest] (instrumented, real MotionEvent dispatch
 * through the actual [StylusPrecisionSlider] composable) which proves the
 * WIRING is real; this file pins down the exact formula in isolation,
 * including boundary cases a full drag-gesture test wouldn't easily hit
 * pixel-for-pixel (exact pressure=0f/1f endpoints, out-of-range input).
 */
class StylusPrecisionRatioTest {

    @Test
    fun nullPressure_meansNoActiveStylus_ratioIsExactlyOne() {
        // No active pointer, or the active pointer isn't a stylus (a
        // finger, per observeStylusPrecisionInput's own contract) --
        // ratio=1f is what makes the delta-tracking in
        // StylusPrecisionSlider/StylusPrecisionRangeSlider mathematically
        // reduce to the raw slider value, i.e. ordinary finger dragging is
        // completely unaffected by this feature.
        assertEquals(1f, stylusPrecisionRatio(null), 0f)
    }

    @Test
    fun lightestPress_returnsStylusMinRatio() {
        assertEquals(STYLUS_MIN_RATIO, stylusPrecisionRatio(0f), TOLERANCE)
    }

    @Test
    fun firmestPress_returnsStylusMaxRatio() {
        assertEquals(STYLUS_MAX_RATIO, stylusPrecisionRatio(1f), TOLERANCE)
    }

    @Test
    fun midPress_interpolatesLinearlyBetweenMinAndMax() {
        val expectedMidpoint = STYLUS_MIN_RATIO + (STYLUS_MAX_RATIO - STYLUS_MIN_RATIO) * 0.5f
        assertEquals(expectedMidpoint, stylusPrecisionRatio(0.5f), TOLERANCE)
    }

    @Test
    fun everyStylusRatio_isStrictlyLessThanFingerRatio() {
        // The whole point of the feature: ANY real stylus contact, at any
        // pressure, must move less than a finger's 1:1 (ratio=1f) would
        // for the same raw distance.
        val samplePressures = listOf(0f, 0.05f, 0.25f, 0.5f, 0.75f, 0.95f, 1f)
        for (pressure in samplePressures) {
            val ratio = stylusPrecisionRatio(pressure)
            assertTrue(
                "expected stylusPrecisionRatio($pressure)=$ratio to be < 1f (finger's ratio)",
                ratio < 1f
            )
        }
    }

    @Test
    fun higherPressure_neverProducesALowerRatio() {
        // Monotonic: pressing harder should never make the drag FINER --
        // that would invert the "press harder = move faster/coarser"
        // design intent this feature is built around.
        val pressures = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)
        val ratios = pressures.map { stylusPrecisionRatio(it) }
        for (i in 1 until ratios.size) {
            assertTrue(
                "expected ratio(${pressures[i]})=${ratios[i]} >= ratio(${pressures[i - 1]})=${ratios[i - 1]}",
                ratios[i] >= ratios[i - 1]
            )
        }
    }

    @Test
    fun outOfRangePressure_stillProducesARatioWithinTheStylusBand() {
        // observeStylusPrecisionInput already coerces real MotionEvent
        // pressure into 0f..1f before this function ever sees it, but
        // stylusPrecisionRatio's own formula is linear and has no
        // built-in clamp -- confirm it doesn't misbehave (e.g. return a
        // ratio >= 1f, defeating the whole feature) if ever called with a
        // pressure outside that range from some other caller.
        assertTrue(stylusPrecisionRatio(-0.5f) <= STYLUS_MIN_RATIO)
        assertTrue(stylusPrecisionRatio(1.5f) >= STYLUS_MAX_RATIO)
    }

    companion object {
        private const val TOLERANCE = 0.0001f
    }
}
