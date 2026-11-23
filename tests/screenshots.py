"""Makes the README screenshots of the site from demo mode.

  python server/parentlock_server.py demo --port 18770   (in another terminal)
  python tests/screenshots.py                             (needs Google Chrome and Pillow)
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageChops

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "docs", "screenshots")
CHROME = os.environ.get("CHROME", r"C:\Program Files\Google\Chrome\Application\chrome.exe")
BASE = "http://127.0.0.1:18770/"

SHOTS = [
    ("site-status", "only=status,actions", "light"),
    ("site-requests", "only=requests", "light"),
    ("site-today", "only=today,days", "light"),
    ("site-time-device", "only=time,device", "light"),
    ("site-apps", "only=apps&open=org.telegram.messenger", "light"),
    ("site-events", "only=events", "light"),
    ("site-dark", "only=status,actions,requests", "dark"),
]


def trim(path, bg):
    im = Image.open(path).convert("RGB")
    diff = ImageChops.difference(im, Image.new("RGB", im.size, bg))
    box = diff.getbbox()
    if box:
        pad = 24
        im = im.crop((0, 0, im.width, min(im.height, box[3] + pad)))
    im.save(path, optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, query, scheme in SHOTS:
        path = os.path.join(OUT, name + ".png")
        profile = tempfile.mkdtemp(prefix="pl-chrome-")
        args = [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
                f"--user-data-dir={profile}", "--window-size=520,3000", "--force-device-scale-factor=2",
                "--virtual-time-budget=9000", f"--screenshot={path}", BASE + "?" + query]
        args.insert(1, "--blink-settings=preferredColorScheme=" + ("0" if scheme == "dark" else "1"))
        if scheme == "dark":
            args.insert(1, "--force-dark-mode")
        subprocess.run(args, check=True, capture_output=True, timeout=120)
        trim(path, Image.open(path).convert("RGB").getpixel((2, Image.open(path).height - 2)))
        print("saved", path)


if __name__ == "__main__":
    sys.exit(main())
