# BeatWave for Android — Post-v1 Audits & Upgrades Backlog

Spec: `docs/superpowers/specs/2026-08-11-beatwave-android-design.md`
Implementation plan (Phases 0-8, complete): `docs/superpowers/plans/2026-08-11-beatwave-android-implementation-plan.md`

v1 is feature-complete and verified (Phase 8, commit `0b9fa7e`). This backlog scopes what
comes next: hardening the existing v1 surface (**audits**) and net-new capability
(**upgrades**), grounded in the actual current code, not speculation. Each item lists what/
why, rough size, risk, and dependencies, the same way the original implementation plan
scoped phases. Nothing here is committed to — this is the menu, not a schedule.

---

## Audits — harden and verify what v1 already ships

### A1. Import size / DoS hardening
**Where:** `AudioImporter.kt`'s `decodeToPcm` (the `pcmOut: ByteArrayOutputStream` loop).
**What:** A 60s wall-clock decode timeout exists, but nothing caps the *size* of the
incoming file before decode starts. A file shared in from another app (Phase 7's
`ACTION_SEND` intake — a less-trusted source than the user's own SAF pick) that is huge or
adversarially crafted (e.g. a long silence-only stream) can grow `pcmOut` unbounded and
exhaust memory well before the timeout fires.
**Fix shape:** query the source's size (`ContentResolver` size hint, or a running byte
count during read) and bail out with a clean `ImportError` once a sane ceiling (e.g. a
generous multiple of the ~3-minute recording cap already used elsewhere) is exceeded.
**Size:** S. **Risk:** low — purely additive, no existing behavior changes.

### A2. Crash resilience & diagnostics
**Where:** app-wide; no `Thread.UncaughtExceptionHandler`, no crash/error logging exists
anywhere in `app/src/main`.
**What:** confirmed via this session's own Phase 8 testing: a real native `SIGABRT` (inside
Android's own audio framework, not app code) killed the process with zero trail beyond
`logcat`. A production user hitting any crash today leaves nothing to diagnose from.
**Fix shape:** a lightweight uncaught-exception hook that at minimum writes a local crash
log file (timestamp, stack trace, last-known state) app-private storage can retain across
restarts, surfaced via a simple "send diagnostics" affordance if desired. Explicitly *not*
scoping a third-party crash SDK (Firebase/Sentry/etc.) here — that's a product decision
(network calls, privacy policy implications) beyond an engineering audit's scope.
**Size:** S–M. **Risk:** low.

### A3. Unit test coverage expansion
**Where:** `app/src/test` (3 files: `LoopManifestParserTest`, `DataModelSanityTest`,
`ProjectRepositoryTest`) vs. `app/src/androidTest` (9 files, all requiring the physical
device).
**What:** core Kotlin business logic with zero pure-JVM coverage: `ArrangementViewModel`'s
grid-placement math (`defaultStartGridUnit`/`defaultLengthGridUnits`, the Phase 8 max-
song-length guard), `GridConstants`' formulas (currently only exercised indirectly via
on-device instrumented tests), `AudioImporter`'s error-classification paths
(`ImportError.NoAudioTrack`/`DecodeFailed`/`IoFailure`). None of this needs real hardware
to test meaningfully — it's pure computation/state-transition logic.
**Fix shape:** extract/test the pure functions directly (many already are `private fun` on
`ArrangementViewModel` — would need small visibility changes or extraction into a
testable object, mirroring how `GridConstants` was already pulled out for exactly this
reason in Phase 5). Gives fast (`./gradlew test`, no device) regression coverage for logic
currently only checked by slow, occasionally load-flaky on-device runs.
**Size:** M. **Risk:** low.

### A4. Accessibility (TalkBack) pass
**Where:** every Composable in `app/src/main/java/com/beatwave/android/ui`.
**What:** zero `contentDescription`/`Modifier.semantics{}` usage anywhere. Icon-only
affordances (record dot, play/pause glyphs if ever iconified) and the whole timeline/
track-row structure are currently unreadable by TalkBack. Text-labeled buttons (Play,
Stop, Export) get *some* default semantics from their text, but track selection state,
per-block info, and the timeline ruler have none.
**Fix shape:** targeted `contentDescription`s on icon-bearing controls, a `semantics{}`
block on `TrackRow` announcing selection/recording state, `Role.Button` where missing.
**Size:** M. **Risk:** low, but requires an accessibility-scanner or TalkBack-enabled pass
on the real device to verify (per this whole project's own "verify on real hardware"
discipline) rather than trusting the diff alone.

