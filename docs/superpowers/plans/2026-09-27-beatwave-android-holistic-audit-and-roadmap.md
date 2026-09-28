# BeatWave Android — Holistic Audit & Sequenced Roadmap

**Date:** 2026-09-27
**Builds on:** `2026-08-17-beatwave-android-engine-upgrades-backlog.md` (B1–B4, E/T/D/X/C items),
`2026-08-24-beatwave-android-engineering-grade-systems-pitch.md` (S1–S6),
`2026-08-12-beatwave-android-audits-and-upgrades-backlog.md` (A1–A8 / U1–U8), and the grid-sequencer
design spec + implementation plan (Tiers 0–3). This document cross-references them; it does not re-derive them.
**Scope note:** a *scoping and roadmap* document, matching the format of the engine-upgrades backlog and
the engineering-grade pitch. **Nothing here is implemented.** The item ids (`DSP-1`, `EXPORT-1a`, …) are
labels defined by this document; they do not exist in any earlier plan. Where an item reverses or amends an
earlier document, that is called out under *Corrections to existing plans* rather than silently overwritten.
**Status:** proposal. Several items are gated on the product decisions listed under *Decisions needed*.

## Why this document exists

The grid-sequencer redesign has reached feature parity with the old timeline screen (Tier 3 prep, commits
`1ce4d9d` and `891254b`); the only remaining planned step is the cutover itself (switch `MainActivity` to
`GridScreen`, delete `ArrangementScreen`), and that is blocked only on real-hardware verification because the
two test tablets were occupied at the time. The question that prompted this document was: *what more can we
add to and improve upon within this app, judged as a whole, by the standards of a strong engineer and a strong
audio engineer?*

The earlier plans cover the native engine (the backlog and S1–S6) and the UI redesign (the grid spec and plan)
in depth, but none of them reviews the app as one system against the new grid paradigm — hundreds of short
blocks instead of a few long ones, tap-driven editing, a melodic instrument model built out of repitched loops.
Nor do they cover data safety and process lifecycle, accessibility of the grid, or how any of this gets
*measured*. This is that pass.

## Headline

BeatWave's engine architecture — a stateless mixer that derives every output frame's read position from the
absolute transport counter, fed by an immutable score published with one atomic swap — is sound and unusually
testable. What is missing is what surrounds it:

- **Nothing can measure the audio.** No test reads a rendered sample, and every native timing figure gathered so
  far comes from a debug build with no optimization flag.
- **The signal path is unfinished.** Every note-off, pause and seek is a hard step (a click). The mix has no
  headroom policy and runs into an always-on `tanh`. Pitch shifting and decode-time resampling use linear
  interpolation.
- **Users can lose work.** A project that fails to decode is replaced by a blank one; a recording take has no
  owner and lives only in RAM until Stop; Auto Backup is unconfigured.
- **The grid is not yet an instrument.** A tap makes no sound, there is no undo, no selection, no loop region,
  and melodic notes are truncated slices of whole loops with no envelope.
- **The Tier 3 cutover has regressions its exit criteria do not list** (no seek, a 64-column ceiling, invisible
  legacy blocks, tap-vs-scroll, and a TalkBack regression).

**Recommended order:** measurement harness and an optimized build → data safety and declick → headroom,
transport ramps, audition → recording truth → and only then effects, instruments and content. Nearly all of the
engine work verifies offline; the scarce tablets gate only the cutover sign-off, latency calibration, the
granted stream configuration, and one background-microphone check.

Read in this order: *At a glance* → *Verification status* → *Roadmap* → *Decisions needed*. Appendices are
reference material.

## At a glance

### Top ten

| # | Id | Item | Why |
|---|----|------|-----|
| 1 | `DSP-1` | Stateless block-edge fades (declick every note) | The most audible defect (clicks up to -3 dBFS on every grid note), fixed cheaply while keeping the mixer stateless. |
| 2 | `EXPORT-1a` | Durable project store, cut 1 | Prevents total, silent loss of the arrangement (and the sample index) on a parse failure or torn save. |
| 3 | `PERF-4` | Native audition voice path | A tap currently makes no sound, the largest playability gap for a sequencer. |
| 4 | `ARCH-3a` | Cutover parity gates including accessibility | Unblocks the redesign and closes the hidden regressions (invisible legacy blocks, 64-column cap, no seek, tap-vs-scroll, identical TalkBack cells). |
| 5 | `GAP2-1a` | Recording session gate and owner | An orphaned or lock-screen-corrupted take loses the whole recording and blocks all future recording until process death. |
| 6 | `DSP-V3` | Host-side golden-render harness | Makes every DSP claim measurable without a scarce device and protects the bit-exact stateless promise. |
| 7 | `DSP-2` | Headroom policy plus lookahead limiter (renderVersion-gated) | Replaces an always-on tanh that distorts at every level in a mix running 30% over full scale. |
| 8 | `IO-V2` | Grid-quantized recording start plus non-looping takes | Removes a random 0-167 ms placement error and the tail replay; base for latency compensation. |
| 9 | `TIME-1` | Sample-accurate loop region | Phrase-based composing needs looping and the stateless design makes it nearly free. |
| 10 | `PRODUCT-1` | Sample root/kind/bpm metadata plus project key | Row 0 is an arbitrary pitch today; scales and cross-track harmony are incoherent, and it unlocks chords, zones and analysis. |

### Quick wins (small effort, high impact)

- **`DSP-1`** — Stateless block-edge fades in ScoreBuilder/renderScore. S effort, impact 5. A per-block gain derived from framesSinceBlockStart, no new state, fixes the click on every grid note; gate on abutting successors so seamless joins stay bit-identical.
- **`EXPORT-1a`** — Project store cut 1: tri-state load, quarantine corrupt file, unique tmp, process-wide lock. S effort, impact 5. Closes the silent blank-project overwrite, the index-wipe and the concurrent-save crash with one result type and one lock.
- **`PERF-1a`** — Native -O2 -g in every variant. One CMake line, verified by compile_commands.json; keeps NDEBUG unset so tripwires stay. Makes every later timing number meaningful.
- **`PRODUCT-V1`** — Fix liboboe.so 16 KB alignment. Version bump plus a readelf check; the acceptance test, not the version number, is the gate.
- **`IO-V2`** — Quantize recording start to the grid plus non-looping take blocks. About 10 lines plus a loop=false flag; removes the random 0-167 ms early placement and the head replay.
- **`PERF-V1`** — Per-callback active-range clamp in renderScore. Bit-identical hoist of two int64 compares; export-time cliff fix and prerequisite for every per-block DSP item.
- **`GAP2-1a`** — Gate lock-screen/Bluetooth transport while recording. Drop three availableCommands and make three handlers no-ops while isRecording; prevents corrupted takes.
- **`GAP1-1a`** — Unique, position-aware grid cell semantics. About 30 lines; substring-compatible with the 30 existing assertions and fixes about 209 identical TalkBack stops.

## How this was produced, and how far to trust it

A read-only, multi-agent audit (33 agents, roughly 71 minutes of wall-clock):

1. **Twelve domain analysts** — DSP fidelity, real-time performance, instruments/note model, mixer/FX, timing,
   recording/I/O, music theory, UX, export/data, QA/observability, product/content, architecture/platform.
   Each was told to read the three planning docs first, was seeded with *unverified hypotheses to confirm or
   refute*, and had to cite `file:line` facts opened during the run.
2. **One adversarial verifier per domain**, tasked with refuting each idea and each bug and adding what the
   analyst missed.
3. **Synthesis**, then a **completeness critic**, which found three gaps (accessibility depth for the new grid;
   memory pressure and process death; privacy of recordings/imports and backup). Each gap was analysed and
   verified the same way (ids `GAP1`–`GAP3`), and a final pass folded them in and resolved the critic's seven
   contradictions.

**Counts:** 121 ideas proposed; 6 confirmed, 113 refined, 2 rejected by verifiers; 44 more added by verifiers
→ 163 verified ideas, merged into 79 roadmap items; 85 bugs kept; 64 hypotheses refuted (Appendix A).

**Limits, stated plainly:**

- Verifiers almost never rejected anything (2 of 121). Read "verified" as *cross-read and corrected*, not
  *independently proven*. That is why the next section separates what was re-checked by hand from what was not.
- Effort and impact ratings are the agents' estimates, not measurements.
- Nine agents' work carried a notice that the automated safety review was rate-limited while it ran. The agents
  were read-only by instruction; the repository was checked afterwards (HEAD unchanged, no new or modified files
  beyond the five test files another session already had uncommitted).

## Verification status

### Re-checked by hand against the code and assets

| Claim | Result |
|-------|--------|
| Native debug build has no optimization flag | **Confirmed.** `app/.cxx/Debug/*/arm64-v8a/compile_commands.json` shows `-g … -fno-limit-debug-info` and no `-O`; `CMakeLists.txt` sets no build-type flags; release minification is off (`app/build.gradle.kts:37`). *Not checked:* that a release build gets `-O3` (the audit says the toolchain default provides it, and also reports that no release configuration has ever been built or run under a test — I did not verify either). |
| Auto Backup unconfigured | **Confirmed.** `AndroidManifest.xml:28` has `allowBackup="true"`; `res/xml/` contains only `file_paths.xml`, so there are no backup rules. The 25 MB quota arithmetic is audit-reported. |
| A project that fails to decode is replaced by a blank one | **Confirmed.** `ProjectRepository.decodeProjectFile` returns `null` both for a missing file and for `SerializationException` / `IllegalArgumentException` / `IOException`; `ArrangementViewModel.kt:311-333` then builds a new `Project` with the *same id* and saves it. Note that saves themselves are atomic (temp file + rename), so a torn file is unlikely — the realistic trigger is a decode failure (schema drift, an older app reading a newer file); the consequence is total. |
| A tap makes no sound (no audition path) | **Confirmed.** No `audition`/`previewNote`/`playNote`/`triggerNote`/`oneShot` symbol anywhere in `app/src/main`; the `AudioEngine` public surface has no one-shot trigger. |
| Cutover: 64-column ceiling, no seek, identical cell text | **Confirmed.** `GRID_COLUMNS = 64` (`GridScreen.kt:208`); `seekToGridUnit` is wired only from `ArrangementScreen.kt:385`; every cell gets one of two position-free strings (`GridScreen.kt:1021-1024`), so 25 rows × 64 columns = 1,600 identical stops. The "about 209 per screen" figure is audit-reported. |
| Cutover: legacy melodic blocks are invisible but still play | **Partly re-checked.** `TrackMigration.kt:18-22` backfills only `assignedSampleIds` and never `pitchRow` (read). That `MelodicGrid` skips `pitchRow == null` blocks (audit cites `GridScreen.kt:734`) matches the Tier 3 work done earlier but line 734 was not re-opened for this document. |
| `liboboe.so` 1.9.0 is 4 KB-aligned | **Confirmed** with `llvm-readelf` (NDK 28.2.13676358) on the built debug intermediates: `PT_LOAD align=0x1000` on arm64-v8a, armeabi-v7a and x86_64. `libbeatwave_audio.so` is already `0x4000`. *Not checked:* the Google Play deadline the audit quotes, and whether Oboe 1.9.3 is the smallest passing version — the acceptance test is `readelf`, not a version number. |
| Recorded takes land early and their tail replays the head | **Placement confirmed by reading:** `GridConstants.kt:32-35` floors the start frame to a grid unit and `:41-44` rounds the length up; `ScoreBuilder.cpp:83-93` places the block at `unit × framesPerGridUnit` with a loop period taken from the take's trimmed length. Worst case is 8,000 frames = 166.7 ms at 90 BPM / 48 kHz. The head replay follows from the loop modulo in `MixEngine.cpp` (read, not run). |
| Headroom and click magnitudes (recomputed from the bundled WAVs) | **Reproduced, with small differences.** Bundled loops are mono 44.1 kHz with peaks of −0.9 (kick), −1.4 (snare), −1.9 (bass) and −3.1 dBFS (arp). Layering kick+snare+bass+arp at unity peaks at **+7.6 dBFS with 30.9% of samples over full scale** (audit: +8.1 / 30.7%; difference is the span analysed). `tanh` on a −3 dBFS 1 kHz tone gives a third harmonic at **−28.6 dB** (matches the audit). A note cut after one 16th at 90 BPM ends at −0.70 (arp), +0.27 (bass), +0.14 (kick) — match — and −0.50 for `vocal_ah` (audit: 0.61; not reconciled). |

### Reported by the audit and **not** re-checked

Everything else, in particular: Google Play policy dates; all effort and impact ratings; CPU/xrun estimates
(for example "under 1% at 12,800 blocks"); recording memory figures (92 MB native buffer, a 138 MB Java-heap
spike at finalize); the 515 ms Fold 5 input-latency figure (one log line, likely a fast-path miss); export render
time at 1,000 blocks; and the behaviour of lock-screen transport commands during a take. Treat these as leads to
measure — several roadmap items exist precisely to measure them first.

## Critical defects

Ten defects the audit ranks above feature work. The *Re-checked* line says how much of each I confirmed
independently (see the table above).

1. **Project file silently overwritten on any parse failure or torn save.**
   *Why it matters:* Total, unrecoverable loss of the arrangement. ProjectRepository returns null for both missing and unparseable, init saves a blank project under the same id, concurrent saves share one tmp name and the fallback can throw. ImportedSampleIndex has the same pattern, so one corrupt index makes the next import drop every prior sample (their blocks then go silent).  
   *Evidence (audit):* ProjectRepository.kt:36-43,60-75; ArrangementViewModel.kt:311,320-335,620-628; ImportedSampleIndex.kt:41-70 (per-instance @Synchronized, fixed tmp name, emptyList on failure). Fixed by EXPORT-1a/1b.  
   *Re-checked:* Load path and blank-project fallback confirmed; the exact trigger likelihood is low (saves are atomic) but the consequence is total. The `ImportedSampleIndex` variant is audit-reported.

2. **A take has no owner and no durability: Activity death orphans it, lock-screen transport corrupts it, RAM-only until Stop.**
   *Why it matters:* onCleared returns early when the transport is running, the new ViewModel never adopts isRecording, so Stop only resets transport and every later Record fails with 'microphone unavailable'. Notification Pause/Stop/Seek bypass the VM guards and jump-cut or starve the take. The whole 240 s take (92 MB native, 138 MB Java heap spike at finalize, OOM not caught) is lost on any process death. Read errors and OS mic silencing become digital zeros with no UI signal.  
   *Evidence (audit):* ArrangementViewModel.kt:278-286,1262,1511; AudioEngine.cpp:353-358,483,500-503,676-688; BeatWavePlaybackService.kt:92-94,217-232 vs ArrangementViewModel.kt:815,848; ArrangementViewModel.kt:1341-1344. Fixed by GAP2-1a/1b, GAP2-2, GAP2-3, GAP2-5.  
   *Re-checked:* Not re-checked.

3. **Every note-off and pause/seek is a hard step (click).**
   *Why it matters:* Grid notes are 1-unit truncations of full-length samples. Last-sample step at the cut: 0.70 synth_arp (-3.1 dBFS), 0.61 vocal_ah, 0.27 bass_riff, 0.14 kick. Pause memsets and seek is a bare store, so nearly every gesture clicks.  
   *Evidence (audit):* MixEngine.cpp:52-56,79; AudioEngine.cpp:101-103,261-277; no fade/ramp/envelope code anywhere in app/src/main. Fixed by DSP-1 then DSP-3.  
   *Re-checked:* Hard cut at block end read in `MixEngine.cpp`. Click magnitudes recomputed: arp (0.70), bass (0.27) and kick (0.14) match the audit; `vocal_ah` does not (0.50 recomputed vs 0.61 reported).

4. **Grid cutover regressions not in the Tier 3 exit criteria, including accessibility.**
   *Why it matters:* After the swap: legacy melodic blocks (pitchRow null) play but are invisible and undeletable; anything past column 63 is unreachable; GridScreen has no seek and the only TalkBack seek action dies with ArrangementScreen; every grid cell announces the same position-free string (about 209 stops per screen, and old block descriptions did not carry over); a vertical scroll starting on an empty cell places a note; pitch dialog desyncs row from sound; melodic sound swap leaves old notes on the old sample; focus/scale reset on fold; a share intent re-imports on every recreation; Tab/Fold two-pane layouts regress with no replacement.  
   *Evidence (audit):* GridScreen.kt:208,734,1020-1026,1054-1099; TrackMigration.kt:18-22; ArrangementViewModel.kt:531-539,579-602; MainActivity.kt:48-50,74-86; seekToGridUnit called only from ArrangementScreen.kt:385; CustomAccessibilityAction only at ArrangementScreen.kt:667. Fixed by ARCH-3a and GAP1-2, GAP1-1a.  
   *Re-checked:* Partly re-checked: 64-column ceiling, missing seek, identical cell text and the migration gap confirmed; the rest audit-reported.

5. **Recorded takes are placed early by up to one 16th and their tail replays the take head.**
   *Why it matters:* Start frame is floored to the grid with no sub-grid offset: up to 8,000 frames (166.7 ms at 90 BPM/48 kHz) of random error. The block is ceil'd but the loop wraps at the take length, so up to 158 ms of the head replays. Input FIFO is never drained and short reads ratchet lag upward, so a one-shot latency calibration goes stale.  
   *Evidence (audit):* GridConstants.kt:32-44; ScoreBuilder.cpp:83-93; MixEngine.cpp:56; AudioEngine.cpp:340-355,529 vs 483; RecordingGridAlignmentTest tolerates < perUnit. Fixed by IO-V2 then GAP2-V3 and DSP-V2.  
   *Re-checked:* Placement error confirmed by reading (see table); the input-FIFO/lag claims are audit-reported.

