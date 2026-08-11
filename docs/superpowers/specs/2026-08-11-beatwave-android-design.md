# BeatWave for Android — v1 Design

## Concept

A loop-based beat-sequencer app, closely modeled on BeatWave (iPhone). Users
drag pre-made loops onto a multi-track timeline, arrange/layer/trim them, and
play back or export the result. v1 keeps scope tight; feature expansion
(bigger loop library, effects, cloud sync, etc.) is planned for later
iterations but explicitly out of scope now.

## Core Feature Set (v1)

- **Timeline/arrangement screen**: horizontal timeline, up to 8 fixed tracks,
  snap-to-grid loop placement, max song length ~2–4 minutes.
- **Bottom-sheet loop library**: swipe-up sheet organized by category
  (Drums/Bass/Synth/Vocal), tap to preview, tap/drag to add a loop onto a
  track.
- **Small curated built-in loop pack**: one genre, a handful of loops per
  category — enough to make the app usable out of the box without licensing
  a full library.
- **Import**: users can bring in their own samples via Android's Storage
  Access Framework (SAF).
- **Per-loop editing**: trim, volume, basic pitch adjustment — matching
  BeatWave's per-block controls.
- **Playback**: play/pause/scrub the full arrangement.
- **Live mic recording onto a track**: record audio input (voice/instrument)
  in real time onto a track while the rest of the arrangement plays back,
  producing a new `Sample` that behaves like any other loop block (can be
  trimmed, moved, layered). Requires `RECORD_AUDIO` permission and a
  monitoring/latency-aware input path in the Oboe engine so the recorded
  take stays aligned to the playing arrangement.
- **Export/Share**: render the full arrangement to an audio file and share
  it via Android's native share sheet (any app — WhatsApp, Instagram, etc.).

## Android-Native Hooks (baked into v1)

- **Background playback + MediaSession**: playback survives backgrounding;
  lock-screen and notification transport controls (play/pause/stop).
- **Share intents**: export sends through the native Android share sheet;
  the app can also receive shared audio files as import candidates from
  other apps.

## Architecture

- **Language/UI**: Kotlin + Jetpack Compose.
- **Audio engine**: Google Oboe (C++ via JNI/NDK) for sample-accurate,
  low-latency multi-track loop playback and mixing, and for live input
  capture. Chosen over MediaPlayer/ExoPlayer or SoundPool because those
  introduce drift or clicking at loop boundaries when multiple tracks must
  stay phase-locked over time — unacceptable for a sequencer where tracks
  are meant to layer seamlessly. Oboe's full-duplex (simultaneous
  input+output) support is also what makes real-time mic recording against
  a playing arrangement feasible with low enough latency to stay in sync.
- **Storage**: fully local, no accounts, no backend.
  - Projects (track/loop arrangement data + references to sample files)
    saved in app storage as project files.
  - Bundled loop pack shipped as app assets.
  - Imported samples copied into app storage via SAF.
- **Playback service**: foreground `MediaSessionService` wrapping the Oboe
  engine so playback and transport controls work from the lock screen and
  notification shade.

## High-Level Data Model

- **Project**: name, tempo/BPM, list of `Track`s, created/modified
  timestamps.
- **Track**: fixed slot (1–8), list of `LoopBlock`s.
- **LoopBlock**: reference to a `Sample`, start position (grid units),
  length, volume, trim/pitch settings.
- **Sample**: source (bundled asset or imported file URI), category,
  duration, cached waveform data (for UI display).

## UX Notes

- Main arrangement screen uses a **bottom-sheet loop library** (swipe up
  over the timeline) rather than a side rail or full-screen modal — keeps
  the timeline visible and matches common modern Android interaction
  patterns.
- Track categories (Drums/Bass/Synth/Vocal) are color-coded and consistent
  between the timeline and the loop library for quick visual matching.
- Each track has a **record button** in addition to its mute/select
  controls; tapping it arms the track, starts arrangement playback (if not
  already playing), and records mic input into a new loop block on that
  track until stopped.
- Overall screen structure (timeline/grid of tracks as the home view, a
  slide-up library/sound browser, per-track production controls, and a
  record action) is modeled directly on BeatWave's own layout — grid-based
  arrangement view, sound library access, and a dedicated recording
  interface — adapted to Android conventions (bottom sheet instead of
  modal, back-button navigation, Material controls) rather than reinvented.

## Out of Scope for v1 (explicitly deferred)

- Accounts / cloud sync.
- Unlimited tracks or song length.
- Home-screen widget.
- Expanded loop library / additional genres.
- Effects or mixing beyond volume + trim + basic pitch (e.g., EQ, reverb).
- Collaboration / multi-user features.

## Testing Approach

- Unit tests for data model and arrangement logic (Kotlin).
- Instrumented tests for Oboe engine sync accuracy — loop boundaries must
  stay phase-locked over extended playback, and mic recordings must land
  aligned to the grid relative to concurrently playing tracks.
- Manual verification pass for MediaSession controls (lock screen,
  notification, headset buttons) and the share-sheet import/export
  round-trip.
