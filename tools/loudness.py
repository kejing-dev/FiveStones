"""Offline loudness evidence (B20 / ui_gates loudness): renders 30 s of a fast game from the app's own WAVs at the
default volume (gain 1.0), with the app's timing (stone flight 220 ms, retract window 500 ms, AI >= 400 ms) and
the +-5 % rate variation, then prints RMS / peak.  kj-mbp-m5 has no audio loopback, so the emulator output itself
cannot be recorded; record on a headset / loopback machine to cross-check.

  python3 tools/loudness.py OUT.wav
"""
import math, os, random, struct, sys, wave

SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        return [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]


def rate(x, r):
    n = int(len(x) / r)
    return [x[min(len(x) - 1, int(i * r))] for i in range(n)]


def main(out):
    rnd = random.Random(5)
    places = [load("place"), load("place_2"), load("place_3")]
    win = load("win")
    buf = [0.0] * (30 * SR)

    def put(x, t):
        o = int(t * SR)
        for i, v in enumerate(x):
            if o + i < len(buf):
                buf[o + i] += v

    t = 0.3
    turn = 0
    while t < 28.0:
        put(rate(rnd.choice(places), 0.95 + rnd.random() * 0.10), t + 0.22)       # human stone lands
        t += 0.22 + 0.5 + 0.4                                                  # window + min think
        put(rate(rnd.choice(places), 0.95 + rnd.random() * 0.10), t + 0.22)   # AI stone lands
        t += 0.22 + 0.55                                                       # a quick human reply
        turn += 1
    put([v * 0.55 for v in win], 28.3)                                     # UI cue at its playback gain
    peak = max(abs(v) for v in buf)
    if peak > 10 ** (-1 / 20):
        k = 10 ** (-1 / 20) / peak
        buf = [v * k for v in buf]
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in buf))
    rms = math.sqrt(sum(v * v for v in buf) / len(buf))
    pk = max(abs(v) for v in buf)
    print("RMS %.1f dBFS  peak %.1f dBFS  turns %d" % (20 * math.log10(rms), 20 * math.log10(pk), turn))


main(sys.argv[1])
