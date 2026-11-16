# Copyright 2026 the Shelldon SSH contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds the launcher icon layers from branding/source/turtle-original.png.
# Run from an empty scratch directory:  python3 -I /path/to/branding/build_icon.py [safe-zone-radius-px]
# Writes foreground_432.png, monochrome_432.png, legacy_*.png, store_512.png and
# preview.png into the current directory. Needs Pillow, numpy and scipy (tooling only,
# nothing from here ships in the app).
import numpy as np, math, sys
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage as ndi

import os
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'source', 'turtle-original.png')
BG = (12, 25, 22)
fg = Image.open(SRC).convert('RGBA')
arr = np.array(fg).astype(np.float32)
a = arr[..., 3] / 255.0
r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
mx = arr[..., :3].max(axis=2)
yy = np.arange(432)[:, None] * np.ones((1, 432))

# 1. remove the baked-in floor shadow (dark, grey-green, below the feet)
shadow = (yy >= 270) & (mx <= 100) & (b >= 0.5 * g)
shadow = ndi.binary_dilation(shadow, iterations=2) & (yy >= 270) & (mx <= 110) & (b >= 0.3 * g)
a2 = a.copy()
a2[shadow] = 0
# drop tiny leftover specks
lab, n = ndi.label(a2 > 0.1)
sizes = ndi.sum(np.ones_like(a2), lab, range(1, n + 1))
keep = np.zeros(n + 1, bool)
keep[1:] = sizes >= 0.02 * sizes.max()
a2 = a2 * keep[lab]
arr[..., 3] = a2 * 255
clean = Image.fromarray(arr.astype(np.uint8), 'RGBA')
clean.save('turtle_clean.png')

# 2. fit into the adaptive-icon safe circle (66dp of 108dp -> r = 132px on 432)
ys, xs = np.nonzero(a2 > 0.5)
pts = np.stack([xs, ys], 1).astype(np.float32)
def radius(c): return np.sqrt(((pts - c) ** 2).sum(1)).max()
c = np.array([(xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2], np.float32)
step = 8.0
rad = radius(c)
while step > 0.25:
    improved = False
    for d in [(1,0),(-1,0),(0,1),(0,-1),(1,1),(1,-1),(-1,1),(-1,-1)]:
        c2 = c + step * np.array(d, np.float32)
        r2 = radius(c2)
        if r2 < rad - 1e-3:
            c, rad, improved = c2, r2, True
    if not improved: step /= 2
TARGET_R = float(sys.argv[1]) if len(sys.argv) > 1 else 136.0
scale = TARGET_R / rad
print('enclosing radius', rad, 'centre', c, 'scale', scale)
S = 432
layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
big = clean.resize((round(S * scale), round(S * scale)), Image.LANCZOS)
cx, cy = c * scale
# centre the enclosing circle in the canvas
layer.alpha_composite(big, (round(S / 2 - cx), round(S / 2 - cy)))
layer.save('foreground_432.png')

# 3. monochrome: silhouette with the # and the eye cut out
la = np.array(layer).astype(np.float32)
A = la[..., 3] / 255.0
R, G, B = la[..., 0], la[..., 1], la[..., 2]
cream = np.clip((np.minimum(np.minimum(R, G), B) - 120) / 40.0, 0, 1)      # the # (and eye glint)
pupil = np.clip((70 - la[..., :3].max(axis=2)) / 25.0, 0, 1)                # dark eye
# only carve pupil in the head region (left third, upper half), not elsewhere
H, W = A.shape
hy, hx = np.mgrid[0:H, 0:W]
head = (hx < W * 0.46) & (hy < H * 0.55)
carve = np.maximum(cream, pupil * head)
carve = ndi.gaussian_filter(carve, 0.6)
lum = 0.3 * R + 0.59 * G + 0.11 * B
tone = np.clip((lum - 45) / 130.0, 0, 1)
M = A * (0.58 + 0.42 * tone) * (1 - np.clip(carve, 0, 1))
mono = np.zeros((H, W, 4), np.uint8)
mono[..., :3] = 255
mono[..., 3] = (M * 255).astype(np.uint8)
Image.fromarray(mono, 'RGBA').save('monochrome_432.png')

# 4. legacy icons: rounded square and circle on the background colour
def legacy(size, shape):
    base = Image.new('RGBA', (size, size), BG + (255,))
    fgs = layer.resize((size, size), Image.LANCZOS)
    # legacy icons show the full canvas; zoom the turtle ~1.25x to fill like a classic icon
    z = 1.25
    zs = round(size * z)
    fgz = layer.resize((zs, zs), Image.LANCZOS)
    base.alpha_composite(fgz, (round((size - zs) / 2), round((size - zs) / 2)))
    mask = Image.new('L', (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    if shape == 'round':
        d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * 0.22), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    base.putalpha(mask)
    return base
for name, sz in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    legacy(sz, 'square').save(f'legacy_{name}.png', optimize=True)
    legacy(sz, 'round').save(f'legacy_round_{name}.png', optimize=True)
legacy(512, 'square').save('store_512.png', optimize=True)

# previews: adaptive masks over the background (circle, squircle-ish), plus monochrome tinted
def preview(layer_img, bgcol, mask_kind, size=216, fgcol=None):
    S2 = 432
    base = Image.new('RGBA', (S2, S2), bgcol + (255,))
    l = layer_img
    if fgcol:
        l = Image.new('RGBA', l.size, fgcol + (0,)); l.putalpha(layer_img.split()[3])
    base.alpha_composite(l)
    # visible window = central 72/108
    vis = round(S2 * 72 / 108); off = (S2 - vis) // 2
    base = base.crop((off, off, off + vis, off + vis)).resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0); d = ImageDraw.Draw(m)
    if mask_kind == 'circle': d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else: d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * 0.3), fill=255)
    base.putalpha(m.resize((size, size), Image.LANCZOS))
    return base
sheet = Image.new('RGBA', (216 * 4 + 50, 216 * 2 + 30), (236, 236, 236, 255))
x = 10
items = [preview(layer, BG, 'circle'), preview(layer, BG, 'squircle'),
         preview(Image.fromarray(mono, 'RGBA'), (222, 232, 224), 'circle', fgcol=(34, 78, 52)),
         preview(Image.fromarray(mono, 'RGBA'), (30, 40, 34), 'squircle', fgcol=(160, 230, 180))]
for it in items:
    sheet.alpha_composite(it, (x, 10)); x += 226
sheet.alpha_composite(Image.open('legacy_xxxhdpi.png'), (10, 250))
sheet.alpha_composite(Image.open('legacy_round_xxxhdpi.png'), (220, 250))
sheet.convert('RGB').save('preview.png')
