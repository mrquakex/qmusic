"""
Renders Qeyf's sounds into res/raw: the same note recipes as the sound picker page (tools/sounds/qeyf-sesleri.html),
played by a Python copy of its Web Audio synthesizer, so the phone plays what the page played.

    python tools/sounds/render_sounds.py        (needs numpy and ffmpeg)

Writes androidApp/src/main/res/raw/snd_<kind>_<id>.ogg.
"""
import math
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "androidApp", "src", "main", "res", "raw")


def hz(name):
    base = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}[name[0]]
    acc = name[1] if name[1] in "#b" else ""
    octave = int(name[-1])
    base += 1 if acc == "#" else -1 if acc == "b" else 0
    return 440.0 * 2 ** ((base + (octave - 4) * 12) / 12)


def N(t, f, d, **extra):
    n = {"t": t, "f": f, "d": d, "w": "sine", "v": 0.5, "a": 0.004}
    n.update(extra)
    return n


def bell(t, name, d, v=0.35, r=3.5, i=2.2):
    return N(t, hz(name), d, fm={"r": r, "i": i}, v=v)


def mallet(t, name, d=0.35, v=0.5):
    return N(t, hz(name), d, w="triangle", v=v, a=0.002)


SOUNDS = {
    "send": {
        "firlat": [N(0, 520, 0.16, f2=1180, g=0.09, v=0.45)],
        "damla": [N(0, 1500, 0.15, f2=820, g=0.05, v=0.5)],
        "tik": [N(0, 2400, 0.035, w="triangle", v=0.35, a=0.001), N(0, 1200, 0.06, v=0.3)],
        "kus": [N(0, hz("G6"), 0.07, v=0.35), N(0.07, hz("C7"), 0.1, v=0.35)],
        "kabarcik": [N(0, 300, 0.14, f2=900, g=0.08, v=0.6, fm={"r": 1.5, "i": 0.6})],
    },
    "recv": {
        "pit": [N(0, 880, 0.16, v=0.45)],
        "cam": [bell(0, "E6", 0.42, 0.28, 3.5, 1.4)],
        "ikinota": [N(0, hz("E6"), 0.08, v=0.35), N(0.08, hz("C6"), 0.14, v=0.35)],
        "dusus": [N(0, 1100, 0.2, f2=620, g=0.12, v=0.42)],
        "yumusak": [N(0, 660, 0.22, w="triangle", v=0.28), N(0, 990, 0.22, w="triangle", v=0.2)],
    },
    "notify": {
        "qeyf": [bell(0, "C6", 0.5), bell(0.11, "E6", 0.5), bell(0.22, "G6", 0.8, 0.32)],
        "cay": [bell(0, "E7", 0.35, 0.3, 5.1, 3), bell(0.16, "E7", 0.55, 0.26, 5.1, 3)],
        "marimba": [mallet(0, "A5", 0.3), mallet(0.1, "E6", 0.45)],
        "yukselen": [N(0, hz("D6"), 0.12, v=0.32), N(0.1, hz("F#6"), 0.12, v=0.32), N(0.2, hz("A6"), 0.3, v=0.32)],
        "kristal": [bell(0, "B6", 1.2, 0.32, 2.76, 2.6)],
    },
    "ring": {
        "ritim": ([mallet(0, "C5"), mallet(0.18, "E5"), mallet(0.36, "G5"), mallet(0.54, "C6", 0.5), mallet(0.9, "G5"), mallet(1.08, "C6", 0.6)], 2.2),
        "hicaz": ([N(t, hz(n), 0.42, w="sawtooth", v=0.22, a=0.002, lp=2200) for n, t in
                   [("D5", 0), ("Eb5", 0.16), ("F#5", 0.32), ("G5", 0.48), ("A5", 0.64), ("G5", 0.9), ("F#5", 1.06), ("Eb5", 1.22), ("D5", 1.38)]], 2.4),
        "eskizil": ([bell(k * 0.05, "E7" if k % 2 else "D7", 0.06, 0.22, 7.1, 3.5) for k in range(18)], 2.0),
        "gece": ([N(0, hz("A4"), 1.1, v=0.18, a=0.15), N(0, hz("C#5"), 1.1, v=0.14, a=0.15), N(0, hz("E5"), 1.1, v=0.14, a=0.15),
                  N(1.1, hz("F#4"), 1.1, v=0.18, a=0.15), N(1.1, hz("A4"), 1.1, v=0.14, a=0.15), N(1.1, hz("C#5"), 1.1, v=0.14, a=0.15)], 2.6),
        "darbuka": ([N(0, 150, 0.22, f2=70, g=0.12, v=0.9),
                     N(0.3, 0, 0.06, w="noise", bp=3200, v=0.6), N(0.45, 0, 0.06, w="noise", bp=3200, v=0.5),
                     N(0.6, 150, 0.22, f2=70, g=0.12, v=0.9),
                     N(0.9, 0, 0.06, w="noise", bp=3200, v=0.6),
                     N(1.2, 150, 0.22, f2=70, g=0.12, v=0.9), N(1.35, 150, 0.22, f2=70, g=0.12, v=0.7),
                     N(1.5, 0, 0.06, w="noise", bp=3200, v=0.6)], 1.8),
    },
    "ringback": {
        "klasik": ([N(0, 450, 1.0, v=0.25, a=0.01, hold=True)], 4.0),
        "yumusakbek": ([N(0, hz("A5"), 0.35, v=0.28), N(0.35, hz("E5"), 0.5, v=0.28)], 2.5),
        "qeyfbek": ([bell(0, "C6", 0.6, 0.22), bell(0.18, "G5", 0.9, 0.2)], 2.8),
    },
    # The small sounds now come from Kenney's pack (import_kenney.py); these are kept as recipes, not written.
    "_small": {
        "recstart": [N(0, hz("C6"), 0.06, v=0.3), N(0.06, hz("G6"), 0.08, v=0.3)],
        "recstop": [N(0, hz("G6"), 0.06, v=0.3), N(0.06, hz("C6"), 0.1, v=0.3)],
        "connected": [bell(0, "G5", 0.3, 0.25), bell(0.12, "D6", 0.4, 0.25)],
        "ended": [N(0, hz("E5"), 0.15, v=0.3), N(0.15, hz("C5"), 0.25, v=0.3)],
    },
}


