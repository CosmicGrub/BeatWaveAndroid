package com.beatwave.android.ui.arrangement

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Device-adaptive layouts (2026-08-18 spec), Phase 0: opens the Loop
 * Library if it isn't already visible, then returns. In Compact width the
 * library lives behind the "open_library_button" (a bottom sheet) -- tap
 * it. In Medium/Expanded width the persistent LoopLibraryPanel (see
 * ArrangementScreen.kt) is already always visible, so
 * "open_library_button" doesn't exist at all in that layout (see
 * PlaybackControlBar's onOpenLibrary being null there) -- there's nothing
 * to tap.
 *
 * Every existing instrumented test that adds a loop via the library was
 * written before two-pane layouts existed and always tapped this button
 * unconditionally, which would now fail outright on any device wide
 * enough to trigger the two-pane layout (both the Tab S9 FE and the Fold 5
 * unfolded screen, at their real native resolutions). This makes that same
 * test flow work correctly regardless of which window size class the test
 * happens to run under, rather than assuming Compact.
 */
fun ComposeTestRule.ensureLoopLibraryOpen() {
    // Matches the existing "onAllNodesWithTag(...).fetchSemanticsNodes().isNotEmpty()"
    // existence-check pattern already used throughout this test suite
    // (e.g. the waitUntil blocks immediately below every call site this
    // helper replaces).
    val openLibraryButtonExists = onAllNodesWithTag("open_library_button")
        .fetchSemanticsNodes()
        .isNotEmpty()
    if (openLibraryButtonExists) {
        onNodeWithTag("open_library_button").performClick()
    }
}

/**
 * Device-adaptive layouts (2026-08-18 spec), Phase 0: closes the Loop
 * Library sheet if it's open as a sheet. In Compact width, dismisses via
 * "loop_library_close_button" -- the usual end of a "select track, open
 * library, add a loop" flow. In Medium/Expanded width, the persistent
 * LoopLibraryPanel has no dismiss button at all (it's always visible by
 * design, see ArrangementScreen.kt) -- "loop_library_close_button" doesn't
 * exist there, so this is correctly a no-op rather than a failure.
 *
 * Same rationale as [ensureLoopLibraryOpen]: every existing test tapped
 * this button unconditionally, which would fail outright in the two-pane
 * layout without this guard.
 */
fun ComposeTestRule.ensureLoopLibraryClosed() {
    val closeButtonExists = onAllNodesWithTag("loop_library_close_button")
        .fetchSemanticsNodes()
        .isNotEmpty()
    if (closeButtonExists) {
        onNodeWithTag("loop_library_close_button").performClick()
    }
}

/**
 * A drop-in replacement for [ComposeTestRule.waitUntil] that survives a
 * real, previously-diagnosed harness race (project memory finding #27): a
 * `waitUntil` condition lambda that calls `fetchSemanticsNodes()` can throw
 * `IllegalStateException("No compose hierarchies found")` -- not just
 * return false -- if its very first poll fires before the just-launched
 * Activity has actually called `setContent()` yet. Plain `waitUntil` does
 * not catch/retry through a thrown exception, so it fails almost instantly
 * instead of after any real timeout; a caller that itself wraps `waitUntil`
 * in `runCatching` only converts that early crash into an early, equally
 * wrong "condition never became true" failure, since it never gets to use
 * the rest of its real timeout budget either.
 *
 * This wrapper catches exactly that one first-poll race and treats it as an
 * ordinary "not true yet" retry instead, so the real timeout budget is
 * actually used. Any OTHER exception -- a genuine bug in the condition, or
 * a "No compose hierarchies found" that persists well past first-composition
 * -- still propagates immediately rather than being silently swallowed.
 */
fun ComposeTestRule.waitUntilSafely(timeoutMillis: Long, condition: () -> Boolean) {
    waitUntil(timeoutMillis) {
        try {
            condition()
        } catch (e: IllegalStateException) {
            if (e.message?.contains("No compose hierarchies found") == true) {
                false
            } else {
                throw e
            }
        }
    }
}

/**
 * The same "No compose hierarchies found" race [waitUntilSafely] guards
 * against, but for [ComposeTestRule.performClick] itself rather than a
 * `waitUntil` condition. Observed NOT just right after Activity launch
 * (finding #27's originally-documented window) but also after real audio
 * playback has been running for a moment (a click immediately following
 * Play + a settle sleep, e.g. Pause or Stop) -- real Oboe/AAudio stream
 * startup is a genuinely heavy, real-time-sensitive OS operation, and the
 * resulting system-wide contention can transiently delay the host
 * Activity's own frame/semantics availability long enough to trip this on
 * an ordinary `performClick()`, which (unlike `waitUntilSafely`) has no
 * retry tolerance of its own for a transient exception.
 *
 * Retries the WHOLE click (not just a query) a bounded number of times
 * with a brief settle between attempts. Any other exception, or the race
 * persisting past the retry budget, still propagates -- this is retry
 * tolerance for a proven transient condition, not a blanket try-again.
 */
fun ComposeTestRule.performClickSafely(tag: String, maxAttempts: Int = 5, retryDelayMs: Long = 200L) {
    var lastError: IllegalStateException? = null
    repeat(maxAttempts) {
        try {
            onNodeWithTag(tag).performClick()
            return
        } catch (e: IllegalStateException) {
            if (e.message?.contains("No compose hierarchies found") != true) throw e
            lastError = e
            Thread.sleep(retryDelayMs)
        }
    }
    throw lastError ?: IllegalStateException("performClickSafely: unreachable retry state for tag=$tag")
}

/**
 * Clicks [tag] and retries the CLICK ITSELF (not just a query, and not
 * just catching an exception like [performClickSafely]) if [verifyEffective]
 * doesn't become true within [perAttemptTimeoutMs] -- covers a distinct,
 * separately-confirmed failure mode (project memory finding #55): a click
 * that `performClick()` reports as fully successful (no exception at all)
 * but that never actually reaches its handler, confirmed via direct
 * ViewModel-level logging showing the target function was simply never
 * invoked for that click. A settle delay before a single click attempt
 * does not help here -- there is no exception or race window to wait out,
 * the click itself needs to be retried until it actually lands.
 *
 * [verifyEffective] should read real state (a ViewModel's `uiState`, not
 * just semantics-tree existence) so a click that "succeeded" in a way
 * that doesn't produce the effect being checked is correctly retried
 * rather than accepted.
 */
fun ComposeTestRule.performClickUntilEffective(
    tag: String,
    maxAttempts: Int = 5,
    perAttemptTimeoutMs: Long = 1_500L,
    verifyEffective: () -> Boolean
) {
    repeat(maxAttempts) {
        if (verifyEffective()) return
        performClickSafely(tag)
        val tookEffect = runCatching {
            waitUntilSafely(timeoutMillis = perAttemptTimeoutMs) { verifyEffective() }
        }.isSuccess
        if (tookEffect) return
    }
    if (!verifyEffective()) {
        throw AssertionError(
            "performClickUntilEffective: clicking tag=\"$tag\" never took effect after $maxAttempts attempts"
        )
    }
}
