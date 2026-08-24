# BeatWave Android — Device-Adaptive Layouts & Hardware-Specific Quality (Galaxy Z Fold 5 / Galaxy Tab S9 FE)

## Concept

BeatWave's UI is currently one universal phone-oriented Compose layout: a
single-column arrangement screen with the Loop Library and per-block editor
living in modal bottom sheets. Two large/foldable devices are now part of
this project's real device-testing lineup — a Samsung Galaxy Z Fold 5
(`SM-F946U`, serial `RFCW80CK2RW`, Snapdragon 8 Gen 2, 12GB RAM, both
displays 120Hz) and a Samsung Galaxy Tab S9 FE (`SM-X518U`, serial
`R52X101MB6W`, 6GB RAM, dual stereo speakers with Dolby Atmos, S Pen
included) — and the phone-only layout leaves most of their screens' width,
and most of their actual hardware, unused.

This spec now covers two related things for both devices: a genuinely
device-tuned **layout** (two-pane arrangement, drag-and-drop), and
genuinely device-tuned **quality/hardware features** grounded in each
device's real, researched specs (RAM-scaled sample cache, S-Pen precision
input, Samsung DeX support) — landing as two separate git branches
(`device/galaxy-tab-s9fe`, `device/galaxy-z-fold-5`) on top of a shared
foundation built first on `master`, plus explicit follow-on phases for the
hardware-specific work that doesn't belong in the initial layout pass.

Notably, BeatWave's own original v1 design doc
(`2026-08-11-beatwave-android-design.md`) already described the Loop
Library as "tap/drag to add a loop onto a track" — only tap ever shipped.
Drag-and-drop here is a restoration of that original intent, not a new
idea, and it's what large-screen layouts finally make natural to build.

## Scope decomposition

Five pieces now, built in this order:

1. **Shared foundation** (not device-specific, lands on `master` first): a
   new minimal Settings screen, a persisted tap/drag-and-drop interaction
   preference, the `androidx.window`-based responsive layout mechanism both
   device branches build on, a RAM-scaled sample cache budget, and the
   verification methodology for stereo-output and animation-smoothness
   checks (see below). Built once so no later phase duplicates it.
2. **`device/galaxy-tab-s9fe`**: two-pane layout tuned for the tablet's
   landscape proportions.
3. **`device/galaxy-z-fold-5`**: two-pane layout tuned for the unfolded
   inner screen, plus verification/polish of the (structurally unchanged)
   compact layout on the cover screen.
4. **S-Pen precision input** (follow-on phase, continues on
   `device/galaxy-tab-s9fe` after piece 2 lands — Tab-specific, since the
   Fold 5 has no stylus).
5. **Samsung DeX support** (follow-on phase, touches both branches — small
   additional commits on each, after pieces 2/3 land, verifying the
   shared-foundation pointer-input groundwork actually works well docked).

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

### RAM-scaled sample cache budget

`SampleBank::kDefaultMaxCacheBytes` is a fixed 256MiB constant today,
chosen conservatively for a typical/budget phone (D1, 2026-08-17). The Tab
S9 FE (6GB RAM) and especially the Fold 5 (12GB RAM) can comfortably run a
much larger cache without memory pressure, meaning fewer re-decodes when
switching between projects that share loop assets. This is deliberately
**general, RAM-aware logic, not a per-device hardcoded value**: at
`AudioEngine::init()` time, Kotlin queries `ActivityManager.getMemoryClass()`
(the standard, guaranteed-safe per-app heap size in MB) and computes a
scaled cache budget, passed to native via the exact mechanism D1 already
built for its test-only override (`testSetSampleBankMaxCacheBytes`) —
generalized here into a real production-path setter, not a test hook. Any
device with enough memory class benefits automatically; the Tab and Fold
are simply the first devices verifying it actually helps.

### Verification methodology: stereo output and animation smoothness

Two verification items belong in the shared foundation as *methodology*,
even though they're executed per-device later (see "Device-specific work"
and the follow-on phases):

- **Stereo output, not a silent downmix.** Both devices' speaker hardware
  is Dolby-Atmos-branded. `MixEngine` is architecturally stereo throughout
  (`kChannelCount = 2`) and stays that way — see "Explicitly out of scope"
  below. What's real and verifiable: confirming the Oboe output stream
  actually negotiates true stereo channel output on this hardware, not a
  silent downmix. Verified via E7's existing stream-open diagnostic
  logging (already logs `performanceMode`/`audioApi`; extend to explicitly
  log channel count too) plus an audible hard-left/hard-right pan test
  tone on each device.
- **120Hz animation smoothness.** The Fold 5 is confirmed 120Hz on both
  displays; the Tab S9 FE's refresh rate needs on-device confirmation
  (not settled by this round of research). Verify the playhead and
  timeline-scroll animations aren't implicitly capped below the display's
  real refresh rate.

### DeX pointer-input groundwork

