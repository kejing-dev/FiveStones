"""Five Stones icon (design-package appIcon): kaya #E2B55F, a 3 x 3 ink grid, five ink-black cloud stones on the
lower-left -> upper-right diagonal and a thin teal #2FA69A light band under them. No text.

  uv run --system-certs --with pillow python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes app/src/main/res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon) and
mipmap-xxxhdpi/ic_spatial_launcher.png. The adaptive launcher icon is the vector drawable ic_launcher_fg.xml
(same drawing) on @color/ic_launcher_background.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 4
KAYA = (0xE2, 0xB5, 0x5F)
INK = (0x3A, 0x2A, 0x16)
TEAL = (0x2F, 0xA6, 0x9A)
STONE = (0x1C, 0x1F, 0x22)
HILITE = (0x6E, 0x78, 0x7C)

# Artwork in a 108-unit box (the adaptive icon grid); layers scale it to 1024.
GRID = [36, 54, 72]
STONES = [(30, 78), (42, 66), (54, 54), (66, 42), (78, 30)]
R = 7.2


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def layer_bg():
    def d(dr, k):
        dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=KAYA + (255,))
        w = 1.4 * k
        for g in GRID:
            dr.line((18 * k, g * k, 90 * k, g * k), fill=INK + (200,), width=round(w))
            dr.line((g * k, 18 * k, g * k, 90 * k), fill=INK + (200,), width=round(w))
    return big(d)


def layer_band():
    def d(dr, k):
        w = 1.7  # half width along the perpendicular (1, 1) / sqrt 2
        (x0, y0), (x1, y1) = STONES[0], STONES[-1]
        o = 6.2  # the band runs just below-right of the stone line
        a, b = (x0 + 2 + o, y0 - 2 + o + 4.5), (x1 + 2 + o + 4.5, y1 - 2 + o)
        pts = [(a[0] - w, a[1] - w), (a[0] + w, a[1] + w), (b[0] + w, b[1] + w), (b[0] - w, b[1] - w)]
        dr.polygon([(x * k, y * k) for x, y in pts], fill=TEAL + (255,))
    return big(d)


def layer_stones():
    def d(dr, k):
        for x, y in STONES:
            dr.ellipse(((x - R) * k, (y - R) * k, (x + R) * k, (y + R) * k), fill=STONE + (255,))
            dr.ellipse(((x - R * 0.55) * k, (y - R * 0.6) * k, (x - R * 0.05) * k, (y - R * 0.2) * k), fill=HILITE + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def flatten(layers):
    out = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def main():
    layers = [layer_bg(), layer_band(), layer_stones()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = flatten(layers)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        tiles = layers + [flat]
        sheet = Image.new("RGB", (w * len(tiles), w), (60, 64, 70))
        for i, t in enumerate(tiles):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
