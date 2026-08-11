#!/usr/bin/env python3
"""
generate_placeholder_loops.py

Synthesizes a small placeholder loop pack for BeatWave's bundled loop
library (v1 scope: one genre, ~2 loops per category). Every sound here is
procedurally generated -- clearly not real licensed content -- which is the
intended v1 approach per the design spec.

Uses ONLY the Python standard library (wave, struct, math, array, json,
random) so it runs offline with no pip installs.

Output:
  - app/src/main/assets/loops/*.wav   (8 mono 16-bit PCM WAV files, 44100 Hz)
  - app/src/main/assets/loops/manifest.json

Run from anywhere; paths are resolved relative to this script's location.
"""

import json
import math
import random
import struct
import wave
from array import array
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SAMPLE_RATE = 44100
BPM = 90
BEAT_SAMPLES = round(SAMPLE_RATE * 60.0 / BPM)  # 29400 samples/beat at 90bpm
BAR_SAMPLES = BEAT_SAMPLES * 4                  # 117600 samples/bar (~2666.67ms)

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
LOOPS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "loops"

random.seed(90210)  # deterministic output across runs


# ---------------------------------------------------------------------------
# Small synthesis helpers (pure stdlib, sample-by-sample)
# ---------------------------------------------------------------------------

def midi_to_freq(midi_note: float) -> float:
    return 440.0 * (2.0 ** ((midi_note - 69.0) / 12.0))


def silence(n: int) -> list:
    return [0.0] * n


def apply_fade(samples: list, fade_in: int = 0, fade_out: int = 0) -> None:
    """In-place linear fade in/out to guarantee click-free, seamless loop
    boundaries (start and end approach zero smoothly)."""
    n = len(samples)
    fade_in = min(fade_in, n)
    fade_out = min(fade_out, n)
    for i in range(fade_in):
        samples[i] *= i / fade_in if fade_in else 1.0
    for i in range(fade_out):
        idx = n - 1 - i
        samples[idx] *= i / fade_out if fade_out else 1.0


def one_pole_lowpass(samples: list, cutoff_hz: float, sr: int = SAMPLE_RATE) -> list:
    rc = 1.0 / (2.0 * math.pi * cutoff_hz)
    dt = 1.0 / sr
    alpha = dt / (rc + dt)
    out = [0.0] * len(samples)
    prev = 0.0
    for i, x in enumerate(samples):
        prev = prev + alpha * (x - prev)
        out[i] = prev
    return out


def one_pole_highpass(samples: list, cutoff_hz: float, sr: int = SAMPLE_RATE) -> list:
    """Implemented as input minus a lowpassed copy (removes sub content)."""
    low = one_pole_lowpass(samples, cutoff_hz, sr)
    return [x - l for x, l in zip(samples, low)]


def mix(*layers: list) -> list:
    n = max(len(l) for l in layers)
    out = [0.0] * n
    for layer in layers:
        for i, v in enumerate(layer):
            out[i] += v
    return out


def normalize(samples: list, peak: float = 0.9) -> list:
    cur_peak = max((abs(s) for s in samples), default=0.0)
    if cur_peak < 1e-9:
        return samples
    scale = peak / cur_peak
    return [s * scale for s in samples]


def pad_to(samples: list, total_len: int) -> list:
    if len(samples) >= total_len:
        return samples[:total_len]
    return samples + silence(total_len - len(samples))


def triangle_wave(phase_frac: float) -> float:
    """phase_frac in [0,1) -> triangle wave in [-1, 1]."""
    return 4.0 * abs(phase_frac - 0.5) - 1.0


def square_wave(phase_frac: float, duty: float = 0.5) -> float:
    return 1.0 if phase_frac < duty else -1.0


# ---------------------------------------------------------------------------
# DRUMS: kick + snare/clap
# ---------------------------------------------------------------------------