def exp_ramp(v0, v1, n):
    """Web Audio's exponentialRampToValueAtTime over n samples."""
    if n <= 0:
        return np.zeros(0)
    k = np.arange(n) / n
    return v0 * (v1 / v0) ** k


def biquad(x, kind, f0, q):
    """The RBJ biquad, as Web Audio's BiquadFilterNode computes it."""
    w0 = 2 * math.pi * f0 / SR
    alpha = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    if kind == "lowpass":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    else:  # bandpass, constant 0 dB peak
        b = [alpha, 0, -alpha]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    b = [c / a[0] for c in b]
    a = [c / a[0] for c in a]
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        xi = x[i]
        yi = b[0] * xi + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2, x1, y2, y1 = x1, xi, y1, yi
        y[i] = yi
    return y


def render_note(n, total):
    out = np.zeros(total)
    t0 = int(n["t"] * SR)
    length = int(n["d"] * SR)
    if t0 >= total:
        return out
    count = min(length + int(0.02 * SR), total - t0)
    # Loudness: a linear rise, then an exponential fall to silence at the end (or held, then a quick release).
    env = np.full(count, 0.0001)
    att = max(1, int(n["a"] * SR))
    rise = np.linspace(0.0001, n["v"], att, endpoint=False)
    env[: min(att, count)] = rise[: min(att, count)]
    if att < length:
        if n.get("hold"):
            rel = int(0.02 * SR)
            env[att: length - rel] = n["v"]
            env[length - rel: length] = np.linspace(n["v"], 0.0001, rel)
        else:
            env[att: length] = exp_ramp(n["v"], 0.0001, length - att)
    env[length:] = 0.0
    # Pitch: an exponential glide to f2 over g, then held.
    if n["w"] == "noise":
        rng = np.random.default_rng(7)
        sig = rng.uniform(-1, 1, count)
    else:
        freq = np.full(count, float(n["f"]))
        if n.get("f2"):
            g = max(1, int(n["g"] * SR))
            freq[: min(g, count)] = exp_ramp(n["f"], n["f2"], g)[: min(g, count)]
            freq[g:] = n["f2"]
        if n.get("fm"):
            r, i = n["fm"]["r"], n["fm"]["i"]
            depth = exp_ramp(n["f"] * i, n["f"] * i * 0.05 + 0.01, max(1, length))
            depth = np.concatenate([depth, np.full(max(0, count - len(depth)), depth[-1])])[:count]
            tt = np.arange(count) / SR
            freq = freq + depth * np.sin(2 * math.pi * n["f"] * r * tt)
        phase = np.cumsum(freq) / SR  # in cycles
        frac = phase - np.floor(phase)
        w = n["w"]
        if w == "sine":
            sig = np.sin(2 * math.pi * phase)
        elif w == "triangle":
            sig = 1 - 4 * np.abs(frac - 0.5)
        elif w == "square":
            sig = np.where(frac < 0.5, 1.0, -1.0)
        else:  # sawtooth
            sig = 2 * frac - 1
    if n.get("lp"):
        sig = biquad(sig, "lowpass", n["lp"], 10 ** (1 / 20))
    if n.get("bp"):
        sig = biquad(sig, "bandpass", n["bp"], 1.4)
    out[t0: t0 + count] = sig * env
    return out


def render(notes, length=None):
    end = max(n["t"] + n["d"] for n in notes) + 0.05
    total = int((length or end) * SR)
    mix = np.zeros(total)
    for n in notes:
        mix += render_note(n, total)
    mix *= 0.9
    peak = np.max(np.abs(mix)) or 1.0
    # Every file as loud as the others: peaks at 0.89 (-1 dBFS).
    mix *= 0.89 / peak
    # Gentle fade on the last 5 ms, so a loop never clicks.
    f = min(len(mix), int(0.005 * SR))
    mix[-f:] *= np.linspace(1, 0, f)
    return mix


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
        target = os.path.join(OUT, name + ".ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "6", target], check=True)
        return os.path.getsize(target)


if __name__ == "__main__":
    total = 0
    for kind, sounds in SOUNDS.items():
        if kind.startswith("_"):
            continue
        for sid, recipe in sounds.items():
            notes, length = recipe if isinstance(recipe, tuple) else (recipe, None)
            size = write(f"snd_{kind}_{sid}", render(notes, length))
            total += size
            print(f"snd_{kind}_{sid}.ogg  {size} bytes")
    print(f"total {total} bytes")
