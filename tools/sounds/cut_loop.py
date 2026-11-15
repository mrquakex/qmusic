"""
Cuts a seamless ringtone loop out of a track: whole bars from a start time, the seam crossfaded so it never clicks,
peaks at -1 dBFS, written as res/raw/snd_ring_<name>.ogg.

    python tools/sounds/cut_loop.py <track> <name> <bpm> <start seconds> <bars>

The "Qeyf zili" ringtone (plucky): python tools/sounds/cut_loop.py Plucky_Ringtone.mp3 plucky 126 22.611 16
(the drop at 22.6 s, through the one-bar break before the next section, which leads back into the drop).
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "androidApp", "src", "main", "res", "raw")


def main():
    track, name, bpm, start, bars = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), int(sys.argv[5])
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "src.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", track, "-ac", "2", "-ar", "44100", src], check=True)
        w = wave.open(src)
        sr, ch = w.getframerate(), w.getnchannels()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float).reshape(-1, ch) / 32768
        s = int(round(start * sr))
        n = int(round(bars * 4 * 60 / bpm * sr))
        xf = int(0.012 * sr)
        seg = x[s: s + n + xf].copy()
        out = seg[:n].copy()
        t = np.linspace(0, np.pi / 2, xf)[:, None]
        out[:xf] = seg[:xf] * np.sin(t) + seg[n: n + xf] * np.cos(t)
        out *= 0.89 / np.max(np.abs(out))
        loop = os.path.join(tmp, "loop.wav")
        o = wave.open(loop, "wb")
        o.setnchannels(ch)
        o.setsampwidth(2)
        o.setframerate(sr)
        o.writeframes((np.clip(out, -1, 1) * 32767).astype("<i2").tobytes())
        o.close()
        target = os.path.join(OUT, f"snd_ring_{name}.ogg")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", loop, "-c:a", "libvorbis", "-q:a", "6", target], check=True)
        print(target, round(n / sr, 3), "s")


if __name__ == "__main__":
    main()