def synth_kick(total_len: int = BAR_SAMPLES) -> list:
    """Short sine pitch-drop kick + amplitude envelope, padded with
    silence to a full bar so it sits cleanly on the grid."""
    hit_ms = 380
    hit_len = int(SAMPLE_RATE * hit_ms / 1000)
    f_start, f_end = 150.0, 45.0
    pitch_tau = 0.035   # seconds, pitch-drop time constant
    amp_tau = 0.10       # seconds, amplitude decay time constant
    attack = int(SAMPLE_RATE * 0.002)  # 2ms click-free attack

    out = [0.0] * hit_len
    phase = 0.0
    for i in range(hit_len):
        t = i / SAMPLE_RATE
        freq = f_end + (f_start - f_end) * math.exp(-t / pitch_tau)
        phase += 2.0 * math.pi * freq / SAMPLE_RATE
        env = math.exp(-t / amp_tau)
        if i < attack:
            env *= i / attack
        out[i] = math.sin(phase) * env

    apply_fade(out, fade_in=0, fade_out=int(SAMPLE_RATE * 0.01))
    out = normalize(out, 0.9)
    return pad_to(out, total_len)


def synth_snare(total_len: int = BAR_SAMPLES) -> list:
    """Filtered noise burst (band-limited) + envelope, with a couple of
    quick repeats layered in for a clap-like flutter, padded to a bar."""
    burst_ms = 220
    burst_len = int(SAMPLE_RATE * burst_ms / 1000)
    amp_tau = 0.055

    noise = [random.uniform(-1.0, 1.0) for _ in range(burst_len)]
    shaped = one_pole_lowpass(noise, 4200.0)
    shaped = one_pole_highpass(shaped, 700.0)

    out = [0.0] * burst_len
    for i in range(burst_len):
        t = i / SAMPLE_RATE
        out[i] = shaped[i] * math.exp(-t / amp_tau)

    # clap flutter: two extra short decaying noise bursts, slightly offset
    for offset_ms, gain in ((9, 0.55), (19, 0.35)):
        off = int(SAMPLE_RATE * offset_ms / 1000)
        for i in range(burst_len - off):
            t = i / SAMPLE_RATE
            out[i + off] += shaped[i] * math.exp(-t / amp_tau) * gain

    # low tonal thump for body
    thump_len = int(SAMPLE_RATE * 0.05)
    for i in range(thump_len):
        t = i / SAMPLE_RATE
        out[i] += math.sin(2 * math.pi * 180.0 * t) * math.exp(-t / 0.02) * 0.4

    apply_fade(out, fade_in=0, fade_out=int(SAMPLE_RATE * 0.01))
    out = normalize(out, 0.85)
    return pad_to(out, total_len)


# ---------------------------------------------------------------------------
# BASS: two short riffs, loop length ~2 bars
# ---------------------------------------------------------------------------

def synth_bass_riff(midi_notes: list, total_len: int = BAR_SAMPLES * 2) -> list:
    n_notes = len(midi_notes)
    note_len = total_len // n_notes
    out = []
    attack = int(SAMPLE_RATE * 0.006)
    release = int(SAMPLE_RATE * 0.012)

    for note_idx, midi_note in enumerate(midi_notes):
        freq = midi_to_freq(midi_note)
        this_len = note_len if note_idx < n_notes - 1 else (total_len - len(out))
        note = [0.0] * this_len
        phase_acc = 0.0
        for i in range(this_len):
            phase_acc += freq / SAMPLE_RATE
            phase_acc -= math.floor(phase_acc)
            env = 1.0
            if i < attack:
                env = i / attack
            elif i > this_len - release:
                env = max(0.0, (this_len - i) / release)
            note[i] = triangle_wave(phase_acc) * env
        out.extend(note)

    out = normalize(out, 0.8)
    return pad_to(out, total_len)


# ---------------------------------------------------------------------------
# SYNTH: chord stab + arpeggio, loop length 1 bar
# ---------------------------------------------------------------------------

def synth_chord(midi_notes: list, total_len: int = BAR_SAMPLES) -> list:
    """Held sine-stack triad with a slow tremolo, single-bar loop."""
    n = total_len
    phases = [0.0] * len(midi_notes)
    freqs = [midi_to_freq(m) for m in midi_notes]
    attack = int(SAMPLE_RATE * 0.02)
    release = int(SAMPLE_RATE * 0.03)
    tremolo_hz = 4.5

    out = [0.0] * n
    for i in range(n):
        t = i / SAMPLE_RATE
        env = 1.0
        if i < attack:
            env = i / attack
        elif i > n - release:
            env = max(0.0, (n - i) / release)
        tremolo = 0.85 + 0.15 * math.sin(2 * math.pi * tremolo_hz * t)
        s = 0.0
        for idx, freq in enumerate(freqs):
            phases[idx] += 2.0 * math.pi * freq / SAMPLE_RATE
            s += math.sin(phases[idx])
        out[i] = (s / len(freqs)) * env * tremolo

    out = normalize(out, 0.75)
    return out


