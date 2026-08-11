# BeatWave for Android — v1 Implementation Plan

Spec: `docs/superpowers/specs/2026-08-11-beatwave-android-design.md`

Each phase should leave the app in a buildable, runnable state. Commit at
the end of each phase.

## Phase 0 — Project Scaffolding
- New Android Studio project: Kotlin + Jetpack Compose, min SDK chosen for
  Oboe/NDK compatibility (API 24+ recommended).
- Add NDK + CMake support for the native audio module.
- Set up module structure: `app` (Compose UI), `audio-engine` (C++/Oboe,
  exposed via JNI), shared Kotlin data/domain module if useful.
- Empty Oboe integration: build a native module that opens a full-duplex
  audio stream and passes silence through, confirm it runs on a device.
- **Exit criteria**: app installs and launches with a blank screen; native
  audio module builds and opens a stream without crashing.

## Phase 1 — Data Model & Local Project Storage
- Implement `Project`, `Track`, `LoopBlock`, `Sample` (per spec's data
  model) as Kotlin data classes.
- Local persistence: save/load a `Project` to app storage (choose a simple
  serialization format, e.g. JSON or protobuf).
- Bundle the small curated loop pack as app assets; load `Sample` metadata
  for them at startup.
- **Exit criteria**: unit tests cover create/save/load of a `Project` with
  tracks and loop blocks referencing bundled samples.

## Phase 2 — Audio Engine: Multi-Track Loop Playback
- Extend the Oboe module to mix multiple simultaneous looping samples with
  independent volume, trim, and start offsets, driven by a shared clock.
- Kotlin/JNI bridge: load a `Project` into the engine, start/stop/seek
  playback.
- **Exit criteria**: instrumented test verifies loop boundaries stay
  phase-locked across tracks over several minutes of playback (no audible
  drift/click) — matches the sync-accuracy testing goal in the spec.

## Phase 3 — Arrangement UI (Timeline + Bottom-Sheet Library)
- Compose screen: horizontal timeline, up to 8 fixed tracks, snap-to-grid
  placement of loop blocks, color-coded by category.
- Bottom-sheet loop library (swipe up over timeline): browse bundled loops
  by category, preview, add to a track.
- Per-loop-block editing UI: trim, volume, basic pitch.
- Play/pause/scrub controls wired to the Phase 2 engine.
- **Exit criteria**: can build a multi-track arrangement from the bundled
  pack entirely through the UI and play it back correctly.

## Phase 4 — Import via SAF
- Storage Access Framework integration: pick an audio file, copy it into
  app storage, create a `Sample` entry, surface it in the loop library
  alongside bundled loops.
- **Exit criteria**: an imported file behaves identically to a bundled loop
  in the arrangement UI.

## Phase 5 — Live Mic Recording
- Extend the native audio module to support full-duplex capture: record
  mic input while the arrangement plays back, synced to the shared clock.
- `RECORD_AUDIO` permission flow.
- Per-track record button: arm → starts/continues playback → records a new
  `Sample`/`LoopBlock` on that track → stop → block becomes editable like
  any other.
- **Exit criteria**: instrumented test confirms a recorded take lands
  aligned to the grid relative to concurrently playing tracks (per spec's
  testing goal); manual pass confirms usable monitoring latency.

## Phase 6 — Background Playback & MediaSession
- Foreground `MediaSessionService` wrapping the audio engine.
- Lock-screen/notification transport controls (play/pause/stop); playback
  survives app backgrounding.
- **Exit criteria**: manual verification — start playback, background the
  app, control it from lock screen and notification shade.

## Phase 7 — Export & Share Intents
- Render the full arrangement to an audio file (reuse the mixing path from
  Phase 2, run offline/non-realtime for a clean export).
- Native Android share sheet integration for exporting the rendered file.
- Receive shared audio files from other apps as import candidates (reuses
  Phase 4's SAF/import path).
- **Exit criteria**: manual round-trip — export a project, share it to
  another app; share an audio file into BeatWave from another app and
  confirm it's importable.

## Phase 8 — Polish & Full Manual Verification Pass
- Walk through the spec's full v1 feature list end-to-end on a device.
- Verify all "Out of Scope for v1" items are indeed absent/not started
  (no accounts, no unlimited tracks, no widget, no extra effects).
- Fix rough edges found during the pass.
- **Exit criteria**: a complete user flow — build an arrangement from
  bundled + imported + recorded loops, play it back, background it, control
  it from lock screen, export and share it — works without issues.

## Notes for the Implementer
- Phases 2 and 5 both touch the native Oboe module — consider designing the
  full-duplex capture path in Phase 2 even though it isn't wired to the UI
  until Phase 5, to avoid rework.
- Keep the JNI surface between Kotlin and the native audio module narrow
  and well-documented; it's the highest-risk interface in the app.
