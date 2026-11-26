#!/usr/bin/env python3
"""Renders the Android adaptive icon (same geometry as make_android_vectors.py) under different launcher masks with the
66dp safe-zone circle, plus a themed (monochrome) preview and the 512px Play Store icon. Needs Pillow."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_android_vectors as G
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
K = 8                       # pixels per dp
N = 108 * K

def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def grad_img(w, h, c0, c1):
    im = Image.new("RGB", (w, h)); d = ImageDraw.Draw(im)
    a, b = hexrgb(c0), hexrgb(c1)
    for y in range(h):
        t = y / max(1, h - 1); d.line([(0, y), (w, y)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)))
    return im

def foreground(p, mono_color=None):
    """Transparent 108dp layer: the mark with the same translate/scale as the VectorDrawable."""
    S = N * 2
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    u = S / 108 * G.SC            # pixels per mark unit
    ox = oy = S / 108 * G.TR
    P = lambda x, y: (ox + x * u, oy + y * u)
    def put(mask, fill):
        layer.paste(fill, (0, 0), mask)
    def rrmask(x0, y0, x1, y1, r):
        m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).rounded_rectangle([*P(x0, y0), *P(x1, y1)], radius=r * u, fill=255); return m
    for (x0, y0, x1, y1, r), (c0, c1) in ((G.LEFT, p["l"]), (G.RIGHT, p["r"])):
        fill = Image.new("RGB", (S, S), mono_color) if mono_color else grad_img(S, S, c0, c1).resize((S, S))
        if not mono_color:  # gradient spans the bar's own height, as in the VectorDrawable
            top, bot = int(P(0, y0)[1]), int(P(0, y1)[1]); g = grad_img(S, max(1, bot - top), c0, c1)
            fill = Image.new("RGB", (S, S), hexrgb(c0)); fill.paste(g, (0, top)); 
            if bot < S: fill.paste(Image.new("RGB", (S, S - bot), hexrgb(c1)), (0, bot))
        put(rrmask(x0, y0, x1, y1, r), fill)
    (ax, ay), (bx, by), w = G.LINE
    lm = Image.new("L", (S, S), 0); ld = ImageDraw.Draw(lm); ld.line([P(ax, ay), P(bx, by)], fill=255, width=int(w * u))
    for pt in (P(ax, ay), P(bx, by)): ld.ellipse([pt[0] - w * u / 2, pt[1] - w * u / 2, pt[0] + w * u / 2, pt[1] + w * u / 2], fill=255)
    c0, c1 = p["line"]; lf = Image.new("RGB", (S, S), mono_color or hexrgb(c0))
    if not mono_color:  # diagonal gradient
        for i in range(0, 256):
            pass
    put(lm, lf)
    for (cx, cy), r, i in G.NODES:
        m = Image.new("L", (S, S), 0); x, y = P(cx, cy); ImageDraw.Draw(m).ellipse([x - r * u, y - r * u, x + r * u, y + r * u], fill=255)
        put(m, Image.new("RGB", (S, S), mono_color or hexrgb(p["nodes"][i])))
    return layer.resize((N, N), Image.LANCZOS)

def compose(p, bg, mono_color=None, bg_hex=None):
    base = Image.new("RGBA", (N, N), hexrgb(bg) + (255,))
    base.alpha_composite(foreground(p, mono_color)); return base

def masked(img, shape):
    m = Image.new("L", (N * 2, N * 2), 0); d = ImageDraw.Draw(m); inset = N * 2 * (108 - 72) / 216   # visible 72dp window
    box = [inset, inset, N * 2 - inset, N * 2 - inset]
    if shape == "circle": d.ellipse(box, fill=255)
    elif shape == "squircle": d.rounded_rectangle(box, radius=(box[2] - box[0]) * 0.36, fill=255)
    elif shape == "rounded": d.rounded_rectangle(box, radius=(box[2] - box[0]) * 0.18, fill=255)
    m = m.resize((N, N), Image.LANCZOS); out = Image.new("RGBA", (N, N), (0, 0, 0, 0)); out.paste(img, (0, 0), m)
    return out.crop((int(inset / 2), int(inset / 2), N - int(inset / 2), N - int(inset / 2)))

def safe_overlay(shape_img):
    im = shape_img.copy(); d = ImageDraw.Draw(im); w = im.width; c = w / 2; r = 33 * K * w / (72 * K)
    d.ellipse([c - r, c - r, c + r, c + r], outline=(230, 60, 60, 255), width=3); return im

if __name__ == "__main__":
    day = compose(G.PAL["day"], "#E6F3FA"); night = compose(G.PAL["night"], "#1B2733")
    mono_bg = "#CFE9E4"; mono = compose(G.PAL["day"], mono_bg, mono_color="#1F3A36")
    tiles = [("Light · circle", masked(day, "circle")), ("Light · squircle", masked(day, "squircle")), ("Dark · circle", masked(night, "circle")),
             ("Dark · rounded square", masked(night, "rounded")), ("Themed (monochrome)", masked(mono, "circle")), ("Safe zone (red = 66dp)", safe_overlay(masked(day, "circle")))]
    T = 300; sheet = Image.new("RGB", (T * 3 + 80, T * 2 + 140), "#F4F7FB"); d = ImageDraw.Draw(sheet)
    d.text((20, 14), "HYDROGEN · Android adaptive icon · Material 3 Expressive check", fill="#222")
    for i, (name, im) in enumerate(tiles):
        t = im.resize((T - 40, T - 40), Image.LANCZOS); x = 20 + (i % 3) * (T + 20); y = 50 + (i // 3) * (T + 50)
        sheet.paste(t, (x, y), t); d.text((x, y + T - 32), name, fill="#222")
    sheet.save(os.path.join(OUT, "android_preview.png"))
    play = compose(G.PAL["day"], "#E6F3FA").convert("RGB").resize((512, 512), Image.LANCZOS)   # full-bleed square, no transparency, store applies its own mask
    play.save(os.path.join(OUT, "playstore_icon_512.png")); print("preview + 512px Play Store icon written")
