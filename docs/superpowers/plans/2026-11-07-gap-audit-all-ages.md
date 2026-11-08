# BeatWave Android — Gap Audit: Features, Functions, Activities & Performance, Ages 4 to Adult

**Date:** 2026-11-07
**Companion documents:** `2026-11-07-unbuilt-items.md` (everything planned but not built), `2026-09-27-beatwave-android-holistic-audit-and-roadmap.md` (engine/data/UX audit), `docs/design/2026-11-07-premium-redesign-mockups.html` (the redesign this audit motivates).
**Scope note:** a scoping document. Nothing here is implemented. Facts about the app were checked against the code at `294f50d`+; where a point is judgement (what a 6-year-old needs) or an external rule (child-privacy law, store policy) it is labelled as such.

## 1. How to read this

The 2026-09-27 audit asked *"what is wrong or missing in the engine and the grid?"* This one asks a different question: **who can actually use BeatWave today, and what would each of them need that is not there?** It covers:

- **Features** — capabilities the app does not have.
- **Functions** — things that exist but behave poorly, are hard to find, or dead-end.
- **Activities** — guided and playful things to *do* (lessons, challenges, games). The app has none.
- **Performance** — speed, smoothness, memory, battery, and how well the app holds up on modest hardware.

…for **six audience bands**, from pre-readers to older adults, plus educators and families who set the app up for others.

## 2. What exists today (verified)

| Area | Today |
|---|---|
| Screen | One grid/piano-roll screen: project title (tap for projects), Logs, Export, track pills (8 tracks), Record, Sounds, Scale chip, grid with a ruler, transport bar |
| Sounds | **8** bundled synthetic loops (Basic Kick, Basic Snare, Bass Riff One/Two, Synth Chord, Synth Arp, Vocal Oh, Vocal Ah), 4 categories; user imports and microphone recordings |
| Pitch / key | 25 chromatic rows (−12…+12), 5 scales (Major, Minor, Pentatonic Major/Minor, Chromatic); rows labelled `+7`, not note names; no key/root |
| Time | Fixed 90 BPM, 1/16 grid, 4-minute cap, 8 tracks; **no tempo control, no metronome, no loop region** |
| Editing | Tap place/delete, drag-to-stretch, long-press editor (volume, trim, pitch); **no undo, selection, copy/paste, zoom** |
| Sound design | One volume per note. **No mixer, pan, mute/solo, effects, envelopes** |
| Output | WAV export + system share; background playback with lock-screen controls |
| Look | Default Material theme, **light only**, no custom colour/type/motion system |
| Language | **1** string resource (`app_name`); **35** hardcoded UI text literals; English only |
| Network | **None** — no analytics, ads, accounts or telemetry code (confirmed by search). Good for children's privacy. |
| Permissions | Microphone, notifications, foreground-service (media playback) |
| Age-specific design | **None.** No onboarding, no tutorial, no simple mode, no parental gate, no age setting |

## 3. The audience bands

Age bands are a design device, not a legal classification. Real children vary widely; the point is to name the *needs* so the app can serve each.

| Band | Who | What they need | Served today? |
|---|---|---|---|
| **Explorer** 4–6 | Pre-readers | Tap-to-hear instantly; huge targets; almost no text; 4–6 friendly sounds; a pattern that loops by itself; sound/colour/animation feedback; nothing that can be lost or broken | ❌ No: a 28 dp cell grid, text labels, silent taps |
| **Maker** 7–9 | Early readers | A simple beat maker; guided first song; stickers/rewards that celebrate *making*; short text with read-aloud; templates to remix | ❌ No guided path; reading-heavy |
| **Remixer** 10–13 | Tweens | Real instruments and genres; record own voice; tempo control; key-locked "can't sound wrong" pitch rows; share a clip; challenges | ⚠️ Partly: can build and record, but nothing guides or protects them |
| **Producer** 14–17 | Teens | Full grid, mixer, effects, sample import, MIDI keyboard, export for social, themes/dark mode | ⚠️ Grid + import + export exist; mixer, FX, MIDI, tempo, undo, dark mode missing |
| **Pro** 18+ | Adults, hobbyists, musicians | Tempo map, automation, stems, loudness-safe export, undo, precision editing, keyboard/stylus, DAW interchange | ⚠️ Engine is sound; the editing and mixing surface is thin |
| **Access** all ages | Low vision, motor, hearing, cognitive, older adults | Large text, high contrast, 48 dp targets, TalkBack/Switch Access, reduced motion, haptics, simple language | ⚠️ Cell descriptions now position-aware; text/targets/contrast/motion not addressed |
| **Guardians & educators** | Parents, teachers | A way to set the experience, limit the microphone, share to a family/classroom, see what a child made | ❌ Nothing |

