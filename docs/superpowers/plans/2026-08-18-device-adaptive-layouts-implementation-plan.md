# BeatWave Android — Device-Adaptive Layouts & Hardware Quality Implementation Plan

Spec: `docs/superpowers/specs/2026-08-18-device-adaptive-layouts-design.md`

(`writing-plans` skill is not installed in this environment — this plan was
written directly, following the format of this project's own
`2026-08-11-beatwave-android-implementation-plan.md`.)

Each phase should leave the app in a buildable, on-device-verified state
before moving to the next. Commit (and push) at the end of each phase, on
the branch that phase specifies. Run the adversarial-review workflow
pattern (established throughout this project's post-v1 audit/upgrade work)
against each phase's diff before its commit.

## Phase 0 — Shared Foundation (branch: `master`)

- Add `androidx.window:window` dependency.
- `ArrangementScreen` reads its current `WindowSizeClass`; Compact renders
  today's existing `Scaffold` unchanged, Medium/Expanded renders a new
  (initially placeholder-simple) two-pane container. No device-specific
  tuning yet — this phase proves the switch itself works.
- Extract `LoopLibraryBottomSheet`'s body (category chips + card list) into
  a standalone `LoopLibraryContent` composable; both the existing
  `ModalBottomSheet` wrapper and a new persistent-panel wrapper render it.
- New `SettingsSheet.kt` (gear icon in the top bar), one setting: Loop
  Library interaction mode (Tap/Drag), `AppPreferences.loopLibraryInteractionMode`
  persists it, defaulting to Tap.
- Drag-and-drop mechanics: `LoopLibraryCard` as drag source,
  `TrackRow` lanes as drop targets, routed through the same
  `ArrangementViewModel` function tap mode already uses. Each card keeps a
  `CustomAccessibilityAction` per visible/selected track regardless of
  interaction mode — build this in the same commit as drag-and-drop, not as
  a follow-up.
- RAM-scaled sample cache: Kotlin queries `ActivityManager.getMemoryClass()`
  at `AudioEngine.init()` time, computes a scaled budget, passes it to a new
  production-path native setter generalized from D1's
  `testSetSampleBankMaxCacheBytes`.
- DeX pointer-input groundwork: `Modifier.hoverable` states on buttons/cards,
  right-click context menu on blocks, space-bar play/pause shortcut.
- Extend E7's stream-open diagnostic log line to also log channel count
  explicitly (verification groundwork for Phase 1/2's stereo pan test).
- **Exit criteria**: unit tests cover the RAM-scaled cache formula
  independent of any device; Compose UI tests assert Compact renders the
  sheet-based flow and Expanded renders the persistent panel; `uiautomator
  dump` confirms the Settings sheet and drag-and-drop's accessibility
  fallback actions are real, announced affordances — not just "it
  compiles." Full existing instrumented + JVM regression suite still green.
  Verified on whichever connected device (Fold 5 or Tab) is most
  convenient. Commit and push to `master`.

## Phase 1 — Tab S9 FE Branch (branch: `device/galaxy-tab-s9fe`, off `master`)

- Two-pane layout tuned for the tablet's landscape proportions: Loop
  Library ~34% left (per the approved wireframe), timeline fills the rest.
- On-device verification of touch-target sizing and track-row density at
  2560×1600.
- Stereo pan test (hard-left/hard-right tone) confirming genuine stereo
  output, not a silent downmix, on the Atmos-branded speakers.
- 120Hz refresh-rate confirmation (unresolved by research) and playhead/
  timeline-scroll animation smoothness check at whatever the real rate
  turns out to be.
- **Exit criteria**: real on-device screenshots plus genuine interaction
  (not a Compose preview) confirming the two-pane layout, touch targets,
  stereo pan test, and animation smoothness all pass on serial
  `R52X101MB6W`. Commit and push `device/galaxy-tab-s9fe`.

## Phase 2 — Fold 5 Branch (branch: `device/galaxy-z-fold-5`, off `master`, independent of Phase 1)

- Two-pane layout tuned for the unfolded inner screen (~1812×2176) —
  panel-width ratio tuned empirically on-device, not assumed from the
  Tab's value.
- Cover-screen verification/polish pass: confirm the existing Compact
  layout (particularly the top bar's project title + Export + Logs +
  Record cluster) actually fits and feels right at ~904×2316. Fix whatever
  doesn't, within the existing compact layout.
- Stereo pan test and animation-smoothness check (Fold 5 confirmed 120Hz
  both displays), same methodology as Phase 1.
- Optional, only if cheap on top of the existing `FoldingFeature` plumbing:
  hinge-aware pane divider in the book-open posture. Skip without
  regret if it adds real complexity.
- **Exit criteria**: real on-device verification (unfolded two-pane, cover
  single-column, stereo pan test, animation smoothness) on serial
  `RFCW80CK2RW`. Commit and push `device/galaxy-z-fold-5`.

## Phase 3 — S-Pen Precision Input (branch: `device/galaxy-tab-s9fe`, follow-on after Phase 1)

- Standard `MotionEvent.TOOL_TYPE_STYLUS` detection + `getPressure()` —
  no proprietary Samsung SDK.
- `LoopBlockEditor`'s Trim `RangeSlider` and Pitch `Slider` gain a reduced
  drag-distance-per-unit-change ratio when the active pointer is a stylus.
- Optional, only if cheap: S-Pen hover preview on loop cards.
- **Exit criteria**: real on-device verification with the actual S Pen —
  confirm finer-grained control is genuinely perceptible on the Trim/Pitch
  controls versus a fingertip, not just "the code branches correctly."
  Commit and push further onto `device/galaxy-tab-s9fe`.

## Phase 4 — Samsung DeX Support (both branches, follow-on after Phases 1/2)

- Verify (not build from scratch — Phase 0's pointer-input groundwork is
  the foundation) that the two-pane layout behaves correctly in a DeX
  window at arbitrary resize dimensions, that hover/right-click reach the
  app correctly when docked, and that window resizing interacts sanely
  with the `WindowSizeClass` breakpoints.
- Fix whatever doesn't hold up docked, on each branch independently.
- **Exit criteria**: real on-device verification, docked to an actual
  monitor, on both `R52X101MB6W` and `RFCW80CK2RW`. Commit and push
  further onto each branch independently.

## Notes for the Implementer

- Phase 0 is a hard prerequisite for every later phase — do not start
  Phase 1 or 2 before Phase 0's `WindowSizeClass` switch and
  `LoopLibraryContent` extraction are verified and merged into `master`.
  Both device branches would otherwise duplicate that plumbing.
- Phases 1 and 2 are independent and can be worked in either order or in
  parallel — neither depends on the other.
- The drag-and-drop accessibility fallback (`CustomAccessibilityAction`)
  is not a nice-to-have deferred to a later pass — build it in the same
  commit as drag-and-drop itself, matching this project's A4 audit
  precedent of accessibility being a first-class requirement, not an
  afterthought.
- The RAM-scaled cache budget is general logic, not per-device branching —
  it belongs entirely in Phase 0, with nothing further needed in Phases 1
  or 2.
- Every phase's "real on-device verification" step means exactly that —
  actual screenshots and actual interaction on the connected hardware,
  never claimed from compilation, a Compose preview, or an emulator alone,
  matching this project's standing discipline established since Phase 6 of
  the original v1 build.
- `MixEngine` stays stereo (`kChannelCount = 2`) throughout every phase
  here — the Atmos-speaker verification in Phases 1/2 confirms genuine
  stereo output, it does not motivate or require multi-channel mixing.
