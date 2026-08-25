# BeatWave Android — Engineering-Grade Audio Systems: Scope & Pitch

**Date:** 2026-08-24
**Builds on:** `2026-08-17-beatwave-android-engine-upgrades-backlog.md` (E1–E9 / T1–T6 / D1–D3 /
X1–X5 / C1–C4) and `2026-08-12-beatwave-android-audits-and-upgrades-backlog.md` (U1–U8) — this
document does not re-derive either, it cross-references them throughout.
**Scope note:** this is a *scoping and pitch* document, matching the engine-upgrades backlog's own
format. Nothing here is implemented. Where it changes a prior document's verdict, that's called
out explicitly rather than silently overwritten.

## Why this document exists

Every device-adaptive-layouts phase so far (Phase 0 shared foundation, the in-flight Tab branch)
has been UI work: how BeatWave's screens lay themselves out on different hardware. None of it has
touched the thing that actually makes BeatWave a DAW rather than a UI skin around a sample player —
the native `AudioEngine`/`MixEngine` audio path. The 2026-08-17 engine-upgrades backlog already did
a rigorous pass over that engine and found real gaps (no time-stretch, no per-track effects, no
export loudness control, and more), but scored several of the largest items `needs-product-decision`
specifically because no concrete, low-risk implementation path was known at the time.

This document is a second pass aimed at closing that gap: a fresh line-by-line inventory of what
the native engine actually does today (confirming, correcting, and extending the prior backlog),
plus targeted research into (a) how real competing mobile loop/DAW apps solve the same problems,
(b) real-time Android/Oboe audio-engineering technique, and (c) DSP/mastering technique — run as
four independent research passes so the audio work in this app is judged against how audio
engineering is actually done, not against generic feature-parity guessing.

## Headline finding: two backlog verdicts change

**T2** ("loop content doesn't time-stretch — this blocks BPM control from being a real feature")
was scored `needs-product-decision`, `high risk`, with no concrete implementation path — the
backlog's own words: "genuinely overlaps U5's `MixEngine` risk." That risk profile was accurate
*for a from-scratch phase vocoder*. It's not accurate once WSOLA is on the table: SoundTouch
(LGPL, pure C++, the library Audacity and Ardour both ship) already implements WSOLA-family
time-stretching with independent tempo/pitch/rate control, is documented as real-time-capable at
roughly 100ms algorithmic latency on mobile-class hardware, and is a vendor-and-wire job rather
than DSP research. T2 moves from *"unclear if this is even buildable well"* to *"known-quantity
integration work"* — see **S1** below. This also directly changes **E2/E3**'s picture (the
real-time pitch-shifter's quality gap): the same library gives independent pitch control, so fixing
T2 substantially improves E2/E3 territory close to for free, rather than needing E3's separately
proposed pitch-bucket cache architecture.

**E6** ("concrete render-path architecture for parametric EQ/reverb") was scored
`needs-product-decision` specifically for lacking a concrete architecture. **S2** below proposes
one: per-track scratch accumulation buffers, RBJ biquad EQ, a feed-forward compressor, and an FDN
algorithmic reverb — each individually cheap, well-specified DSP with known CPU cost, not a research
project. E6's real open question (persistent per-track filter state crossing the engine's
"everything derived fresh from the transport counter" mandate 6) still needs the deliberate,
scoped exception the backlog already correctly identified — S2 adopts that exception explicitly
rather than re-litigating it.

## Headline finding: a real, previously undocumented gap

The engine-upgrades backlog covers recording's cap (B1), channel-count fallback (B4), and a
possible mono mode (X3) — but nothing in it, or anywhere else in the codebase, compensates for
**round-trip latency** on the recording path. `AudioEngine::captureRecordingFrames` derives a
captured sample's position as `transportFrame - recordingStartFrame` — a naive same-instant
assumption. In reality, what a user hears out the speaker and what the mic captures back are
offset by the device's real round-trip latency (a figure this project has *already measured* on
real hardware — the Fold 5's measured ~515ms mic input latency this session). Nothing today shifts
a captured take by that offset before committing it into the loop grid, meaning every live
overdub on real hardware is landing measurably off-beat from what the user heard while performing
it, silently. This isn't a corner case — it's the normal path any time someone actually uses the
mic-recording feature the engine already ships. See **S4**.

