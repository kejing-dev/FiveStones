"""Five Stones: empty space in the control window of an intent screenshot (28° right turn, 1920x1080; window rect fixed).

build.md D44 measure: blank = (content top - window top) + (window bottom - content bottom), as a share of the window
height, where the content block runs from the first to the last row that has anything on it; plus the largest empty band
inside the content block (must stay <= 20 %).
  uv run --with pillow python3 tools/blank_band.py <dir with *-window.png>
"""
import sys, glob, statistics
from PIL import Image
RECT = (847, 426, 1107, 722)
for path in sorted(glob.glob(sys.argv[1] + '/*-window.png')):
    im = Image.open(path).convert('L').crop(RECT)
    w, h = im.size
    m = int(w * 0.07)
    rows = list(range(int(h * 0.03), int(h * 0.97)))
    blank = []
    for y in rows:
        px = [im.getpixel((x, y)) for x in range(m, w - m)]
        med = statistics.median(px)
        blank.append(max(abs(v - med) for v in px) < 14)
    H = len(blank)
    first = next((i for i, b in enumerate(blank) if not b), 0)
    last = H - 1 - next((i for i, b in enumerate(reversed(blank)) if not b), 0)
    outer = (first + (H - 1 - last)) / H
    run = best = 0
    for b in blank[first:last + 1]:
        run = run + 1 if b else 0; best = max(best, run)
    print("%-38s top+bottom blank %3.0f%%   largest inner band %3.0f%%" % (path.split('/')[-1], 100 * outer, 100 * best / H))