Samsung DeX support (full follow-on phase below) needs baseline
non-touch-pointer handling that's genuinely shared, not device-specific:
mouse hover states on buttons/cards (Compose's `Modifier.hoverable`),
right-click context-menu support where natural (e.g. a quick-actions menu
on a block), and basic transport keyboard shortcuts (space = play/pause).
This groundwork lands in the shared foundation because it's useful even
outside DeX (e.g. a Bluetooth mouse/keyboard paired to a phone) — the DeX
phase later only adds and verifies the DeX-specific polish on top of it.

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

## Follow-on phase: S-Pen precision input (Tab only)

Real Android stylus support via standard `MotionEvent.TOOL_TYPE_STYLUS`
detection and `MotionEvent.getPressure()` — no proprietary Samsung SDK
needed for this baseline. Target: `LoopBlockEditor`'s Trim `RangeSlider`
and Pitch `Slider` gain finer drag precision when the active pointer is
detected as a stylus (a reduced drag-distance-per-unit-change ratio),
giving genuinely more precise control than a fingertip for fine edits.

**Optional, not required:** S-Pen hover preview (hovering a loop card
in the library panel previews it without tapping, via Compose's hover
APIs). Build the baseline pressure-aware dragging first; add hover preview
only if it turns out cheap on top of the DeX pointer-input groundwork
already in the shared foundation.

## Follow-on phase: Samsung DeX support (both devices)

Builds on the shared-foundation pointer-input groundwork (hover, right-click,
keyboard shortcuts). Per-device work here is verification, not new
structural design: does the two-pane layout continue to look and behave
correctly in a DeX window at arbitrary resize dimensions, do hover and
right-click actually reach the app correctly when DeX-docked, and does
window resizing interact sanely with the `WindowSizeClass` breakpoints
already driving the compact/expanded switch. Verified docked, on a real
monitor/dock, on both devices.

## Testing & verification

- Compose UI tests exercising the responsive switch itself at both size
  classes — asserting compact renders the sheet-based flow and
  expanded/medium renders the persistent panel, not just "it compiles."
- Unit coverage on the RAM-scaled cache budget formula itself (given a
  memory class, does it compute the expected bound), independent of any
  device.
- Accessibility pass on the new Settings screen and the drag-and-drop
  fallback actions, verified via `adb shell uiautomator dump` against the
  real `AccessibilityNodeInfo` tree, matching A4's established method.
- Real on-device verification on all three layout configurations (Tab
  landscape, Fold unfolded, Fold cover) on the actual connected hardware —
  screenshots plus genuine interaction, never claimed from compilation or
  a Compose preview alone.
- Real on-device verification of the hardware-quality items: the
  hard-left/hard-right stereo pan test and 120Hz animation check (both
  devices), S-Pen pressure-aware dragging (Tab), and DeX-docked behavior
  (both devices, on a real monitor/dock).
- An adversarial-review workflow pass on each branch's diff before commit,
  matching the sweep-then-implement-then-adversarially-review pipeline used
  throughout this project's post-v1 audit/upgrade work.

## Branching & sequencing plan

1. Shared foundation (Settings screen + preference, `WindowSizeClass`
   switch skeleton, `LoopLibraryContent` extraction, RAM-scaled cache
   budget, DeX pointer-input groundwork, verification methodology) lands on
   `master`, verified on whichever connected device is most convenient,
   committed and pushed to `github.com/CosmicGrub/BeatWaveAndroid`.
2. Branch `device/galaxy-tab-s9fe` off `master` → Tab-specific two-pane
   tuning and verification → commit, push.
3. Branch `device/galaxy-z-fold-5` off `master` (independent/parallel to
   the Tab branch, not stacked on it) → unfolded two-pane tuning +
   cover-screen verification/polish + optional hinge-awareness → commit,
   push.
4. Follow-on: S-Pen precision input lands as further commits on
   `device/galaxy-tab-s9fe` (after piece 2 is verified) → commit, push.
5. Follow-on: Samsung DeX verification/polish lands as further commits on
   *both* `device/galaxy-tab-s9fe` and `device/galaxy-z-fold-5` (after
   pieces 2/3 are verified) → commit, push each.
6. All branches stay open on GitHub for manual review/merge later — this
   spec does not include merging any branch back into `master`.

## Explicitly out of scope

- Merging any device branch back into `master` — a later, separate
  decision.
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
- **True multi-channel/object-based audio (Dolby Atmos mixing).**
  `MixEngine` stays architecturally stereo (`kChannelCount = 2`) throughout
  this spec — the Atmos-branded speaker hardware only motivates *verifying
  genuine stereo output*, not adding real multi-channel mixing, which would
  be a native engine rearchitecture out of scope here.
- **Deep Samsung S-Pen SDK integration** (Air Command shortcuts, custom
  S-Pen gestures beyond hover/pressure). Only standard Android stylus
  `MotionEvent` handling is in scope.
- Any additional Settings entries beyond the interaction-mode toggle (e.g.
  dark theme itself) — the screen is built to hold future settings, but
  only the one toggle is in scope now.
