# BeatWave Android — Unbuilt Items

**Date:** 2026-11-07  
**Source:** the 2026-09-27 roadmap (`2026-09-27-beatwave-android-holistic-audit-and-roadmap.md`), the 2026-08 backlogs and pitch, and the grid spec — cross-checked against the code and git history as of commit `294f50d`+.  
**What this is:** one flat inventory of everything planned, pitched or discovered that is **not built yet**. It adds no new analysis; the gap audit that goes beyond the existing plans is `2026-11-07-gap-audit-all-ages.md`.

## Summary

| | Count |
|---|---|
| Roadmap items (2026-09-27) | 79 |
| …built | 5 |
| …partly built | 2 |
| …unbuilt | 72 |
| Earlier-backlog items still unbuilt (Section B) | 9 |
| Known defects reported but not fixed (Section C) | 9 |
| Deliberately deferred / not recommended (Section D) | 22 (see roadmap doc) |

## A. Roadmap items

### Built since the roadmap was written

- **`PERF-V1`** — Per-callback active-range clamp plus anyActive early-out in renderScore (merges DSP-V1, PERF-3 step 1, INSTR-3, MIX-V1, TIME-V2) — *built (per-callback block culling)*
- **`GAP1-1a`** — Unique, position-aware cell semantics: pitch, bar.beat.step, collectionInfo/collectionItemInfo, onClickLabel (phase A of GAP1-1) — *built (position-aware cell descriptions)*
- **`ARCH-3a`** — Cutover parity gates as small independent commits (merges the cutover-bug list) — *built (all 8 gates; the GAP1-2 parity test is a separate, unbuilt item)*
- **`ARCH-3b`** — The swap: flip MainActivity to GridScreen, delete ArrangementScreen, port the old-screen tests — *built (MainActivity -> GridScreen, ArrangementScreen deleted)*
- **`PERF-5`** — Latest-wins conflated commit pipeline (single consumer) for engine reload (merges ARCH-2 step 1) — *built (conflated single-writer commit pipeline; no undo yet)*

### Partly built

- **`EXPORT-1a`** — Durable project store cut 1: tri-state load, quarantine-not-overwrite, unique tmp, process-wide lock (merges QA-7, PRODUCT-V2, GAP3-2 load half, GAP3-V2, ARCH-V2 lock half) — partly built: save lock + unique temp file + corrupt files quarantined + index guarded. Not built: a typed Missing/Corrupt/Ok load result, extracting the bootstrap into a pure function.
- **`GAP2-1b`** — Recording session owner in PlaybackEngine: adopt an in-flight take after Activity death, finalize from notification Stop/onCleared (M half of GAP2-1) — partly: record/stop race closed; the recording owner that survives Activity death is not built.

### Unbuilt, by stage

#### Now (before/alongside cutover) (12)

