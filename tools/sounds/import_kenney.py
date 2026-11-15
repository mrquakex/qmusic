"""
Brings the sounds picked from Kenney's "Interface Sounds" pack (CC0, www.kenney.nl) into res/raw: silence trimmed
from both ends, peaks at -1 dBFS, mono 44.1 kHz Ogg Vorbis.

    python tools/sounds/import_kenney.py <folder with the pack's .ogg files>
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "androidApp", "src", "main", "res", "raw")

# Kenney file → Qeyf sound (chosen 2026-11-15).
PICKS = {
    "switch_006": "snd_send_anahtar",
    "switch_001": "snd_recv_cit",
    "question_001": "snd_notify_soru",
    "drop_003": "snd_small_recstart",
    "drop_004": "snd_small_recstop",
    "maximize_008": "snd_small_connected",
    "minimize_008": "snd_small_ended",
}
SR = 44100


def process(src, target):
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "in.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-ac", "1", "-ar", str(SR), raw], check=True)
        with wave.open(raw) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768
        # Silence at either end (under -50 dB) goes, keeping 3 ms before the sound and 20 ms after it.
        loud = np.where(np.abs(x) > 10 ** (-50 / 20))[0]
        if len(loud):
            a = max(0, loud[0] - int(0.003 * SR))
            b = min(len(x), loud[-1] + int(0.02 * SR))
            x = x[a:b]
        x = x * (0.89 / (np.max(np.abs(x)) or 1))
        fade = min(len(x), int(0.004 * SR))
        x[-fade:] *= np.linspace(1, 0, fade)
        out = os.path.join(tmp, "out.wav")
        o = wave.open(out, "wb")
        o.setnchannels(1)
        o.setsampwidth(2)
        o.setframerate(SR)
        o.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        o.close()
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", out, "-c:a", "libvorbis", "-q:a", "6", target], check=True)
        return len(x) / SR


if __name__ == "__main__":
    folder = sys.argv[1]
    for name, target in PICKS.items():
        length = process(os.path.join(folder, name + ".ogg"), os.path.join(OUT, target + ".ogg"))
        print(f"{name} -> {target}.ogg  {length:.3f} s")