## Method

Four parallel research passes, each grounded in primary sources rather than general familiarity:
1. Direct re-reading of the native/Kotlin audio source and git/doc history (confirms what's real,
   what's stashed, what's only spec'd).
2. Competitive research across 8 real mobile loop/DAW apps (Koala Sampler, FL Studio Mobile,
   BandLab, Auxy, Cubasis 3, Endlesss, G-Stomper Studio, Caustic 3) — what "engineering-grade"
   concretely means to users of this exact app category.
3. Android/Oboe real-time-audio engineering technique (Google's own Oboe guide, NDK audio-latency
   docs, the official Oboe musical-game codelab, AOSP CDD, and the canonical "two clocks"
   scheduling pattern).
4. DSP/mastering technique (RBJ EQ cookbook, compressor design tutorials, reverb algorithm
   literature, ITU-R BS.1770 loudness, dither theory, WSOLA/phase-vocoder literature).

## Six proposed systems

Each system below states what it is, why it earns its place against BeatWave's own goals (not
generic DAW feature-parity), the concrete technical approach the research actually supports, which
existing backlog items it resolves/sharpens/supersedes, and a size/risk callout using the backlog's
own S/M/L and low/medium/high scale for direct comparability.

---

### S1. Tempo-Elastic Loop Engine
**Size: L. Risk: medium** (down from T2's `high` — see headline finding above).

**What:** Vendor SoundTouch into the native engine and route every loop block's playback through
it instead of the current fixed-ratio 2-point-interpolation resample, giving independent control
over a block's tempo (following project BPM) and pitch (the existing ±semitone control).

**Why it matters here:** T2 is not one backlog item among many — it's the one the backlog itself
flagged as blocking T1 (BPM UI) from being a "satisfying feature... will read as broken, not
polished" without it, and by extension blocks T3/T4/T6 (mid-playback tempo change, metronome,
loop-region playback) from meaning anything real. BeatWave's entire premise is a *tempo-based* loop
sequencer; today's engine plays samples at their own fixed native tempo regardless of the project's
declared BPM. This is the single highest-leverage item on this list because it turns a real,
structural gap in the app's own core premise into a shipped feature, not a nice-to-have.

**Concrete approach:** WSOLA-family stretching (cross-correlation-based segment repositioning) is
the right family for this content specifically — BeatWave's loop blocks are short, mostly
percussive/quasi-periodic sample loops, exactly what WSOLA handles well; the phase vocoder's
advantage (dense polyphonic material) doesn't apply here and its "phasiness"/transient-smearing
weaknesses would actively hurt drum loops. Wire SoundTouch's rate control from
`framesPerGridUnit`'s existing bpm-derived math (already correctly threaded through
`ScoreBuilder`) and its pitch control from the existing semitone field — both inputs already exist
in `Project`/`LoopBlock` today, this is a render-path change, not a data-model change.

**Resolves/sharpens:** Unblocks **T1** (resequence it to land right after S1, not independently —
T2 was the actual blocker, not missing UI). Substantially improves **E2/E3** territory using the
same integration. Directly enables **T3/T4/T6** to mean what they're supposed to mean.

---

### S2. Per-Track Mixer & Effects Rack
**Size: L. Risk: medium-high** (architecture risk concentrated in the mandate-6 exception; the DSP
itself is individually cheap).

**What:** Per-track scratch accumulation buffers in `MixEngine` (mirroring the existing
`mInputScratchBuffer` precedent the backlog already flagged as the right model), each carrying a
small channel-strip: RBJ-cookbook biquad parametric EQ (3-5 bands), a feed-forward compressor,
tanh/ADAA saturation, and a send to one shared FDN algorithmic reverb (2-3 presets: room/plate/
hall) — plus a real per-track fader stage ahead of the sum, which is also **E4**'s fix (gain
staging) as a natural side effect of the same work.

