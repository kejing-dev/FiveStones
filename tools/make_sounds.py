"""Synthesises Five Stones' seven effects (design-package sound.list) into app/src/main/assets/sounds, stdlib only.

  python3 tools/make_sounds.py [--check]

place / place_2 / place_3  stone on kaya (three variants; the app adds +-7 % rate)
retract                    stone sliding back into the bowl
illegal                    one dull knock          forbidden   two short knocks
hint                       a soft wood-block       win         wood roll + short chord (<= 1.5 s)
draw                       one low tone        lose   two falling notes (the AI made five)
All mono 44.1 kHz 16-bit, stone / win peaks at -1 dBFS, no ambience (B24). --check prints peak / RMS per file.
"""
import math
import os
import random
import struct
import sys
import wave

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
PEAK_DB = -1.0  # B20: default peaks within -1 dBFS


def lowpass(x, cutoff):
    a = 1 - math.exp(-2 * math.pi * cutoff / SR)
    y, out = 0.0, []
    for v in x:
        y += a * (v - y)
        out.append(y)
    return out


def highpass(x, cutoff):
    lp = lowpass(x, cutoff)
    return [a - b for a, b in zip(x, lp)]


def tone(freq, dur, decay, phase=0.0, amp=1.0):
    n = int(SR * dur)
    return [amp * math.sin(2 * math.pi * freq * i / SR + phase) * math.exp(-i / SR / decay) for i in range(n)]


def noise(dur, decay, seed):
    r = random.Random(seed)
    n = int(SR * dur)
    return [(r.random() * 2 - 1) * math.exp(-i / SR / decay) for i in range(n)]


def mix(*parts, length=None):
    n = length or max(len(p) + int(off * SR) for p, off in parts)
    out = [0.0] * n
    for p, off in parts:
        o = int(off * SR)
        for i, v in enumerate(p):
            if o + i < n:
                out[o + i] += v
    return out


def norm(x, db=PEAK_DB):
    peak = max(abs(v) for v in x) or 1.0
    k = 10 ** (db / 20) / peak
    # 5 ms fade-out so nothing clicks at the end
    fade = int(0.005 * SR)
    y = [v * k for v in x]
    for i in range(fade):
        y[-1 - i] *= i / fade
    return y


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in x))


def drive(x, k=2.2):
    """Soft saturation: raises the body of the sound (RMS) without raising the peak (B20 loudness)."""
    return [math.tanh(k * v) for v in x]


def stone(seed, body=2100.0, wood=190.0):
    click = highpass(noise(0.03, 0.004, seed), 1200)
    ring = tone(body, 0.32, 0.05, amp=0.55)
    ring2 = tone(body * 1.53, 0.2, 0.025, amp=0.25)
    thump = tone(wood, 0.34, 0.08, amp=0.8)
    board = tone(wood * 2.7, 0.3, 0.06, amp=0.35)
    x = lowpass(mix((click, 0), (ring, 0.001), (ring2, 0.001), (thump, 0.0), (board, 0.0)), 6500)
    peak = max(abs(v) for v in x)
    return drive([v / peak for v in x])


def main():
    write("place", norm(stone(1, 2100, 190)))
    write("place_2", norm(stone(2, 2280, 205)))
    write("place_3", norm(stone(3, 1960, 178)))
    slide = lowpass(highpass(noise(0.26, 0.09, 9), 700), 3200)
    clicks = mix(*[(highpass(noise(0.01, 0.002, 20 + k), 1500), 0.03 + k * 0.045) for k in range(5)], length=int(0.3 * SR))
    write("retract", norm(mix((slide, 0), ([v * 0.6 for v in clicks], 0)), -4))
    knock = lowpass(mix((tone(210, 0.14, 0.03), 0), (noise(0.02, 0.004, 5), 0)), 1800)
    write("illegal", norm(knock, -3))
    write("forbidden", norm(mix((knock, 0), (knock, 0.13)), -3))
    block = lowpass(mix((tone(880, 0.3, 0.06), 0), (tone(1380, 0.2, 0.03, amp=0.4), 0), (highpass(noise(0.01, 0.002, 7), 1500), 0)), 4000)
    write("hint", norm(block, -6))
    roll = [(stone(30 + k, 2000 + 60 * k, 190), 0.07 * k) for k in range(5)]
    chord = [(tone(f, 1.1, 0.45, amp=0.35), 0.38) for f in (523.25, 659.25, 783.99, 1046.5)]
    write("win", norm(lowpass(mix(*roll, *chord, length=int(1.45 * SR)), 5000)))
    low = [math.sin(2 * math.pi * (300 - 90 * i / (0.6 * SR)) * i / SR) * math.exp(-i / SR / 0.25) for i in range(int(0.6 * SR))]
    write("draw", norm(lowpass(low, 1500), -4))
    # lose: two falling wood notes, no fanfare (the opponent's five)
    fall = mix((tone(392, 0.35, 0.12), 0), (tone(294, 0.5, 0.18), 0.22), (noise(0.02, 0.004, 41), 0), (noise(0.02, 0.004, 42), 0.22))
    write("lose", norm(lowpass(fall, 1800), -3))
    if "--check" in sys.argv:
        for f in sorted(os.listdir(OUT)):
            with wave.open(os.path.join(OUT, f)) as w:
                data = struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))
            peak = max(abs(v) for v in data) / 32767
            rms = math.sqrt(sum(v * v for v in data) / len(data)) / 32767
            print("%-14s %5.2f s  peak %6.1f dBFS  rms %6.1f dBFS" % (f, len(data) / SR, 20 * math.log10(peak), 20 * math.log10(rms)))


main()