## 4. Gaps — Features

Severity: **H** blocks a whole audience · **M** significantly limits · **L** polish. "Roadmap" links to the 2026-09-27 ids where one exists; **NEW** means the earlier audits did not cover it.

| # | Gap | Who it hurts | Evidence | Sev | Roadmap |
|---|---|---|---|---|---|
| F1 | **No age-appropriate experience layers** (Explorer / Studio / Pro) | Explorer, Maker | One screen, one density, no mode concept | H | NEW |
| F2 | **No onboarding or first-beat path** | Everyone new; essential for Maker/Explorer | No coach marks, no template, empty grid on first launch | H | `PRODUCT-3` (partly) |
| F3 | **No audition: a tap makes no sound** | Explorer, Maker, Remixer (cause→effect is the product) | No one-shot path in engine | H | `PERF-4` |
| F4 | **Sound library of 8 loops**, alphabetical, no kits, tags, genres or previews-by-kind | All; fatal for Explorer/Maker/Remixer | `manifest.json`; Sounds sheet sorts by name | H | `PRODUCT-2`, `PRODUCT-6a/b` |
| F5 | **No tempo control** (fixed 90 BPM) | Remixer, Producer, Pro; also kids matching a song | `DEFAULT_BPM` constant, no UI | H | `TIME-4` (decision pending) |
| F6 | **No dark theme / no design system** | Producer, Access (light sensitivity), bedtime use | Default `MaterialTheme`, light manifest theme | M | `U3` |
| F7 | **No mixer** (volume/pan/mute/solo per track), **no effects** | Producer, Pro | Track has no gain fields | M | `MIX-3`, `MIX-6` |
| F8 | **No undo/redo** | **Everyone, and most of all children** — one mis-tap deletes work | No history anywhere | H | `UX-4` |
| F9 | **No metronome / count-in / loop region** | Remixer+ (recording in time, looping a phrase) | None in engine | M | `TIME-1`, `TIME-3` |
| F10 | **Pitch rows shown as `+7`, not note names; no key/root** | Maker, Remixer, music learners | Row labels; `Scale.kt` has no root | M | `PRODUCT-1` |
| F11 | **No selection / copy-paste / duplicate-bar / zoom** | Producer, Pro (a 16-bar pattern = 128 taps vs 15 pastes) | Absent | M | `UX-5`, `UX-1` |
| F12 | **No MIDI input** | Teens, adults, schools with keyboards | No `android.media.midi` | M | deferred (`IO-8`) — see §9 |
| F13 | **No settings screen** | Guardians, Access, Pro | None exists | H | NEW |
| F14 | **English only; strings hardcoded** | Non-English families; blocks read-aloud | 1 resource string, 35 literals | M | deferred (`GAP1-8`) — **revisit** |
| F15 | **Sharing = WAV via the system sheet only** | Remixer, Producer (shares clips on social) | One share path | M | `EXPORT-5` (AAC), `EXPORT-6` deferred |
| F16 | **No compressed / small export** | Anyone sending by messaging app | WAV only | L | `EXPORT-5` |
| F17 | **No guardian controls** (mic lock, mode lock, time limit) | Guardians of Explorer/Maker | None | H for kids | NEW |
| F18 | **No project duplicate / template / "save a copy"** | Remixers, learners, teachers | Picker offers open/rename/delete | M | NEW |
| F19 | **Tablet/Fold two-pane grid not built** | Large-screen users (the owner's own devices) | Spec deferral; old two-pane deleted with the cutover | M | spec deferral |
| F20 | **No home-screen widget / quick actions** | Casual users | — | L | `U7` |
| F21 | **No cloud backup policy; projects can be lost on a new phone** | Everyone | `allowBackup` with no rules | M | `GAP3-1` |

## 5. Gaps — Functions (exists, but behaves badly)

| # | Gap | Who it hurts | Evidence | Sev | Roadmap |
|---|---|---|---|---|---|
| N1 | **Long-press is the only way to edit a note** — invisible to a child | Explorer, Maker | `GridScreen` long-press → editor; no affordance | H | NEW |
| N2 | **Microphone denied twice = permanent dead end**, no explanation or Settings link | Everyone; kids can't fix it | Snackbar only | H | audit finding |
| N3 | **Recorded take lands up to a 16th early** and replays its head | Remixer+ (anything recorded) | `GridConstants` floors the start | H | `IO-V2` |
| N4 | **Every note ends in a click** (hard note-off) | Everyone's ears; most obvious on short melodic notes | Measured: arp edge steps to −0.70 FS | H | `DSP-1` |
| N5 | **Layered sounds are too loud and distort** — 4 loops peak +7.6 dBFS | Everyone; hurts hearing-sensitive children most | Measured (see roadmap doc) | H | `DSP-2` |
| N6 | **Playhead does not scroll the grid** (it leaves a 360 dp screen after ~11 columns) | Everyone on phones | Static viewport | M | `GAP1-7` |
| N7 | **Seek only by tapping a 28 dp-tall ruler**; no scrub, no "go to start" button on the bar | Explorer/Maker, Access | Ruler is the seek surface | M | `GAP1-7` |
| N8 | **Record/Stop-record are bare text, ~16 dp tall** | Children, motor-impaired, everyone | `labelSmall` text with `clickable` | M | audit finding |
| N9 | **Header strip squeezes on narrow screens / large fonts** (fixed this session for pills; Record/Sounds/Scale now scroll) | Cover-screen and large-font users | Fold 5 closed = 344 dp | M | fixed (partly) |
| N10 | **Scale picker clips on short screens / large fonts** | Access | Dialog column not scrollable | L | audit finding |
| N11 | **Project name ellipsis** was missing (fixed); no project **thumbnail / last-edited / length** in picker | Anyone with >3 projects; kids find theirs by picture | Picker shows name only | M | NEW |
| N12 | **Delete project has no undo / trash** | Children, anyone | Confirm dialog, then gone | M | NEW |
| N13 | **Out-of-scale notes are hidden by omission**, not marked; row labels have no tonic | Maker/Remixer learning scales | `scaleVisibleSemitones` | L | `PRODUCT-1` |
| N14 | **Imported sound names keep `.wav`, sort case-sensitively** | Everyone importing | 3 `sortedBy` sites | L | `PRODUCT-6a` |
| N15 | **Cell content descriptions improved but a TalkBack user still can't place a note > 1 unit long** (drag only) | Access | DnD fallbacks never built | M | `GAP1-5` |

## 6. Gaps — Activities (things to do)

BeatWave today is a **tool**, not an experience. The most effective way to bring in a 6-year-old *and* retain a 16-year-old is an activity layer on top of the same engine. All of these are **NEW** (none appear in any earlier plan) and none is built.

| # | Activity | Bands | What it is | Needs |
|---|---|---|---|---|
| A1 | **First Beat (60 seconds)** | All | Guided: pick a sound, tap four cells, hear it loop, celebrate | `PERF-4` audition, templates, coach marks |
| A2 | **Sound Safari** | Explorer, Maker | Tap animal/instrument tiles to hear and drop them into a loop | Kit library with pictures |
| A3 | **Copy the Beat** | Maker, Remixer | A pattern plays; recreate it; gentle scoring ("you got 6 of 8") | Pattern templates, compare logic |
| A4 | **Remix a Song-Kit** | Remixer, Producer | Start from a 4-bar groove; swap sounds, change tempo, make it yours | Templates, tempo, undo |
| A5 | **Daily Groove** | Maker→Producer | One small prompt a day ("make a beat using only 3 sounds") | Prompt list; *no streak pressure for children* |
| A6 | **Ear Training** | Remixer, Producer | Match pitch, find the odd note, identify a scale | Audition, key/root |
| A7 | **Melody Maker (can't-sound-wrong)** | Maker, Remixer | Key-locked rows, only pentatonic; always sounds good | `PRODUCT-1`, scale snap |
| A8 | **Record Your Voice** | Maker→Adult | Record, pick a pitch, play it as an instrument | Mic UX fix, `IO-5`, guardian gate for kids |
| A9 | **Jam Together** (same device, two players) | Explorer→Remixer | Split-screen or take-turns pads | Large-screen layout, multi-touch audit |
| A10 | **Share a Mix** | Remixer→Pro | Pretty share card with waveform + title; AAC/MP4 clip | `EXPORT-5`, share-card render |
| A11 | **Lessons for teachers** | Educators | Printable 5-lesson pack; classroom project share | Content, `EXPORT-6`-style bundle |
| A12 | **Pro challenges** | Producer, Pro | "Mix to −14 LUFS", "write in 7/8", "sidechain the bass" | Mixer, loudness meter, time signatures |

**Design guardrail (judgement):** rewards should celebrate *creating*, not *returning*. For Explorer/Maker avoid streaks, countdowns, loss framing, and anything that nags.

## 7. Gaps — Performance

Numbers marked ✔ were measured or read directly this session; marked ⚠ come from the 2026-09-27 audit and were not re-measured.

| # | Gap | Evidence | Who it hurts | Sev |
|---|---|---|---|---|
| P1 | **Every native timing number comes from a build with no optimization flag (−O0)** | ✔ `compile_commands.json`; no release build has ever been run under a test | Everything below is unproven on the build users get | H |
| P2 | **1,600 grid cells are composed eagerly (25 rows × 64 columns) and the playhead updates the grid ~20×/s while playing** | ✔ code; recomposition cost ⚠ unmeasured. Caused a real test hang until the transport was stopped | Entry-level phones, long songs, battery | H |
| P3 | **Grid width grows with the song and is not virtualized: a full 4-minute song at 90 BPM is 1,440 columns, so a melodic track would eagerly compose ~36,000 cells (25 rows × 1,440)** | ✔ `gridColumnCount` + arithmetic; render cost ⚠ unmeasured | Same | H |
| P4 | **Mixer cost scaled with all blocks, not the playing ones** (fixed this session: per-callback culling) | ✔ code | Dense songs | fixed |
| P5 | **No xrun/underrun counter anywhere**; stream config granted is never read | ✔ grep | Can't tell a glitching phone from a healthy one | M |
| P6 | **Cold start opens the audio engine and decodes samples inside the ViewModel init** | ✔ code | Slow first frame on old phones | M |
| P7 | **A 4-minute recording buffers ~92 MB in native RAM until Stop; takes finalize through a ~138 MB Java heap spike** | ⚠ | Low-RAM phones (many children's devices) | H |
| P8 | **Export renders into one large buffer while holding the engine lock** | ⚠ | Long songs; UI blocked | M |
| P9 | **Startup/battery never profiled**; foreground service runs for the whole playback | ✔ none done | Battery-conscious users, kids' hand-me-down devices | M |
| P10 | **Layered sounds clip into a `tanh` stage** | ✔ 30.9% of samples over full scale on a 4-loop mix | Audio quality; hearing safety | H |
| P11 | **Oboe library is 4 KB-aligned** — fails to load on 16 KB-page devices | ✔ `llvm-readelf` | Newer phones; Play updates later | H |
| P12 | **No baseline profile, no release minification** | ✔ `isMinifyEnabled = false` | App size, startup | L |
| P13 | **Animations and haptics are minimal** (record dot pulses; nothing else) — not a perf defect, but "feels premium" is mostly 60/120 fps motion done cheaply | ✔ | Perceived quality | M |

**Low-end hardware note (judgement):** children often use hand-me-down phones with 2–3 GB RAM and slow storage. Items P2, P3, P7 and P9 are the ones that decide whether the app is usable there. They should be measured on a deliberately weak device (a throttled emulator is a stand-in, not a substitute).

## 8. Child safety & compliance checklist

> **Not legal advice, and not verified against current policy text.** Children's-privacy law (e.g. COPPA in the US, GDPR-K in the EU, the UK Children's Code) and store family policies change; each line below is a *question to confirm* before the app is described as child-appropriate or listed for children.

| Question | Where BeatWave stands (facts) | Action |
|---|---|---|
| Does the app collect personal data from children? | No network code, analytics, ads or accounts (✔ searched). All data local. | Keep it that way; document it |
| Microphone use | Recording needs `RECORD_AUDIO`; the prompt is the system dialog | Add a guardian gate and plain-language explanation for Explorer/Maker; never record in the background (✔ it does not) |
| Imported audio from the web / shared files | Share intent accepts audio `content://` URIs | For child profiles, hide import or require the guardian gate |
| Sharing | WAV via system share sheet | For child profiles, require a guardian gate before any share |
| Cloud backup of recordings | Auto Backup on, no rules; recordings can leave the device | Decide and configure (`GAP3-1`) before any child-oriented release |
| Purchases, ads, links out | None | Keep none |
| Crash logs | Stay on device; user can share manually | Fine |
| Store listing / content rating / target-audience answers | Not prepared | Required before any store release |
| Accessibility | Partly | See §3 Access row |

## 9. Recommended product shape

**One engine, one project format, three experience layers.** A project made in Explorer opens in Studio and Pro; nothing is ever locked away from a child's *own* work, only simplified. This also honours the earlier product direction that users should be able to move between modes "near seamlessly and naturally".

| Layer | Ages (guide) | The screen is… | Hidden |
|---|---|---|---|
| **Explorer** | 4–8 | Pads and a big looping pattern strip; picture tiles, big Play, "Again!", no text needed (read-aloud on tap); 4 beats × 4 sounds | Everything else |
| **Studio** | 9–15 | Today's grid, made friendlier: note names, scale lock, sound kits, tempo, undo, templates, challenges | Mixer, FX, automation |
| **Pro** | 14+ | Full mixer, effects, loop region, tempo map, stems, loudness meter, MIDI, keyboard shortcuts | — |

A **guardian PIN** (not a maths puzzle that a bright 8-year-old solves) sets the layer, locks it, and controls microphone / import / share. Layers are *presentation*; they are not an age check and the app should not ask a child's age.

**Cross-layer foundations that unlock everything above (build these first):**
1. **Design system + tokens** (colour roles, type scale, spacing, elevation, motion) and **dark theme** — everything else is built on it.
2. **Audition on tap** (`PERF-4`) — cause-and-effect is the product for the youngest users.
3. **Undo/redo** (`UX-4`) — safety net for every age.
4. **Strings externalized** — needed for any second language *and* for read-aloud.
5. **Settings + guardian gate.**
6. **Sound library v2** — kits with pictures, tags and genres.

## 10. Priority and sequencing

| Order | Theme | Items | Why now |
|---|---|---|---|
| **0** | Finish what is half-done | Fold test failures, two open defects, push; measure on a release build (`PERF-1a`, `PERF-1b`) | A premium look on an unmeasured engine is risk |
| **1** | Trust the audio | `DSP-1` declick, `DSP-2` headroom, `IO-V2` recording placement | Every age hears these |
| **2** | Foundations | Design tokens + dark theme, string externalization, settings, undo, audition | Unlocks 3–5 |
| **3** | Make it understandable | Onboarding / First Beat, sound kits v1, tempo, note names + scale lock | Where new users succeed or leave |
| **4** | Layers | Explorer layer, guardian gate, Studio polish, large-screen two-pane | The all-ages promise |
| **5** | Depth | Mixer, loop region, metronome, selection/copy-paste, zoom | Producer/Pro |
| **6** | Activities & sharing | A1–A8, share card, AAC | Retention and delight |
| **7** | Pro extras | MIDI, stems, FX, tempo map, time signatures | Long tail |

## 11. Decisions needed

1. **Is a children's release a goal, or "kid-friendly mode inside a general app"?** The first brings store-policy and legal weight (§8); the second is lighter but must still protect the microphone, import and share.
2. **Should BPM be user-editable?** It gates `TIME-4`, Copy the Beat, and Remix a Song-Kit.
3. **Will you license or commission real sound content** (kits with pictures)? The 8 synthetic loops cannot carry Explorer/Maker. Sources, licences and provenance need a decision.
4. **Second language?** If yes, string externalization moves from "deferred" to foundation.
5. **MIDI input and school/classroom use** — in or out? (The 2026-09-27 roadmap deferred MIDI.)
6. **Is the Explorer layer a separate launcher entry (its own icon) or a mode inside the app?**
7. **Store plan.** Whether a store release is planned decides how much of §8 is a gate.

## 12. What this audit did not do

- No user research, no testing with children or educators. Needs of age bands are informed design judgement.
- Child-privacy and store-policy statements are *questions*, not conclusions (§8).
- Performance items marked ⚠ were not re-measured.
- The redesign in the companion mockups is a proposal; no UI code was changed.
