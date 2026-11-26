#!/usr/bin/env python3
"""Rebuilds the Hydrogen icon sets from geometry (vector recreation of the supplied logo concepts).
Run:  python3 branding/make_icons.py        (needs Pillow + numpy)
Outputs: Apple asset catalog, Android adaptive-icon vectors, visionOS/Icon Composer layers, .ico/.icns, preview sheet."""
import json, os, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 4  # supersampling

# ── mark geometry in a 100×100 box ────────────────────────────────────────────
LEFT = (20, 8, 34, 72, 7)      # x0,y0,x1,y1,radius
RIGHT = (66, 28, 80, 92, 7)
LINE = ((22, 82), (78, 18), 7)  # p0, p1, width
NODES = [((22, 82), 8, 0), ((50, 50), 7, 1), ((78, 18), 9, 2)]  # centre, radius, colour index

def hexc(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

PALETTES = {
    # name: (left bar top, left bar bottom, right bar top, right bar bottom, node colours, line a, line b)
    "ios_light": ("#3B82F6", "#5B6CF2", "#8B5CF6", "#D946EF", ["#4F7CF0", "#6C6CF5", "#8B5CF6"], "#4F7CF0", "#8B5CF6"),
    "ios_dark":  ("#5B9BFF", "#7C7CFF", "#A66BFF", "#F05CFF", ["#6AA5FF", "#8A8AFF", "#B07CFF"], "#6AA5FF", "#B07CFF"),
    "android_light": ("#5FC9B5", "#6FB5E6", "#9DB5F2", "#7FE0CF", ["#5FC9B5", "#6C9BE8", "#8FD9C6"], "#5FC9B5", "#8FB4F0"),
    "android_dark":  ("#2FD6C0", "#3FA8D8", "#5B6FE0", "#35D6C2", ["#35D6C2", "#5B7FE8", "#2FD6C0"], "#35D6C2", "#5B7FE8"),
}

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def vgrad(w, h, top, bottom):
    t = np.linspace(0, 1, h)[:, None, None]
    a = np.array(top)[None, None, :]; b = np.array(bottom)[None, None, :]
    arr = (a + (b - a) * t).astype(np.uint8)
    arr = np.repeat(arr, w, axis=1)
    return Image.fromarray(arr, "RGB")

def dgrad(w, h, c0, c1):  # diagonal (top-left → bottom-right)
    x = np.linspace(0, 1, w)[None, :]; y = np.linspace(0, 1, h)[:, None]
    t = ((x + y) / 2)[:, :, None]
    a = np.array(c0)[None, None, :]; b = np.array(c1)[None, None, :]
    return Image.fromarray((a + (b - a) * t).astype(np.uint8), "RGB")

def render_mark(size, pal, mono=None, alpha=1.0):
    """Mark on a transparent square canvas of `size` px (mark fills it fully)."""
    n = size * S; k = n / 100
    lt, lb, rt, rb, nodes, la, lb2 = [hexc(x) if isinstance(x, str) else [hexc(c) for c in x] for x in pal]
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    def paint(mask, fill_img):
        layer = fill_img.convert("RGBA"); layer.putalpha(mask.point(lambda v: int(v * alpha)))
        out.alpha_composite(layer)

    def rr(box):
        x0, y0, x1, y1, r = box
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).rounded_rectangle([x0*k, y0*k, x1*k, y1*k], radius=r*k, fill=255)
        return m, (int(x0*k), int(y0*k), int(x1*k), int(y1*k))

    def solid(c): return Image.new("RGB", (n, n), mono or c)

    # bars with vertical gradients
    for box, top, bot in ((LEFT, lt, lb), (RIGHT, rt, rb)):
        m, (x0, y0, x1, y1) = rr(box)
        g = Image.new("RGB", (n, n), top)
        if not mono:
            strip = vgrad(n, max(1, y1 - y0), top, bot); g.paste(strip, (0, y0))
        else: g = solid(top)
        paint(m, g)
    # diagonal link
    (x0, y0), (x1, y1), w = LINE
    m = Image.new("L", (n, n), 0); d = ImageDraw.Draw(m)
    d.line([x0*k, y0*k, x1*k, y1*k], fill=255, width=int(w*k))
    for (cx, cy) in ((x0, y0), (x1, y1)): d.ellipse([(cx - w/2)*k, (cy - w/2)*k, (cx + w/2)*k, (cy + w/2)*k], fill=255)
    paint(m, solid(la) if mono else dgrad(n, n, la, lb2))
    # nodes
    for (cx, cy), r, ci in NODES:
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).ellipse([(cx - r)*k, (cy - r)*k, (cx + r)*k, (cy + r)*k], fill=255)
        paint(m, solid(nodes[ci]))
        ring = Image.new("L", (n, n), 0)  # thin light ring so nodes read against the bars
        ImageDraw.Draw(ring).ellipse([(cx - r - 1.2)*k, (cy - r - 1.2)*k, (cx + r + 1.2)*k, (cy + r + 1.2)*k], outline=255, width=int(1.2*k))
    return out.resize((size, size), Image.LANCZOS)