**Why it matters here:** Every competing app surveyed that positions itself as a "real" mobile DAW
— FL Studio Mobile, Cubasis 3, Koala, G-Stomper, Caustic 3 — has some form of per-track channel
strip; it's the single most consistent pattern across the whole competitive set, not a feature one
app happens to have. BeatWave today sums every block straight into one shared buffer with zero
per-track processing — every other DAW-positioned app in this category has already crossed this
line.

**Concrete approach:** RBJ biquad EQ is cheap and well-specified (coefficients recomputed only on
parameter change, smoothed to avoid zipper noise, denormal-flushed on ARM). FDN reverb (Moorer-
style: early-reflection stage + Hadamard-matrix feedback delay network with damping) gets most of
the "sounds produced" payoff competing apps get from reverb at a small fraction of convolution
reverb's cost — convolution is a real, deliberate non-goal here (100x+ more expensive for
equivalent late-reverb density, plus IR-asset APK size/management), correctly deferred rather than
omitted by oversight. Saturation via tanh/atan waveshaping needs either 2-4x oversampling or
antiderivative anti-aliasing (ADAA) to avoid the aliasing a naive per-sample nonlinearity produces
— pick ADAA if the CPU budget is tight, oversampling if implementation simplicity matters more.

**Resolves/sharpens:** This *is* the concrete architecture **E6** was missing. Fixes **E4** (gain
staging) as a byproduct. Adopts, rather than re-opens, **E5**'s already-correct rejection (a
lookahead limiter still isn't worth it — this system doesn't need one).

---

### S3. Sidechain Ducking Engine
**Size: M. Risk: medium.**

**What:** A feed-forward compressor whose envelope detector taps a *different* track's post-fader
buffer (the classic kick-triggers-bass-duck routing), built directly on S2's per-track buffer
architecture.

**Why it matters here:** This is the single feature the competitive research flagged, independent
of app, as *the* thing that makes a loop-based mix sound "produced" rather than stacked — present
in some form in 4 of the 7 apps surveyed that have any dynamics processing at all (FL Studio
Mobile's Auto Ducker, BandLab's Pumper, Cubasis 3's genuine sidechain-input compressor, G-Stomper's
sidechain-capable compressors), and it's the single genre-defining move (EDM/lo-fi "pump") a
loop-sequencer's exact use case is built around. Explicitly **not** the same proposal as E5 (a
lookahead limiter) — this is feed-forward, no added delay line, no conflict with the engine's
low-latency stream mode.

**Concrete approach:** Needs S2's per-track buffers plus one addition S2 alone doesn't require: a
topological render order (sidechain source track rendered before the destination track within the
same callback, not a separate pass) and a routing mechanism to read another track's post-fader
buffer as a control-only input without double-summing it into the output. This is the one place
sidechain routing genuinely adds architecture risk beyond S2 — worth scoping as its own follow-on
commit after S2 lands and is verified working, not bundled into the same change.

**Resolves/sharpens:** New — no existing backlog item covers this; it's adjacent to but distinct
from E5 (correctly still rejected) and E6 (S3's prerequisite).

---

### S4. Latency-Compensated Live Recording
**Size: M. Risk: medium.**

**What:** Measure each device/route's real round-trip latency once per session (and on every route
change — wired headset plugged in, Bluetooth connected) via Android's own CTS/OboeTester
methodology: play a burst-of-white-noise probe signal, record it back through the same full-duplex
path, and find the exact sample offset via cross-correlation. Apply that measured offset as a
frame-count shift when committing captured audio into the loop grid (write-behind PDC), so a
performance that landed on-beat to the user's ear also lands on-beat in the stored recording.

**Why it matters here:** This is the headline gap above — a real correctness issue in a feature the
engine already ships, not a nice-to-have. It's also the most concrete way to spend the ~515ms
Fold-5 mic-latency measurement this project already has sitting in its own device-verification
history: that number is exactly the kind of per-device/per-route offset this system needs to
correct for, on the exact hardware this project already tests against.

