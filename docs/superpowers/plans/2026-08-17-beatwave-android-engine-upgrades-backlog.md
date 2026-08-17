# BeatWave Android — Engine Upgrades Backlog

**Date:** 2026-08-17
**Companion to:** `2026-08-12-beatwave-android-audits-and-upgrades-backlog.md` (the product-level
audits/upgrades backlog — A1–A8, U1–U8)

## Why this document exists

The product-level backlog's U4 (compressed export), U5 (parametric effects), and U6
(unlimited tracks/song length) each touch the native engine, but were scoped at a
product-feature level — a paragraph each, written without reading the render math. This
document goes one level deeper: a systematic pass over every engine component (native
`AudioEngine`/`MixEngine`, the decode/cache pipeline, the export/recording pipeline, and the
Kotlin coordination layer) to find engine-internal upgrade opportunities a feature-list
backlog wouldn't surface on its own — algorithmic quality gaps, resource-management gaps,
missing controls, and the real architectural forks behind U4/U5/U6 specifically.

Produced by a 5-lens research pass (one lens per engine component, each independently reading
the real source — not guessing), then synthesized here. Where a lens's own conclusion was
**"not worth doing,"** that's kept as a real, explicit finding, not dropped — a confident "no"
backed by reasoning is a useful scoping result too.

**Scope note:** this is a *scoping* document, matching the existing backlog's own format. None
of these have been implemented yet — this is the menu, not a commitment.

---

## Real bugs found along the way

These aren't upgrades — they're genuine defects the research surfaced as a side effect of
reading the real code closely. All are small, low-risk, and worth fixing independent of any
larger upgrade decision.

### B1. Recording cap silently undershoots the app's own max song length
**Where:** `AudioEngine.h` (`kMaxRecordingSeconds = 180`) vs. `GridConstants.kt`
(`MAX_SONG_LENGTH_SECONDS = 240`).
**What:** `kMaxRecordingSeconds`'s own doc comment claims it "matches the design spec's own
max song length" — it doesn't. The native recording buffer is sized for 180s (3 min) while
the actual app-level song-length cap is 240s (4 min). A user recording across most of a
full-length arrangement gets auto-stopped a full minute (25%) early, with no messaging that
distinguishes this from the song-length cap.
**Fix shape:** Bump `kMaxRecordingSeconds` to 240, and ideally stop hardcoding it as an
independent native literal — pass the cap in from Kotlin at `startRecording()` time so it
can't silently re-drift the next time the song-length cap changes (see U6 below).
**Size:** S. **Risk:** low (one constant change + a larger transient buffer allocation, ~88MB
vs ~66MB at 48kHz stereo float32 for 240s — worth a quick memory sanity check on the project's
real low-end test device).

### B2. WavDecoder never checks the WAV format tag — IEEE-float WAV would silently decode as noise
**Where:** `WavDecoder.cpp`'s fmt-chunk parsing (reads `numChannels`/`sampleRateHz`/
`bitsPerSample`, never reads `audioFormat`).
**What:** A 32-bit-float WAV (`audioFormat=3`, bitsPerSample=32 — a normal Audacity/Reaper/
Logic export option) would be parsed on the exact same path as 32-bit *integer* PCM, silently
reinterpreting IEEE-754 float bit patterns as huge/near-random int32 values — a buffer of
near-silent noise, with no error and no signal anything went wrong. Currently masked because
`AudioImporter` always writes canonical 16-bit integer PCM, so no *imported* sample can trigger
it — but any bundled loop-pack asset ever authored/re-exported as float WAV would silently
break.
**Fix shape:** Read `audioFormat` during parsing; for format 3 (or EXTENSIBLE-float), read the
data chunk as raw IEEE-754 floats directly instead of routing through the integer
normalization path; for any other format, fail `decodeBytesToPcm` explicitly rather than
silently misinterpreting bytes.
**Size:** S. **Risk:** low (narrow, deterministic, easy to cover with a hand-built float-WAV
test fixture; zero interaction with caching/resampling/real-time path).