def place_mark(canvas, mark_px_ratio, pal, **kw):
    s = canvas.size[0]; ms = int(s * mark_px_ratio)
    mk = render_mark(ms, pal, **kw)
    canvas.alpha_composite(mk, ((s - ms) // 2, (s - ms) // 2))
    return canvas

# ── backgrounds ──────────────────────────────────────────────────────────────
def bg_light(s):  return vgrad(s, s, hexc("#FBF8F0"), hexc("#EFEBDD")).convert("RGBA")
def bg_dark(s):
    img = vgrad(s, s, hexc("#0B1030"), hexc("#16235A")).convert("RGBA")
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).ellipse([s*0.15, s*0.15, s*0.85, s*0.85], fill=90)
    img.paste(Image.new("RGBA", (s, s), (90, 80, 255, 255)), (0, 0), m.filter(ImageFilter.GaussianBlur(s * 0.12)))
    return img
def bg_glass(s):
    img = vgrad(s, s, hexc("#F4F8FF"), hexc("#DDE6F5")).convert("RGBA")
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).polygon([(0, 0), (s * 0.78, 0), (0, s * 0.78)], fill=120)
    img.paste(Image.new("RGBA", (s, s), (255, 255, 255, 255)), (0, 0), m.filter(ImageFilter.GaussianBlur(s * 0.03)))
    d2 = ImageDraw.Draw(img); d2.rectangle([0, 0, s - 1, s - 1], outline=(255, 255, 255, 200), width=max(2, s // 128))
    return img
def bg_tinted(s): return Image.new("RGBA", (s, s), (0, 0, 0, 255))

def squircle_mask(s, radius_ratio=0.225):
    m = Image.new("L", (s * 2, s * 2), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, s * 2 - 1, s * 2 - 1], radius=int(s * 2 * radius_ratio), fill=255)
    return m.resize((s, s), Image.LANCZOS)

def rounded(img, ratio=0.225):
    out = Image.new("RGBA", img.size, (0, 0, 0, 0)); out.paste(img, (0, 0), squircle_mask(img.size[0], ratio)); return out

def ios_icon(variant, s=1024):
    """Full-bleed square (the system applies the rounded mask and, on iOS/iPadOS/visionOS 26+, Liquid Glass)."""
    if variant == "light": return place_mark(bg_light(s), 0.62, PALETTES["ios_light"])
    if variant == "dark": return place_mark(bg_dark(s), 0.62, PALETTES["ios_dark"])
    if variant == "glass": return place_mark(bg_glass(s), 0.62, PALETTES["ios_light"], alpha=0.93)
    if variant == "tinted": return place_mark(bg_tinted(s), 0.62, PALETTES["ios_light"], mono=(235, 235, 235))
    raise ValueError(variant)

def mac_icon(s, variant="light"):
    """macOS style: 80% rounded tile with soft shadow on a transparent canvas."""
    tile = int(s * 0.80); base = ios_icon(variant, tile if tile >= 64 else 64).resize((tile, tile), Image.LANCZOS)
    base = rounded(base, 0.225)
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    sh = Image.new("RGBA", (s, s), (0, 0, 0, 0)); sm = Image.new("L", (tile, tile), 0)
    sh.paste((0, 0, 0, 90), ((s - tile) // 2, (s - tile) // 2 + int(s * 0.012)), squircle_mask(tile))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(max(1, s * 0.012))))
    canvas.alpha_composite(base, ((s - tile) // 2, (s - tile) // 2))
    return canvas

def android_round(variant, s=512):
    pal = PALETTES["android_" + variant]
    bg = hexc("#E6F3FA") if variant == "light" else hexc("#1B2733")
    img = Image.new("RGBA", (s, s), bg + (255,))
    place_mark(img, 0.50, pal)
    m = Image.new("L", (s * 2, s * 2), 0); ImageDraw.Draw(m).ellipse([0, 0, s * 2 - 1, s * 2 - 1], fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0)); out.paste(img, (0, 0), m.resize((s, s), Image.LANCZOS)); return out

# ── write files ──────────────────────────────────────────────────────────────
def save(img, *parts):
    p = os.path.join(ROOT, *parts); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p); return p

def apple_catalog():
    base = ("apple", "TheBrowser", "Assets.xcassets")
    images = [
        {"filename": "icon_1024_light.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "dark"}], "filename": "icon_1024_dark.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {"appearances": [{"appearance": "luminosity", "value": "tinted"}], "filename": "icon_1024_tinted.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
    ]
    save(ios_icon("light").convert("RGB"), *base, "AppIcon.appiconset", "icon_1024_light.png")
    save(ios_icon("dark").convert("RGB"), *base, "AppIcon.appiconset", "icon_1024_dark.png")
    save(ios_icon("tinted").convert("RGB"), *base, "AppIcon.appiconset", "icon_1024_tinted.png")
    for size in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = size * scale; fn = f"mac_{size}@{scale}x.png"
            save(mac_icon(px), *base, "AppIcon.appiconset", fn)
            images.append({"filename": fn, "idiom": "mac", "scale": f"{scale}x", "size": f"{size}x{size}"})
    json.dump({"images": images, "info": {"author": "xcode", "version": 1}}, open(os.path.join(ROOT, *base, "AppIcon.appiconset", "Contents.json"), "w"), indent=2)
    json.dump({"info": {"author": "xcode", "version": 1}}, open(os.path.join(ROOT, *base, "Contents.json"), "w"))
    # in-app logo (transparent) for start page / about
    save(render_mark(512, PALETTES["ios_light"]), *base, "HydrogenMark.imageset", "hydrogen_mark.png")
    json.dump({"images": [{"filename": "hydrogen_mark.png", "idiom": "universal"}], "info": {"author": "xcode", "version": 1}}, open(os.path.join(ROOT, *base, "HydrogenMark.imageset", "Contents.json"), "w"), indent=2)
    json.dump({"colors": [{"color": {"color-space": "srgb", "components": {"red": "0.424", "green": "0.361", "blue": "0.906", "alpha": "1.000"}}, "idiom": "universal"}], "info": {"author": "xcode", "version": 1}}, open(os.path.join(ROOT, *base, "AccentColor.colorset", "Contents.json"), "w") if os.makedirs(os.path.join(ROOT, *base, "AccentColor.colorset"), exist_ok=True) is None else None, indent=2)

def extras():
    # visionOS layered icon: drag these two 1024 PNGs into the visionOS App Icon slots (Back / Front)
    back = vgrad(1024, 1024, hexc("#0B1030"), hexc("#1B2A6B")).convert("RGBA")
    save(back, "branding", "visionos", "layer_back.png")
    save(render_mark(1024, PALETTES["ios_dark"]), "branding", "visionos", "layer_front.png")
    # Icon Composer (Liquid Glass) layers: background + foreground, for iOS/iPadOS/macOS/visionOS 26+
    save(bg_light(1024).convert("RGB"), "branding", "icon-composer", "background_light.png")
    save(bg_dark(1024).convert("RGB"), "branding", "icon-composer", "background_dark.png")
    save(render_mark(1024, PALETTES["ios_light"]), "branding", "icon-composer", "foreground_light.png")
    save(render_mark(1024, PALETTES["ios_dark"]), "branding", "icon-composer", "foreground_dark.png")
    # Windows / PC
    ico = mac_icon(256).convert("RGBA")
    ico.save(os.path.join(ROOT, "branding", "pc", "hydrogen.ico") if os.makedirs(os.path.join(ROOT, "branding", "pc"), exist_ok=True) is None else "", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    for s in (256, 512, 1024): save(ios_icon("light", s).convert("RGB") if s != 1024 else ios_icon("light").convert("RGB"), "branding", "pc", f"hydrogen_{s}.png")
    os.makedirs(os.path.join(ROOT, "branding", "macos"), exist_ok=True)
    mac_icon(1024).save(os.path.join(ROOT, "branding", "macos", "Hydrogen.icns"))
    # Android Play Store 512 + round previews
    save(android_round("light"), "branding", "android", "round_light.png"); save(android_round("dark"), "branding", "android", "round_dark.png")
    save(ios_icon("light", 512).convert("RGB"), "branding", "android", "play_store_512.png")

def preview():
    W, H = 1500, 820; sheet = Image.new("RGB", (W, H), (248, 249, 252)); d = ImageDraw.Draw(sheet)
    d.text((W // 2 - 90, 24), "HYDROGEN  ·  generated icon sets", fill=(60, 66, 80))
    x = 60
    for label, v in (("iOS light", "light"), ("Liquid Glass (light)", "glass"), ("Liquid Glass (dark)", "dark"), ("Tinted", "tinted")):
        sheet.paste(rounded(ios_icon(v, 256), 0.225), (x, 90), rounded(ios_icon(v, 256), 0.225)); d.text((x, 360), label, fill=(60, 66, 80)); x += 290
    x = 60
    for label, v in (("Android light", "light"), ("Android dark", "dark")):
        r = android_round(v, 256); sheet.paste(r, (x, 440), r); d.text((x, 710), label, fill=(60, 66, 80)); x += 290
    m = mac_icon(256); sheet.paste(m, (x, 440), m); d.text((x, 710), "macOS", fill=(60, 66, 80))
    save(sheet, "branding", "preview.png")

if __name__ == "__main__":
    apple_catalog(); extras(); preview(); print("icons written")