def synth_arp(midi_notes: list, total_len: int = BAR_SAMPLES) -> list:
    """Square-wave arpeggio stepping through midi_notes, filling exactly
    one bar (8 eighth notes)."""
    n_steps = 8
    step_len = total_len // n_steps
    attack = int(SAMPLE_RATE * 0.003)
    release = int(SAMPLE_RATE * 0.006)

    out = []
    for step in range(n_steps):
        midi_note = midi_notes[step % len(midi_notes)]
        freq = midi_to_freq(midi_note)
        this_len = step_len if step < n_steps - 1 else (total_len - len(out))
        note = [0.0] * this_len
        phase_acc = 0.0
        for i in range(this_len):
            phase_acc += freq / SAMPLE_RATE
            phase_acc -= math.floor(phase_acc)
            env = 1.0
            if i < attack:
                env = i / attack
            elif i > this_len - release:
                env = max(0.0, (this_len - i) / release)
            note[i] = square_wave(phase_acc) * env * 0.5
        out.extend(note)

    out = normalize(out, 0.7)
    return pad_to(out, total_len)


# ---------------------------------------------------------------------------
# VOCAL: placeholder "vowel-ish" tones (clearly synthetic, not real vocals)
# ---------------------------------------------------------------------------

def synth_vocal_tone(fundamental_midi: float, partial_gains: list,
                      total_len: int = BAR_SAMPLES) -> list:
    n = total_len
    fundamental = midi_to_freq(fundamental_midi)
    attack = int(SAMPLE_RATE * 0.06)
    release = int(SAMPLE_RATE * 0.15)
    vibrato_hz = 5.5
    vibrato_depth = 0.006  # +/- 0.6% frequency wobble

    phase = 0.0
    out = [0.0] * n
    for i in range(n):
        t = i / SAMPLE_RATE
        env = 1.0
        if i < attack:
            env = i / attack
        elif i > n - release:
            env = max(0.0, (n - i) / release)
        vibrato = 1.0 + vibrato_depth * math.sin(2 * math.pi * vibrato_hz * t)
        freq = fundamental * vibrato
        phase += 2.0 * math.pi * freq / SAMPLE_RATE
        s = 0.0
        for partial_num, gain in enumerate(partial_gains, start=1):
            s += math.sin(phase * partial_num) * gain
        out[i] = s * env

    out = normalize(out, 0.7)
    return out


# ---------------------------------------------------------------------------
# WAV I/O
# ---------------------------------------------------------------------------

def write_wav(path: Path, samples: list, sr: int = SAMPLE_RATE) -> int:
    """Writes 16-bit PCM mono WAV. Returns the number of frames written."""
    ints = array('h', (max(-32768, min(32767, int(round(s * 32767.0)))) for s in samples))
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(struct.pack('<%dh' % len(ints), *ints))
    return len(ints)


def measure_duration_ms(path: Path) -> float:
    with wave.open(str(path), 'rb') as wf:
        n_frames = wf.getnframes()
        sr = wf.getframerate()
        return n_frames * 1000.0 / sr


# ---------------------------------------------------------------------------
# Build the pack
# ---------------------------------------------------------------------------

