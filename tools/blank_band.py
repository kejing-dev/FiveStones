"""Five Stones: largest empty band inside the control window of an intent screenshot (window rect fixed for the 28° right turn at 1920x1080)."""
import sys, glob, statistics
from PIL import Image
RECT = (847, 426, 1107, 722)
for path in sorted(glob.glob(sys.argv[1] + '/*-window.png')):
    im = Image.open(path).convert('L').crop(RECT)
    w, h = im.size
    m = int(w * 0.07)
    blank = []
    for y in range(int(h * 0.03), int(h * 0.97)):
        px = [im.getpixel((x, y)) for x in range(m, w - m)]
        med = statistics.median(px)
        blank.append(max(abs(v - med) for v in px) < 14)
    H = len(blank); run = best = 0
    for b in blank:
        run = run + 1 if b else 0; best = max(best, run)
    print("%-36s largest blank band %3.0f%% of window height" % (path.split('/')[-1], 100 * best / H))