### A5. Static analysis setup
**Where:** no `detekt`/`ktlint`/explicit Android Lint configuration exists in any
`build.gradle.kts`.
**What:** Android Lint ships free with AGP and has never been explicitly run against 8
phases of accumulated code. Worth a baseline pass before adding more surface area.
**Fix shape:** run `./gradlew lint`, triage the report, decide what's worth fixing vs.
suppressing with rationale. Optionally add `detekt` for Kotlin-specific style/complexity
checks if the team wants ongoing enforcement, not just a one-time pass.
**Size:** S (one-time lint pass) to M (if adding an enforced ongoing tool).
**Risk:** low.

### A6. Rotation & adaptive-layout audit
**Where:** `AndroidManifest.xml`'s `MainActivity` entry (no `android:configChanges`);
`ArrangementScreen.kt` (fixed `HEADER_WIDTH`/`TRACK_ROW_HEIGHT`/`PIXELS_PER_GRID_UNIT`
constants, no `WindowSizeClass` usage).
**What:** rotation isn't explicitly handled, so a config change goes through the default
Activity recreate path — likely fine given `ArrangementViewModel`'s state survives
recreation via the standard `ViewModel` lifecycle, but never explicitly verified in this
project (every phase's on-device testing ran in one fixed orientation on one small
handheld). Layout constants are also hardcoded, not size-class-adaptive, so a tablet or
foldable would render the same cramped-handheld layout scaled up rather than using the
extra space.
**Fix shape:** a verification-only pass first (rotate on the real device, confirm no data
loss/crash) before deciding whether adaptive layout is worth pursuing as a separate
upgrade (see U-list below is NOT the place for this — this audit is about confirming
current behavior, not building new layout).
**Size:** S (verification only). **Risk:** low.

### A7. Native engine performance/battery profiling
**Where:** `AudioEngine.cpp`'s `onAudioReady` (the real-time mixing callback) and the
Phase 6 foreground service's sustained-background-playback path.
**What:** the app has been correctness-tested extensively (drift, alignment, background
survival) but never profiled for CPU headroom margin (how close to the callback deadline
does mixing 8 tracks actually run on this budget SoC?) or battery drain over an extended
real background-playback session.
**Fix shape:** `simpleperf`/`systrace` capture during an 8-track playback session on the
real device, plus a multi-hour battery-drain comparison (background playback vs. idle).
**Size:** M. **Risk:** low (measurement only, no code change implied unless it finds
something).

### A8. Data/privacy audit
**Where:** app-wide storage locations (`filesDir/imported_samples`, `filesDir/recordings`,
`cacheDir/exports`, `filesDir/*.json` projects).
**What:** a confirmatory pass (not expected to find anything, given no network code exists
anywhere in this app) that no analytics/telemetry sneaked in across 8 phases, and that
every user-generated file (recordings, imports, exports) stays correctly scoped to
app-private storage with no unintended external readability beyond the Phase 7
FileProvider's deliberate, narrow `exports/` grant.
**Size:** S. **Risk:** low.

---

## Upgrades — net-new capability beyond v1 scope

### U1. Multiple saved projects
**Where:** `ProjectRepository.save(project)`/`load(id)`/`delete(id)`/`list()` are already
fully ID-parameterized (`app/src/main/java/com/beatwave/android/data/storage/
ProjectRepository.kt`); only `ArrangementViewModel` hardcodes a single `PROJECT_ID =
"current"`.
**What:** the persistence layer has supported multiple projects since Phase 1 — this is
purely a UI gap (a project list/picker screen, "New Project"/"Rename"/"Delete" actions).
**Size:** M. **Risk:** low-medium (touches `ArrangementViewModel`'s init path, needs care
around the existing native-engine-load sequencing).

### U2. Waveform visualization
**Where:** `Sample.waveformPeaks: List<Float> = emptyList()` — present since Phase 1
(`data/model/Sample.kt:18`, comment: "Populated by Phase 3 UI work (waveform rendering);
left empty until then") but never populated by `AssetLoopLibrary`, `AudioImporter`, or the
recording path, and never rendered by any Composable.
**What:** real waveform peaks drawn on each loop block in the timeline — closer to
BeatWave's actual reference UI (per the design spec's own stated inspiration) and useful
for trim-point selection in the block editor.
**Fix shape:** compute peaks at import/record time (a simple downsample of the decoded
PCM, native-side or Kotlin-side), persist in the existing field, render via a `Canvas` in
`LoopBlockEditor`/timeline block Composables.
**Size:** M–L (native peak extraction + new rendering code + trim-editor integration).
**Risk:** low — additive, no existing behavior changes.

