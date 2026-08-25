# BeatWave Android — Grid/Piano-Roll Frontend Redesign

## Concept

BeatWave's current frontend is a timeline: 8 fixed track rows, each a
horizontally-scrollable lane you drag arbitrary loop blocks onto, edited via
a separate per-block dialog. This spec replaces that screen entirely with a
grid/piano-roll interface, inspired directly by the real iOS app "Beatwave"
(MWM) — reference: a review video the user shared
(`youtube.com/watch?v=h1ffccclSMs`), confirmed on inspection to show exactly
this mechanic: the X-axis is time, the Y-axis is pitch (scale-constrained
note rows for melodic instruments, named per-sound rows for drum kits), you
tap to place a hit, and a "Sounds" picker assigns which instrument each
grid belongs to.

This is a full replacement, not a new mode alongside the old screen. The
native audio engine, the sample library, the project/track persistence
layer, and the existing Loop Library browsing UI and per-block editor
dialog are **not** rebuilt — this spec is specifically about wiring those
already-built systems into a new grid frontend, not re-architecting the
engine underneath them. Everything below assumes the native engine
(`AudioEngine`/`MixEngine`/`ScoreBuilder`) is untouched.

This directly resumes and reframes an earlier parked research thread (a
separate "Padtune" app concept exploring the same real iOS app) — that
research is no longer needed standalone; its findings are folded in here,
scoped as integrating into BeatWave itself rather than a new app.

## Scope decomposition

Two pieces, built in this order, matching how this project has handled
every other major initiative (v1's own phased build, the device-adaptive-
layouts spec's shared-foundation-then-branches structure):

1. **This spec's scope (compact-first):** the new grid screen at phone
   width — data model changes, track switcher, grid rendering for both row
   modes, gesture handling, the Sounds picker repurposed for instrument
   assignment, the scale system, migration of existing saved projects, and
   replacing the old timeline screen. Verified on real hardware, matching
   this project's standing discipline.
2. **Explicitly deferred, separate future initiative:** a Tab/Fold
   two-pane treatment for the new grid screen, mirroring the shape of the
   old timeline screen's own device-adaptive-layouts phases (shared
   foundation, then a Tab branch, then a Fold branch). Not scoped here —
   picked up later, once the compact grid is built and validated. The
   existing two-pane/`WindowSizeClass` work built for the old timeline
   screen does not carry over automatically; it was built for a screen
   this spec removes.

## Data model

Two additions to the existing model (`Track`, `LoopBlock`), nothing else
touched — no new top-level entities, no change to `Project`, `Sample`, or
any native/engine code:

### `Track.assignedSampleIds: List<String>`

Replaces the old "a track can hold any mix of arbitrary samples" model
with an explicit instrument/kit assignment, since a grid needs one
coherent row-set. One rule handles both instrument families that already
exist in the sample library (`SampleCategory`):

- **Melodic track** (assigned sample's category is `BASS`/`SYNTH`/
  `VOCAL`): exactly one entry. The grid's rows are pitch offsets of that
  one sample (see "Pitch rows" below). Picking a different sample via the
  Sounds picker *replaces* the current one — a melodic track has one
  voice.
- **Drum track** (category `DRUMS`): one or more entries, each becoming
  its own named row at that sample's own natural pitch — no pitch-
  shifting. Picking a sample via Sounds *adds* it to the set. This is how
  a BW808-kit-like multi-row drum grid gets built from BeatWave's
  existing individual one-shot samples (e.g. Basic Kick + Basic Snare →
  a 2-row kit) without inventing a new "Instrument"/"Kit" data structure.
- **Unassigned track** (`assignedSampleIds` empty): the grid shows a
  single centered prompt ("Tap Sounds to choose an instrument") instead
  of a zero-row grid.

### `LoopBlock.pitchRow: Int?`

The row a block is dropped on. For a melodic track, `pitchSemitones`
becomes **derived** from this (an offset from the assigned sample's
natural pitch), not set by an independent slider — `LoopBlockEditorDialog`
keeps its Pitch slider for the long-press fine-tune case (see
"Interaction"), but grid placement is the primary way pitch gets set now.
`null` for a drum-track block, where the row selects *which* assigned
sample plays, not a pitch offset.

### Migration for existing saved projects

On load, if a track's `assignedSampleIds` is empty, auto-populate it from
whatever distinct sample IDs already exist among that track's current loop
blocks (dedup, first-seen order). Existing projects become immediately
usable in grid view with no explicit migration screen and no data loss —
a track that already happened to hold only one sample's blocks (the common
case for most real usage) migrates into exactly the melodic-track shape
above; a track with several different DRUMS-category samples migrates
into a multi-row kit directly.

### Timing

Unchanged. The grid's columns are the exact same grid-unit system
(`GridConstants`, 1 grid unit = 1/16th note, 4 units/beat) already driving
`ScoreBuilder` and the rest of the engine — no new timing concept, no
native changes.

## Screen structure

Single main screen, replacing `ArrangementScreen` as the app's primary
destination:

- **Top bar:** project title (tap to switch/rename/delete/create, as
  today) + a horizontally-scrollable strip of track pills, one per track
  slot, each showing its assigned instrument's name (or "Empty").