**Concrete approach:** Gate the live-monitoring/overdub UX by `FEATURE_AUDIO_PRO`
(CDD-certified ≤20ms round-trip + MMAP/exclusive support) vs. `FEATURE_AUDIO_LOW_LATENCY`-only —
devices without either flag should get a coarser, AudioManager-nominal-latency fallback rather than
the full self-calibration flow, and the UI should be honest about which mode it's in. This pairs
naturally with **C1** (the already-wired, currently-unused `getInputLatencyMillis`/
`getOutputLatencyMillis` calls) as a starting estimate before the first measured calibration
completes.

**Resolves/sharpens:** New — the existing backlog's B1/B4/X3 recording items are all real but don't
cover this; this is a distinct, previously undocumented finding.

---

### S5. Self-Tuning, Resilient Audio I/O
**Size: M. Risk: low-medium** (mostly extends already-scoped, already-low-risk items).

**What:** Land the already-stashed **E7** first (zero new design work — it's a complete, tested-
shaped diff sitting in `git stash@{0}`, blocked purely on on-device verification, and is already a
cited prerequisite of the device-adaptive-layouts spec's own stereo-verification plan). Then layer
three genuine upgrades past E7's single fixed-guess buffer size: Oboe's own `LatencyTuner` (grows
`bufferSizeInFrames` only on a real, measured xRun, rather than guessing once at stream-open);
device native-config discovery via `AudioManager.getProperty` (open every stream at the device's
actual native sample rate/burst size, avoiding AudioFlinger-side resampling that can silently
disqualify MMAP eligibility); and **E8** (an `AudioStreamErrorCallback`, so a headphone unplug or
Bluetooth disconnect triggers a real reopen instead of silently killing playback).

**Why it matters here:** Every other system above assumes a stream that stays open, at a stable
low-latency configuration, across a real user session with real route changes — none of that is
guaranteed today. This is infrastructure the rest of the pitch quietly depends on, and it's also
the lowest-risk, most independently-shippable item here (E7 needs zero new design; the rest are
well-documented Oboe patterns, not open research questions).

**Concrete approach:** After opening, verify the *actually-granted* `PerformanceMode`/`AudioApi`
(Oboe's exclusive-mode request can be silently downgraded) before deciding whether `LatencyTuner`
should even be active — a device that fell back to a shared/non-MMAP path needs its larger,
more-stable default buffer left alone, exactly as the already-stashed E7 diff already reasons.
`AudioStreamErrorCallback`'s two hooks (`onErrorBeforeClose` while the stream is still queryable,
`onErrorAfterClose` once it's safe to reopen) dispatch off the real-time thread, so this is a
threading/lifecycle change, not a real-time-safety one.

**Resolves/sharpens:** Lands **E7** as-is. Directly implements **E8**. Extends **C1**'s "surface
the latency numbers" scope to include xRun count as a live diagnostics signal.

---

### S6. Mastering-Grade Export & Edit-Time Sample Hygiene
**Size: M. Risk: low-medium.**

**What:** Two additions to the export/edit path, bundled because both are "the small detail that
separates a toy loop app from a professional one," at low CPU cost, entirely outside the real-time
callback:
1. **Export loudness normalization.** A two-pass offline export step: measure the bounced mix's
   integrated loudness via ITU-R BS.1770 (K-weighted, 400ms-block, two-stage-gated LUFS), apply a
   static gain to hit a sane target, and true-peak-limit as backstop — plus TPDF dither when
   truncating the internal float mix to 16-bit for `WavWriter`. Vendor a small reference
   implementation (e.g. `libebur128`) rather than re-deriving BS.1770 from the ITU spec.
2. **Zero-crossing-aware trim.** When a user sets a loop block's trim points, snap to the nearest
   true zero crossing (sign change between consecutive samples) within a small window, matching
   crossing direction at both loop edges for continuity, falling back to a short equal-power
   crossfade when no clean crossing exists nearby.

**Why it matters here:** Loudness normalization is the single most industry-standardized,
least-subjective mastering step surveyed — it's what keeps a BeatWave export from sounding
conspicuously quieter or louder than everything else on Spotify/YouTube/TikTok, which is exactly
where an exported loop track ends up. Zero-crossing trim eliminates the most common,
most-amateurish-sounding defect in any loop-based app (the audible tick at a loop point) for
essentially zero engineering or runtime cost — it runs at edit time, not in the audio callback.

**Concrete approach:** Both pieces are safe to build independently and land separately. The
loudness-normalization piece has one real dependency worth flagging: it makes export a genuine
two-pass operation over the full rendered buffer, which sharpens the backlog's already-identified
**X2** (export is one un-chunked, un-cancelable, mutex-holding call) from "a joint prerequisite of
U6" into something this system also wants directly — a two-pass export without any progress
signal is a materially worse UX than today's already-borderline one-pass version.

**Resolves/sharpens:** New (loudness normalization, zero-crossing trim) — not covered by any
existing backlog item. Sharpens **X2**'s urgency independent of U6.

---

## Suggested sequencing

Not a full implementation plan (that's a natural follow-up document once specific systems are
greenlit, matching this project's established spec → plan pattern) — just a dependency-aware
order:

1. **S5's E7 slice first** — it's sitting finished in the stash, already a cited dependency of
   other in-flight work, and blocks nothing else here. Land it alone before anything below.
2. **S1 (Tempo-Elastic Loop Engine)** — highest leverage, and the thing everything tempo-related
   (T1/T3/T4/T6) has actually been waiting on. Independent of S2/S3.
3. **S2 (Mixer & Effects Rack)** — the architecture S3 and half of S6 build on. Land and verify on
   real hardware before starting S3.
4. **S3 (Sidechain Ducking)** — small, separate follow-on commit once S2 is verified.
5. **S4 (Latency-Compensated Recording)** and the rest of **S5** (LatencyTuner, config discovery,
   E8) — both independent of S1/S2/S3, safe to run in parallel with them.
6. **S6 (Export loudness/dither + zero-crossing trim)** — independent of everything except
   benefiting from S2's gain-staging already being consistent by the time it lands.

## What this deliberately excludes

MIDI/virtual-instrument synthesis (Cubasis, Koala, BandLab, Auxy, Caustic 3 all have some form of
it) and AI-driven features (Koala's stem separation, BandLab's ML mastering chain) are both real,
both genuinely "engineering-grade" — and both are excluded here on purpose. Every sound source in
BeatWave today is a recorded sample played back at a pitch-shifted/time-stretched rate; adding a
synthesis engine or MIDI note model is a change to what kind of app BeatWave *is*, not an upgrade to
its existing engine, and doesn't belong folded into the same pitch as the systems above. Automation
lanes (present in BandLab/Auxy/Cubasis/FL Studio Mobile/Caustic 3) are a closer, more natural fit —
but automating *what* only becomes a real question once S2's mixer parameters exist to automate,
making it a legitimate System 7 candidate for a follow-up pass, not this one.

## Sources

Competitive: manual.koalasampler.com; image-line.com FL Studio Mobile manual; blog.bandlab.com /
help.bandlab.com; studio.auxy.co / disco.auxy.co; download.steinberg.net Cubasis 3 manual /
forums.steinberg.net; endlesss.net; planet-h.com G-Stomper manual. Android/Oboe: google.github.io/
oboe (FullGuide, LatencyTuner reference); developer.android.com/ndk/guides/audio/audio-latency;
developer.android.com/codelabs/musicalgame-using-oboe; source.android.com CDD 5.6 and AAudio/MMAP
docs; web.dev/articles/audio-scheduling ("A Tale of Two Clocks," Chris Wilson). DSP: the W3C Audio
EQ Cookbook mirror; Reiss/Giannoulis compressor-design tutorial; Esqueda et al. and Jatin
Chowdhury's ADAA writeups; SoundTouch's own documentation; help.ableton.com / manual.ardour.org on
delay compensation; ITU-R BS.1770-5.