### U3. Dark theme
**Where:** `AndroidManifest.xml`'s `android:theme="@android:style/Theme.Material.Light.
NoActionBar"`; no `darkColorScheme`/dynamic color anywhere in the Compose layer.
**What:** a proper `MaterialTheme` with light+dark `ColorScheme`s, following system theme
by default (`isSystemInDarkTheme()`).
**Size:** S–M (mostly mechanical once color tokens are chosen; touches every screen for
verification).
**Risk:** low.

### U4. Compressed export (AAC/MP3)
**Where:** `PlaybackEngine.exportToFile`/native `WavWriter` (Phase 7) — WAV only,
deliberately chosen over compressed formats to avoid `MediaCodec` encoder complexity at
the time.
**What:** an additional compressed export option for smaller, messaging-app-friendly
shared files, alongside (not replacing) WAV.
**Fix shape:** `MediaCodec` AAC encoder wired onto the same offline-rendered PCM buffer
Phase 7 already produces before `WavWriter` runs — the render path doesn't change, only
what happens to its output.
**Size:** M. **Risk:** medium (first `MediaCodec` *encode* path in the app — `AudioImporter`
only *decodes*; new failure modes to handle gracefully).

### U5. Parametric effects (EQ / reverb)
**Explicitly out of scope for v1** per the design spec. A real feature addition, not a
polish item — touches `MixEngine.cpp`'s render path, `LoopBlock`'s data model, and the
block editor UI. **Size:** L. **Risk:** medium-high (real-time-safety constraints on the
audio thread apply to any DSP added here, same rigor as the existing mix path).

### U6. Configurable/unlimited tracks & song length
**Explicitly out of scope for v1** (spec: "Unlimited tracks or song length"). Phase 8 just
finished *enforcing* the v1 caps (8 tracks, ~4min) — loosening them is a deliberate product
decision, not a bug fix. **Size:** M (mostly UI — track count is threaded through fairly
cleanly already). **Risk:** low-medium (revisit the max-song-length UX messaging).

### U7. Home-screen widget
**Explicitly out of scope for v1.** Quick transport controls (play/pause/stop) from the
home screen, reusing the Phase 6 `PlaybackEngine`/MediaSession state. **Size:** M.
**Risk:** low (additive; doesn't touch existing screens).

### U8. Undo/redo for arrangement edits
**Not mentioned in the v1 spec at all** — a genuinely new UX capability. Every mutation
already flows through `ArrangementViewModel.rebuildAndPersist`, which is a promising single
choke point for snapshotting project state. **Size:** M. **Risk:** low-medium (state
management correctness, especially interaction with the native engine reload on undo).

---

## Suggested sequencing (not a commitment — for discussion)

**Wave 1 (cheap, low-risk, do soon):** A1, A5, A8, A6 (verification only) — all S-sized,
low-risk audits that mostly just need doing.

**Wave 2 (moderate value, moderate size):** A2, A3, U1, U3 — crash diagnostics and unit
test coverage compound in value the longer they're deferred; U1 (multiple projects) is
unusually cheap for its value given the backend is already there; dark theme is a common,
expected feature at this point.

**Wave 3 (bigger bets):** A4, A7, U2, U4 — real user-facing/measurement work, worth doing
once Wave 1-2 items are settled.

**Wave 4 (deliberate scope expansions, discuss product priority first):** U5, U6, U7, U8 —
all explicitly out-of-v1-scope features; each is a real project in its own right, not a
quick add.

## Notes for whoever picks this up

- Every phase of v1 was built and verified with real on-device instrumented tests plus, where
  automation hit genuine limits, hands-on manual verification with evidence (screenshots,
  `dumpsys`, byte-level file checks) — see `beatwave-android-project` memory for accumulated
  device-specific gotchas (this device's audio-HAL exhaustion under heavy back-to-back test
  batches, the Kotlin-nests-block-comments trap, `GrantPermissionRule` + Compose test-tap
  quirks, etc.). Keep that discipline for whatever gets picked up from this backlog.
- A3 (unit tests) and A5 (lint) are natural *prerequisites* to lean on more heavily once
  the app takes on bigger changes (U5 effects, U6 unlimited scope) — cheap insurance before
  expensive work, not busywork.
