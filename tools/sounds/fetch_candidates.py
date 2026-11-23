"""
Gathers recorded (not synthesized) candidates for the message sounds from Freesound, CC0 only: short pops, bubbles and
soft swooshes, like the ones messaging apps use. Each is trimmed of silence, cut short, faded, loudness-matched and
saved as mp3 for the listening page.

    python tools/sounds/fetch_candidates.py

Writes tools/sounds/pick/<kind>_<n>.mp3 and tools/sounds/pick/candidates.json (title, author, url, licence).
"""
import html
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "pick")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

# What each sound should be, as Freesound searches; the shortest well-rated ones are kept.
SEARCHES = {
    "send": ["message sent pop", "bubble pop short", "mouth pop", "soft whoosh short", "swoosh ui", "pop click"],
    "receive": ["message notification pop", "water drop single", "soft blip notification", "bubble notification",
                "wood tap soft", "glass tap single"],
}
PER_KIND = 12
MAX_DUR = 1.2
SKIP = re.compile(r"music|song|loop|voice|ambien", re.I)


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as r:
        return r.read()


def search(words: str) -> list:
    q = urllib.parse.urlencode({"q": words, "f": 'license:"Creative Commons 0"', "s": "Rating (highest first)"})
    page = get("https://freesound.org/search/?" + q).decode("utf-8", "replace")
    out = []
    for block in re.findall(r'<div\s+class="bw-player"(.*?)tabindex', page, re.S):
        attrs = dict(re.findall(r'data-([a-z0-9-]+)="([^"]*)"', block))
        if "mp3" not in attrs:
            continue
        out.append({
            "id": attrs.get("sound-id"),
            "user": html.unescape(attrs.get("username", "")),
            "title": html.unescape(attrs.get("title", "")),
            "dur": float(attrs.get("duration", "0") or 0),
            "mp3": attrs["mp3"].replace("-lq.mp3", "-hq.mp3"),
        })
    return out


def convert(src: str, dst: str, keep: float) -> None:
    filt = (
        "silenceremove=start_periods=1:start_threshold=-42dB,"
        "areverse,silenceremove=start_periods=1:start_threshold=-42dB,areverse,"
        f"atrim=0:{keep},afade=t=out:st={max(0.0, keep - 0.05)}:d=0.05,"
        "loudnorm=I=-20:TP=-2:LRA=7"
    )
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-af", filt, "-ac", "1", "-ar", "44100",
                    "-c:a", "libmp3lame", "-q:a", "3", dst], check=True)


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    result = {}
    seen = set()
    with tempfile.TemporaryDirectory() as tmp:
        for kind, words in SEARCHES.items():
            picked = []
            for w in words:
                try:
                    hits = search(w)
                except Exception as e:  # one search failing leaves the others
                    print("search failed:", w, e, file=sys.stderr)
                    continue
                # A few from each search, so the list has several kinds of sound, not one kind many times.
                taken = 0
                for h in hits:
                    if h["id"] in seen or not (0.05 < h["dur"] <= MAX_DUR) or SKIP.search(h["title"]):
                        continue
                    seen.add(h["id"])
                    picked.append(h)
                    taken += 1
                    if taken >= 3:
                        break
            kept = []
            for h in picked:
                if len(kept) >= PER_KIND:
                    break
                n = len(kept) + 1
                src = os.path.join(tmp, f"{kind}_{n}_src.mp3")
                dst = os.path.join(OUT, f"{kind}_{n}.mp3")
                try:
                    with open(src, "wb") as f:
                        f.write(get(h["mp3"]))
                    convert(src, dst, keep=0.6 if kind == "send" else 0.9)
                except Exception as e:
                    print("skip", h["title"], e, file=sys.stderr)
                    continue
                kept.append({"file": f"{kind}_{n}.mp3", "title": h["title"], "author": h["user"],
                             "url": f"https://freesound.org/people/{h['user']}/sounds/{h['id']}/", "license": "CC0"})
                print(kind, n, h["title"], "by", h["user"])
            result[kind] = kept
    with open(os.path.join(OUT, "candidates.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
