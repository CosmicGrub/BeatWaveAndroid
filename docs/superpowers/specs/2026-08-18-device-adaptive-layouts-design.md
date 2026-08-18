# BeatWave Android — Device-Adaptive Layouts (Galaxy Z Fold 5 / Galaxy Tab S9 FE)

## Concept

BeatWave's UI is currently one universal phone-oriented Compose layout: a
single-column arrangement screen with the Loop Library and per-block editor
living in modal bottom sheets. Two large/foldable devices are now part of
this project's real device-testing lineup — a Samsung Galaxy Z Fold 5
(`SM-F946U`, serial `RFCW80CK2RW`) and a Samsung Galaxy Tab S9 FE
(`SM-X518U`, serial `R52X101MB6W`) — and the phone-only layout leaves most of
their screens' width unused.

This spec covers building a genuinely device-tuned experience for both,
landing as two separate git branches (`device/galaxy-tab-s9fe`,
`device/galaxy-z-fold-5`) on top of a shared foundation built first on
`master`.

Notably, BeatWave's own original v1 design doc
(`2026-08-11-beatwave-android-design.md`) already described the Loop
Library as "tap/drag to add a loop onto a track" — only tap ever shipped.
Drag-and-drop here is a restoration of that original intent, not a new
idea, and it's what large-screen layouts finally make natural to build.

## Scope decomposition

Three pieces, built in this order:

1. **Shared foundation** (not device-specific, lands on `master` first): a
   new minimal Settings screen, a persisted tap/drag-and-drop interaction
   preference, and the `androidx.window`-based responsive layout mechanism
   both device branches build on. Built once so neither branch duplicates
   it.
2. **`device/galaxy-tab-s9fe`**: two-pane layout tuned for the tablet's
   landscape proportions.
3. **`device/galaxy-z-fold-5`**: two-pane layout tuned for the unfolded
   inner screen, plus verification/polish of the (structurally unchanged)
   compact layout on the cover screen.

## Shared foundation

### Responsive switch

New dependency: `androidx.window:window`, giving `WindowSizeClass`
(width/height buckets) and `FoldingFeature` (hinge position/orientation).
`ArrangementScreen` reads its current width size class:

- **Compact** → today's existing single-column `Scaffold`, unchanged. This
  is what the Fold 5 cover screen lands in automatically — no new layout
  code, only verification that the existing layout actually fits well at
  its specific narrow-tall proportions.
- **Medium/Expanded** → a new two-pane layout: Loop Library panel on the
  left (~34% width, per the approved wireframe), arrangement timeline
  filling the rest.

This is a live, runtime switch, not a build-time/per-branch fork — it
responds correctly to the Fold 5 actually folding/unfolding while the app
is running, which a hardcoded per-device layout cannot do.

### `LoopLibraryContent` extraction

`LoopLibraryBottomSheet`'s body (category filter chips + card list,
currently inline inside its `ModalBottomSheet`) is extracted into a
standalone `LoopLibraryContent` composable. Both the existing
`ModalBottomSheet` wrapper (compact) and a new persistent-panel wrapper
(expanded) render the same `LoopLibraryContent` — the browsing/filtering
logic itself is not duplicated between phone and large-screen layouts.

### Settings screen

New `SettingsSheet.kt`, matching the existing sheet conventions
(`paneTitle`, `testTag`s per row, the `.selectable()`/`RadioButton` pattern
A4 established in `CategoryPickerDialog`). One setting for now: Loop
Library interaction mode (Tap / Drag-and-drop), defaulting to Tap. A new
`AppPreferences.loopLibraryInteractionMode` field persists it. The
arrangement screen's top bar gains a gear icon that opens this sheet. The
sheet is intentionally minimal but is meant to be the home for future
preference-shaped features (e.g. the already-scoped-but-unbuilt U3 dark
theme item), not a one-off.

### Drag-and-drop mechanics

When interaction mode = Drag: each `LoopLibraryCard` becomes a drag source
and each `TrackRow`'s lane becomes a drop target, via Compose's
`dragAndDropSource`/`dragAndDropTarget` modifiers. A successful drop calls
the exact same underlying "add loop to selected/dropped-on track" function
that tap mode already uses via `ArrangementViewModel` — the two interaction
modes are two different gestures triggering one unchanged data path, never
two forked implementations.

