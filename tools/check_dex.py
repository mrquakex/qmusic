"""
Release check: no method in the APK may take more than 255 argument registers, or Android refuses to load its class
(VerifyError at run time, not at build time). 2.9.0 crashed on opening Messages this way: the chat strings had grown to
a 262-register constructor.

    python tools/check_dex.py [path/to/app.apk]

Exits 1 (and lists them) when any method takes 200 or more.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIMIT = 200


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "androidApp", "build", "outputs", "apk", "release", "androidApp-release.apk")
    tools = sorted(glob.glob(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "build-tools", "*")))
    dexdump = os.path.join(tools[-1], "dexdump.exe" if os.name == "nt" else "dexdump")
    bad = []
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as z:
            dexes = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
            for n in dexes:
                z.extract(n, tmp)
        for n in dexes:
            out = subprocess.run([dexdump, "-f", os.path.join(tmp, n)], capture_output=True, text=True, errors="replace").stdout
            cls = meth = None
            for line in out.splitlines():
                m = re.match(r"\s*Class descriptor\s*:\s*'(.+)'", line)
                if m:
                    cls = m.group(1)
                    continue
                m = re.match(r"\s*name\s*:\s*'(.+)'", line)
                if m:
                    meth = m.group(1)
                    continue
                m = re.match(r"\s*ins\s*:\s*(\d+)", line)
                if m and int(m.group(1)) >= LIMIT:
                    bad.append((int(m.group(1)), cls, meth))
    if bad:
        for ins, cls, meth in sorted(bad, reverse=True):
            print(f"{ins} registers: {cls} {meth}")
        sys.exit(1)
    print(f"ok: no method takes {LIMIT}+ argument registers ({len(dexes)} dex files)")


if __name__ == "__main__":
    main()
