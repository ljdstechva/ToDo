#!/usr/bin/env python3
"""Synthesises the five bundled alarm tones into app/src/main/res/raw/*.ogg.

Every tone is generated here from plain sine/bell maths, so there is no third-party audio and no
licence to track. Each file is one loop cycle; the app plays it with MediaPlayer.isLooping.

    python3 tools/make_tones.py            # needs numpy and ffmpeg with libvorbis

Tones (id -> character):
    siren    very loud sweeping siren
    classic  digital alarm-clock beeps
    phone    old telephone bell, double ring
    chimes   soft pentatonic bells (calming; the app also fades it in)
    sunrise  warm swelling chord (calming; the app also fades it in)
"""

import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100
OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"


def t_axis(seconds):
    return np.arange(int(SR * seconds)) / SR


def fade(x, ms=6):
    n = int(SR * ms / 1000)
    if n and len(x) > 2 * n:
        ramp = np.linspace(0.0, 1.0, n)
        x[:n] *= ramp
        x[-n:] *= ramp[::-1]
    return x


def normalise(x, peak):
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def siren():
    t = t_axis(5.0)  # 3 sweeps of 0.6 Hz: the loop joins smoothly
    freq = 1000 + 450 * np.sin(2 * np.pi * 0.6 * t - np.pi / 2)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    tone = np.tanh(3.0 * np.sin(phase)) + 0.25 * np.sin(2 * phase)
    return normalise(fade(tone, 4), 0.97)


def classic():
    cycle = []
    beep = t_axis(0.10)
    gap = np.zeros(int(SR * 0.08))
    for _ in range(4):
        b = np.tanh(2.0 * np.sin(2 * np.pi * 2400 * beep)) * 0.9
        cycle += [fade(b, 3), gap]
    cycle.append(np.zeros(int(SR * 0.5)))
    one = np.concatenate(cycle)
    return normalise(np.concatenate([one, one]), 0.85)


def bell(f0, seconds, tau):
    t = t_axis(seconds)
    partials = [(1.0, 1.0), (2.0, 0.45), (2.76, 0.30), (5.4, 0.12)]
    x = sum(a * np.sin(2 * np.pi * f0 * k * t) for k, a in partials)
    return x * np.exp(-t / tau)


def phone():
    # Two small bells struck 25 times a second: ring 0.4 s, pause 0.2 s, ring 0.4 s, pause 2 s.
    def ring(seconds):
        t = t_axis(seconds)
        hammer = (np.sin(2 * np.pi * 25 * t) > 0).astype(float)
        strike = np.exp(-((t * 25) % 1.0) / 0.35)
        body = np.sin(2 * np.pi * 1150 * t) + 0.6 * np.sin(2 * np.pi * 1730 * t) + 0.3 * np.sin(2 * np.pi * 3170 * t)
        return fade(body * (0.35 + 0.65 * hammer * strike), 8)

    parts = [ring(0.4), np.zeros(int(SR * 0.2)), ring(0.4), np.zeros(int(SR * 2.0))]
    return normalise(np.concatenate(parts), 0.8)


def chimes():
    notes = [523.25, 659.25, 783.99, 880.00, 1046.50, 880.00, 783.99, 659.25]
    total = np.zeros(int(SR * 5.5))
    for i, f in enumerate(notes):
        start = int(SR * 0.5 * i)
        b = bell(f, 2.2, 0.9)
        end = min(len(total), start + len(b))
        total[start:end] += b[: end - start]
    return normalise(fade(total, 400), 0.55)


def sunrise():
    t = t_axis(7.0)
    chord = [261.63, 329.63, 392.00, 493.88, 587.33]
    pad = np.zeros_like(t)
    for f in chord:
        for detune in (0.997, 1.003):
            pad += np.sin(2 * np.pi * f * detune * t)
    env = np.interp(t, [0, 2.5, 4.0, 6.5, 7.0], [0, 1, 1, 0, 0])
    tremolo = 1 - 0.1 * (0.5 + 0.5 * np.sin(2 * np.pi * 4 * t))
    sparkle = np.zeros_like(t)
    for at, f in ((1.0, 1046.5), (2.2, 1318.5), (3.4, 1568.0)):
        s = int(SR * at)
        b = bell(f, 2.0, 0.7) * 0.6
        sparkle[s:s + len(b)] += b[: len(t) - s]
    return normalise(pad * env * tremolo + sparkle * env, 0.6)


TONES = {
    "tone_siren": siren,
    "tone_classic": classic,
    "tone_phone": phone,
    "tone_chimes": chimes,
    "tone_sunrise": sunrise,
}


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, make in TONES.items():
            wav = Path(tmp) / f"{name}.wav"
            write_wav(wav, make())
            ogg = OUT / f"{name}.ogg"
            subprocess.run(
                ["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "5", str(ogg)],
                check=True,
            )
            print(f"{ogg.relative_to(OUT.parent.parent.parent.parent.parent)}  {ogg.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