- **Middle:** the grid for whichever track is currently focused.
- **Bottom bar:** Play/Stop/elapsed-position (as today) + a "Sounds"
  button.

### Track switcher

Tapping a pill switches the visible grid to that track's row-set and
placed blocks. The currently-focused track's pill is visually highlighted.

### Sounds picker

Reuses the existing `LoopLibraryContent` composable (category chips, card
list) directly — no new browsing UI. Repurposed as the instrument-
assignment picker for whichever track is currently focused: a card's "Add"
action replaces the melodic track's one sample, or adds a row to a drum
track's kit, per the `assignedSampleIds` rule above. A card already in the
focused drum track's kit shows "Remove" instead of "Add" (melodic tracks
don't need this — picking a different sample already replaces the one
they have). Removing a sample from a kit takes it out of
`assignedSampleIds`, but does **not** delete any notes already placed on
its row — same non-destructive rule the scale system uses below: a row
with existing notes stays visible even once its sample is no longer in
the assigned set, so re-adding the same sample later reunites it with the
notes still sitting there.

### Grid rendering

- **Rows:** pitch rows (melodic) or named sample rows (drum), per track
  type above. Vertically scrollable if the row count exceeds the
  viewport (a full chromatic ±12 range, or a large drum kit).
- **Columns:** grid units, horizontally scrollable for songs longer than
  one screen — same scroll/width mechanics `ArrangementScreen`'s timeline
  already has, just applied to a grid instead of block lanes.
- **Playhead:** a vertical line sweeping across the grid during playback,
  driven by the same `currentFrame`/50ms-poll state `PlaybackControlBar`
  already reads — no new playback-position plumbing.

## Interaction

- **Tap an empty cell** → place a one-shot note there (default 1-grid-
  unit length).
- **Tap-and-drag horizontally from an empty cell** → place a stretched
  note spanning the dragged columns, for riff-style samples that want to
  play across a span (`LoopBlock.lengthGridUnits`, same field the old
  timeline used).
- **Tap a filled cell** → delete it.
- **Long-press a filled cell** → open `LoopBlockEditorDialog` (unchanged
  dialog — volume/trim/fine pitch), reused as-is for the cases a quick
  tap-to-place/delete doesn't cover.

## Pitch rows and the scale system

A chip on the grid screen itself (near the track switcher — this gets
changed while actively composing, not buried in a separate Settings
screen), e.g. **"C Major ▾"**, opens a compact picker: 12 root notes × a
small preset list of common scale types (Major, Minor, Pentatonic Major,
Pentatonic Minor, Chromatic). Deliberately not a full music-theory engine
— just enough presets to cover the common cases without unbounded scope.

**Chromatic is just another scale choice**, not a separate toggle: picking
"Chromatic" shows all ±12 semitone rows unfiltered. One mental model, one
control.

**Switching scales never hides or deletes a placed note.** The row-
visibility rule is: show every row in the current scale, *plus* any row
that currently has a note placed on it, even if that row falls outside the
current scale. Flipping between scales only reveals/hides *empty*
in-between rows — anything already placed stays exactly where it is and
stays visible.

This is a per-session UI preference (like today's `showLibrary` state),
not a persisted project field — nothing added to the save format.

## What's explicitly out of scope (this pass)

- **Tab/Fold two-pane layout** for the new grid screen — deferred, see
  "Scope decomposition" above.
- **A full music-theory scale engine** (custom/exotic scales, modes beyond
  the preset list) — the small preset list covers the common cases; more
  can be added later without a redesign.
- **A real multi-sound "Instrument"/"Kit" data structure** — drum tracks
  get kit-like behavior via `assignedSampleIds` grouping existing
  independent samples, not a new bundled-instrument concept. Worth
  revisiting if BeatWave's sample library grows a genuine multi-sound kit
  format later.
- **Per-note velocity** — a note's volume still comes from
  `LoopBlockEditorDialog`'s existing Volume slider (long-press to reach
  it), not a tap-position/pressure-based velocity control.
- **S-Pen precision input, DeX support** — these are separate, already-
  scoped follow-on phases of the *old* timeline screen's own spec
  (`2026-08-18-device-adaptive-layouts-design.md`), targeting controls
  (`LoopBlockEditorDialog`'s sliders) that this new screen still reuses
  as-is. S-Pen work in progress on `device/galaxy-tab-s9fe` continues
  independently and isn't invalidated by this spec. Whether the new grid
  itself (tap/drag placement, not just the reused dialog's sliders) gets
  its own S-Pen treatment is an open question to revisit whenever the
  grid screen is next adapted for the Tab specifically — not bundled into
  this pass, and not necessarily the same moment as the two-pane phase.

## Removed vs. reused

- **Removed** once the new grid screen is verified working:
  `ArrangementScreen`, `TrackRow`, and the timeline-specific rendering/
  scroll code — no dead parallel UI left alongside the new screen.
- **Reused directly, just re-wired:** `LoopLibraryContent` (Sounds
  picker), `LoopBlockEditorDialog` (long-press editor), `PlaybackControlBar`
  (transport bar), the project-picker top-bar behavior, and the entire
  native engine / persistence / export pipeline underneath all of it.