### B3. `mRetiredScores` retains every committed score forever
**Where:** `AudioEngine.h`'s `mRetiredScores` (every `commitProject()` — i.e. every arrangement
edit — permanently retains the new `PlaybackScore`, by original design, "for zero
locking/refcounting overhead").
**What:** The original trade-off reasoning holds up reasonably well (retained scores are small
POD structs, ~100–150 bytes/block, not duplicated audio — `ResolvedLoopBlock::sample` is a
`shared_ptr`), but it's genuinely unbounded, and session lengths trend upward now that U1
(multi-project) and the Media3 background service keep the process alive longer.
**Fix shape:** Bound `mRetiredScores` to a small ring (e.g. keep the newest 8, drop older).
Provably safe: `onAudioReady` loads `mScore` once per callback and only reads that snapshot
for the callback's duration, so any score more than ~one callback period older than the
current one can never be read again.
**Size:** S. **Risk:** low (eviction happens off the audio thread, under the existing mutex).

### B4. Recording's input stream hardcodes Stereo with no fallback
**Where:** `AudioEngine::startRecording()`'s input `AudioStreamBuilder`
(`setChannelCount(Stereo)`, no retry on failure).
**What:** The class doc comment claims the input stream matches the output "as closely as the
device allows," but the code makes one fixed Stereo request with no fallback. Many phones'
real input path is fundamentally mono (single mic element) — if exclusive low-latency Stereo
input isn't supported, `openStream` fails and `startRecording()` just returns `false`, with no
distinction from "permission denied" or any other failure. The downstream capture math
(`captureRecordingFrames`) already handles mono input correctly (broadcasts to both output
channels) — the gap is narrowly in the stream-open request.
**Fix shape:** On a Stereo open/start failure, retry once with `ChannelCount::Mono` before
giving up.
**Size:** S. **Risk:** low-medium (small code change; real cost is on-device verification on a
mono-only-capable input device, per this project's own verification discipline).

---

## 1. Mixing / DSP quality — `MixEngine.cpp`, `AudioEngine.cpp` stream setup

| # | Title | Size | Risk | Verdict |
|---|---|---|---|---|
| E1 | Upgrade the load-time asset resampler (`WavDecoder::resampleLinear`) to a bandlimited resampler | M | low | **worth-doing** |
| E2 | Cubic Hermite interpolation for the real-time pitch-shift resampler | S | low | **worth-doing** |
| E3 | True anti-aliased pitch shifting (fixes pitch-up aliasing properly) | L | medium | needs-product-decision |
| E4 | Fix gain-staging/headroom instead of replacing the tanh soft-clip | S | low | **worth-doing** |
| E5 | Multi-band/lookahead limiter | — | — | **not worth it** |
| E6 | Concrete render-path architecture for parametric EQ/reverb (technical depth on U5) | L | medium-high | needs-product-decision |
| E7 | Tune the output stream's runtime buffer size post-open | S | low | **worth-doing** |
| E8 | Add an `AudioStreamErrorCallback` (stream disconnect recovery) | M | medium | **worth-doing** |
| E9 | Battery-saving `PerformanceMode` alternative | — | — | **not worth it** |

**E1 — Upgrade the load-time resampler.** `WavDecoder::resampleLinear` (called once per unique
sample, off the audio thread, whenever a sample's native rate differs from the engine's
negotiated output rate) shares the exact same audible-quality limitation as the real-time
pitch-shifter — but with *none* of its real-time constraints. This is the single cheapest,
lowest-risk real quality win in the whole engine: swap the inner loop for a proper
band-limited (windowed-sinc) resampler. It improves every sample's baseline fidelity, not just
pitch-shifted ones, and has zero interaction with the real-time budget.

**E2/E3 — Pitch-shift quality.** The real-time pitch-shifter (`MixEngine::renderScore`) is
2-point linear interpolation. At this app's own ±12 semitone range, pitch-down sounds
dulled/muffled (linear's poor passband flatness); pitch-up produces genuine audible aliasing
(linear interpolation is a very weak anti-alias filter, and pitching up is effectively
decimation). **E2** (cubic Hermite, 4-tap) is a cheap, real quality improvement for the
dulling problem — but honestly does *not* fix pitch-up aliasing on its own. **E3** (true
band-limited/anti-aliased shifting) is the actual fix, and the research identified a concrete
implementation path that fits this codebase's existing idiom better than a real-time sinc
kernel would: extend `SampleBank`'s cache key to `(assetPath, quantized-pitch-bucket)` and
pre-render bandlimited variants once at commit time (off the audio thread), rather than doing
expensive interpolation in the real-time hot loop. Real architecture change (cache + build
layer), L-sized, genuinely needs a product decision on how much pitch-shift fidelity matters.

**E4 vs E5 — Dynamics.** The `tanh()` soft-clip is fine as a bounded, real-time-safe safety
net — but at this app's own realistic ceiling (4–8 simultaneously active full-volume tracks
summing to 4.0–8.0), `tanh` is within a fraction of a percent of ±1: it sounds like ordinary
hard clipping despite the "soft" framing, because there's no gain staging or headroom
anywhere. **E4** (fix the actual problem — default headroom trim scaled by active-track-count,
or RMS-based normalization at import time) is the real, cheap fix. A proper multi-band or
lookahead limiter (**E5**) was investigated and explicitly rejected: lookahead requires a delay
line, which directly fights the app's own deliberately-chosen low-latency stream mode, for a
transparency gain most users layering pre-mixed loops won't notice.

**E6 — EQ/reverb architecture (deepens U5).** Today `renderScore` accumulates every block
directly into one shared output buffer with no per-track intermediate buffer to insert a
track-level effect after. Real effects need: (1) fixed-size per-track scratch accumulation
buffers (mirroring the existing `mInputScratchBuffer` precedent), and (2) — the real
architectural tension — EQ/reverb inherently need **persistent per-track filter/delay state
across callbacks**, which directly conflicts with mandate 6's "everything derived fresh from
the transport counter, nothing stored across callbacks" philosophy. This needs a deliberately
scoped exception (filter state owned solely by the audio thread, never touched by
`commitProject`) and an explicit decision about what happens to a track's in-flight reverb
tail when a new score deletes/reassigns that track slot — a case the current stateless model
has never had to handle. Whoever picks up U5 should design against this first.

**E7 — Buffer-size tuning.** `AudioEngine::start()` never calls
`stream->setBufferSizeInFrames()` after opening — a standard, well-documented Oboe
latency-tuning step. Cheap, low-risk, real (if modest) latency payoff for a loop sequencer
where beat-accurate timing matters. Bundle in logging `getPerformanceMode()`/`getAudioApi()`
alongside the existing sample-rate log line — Oboe can silently fall back to a non-MMAP path
even when LowLatency/Exclusive is requested, and there's currently no way to tell.

**E8 — Stream disconnect recovery.** No `AudioStreamErrorCallback` is registered anywhere
(output or input). Real-world events — unplugging headphones, a Bluetooth disconnect, another
app stealing an exclusive-mode stream — currently just silently kill playback with no
recovery. Oboe dispatches the recovery hook off the real-time thread, so this isn't a
real-time-safety problem, just needs careful re-threading of stream ownership and real
on-device testing across a few disconnect scenarios.

---

## 2. Transport & tempo — `AudioEngine` transport, `ScoreBuilder`, `GridConstants.kt`

| # | Title | Size | Risk | Verdict |
|---|---|---|---|---|
| T1 | Add BPM UI control + `setBpm()` (the wiring itself) | S | low* | needs-product-decision |
| T2 | Loop content doesn't time-stretch — BPM control alone would sound broken | L | high | needs-product-decision |
| T3 | Mid-playback tempo change needs transport handling | S | low | **worth-doing** (ship "stop first") |
| T4 | Metronome/click track | M | low | **worth-doing** |
| T5 | Time signature (currently hardcoded 4/4) | S | low | **worth-doing** |
| T6 | Loop-region/section playback | M | low | **worth-doing** |

**Correction to the working assumption:** tempo is *not* architecturally fixed the way it was
assumed to be. `Project.bpm` is a real, already-serialized field; every `GridConstants`
formula already takes `bpm` as a parameter; `ScoreBuilder::begin(bpm, sampleRateHz)`
recomputes fresh from whatever `bpm` it's given, every call; and `ProjectPlaybackController`
already does a full rebuild+recommit from `project.bpm` on *every single arrangement edit*.
"90 BPM, unchangeable" is purely a missing UI control (**T1**) — genuinely cheap wiring.

**But T2 is the finding that actually matters here.** `framesPerGridUnit` (derived from bpm)
only controls where a block's audible *window* starts/ends on the timeline. It has **no
effect** on `loopContentLengthFrames` — how long one repeat of the sample's own audio actually
takes to play, which is fixed by the sample's real recorded duration and `pitchRatio` alone.
Raising the project BPM shrinks each block's window while the sample's own repeat cycle stays
exactly as long as before: the loop gets cut off mid-cycle (an audible click) and no longer
lines up with the new beat grid, because the sample never actually sped up. **This engine has
no time-stretch mechanism today.** Shipping a bare BPM field would not make the song play
faster/slower the way it does in GarageBand/FL Studio — it would just misalign every existing
loop. Before any BPM-control work is scoped as a real feature, this needs an explicit product
decision: build true time-stretching (L, genuinely overlaps U5's MixEngine risk), or accept a
lossy auto-pitch-shift-to-match approach, or scope BPM control as "new projects only, no
retroactive tempo change."

**T3** is cheap once T1/T2 are resolved: recommitting a bpm-changed score while actively
playing is *not* like an ordinary same-bpm edit (which is already safe today, live, on every
arrangement edit) — every block's `blockStartFrame` gets recomputed to a new value, and the
raw transport counter has no idea a rescale happened, producing an audible discontinuity on
the next callback. Recommended pragmatic fix: only allow BPM changes while transport is
stopped (mirrors `stopTransport()`'s existing reset-to-0 semantics) — zero new native surface
area.

**T4/T5/T6 are all genuinely cheaper than the tempo work above**, and none of them touch the
tricky parts:
- **Metronome (T4):** the block-based mixing model already models exactly what a click needs —
  a synthetic track with one auto-placed block per beat, reusing the existing
  decode/cache/mix path with zero new `MixEngine` code.
- **Time signature (T5):** "beats per bar" turns out to be a UI-only hardcoded literal (`4`,
  in exactly two places in `ArrangementScreen.kt`) — the native engine has no concept of "bar"
  at all. This was never an engine problem.
- **Loop-region playback (T6):** no existing native concept, but the existing atomic
  `seekToFrame`/`getCurrentFrame` transport primitives make a naive ~50ms-accuracy Kotlin-only
  version nearly free; a sample-accurate version needs a small (M-sized), low-risk native
  addition (loop-boundary check inside the existing transport-advance step).

---

## 3. Decode & sample memory pipeline — `SampleBank`, `WavDecoder`

| # | Title | Size | Risk | Verdict |
|---|---|---|---|---|
| D1 | SampleBank cache eviction (confirmed dead code — `clear()` has zero call sites) | S–M | low-medium | **worth-doing** |
| D2 | Native compressed-format (MP3/AAC/OGG) decode | — | — | **not worth it** |
| D3 | Fully-in-memory `SampleBuffer` → streaming | L | high | needs-product-decision (contingent) |

**D1 — the strongest finding in this whole document.** `SampleBank::clear()` is defined but
has **zero call sites anywhere in the codebase outside its own definition** — this isn't
speculative, it's dead-code-confirmed by two independent research lenses that both found it
separately. The cache genuinely grows for the process's whole session lifetime (correctly
released on real app teardown, so not a cross-restart leak, but unbounded within one session).
This directly compounds with two already-shipped features: U1's multi-project switching
re-decodes+permanently-caches every sample referenced by whichever project is opened, on top
of whatever previous projects already cached; and A1's import-size hardening caps a *single*
import at 96MiB decoded PCM but doesn't cap how many distinct large imports a session
accumulates. Two independent fix shapes were proposed (pick one, they solve different
problems): an LRU + memory-budget eviction policy in `SampleBank` itself (M, handles the
general case), or simply wiring the already-existing `clear()` into project-switch boundaries
(S, simpler but coarser — forces re-decode of samples shared between projects, e.g. common
bundled loops, on every switch).

**D2 — investigated and rejected.** Should the native engine decode compressed formats
directly instead of always normalizing to WAV first via Kotlin's `MediaCodec`? No: the current
split is the *right* engineering call, not just the simpler one. A1's DoS-hardening work
already built real, tested defenses specifically around the Java-side `MediaCodec` decode path
(size-hint checks, running-byte-count ceiling, documented best-effort timeout handling around
`Thread.interrupt()` not being honored by native `MediaCodec` calls). Re-implementing
compressed decode natively would mean either duplicating all of that hardening in C++ (the NDK
`AMediaCodec` API has the *same* underlying non-interruptibility problem) or leaving native
decode unhardened, reopening a closed DoS vector — for a marginal one-time-transcode-cost
saving.

**D3 — correctly scoped as contingent, not proposed outright.** Every `SampleBuffer` holds its
entire decoded PCM resident in memory. This is fine — even the right choice — for the app's
actual use case (short loops, bounded by existing caps): it's what makes `MixEngine`'s
deliberately stateless, random-access-safe design possible with the current code's simplicity.
This only becomes a real, eventually-necessary upgrade if U6 ("unlimited song length") is ever
scoped to *also* mean unlimited individual **sample** length (e.g. importing a full song as a
single backing-track loop block) — which is a different, larger product decision than U6 as
currently written (arrangement length, not source-clip length). Worth having on record as the
concrete technical reason "just extend U6 to samples too" is not a small follow-on.

---

## 4. Export & recording pipeline — `WavWriter`, `exportToFile`, recording capture

| # | Title | Size | Risk | Verdict |
|---|---|---|---|---|
| X1 | Compressed (AAC) export — real architecture, deepens U4 | L | medium-high | needs-product-decision |
| X2 | Export is one un-chunked, un-cancelable, mutex-holding call | M | low-medium | needs-product-decision |
| X3 | User-facing mono recording mode | S | low | needs-product-decision |
| X4 | User-selectable recording sample rate | — | — | **not worth it** |
| X5 | 24/32-bit export | — | — | **not worth it** (for now) |

**X1 — deepens U4 with a real architectural fork.** U4's own text says an AAC encoder can be
"wired onto the same offline-rendered PCM buffer... the render path doesn't change." Tracing
the actual code shows this undersells the work: the rendered float buffer is a **native-only
local variable** inside the single JNI export function — it never crosses the JNI boundary
back to Kotlin today, and no NDK media library is linked in `CMakeLists.txt` at all. Two real
options with very different cost profiles: (A) marshal the buffer across JNI and encode with
Android's existing Java `MediaCodec` (straightforward, but real JVM-heap marshaling cost on
top of an already-~92MB-for-4-minutes native buffer — gets worse the moment U6 removes the
length cap), or (B) encode entirely native-side via NDK `AMediaCodec`/`AMediaMuxer` (avoids
marshaling, but is a genuinely new dependency and a different API family, not just a second
use of the existing decode-only `MediaCodec` usage). Either way: this is the app's **first
audio *encode* path**, not a second use of an existing one — expect new failure modes with no
current analog (per-device encoder sample-rate/profile rejection, async drain-loop bugs).

**X2 — a load-bearing assumption that breaks under U6.** The entire offline render happens in
one synchronous JNI call for the whole song at once, with no progress callback and no
cancellation hook — and `exportToFile` holds the *same* `engineMutex` that
`initialize`/`loadProject`/`startRecording`/`shutdown` all use, for the full render+write
duration, even though the offline export engine is otherwise independent of the live one. Not
a pressing problem today (offline mixing has no I/O wait per frame, so even a dense export is
probably fast enough that an opaque spinner is tolerable) — but it's a joint prerequisite of
U6, not something to defer indefinitely after it: an unbounded song length makes this an
unbounded, un-cancelable, whole-engine-blocking operation.

**X3/X4/X5 — mixed verdicts, explicitly reasoned.** Mono recording (**X3**) is real but
narrow — the most realistic recording source (a phone mic capturing one voice/instrument) is
already effectively mono content duplicated across both channels today, so this mostly trades
UI surface for a memory saving (roughly halves the recording buffer), not new creative
capability; worth building only alongside a real reason (e.g. a longer recording cap at equal
memory, once B1 is fixed). A user-selectable recording rate (**X4**) was investigated and
rejected outright: recording intentionally shares the live output's exact sample rate so its
position stays a simple 1:1 index into the one shared transport counter — decoupling that
would mean either real-time resampling inside the single most latency-sensitive code path in
the app, or a much larger architectural change, for no real product pull. 24/32-bit export
(**X5**) is undermotivated *right now*: the final mix is unconditionally `tanh`-soft-clipped
before `WavWriter` ever sees it (same function for both live playback and export), so a wider
bit depth wouldn't recover any headroom the limiter already removed — only the int16
quantization noise floor itself (~-90dB, inaudible on an already-limited mobile mix). If ever
revisited, 32-bit float (not 24-bit) is the right target — `WavDecoder` already understands
that format on decode, so no new byte-packing code would be needed.

---

## 5. Coordination layer & extensibility — `PlaybackEngine.kt` and cross-cutting concerns

| # | Title | Size | Risk | Verdict |
|---|---|---|---|---|
| C1 | Surface the already-computed Oboe latency numbers in a diagnostics view | S | low | **worth-doing** |
| C2 | Unlimited tracks: the real scaling risk is `renderScore`'s linear scan, not track count | M | medium | needs-product-decision |
| C3 | Undo (U8) mid-recording needs an explicit decision | S | medium | needs-product-decision |
| C4 | `pause()` never suspends the underlying stream — idle-suspend for background pause | M | medium | needs-product-decision |

**C1 — nearly free.** `AudioEngine::getInputLatencyMillis()`/`getOutputLatencyMillis()` are
fully wired end-to-end from a real Oboe latency query down to a Kotlin-callable function — and
have **zero callers anywhere above `PlaybackEngine`**. The plumbing already exists and works;
nothing has ever asked for it. Real troubleshooting value for a user reporting audio glitches,
and complements (rather than duplicates) the existing A7 audit item, which only proposes
one-off `simpleperf`/`systrace` dev-session captures — this would be an always-available,
in-production number.

**C2 — sharpens the real risk behind U6 before it ships.** Verified against the real native
code: there's no fixed-size array or compiled-in track-count assumption anywhere — tracks and
blocks are genuinely unbounded `std::vector`s, so U6's own "mostly UI, already threaded
through cleanly" claim is correct as far as it goes. But `renderScore`'s inner loop pays a
bounds-check for *every* block on *every* track on *every* output frame, with no early exit
for blocks nowhere near the current playhead — so real per-callback cost scales with **total
scheduled block count**, not with how many blocks are concurrently audible. At today's v1
caps, this is under 3% of the callback budget (invisible in testing). A plausible power-user
arrangement at, say, 64 tracks × 10 minutes pushes the *same bounds-check-only* cost to
roughly half the callback budget — before the actual mix work, before OS/thermal pressure, on
the project's own low/mid-range test device. Recommendation: don't ship U6 as literally
"unlimited" without either (a) a per-track pre-filter so blocks can be skipped in
better-than-linear time (requires blocks sorted by `blockStartFrame`), or (b) a soft cap on
*total scheduled blocks* as the actual product-facing limit, since that's what the cost
tracks — and make sure A7's profiling scope explicitly includes a stress config near the
estimated ceiling (it currently only mentions profiling today's already-capped 8-track
config, which would never catch this).

**C3 — the engine is already safe; the product behavior isn't decided.** Reloading a
project mid-playback (what undo would do) needs **no special engine handling at all** —
this isn't hypothetical, every ordinary arrangement edit already does exactly this today, live,
with zero special-casing, and it works, because `commitProject` only atomically swaps a
pointer and every block's on/off state is derived fresh every frame. Recording state
(`mRecording`/`mRecordingBuffer`) is also completely independent of score state at the engine
level, so a mid-recording reload can't corrupt or interrupt an active capture either. What's
missing is a **product decision**: `ArrangementViewModel` already has an established,
three-times-repeated pattern of explicitly gating other actions while a recording is active
(refusing project switches, no-oping pause, blocking seeks) — undo has no equivalent decision
yet, and shipping it without one means "what happens if you hit Undo mid-recording" is
accidentally undefined rather than deliberately chosen.

**C4 — real but low-urgency without usage data.** `pause()` only flips a flag; the underlying
Oboe stream stays fully open indefinitely, waking every ~20ms to write silence, for as long as
the app sits paused-in-background (a state `BeatWavePlaybackService` is explicitly designed to
support indefinitely). This is a real, currently-absent optimization — but its actual battery
impact is unknown without knowing how often real users leave the app paused-and-backgrounded
for extended periods, versus the more likely "actively playing or fully stopped" pattern for a
loop sequencer. Cheapest next step: make sure A7's planned battery-drain profiling explicitly
includes "paused-in-background" as a measured condition (it currently only compares active
background playback vs. fully idle) before deciding whether the fuller suspend/resume
mechanism is worth building.

---

## Cross-cutting sequencing notes

- **B1 (recording cap drift) should be fixed before U6 touches the song-length cap again** —
  otherwise the same drift bug just reappears at a new number.
- **T2 (loop content doesn't time-stretch) blocks T1 (BPM UI) from being a satisfying feature
  on its own** — surfacing BPM control without addressing this will read as broken, not
  polished.
- **C2 (renderScore scaling) and X2 (export chunking/cancellation) are both real prerequisites
  of U6**, not independent follow-ups — scoping U6 without them risks shipping "unlimited"
  that glitches or hangs under genuine power-user load.
- **D1 (SampleBank eviction) and U1 (multi-project, already shipped) interact directly** — the
  eviction gap was always latent, but U1 is what makes it a routinely-exercised path rather
  than a hypothetical one.
- **E3 (true anti-aliased pitch shift) and E6 (EQ/reverb architecture) are the two largest
  structural investments found** — both L-sized, both touch `MixEngine`'s real-time render
  path, both flagged `needs-product-decision`. If both are ever pursued, worth designing them
  together rather than sequentially, since E6's per-track scratch-buffer addition and E3's
  pitch-bucket cache extension both touch the same commit-time build path.
