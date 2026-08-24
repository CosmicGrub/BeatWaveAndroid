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