def build_pack():
    LOOPS_DIR.mkdir(parents=True, exist_ok=True)

    # midi note numbers: 60 = C4
    bass_riff_1_notes = [33, 33, 36, 33, 40, 33, 36, 31]   # A1-rooted riff
    bass_riff_2_notes = [38, 41, 38, 45, 38, 41, 36, 38]   # D2-rooted riff

    chord_1_notes = [60, 64, 67]        # C major triad
    arp_1_notes = [60, 64, 67, 72]      # C major arpeggio up an octave

    jobs = [
        dict(
            filename="kick_basic_01.wav",
            id="kick_basic_01",
            name="Basic Kick",
            category="DRUMS",
            gen=lambda: synth_kick(),
        ),
        dict(
            filename="snare_basic_01.wav",
            id="snare_basic_01",
            name="Basic Snare",
            category="DRUMS",
            gen=lambda: synth_snare(),
        ),
        dict(
            filename="bass_riff_01.wav",
            id="bass_riff_01",
            name="Bass Riff One",
            category="BASS",
            gen=lambda: synth_bass_riff(bass_riff_1_notes),
        ),
        dict(
            filename="bass_riff_02.wav",
            id="bass_riff_02",
            name="Bass Riff Two",
            category="BASS",
            gen=lambda: synth_bass_riff(bass_riff_2_notes),
        ),
        dict(
            filename="synth_chord_01.wav",
            id="synth_chord_01",
            name="Synth Chord",
            category="SYNTH",
            gen=lambda: synth_chord(chord_1_notes),
        ),
        dict(
            filename="synth_arp_01.wav",
            id="synth_arp_01",
            name="Synth Arp",
            category="SYNTH",
            gen=lambda: synth_arp(arp_1_notes),
        ),
        dict(
            filename="vocal_oh_01.wav",
            id="vocal_oh_01",
            name="Vocal Oh",
            category="VOCAL",
            # "oh": low-ish fundamental, energy concentrated in low partials
            gen=lambda: synth_vocal_tone(57, [1.0, 0.35, 0.12, 0.04]),
        ),
        dict(
            filename="vocal_ah_01.wav",
            id="vocal_ah_01",
            name="Vocal Ah",
            category="VOCAL",
            # "ah": slightly higher fundamental, brighter partial mix
            gen=lambda: synth_vocal_tone(62, [1.0, 0.55, 0.30, 0.15, 0.06]),
        ),
    ]

    manifest = []
    print(f"Sample rate: {SAMPLE_RATE} Hz, BPM: {BPM}, "
          f"1 beat = {BEAT_SAMPLES} samples, 1 bar = {BAR_SAMPLES} samples\n")

    for job in jobs:
        samples = job["gen"]()
        out_path = LOOPS_DIR / job["filename"]
        n_frames = write_wav(out_path, samples)
        duration_ms = round(n_frames * 1000.0 / SAMPLE_RATE)

        manifest.append({
            "id": job["id"],
            "name": job["name"],
            "category": job["category"],
            "assetPath": f"loops/{job['filename']}",
            "durationMs": duration_ms,
            "bpm": BPM,
        })

        size_kb = out_path.stat().st_size / 1024.0
        print(f"  wrote {job['filename']:22s} "
              f"{n_frames:7d} frames  {duration_ms:6d} ms  {size_kb:7.1f} KB")

    manifest_path = LOOPS_DIR / "manifest.json"
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)
        f.write('\n')

    print(f"\nWrote manifest: {manifest_path}")
    return manifest, manifest_path


def sanity_check(manifest: list, manifest_path: Path) -> bool:
    print("\nSanity check:")
    ok = True
    total_bytes = 0
    valid_categories = {"DRUMS", "BASS", "SYNTH", "VOCAL"}

    for entry in manifest:
        wav_path = LOOPS_DIR / Path(entry["assetPath"]).name
        # assetPath is relative to app/src/main/assets/, e.g. "loops/xxx.wav"
        full_asset_path = LOOPS_DIR.parent / entry["assetPath"]
        if not full_asset_path.exists():
            print(f"  FAIL: {entry['assetPath']} does not exist on disk")
            ok = False
            continue

        measured_ms = measure_duration_ms(full_asset_path)
        diff = abs(measured_ms - entry["durationMs"])
        total_bytes += full_asset_path.stat().st_size

        status = "OK" if diff <= 2.0 else "MISMATCH"
        if diff > 2.0:
            ok = False
        print(f"  {status:8s} {entry['id']:16s} manifest={entry['durationMs']:6d}ms "
              f"measured={measured_ms:8.2f}ms diff={diff:5.2f}ms")

        if entry["category"] not in valid_categories:
            print(f"  FAIL: {entry['id']} has invalid category {entry['category']}")
            ok = False

    print(f"\nTotal asset size: {total_bytes / (1024*1024):.2f} MB "
          f"({total_bytes} bytes) across {len(manifest)} files")
    if total_bytes > 5 * 1024 * 1024:
        print("  WARNING: exceeds 5MB target")
        ok = False

    if ok:
        print("\nAll checks passed.")
    else:
        print("\nSome checks FAILED -- see above.")
    return ok


if __name__ == "__main__":
    manifest, manifest_path = build_pack()
    passed = sanity_check(manifest, manifest_path)
    if not passed:
        raise SystemExit(1)