6. **Mix is gain-staged into an always-on tanh.**
   *Why it matters:* Kick+snare+bass+arp at unity peaks +8.1 dBFS with 30.7% of samples over full scale. No trim or headroom, tanh distorts at every level (H3 -28.6 dB at -3 dBFS), hard clamps sit behind it in the HAL and WavWriter.  
   *Evidence (audit):* MixEngine.cpp:10-14,85-89; ScoreBuilder.cpp:109; numpy port of the bundled loops. Fixed by DSP-2, gated by renderVersion.  
   *Re-checked:* Headroom numbers and `tanh` distortion recomputed and match.

7. **All device-test timing data comes from an -O0 native build; shipped Release has never been built or tested.**
   *Why it matters:* Every xrun/headroom figure and the pending A7/E7 verification describe a binary that never ships. The shipped -O3 -DNDEBUG binary and R8 output have never run under any test.  
   *Evidence (audit):* app/.cxx/Debug/*/compile_commands.json has '-g ... -fno-limit-debug-info' and no -O; no Release configure exists; app/build/outputs/apk has only debug and androidTest. Fixed by PERF-1a/1b, QA-4b.  
   *Re-checked:* Confirmed (no `-O` in the debug compile commands).

8. **liboboe.so is 4 KB page-aligned.**
   *Why it matters:* Fails to load on 16 KB page-size devices and blocks Play updates for API 35+ apps from Feb 1 2027. The app's own libs are already aligned, so Oboe is the only offender.  
   *Evidence (audit):* llvm-readelf: liboboe.so LOAD align 0x1000 vs libbeatwave_audio.so 0x4000; build.gradle.kts:91 pins oboe 1.9.0. Fixed by PRODUCT-V1 (version to be confirmed by readelf, not assumed).  
   *Re-checked:* Alignment confirmed with `llvm-readelf`; the Play deadline is audit-reported.

9. **Export can abort the process and starves the UI; Auto Backup is unconfigured.**
   *Why it matters:* Export allocates one 92 MB float vector with no try/catch (bad_alloc means SIGABRT), holds engineMutex for the whole render (queuing edits and stopRecording, so a take overshoots), duplicates a full SampleBank per export, and silently drops blocks whose sample failed to load. Separately allowBackup=true with no rules means recordings/imports (46 MB take, up to 96 MiB import) push the backup over the 25 MB cap, so even the KB-sized project JSON stops backing up.  
   *Evidence (audit):* audio_engine_jni.cpp:346-352,390-406; PlaybackEngine.kt:161-170; ProjectPlaybackController.kt:62,67-76,110-125; AndroidManifest.xml:28. Fixed by PERF-V2, LOADREPORT, GAP3-1.  
   *Re-checked:* Backup configuration confirmed; the export-allocation and lock claims are audit-reported.

10. **Transport race: a concurrent seek/stop can be overwritten by the in-flight callback's fetch_add.**
   *Why it matters:* A Stop can leave the transport at 960 frames, so the next Play skips 20 ms. It worsens once loop wrap or tempo rescale rewrite the transport from the audio thread.  
   *Evidence (audit):* AudioEngine.cpp:94-103 bare stores vs :295,365 load and fetch_add. Fixed inside DSP-3.  
   *Re-checked:* Not re-checked.

## Themes

- **Measure first: harness, optimized build, telemetry, CI.** Nothing today can read a rendered sample or trust a timing number. A host-side golden/null/spectral harness (with a -m32 build and fuzz target), an optimized native build, callback telemetry, exit-reason logging and CI make every later DSP change test-first and make 'measure before optimizing' real.
- **Clean signal path: declick, headroom, transport ramps, SRC, export fidelity.** Foundation DSP before any effect or instrument: stateless block-edge fades, a renderVersion-gated headroom policy plus lookahead limiter replacing the tanh, transport ramps on a separate ramp counter, band-limited resampling, dithered/float export.
- **Engine scalability without breaking statelessness.** Per-callback active-range clamping fixes the scan cost bit-identically. Chunked export, shared decoded buffers with rate-keyed banks, ordered commits and prewarm remove stalls. Bucket indexes and incremental score edits are unnecessary at realistic block counts.
- **Data safety, durability and lifecycle.** Never lose a project or a take, never leave the engine frozen on a dead stream or an orphaned recording, keep private audio out of the wrong backups, and stage the cutover so its hidden regressions are gated.
- **Play it live: audition, transport, canvas, undo, accessibility.** The grid must make sound on touch, loop a phrase, follow the playhead, scale to 240 s, forgive mistakes, and stay operable by TalkBack, Switch Access, keyboard and D-pad. That takes a native voice pool, a loop region, a windowed Canvas grid with one gesture owner and real semantics, and an undo/edit pipeline.
- **Recording truth: placement, latency, chain hygiene, health.** Fix the random 0-167 ms placement error first (cheap, deterministic), then measure granted input mode, drain/track input lag, compensate the systematic round trip, clean the capture chain (Unprocessed preset, DC, meter) and flag OS-silenced or disconnected input.
- **Pitch and key coherence.** Row 0 is 'whatever pitch the sample has'. Sample root/kind/bpm/origin/license metadata plus a project key make note names, scales and cross-track harmony real, applied at score build with a null key meaning identity.
- **Instrument realism and content.** Envelope/one-shot ring-out, then a one-shot-first kit pack, pads/choke/slicing, sustain loops and zones. Content and schema work dominates the DSP.
- **Mix and FX architecture.** Strips (gain/pan/mute/solo) first in the existing single pass, then a two-pass fixed-topology graph for EQ, reverb/delay sends and a compressor, gated on headroom, telemetry and culling.
- **Time and groove.** Tempo becomes editable only via an offline stretch-variant cache (keeps the mixer stateless). Swing is a cheap baked shift. Count-in gives deterministic recording starts. One gridToFrame definition prevents off-by-one drift.
- **Release, export and interchange.** Chunked crash-safe export, correct import format handling, AAC sharing, stems. Store-readiness items only matter if a Play release is decided.

## Sequencing and dependencies

Native and offline-verifiable work (harness, -O2, declick, culling, telemetry, headroom, chunked export, recording placement, take durability, backup rules) never touches GridScreen or ArrangementScreen, so it proceeds while the tablets are busy. The cutover gates (ARCH-3a) land as small independent commits, the a11y parity test (GAP1-2) and cell semantics (GAP1-1a) join the exit criteria, and ARCH-3b stays a small reversible swap gated only on the two pending hardware tests. Do not start UX-1, ARCH-9, UX-5 or the heavier GridScreen items until after the swap; ARCH-8a (Kotlin 2) then PERF-6 measure recomposition before UX-1.

Hard dependencies: DSP-V3 before DSP-1/2/3/4/8, PERF-V1, IO-V2, PERF-V2. PERF-1a before PERF-2 before any 'is it fast enough' call. DSP-1 and PERF-V1 before INSTR-1. DSP-2 before INSTR-1, MIX-3 and MIX-6. EXPORT-1b (renderVersion) before DSP-2, INSTR-1, PRODUCT-1. PERF-V3 before PERF-4 and PERF-V2. PERF-5 before UX-4. DSP-3 before TIME-1. IO-V2 then IO-5 then DSP-V2. PRODUCT-1 before chords, zones, analysis. DSP-4 before tempo elasticity and after INSTR-1.

Contradictions resolved (all seven the critic raised): (1) UX-1 single semantics node vs shipped a11y work: the node is only a test oracle beside virtualized per-cell/per-note semantics with Place/Delete actions; a11y parity (GAP1-2, GAP1-1a) is a cutover gate, and DnD fallbacks were never built (only the ruler seek action exists), so seek anchors are ported. (2) Old-project sound changes: EXPORT-1b adds schemaVersion plus renderVersion. Fades ship for all projects as a defect fix with a legacy flag for null tests; headroom, tails and key transposition apply only to renderVersion>=2, with null key = identity. This replaces the blanket loudness note for untouched projects. (3) DSP-3 vs LivePlaybackPauseTest: published transport freezes at the pause request; the fade runs on a separate ramp counter; the test gains a tail-energy assertion; the same counter serves TIME-1. (4) PERF-4 audition vs recording: audition mixing is gated off during recording and count-in. Audio-thread exceptions to mandate 6 are at least FOUR, documented together, all reset on seek/stop/swap, with the drift test as guard: pause/seek ramp counter, audition counter and voices, limiter state, MIX-3 smoothed strip atomics. (5) Shared bank vs export-rate selector: rate is a key of the shared bank; a differing-rate export uses a separate bank counted against the same LRU; the first DSP-8 slice exports at device rate only. (6) Defer-list overlaps: ADPF renamed PERF-7b (defer) vs PERF-7a (xrun tuner, roadmap); EXPORT-5 is AAC (roadmap) vs EXPORT-5b FLAC/Opus (defer); DSP-7 ships only inside MIX-3. (7) DSP-2 lookahead vs loop wrap/seek: the lookahead reads through the same two-call split, resets on seek/stop/swap, with a host test that a limited wrap is bit-identical to a limited linear render. Also resolved: EXPORT-1 vs UX-4 persistence policy is immediate atomic single-flight coalescing writes (no debounce); UX-4 uses the same writer.

Weak claims fixed or downgraded: Oboe version is verified by readelf on all ABIs, not assumed (1.9.3 is the expected minimal candidate); PERF-1 split into a true S flag (1a) and an M trust half (1b, releaseTest, relaxed drift criterion, abiFilters, benchmark with tripwires on/off); DSP-V2 removed from quick wins (S plumbing, M hardware-gated calibration, 515 ms is one unverified log line and depends on IO-5's granted preset); ARCH-3 split into gates (M) and swap (M) with a11y and two-pane exit criteria; DSP-4 downgraded to impact 3 and sequenced after INSTR-1; PERF-V1 raised to impact 4 and placed before DSP-3; the GAP3-6 'silence after Home' rationale was refuted for API 28 (any FGS keeps the mic), so the ON_STOP finalize is decided by the manual check in IO-4b; the reduced-motion playhead quantization and semantics churn numbers in GAP1-7 were dropped; GAP2-2's WavDecoder sentence corrected (only 0xFFFFFFFF sizes tolerated, so recovery patches headers).

Deploy the loudness change once (DSP-2), then MIX-3 supplies per-track control. Hardware-gated items are limited to ARCH-3b sign-off, IO-4b manual check, DSP-V2 loopback, IO-5 granted preset, IO-3 reopen, PERF-7a and the soak.

## Roadmap

Each item lists its impact (1–5), effort (S ≤ 1 day, M ≤ 1 week, L ≤ 1 month, XL > 1 month) and hard
dependencies. Where an item says *merges …*, several audit ideas were consolidated into it.

### Now (before/alongside cutover) — Measurement, data safety, declick and cutover gates

Small items that are GridScreen-free (or are the cutover's own gates), verifiable offline or with one device run. They make every later change measurable, protect the user's project and takes, and turn the blocked cutover into a short, reversible swap. Hardware verification of the two pending tests still gates ARCH-3b only.

*16 items.*

- **`DSP-V3`** — Host-side golden-render harness plus float-render/capture hook (merges INSTR-V1, MIX-V2, EXPORT-V2, QA-1) *(impact 4/5, effort M)*  
  Every DSP exit criterion (step size, THD, null, chunk invariance, alias level, byte-identical chunked export) is unmeasurable today. First step: android/asset_manager.h stub with stub functions (not just typedefs) plus chunk-invariance and 'reproduce the 0.70 note-edge step' tests. Also add on-device nativeTestRenderFloat and nativeTestFeedInput hooks. Runs via WSL/Docker or GitHub Actions. Include a -m32 build, which doubles as the WavDecoder 32-bit chunk-wrap check (fix that low-severity hang in the same item).  
  *Depends on:* none

- **`PERF-1a`** — Native -O2 -g in every variant, NDEBUG left unset in debug (S half of PERF-1, ARCH-1, QA-4 step 1) *(impact 4/5, effort S)*  
  One CMake line; verify via compile_commands.json. No -ffast-math (finite-math-only could delete the NaN guard at ScoreBuilder.cpp:75). Release already gets -O3 from the toolchain, so the win is the Debug APKs every device test uses. Record MixEngineDriftTest elapsedMs before/after.  
  *Depends on:* none

- **`PRODUCT-V1`** — Fix liboboe.so 16 KB alignment; gate on llvm-readelf, not on a version number (merges ARCH-4) *(impact 4/5, effort S)*  
  Hard load failure on 16 KB devices and a Play block from Feb 2027. Treat 1.9.3 as the expected minimal candidate (release notes say 'Updated to 16kb page sizes') but the acceptance test is readelf -lW on the downloaded AAR for arm64-v8a and x86_64 (armeabi-v7a exempt); if 1.9.3 fails, step to the lowest version that passes. Oboe 1.10+ changes stream callbacks, so re-run LivePlaybackPause and RecordingLiveHardwareCapture on a device once.  
  *Depends on:* none

- **`EXPORT-1a`** — Durable project store cut 1: tri-state load, quarantine-not-overwrite, unique tmp, process-wide lock (merges QA-7, PRODUCT-V2, GAP3-2 load half, GAP3-V2, ARCH-V2 lock half) *(impact 5/5, effort S)*  
  Worst-case failure in the app and the fix is small. load returns Missing/Corrupt/Ok; Corrupt renames to .corrupt-<ts> and seeds the replacement under a NEW id with a message; unique tmp per save; one process-wide lock shared by ProjectRepository and ImportedSampleIndex (the per-instance @Synchronized is the bug); ImportedSampleIndex.add refuses to write over an unreadable index. Persistence policy (resolves the UX-4 conflict): the disk write is immediate, atomic and single-flight, coalescing to the latest snapshot, no debounce. Extract the VM bootstrap into a pure function so the JVM test is possible; update the old 'returns null' test. Touches only repositories and one init branch, safe before the cutover.  
  *Depends on:* none

- **`GAP2-1a`** — Gate lock-screen/Bluetooth transport while recording (S half of IO-4 and GAP2-1) *(impact 4/5, effort S)*  
  EnginePlayer.getState() drops PLAY_PAUSE/STOP/SEEK availableCommands while isRecording, and handleSetPlayWhenReady(false)/handleStop/handleSeek become no-ops (headset buttons can still call them). About a day, closes take corruption. Do NOT add microphone to the Media3 service type.  
  *Depends on:* none

- **`IO-V2`** — Arm-then-punch: quantize recording start to the next grid boundary, plus non-looping take blocks (merges TIME-V1; supersedes IO-1's WAV padding for the common case) *(impact 4/5, effort S)*  
  About 10 lines removes the random 0-167 ms early placement; a loop=false flag (default true, so old scores unchanged) removes the head replay in the block tail without baking silence into WAVs. Tighten RecordingGridAlignmentTest to <= 1 frame. Uses nativeTestFeedInput from DSP-V3. Fold TIME-V3's round-not-floor into startGridUnitForFrame here if fractional fpg is in play.  
  *Depends on:* `DSP-V3`

- **`DSP-1`** — Stateless block-edge fades in ScoreBuilder/renderScore (merges MIX-1 edge part, TIME-2a, EXPORT-V1, INSTR-1 declick commit) *(impact 5/5, effort S)*  
  Fixes the most audible defect on every grid note. Fade-out unless ScoreBuilder detects an abutting successor with the same sample/trim/pitch (seamless tiling stays bit-identical). Fade-in only when trimStart>0 or the first sample is above about -60 dBFS (snare onset is intentional). Derive blockLength as llround(end)-llround(start) to close the 1-frame gap at fractional fpgu. Clamp the ramp to half the window. Drop the seam-crossfade commit; fold trim-edge zero-crossing into S6. Versioning decision: fades are a defect fix and apply to all projects (documented as a one-time change; a legacy MixOptions flag keeps the old path for null tests); loudness, tails and key transposition are gated by renderVersion (see EXPORT-1b).  
  *Depends on:* `DSP-V3`

- **`PERF-V1`** — Per-callback active-range clamp plus anyActive early-out in renderScore (merges DSP-V1, PERF-3 step 1, INSTR-3, MIX-V1, TIME-V2) *(impact 4/5, effort S)*  
  Bit-identical, stateless, turns the scan from O(blocks x frames) into O(blocks). Also the export-time cliff fix (6-17 s at 1,000 blocks) and the prerequisite for per-block DSP (fades, envelopes, tails, strips). Raised to impact 4 for that dependency weight. Keep per-frame nonNegativeMod so mandate 6 is untouched; skip bucket index and JNI batching. The '<1% at 12,800 blocks' figure is an estimate; re-validate on -O2 with PERF-2 telemetry.  
  *Depends on:* `DSP-V3`, `PERF-1a`

- **`PERF-2`** — In-process RT telemetry: callback histogram, xrun/granted-config poll, block-count benchmark, trace recipe (merges QA-3, MIX-4 telemetry half, GAP2-8 counters) *(impact 4/5, effort M)*  
  Measure before adding FX or limiter. Two vDSO clock reads plus relaxed atomics per callback. Poll getXRunCount and granted mode/API/burst/sharing from Kotlin (unimplemented on OpenSL, which the Retroid may use). Compute thresholds as fractions of the real callback period from the opened stream (960-frame bursts observed), not a fixed 2.667 ms. Benchmark 100/300/1000/3000 blocks at 960 and 128 frames, with NDEBUG tripwires on and off. Also a debug-screen readout. The 60-minute thermal/battery soak is a separate manual protocol run before and after GAP2-6.  
  *Depends on:* `PERF-1a`

- **`GAP2-V2`** — Exit-reason log (ApplicationExitInfo, API 30+) plus 'take in progress' marker (merges QA-6) *(impact 3/5, effort S)*  
  CrashLogger sees only Java exceptions. A local few-dozen-line log of reason/status/rss on next launch, with a VmHWM/VmRSS fallback on API 26-29 (the Retroid gets no exit reasons), turns the unmeasured lmkd/heap assumptions in GAP2-2/GAP2-6/IO-4b into data within a week of use. The marker later drives take recovery. Cap trace copies.  
  *Depends on:* none

- **`IO-V3`** — Release the 92 MB recording buffer after each take *(impact 3/5, effort S)*  
  std::vector<float>().swap at the end of finishRecordingCommon and on failed starts; two lines, drops pinned RSS by about 92 MB after the first take. Superseded structurally by GAP2-2 but worth doing now.  
  *Depends on:* none

- **`ARCH-V1`** — Minimal CI: JVM tests, lint, assembleRelease, ELF alignment gate, Linux host-test job (merges QA-5 lane) *(impact 3/5, effort S)*  
  Nothing runs automatically and Release has never been built. A GitHub workflow catches -O0, 4 KB Oboe and harness regressions on first push. Instrumented tests stay manual; a Gradle Managed Device (ATD, API 30, x86_64 abiFilters override) for the offline-engine subset is a pre-flight lane only and never hardware sign-off.  
  *Depends on:* `DSP-V3`, `PRODUCT-V1`

- **`GAP1-1a`** — Unique, position-aware cell semantics: pitch, bar.beat.step, collectionInfo/collectionItemInfo, onClickLabel (phase A of GAP1-1) *(impact 4/5, effort S)*  
  Cutover gate. Keep the 'Filled'/'Empty' prefix (30 assertContentDescriptionContains sites still pass) then append '+7 semitones, bar 2 beat 3 step 1'; use stateDescription and onClickLabel instead of 'tap to' text (TalkBack says double tap). About 30 lines, restores what the old BlockView announced.  
  *Depends on:* none

- **`ARCH-3a`** — Cutover parity gates as small independent commits (merges the cutover-bug list) *(impact 5/5, effort M)*  
  Gates: (1) backfill pitchRow=round(pitchSemitones) for melodic legacy blocks and make updateBlock keep row and sound in sync (dialog slider then edits only a +/-50 cent tune); (2) grid width = max(64, last block end + 1 bar) as a stopgap until UX-1; (3) ruler tap-to-seek plus 'Seek to start/end' onClick and custom actions on the strip (the seek anchors move with the ruler); (4) waitForUpOrCancellation/isConsumed so vertical scroll does not place notes, with a swipe-up test; (5) rememberSaveable for focus/scale/sheet/pendingRecordTrackSlot; (6) remap sampleId on melodic reassign and block cross-family reassign; (7) share intent handled only when savedInstanceState==null and consumed, with try/catch around the EXTRA_STREAM read and a content:// / audio/* allow-list (no referrer dialog, no tighter cap: a 4-minute song would be rejected); (8) DrumGrid maps every column of a multi-unit block. Added exit criteria: GAP1-2 parity test green, Tab/Fold two-pane regression acknowledged and androidx.window kept, MainActivity smoke test (share intent, recreate).  
  *Depends on:* `GAP1-1a`

- **`GAP1-2`** — GridA11yParityTest and the parity list pasted into Tier 3.1 exit criteria *(impact 4/5, effort M)*  
  12-item test driven through the real AccessibilityNodeInfo tree via UiAutomation: place, place length>1 without a drag, delete, reassign with pitchRow==round(pitchSemitones), open editor via custom action, Sounds by semantics, scale chip, Play/Stop before the grid in traversal order with range info on position, seek preserved, record start/stop, touchBoundsInRoot >= 48 dp except documented grid cells, DnD action only if it ships. Lands red by design; a staged pass list tracks which items each gate turns green. Start with items 1, 3, 5, 9 (9 is expected red until ARCH-3a).  
  *Depends on:* `GAP1-1a`

- **`ARCH-3b`** — The swap: flip MainActivity to GridScreen, delete ArrangementScreen, port the old-screen tests *(impact 5/5, effort M)*  
  Three reversible commits: (1) pure-move PlaybackControlBar/formatPosition/RecordAffordance into their own files; (2) flip MainActivity.kt:14,54 with a MainActivity-level smoke test; (3) delete ArrangementScreen.kt (1,034 lines), selectTrack/addLoopToSelectedTrack, defaultStartGridUnit/defaultLengthGridUnits/DEFAULT_LOOP_REPEATS, and port (not drop) the 8 old-screen instrumented classes (about 2.3k lines: BackgroundPlaybackService, ArrangementScreenPlayback, CrashLogsUi, ExportShare, FullIntegrationWalkthrough, ImportConcurrencyGuard, ImportedSampleArrangement, MultipleProjects). Do NOT delete androidx.window or the two-pane work; the grid spec defers a two-pane grid that reuses it. Resolve the device branches first (Tab branch likely conflicts in LoopLibraryBottomSheet.kt; Fold branch is a modify/delete conflict). Blocked only on hardware verification of the two pending tests.  
  *Depends on:* `ARCH-3a`, `GAP1-2`

### Next (engine foundation, GridScreen-free) — Headroom, transport, scalability, recording truth, durability

These touch native, PlaybackEngine, ProjectRepository and JNI rather than GridScreen or ArrangementScreen, so they proceed and verify offline or with short device runs while the cutover waits. Foundation before features: declick, headroom, culling and telemetry all land before envelopes, effects or instruments.

*26 items.*

- **`EXPORT-1b`** — Durable project store cut 2: .bak, schemaVersion plus renderVersion, sample-index treatment, relocatable refs (merges ARCH-V2, GAP3-V1, GAP3-5, QA-V3 logic) *(impact 4/5, effort M)*  
  Add schemaVersion to Project and index.json; a newer version loads as Unreadable(NewerVersion) and is never overwritten by an older app. Add Project.renderVersion: old projects load as legacy (tanh, no tails, null key = identity), new projects opt into headroom, tails and key transposition. This keeps golden/null tests meaningful and replaces the 'one-time loudness note' for untouched projects. Also: .bak on save, quarantine plus rebuild-from-WAV scan as last resort for the index (name/category/peaks are lossy, so .bak is the real defence), resolve sample refs as directory enum plus validated UUID leaf with idempotent legacy-path migration, and UUID-or-'current' validation of project ids.  
  *Depends on:* `EXPORT-1a`

- **`LOADREPORT`** — LoadReport: surface blocks whose sample failed to load or is missing (merges ARCH-6 LoadReport, GAP3-2 missing-audio half) *(impact 3/5, effort S)*  
  addLoopBlock's Boolean is ignored and export reports success while dropping blocks. Count failures, return a LoadReport, show one Snackbar ('3 clips need audio that isn't on this device') and mark blocks; never prune. Also makes cloud-only restores (GAP3-1) degrade visibly. Small ViewModel touch, safe before or after the swap.  
  *Depends on:* `EXPORT-1a`

- **`GAP2-1b`** — Recording session owner in PlaybackEngine: adopt an in-flight take after Activity death, finalize from notification Stop/onCleared (M half of GAP2-1) *(impact 4/5, effort M)*  
  Add recordingTrackSlot to PlaybackEngineState and adopt it on VM init; move finalize into PlaybackEngine or a small recording coordinator (it needs project, bpm, rate, category dialog) so notification Stop and onCleared can finalize or discard; replace onCleared's runBlocking teardown with fire-and-forget on engineScope. Exit test asserts non-silence (RMS above a floor with a tone at the mic), not just frames>0.  
  *Depends on:* `GAP2-1a`

- **`DSP-2`** — Headroom policy plus score-aware lookahead limiter replacing the tanh (merges MIX-2, DSP-2a/2b), gated by renderVersion *(impact 4/5, effort M)*  
  Ship 2a first: static per-track/master trim plus a final hard clamp (fixes most of the 30%-over-unity problem). Then 2b: 2-3 ms lookahead using the pure-function property (render N+L, no delay line), smoothed attack, tanh only as last-resort clamp, peak/GR atomics for a meter. Limiter state resets on seek/stop/pause/swap; export flushes the lookahead tail; the lookahead reads across a loop wrap through the same two-call renderScore split, with a host test that a limited loop wrap is bit-identical to a limited linear render. Legacy renderVersion keeps the tanh path. Disagrees with E5 only because renderScore is stateless. Land before envelopes, strips and FX add overlap.  
  *Depends on:* `DSP-V3`, `DSP-1`, `PERF-V1`, `PERF-2`, `EXPORT-1b`

- **`DSP-3`** — Transport ramps: shared renderCallback, pause/stop/seek ramps, CAS/pending-request fix, single-callback score-swap blend (merges TIME-2b, MIX-1 seek/edit half) *(impact 4/5, effort M)*  
  Fixes clicks on pause/seek and the fetch_add race. Resolution of the LivePlaybackPauseTest conflict: freeze the published transport frame at the pause request and run the fade-out from a separate audio-thread ramp counter that renders frames from the frozen position without advancing the published transport; extend the test to assert both the frozen position and a fade-tail energy check. Score-swap blend is two renders within one bracketed callback (not a cross-callback pointer, not block diffing). Disable in the export engine. The same ramp counter serves the TIME-1 loop wrap.  
  *Depends on:* `DSP-V3`, `DSP-1`

- **`PERF-V3`** — Pre-warm and share decoded samples; decode outside SampleBank mutex with single-flight (merges EXPORT-V3) *(impact 3/5, effort M)*  
  The dominant one-off commit stall is synchronous decode+resample under the global mutex, and every export re-decodes. Pre-decode at assignment is mandatory for audition. Sharing is keyed by (path, target rate): the export rate is an explicit key, and a differing-rate export decodes into a separate export bank counted against the same LRU cap so memory cannot double. This resolves the conflict with the DSP-8 rate selector.  
  *Depends on:* none

- **`PERF-V2`** — Chunked, crash-safe export outside engineMutex with native exception guard and shared bank (merges EXPORT-3, GAP2-7, ARCH-6 firewall) *(impact 4/5, effort M)*  
  Render 4096-16384-frame chunks into a streaming WavWriter (.part then rename), atomic cancel flag and progress, try/catch at every allocating JNI entry, refuse export while recording, export engine built outside engineMutex and reusing the live bank when rates match. Scratch <= 128 KB and no new decodes for cached samples are the exit criteria; byte-identity to the one-shot export is the test (host-runnable). Design must allow a second pass for later loudness work. Skip WorkManager and foreground service.  
  *Depends on:* `DSP-V3`, `PERF-V3`

- **`PERF-5`** — Latest-wins conflated commit pipeline (single consumer) for engine reload (merges ARCH-2 step 1) *(impact 3/5, effort S)*  
  Fixes stale-score ordering and coalesces bursts; prerequisite for undo. Engine commit stays immediate; the save goes through the same single-flight atomic writer as EXPORT-1a. Add a flush hook because many instrumented tests assume loadProject follows a tap. Defer incremental nativeApplyEdit and full ProjectSession.  
  *Depends on:* `EXPORT-1a`

- **`PERF-4`** — Native audition voice path: SPSC trigger ring plus 16-voice pool, mixed while paused (merges UX-2, INSTR-4b, THEORY-3, PRODUCT-5 tap-to-hear) *(impact 5/5, effort M)*  
  A tap making no sound is the biggest playability gap. Voices derive position from a second audition-frame counter (no per-voice cursors); drain and mix before the !mPlaying early return; retire sample refs through the mScoreReadInFlight/mRetiredScores pattern; cap the pinned bank. Audition mixing is gated off (or headphones-only) while recording or during a count-in, because speaker bleed corrupts takes. Keep full-loop preview separate from 1-unit note audition. Latency targets per device: about 30 ms p50 where MMAP is granted, 60-120 ms on the Retroid. Native side lands now; the one-line toggleGridCell hook lands after the swap.  
  *Depends on:* `DSP-1`, `DSP-V3`, `PERF-V3`, `GAP2-1a`

- **`IO-5`** — Recording front-end: Unprocessed preset with fallback, Stereo/Mono retry with typed errors, granted-config log, peak/clip meter with silence hint, DC blocker on capture path (merges DSP-6, GAP2-V1, IO-7 reduced, GAP3-6 meter half) *(impact 4/5, effort M)*  
  VoiceRecognition preset (Oboe default) can apply AGC/NS and may keep input off the fast path, so this may be the biggest latency and quality lever; confirm the granted preset on hardware and label takes made on a processed path. Exact-zero detection for the silent fallback, stop-time peak measurement (no volume-ceiling change), 20 Hz HPF/DC on capture only, size scratch as frames x inputChannels on the Unspecified retry, Settings deep link when RECORD_AUDIO is permanently denied. Skip silence auto-trim, normalization and mono-as-mono storage.  
  *Depends on:* `IO-V2`, `DSP-V3`

- **`GAP2-V3`** — Input backlog: counters first, then drain FIFO and lag tracking (merges IO-V1, IO-2 drain half) *(impact 4/5, effort M)*  
  Ship zero-length/short-read/overrun counters plus a backlog probe (S) and record rates on both devices before changing behavior. Then allocate-before-open, drain until read returns 0, and an SPSC FIFO consuming at a fixed target latency; short reads ratchet lag upward, so a one-shot calibration goes stale. Offline test injects short reads and asserts click position within 1 ms at the end of a take.  
  *Depends on:* `IO-V2`, `DSP-V3`

- **`DSP-V2`** — Record-latency compensation: measure granted modes, per-route shift at commit, then loopback calibration (2a S-M plumbing, 2b M hardware-gated; merges IO-2, IO-1 compFrames) *(impact 4/5, effort M)*  
  The 515 ms Fold 5 figure is one log line and probably means the input fell off the fast path (preset, Stereo request), so first read the granted mode after IO-5 and re-measure. 2a: read both Oboe latencies before closing input, store per route, apply as a frame shift derived from the compensated start. 2b: speaker-to-mic loopback cross-correlation on a free tablet; timestamps miss radio/DSP delay. Removed from quick wins: the shift plumbing is S but the calibration is M and hardware-gated.  
  *Depends on:* `IO-V2`, `IO-5`, `GAP2-V3`

- **`GAP2-5`** — Capture-health detection: OS-silenced, disconnected or erroring input flagged and its range marked *(impact 3/5, effort M)*  
  Exact-zero run counter over frames actually returned by read() (never the zero-filled remainder), last oboe::Result, ErrorDisconnected within one callback, isClientSilenced via AudioRecordingCallback on API 29+. Banner plus marked range in the sidecar; never auto-pause. Native counters can ship before GAP2-2. Warning, not proof (a hardware mute also gives zeros).  
  *Depends on:* `GAP2-V3`

- **`GAP2-2`** — Crash-safe take spill: SPSC ring plus non-RT writer to .part WAV, sidecar metadata, orphan recovery (merges IO-6, GAP3-V3) *(impact 4/5, effort L)*  
  Removes the 92 MB RAM-only take: ring about 3 MB, append int16 to <uuid>.wav.part, header patched and renamed on stop, atomic sidecar (slot, start frame, bpm, rate, projectId), launch sweep repairs headers (data size 0 does not decode, only 0xFFFFFFFF is tolerated), zero-fill gaps to keep sequential writes and alignment, ENOSPC keeps what was written. Loss on kill -9 is <= 0.35 s. Proof: byte-identical to today's WavWriter output for a synthetic 60 s take and a kill -9 test on hardware. Must land before more live capture state (IO-3, GAP2-5 ranges).  
  *Depends on:* `GAP2-1b`, `DSP-V3`, `GAP2-V2`

- **`GAP2-3`** — Heap-safe take finalize and import: streaming peaks, MediaCodec-to-file, single-pass native resample (merges EXPORT-7b) *(impact 3/5, effort M)*  
  Three commits in order: extractFromFile with 64 KB blocks (S), stream MediaCodec output to the WAV instead of ByteArrayOutputStream (M), native decode+resample single pass and equal-rate no-copy (M). Also catch Throwable/OutOfMemoryError around finalize (currently only IOException, so an OOM crashes after the WAV is on disk). Removes any need for largeHeap.  
  *Depends on:* none

- **`EXPORT-7a`** — Importer honors codec output format (INFO_OUTPUT_FORMAT_CHANGED); HE-AAC/PS fixture *(impact 3/5, effort S)*  
  HE-AAC/PS imports can come out at the wrong rate/pitch; header uses extractor format. Take rate, channels and encoding from codec.getOutputFormat(); reject unsupported encodings with DecodeFailed. Isolated to AudioImporter.  
  *Depends on:* none

- **`GAP2-6`** — Memory governor: totalMem-scaled budget, onTrimMemory hook, pinned-byte accounting, retired-score trim *(impact 3/5, effort M)*  
  Budget = clamp(0.06 x totalMem, 64, 512 MiB), halved on low-RAM (getMemoryClass is the Dalvik heap limit and cannot size native memory, so this deliberately disagrees with the device-adaptive plan's input). Pinned bytes = entries with use_count()>1; evict-before-insert from header size; trimRetiredScores keeps only the newest score and runs off-thread; ComponentCallbacks2 levels. Decide priority after GAP2-V2 shows real kill data; the Retroid is retired from the active lineup. Must precede features adding per-track native buffers.  
  *Depends on:* `PERF-V3`, `GAP2-V2`

- **`GAP3-1`** — Backup policy: cloud = projects + prefs only, device-transfer = everything, crash logs never (merges QA-V3) *(impact 3/5, effort S)*  
  Config-only: dataExtractionRules plus fullBackupContent (API 26-30; requireFlags only valid there). Framed as a product decision: a cloud-only restore loses the user's own takes and imports. Cloud backup is capped at 25 MB (one max take is 46 MB). Pairs with LOADREPORT so restored projects show missing audio instead of silence. JVM XML-parse test plus lint DataExtractionRules.  
  *Depends on:* `EXPORT-1a`, `LOADREPORT`

- **`DSP-8`** — Export fidelity: fix WavDecoder audioFormat (B2), TPDF-dithered 16-bit default, 24-bit and float32 WAV *(impact 3/5, effort S)*  
  Fix B2 first (float WAV currently decodes as noise). Keep dithered 16-bit as the default because stock players vary on 24/32f. Do not ship 24-bit LinearFloat before the limiter exists. First slice exports at the device rate only; a 44.1/48 selector needs a frame-count rescale and uses a separate rate-keyed export bank (see PERF-V3).  
  *Depends on:* `DSP-V3`, `DSP-2`

- **`INSTR-V2`** — Decode-time sample analysis: effective end, onset, peak/RMS, DC on SampleBuffer *(impact 3/5, effort S)*  
  Prerequisite for ONE_SHOT lengths (kick content ends at 380 ms of a 2,667 ms file), pad decay defaults, capped export tails and balanced default trims.  
  *Depends on:* `PERF-V3`

- **`TIME-1`** — Sample-accurate loop region plus end-of-song policy: native half (UX-3 native half) *(impact 5/5, effort M)*  
  Stateless two-call renderScore split at the wrap (while-loop for regions shorter than a burst), transport through the CAS/pending-request fix, wrap uses the DSP-3 ramp, no-op for the export engine and while recording; offline equality test first. Ruler brace UI is TIME-1b after the swap.  
  *Depends on:* `DSP-3`, `DSP-2`

- **`QA-V2`** — Native warning gate (-Wall -Wextra -Wshorten-64-to-32), clang-tidy and WavDecoder fuzz target on the host lane *(impact 3/5, effort S)*  
  Cheap once DSP-V3 exists; the -m32 fuzz run finds the chunk-size hang mechanically. Bundled into the host CI job.  
  *Depends on:* `DSP-V3`, `ARCH-V1`

- **`IO-3`** — Stream-loss recovery, audio focus, becoming-noisy (merges ARCH-7 focus half) *(impact 4/5, effort L)*  
  No error callback, so the transport freezes while the UI shows Playing. Register the callback on the OUTPUT stream only; detect input loss from read() results and finalize the partial take (via GAP2-2). A reopen at a different rate must rebuild the score (SampleBank keys by path and bakes the rate). Split focus/noisy handling (S-M) from reopen (M).  
  *Depends on:* `PERF-2`, `GAP2-2`

- **`IO-4b`** — Background-capture policy decided by one manual check (merges GAP2-4, ARCH-7 mic half, GAP3-6a) *(impact 3/5, effort M)*  
  Manual check first (2 minutes): on the Tab S9 FE with the current build arm a take with a steady 1 kHz tone, press Home, 20 s screen-on then 40 s screen-off, compare WAV RMS 5-60 s with the first 3 s. Exact zeros or a >40 dB drop means a dedicated RecordingService (type microphone, RECORD_AUDIO granted and activity visible, calls startForeground immediately; do not co-declare microphone on the Media3 service) is required; within 3 dB means ON_STOP finalize is defence in depth only. Also test API 28 (Retroid), where any FGS keeps the mic. Drop FLAG_KEEP_SCREEN_ON unless the screen-off arm fails. Priority P0 or P2 depends on the result.  
  *Depends on:* `GAP2-1b`

- **`EXPORT-2-defer-marker`** — (placeholder retired) see defer list for LUFS/true-peak presets *(impact 1/5, effort S)*  
  Not scheduled; listed only so EXPORT-2 c/d are not silently dropped. See defer.  
  *Depends on:* none

- **`QA-4b`** — releaseTest build type: R8 on, debug-signed, FULL native symbols, run offline subset against the shipped binary (merges QA-4 step 2, ARCH-V3, PERF-1b) *(impact 3/5, effort M)*  
  PERF-1b: relax drift criterion to about 2x (it includes JNI cross-checks), keep 1e-6 tolerance for FMA contraction, drop x86_64/armeabi-v7a from debug abiFilters, add the Retroid (API 28) to device lists. Then a releaseTest lane so the -O3 -DNDEBUG binary and R8 output run under MixEngineDriftTest and friends; androidTest against a minified variant needs testBuildType plus keep rules for what tests touch (JNI keep rules are mostly redundant). Archive mapping.txt and native-debug-symbols.zip (build/outputs/native-debug-symbols/).  
  *Depends on:* `PERF-1a`, `PERF-2`

### Then (post-cutover: GridScreen and ViewModel work) — Play it live, key coherence, mixer strips, envelopes, accessibility depth

Once MainActivity shows GridScreen, heavy GridScreen/ArrangementViewModel work no longer risks the cutover. Native prerequisites are already landed and verified. Kotlin 2 comes first so recomposition measurements are taken on the compiler that will ship.

*14 items.*

- **`ARCH-8a`** — Kotlin 2.2 with the Compose compiler Gradle plugin on the existing AGP (step 1 of ARCH-8) *(impact 2/5, effort M)*  
  Strong skipping is on by default in Kotlin 2.x and may remove much of the GridScreen per-tick recomposition cost, so do it before PERF-6 and UX-1 measurements. Separate commits; kotlinx-serialization plugin moves with it. API 36 targeting is ARCH-8b, conditional on a Play release.  
  *Depends on:* `ARCH-3b`

- **`PERF-6`** — Draw-phase playhead, session refresh only on non-position change, then audible-position clock (merges TIME-7 v1) *(impact 3/5, effort S)*  
  Two independent S steps: read currentFrame only inside the overlay draw/offset lambda, and stop calling refreshState() every 50 ms tick in the service. Record gfxinfo baseline first (after ARCH-8a). Extrapolate with withFrameNanos minus output latency; Oboe getTimestamp needs a fallback on legacy streams.  
  *Depends on:* `PERF-2`, `ARCH-3b`, `ARCH-8a`

- **`UX-4`** — Edit transactions: gesture-coalesced undo/redo on the conflated commit pipeline *(impact 4/5, effort M)*  
  A mis-tap on a 28 dp cell deletes a note with no undo. Snapshots at gesture commit, refused while recording, 'Note deleted - Undo' snackbar. Persistence uses the same immediate single-flight atomic writer as EXPORT-1a (no debounce; resolves the earlier conflict), flushed on stop/background/undo. It is the transaction primitive for paste, transpose and any generators.  
  *Depends on:* `PERF-5`, `ARCH-3b`

- **`PRODUCT-1`** — Sample metadata schema plus project key: rootMidi/kind/bpm/tags/license/origin, note-named rows (merges INSTR-2, THEORY-1, UX-6, GAP3-9) *(impact 5/5, effort M)*  
  Bundled roots are A1/D2/C4/A3/D4, so 'Major' on two tracks yields different keys. effective = pitchRow + fold(keyRoot - sampleRoot) with fold in [-6,+6], applied at score build; do not fold the row itself and do not persist an absolute-MIDI pitchRow, so no data migration. A null keyRoot means identity, and the key/transposition applies only to renderVersion>=2 projects, so existing melodic blocks do not shift. Hand-curate roots for bundled loops (chord = key hint / polyphonic flag); import detection later. Add a defaulted origin enum (BUNDLED_SYNTH/RECORDED/IMPORTED). Reverses the spec's session-only scale, so say so; update chromatic-default tests in the same commit.  
  *Depends on:* `ARCH-3b`, `EXPORT-1b`

- **`INSTR-1`** — Per-note envelope, one-shot ring-out and release tail, drum choke groundwork (merges THEORY-V1) *(impact 5/5, effort M)*  
  The 380 ms kick is cut to 44% by its 1-unit cell. Add ADSR/mode to ResolvedLoopBlock with a no-wrap ONE_SHOT path, length from decode-time effective end, 0.5-1 ms attack for drums (2-3 ms melodic), ramp capped at half the window, export/lock-screen duration including the tail. JNI change touches three addLoopBlock twins. Gated by renderVersion. Tails add overlap, so headroom must land first.  
  *Depends on:* `DSP-1`, `DSP-2`, `INSTR-V2`, `PERF-V1`, `EXPORT-1b`

- **`GAP1-7`** — Transport semantics and page-follow: progressBarRangeInfo plus bar.beat stateDescription, playhead follow mode *(impact 4/5, effort M)*  
  Page-follow matters to everyone (playhead leaves a 360 dp viewport after about 11 columns). Keep the visible '0:00' text (a test asserts it) and add stateDescription so TalkBack does not read a bare percentage. Drop the reduced-motion step-quantization (functional movement, not decorative) and the churn arguments (the semantics string already changes about 1 Hz); keep an optional in-app switch.  
  *Depends on:* `ARCH-3b`

- **`GAP1-V1`** — Hardware-keyboard and D-pad/gamepad grid navigation (empty melodic cells are currently unreachable) *(impact 4/5, effort M)*  
  Empty melodic cells use raw pointerInput and are not focus targets, so keyboard/D-pad/DeX users (including the Retroid, a D-pad handheld) cannot place notes; the promised Space=play/pause never shipped. Shared cursor model with GAP1-5: arrows move, Enter places, Shift+arrows stretch, Delete removes, Space toggles play. Prototype on the current grid container.  
  *Depends on:* `ARCH-3b`

- **`TIME-1b`** — Loop region UI: bar-snapped brace on the ruler, tap-to-seek kept (UX-3 UI half) *(impact 4/5, effort M)*  
  Ruler pan-vs-loop-drag competes for the same gesture, so resolve pan ownership with UX-1 first (pan by two fingers or ruler drag; stretch is long-press-drag).  
  *Depends on:* `TIME-1`, `ARCH-3b`, `UX-1`

- **`UX-1`** — Single-surface Canvas grid virtualized to the 240 s song, one gesture owner, windowed semantics (merges PRODUCT-4, GAP1-V3, GAP1-1b, GAP1-6, UX-V3 shading) *(impact 5/5, effort L)*  
  64 columns is 4.4% of the song cap and 1,600 eager cells is a recomposition liability. Definition of done includes accessibility: a draw-less Layout overlay emits semantics only for the visible window plus a margin, merges each note into one node with columnSpan=length, exposes scroll axis ranges, keeps grid_cell_c_r testTags, and adds a root custom semantics key listing every note as the O(1) oracle that sits BESIDE the TalkBack tree, never replacing it (resolves the single-node contradiction). Cell width/height become state (20-64 dp zoom, 48 dp accessible preset), pan by ruler or two-finger drag, stretch by long-press-drag, DrumGrid ported in the same change, bar/beat shading and clipped playhead, re-base 7 test files. Baseline gfxinfo first. Replaces the ARCH-3a stopgap width.  
  *Depends on:* `ARCH-3b`, `PERF-6`, `GAP1-2`, `GAP1-1a`

- **`MIX-3`** — Track strips: volume, pan (centre-unity law), mute, solo as smoothed per-track atomics in the existing pass (merges UX-V2, DSP-7) *(impact 4/5, effort L)*  
  Table-stakes for an 8-track arranger and gives headroom control beyond per-note dialogs. Defaulted Track fields keep old JSON loading; pan ships only here (no standalone DSP-7). Per-sample smoothing or absolute-frame-aligned ticks so chunk invariance is testable; mute/solo gates ramp about 5 ms; solo is a model rule resolved into effective gains. Adds audio-thread smoother state (counted in the mandate-6 exceptions). Defer the two-pass scratch-buffer graph until FX need it.  
  *Depends on:* `DSP-2`, `PERF-V1`, `ARCH-3b`

- **`TIME-3`** — Metronome and count-in with grid-exact recording start *(impact 4/5, effort M)*  
  Deterministic starts make latency error constant and measurable. Metronome is a stateless click synth off the transport frame (never in export). Pin the negative-position contract (UI clamps; Media3 position max(0,frame)); round rather than floor in startGridUnitForFrame for fractional fpgu. Audition is gated off during the count-in.  
  *Depends on:* `IO-V2`, `DSP-3`, `ARCH-3b`

- **`GAP1-4`** — Font-scale 200%, RTL and pseudo-locale resilience: LTR-locked time axis, wrapping header, scalable row height *(impact 3/5, effort M)*  
  supportsRtl=true means an Arabic/Hebrew device mirrors the grid today and drag-to-stretch extends the wrong way (absolute x). Lock the time axis to LTR; move Record/Sounds/Scale to a wrapping row; heightIn for rows with ellipsis and full name in semantics. The ar-XB run works without string externalization; the en-XA half waits on externalization (deferred).  
  *Depends on:* `ARCH-3b`

- **`UX-POLISH`** — Bar/beat shading and ruler labels plus haptic ticks on place/delete/stretch-column crossings (UX-V3, GAP1-V2) *(impact 3/5, effort S)*  
  Pure functions columnEmphasis() and a haptic hook behind a preference; ship on the current grid before UX-1 and carry into the Canvas. Low risk.  
  *Depends on:* `ARCH-3b`

- **`PRODUCT-6a`** — Case-insensitive natural sort comparator and extension stripping on imported names *(impact 3/5, effort S)*  
  Three sortedBy sites are case-sensitive and imported names keep '.wav'. About 20 lines with tests; the richer picker (favorites, search, chips) is PRODUCT-6b after PRODUCT-2.  
  *Depends on:* `ARCH-3b`

### Later (features on a sound foundation; several need product decisions) — Tempo, groove, content, instruments, FX, interchange, polish

Shiny and valuable but only after declick, headroom, culling, metadata, telemetry and undo exist. Several are content-bound rather than DSP-bound.

*23 items.*

- **`DSP-4`** — Band-limited SRC: Kaiser polyphase at decode, then integer-semitone pitch-variant cache *(impact 3/5, effort M)*  
  Downgraded from 4: droop and images are real on 44.1 kHz loops at 48 kHz and for +12 st raw decimation, but small for unpitched drum hits and synthetic content; its main value is the variant-cache foundation for TIME-4. Commit 1: kernel plus decode-time swap outside the SampleBank lock. Commit 2: variants keyed (path, rate, semitone) counted against the LRU. Golden-tone tests on the host harness.  
  *Depends on:* `DSP-V3`, `PERF-V3`, `INSTR-1`

- **`PRODUCT-2`** — One-shot-first launch pack (2a: drum kits plus kind/tags/license manifest and About/Licenses; 2b: multisampled instruments) *(impact 5/5, effort XL)*  
  The bundle is 8 synthetic phrase loops; a 1-unit note is 3% of a bass riff. 2a first (about 20 hand-authored/CC0 one-shots, baked peaks, content-QA test, a repo LICENSE decision and provenance note for the bundled synthetic loops, About/Licenses screen from a curated notices JSON guarded by a test). 2b needs a spec amendment lifting the Instrument/Kit exclusion. Fold in the natural sort.  
  *Depends on:* `PRODUCT-1`, `INSTR-1`, `PRODUCT-6a`

- **`INSTR-6`** — Drum pads: pad ids, per-pad tune/decay/gain, choke groups, slicing loops into pads (merges INSTR-V3) *(impact 4/5, effort L)*  
  Pad id, not sampleId, is the key (two pads can share one file). Choke and swing computed at build time keep the mixer stateless. Slicing reuses per-block trim; migration derives pads from assignedSampleIds.  
  *Depends on:* `INSTR-1`, `INSTR-V2`, `PRODUCT-2`

- **`TIME-6`** — Swing and seeded humanize baked at ScoreBuilder (per-track opt-in, short blocks only) *(impact 3/5, effort S)*  
  Zero audio-thread cost. Shift both block edges, clamp at 0, persist swing on Project with migration. Kotlin mirrors (GridConstants, recording alignment, playhead) must agree or takes drift by up to 53 ms. 24 units/beat stays deferred.  
  *Depends on:* `DSP-1`, `EXPORT-1b`

- **`TIME-V3`** — Single gridToFrame definition exposed to Kotlin (native llround) for seek, metadata, recording start *(impact 2/5, effort S)*  
  Kotlin truncates/floors where ScoreBuilder rounds; irrelevant at 90 BPM/48 kHz (fpg exactly 8000) but off by a frame at fractional fpg. Must precede any user-editable BPM.  
  *Depends on:* none

- **`TIME-4`** — Tempo-elastic loops via offline Signalsmith Stretch variant cache, then BPM control (merges DSP-5, TIME-5a) *(impact 4/5, effort L)*  
  Gated on the T1/T2 product decision (is BPM user-editable?). Stateless-friendly. Needs circular padding, latency trim, seam-continuity assertion, async regeneration with a varispeed fallback, 0.75-1.5x quality window, nativeBpm on recordings. BPM UI, Double bpm, audio-thread rescale (refused while recording) and TIME-V3 strictly after the stretch cache. Tempo map (TIME-5b) is a separate L.  
  *Depends on:* `DSP-4`, `PRODUCT-1`, `TIME-V3`

- **`UX-5`** — Selection, copy/paste, duplicate bar, transpose, snap chips *(impact 4/5, effort L)*  
  128 taps versus 15 pastes for a 16-bar pattern. Pure Track functions plus JVM tests can land on the current grid; marquee needs the Canvas. 12 units/beat migration stays out.  
  *Depends on:* `UX-1`, `UX-4`

- **`INSTR-4`** — Per-note velocity with a dB taper and velocity lane (merges UX-8) *(impact 3/5, effort M)*  
  Anchor the default at unity so new notes are not quieter than existing ones; one scale shared with the editor slider. The spec excluded velocity, so this needs sign-off.  
  *Depends on:* `UX-1`, `DSP-2`

- **`PRODUCT-3`** — First beat in 60 seconds: starter templates plus one coach mark *(impact 4/5, effort M)*  
  Only credible once one-shot kits, audition and declick exist. Use loadedProject==null as the first-run condition and test that every template sampleId resolves in the manifest.  
  *Depends on:* `PRODUCT-2`, `PERF-4`, `ARCH-3b`

- **`MIX-6`** — FX on a two-pass strip graph: HPF/LPF+EQ (TPT SVF), delay send, FDN reverb send, compressor (four commits) *(impact 4/5, effort XL)*  
  Needs FTZ/DAZ on three ABIs with register restore, an export FX tail, preallocated buffers, sub-block chunking. Gate on callback-load telemetry (target <30% on the Tab S9 FE).  
  *Depends on:* `MIX-3`, `DSP-2`, `PERF-2`, `PERF-V1`

- **`EXPORT-4`** — Stem export (per-track solo runs or single-pass buses) summing to a LinearFloat 32f master *(impact 3/5, effort M)*  
  Needs float export and shared decoded buffers. ACTION_SEND_MULTIPLE, not zip. Null test against the un-limited master.  
  *Depends on:* `DSP-8`, `PERF-V2`, `PERF-V3`

- **`EXPORT-5`** — AAC-LC .m4a export as Kotlin post-process of the master WAV *(impact 3/5, effort M)*  
  6x smaller share files. AAC only; SNR criterion about 20 dB after delay alignment; change the share MIME.  
  *Depends on:* `PERF-V2`

- **`PERF-7a`** — Xrun-driven buffer step tuner (E7 stash replacement) *(impact 3/5, effort M)*  
  Only after PERF-2 and the soak show what the tablets grant. Effective only where MMAP/LowLatency is granted (Retroid gets None). ADPF is split out to the defer list as PERF-7b.  
  *Depends on:* `PERF-2`

- **`THEORY-6`** — Euclidean rhythm and genre presets on drum rows (one transaction) *(impact 3/5, effort M)*  
  Pure Bjorklund plus tests is cheap but musical value needs more than two drum sounds and one-shot ring-out.  
  *Depends on:* `UX-4`, `PRODUCT-2`, `INSTR-6`

- **`INSTR-7`** — Sustain loop with crossfade, then multisample zones (merges THEORY-V3, UX-V1) *(impact 4/5, effort L)*  
  Sustain-loop slice first on one sustained sample, zones second. Content plus loop-point authoring as much as engine work. Add a license field.  
  *Depends on:* `INSTR-1`, `PRODUCT-1`, `PRODUCT-2`

- **`PRODUCT-8`** — Import intelligence as suggestions: kind, YIN root, then key and BPM (merges THEORY-4) *(impact 3/5, effort M)*  
  Pre-filled chips in the category dialog, never destructive (no import-time trim or normalize). f0/tuning for one-shots first, key second, BPM last and only riff/arp fixtures (single-hit loops are ambiguous). Hand-written, no GPL DSP.  
  *Depends on:* `PRODUCT-1`

- **`PRODUCT-6b`** — Sounds picker v2: favorites, recents, search, kind/tag chips, waveform, kit-add *(impact 3/5, effort M)*  
  Matters only once a larger pack exists. Persist in AppPreferences or a small JSON, not the Sample model.  
  *Depends on:* `PRODUCT-2`

- **`GAP1-5`** — Assistive editing mode: cursor, note list, moveNote/resizeNote, one-level undo (phase 1) *(impact 4/5, effort L)*  
  Non-gesture equivalents for placing length>1, moving and resizing; one cursor model shared with GAP1-V1. Start with ViewModel moveNote/resizeNote plus JVM invariants (also fixes pitchRow sync). Audition-of-pitch is carried by PERF-4, not built here.  
  *Depends on:* `GAP1-V1`, `UX-4`, `PERF-4`

- **`GAP3-3`** — Startup sweeper for orphaned audio plus reference-counted sample delete (dry run first) *(impact 3/5, effort M)*  
  Users cannot erase a recording or import and process death leaves unreachable WAVs. Ship log-only first; abort whenever the index is not Ok; grace window for a pending category dialog; take recovery (GAP2-2) must claim .part files before the sweeper. Exports purge is optional (OS reclaims cache).  
  *Depends on:* `EXPORT-1b`, `GAP2-2`

- **`ARCH-9`** — Strangler extraction of ArrangementViewModel: pure reducers first, AudioBackend seam (QA-V1), coordinators *(impact 3/5, effort L)*  
  Pure reducers with JVM tests are high value and independent; the UiState split waits until PERF-6 on Kotlin 2 measures the real recomposition cost.  
  *Depends on:* `UX-4`, `PERF-6`, `ARCH-8a`

- **`QA-2`** — TSan/ASan/UBSan/RTSan gate on the commit/reclaim protocol (needs an Oboe stub or Oboe-free ScorePublisher extraction) *(impact 3/5, effort M)*  
  Most valuable just before FX add render-path state. Linux-only lane; lead with the RTSan/ASan mutation check (needs a non-empty score per commit) rather than re-proving B3. Also covers the JNI global engine race.  
  *Depends on:* `DSP-V3`, `ARCH-V1`

- **`ARCH-8b`** — targetSdk/compileSdk 36, AGP 8.11, edge-to-edge (conditional on a Play release) *(impact 2/5, effort M)*  
  Only if a store release is decided (deadline for API 36 has passed for Play distribution; nothing in the repo shows a Play plan). Target 36 ignores orientation/resizability limits on 600 dp+ screens and enables predictive back, which matters for Tab/Fold. Bundle with backup rules, versioning, name clearance.  
  *Depends on:* `ARCH-8a`, `PRODUCT-V1`

- **`PRODUCT-9`** — Ship-readiness checklist (name clearance, versionName, licenses, offline positioning) *(impact 3/5, effort M)*  
  Release gate only if a store release is decided; folds 16 KB, backup rules and About/Licenses done elsewhere.  
  *Depends on:* `ARCH-8b`, `GAP3-1`, `PRODUCT-2`

## Deliberately deferred or not recommended

Listed so they are not silently dropped and not re-litigated without new information.

- **INSTR-5 closed-form synth voices (PolyBLEP, FM, unison, glide).** Product-direction change (pitch doc excludes synthesis), XL once UI and persistence count. 'Alias-free' is overstated and double sin() makes FM costlier than estimated. Revisit only after a deliberate decision that BeatWave is more than a sample sequencer.
- **IO-8 MIDI input and record-into-grid.** Reverses the pitch doc's exclusion, needs a product decision, depends on a monotonic-clock map, voice pool and undo. AMidi needs API 29 against minSdk 26.
- **Streaming time-stretch in the render path (SoundTouch, Rubber Band).** Stateful FIFOs violate the stateless mixer (seek, loop wrap and recommit need re-priming). SoundTouch is LGPL, Rubber Band is GPL. Use the offline variant cache (TIME-4) if tempo elasticity is wanted.
- **Ableton Link and MIDI clock out (TIME-7 v2).** Link is GPLv2+/proprietary; no external-sync user; needs timestamp snapshot machinery.
- **EXPORT-2c/d LUFS and true-peak targets, 44.1/48 selector beyond device rate.** Static gain into heavy limiting on quiet loop mixes is a trap; the true-peak backstop already comes with DSP-2. Rate selector needs a rate-keyed export bank (PERF-V3) and a frame-count rescale, so first slice exports at the device rate only. Revisit when sharing becomes a use case.
- **PERF-3 CSR bucket index, prefix-max binary search, incremental nativeApplyEdit.** The active-range clamp (PERF-V1) makes the scan O(blocks) bit-identically; index pays only beyond about 10^4-10^5 blocks; incremental score COW is an L/XL redesign with no measured need. Re-validate the '<1% at 12,800 blocks' claim with PERF-2 on a Release build before finally closing this.
- **THEORY-V2 / PRODUCT-V3 default-loop-length ceil fix (68 vs 64 units).** defaultLengthGridUnits is deleted by ARCH-3b. The bug is live on the current launch screen until then, so apply the epsilon-tolerant round-up (16 lines with two tests) only if the cutover slips a full sprint.
- **MIX-7 score-driven sidechain, MIX-8 automation lanes, INSTR-8 polyphony cap/steal.** Each needs strips, the FX graph and lane UI first; concurrency is UI-bounded to about 200 windows, so the cap is insurance. Follow-ons after MIX-6 proves the graph.
- **24 units per beat (triplets/32nds) and THEORY-7 per-block offsetTicks.** XL migration touching persisted ints, 13+ Kotlin references and the grid UI. Baked swing (TIME-6) gives the feel at S cost.
- **THEORY-2/5/9 chord stamper, progression lane, song sections; THEORY-8 modes and snap.** No bundled tonal one-shot exists, so chord stamping is gated off for all bundled sounds; sections need a virtualized grid, loop region and undo; the straddling-block rule is not audio-neutral. Modes (spec-deferred) wait on PRODUCT-1, UX-4 and content.
- **EXPORT-6 .beatwave bundles, PRODUCT-7 beat links.** No distribution channel evidenced; custom-scheme links are not tappable in messengers. If ever built: SAF file plus QR, carry sample index entries, verify hashes. Backup rules ship separately as GAP3-1.
- **EXPORT-5b FLAC/Opus export and EXPORT-8 SMF export.** FLAC needs libFLAC (platform encoder is raw-frame, likely 16-bit only) and Opus needs API 29+. SMF pitches are wrong without sample roots. (AAC .m4a stays on the roadmap as EXPORT-5.)
- **ARCH-2 full ProjectSession/EngineApi and ARCH-6 explicit engine handle plus AssetManager global ref.** Conflated pipeline (PERF-5) plus durable saves fix the actual bugs; the global-ref risk is theoretical. The multi-instance MainActivity stale-copy clobber remains a known risk, see open questions.
- **PERF-7b ADPF performance hint.** Needs API 33+, unquantified benefit on Exynos, useless on the Retroid. Revisit only if PERF-2/soak shows deadline misses on API 33+ devices. (The xrun tuner stays on the roadmap as PERF-7a.)
- **DSP-7 as a standalone pan-law item.** Merged into MIX-3 with a centre-unity law; no separate work.
- **GAP1-8 string externalization / UiText / locales.** Speculative for an English-only personal app; ~20 VM messages plus 39 literals plus 35 coupled assertions. Do only if a second language is planned; the en-XA pseudo-locale half of GAP1-4 waits on it.
- **GAP3-4 referrer-label confirm dialog and tighter 32 MiB share cap.** Referrer is spoofable and 32 MiB rejects an ordinary 4-minute song. Only the try/catch and content:// allow-list survive (in ARCH-3a).
- **GAP3-8 verify_loops.py reproducibility script and GAP3-9 export-time rights notice.** Little value if loops never change; one paragraph of provenance plus a license field (in PRODUCT-2) covers it. Export notice has doubtful legal or product value; origin enum lives in PRODUCT-1.
- **DSP-6/IO-7 normalization, silence auto-trim, raising the volume ceiling.** Mutates user audio or fights gain staging; stop-time peak measurement and the meter (IO-5) are enough.
- **GAP2-4 FLAG_KEEP_SCREEN_ON during takes.** Unneeded once background policy is decided; costs battery. Reconsider only if the screen-off arm of the manual check fails.
- **IO-2b timestamp-based per-take offset as a separate item; standalone IO-1 WAV head/tail padding.** IO-V2 plus loop=false remove the placement error without baking silence into WAVs (avoids kit-reuse and negative-trim problems). Timestamp offset folds into DSP-V2b only if loopback calibration proves insufficient.
- **EXPORT-2-defer-marker.** Placeholder entry in the Next stage retained for traceability of dropped EXPORT-2 c/d; no work scheduled (see the LUFS defer above).

## Decisions needed

These gate specific items; none can be settled by more analysis.

- Is a Play Store release planned? It decides whether ARCH-8b (API 36), PRODUCT-9, About/Licenses and unique naming are gates or ignorable (16 KB alignment is cheap either way).
- Is BPM meant to become user-editable (engine backlog T1/T2)? TIME-4, the BPM control, swing/count-in math and TIME-V3 all hinge on this; DEFAULT_BPM=90 is a constant today.
- What is the intended melodic instrument model: sliced phrase loops or true single-note instruments? It decides whether PRODUCT-2b/INSTR-7 are worth building or phrase slicing (INSTR-6) suffices.
- Should the key/scale become a persisted Project field applied only for renderVersion>=2? That reverses the grid spec's session-only decision.
- Is per-note velocity in scope? The grid spec excludes it, but audition, drum dynamics and the velocity lane lean on it.
- Do the device/galaxy-tab-s9fe and device/galaxy-z-fold-5 branches still need merging, and what replaces the Tab/Fold two-pane layouts that the cutover deletes (the grid spec defers a two-pane grid that reuses androidx.window)?
- Is the Retroid Pocket 2+ (API 28, PerformanceMode::None, 960-frame bursts, D-pad, no ApplicationExitInfo) a supported target? It changes latency targets, keyboard/D-pad priority (GAP1-V1), background-mic policy and memory-governor priority.
- Should recordings and imports be in cloud backup at all? GAP3-1 excludes them (25 MB cap, privacy); the cost is that a cloud-only restore has projects without the user's own takes.
- What does the manual background-capture check show on the Tab S9 FE and on API 28? It decides whether IO-4b is P0 (dedicated microphone service) or P2 (ON_STOP finalize only).
- Should fades apply to all existing projects (current plan, as a defect fix) or only to renderVersion>=2? And is a permanent legacy tanh path acceptable code weight?
- Is higher-than-16-bit export or float exchange wanted by any real user? If not, DSP-8 shrinks to dither plus the B2 decode fix.
- Should MainActivity stay standard launch mode? Multiple instances each hold a private Project copy and the older one clobbers the newer's edits; a singleTop/singleTask change or an app-scoped ProjectSession (deferred ARCH-2) would fix it but affects the test harness.
- Is a second UI language planned? If yes, GAP1-8 (UiText plus strings.xml) moves from defer to the cutover follow-ups.

## Corrections to existing plans

Audit-reported unless stated; each was found by an analyst reading code against the earlier document.

- **Engine backlog B1, B3 and D1 are listed as open but are fixed in code** (commits `07f5372`, `551597d`,
  `0255e76`; `kRetainedScoreCount = 8` at `AudioEngine.h:345`; `SampleBank` has an LRU). **E7** (Oboe buffer-size
  tuning) exists only as a git stash (`git stash list` confirms an "E7: Oboe buffer-size tuning + diagnostics
  logging" entry); `PERF-7a` replaces it as an xrun-driven tuner gated on telemetry.
- **Pitch doc S3** claimed sidechain needs a topological render order; refuted — with a two-pass graph (all voice
  mixes, then strip DSP) any strip can key from any pass-1 buffer without ordering constraints.
- **Backlog E5** (a lookahead limiter fights the low-latency stream) is refuted for a short lookahead: 72 frames
  (1.5 ms) is below one 128-frame burst. `DSP-2` departs from E5 only because `renderScore` is stateless, so a
  lookahead is just "render N+L", not a delay line.
- **Backlog E4** ("`tanh` only sounds like hard clipping at 4–8 tracks") is partly refuted: it is level-dependent
  distortion from the first track (audit: 2.0% THD at −6 dBFS, 3.6% at −3.1 dBFS). The 30%-over-full-scale mix
  figure above was re-checked.
- **Device-adaptive plan:** `ActivityManager.getMemoryClass()` is the per-app Dalvik heap limit and cannot size the
  native sample cache; `GAP2-6` uses a `totalMem`-scaled budget instead.
- **Audits backlog A4** ("zero semantics anywhere") is stale — A4 shipped (`4811e88`). The real accessibility gap
  is `GridScreen`, not the old screen. The grid plan's Tier 3.1 exit criteria contain no accessibility criterion;
  `ARCH-3a` and `GAP1-2` add one.
- **Grid spec decisions that some items reverse or need sign-off for:** the session-only scale (`PRODUCT-1` makes
  the key a persisted project field), no velocity (`INSTR-4`), and the Instrument/Kit exclusion (`PRODUCT-2b`).

## Appendix A — Hypotheses the analysts refuted

Seeded hypotheses (some written by the person commissioning the audit) and analysts' own premises that the code
contradicted. Kept so the same wrong ideas are not proposed again. Entries beginning *CONFIRMED* were checked and
found true; they are here for the record.

- DSP: Loop wrap 'frameB = trimStart' is a discontinuity: PARTLY REFUTED. MixEngine.cpp:67-68 interpolating the last sample toward the first is the correct seamless-loop behavior. Bundled loops are seam-clean: measured \|last-first\| <=0.006 (-44 dBFS), because generate_placeholder_loops.py:56-66 and the per-note attack/release bake end fades in. The defect only bites user-trimmed, imported or recorded material and block-window truncation, which are covered by DSP-1.
- DSP: Linear interpolation low-passes/aliases 'at any ratio': REFUTED at ratio 1.0. pitchRatio = pow(2,0)=1.0 exactly (ScoreBuilder.cpp:74) gives frac=0, an exact copy, so drum rows and row 0 lose nothing in the real-time path. At +12 st frac is also always 0 (pure decimation) so cubic Hermite equals linear there (23.6 dB on synth_arp); the loss is the 44.1->48 decode SRC and non-integer ratios.
- DSP: Denormals and DC as current problems: REFUTED. There is no recursive filter or per-block state, so nothing can decay into denormals; bundled loop DC is at most 0.0012 (-58 dBFS) and tanh passes it unchanged. FTZ/DAZ (none set anywhere per grep) becomes necessary only when S2 adds biquads/reverb.
- DSP: Mono duplication 'without a pan law' is a mixer bug: REFUTED as a bug. MixEngine.cpp:75-79 is an intentional dual-mono copy and no pan control exists at all (LoopBlock.kt has none). The gap is a missing feature (DSP-7).
- DSP: WavWriter hard-clips or is true-peak-unsafe on mixed content: REFUTED as a clipping source. tanh output is strictly below 1.0 (peak -0.11 dBFS on the 4-loop mix), so the int16 clamp at WavWriter.cpp:32-38 never engages for a mix; there is still no dither, no true-peak awareness and no bit-depth choice (DSP-8).
- DSP: tanh is a CPU concern: REFUTED. 1920 tanhf calls per 960-frame stereo callback is a negligible share of the 20 ms burst; its problem is audible distortion and level-dependence, not cost.
- PERF: Disk I/O sits on the tap-to-audible path: refuted. toggleGridCell updates _uiState immediately (ArrangementViewModel.kt:621) and the JSON save runs after loadProject inside the background coroutine (:624-627), so it does not delay the engine commit; the real costs are write amplification and ordering (PERF-5, bug 1). The full O(N) rebuild per tap and the lack of debounce or incremental commit ARE confirmed.
- PERF: The atomic swap, retirement ring and quiescence wait are a significant real-time or memory cost: refuted. Steady state holds kRetainedScoreCount=8 scores (AudioEngine.h:345) at 72 B/block, about 0.6 MB at 1000 blocks and 7 MB at 12.8k blocks; the audio thread adds two seq_cst operations per callback (AudioEngine.cpp:293-294), a few ns on arm64. The only bad case is a callback duty near 100%, where waitForScoreReadQuiescence (:589-596) can stall a commit up to 2000 x 200 us = 400 ms while holding both mutexes and then reclaims anyway, which is an overload symptom rather than a normal-load cost. Retired scores can pin evicted SampleBuffers outside the 256 MiB cache budget, but only when sample sets change.
- PERF: Missing -ffast-math, LTO or NEON flags are the CMake problem: refuted as framed. arm64 has NEON as baseline, LTO gains almost nothing when the hot loop is a single translation unit, and -ffast-math is unsafe here (it can remove ScoreBuilder's NaN guard at ScoreBuilder.cpp:75). The real defect is that the Debug variant, the only one ever built or installed, has no -O flag at all (PERF-1). Manual thread-priority or affinity hints are not verifiable from the source and should not be added, since AAudio manages the callback thread priority.
- PERF: The per-sample tanh is a CPU hotspot: refuted. About 256 tanh calls per 128-frame stereo callback is an estimated 3-5 us, about 0.1-0.2% of the 2.667 ms budget (MixEngine.cpp:87-89); it matters for tone (E4), not headroom. Only the extra pass over an all-zero buffer is worth skipping (PERF-3).
- PERF: renderScore allocates, locks or does JNI/IO per callback: refuted. MixEngine.cpp contains only arithmetic on the immutable score, and the recording branch reads with a zero timeout (AudioEngine.cpp:353-354). The per-frame int64 modulo on active frames is confirmed but is a division-cost issue, not an RT-safety issue.
- INSTR: 'Melodic content is limited to the 8 bundled loops': refuted. SAF import (AudioImporter) and mic recording add arbitrary samples that land on the melodic grid (ArrangementViewModel.kt:1386-1459 confirmPendingRecording assigns them with pitchRow=0). The surviving part is that none carry root-pitch metadata, and there is no runtime synthesis (no oscillator code in app/src/main/cpp); the 8 bundled loops are themselves synthesized offline by tools/generate_placeholder_loops.py.
- INSTR: 'The stateless mixer blocks envelopes, release tails and polyphony': refuted. The gate length is known at commit (ScoreBuilder.cpp:92-93) and position depends only on transportFrame - blockStartFrame (MixEngine.cpp:52-56), so ADSR and release are closed-form, overlapping tails sum for free (:79), and tails only require extending the resolved window and export duration. What it truly cannot express is IIR filter/delay/reverb state, Karplus-Strong or feedback FM, and live-played notes with unknown note-off; those need baked patches or a descriptor queue (INSTR-4/5).
- INSTR: 'No velocity at all': partly refuted. A per-note gain exists as LoopBlock.volume (LoopBlock.kt:16, clamped 0..1 at ScoreBuilder.cpp:109) but is reachable only through the modal long-press editor (LoopBlockEditor.kt:75-85); there is no gesture-level velocity, no curve and no velocity layers.
- INSTR: 'Formant shift' as the main varispeed artifact: refuted for the bundled content. The loops are synthetic harmonic stacks with no formants; the audible artifacts are proportional scaling of every time-domain feature (chord tremolo 4.5 -> 6.7 Hz and vocal vibrato 5.5 -> 8.2 Hz at +7, attack times, loop period 2667 -> 1780 ms) and off-grid riff timing (444.9 ms = 2.67 units at +7). The claims that note length is only a gate, that there is no note-off, and that row 0 has no root all hold.
- MIX: PARTLY REFUTED (hypothesis 3 as worded): 'volume changes require swapping an entire score' is literally true (volume is baked into ResolvedLoopBlock, PlaybackScore.h:40) but the swap is not the problem: it is a lock-free, memory-safe pointer publish (AudioEngine.cpp:230). The real defects are that volume is not a live parameter at all today (the slider only commits on Save, LoopBlockEditor.kt:172-176, then rebuilds every block via rebuildAndPersist) and that the swap steps gain instantly with no smoothing; the conclusion that a live knob needs engine-level smoothed atomics does hold.
- MIX: NOT REFUTED (hypotheses 1 and 2 confirmed, carried into MIX-3/MIX-4): Track.kt:10-21 has no volume/pan/mute/solo/FX fields and there is no runtime metering; the only 'peaks' in the app are static waveform display data (WaveformPeaksExtractor.kt, Waveform.kt).
- MIX: PARTLY REFUTED (backlog E4 claim that tanh only sounds like hard clipping at 4-8 tracks): tanh is level-dependent distortion from the first track, computed at 2.0% THD for a -6 dBFS sine and 3.6% for a -3.1 dBFS sine (the level of the bundled synth_arp_01/vocal loops), so headroom work matters at 1-2 tracks, not just 4-8.
- MIX: REFUTED (pitch doc S3 claim that sidechain requires a topological render order): with a two-pass graph (all voice mixes first, strip DSP second) any strip can key from any pass-1 buffer with no ordering constraint and no cycles.
- MIX: REFUTED (E5 rationale that a lookahead limiter fights the low-latency stream): a 72-frame (1.5 ms) lookahead is under one 128-frame burst, is applied equally to all tracks so alignment holds, and can be subtracted from the reported playhead; there is no live input monitoring through the mix bus.
- TIME: H2 (bpm change does not re-time content; non-grid-length samples drift) is confirmed in code (ScoreBuilder.cpp:83-84 ignores bpm) but PARTIALLY refuted as a live problem: the 8 bundled loops are exactly grid-aligned at 90 BPM (WAV headers give 117,600 frames @44.1 kHz = 16 x 7,350 and 128,000 @48 kHz = 16 x 8,000; the 5.333 s loops are 32 units), and no code path can change bpm (only DEFAULT_BPM=90), so drift is unreachable today. It becomes real for any BPM other than 90, pitched melodic rows (period = trim/2^(n/12), e.g. +7 semitones is ~10.68 units), imports and recordings.
- TIME: Assumed 128-frame/2.67 ms callback budget is not what the code observes: AudioEngine.cpp:325-327 records framesPerBurst=960 at 48 kHz (20 ms), so latency and jitter figures in the ideas quote both; the per-second CPU cost of the mixer is burst-size independent.
- IO: PARTLY REFUTED - 'takes land late because there is no latency compensation': the absence is confirmed (getInput/OutputLatencyMillis are wired to nothing above PlaybackEngine.kt:266-267), but the sign is not uniformly late. Placement floors the start to the grid (GridConstants.kt:32-35), so takes play EARLY by startFrame mod perUnit (up to 166.7 ms at 90 BPM/48 kHz) and net error is that minus the round-trip latency; compensating latency alone would leave takes random in sign (see IO-1 before IO-2).
- IO: CONFIRMED (not refuted): no stream-loss handling - Grep for AudioStreamErrorCallback/onErrorBeforeClose/onErrorAfterClose over app/src = 0 hits, and Oboe 1.9.0 with no callback leaves a disconnected stream as-is (AudioStreamAAudio.cpp), so playback freezes with the UI still showing Playing.
- IO: CONFIRMED (not refuted): no audio focus - Grep for AudioFocus/AudioManager/AudioDeviceCallback/BECOMING_NOISY/AudioAttributes over app/src = 0 hits; the Media3 SimpleBasePlayer adapter sets none.
- IO: CONFIRMED (not refuted): no input meter/clip indicator (Grep for meter/peak/clip in ui = none) and input hardcoded Stereo with no retry (AudioEngine.cpp:519); B4 remains open.
- IO: CONFIRMED (not refuted): no MIDI - Grep -i midi over the repo hits only tools/generate_placeholder_loops.py; no android.media.midi in app/src or the manifest.
- IO: PLAN STALENESS (not a seeded hypothesis): the engine backlog lists B1 (recording cap), B3 (mRetiredScores) and D1 (SampleBank eviction) as open, but the code has them fixed (commits 07f5372, 551597d, 0255e76; AudioEngine.h:345 kRetainedScoreCount=8; ArrangementViewModel.kt:1224 passes MAX_SONG_LENGTH_SECONDS; SampleBank LRU). E7 is still only in git stash@{0}; I did not re-verify the pitch doc's 515 ms Fold 5 figure.
- THEORY: 'Scale.kt is only a visual highlight' is false: scaleVisibleSemitones (Scale.kt:61-62) feeds a row filter (GridScreen.kt:746-749) that removes out-of-scale empty rows, so it constrains placement by omission. It never snaps or nudges, and out-of-scale rows that already hold notes stay visible unmarked.
- THEORY: 'The root is always C' is false: there is no root at all. Offset 0 is the sample's own pitch (Scale.kt:49-60), so highlighting is correct only relative to each sample. The bundled roots are A, D and C (generator :341-345, :396, :404), the chip shows no tonic, and the state is one global session-only var defaulting to Chromatic (GridScreen.kt:366). Kept as THEORY-1.
- THEORY: 'Samples have no key or BPM metadata beyond duration' is half false: manifest.json carries bpm:90 for every bundled sample and LoopManifestParser.kt:39 reads it, but :46-52 discards it when building Sample. Key metadata truly does not exist anywhere. 'Imported audio cannot be conformed' is true: imports carry only duration and peaks (AudioImporter.kt:71-76), and there is no time-stretch (ScoreBuilder.cpp:83-87 ties loop length to pitchRatio).
- THEORY: Confirmed, not refuted: no chord, arpeggio, progression or rhythm-generator code exists (Kotlin+C++ grep for chord/arpeggi/euclid/progression/transpos/swing/humaniz/detect/autocorr/yin/chroma found nothing), which is why THEORY-2, 3, 5, 6 and 7 are new.
- UX: H1 partly refuted: it is not true that a placed note is heard only after pressing Play. Edits made while playing are committed live via rebuildAndPersist -> loadProject/commitProject (ArrangementViewModel.kt:620-628) and sound when the playhead reaches them. What survives (and drives UX-2) is silence at tap time and whenever transport is stopped/paused (AudioEngine.cpp:261-277), with no pitch feedback and MediaPlayer-only preview (previewSample, 862-892).
- UX: H4 partly refuted: the grid is not inoperable by TalkBack - each cell is individually focusable with a content description and an onClick action (GridScreen.kt:1020-1026, 1099). What survives is that no cell announces its row, step or pitch (constant 'Empty cell, tap to place'), giving 25 x 64 = 1,600 identical stops, and empty melodic cells are not keyboard-focusable; the old screen's bar/beat descriptions (ArrangementScreen.kt:962-966) did not carry over.
- UX: H2 and H3 survived unrefuted: grep of app/src/main finds no undo, redo, select/lasso, clipboard/paste, duplicate, snap or pinch/zoom symbol, and resolution is fixed at 1/16 (GridConstants.kt:17) with fixed 28 dp cells (GridScreen.kt:210-212).
- EXPORT: Project JSON writes may not be atomic: largely REFUTED. ProjectRepository.save writes <id>.json.tmp then renameTo (ProjectRepository.kt:31-44) and ImportedSampleIndex does the same (ImportedSampleIndex.kt:59-71), so a process kill mid-write cannot leave a truncated target. What survives is weaker: no fsync (power loss or kernel crash), unserialized concurrent writers, a non-atomic copy fallback, and the destructive-overwrite-on-load-failure bug (EXPORT-1 and bugs).
- EXPORT: Export may render in realtime or hold up live playback: REFUTED. It uses a throwaway offline AudioEngine driven by renderOffline (ProjectPlaybackController.kt:104-129, audio_engine_jni.cpp:390-406), faster than realtime, and never touches the live Oboe stream. The PlaybackEngine.kt:154-159 comment that the engineMutex is needed because of shared decode state is inaccurate (SampleBank is a per-engine member, AudioEngine.h:246); the mutex only causes harm (recording-stop bug).
- EXPORT: Killing the app mid-export loses user data: REFUTED as stated. Exports are disposable cacheDir artifacts (ArrangementViewModel.kt:1092-1095) and project data is unaffected; the real residue is a truncated file at the final name whose header already claims full length (WavWriter.cpp:53,69-83), which is never shared because pendingShareFilePath stays null on failure. Progress and cancel are CONFIRMED absent (single JNI call, Boolean isExporting only).
- EXPORT: Export is 16-bit WAV only with no stems, MIDI or compressed formats: CONFIRMED (WavWriter.cpp:58; no encoder, MediaMuxer, MIDI or zip code in app/src/main; CMakeLists.txt:8-22 links only oboe/log/android). Also confirmed: no bit-depth, rate or loudness options, and no dither.
- EXPORT: Import decodes via MediaCodec to 16-bit so float/24-bit sources lose precision: CONFIRMED for the MediaCodec path (AudioImporter.kt:405 fixes 2 bytes/sample and KEY_PCM_ENCODING is never set), but REFUTED for the native WavDecoder, which already handles 8/16/24/32-bit integer PCM (WavDecoder.cpp:21-47). IEEE-float WAV remains unhandled (WavDecoder.cpp:84-91, backlog B2) but is unreachable today because every import is re-encoded to 16-bit PCM.
- EXPORT: Reverb or effect tails are truncated by export: REFUTED as a current defect, since no reverb or effect exists. Export length is simply the last block end (ArrangementViewModel.kt:637-646, PlaybackEngine.kt:163-168), with hard cuts at block ends by design. It becomes real when S2 lands, hence the tailFrames parameter in EXPORT-3.
- EXPORT: FileProvider over-exposes app storage: REFUTED. file_paths.xml:16-17 grants only cache exports/ and files crash_logs/, with exported=false and grantUriPermissions in the manifest (AndroidManifest.xml:93-101); this matches audit A8's intent.
- QA: Partly refuted: 'the instrumented suite depends on the 2 personal devices'. 11 of 45 @Test (6 of 23 classes: MixEngineDrift, RecordingCapacity, RecordingGridAlignment, RetiredScoreBound, SampleBankEviction, AudioImporterSizeCap) use only the offline nativeTest*/nativeExport* engine or the importer, with no Oboe stream and no UI; the other 34 open a live stream or Compose UI. The no-CI and documented device-load flakiness parts are confirmed (no CI files in git ls-files; grid plan 2026-08-24:271-273).
- QA: Refuted: 'the nativeTest* offline engine can host golden-audio tests'. nativeTestAdvanceOffline returns void and discards the mix into a thread_local scratch (audio_engine_jni.cpp:221-232, AudioEngineBridge.kt:203); only nativeExportRenderToFile emits samples, as a file, and existing tests assert only its header (ExportShareTest.kt:150-170). Hence the host lane in QA-1.
- QA: Mostly refuted: 'fuzzing the decoders/importer is high-value'. WavDecoder only reads bundled assets or canonical 16-bit WAVs the app wrote itself; untrusted file parsing is delegated to platform MediaExtractor/MediaCodec (AudioImporter.kt:212-258, writeWavFile at :362), so WavDecoder fuzzing finds robustness and ABI-divergence bugs (see the chunk-walk bug) rather than attack surface, and is folded into QA-1 as property tests rather than a standalone item.
- QA: Confirmed rather than refuted, for the record: no xrun counter is read (only a comment at AudioEngine.cpp:732); release builds are not minified (build.gradle.kts:37) and native debug symbols are not configured.
- PRODUCT: PARTLY REFUTED - 'no BPM tag': every manifest entry has bpm:90 (manifest.json lines 8,15,22,...) and LoopManifestParser.kt:39 parses it, but Sample construction at :45-52 discards it. So the defect is a dropped field, not missing data. No key/root/genre/tags/kind exists anywhere (Sample.kt:11-19), so the rest of the hypothesis holds.
- PRODUCT: PARTLY REFUTED - 'no sharing beyond WAV export': inbound sharing exists (AndroidManifest.xml:60-64 ACTION_SEND audio/* filter, MainActivity.handleShareIntent) and a crash-log text share exists (GridScreen.kt:338-351). Outbound project sharing is WAV-only (GridScreen.kt:328-333) and there is no project/link/remix sharing. The 'no templates, no onboarding' half is confirmed by grep (no onboard/welcome/tutorial/template/starter hits in app/src/main).
- PRODUCT: NOT REFUTED (confirmed, sharpened): the alphabetical sort is real (ArrangementViewModel.kt:344,1029,1457) and is case-sensitive, which is worse than described. It is reported as a bug plus PRODUCT-6.
- ARCH: 16 KB failure of our own native lib: refuted for libbeatwave_audio.so and libc++_shared.so (PT_LOAD p_align 16384 on arm64-v8a and x86_64, APK zip offsets 16 KB aligned, NDK r28 default; NDK flags.cmake only forces 4096 when ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES is off). Only the prebuilt liboboe.so 1.9.0 (4096) fails, so the fix is ARCH-4, not linker flags in CMakeLists.txt.
- ARCH: Missing foreground-service type or permission for background playback: refuted, AndroidManifest.xml:22-23,75-78 declare FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK and foregroundServiceType mediaPlayback (the Play Console FGS declaration form is still needed at publish time per support.google.com/googleplay/android-developer/answer/13392821); POST_NOTIFICATIONS is declared (:24) and requested lazily at first play (GridScreen.kt:289-302).
- ARCH: Edge-to-edge enforcement at targetSdk 35 breaks layouts: largely refuted, GridScreen wraps content in a Scaffold that applies system-bar padding (GridScreen.kt:369-436); only an explicit enableEdgeToEdge() and a dialog/sheet inset pass are missing (folded into ARCH-8).
- ARCH: Predictive back at target 36 breaks custom back handling: refuted, grep finds zero BackHandler, onBackPressed or enableOnBackInvokedCallback usage in app/src, and there is no orientation lock for the Android 16 large-screen rule to override.
- ARCH: Tier 3 is blocked only by real-hardware verification: partly refuted, static reading found three parity gaps (64-column grid vs 240 s song, no seek in GridScreen, state lost on recreation) plus unmerged device branches, all folded into ARCH-3.
- ARCH: NDK/C++ standard pin is a compliance problem: refuted, ndkVersion 28.2.13676358 is installed and pinned (build.gradle.kts:13) and C++17 is adequate; the real native build-config issue is the missing -O level in debug (ARCH-1).
- GAP1: Brief premise that drag-and-drop CustomAccessibilityAction fallbacks exist: refuted. git grep for dragAndDrop\|Add to Track\|CustomAccessibilityAction( on master, device/galaxy-tab-s9fe and device/galaxy-z-fold-5 finds only 'Seek to end' (ArrangementScreen.kt:667). The device-adaptive DnD, 'Add to Track N' actions and Settings sheet (spec :93-107; plan :25-33) are unbuilt, so the ARCH-3 'DnD fallbacks preserved' item currently refers to nothing.
- GAP1: Backlog A4's 'zero contentDescription/semantics anywhere' (backlog :58-65): stale. A4 shipped in commit 4811e88, and the code now has about 36 semantic-string sites in ui/ (ArrangementScreen 15, GridScreen 6, Library 4, Editor 4, Project 5, Crash 1, Scale 1). The real gap is GridScreen, not the old screen.
- GAP1: Roadmap IDs UX-1 and ARCH-3 appear in no repo document (grep of all *.md, plus a grep of the whole tree excluding build/). The cutover exit criteria are grid plan Tier 3.1 (:267-274), which contains no accessibility criterion.
- GAP1: Icon-only controls lacking contentDescription: none exist. grep finds zero Icon/IconButton/Image/painterResource in app/src/main, and the LoopLibraryCard comment (LoopLibraryBottomSheet.kt:270-286) says the project has no icon-button precedent. The only glyph-like control, the recording dot and timer, is clearAndSetSemantics'd under a static row description (ArrangementScreen.kt:875-887).
- GAP1: Playhead needs its own semantics node: not needed. It is deliberately cleared (GridScreen.kt:983) and the position is exposed on the transport bar. The earlier decision to avoid a continuous liveRegion (ArrangementScreen.kt:585-591) is sound; only range info and bar.beat are missing (GAP1-7).
- GAP1: The record-pulse animation ignores reduced motion: probably false. Compose animation classes follow the animator duration scale (eevis.codes/blog/2022-12-12/android-animations-and-reduced-motion), and the pulse is about 0.83 Hz. The non-animation, polled playhead is the part that ignores the setting.
- GAP2: Task brief premise 'PERF-V2 only chunks the export' is false at HEAD 891254b: audio_engine_jni.cpp:397-398 allocates totalFrames*2 floats and calls renderOffline once for the whole length, and the ids PERF-V2, PERF-4, IO-3, IO-4b, IO-5 and IO-6 appear in no repo document (grep of docs/ returns nothing), so they are external roadmap labels.
- GAP2: Device-adaptive plan premise that ActivityManager.getMemoryClass() sizes the native sample cache: it is the per-app Dalvik heap limit (ActivityManager docs), which malloc'd SampleBuffers are not counted against.
- GAP2: SampleBank.h:95-104 premise that the 256 MiB budget bounds session native growth: evicted entries stay pinned by up to 8 retired scores (AudioEngine.h:345) and eviction runs after insertion (SampleBank.cpp:55-58), so real peaks are 1.5-2.6x the budget.

## Appendix B — Bugs the audit kept

85 bugs after verification: 12 high, 47 medium, 26 low. The same defect is sometimes reported by more than one
domain; duplicates are preserved because each analyst's evidence differs.

### High

- **[DSP] Every grid note and drum hit hard-truncates a full-length sample with no release.** ArrangementViewModel.kt:453-480 creates blocks with lengthGridUnits=1 (166.7 ms at DEFAULT_BPM 90) and no trim; MixEngine.cpp:52-55 drops the block at window end with no ramp. Measured last-sample step at the cut on real bundled WAVs: synth_arp 0.700 (-3.1 dBFS), vocal_ah 4u 0.611 (-4.3), bass_riff 1u/2u 0.267 (-11.5), kick 1u 0.141 (-17.0). *Suggested fix:* Per-block fade-in/fade-out gain in renderScore, resolved in ScoreBuilder (DSP-1).
- **[INSTR] Every note window edge is a hard step, so most short melodic notes click.** MixEngine.cpp:53-54 drops a block the instant framesSinceBlockStart >= blockLengthFrames and :79 has no gain ramp; ScoreBuilder.cpp:90-93 sets the window to lengthGridUnits*framesPerGridUnit and every block restarts at source frame 0, so each 16th is cut and restarted. Measured from the bundled WAVs at the default 1-unit gate (ArrangementViewModel.kt:460, 166.7 ms @ DEFAULT_BPM=90 at :1523): 18 of 24 melodic sample x {-12,0,+7,+12} combos end at >= -20 dBFS pre-tanh, worst bass_riff_02 +12 at 0.716 (-2.9 dBFS) and synth_arp 0.70 (-3.1 dBFS) at 0/+7/-12; kick at 0 st is cut at -16.9 dBFS. *Suggested fix:* Apply a minimum 1-3 ms linear or raised-cosine ramp at both block edges in renderScore (INSTR-1 first step), then real release tails.
- **[IO] Recorded take plays back early by up to one grid unit (start floored, no sub-grid offset).** GridConstants.kt:32-35 floors startFrame to a grid unit; ArrangementViewModel.kt:1332,1428-1434 places the LoopBlock there with no offset/trim; ScoreBuilder.cpp:90-91 puts sample frame 0 at unit*perUnit. Error = startFrame mod perUnit, up to 8,000 frames = 166.7 ms at 90 BPM/48 kHz (RecordingGridAlignmentTest.kt:224,249 only asserts < perUnit). *Suggested fix:* Prepend (startFrame - blockStartFrame - compFrames) frames of silence to the WAV at commit (IO-1).
- **[THEORY] Pre-grid melodic blocks (pitchRow == null) are invisible in MelodicGrid but still play.** GridScreen.kt:734 (`val row = block.pitchRow ?: continue`, so such blocks are never drawn); TrackMigration.kt:18-22 (migration fills only assignedSampleIds, never pitchRow); ProjectRepositoryTest.kt:148-151 asserts pitchRow stays null for migrated blocks (that fixture is a drum); ArrangementViewModel.kt:1411-1413 acknowledges MelodicGrid renders only pitchRow != null. toggleGridCell (:439-442) matches on pitchRow, so tapping row 0 stacks a second block over the invisible one. MainActivity.kt:54 still shows ArrangementScreen, so this is a Tier 3 cutover blocker, not a live regression. *Suggested fix:* In MELODIC kind, normalize at read time: row = block.pitchRow ?: pitchSemitones.roundToInt().coerceIn(-12,12) in MelodicGrid.blocksByCell and toggleGridCell, or backfill pitchRow in the ViewModel init after samples load (the migration function has no sample categories). Add a JVM test with a legacy melodic-track JSON fixture.
- **[THEORY] Notes are hard-gated at block end with no release ramp, so every melodic note-off clicks.** MixEngine.cpp:53 (`framesSinceBlockStart >= blockLengthFrames -> continue`) and :79 (no gain ramp); grep of fade/release/envelope/ramp in app/src/main/cpp finds only memory-order uses. ArrangementViewModel.kt:460 places 1-unit (166.7 ms at 90 BPM) blocks, and bundled melodic samples are sustained tones normalized to 0.7-0.8 (generator :193-215, :280-310), so the cut lands at up to about 0.7 full scale (roughly a -3 dBFS step, 0.6 after tanh). *Suggested fix:* Stateless per-frame gain in renderScore: g = min(1, (blockLengthFrames - framesSinceBlockStart) / releaseFrames) with releaseFrames about 3-5 ms (150-240 frames at 48 kHz), plus a matching 1-2 ms onset ramp when trimStart > 0. Verify by an offline render of a 1-unit Vocal Oh block: max \|x[n]-x[n-1]\| at the cut below 0.02.
- **[UX] Vertical scroll that starts on an empty melodic cell places a note when the finger lifts.** tapOrDragToStretch (GridScreen.kt:1054-1099) reads raw awaitPointerEvent(), tracks only x movement (1071), never checks isConsumed, and calls onTap() on any release while dragging == false (1068). MelodicGrid wraps its rows in verticalScroll (757-761) and 25 rows exceed a phone viewport, so a vertical pan begun on an empty cell is consumed by the scroller but still ends in onTap(). Compose docs say consumed events still reach children, which must ignore them (developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures), and the framework's waitForUpOrCancellation re-checks isConsumed in the Final pass (androidx TapGestureDetector.kt). No androidTest swipes vertically (GridScreenDragStretchTest only moves horizontally). Inferred from code; not executed (read-only). *Suggested fix:* Use waitForUpOrCancellation() (or check isConsumed in the Final pass) and cancel the tap when vertical movement exceeds touch slop; add an instrumented swipeUp-over-empty-cell test asserting loopBlocks is unchanged.
- **[UX] Migrated/legacy melodic blocks are silent in the UI contract but still play: invisible and undeletable in MelodicGrid.** migrateTrackAssignedSampleIds only backfills assignedSampleIds (TrackMigration.kt:18-22) and ProjectRepositoryTest.kt:149 asserts pitchRow stays null for legacy blocks; MelodicGrid skips blocks with pitchRow == null (GridScreen.kt:734) while ProjectPlaybackController.loadProject sends every block to the engine (57-77). Old-screen placement also put each next block at the end of the last one with length 4x the loop, i.e. 64 units for a 2.667 s loop (GridConstants.kt:78-93), beyond the 64 rendered columns anyway. *Suggested fix:* After samples load, backfill pitchRow = round(pitchSemitones).coerceIn(-12,12) for blocks on melodic tracks (and surface an 'N notes outside view' indicator); test against a real pulled 'current' project as the plan's Tier 0 note requires.
- **[EXPORT] Project init overwrites an undecodable project file with a blank project.** ArrangementViewModel.kt:311 loads the last project; ProjectRepository.decodeProjectFile (ProjectRepository.kt:60-75) returns null for ANY SerializationException, IllegalArgumentException (including the Project/Track init require() checks at Project.kt:19-21 and Track.kt:22-24) or transient IOException; ArrangementViewModel.kt:320-334 then builds a blank 'My Project' with the same id and calls repository.save(project) over the original file. No quarantine, no .bak. *Suggested fix:* Make load() return Missing/Corrupt/Ok; save a blank project only on Missing; on Corrupt rename the file to <id>.json.corrupt-<ts>, try <id>.json.bak, and tell the user.
- **[PRODUCT] Sounds 'Add' on a melodic track relabels the track but keeps playing the OLD sample.** ArrangementViewModel.kt:531-539 assignSampleToTrack changes only assignedSampleIds; loopBlocks keep their sampleId. ProjectPlaybackController.kt:62 (and :110 for export) resolves samples[block.sampleId], so existing notes still sound as the previous instrument while the pill shows the new name. The doc at ArrangementViewModel.kt:524-530 and the test comment GridScreenSoundsPickerTest.kt:119-121 claim 'it now plays the newly-assigned sample', but the test (:124-125) only asserts the cell is 'Filled' and the pill text. Reassigning a melodic track to a DRUMS sample flips it to DrumGrid, whose blocksByCell (GridScreen.kt:911-914) drops every pitchRow != null block, leaving notes that are audible but invisible. *Suggested fix:* For melodic-to-melodic reassignment, rewrite each block's sampleId to the new id (keeping pitchRow/pitchSemitones), or resolve pitchRow != null blocks against track.assignedSampleIds[0] in loadProject/export. Block or convert cross-family reassignment. Add an offline-render test asserting the spectrum changes after reassign.
- **[PRODUCT] Migrated legacy melodic blocks (pitchRow == null) and anything past column 63 are invisible in the grid but still play.** TrackMigration.kt:18-22 fills only assignedSampleIds and never derives pitchRow. MelodicGrid skips such blocks (GridScreen.kt:734 'block.pitchRow ?: continue') and renders only columns 0 until GRID_COLUMNS = 64 (:208,:782). Legacy placement defaults each block to 4x loop length, 64 units for a 1-bar loop (GridConstants.kt:86-93), so the second block starts at column 64. ProjectPlaybackController.kt:61-77 schedules every block regardless. A user's existing project opens as a seemingly empty melodic grid that still makes sound, which is the Tier 3 cutover path. *Suggested fix:* In migration derive pitchRow = round(pitchSemitones) clamped to +/-12 for blocks on MELODIC tracks, and render off-grid or legacy blocks (a 'N blocks beyond bar 4' chip). Widen the canvas per PRODUCT-4. Extend the Tier 0 migration test to check visibility of legacy blocks, not just assignedSampleIds.
- **[GAP1] Grid cells announce identical, position-free text (about 209 indistinguishable TalkBack stops per screen).** GridScreen.kt:1020-1026 sets contentDescription to 'Empty cell, tap to place' or 'Filled cell, tap to remove, long-press to edit' for all 1,600 cells (25 rows x GRID_COLUMNS=64, :208, :763-828), with no pitch, bar, beat or note length. The description also says 'tap' (TalkBack needs 'double tap') and duplicates the onClick label 'Place' (:1099). The old BlockView announced sample, category, bar and beats (ArrangementScreen.kt:962-966), so the cutover regresses shipped A4 behaviour. The Play/Stop bar sits after the grid in both composition and geometric order (GridScreen.kt:418-433). *Suggested fix:* Per GAP1-1 phase A: unique description with row and step, ToggleableState/stateDescription for filled versus empty, collectionItemInfo, and onClickLabel instead of instruction text. Put the grid in a traversal group ordered after the transport bar.
- **[GAP2] Native take is orphaned when the Activity is destroyed mid-take: unfinalizable, blocks all future recording, capture session stays open.** ArrangementViewModel.kt:1511 `if (!playbackEngine.state.value.isStopped) return` skips shutdown, and Media3's default onTaskRemoved keeps the service while playback is ongoing (developer.android.com/media/media3/session/background-playback), so the process and native take survive. A new VM's init collector (ArrangementViewModel.kt:278-286) never copies engineState.isRecording, recordingTrackSlot stays null, ensureRecordingFinalizing() returns null (ArrangementViewModel.kt:1262) so Stop only resets the transport (PlaybackEngine.kt:217-221); AudioEngine.cpp:500-503 then rejects every startRecording, shown as 'microphone unavailable' (ArrangementViewModel.kt:1227). The input stream stays open (privacy indicator expected on Android 12+, unverified) and the take is never written. *Suggested fix:* GAP2-1: make PlaybackEngine own the recording session state, adopt it on VM init, and finalize or discard it on onCleared when recording.

### Medium

- **[DSP] Pause, stop and seek cut the output instantly.** AudioEngine.cpp:261-277 memsets the callback to zero the moment mPlaying is false; :94-103 stopTransport/seekToFrame just store mTransportFrame. A random pause lands where \|x\|>0.3 for 99% of synth_arp, 61% of bass_riff, 50% of vocal_ah (numpy statistics o…
- **[DSP] AudioImporter trusts the extractor's sample rate and channel count, not the decoder's output format.** AudioImporter.kt:246-256 reads rate and channels from the extractor track format and :356 writes them into the WAV header; the drain loop at :301-336 ignores negative dequeueOutputBuffer results such as INFO_OUTPUT_FORMAT_CHANGED and never reads codec.getOu…
- **[PERF] rebuildAndPersist launches unordered, unserialized coroutines that share one temp file (stale commit or crash).** ArrangementViewModel.kt:620-628 does viewModelScope.launch(Dispatchers.Default){ playbackEngine.loadProject(...); repository.save(newProject) } per mutation; PlaybackEngine.kt:133-137 holds engineMutex only around loadProject, so save() runs outside any loc…
- **[PERF] Every block ends with a hard gate (no fade), producing a click at each grid note-off.** MixEngine.cpp:53-55 returns silence the instant framesSinceBlockStart >= blockLengthFrames, and grep of app/src/main/cpp for fade, ramp, envelope and click finds nothing. A 1-unit block (toggleGridCell, ArrangementViewModel.kt:453-480) of a 2,667 ms loop su…
- **[INSTR] Editing pitch in the long-press dialog desyncs the audible pitch from the note's grid row.** LoopBlockEditor.kt:62,151-160,173-176 edits only pitchSemitones, and ArrangementViewModel.kt:591-596 (updateBlock) copies volume/trim/pitchSemitones but never pitchRow. MelodicGrid places and hit-tests by pitchRow (GridScreen.kt:731-740, ArrangementViewMode…
- **[INSTR] Swapping a melodic track's sound does not re-voice existing notes, contrary to its own doc comment and test comment.** ArrangementViewModel.kt:524-539 (assignSampleToTrack) only changes assignedSampleIds, yet its comment says existing blocks 'now play the newly-assigned sample'; playback resolves by block.sampleId (ProjectPlaybackController.kt:62), which is never remapped (…
- **[MIX] Block windows hard-gate at blockLengthFrames with no fade: audible click at most grid-screen note-offs.** MixEngine.cpp:52-56 drops a block to exactly zero at its window end and :79 applies no envelope; the grid screen places 1-grid-unit blocks by default (ArrangementViewModel.kt:460,474). Reading assets/loops (44.1 kHz mono), the source sample at the 1/16-note…
- **[TIME] Recorded take is placed at floor(startFrame/framesPerUnit) but plays from its first sample, so it lands early by up to one 16th.** ArrangementViewModel.kt:1332 startGridUnit = GridConstants.startGridUnitForFrame(startFrame) (GridConstants.kt:32-35 floors); LoopBlock.kt has no sub-grid offset and MixEngine.cpp:52-64 plays from loopLocalFrame 0 at blockStartFrame; recordingStartFrame is…
- **[TIME] Lock-screen/notification transport bypasses the ViewModel's recording guards.** BeatWavePlaybackService.kt:195-205 always offers play/pause, stop and seek commands; :217-232 route handleSetPlayWhenReady/handleStop/handleSeek straight to engine.pause/stop/seekToFrame; PlaybackEngine.kt:211-226 has no recording check, whereas Arrangement…
- **[TIME] GridScreen has no seek path, so the Tier 3 cutover would remove the only in-app seek.** GridScreen.kt contains no seek call; the only consumer of viewModel::seekToGridUnit is ArrangementScreen.kt:385 (TimelineRuler tap handler at :636-642), which Tier 3 deletes; GridScreen.kt:851-872 'grid_scrub_strip' only pans horizontalScrollState and Playb…
- **[IO] Take block is longer than the take, so the take head replays in the block tail.** GridConstants.kt:41-44 ceils the block length; ScoreBuilder.cpp:83-93 sets loopContentLengthFrames to the take frame count; MixEngine.cpp:56 wraps modulo. In the test's own numbers (40,400 frames in a 6-unit 48,000-frame block) the first 7,600 frames (158 m…
- **[IO] Notification/lock-screen Pause, Stop and Seek bypass the recording guards.** Guards live only in ArrangementViewModel.kt:815 and :848; EnginePlayer (BeatWavePlaybackService.kt:217-232) calls PlaybackEngine.pause/stop/seekToFrame directly (PlaybackEngine.kt:211-226, no isRecording check). Pause makes onAudioReady return before draini…
- **[IO] Input read errors are swallowed and become silence in the take.** AudioEngine.cpp:353-358 sets validInputFrames = result ? max(0,value) : 0, so any read error (for example ErrorDisconnected when a headset mic is unplugged) falls into the explicit-silence branch (:729-738) while getRecordedFrameCount keeps growing; nothing…
- **[IO] Input FIFO is not drained, so per-take alignment depends on allocation time.** AudioEngine.cpp:529 requestStart runs before :538 scratch assign and :552 beginRecordingCommon (92 MB assign/zero-fill at :483); callbacks then read exactly numFrames (:353-354), so the startup backlog persists for the whole take. Magnitude is device-depend…
- **[IO] Finalizing a long take materializes ~138 MB on the Java heap with no OOM handling.** ArrangementViewModel.kt:1340-1344 does WaveformPeaksExtractor.extract(outputFile.readBytes()), catching only IOException; WaveformPeaksExtractor.kt:44-47 and :152-154 expand to a FloatArray; a 240 s stereo 48 kHz take is 46 MB bytes + 92 MB floats; AudioImp…
- **[IO] Recording open is Stereo-only with no fallback and every failure reads 'microphone unavailable' (backlog B4 still open).** AudioEngine.cpp:519 setChannelCount(Stereo), no retry; ArrangementViewModel.kt:1225-1228 maps every false to one message; git log shows fixes for B1/B3/D1 but none for B4.
- **[IO] Mid-take backgrounding is unhandled and the only foreground service is mediaPlayback type.** AndroidManifest.xml:22-24 and :75-78 declare no microphone permission/type; no ON_STOP/LifecycleEventObserver in ui; Android 9 behavior changes say background apps cannot access the microphone (developer.android.com/about/versions/pie/android-9.0-changes-al…
- **[THEORY] Grid is hard-capped at 64 columns, hiding content the old screen could author.** GridScreen.kt:208,782,858,937 (GRID_COLUMNS = 64, eager non-lazy rows). GridConstants.kt:78,88-93: default placement is 4 repeats x ceil(2667 ms / 166.67 ms) = 4 x 17 = 68 units for every bundled loop, so a second loop appended by the old screen starts at c…
- **[THEORY] Long-press editor's pitch slider desyncs pitchRow from the audible pitch.** ArrangementViewModel.kt:579-602 (updateBlock writes pitchSemitones and never touches pitchRow); LoopBlockEditor.kt:141-160 (integer-step -12..12 slider, initial value = block.pitchSemitones); GridScreen.kt:523-525 wires it in. After saving +3 on a block at…
- **[UX] Grid hard-caps at 64 columns, so recorded takes and later notes beyond column 63 play but cannot be seen or edited.** GRID_COLUMNS = 64 (GridScreen.kt:208, used at 782/858/937) versus 1,440 max at 90 BPM (GridConstants.kt:63-64); confirmPendingRecording places the take at startGridUnitForFrame (ArrangementViewModel.kt:1332, 1428-1434), so a take started 12 s in (column 72)…
- **[UX] Editing pitch in the long-press dialog desyncs the note's row from its sound.** updateBlock writes pitchSemitones but never pitchRow (ArrangementViewModel.kt:579-602); the engine uses only pitchSemitones (ProjectPlaybackController.kt:75) while MelodicGrid positions by pitchRow (GridScreen.kt:734), and the dialog's Pitch slider is integ…
- **[UX] Replacing a melodic instrument via Sounds leaves existing notes on the old sample, contradicting the documented behavior.** assignSampleToTrack changes only assignedSampleIds (ArrangementViewModel.kt:531-539) although its doc and plan 2.4 say existing blocks 'now play the newly assigned sample'; playback resolves block.sampleId (ProjectPlaybackController.kt:62), so old notes kee…
- **[UX] Every note ends with a hard gate and no release ramp, producing a click.** renderScore skips a block the instant framesSinceBlockStart >= blockLengthFrames (MixEngine.cpp:53-55) and ScoreBuilder carries no fade fields (ScoreBuilder.cpp:98-113). Read-only WAV analysis at 90 BPM: sample value at the 166.7 ms cut of a 1-unit note is…
- **[UX] Unserialized per-edit save and engine commit can truncate the project file or leave the engine on a stale score.** rebuildAndPersist launches an independent coroutine per edit (ArrangementViewModel.kt:620-628) whose repository.save has no lock and one fixed temp name '<id>.json.tmp' (ProjectRepository.kt:31-44); two overlapping saves can interleave writeText/renameTo, a…
- **[EXPORT] Stopping a recording blocks behind an in-flight export, so the take overshoots.** ArrangementViewModel.exportProject (:1100-1108) checks only isExporting and an empty timeline, with no recording guard, and the Export button has no enabled condition (ArrangementScreen.kt:316-335). PlaybackEngine.exportToFile holds engineMutex for the whol…
- **[EXPORT] Unsynchronized concurrent saves share one tmp file; the fallback path can throw and crash.** rebuildAndPersist launches one Dispatchers.Default coroutine per edit that runs loadProject under engineMutex and then repository.save outside it (ArrangementViewModel.kt:620-627). ProjectRepository.save (:31-44) is not synchronized (unlike ImportedSampleIn…
- **[EXPORT] Export (and playback) silently drop blocks whose sample is missing or fails to decode.** ProjectPlaybackController.kt:110 does 'samples[block.sampleId] ?: continue' and :115-125 ignores the Boolean returned by nativeExportAddLoopBlock; the export then reports success (:129 compares only the frame count) and ArrangementViewModel.kt:1114-1119 sha…
- **[EXPORT] ImportedSampleIndex.add() rewrites the index from an empty list after a bad read.** load() returns emptyList() on any parse or IO error (ImportedSampleIndex.kt:42-52); add() does 'load().filterNot{...} + sample' and writes the result (:59-70), so one corrupt or unreadable index makes the next import replace it with a single entry. All prev…
- **[EXPORT] Importer takes sample rate and channel count from the extractor, ignoring the decoder's output format (HE-AAC).** AudioImporter.kt:246-256 reads KEY_SAMPLE_RATE/KEY_CHANNEL_COUNT from the extractor track format and writes them into the WAV header (:375-388), while the drain loop (:301-336) never handles INFO_OUTPUT_FORMAT_CHANGED (grep for INFO_OUTPUT_FORMAT_CHANGED\|g…
- **[QA] Any project parse failure silently overwrites the user's project with a blank one.** ProjectRepository.kt:60-75 decodeProjectFile returns null on SerializationException/IllegalArgumentException/IOException, indistinguishable from 'no file'. ArrangementViewModel.kt:311-334 then builds a default 'My Project' and calls repository.save(project)…
- **[PRODUCT] Blocks end with a hard cut and no release, so default 1-unit notes click.** MixEngine.cpp:53-55 stops a block at blockLengthFrames with no gain ramp; grep for fade/ramp/declick/crossfade/envelope in app/src/main/cpp finds none. A tapped note is 1 unit = 7,350 frames (166.7 ms at 90 BPM, ArrangementViewModel.kt:456-460). Measured on…
- **[ARCH] Unordered concurrent project saves can crash or tear the file, and a torn file is then overwritten with a blank project.** ArrangementViewModel.kt:620-628 launches loadProject+repository.save per edit on Dispatchers.Default with no ordering; ProjectRepository.kt:36-43 writes every save to the same '<id>.json.tmp', so two overlapping saves interleave, and the loser's renameTo fa…
- **[ARCH] A shared-in audio file is re-imported on every activity recreation.** MainActivity.kt:48-50 calls handleShareIntent(intent) unconditionally in onCreate, ignores savedInstanceState and never consumes the intent, and onNewIntent does not call setIntent, so the original ACTION_SEND intent persists across recreation. Each fold/un…
- **[ARCH] A second MainActivity/ViewModel instance clobbers the first instance's edits.** MainActivity.kt:38-43 and AndroidManifest.xml:47-58 accept multiple standard-launch-mode instances from share intents, each with its own ArrangementViewModel and private ArrangementUiState.project (ArrangementViewModel.kt:237,268); the manifest claims they…
- **[ARCH] Latent after cutover: blocks placed beyond grid column 63 are invisible and undeletable.** GridScreen.kt:208,782,937 render only GRID_COLUMNS=64 (10.7 s at 90 BPM, 4 units/beat) while GridConstants.kt:59 allows 240 s (1,440 units) and confirmPendingRecording (ArrangementViewModel.kt:1332,1386-1447) places a take at the playhead's grid unit; a tak…
- **[GAP1] Editing a note's Pitch in the long-press dialog changes the sound but not the grid row.** updateBlock copies volume, trim and pitchSemitones only (ArrangementViewModel.kt:591-596) and never updates pitchRow. LoopBlock.kt:24-27 says pitchSemitones is derived from pitchRow on melodic tracks, and the grid places notes by pitchRow (GridScreen.kt:734…
- **[GAP1] Cutover removes the only seek UI, including the only TalkBack seek anchors.** seekToGridUnit is called only from ArrangementScreen (ArrangementScreen.kt:385; ViewModel :839). GridScreen.kt contains no seek reference (grep). The ruler's 'Seek to start' onClick and 'Seek to end' CustomAccessibilityAction (ArrangementScreen.kt:660-672)…
- **[GAP1] At 200% font scale the header row squeezes the track switcher to about 0 dp (predicted, not run).** GridScreen.kt:437-462 lays out TrackSwitcher(weight 1f), RecordAffordance, a 'Sounds' TextButton and ScaleChip in one Row. At fontScale 2 on 360 dp the non-weighted children sum to roughly 66 + 111 + 180 = 357 dp by my arithmetic, so the weighted TrackSwitc…
- **[GAP2] Lock-screen/notification Pause, Stop and seek bypass the take guards and silently corrupt an in-progress take.** BeatWavePlaybackService.kt:217-220 (pause), :222-225 (stop), :227-232 (seek) and :92-94 (custom Stop) call PlaybackEngine directly, skipping the recordingTrackSlot guards in ArrangementViewModel.kt:815 and :848. While paused the callback returns before read…
- **[GAP2] Recording buffer (92.16 MB at 240 s) is never released after the take.** AudioEngine.cpp:483 assigns and zero-fills capacityFrames*2 floats; neither stopRecording (AudioEngine.cpp:600-625) nor finishRecordingCommon (AudioEngine.cpp:676-688) clears, swaps or shrinks it (grep for shrink_to_fit/swap/clear in app/src/main/cpp finds…
- **[GAP2] SampleBank budget does not bound resident native memory (retired scores pin evicted samples; eviction runs after insert; mutex held across decode).** SampleBank.cpp:55-58 inserts then evicts, while :32-44 keeps decoded floats alive during resample (WavDecoder.cpp:203-205 copies at equal rates), so a 96 MiB-PCM import peaks near 401 MiB on top of up to 256 MiB cached. Evicting an entry frees nothing while…
- **[GAP2] Take finalize spikes 138 MB of Java heap and only catches IOException, so an OOM kills the process after the WAV is written but before the take is surfaced.** ArrangementViewModel.kt:1341-1344: readBytes() of a 240 s take (46.08 MB) then WaveformPeaksExtractor.kt:149 allocates FloatArray(numFrames*channels) (92.16 MB) with `catch (e: IOException)` only; pendingRecording is set only afterwards (ArrangementViewMode…
- **[GAP2] Input read errors and system mic silencing are indistinguishable from an underrun: the take is zero-filled with no signal to the UI.** AudioEngine.cpp:353-358 maps any failed read() to validInputFrames=0 and captureRecordingFrames zero-fills (AudioEngine.cpp:729-738); the input stream has no error callback (AudioEngine.cpp:514-520) and there is no isClientSilenced/AudioRecordingCallback us…
- **[GAP3] Auto Backup default-includes all recordings and imported audio, and the 25 MB cloud quota is easily exceeded.** AndroidManifest.xml:28 allowBackup="true" with no dataExtractionRules/fullBackupContent (only file_paths.xml exists in res/xml). A max take is 46,080,000 B (240 s x 48,000 x 2 ch x 2 B: ArrangementViewModel.kt:1224, AudioEngine.h:244, WavWriter.cpp:58) and…
- **[GAP3] An unreadable project file is treated as 'no project' and overwritten with an empty one at startup.** ProjectRepository.kt:60-75 returns null for both a missing file and any parse/IllegalArgument failure (including Project.init's require(tracks.size <= 8)); ArrangementViewModel.kt:311 and 320-334 then build a new 'My Project' with the same id and repository…
- **[GAP3] ImportedSampleIndex.add() erases all prior sample metadata after a failed load.** ImportedSampleIndex.kt:41-53 returns emptyList() on SerializationException/IllegalArgument/IOException; :59-62 add() does load().filterNot{...} + sample and rewrites the file, and :67-70 falls back to a non-atomic writeText. Effect: previously imported/reco…
- **[GAP3] User audio can never be deleted and is orphaned on process death.** deleteProject only deletes the project JSON (ArrangementViewModel.kt:777-795; ProjectRepository.kt:78-83). The WAV is already written before the category dialog (AudioImporter.kt:120, 362-400) while pendingImport exists only in memory (ArrangementViewModel.…

### Low

- **ARCH:** GridScreen UI state is lost on every activity recreation (fold/unfold, rotate, DeX resize); onCleared blocks the main thread behind the engine mutex held by export or loadProject; Failed sample loads are silently dropped from the score; allowBackup=true with no dataExtractionRules puts up to hundreds of MB of private audio in a 25 MB backup set
- **DSP:** WavDecoder never checks the WAV format tag, so IEEE-float WAV decodes as int32 noise (backlog B2, still open); resampleLinear has no anti-alias filter when downsampling
- **EXPORT:** Export allocates the full render in one std::vector with no exception handling, so bad_alloc aborts the whole app
- **GAP1:** Fixed 28 dp grid rows clip row labels at large font scale (predicted, not run); In RTL locales the time axis mirrors and drag-to-stretch extends the wrong way (predicted, not run)
- **GAP2:** No C++ exception containment across JNI: std::bad_alloc from very large vectors aborts the process past CrashLogger; Unreferenced recording files and .discard files are never swept
- **GAP3:** Exported activity trusts share-intent extras: no guard, no scheme check, decode starts without confirmation; Export file name is unbounded, so a long project name makes export fail with a generic message; Imported WAV header is built from the extractor's track format, never the decoder's output format
- **INSTR:** DrumGrid draws only the start cell of a multi-unit drum block, while tap-to-delete is span-aware
- **MIX:** Transport and edit transitions are hard steps: pause memset, seek counter jump and score swap; Per-track mute promised by the v1 spec was never implemented; no track-level gain, pan or solo exists either; Native library is built with no optimization flag in the Debug variant used by all instrumented device tests
- **PRODUCT:** Library sort is case-sensitive and imported names keep their file extension
- **QA:** WavDecoder chunk walk wraps on 32-bit and can loop forever (armeabi-v7a is built); Non-finite volume passes the ScoreBuilder clamp and nothing downstream guards NaN; JNI global engine is lazily created and reset with no synchronization
- **TIME:** seekToFrame and stopTransport can be overwritten by the in-flight callback's fetch_add
- **UX:** Playhead overlay is not clipped to the grid viewport; Focused track, open Sounds sheet and scale reset on rotation or fold/unfold; Timeline-era copy leaks into the grid

## Appendix C — Provenance

Produced 2026-09-27 by the Claude Code workflow run `wf_96bac43f-c89` against repository HEAD `891254b`. The full
machine-readable result (163 verified ideas with per-idea evidence, exit criteria and first steps; the critic
report; all 85 bugs) is retained with that session and can be regenerated or extended from it. The idea catalogue
is intentionally not reproduced here: the roadmap items above already carry the substance of each merged idea.