| Id | Item | Impact | Effort | Depends on |
|---|---|---|---|---|
| `DSP-1` | Stateless block-edge fades in ScoreBuilder/renderScore (merges MIX-1 edge part, TIME-2a, EXPORT-V1, INSTR-1 declick commit) | 5 | S | `DSP-V3` |
| `EXPORT-1a` | Durable project store cut 1: tri-state load, quarantine-not-overwrite, unique tmp, process-wide lock (merges QA-7, PRODUCT-V2, GAP3-2 loa… | 5 | S | none |
| `DSP-V3` | Host-side golden-render harness plus float-render/capture hook (merges INSTR-V1, MIX-V2, EXPORT-V2, QA-1) | 4 | M | none |
| `GAP1-2` | GridA11yParityTest and the parity list pasted into Tier 3.1 exit criteria | 4 | M | `GAP1-1a` |
| `GAP2-1a` | Gate lock-screen/Bluetooth transport while recording (S half of IO-4 and GAP2-1) | 4 | S | none |
| `IO-V2` | Arm-then-punch: quantize recording start to the next grid boundary, plus non-looping take blocks (merges TIME-V1; supersedes IO-1's WAV p… | 4 | S | `DSP-V3` |
| `PERF-1a` | Native -O2 -g in every variant, NDEBUG left unset in debug (S half of PERF-1, ARCH-1, QA-4 step 1) | 4 | S | none |
| `PERF-2` | In-process RT telemetry: callback histogram, xrun/granted-config poll, block-count benchmark, trace recipe (merges QA-3, MIX-4 telemetry… | 4 | M | `PERF-1a` |
| `PRODUCT-V1` | Fix liboboe.so 16 KB alignment; gate on llvm-readelf, not on a version number (merges ARCH-4) | 4 | S | none |
| `ARCH-V1` | Minimal CI: JVM tests, lint, assembleRelease, ELF alignment gate, Linux host-test job (merges QA-5 lane) | 3 | S | `DSP-V3`, `PRODUCT-V1` |
| `GAP2-V2` | Exit-reason log (ApplicationExitInfo, API 30+) plus 'take in progress' marker (merges QA-6) | 3 | S | none |
| `IO-V3` | Release the 92 MB recording buffer after each take | 3 | S | none |

#### Next (engine foundation, GridScreen-free) (25)

| Id | Item | Impact | Effort | Depends on |
|---|---|---|---|---|
| `PERF-4` | Native audition voice path: SPSC trigger ring plus 16-voice pool, mixed while paused (merges UX-2, INSTR-4b, THEORY-3, PRODUCT-5 tap-to-h… | 5 | M | `DSP-1`, `DSP-V3`, `PERF-V3`, `GAP2-1a` |
| `TIME-1` | Sample-accurate loop region plus end-of-song policy: native half (UX-3 native half) | 5 | M | `DSP-3`, `DSP-2` |
| `DSP-2` | Headroom policy plus score-aware lookahead limiter replacing the tanh (merges MIX-2, DSP-2a/2b), gated by renderVersion | 4 | M | `DSP-V3`, `DSP-1`, `PERF-V1`, `PERF-2`, `EXPORT-1b` |
| `DSP-3` | Transport ramps: shared renderCallback, pause/stop/seek ramps, CAS/pending-request fix, single-callback score-swap blend (merges TIME-2b,… | 4 | M | `DSP-V3`, `DSP-1` |
| `DSP-V2` | Record-latency compensation: measure granted modes, per-route shift at commit, then loopback calibration (2a S-M plumbing, 2b M hardware-… | 4 | M | `IO-V2`, `IO-5`, `GAP2-V3` |
| `EXPORT-1b` | Durable project store cut 2: .bak, schemaVersion plus renderVersion, sample-index treatment, relocatable refs (merges ARCH-V2, GAP3-V1, G… | 4 | M | `EXPORT-1a` |
| `GAP2-1b` | Recording session owner in PlaybackEngine: adopt an in-flight take after Activity death, finalize from notification Stop/onCleared (M hal… | 4 | M | `GAP2-1a` |
| `GAP2-2` | Crash-safe take spill: SPSC ring plus non-RT writer to .part WAV, sidecar metadata, orphan recovery (merges IO-6, GAP3-V3) | 4 | L | `GAP2-1b`, `DSP-V3`, `GAP2-V2` |
| `GAP2-V3` | Input backlog: counters first, then drain FIFO and lag tracking (merges IO-V1, IO-2 drain half) | 4 | M | `IO-V2`, `DSP-V3` |
| `IO-3` | Stream-loss recovery, audio focus, becoming-noisy (merges ARCH-7 focus half) | 4 | L | `PERF-2`, `GAP2-2` |
| `IO-5` | Recording front-end: Unprocessed preset with fallback, Stereo/Mono retry with typed errors, granted-config log, peak/clip meter with sile… | 4 | M | `IO-V2`, `DSP-V3` |
| `PERF-V2` | Chunked, crash-safe export outside engineMutex with native exception guard and shared bank (merges EXPORT-3, GAP2-7, ARCH-6 firewall) | 4 | M | `DSP-V3`, `PERF-V3` |
| `DSP-8` | Export fidelity: fix WavDecoder audioFormat (B2), TPDF-dithered 16-bit default, 24-bit and float32 WAV | 3 | S | `DSP-V3`, `DSP-2` |
| `EXPORT-7a` | Importer honors codec output format (INFO_OUTPUT_FORMAT_CHANGED); HE-AAC/PS fixture | 3 | S | none |
| `GAP2-3` | Heap-safe take finalize and import: streaming peaks, MediaCodec-to-file, single-pass native resample (merges EXPORT-7b) | 3 | M | none |
| `GAP2-5` | Capture-health detection: OS-silenced, disconnected or erroring input flagged and its range marked | 3 | M | `GAP2-V3` |
| `GAP2-6` | Memory governor: totalMem-scaled budget, onTrimMemory hook, pinned-byte accounting, retired-score trim | 3 | M | `PERF-V3`, `GAP2-V2` |
| `GAP3-1` | Backup policy: cloud = projects + prefs only, device-transfer = everything, crash logs never (merges QA-V3) | 3 | S | `EXPORT-1a`, `LOADREPORT` |
| `INSTR-V2` | Decode-time sample analysis: effective end, onset, peak/RMS, DC on SampleBuffer | 3 | S | `PERF-V3` |
| `IO-4b` | Background-capture policy decided by one manual check (merges GAP2-4, ARCH-7 mic half, GAP3-6a) | 3 | M | `GAP2-1b` |
| `LOADREPORT` | LoadReport: surface blocks whose sample failed to load or is missing (merges ARCH-6 LoadReport, GAP3-2 missing-audio half) | 3 | S | `EXPORT-1a` |
| `PERF-V3` | Pre-warm and share decoded samples; decode outside SampleBank mutex with single-flight (merges EXPORT-V3) | 3 | M | none |
| `QA-4b` | releaseTest build type: R8 on, debug-signed, FULL native symbols, run offline subset against the shipped binary (merges QA-4 step 2, ARCH… | 3 | M | `PERF-1a`, `PERF-2` |
| `QA-V2` | Native warning gate (-Wall -Wextra -Wshorten-64-to-32), clang-tidy and WavDecoder fuzz target on the host lane | 3 | S | `DSP-V3`, `ARCH-V1` |
| `EXPORT-2-defer-marker` | (placeholder retired) see defer list for LUFS/true-peak presets | 1 | S | none |

#### Then (post-cutover: GridScreen and ViewModel work) (14)

| Id | Item | Impact | Effort | Depends on |
|---|---|---|---|---|
| `INSTR-1` | Per-note envelope, one-shot ring-out and release tail, drum choke groundwork (merges THEORY-V1) | 5 | M | `DSP-1`, `DSP-2`, `INSTR-V2`, `PERF-V1`, `EXPORT-1b` |
| `PRODUCT-1` | Sample metadata schema plus project key: rootMidi/kind/bpm/tags/license/origin, note-named rows (merges INSTR-2, THEORY-1, UX-6, GAP3-9) | 5 | M | `ARCH-3b`, `EXPORT-1b` |
| `UX-1` | Single-surface Canvas grid virtualized to the 240 s song, one gesture owner, windowed semantics (merges PRODUCT-4, GAP1-V3, GAP1-1b, GAP1… | 5 | L | `ARCH-3b`, `PERF-6`, `GAP1-2`, `GAP1-1a` |
| `GAP1-7` | Transport semantics and page-follow: progressBarRangeInfo plus bar.beat stateDescription, playhead follow mode | 4 | M | `ARCH-3b` |
| `GAP1-V1` | Hardware-keyboard and D-pad/gamepad grid navigation (empty melodic cells are currently unreachable) | 4 | M | `ARCH-3b` |
| `MIX-3` | Track strips: volume, pan (centre-unity law), mute, solo as smoothed per-track atomics in the existing pass (merges UX-V2, DSP-7) | 4 | L | `DSP-2`, `PERF-V1`, `ARCH-3b` |
| `TIME-1b` | Loop region UI: bar-snapped brace on the ruler, tap-to-seek kept (UX-3 UI half) | 4 | M | `TIME-1`, `ARCH-3b`, `UX-1` |
| `TIME-3` | Metronome and count-in with grid-exact recording start | 4 | M | `IO-V2`, `DSP-3`, `ARCH-3b` |
| `UX-4` | Edit transactions: gesture-coalesced undo/redo on the conflated commit pipeline | 4 | M | `PERF-5`, `ARCH-3b` |
| `GAP1-4` | Font-scale 200%, RTL and pseudo-locale resilience: LTR-locked time axis, wrapping header, scalable row height | 3 | M | `ARCH-3b` |
| `PERF-6` | Draw-phase playhead, session refresh only on non-position change, then audible-position clock (merges TIME-7 v1) | 3 | S | `PERF-2`, `ARCH-3b`, `ARCH-8a` |
| `PRODUCT-6a` | Case-insensitive natural sort comparator and extension stripping on imported names | 3 | S | `ARCH-3b` |
| `UX-POLISH` | Bar/beat shading and ruler labels plus haptic ticks on place/delete/stretch-column crossings (UX-V3, GAP1-V2) | 3 | S | `ARCH-3b` |
| `ARCH-8a` | Kotlin 2.2 with the Compose compiler Gradle plugin on the existing AGP (step 1 of ARCH-8) | 2 | M | `ARCH-3b` |

#### Later (features on a sound foundation; several need product decisions) (23)

| Id | Item | Impact | Effort | Depends on |
|---|---|---|---|---|
| `PRODUCT-2` | One-shot-first launch pack (2a: drum kits plus kind/tags/license manifest and About/Licenses; 2b: multisampled instruments) | 5 | XL | `PRODUCT-1`, `INSTR-1`, `PRODUCT-6a` |
| `GAP1-5` | Assistive editing mode: cursor, note list, moveNote/resizeNote, one-level undo (phase 1) | 4 | L | `GAP1-V1`, `UX-4`, `PERF-4` |
| `INSTR-6` | Drum pads: pad ids, per-pad tune/decay/gain, choke groups, slicing loops into pads (merges INSTR-V3) | 4 | L | `INSTR-1`, `INSTR-V2`, `PRODUCT-2` |
| `INSTR-7` | Sustain loop with crossfade, then multisample zones (merges THEORY-V3, UX-V1) | 4 | L | `INSTR-1`, `PRODUCT-1`, `PRODUCT-2` |
| `MIX-6` | FX on a two-pass strip graph: HPF/LPF+EQ (TPT SVF), delay send, FDN reverb send, compressor (four commits) | 4 | XL | `MIX-3`, `DSP-2`, `PERF-2`, `PERF-V1` |
| `PRODUCT-3` | First beat in 60 seconds: starter templates plus one coach mark | 4 | M | `PRODUCT-2`, `PERF-4`, `ARCH-3b` |
| `TIME-4` | Tempo-elastic loops via offline Signalsmith Stretch variant cache, then BPM control (merges DSP-5, TIME-5a) | 4 | L | `DSP-4`, `PRODUCT-1`, `TIME-V3` |
| `UX-5` | Selection, copy/paste, duplicate bar, transpose, snap chips | 4 | L | `UX-1`, `UX-4` |
| `ARCH-9` | Strangler extraction of ArrangementViewModel: pure reducers first, AudioBackend seam (QA-V1), coordinators | 3 | L | `UX-4`, `PERF-6`, `ARCH-8a` |
| `DSP-4` | Band-limited SRC: Kaiser polyphase at decode, then integer-semitone pitch-variant cache | 3 | M | `DSP-V3`, `PERF-V3`, `INSTR-1` |
| `EXPORT-4` | Stem export (per-track solo runs or single-pass buses) summing to a LinearFloat 32f master | 3 | M | `DSP-8`, `PERF-V2`, `PERF-V3` |
| `EXPORT-5` | AAC-LC .m4a export as Kotlin post-process of the master WAV | 3 | M | `PERF-V2` |
| `GAP3-3` | Startup sweeper for orphaned audio plus reference-counted sample delete (dry run first) | 3 | M | `EXPORT-1b`, `GAP2-2` |
| `INSTR-4` | Per-note velocity with a dB taper and velocity lane (merges UX-8) | 3 | M | `UX-1`, `DSP-2` |
| `PERF-7a` | Xrun-driven buffer step tuner (E7 stash replacement) | 3 | M | `PERF-2` |
| `PRODUCT-6b` | Sounds picker v2: favorites, recents, search, kind/tag chips, waveform, kit-add | 3 | M | `PRODUCT-2` |
| `PRODUCT-8` | Import intelligence as suggestions: kind, YIN root, then key and BPM (merges THEORY-4) | 3 | M | `PRODUCT-1` |
| `PRODUCT-9` | Ship-readiness checklist (name clearance, versionName, licenses, offline positioning) | 3 | M | `ARCH-8b`, `GAP3-1`, `PRODUCT-2` |
| `QA-2` | TSan/ASan/UBSan/RTSan gate on the commit/reclaim protocol (needs an Oboe stub or Oboe-free ScorePublisher extraction) | 3 | M | `DSP-V3`, `ARCH-V1` |
| `THEORY-6` | Euclidean rhythm and genre presets on drum rows (one transaction) | 3 | M | `UX-4`, `PRODUCT-2`, `INSTR-6` |
| `TIME-6` | Swing and seeded humanize baked at ScoreBuilder (per-track opt-in, short blocks only) | 3 | S | `DSP-1`, `EXPORT-1b` |
| `ARCH-8b` | targetSdk/compileSdk 36, AGP 8.11, edge-to-edge (conditional on a Play release) | 2 | M | `ARCH-8a`, `PRODUCT-V1` |
| `TIME-V3` | Single gridToFrame definition exposed to Kotlin (native llround) for seek, metadata, recording start | 2 | S | none |

## B. Earlier-backlog items still unbuilt

From `2026-08-12-…audits-and-upgrades-backlog.md` and the grid spec. Status checked against the code today.

| Item | What | Status today |
|---|---|---|
| U3 | Dark theme / proper `MaterialTheme` colour scheme | Unbuilt — no `ColorScheme`, no `isSystemInDarkTheme`, manifest still uses a light Material theme |
| U4 | Compressed export (AAC/MP3) | Unbuilt — WAV only (roadmap `EXPORT-5` is the AAC version) |
| U5 | Parametric effects (EQ / reverb) | Unbuilt — no FX in the engine (roadmap `MIX-6`) |
| U6 | Configurable tracks / song length | Unbuilt — still 8 tracks, 4 minutes |
| U7 | Home-screen widget | Unbuilt |
| U8 | Undo / redo | Unbuilt — roadmap `UX-4`; the single-writer pipeline (`PERF-5`) is its prerequisite and is done |
| A6 | Rotation / adaptive-layout verification | Partly — grid rotates/folds with saved state; the Tab/Fold two-pane grid the spec deferred is not built |
| A7 | Native profiling + battery soak | Unbuilt — no profiling has been done on a release build; debug build has no `-O` flag |
| A8 | Data / privacy audit | Unbuilt as a formal pass (the code has no network access; backup rules are still missing) |

Also unbuilt from the pitch (`2026-08-24-…engineering-grade-systems-pitch.md`): S1 tempo-elastic loops, S2 per-track mixer & FX rack, S3 sidechain, S4 latency-compensated recording, S5 self-tuning I/O (the E7 buffer-tuning work sits in a git stash), S6 mastering-grade export. The roadmap items above are their re-scoped successors.

## C. Known defects reported but not fixed

From the 2026-11-07 audit. All are in the working notes with `file:line`; none is data-losing on its own.

- Global native engine pointer is unsynchronized, and a transport call after shutdown can silently create a fresh engine.
- `WavDecoder` decodes IEEE-float WAVs as noise (format tag never read); a hostile header can trigger a huge allocation / process abort (no JNI try/catch).
- `AudioImporter` takes channel count from the extractor, not the codec output (HE-AAC parametric stereo etc.), and can leak a `MediaExtractor` on unexpected exceptions.
- Denying the microphone permission twice leaves recording a dead end — no rationale, no link to Settings.
- Record / stop-record control is a bare text label, well under the 48 dp touch-target minimum.
- Scale picker dialog is not scrollable (clips on short landscape screens / large font).
- `BackgroundPlaybackServiceTest.activityDestroyed_…` fails every time on the closed Fold 5 (API 37); passes on the Tab. Cause undiagnosed.
- `GridScreenTier3CapabilityTest` has one intermittent miss on the Fold at the project-picker step.
- `GridScreenInteractionTest` is occasionally red on its first run after a force-stop (seen on Tab and emulator).

## D. Deliberately deferred

22 items are on the roadmap's own defer list with reasons (built-in synth, MIDI, streaming time-stretch, Ableton Link, chord/progression generators, 24-units-per-beat, `.beatwave` bundles, FLAC/Opus, UI string externalization, …). They are not repeated here — see *Deliberately deferred or not recommended* in the roadmap doc. Two of them are worth revisiting because of the age-range question in the gap audit: **UI string externalization** (needed for any second language, and for a read-aloud children's mode) and **MIDI input** (needed for school keyboards).

