#!/usr/bin/env python3
"""
Generates GridRacer's 5 sound effects as 44.1kHz mono 16-bit PCM WAV files, using
only the Python standard library (wave/struct/math/random) - no external assets,
no downloads, no synthesis libraries. Run from the repo root:

    python3 scripts/generate_sounds.py

Output goes to app/src/main/res/raw/*.wav (Android resource names must be
lowercase letters/digits/underscore, which these already are).
"""
import math
import os
import random
import struct
import wave

SAMPLE_RATE = 44100
PEAK_AMPLITUDE = 0.6 * 32767
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def write_wav(name: str, samples: list[float]) -> None:
    """Normalizes [samples] (roughly -1..1) to 16-bit PCM headroom and writes a mono WAV."""
    peak = max((abs(s) for s in samples), default=1.0) or 1.0
    scale = PEAK_AMPLITUDE / peak
    path = os.path.join(OUT_DIR, name)
    with wave.open(path, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for s in samples:
            value = int(max(-32768, min(32767, s * scale)))
            frames += struct.pack("<h", value)
        wav_file.writeframes(bytes(frames))
    print(f"wrote {path} ({len(samples) / SAMPLE_RATE:.3f}s, {len(samples)} samples)")


def button_click() -> list[float]:
    """Short sine burst with a fast exponential decay - a crisp UI tap."""
    duration = 0.04
    freq = 1200.0
    n = int(SAMPLE_RATE * duration)
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        envelope = math.exp(-t * 90)
        samples.append(math.sin(2 * math.pi * freq * t) * envelope)
    return samples


def crash() -> list[float]:
    """Filtered noise burst layered with a low sine 'thud' - a car hitting a wall."""
    duration = 0.35
    n = int(SAMPLE_RATE * duration)
    raw_noise = [random.uniform(-1, 1) for _ in range(n)]
    window = 6
    filtered = []
    running_sum = 0.0
    for i in range(n):
        running_sum += raw_noise[i]
        if i >= window:
            running_sum -= raw_noise[i - window]
        filtered.append(running_sum / min(i + 1, window))
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        noise_env = math.exp(-t * 9)
        thud_env = math.exp(-t * 14)
        thud = math.sin(2 * math.pi * 95 * t) * thud_env * 0.7
        samples.append(filtered[i] * noise_env * 0.85 + thud)
    return samples


def finish_cheer() -> list[float]:
    """A short ascending arpeggio - a triumphant little fanfare for finishing the race."""
    notes = [523.25, 659.25, 783.99, 1046.50, 1318.51]  # C5 E5 G5 C6 E6
    note_duration = 0.16
    samples = []
    for freq in notes:
        n = int(SAMPLE_RATE * note_duration)
        for i in range(n):
            t = i / SAMPLE_RATE
            envelope = math.sin(math.pi * i / n) if n > 0 else 0.0
            value = math.sin(2 * math.pi * freq * t) * 0.7 + math.sin(2 * math.pi * freq * 2 * t) * 0.2
            samples.append(value * envelope)
    return samples


def motor_hum() -> list[float]:
    """Fundamental + decaying harmonics, looped over an exact integer number of periods."""
    fundamental = 90.0
    target_duration = 0.5
    periods = max(1, round(fundamental * target_duration))
    duration = periods / fundamental
    n = int(SAMPLE_RATE * duration)
    harmonics = [(1, 0.6), (2, 0.25), (3, 0.1), (4, 0.05)]
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        value = sum(math.sin(2 * math.pi * fundamental * mult * t) * amp for mult, amp in harmonics)
        samples.append(value)
    return samples


def starting_pistol() -> list[float]:
    """Sharp, fast attack/decay noise burst - more percussive than the crash sound, no low thud."""
    duration = 0.15
    n = int(SAMPLE_RATE * duration)
    samples = []
    for i in range(n):
        t = i / SAMPLE_RATE
        envelope = math.exp(-t * 45)
        samples.append(random.uniform(-1, 1) * envelope)
    return samples


def main() -> None:
    random.seed(1234)  # deterministic output across runs
    os.makedirs(OUT_DIR, exist_ok=True)
    write_wav("sfx_button_click.wav", button_click())
    write_wav("sfx_crash.wav", crash())
    write_wav("sfx_finish_cheer.wav", finish_cheer())
    write_wav("sfx_motor_hum.wav", motor_hum())
    write_wav("sfx_starting_pistol.wav", starting_pistol())


if __name__ == "__main__":
    main()
