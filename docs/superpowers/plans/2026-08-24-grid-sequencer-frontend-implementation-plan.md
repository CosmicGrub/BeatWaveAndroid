# BeatWave Android — Grid/Piano-Roll Frontend Implementation Plan

Spec: `docs/superpowers/specs/2026-08-24-grid-sequencer-frontend-design.md`

(`writing-plans` skill is not installed in this environment — this plan was
written directly, following the format of this project's own
`2026-08-18-device-adaptive-layouts-implementation-plan.md`.)

Each phase should leave the app in a buildable, on-device-verified state
before moving to the next. Commit (and push) at the end of each phase, on
`master`. Run the adversarial-review workflow pattern (established
throughout this project's post-v1 audit/upgrade work) against each phase's
diff before its commit.

## Organizing method: tier, priority, risk

Ordered by **tier** (a hard sequencing boundary — nothing in a later tier
starts before its tier's own exit criteria are met), and within a tier by
**priority** (what's essential to that tier's own goal vs. what fills out
capability). Every phase also carries an explicit **risk** rating —
not decoration, it's *why* the tiers are ordered the way they are:

- **Tier 0 — Foundation.** Zero-risk, purely additive groundwork nothing
  else can start without.
- **Tier 1 — Walking Skeleton.** The single riskiest, least-proven bet in
  this whole initiative — does a tap/drag grid interaction model actually
  work well in Compose and feel right — deliberately isolated into the
  *smallest possible* slice (one instrument type, one gesture each) so
  real on-device proof arrives fast, before any further investment. If
  this tier's own exit criteria don't hold up, everything downstream gets
  re-evaluated before Tier 2 starts.
- **Tier 2 — Full Capability.** Everything the spec calls for that Tier 1
  deliberately deferred, each piece now individually lower-risk because
  it's building on a *proven* interaction foundation rather than an
  unproven one.
- **Tier 3 — Cutover.** High risk *by nature* (irreversible: removes the
  screen every existing user flow currently depends on) — sequenced last
  on purpose, only attempted once Tiers 1-2 are fully solid, never
  parallelized with them.

`master` stays fully shippable and working at every single commit through
Tiers 0-2 — `ArrangementScreen` remains the app's real active entry point
throughout; only Tier 3 touches navigation or removes anything.

---

## Tier 0 — Foundation

### 0.1 Data model & migration
**Priority: P0 (blocking everything). Risk: Low** (additive, backward-
compatible fields; no existing code path reads them yet).

- Add `assignedSampleIds: List<String> = emptyList()` to `Track`.
- Add `pitchRow: Int? = null` to `LoopBlock`.
- Both are new, defaulted/nullable fields on existing `@Serializable` data
  classes — backward-compatible with already-saved project JSON
  (kotlinx.serialization fills in the default for a field missing from
  older saved data). Write an explicit unit test proving this: deserialize
  a hand-written JSON string shaped like a pre-this-phase saved project
  (no `assignedSampleIds`/`pitchRow` keys at all) and confirm it loads
  with the new fields correctly defaulted, not a deserialization error.
- Migration function (pure, unit-testable in isolation): given a `Track`
  whose `assignedSampleIds` is empty, derive it from
  `track.loopBlocks.map { it.sampleId }.distinct()` (first-seen order).
  Wire it into wherever project loading already happens (`ProjectRepository`
  load path or `ArrangementViewModel.init`) so every existing saved
  project gets this applied transparently on next open — no dedicated
  migration screen, matching the spec's own explicit requirement.

**Exit criteria:** unit tests cover the migration function's three real
cases (already-populated/untouched, single-sample/migrates-to-one-entry,
multi-sample/migrates-to-all-distinct-IDs-in-order) and old-format JSON
backward-compatibility. Full existing instrumented + JVM regression suite
stays green with zero user-visible behavior change (nothing reads the new
fields yet). Commit and push to `master`.

---

## Tier 1 — Walking Skeleton

**Do not start Tier 2 until this tier's exit criteria hold on real
hardware.** Everything here is scoped to melodic (single-sample) tracks
only — drum-kit multi-row support, drag-to-stretch, long-press editing,
the Sounds picker, and the scale system are all explicitly deferred to
Tier 2. This isn't laziness: it's the smallest slice that actually tests
the risky assumption, and every deferred piece is additive on top of it,
not a redesign if it turns out fine.

### 1.1 Minimal grid rendering (melodic only)
**Priority: P0. Risk: Medium** (new rendering code, first real test of
whether a grid — as opposed to a timeline — reads well at phone width).

- Track switcher (pills for slots 1-8, tap to focus).
- Row computation for a melodic (`BASS`/`SYNTH`/`VOCAL`) track with
  exactly one assigned sample: chromatic ±12 pitch-offset rows (the
  scale-constrained *view* filter is Tier 2 — this always shows full
  chromatic).
- Grid canvas: columns per `GridConstants` grid units (the same system
  `ScoreBuilder` already uses), rendering existing `LoopBlock`s at their
  `pitchRow`/`startGridUnit` position. Drum tracks and unassigned tracks
  can render a simple placeholder for now ("not yet supported" /the
  empty-state prompt) — full drum-row rendering is Tier 2's 2.1.

**Exit criteria:** instrumented test hosting the grid composable directly
(`createAndroidComposeRule<ComponentActivity>` — the pattern this
project's own Phase 3 S-Pen work established, not a full `MainActivity`
launch, since nothing is wired into real navigation yet) confirms correct
row labels/count and that existing migrated blocks render at the right
position, for a melodic track. Real on-device screenshot on `R52X101MB6W`
showing a real melodic grid (a bundled BASS or SYNTH sample) rendering
correctly. Commit and push to `master`.

### 1.2 Minimal interaction: tap-to-place, tap-to-delete (melodic only)
**Priority: P0. Risk: High** — this is the actual bet. Getting Compose
gesture disambiguation right (a plain tap vs. the start of a drag vs. a
long-press, all on the same grid cell) is genuinely nontrivial, and
whether the result *feels* like the reference video rather than fiddly or
mis-triggering is something no amount of code review substitutes for.

- Tap an empty cell → create a new `LoopBlock` (`pitchRow` = tapped row,
  `startGridUnit` = tapped column, `lengthGridUnits` = 1), routed through
  the real `ArrangementViewModel` → engine commit path (the same
  underlying mechanism `addLoopToSelectedTrack` already uses for the old
  screen — not a UI-only mock).
- Tap a filled cell → delete that `LoopBlock`.
- No drag-to-stretch, no long-press editor yet — a single unambiguous
  gesture (tap) is deliberately all this slice exercises, so a problem
  with tap-recognition itself isn't confused with a problem in
  disambiguating tap from drag from long-press (that disambiguation is
  Tier 2's own risk to isolate and test on its own).

**Exit criteria:** instrumented test proves a tap genuinely mutates the
real `Project`/`LoopBlock` data (not just a UI-local state change) and a
second tap on the same cell removes it. Real on-device interaction on
`R52X101MB6W`: tap-place a note, tap Play, and prove it's genuinely
scheduled by the real engine — read `AudioEngineBridge.getCurrentFrame()`
directly after, the same proof pattern `ArrangementScreenPlaybackTest`
already established for the old screen, not just a visual check. **This
is the tier's actual go/no-go signal** — if tap-placement doesn't feel
right here, that's a finding worth surfacing and discussing before Tier 2
adds more surface area on top of it. Commit and push to `master`.

---

## Tier 2 — Full Capability

Each item below builds on Tier 1's now-proven interaction foundation.
Independent of each other — any order within this tier is fine, and they
could be parallelized across sessions if that ever matters; listed here
in the order the spec itself introduces them, not a required sequence.

### 2.1 Drum-kit row support
**Priority: P1. Risk: Low** (extends 1.1's row-computation function with
a second, already-designed branch — no new rendering mechanics).

- Row computation gains the `DRUMS`-category case: one or more assigned
  samples → one named row per sample, in `assignedSampleIds` order.
- Tap-to-place/delete (from 1.2) already works unchanged once rows exist
  — a drum row's tap just uses that row's own sample instead of a
  pitch-shifted one.

**Exit criteria:** instrumented test + real on-device screenshot showing
a multi-row drum kit (e.g. Basic Kick + Basic Snare) rendering and
tap-placing/deleting correctly. Commit and push to `master`.

### 2.2 Drag-to-stretch placement
**Priority: P1. Risk: Medium** (the gesture-disambiguation risk Tier 1
deliberately deferred — tap vs. drag on the same cell).

- Tap-and-drag from an empty cell → `lengthGridUnits` spans the dragged
  columns, for riff-style samples.
- Collision handling: if the drag reaches an already-occupied cell on the
  same row, clamp the new block's length to stop just before it, rather
  than overlapping or silently overwriting — matches how a real
  step-sequencer grid behaves, no user-facing decision needed.

**Exit criteria:** instrumented test proving a drag produces a block with
the right `lengthGridUnits`, and the collision-clamping case specifically.
Real on-device interaction confirming a dragged placement doesn't
misfire as a delete or get confused with a plain tap. Commit and push to
`master`.

### 2.3 Long-press editor
**Priority: P1. Risk: Low** (pure reuse — `LoopBlockEditorDialog` and its
S-Pen precision-input work, `device/galaxy-tab-s9fe`, are untouched;
this only adds the long-press gesture that opens it).

- Long-press a filled cell → open `LoopBlockEditorDialog` unchanged.
- Playhead: a vertical line during playback, driven by the same
  `currentFrame`/50ms-poll state `PlaybackControlBar` already reads.

**Exit criteria:** instrumented test confirms long-press opens the dialog
with the tapped block's real data. Real on-device check that long-press
doesn't misfire as a tap (accidentally deleting) or a drag. Commit and
push to `master`.

### 2.4 Sounds picker wiring
**Priority: P1. Risk: Low-Medium** (mostly plumbing existing UI to new
callbacks; the only real judgment call is the melodic-replace behavior
below).

- Repurpose `LoopLibraryContent` (unchanged internals) as the
  instrument-assignment picker for the focused track.
- **Melodic track:** "Add" *replaces* `assignedSampleIds` with that one
  sample. Existing `LoopBlock`s keep their `pitchRow` unchanged — they
  now play the newly assigned sample at that row's pitch offset. Not
  explicit in the spec, but follows directly from its own "never silently
  delete placed data" precedent (already applied to the scale system and
  to drum-kit removal) — applying it here too is the natural reading, not
  a new open question.
- **Drum track:** "Add" *adds* to `assignedSampleIds`; an already-assigned
  card shows "Remove" instead, which takes it out of the list without
  deleting notes already on its row (per the spec).

**Exit criteria:** instrumented tests for melodic-replace and
drum-add/remove, including "existing notes survive a reassignment". Real
on-device: assign an instrument to a freshly empty track via Sounds,
confirm the grid populates immediately. Commit and push to `master`.

### 2.5 Scale system
**Priority: P1. Risk: Low** (self-contained UI + a filter function over
1.1's already-working row computation).

- A chip near the track switcher (e.g. "C Major ▾") opens a picker: 12
  root notes × {Major, Minor, Pentatonic Major, Pentatonic Minor,
  Chromatic}. Per-session state (like today's `showLibrary`), not
  persisted.
- Row-visibility rule: show every row in the chosen scale, **plus** any
  row with an existing note even if out-of-scale. "Chromatic" is the
  no-filter case of the same control, not a separate toggle.

**Exit criteria:** instrumented test confirms row visibility matches the
chosen scale, and the "existing notes stay visible even off-scale"
exception specifically. Real on-device screenshot showing the row-set
change when switching scales. Commit and push to `master`.

---

## Tier 3 — Cutover

**Only start once every Tier 1 and Tier 2 exit criteria are met and
verified on real hardware. Not parallelized with earlier tiers.**

### 3.1 Replace the timeline screen
**Priority: P0 (this is the whole point of the initiative), sequenced
last because Risk: High** — irreversible in practice (removes the screen
every existing v1 capability currently runs through) and the first point
every earlier tier's accumulated behavior is exercised together, for
real, at once.

- Switch `MainActivity`'s `setContent` from `ArrangementScreen` to the
  new grid screen.
- Remove `ArrangementScreen.kt`, `TrackRow`, and other now-dead
  timeline-specific rendering/scroll code. Do **not** remove
  `LoopLibraryContent`, `LoopBlockEditorDialog`, `PlaybackControlBar`, or
  the project-picker top-bar logic — all explicitly reused per the spec.
- Retire/replace instrumented tests that specifically exercised the old
  screen's own shape (e.g. `ArrangementScreenPlaybackTest`'s
  track-header-and-timeline-block flow) with grid-screen equivalents
  covering the same real-engine-driving proof pattern. Screen-independent
  tests (native engine, import pipeline, crash logging, multi-project)
  need no changes.
- Full regression suite pass on real hardware, **both** devices
  (`R52X101MB6W` and `RFCW80CK2RW`), matching this project's standing
  dual-device discipline for anything touching the main screen.

**Exit criteria:** the app's real launch shows the new grid screen; every
core v1 capability (build an arrangement, play/pause/stop, export,
import, multi-project switching, background playback, crash-log access)
demonstrably works end-to-end through the new screen; full regression
suite green (or only the same class of pre-existing device-load
flakiness already well-documented for these two devices, not new
failures); real screenshots + interaction on both devices. Commit and
push to `master`.

---

## Notes for the Implementer

- Tier boundaries are hard: don't start Tier 2 work before Tier 1's exit
  criteria (especially 1.2's go/no-go interaction check) are actually met
  on real hardware, and don't start Tier 3 before every Tier 2 item is
  done. This is the entire point of the tier structure — catching a
  fundamental interaction-model problem after 1.2, with almost nothing
  else built yet, is cheap; catching it after Tier 2 (five more features
  built on top) or Tier 3 (already cut over) is not.
- Within Tier 2, priority/order among 2.1-2.5 doesn't matter — they're
  independent, listed in spec order for reference, not a required
  sequence.
- The Tier 0 migration logic is worth checking against this project's own
  actual real saved "current" project data (pull it off one of the test
  devices) in addition to synthetic fixtures.
- S-Pen precision input (`device/galaxy-tab-s9fe`) and the Fold two-pane
  branch (`device/galaxy-z-fold-5`) are independent, unaffected work —
  `LoopBlockEditorDialog` is reused as-is by 2.3, so neither branch's
  work is invalidated or needs redoing.
- Every phase's "real on-device verification" means exactly that — actual
  screenshots and actual interaction on connected hardware, never claimed
  from compilation, a Compose preview, or an emulator alone, matching this
  project's standing discipline since Phase 6 of the original v1 build.
- The Tab/Fold two-pane treatment for this new grid screen, and whether/
  how the device-adaptive-layouts spec's own Phase 4 (DeX) still applies
  once this screen replaces the timeline, are both explicitly out of
  scope here — separate future initiatives per the design spec's own
  phasing, not follow-on work bundled into this plan.