**Accessibility requirement, not an afterthought:** drag gestures are
generally not operable via TalkBack. Each `LoopLibraryCard` keeps a
`CustomAccessibilityAction` ("Add to Track N", one per currently-selected
or visible track) regardless of interaction mode, so enabling drag-and-drop
never regresses accessibility for anyone navigating by screen reader.

## Device-specific work

### Tab S9 FE

Used landscape (2560×1600); width size class is Expanded almost always in
that orientation. Two-pane layout matches the approved wireframe directly —
Loop Library ~34% left, timeline fills the rest. Primary work is on-device
verification of touch-target sizing and track-row density at this
resolution, not new structural design.

### Fold 5 — unfolded

~1812×2176, nearly square; lands in Medium/Expanded depending on
orientation. The Tab's panel-width ratio likely needs adjusting for this
narrower absolute width — treated as a value tuned empirically on-device,
not decided in advance.

**Optional enhancement, not a requirement:** `FoldingFeature` exposes the
hinge's position. When the device is in a half-opened "book" posture, the
layout could use the hinge itself as the pane divider instead of a fixed
percentage. Build the flat two-pane layout first; add hinge-awareness only
if it turns out cheap once the `WindowSizeClass`/`FoldingFeature` plumbing
already exists. Not required for this spec to be considered done.

### Fold 5 — cover screen

~904×2316. Structurally just the existing Compact-width layout via the
same responsive switch — no new UI code. The work here is verification:
does the existing top bar (project title + Export + Logs + Record cluster,
originally tuned against more typical phone widths) actually fit and feel
right at this specific narrow-tall aspect ratio? Fix whatever doesn't,
within the existing compact layout — this is a polish/bugfix pass, not a
redesign.

## Testing & verification

- Compose UI tests exercising the responsive switch itself at both size
  classes — asserting compact renders the sheet-based flow and
  expanded/medium renders the persistent panel, not just "it compiles."
- Accessibility pass on the new Settings screen and the drag-and-drop
  fallback actions, verified via `adb shell uiautomator dump` against the
  real `AccessibilityNodeInfo` tree, matching A4's established method.
- Real on-device verification on all three configurations (Tab landscape,
  Fold unfolded, Fold cover) on the actual connected hardware — screenshots
  plus genuine interaction, never claimed from compilation or a Compose
  preview alone.
- An adversarial-review workflow pass on each branch's diff before commit,
  matching the sweep-then-implement-then-adversarially-review pipeline used
  throughout this project's post-v1 audit/upgrade work.

## Branching & sequencing plan

1. Shared foundation (Settings screen + preference, `WindowSizeClass`
   switch skeleton, `LoopLibraryContent` extraction) lands on `master`,
   verified on whichever connected device is most convenient, committed and
   pushed to `github.com/CosmicGrub/BeatWaveAndroid`.
2. Branch `device/galaxy-tab-s9fe` off `master` → Tab-specific two-pane
   tuning and verification → commit, push.
3. Branch `device/galaxy-z-fold-5` off `master` (independent/parallel to
   the Tab branch, not stacked on it) → unfolded two-pane tuning +
   cover-screen verification/polish + optional hinge-awareness → commit,
   push.
4. Both branches stay open on GitHub for manual review/merge later — this
   spec does not include merging either branch back into `master`.

## Explicitly out of scope

- Merging `device/galaxy-tab-s9fe` or `device/galaxy-z-fold-5` back into
  `master` — a later, separate decision.
- Any device beyond these two connected ones (e.g. generic large-screen
  support). The `WindowSizeClass` mechanism will incidentally behave
  reasonably on other large/foldable devices as a side effect of being
  built the standard way, but this spec only scopes verification against
  the Fold 5 and Tab S9 FE.
- Landscape orientation on a regular (non-foldable, non-tablet) phone —
  not a verification target here, even though the same responsive switch
  will technically engage there too.
- The Fold 5 hinge-aware pane divider, unless it turns out to be cheap once
  the underlying plumbing exists (see "Optional enhancement" above).
- Any additional Settings entries beyond the interaction-mode toggle (e.g.
  dark theme itself) — the screen is built to hold future settings, but
  only the one toggle is in scope now.
