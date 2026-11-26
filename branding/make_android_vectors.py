#!/usr/bin/env python3
"""Writes the Hydrogen adaptive-icon VectorDrawables (colour, night colour, monochrome) and the in-app mark."""
import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Material 3 Expressive: bolder strokes and fully rounded ends (radius = half the bar width), larger nodes.
LEFT = (18, 8, 36, 72, 9); RIGHT = (64, 28, 82, 92, 9)
LINE = ((22, 82), (78, 18), 9)
NODES = [((22, 82), 10, 0), ((50, 50), 9, 1), ((78, 18), 11, 2)]
# Adaptive icon: every pixel of the mark must sit inside the 66dp safe-zone circle of the 108dp canvas.
# Farthest point of the mark from its centre (50,50) is about 53.5 units, so scale = 33 / 53.5 -> 0.6 (radius 32dp). Centre = 54dp.
SC = 0.6; TR = 54 - 50 * SC
PAL = {
    "day": dict(l=("#5FC9B5", "#6FB5E6"), r=("#9DB5F2", "#7FE0CF"), nodes=["#5FC9B5", "#6C9BE8", "#8FD9C6"], line=("#5FC9B5", "#8FB4F0")),
    "night": dict(l=("#2FD6C0", "#3FA8D8"), r=("#5B6FE0", "#35D6C2"), nodes=["#35D6C2", "#5B7FE8", "#2FD6C0"], line=("#35D6C2", "#5B7FE8")),
    "brand": dict(l=("#3B82F6", "#5B6CF2"), r=("#8B5CF6", "#D946EF"), nodes=["#4F7CF0", "#6C6CF5", "#8B5CF6"], line=("#4F7CF0", "#8B5CF6")),
}

def rr(x0, y0, x1, y1, r):
    w, h = x1 - x0, y1 - y0
    return f"M{x0+r},{y0} h{w-2*r} a{r},{r} 0 0 1 {r},{r} v{h-2*r} a{r},{r} 0 0 1 -{r},{r} h-{w-2*r} a{r},{r} 0 0 1 -{r},-{r} v-{h-2*r} a{r},{r} 0 0 1 {r},-{r} z"

def circ(cx, cy, r): return f"M{cx-r},{cy} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 -{2*r},0 z"

def grad(attr, x0, y0, x1, y1, c0, c1):
    return (f'        <aapt:attr name="android:{attr}">\n            <gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" android:endX="{x1}" android:endY="{y1}">\n'
            f'                <item android:offset="0" android:color="{c0}" />\n                <item android:offset="1" android:color="{c1}" />\n            </gradient>\n        </aapt:attr>\n')

def paths(p, mono=False):
    out = []
    for (x0, y0, x1, y1, r), (c0, c1) in ((LEFT, p["l"]), (RIGHT, p["r"])):
        if mono: out.append(f'    <path android:fillColor="#000000" android:pathData="{rr(x0, y0, x1, y1, r)}" />\n')
        else: out.append(f'    <path android:pathData="{rr(x0, y0, x1, y1, r)}">\n' + grad("fillColor", 0, y0, 0, y1, c0, c1) + '    </path>\n')
    (ax, ay), (bx, by), w = LINE
    if mono: out.append(f'    <path android:pathData="M{ax},{ay} L{bx},{by}" android:strokeColor="#000000" android:strokeWidth="{w}" android:strokeLineCap="round" />\n')
    else: out.append(f'    <path android:pathData="M{ax},{ay} L{bx},{by}" android:strokeWidth="{w}" android:strokeLineCap="round">\n' + grad("strokeColor", ax, ay, bx, by, *p["line"]) + '    </path>\n')
    for (cx, cy), r, i in NODES:
        out.append(f'    <path android:fillColor="{"#000000" if mono else p["nodes"][i]}" android:pathData="{circ(cx, cy, r)}" />\n')
    return "".join(out)

def vector(body, vp, wh, group=None):
    g0 = g1 = ""
    if group: g0 = f'    <group android:translateX="{group[0]}" android:translateY="{group[0]}" android:scaleX="{group[1]}" android:scaleY="{group[1]}">\n'; g1 = "    </group>\n"
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{wh}dp" android:height="{wh}dp" android:viewportWidth="{vp}" android:viewportHeight="{vp}">\n{g0}{body}{g1}</vector>\n')

def write(rel, text):
    p = os.path.join(RES, rel); os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "w").write(text)

write("drawable/ic_launcher_foreground.xml", vector(paths(PAL["day"]), 108, 108, (TR, SC)))
write("drawable-night/ic_launcher_foreground.xml", vector(paths(PAL["night"]), 108, 108, (TR, SC)))
write("drawable/ic_launcher_monochrome.xml", vector(paths(PAL["day"], mono=True), 108, 108, (TR, SC)))
# Android 12+ splash screen: 288dp canvas, icon must fit inside a 192dp circle (radius 96). Mark radius 53.5 * 1.6 = 85.6.
write("drawable/ic_splash_icon.xml", vector(paths(PAL["day"]), 288, 288, (144 - 50 * 1.6, 1.6)))
write("drawable-night/ic_splash_icon.xml", vector(paths(PAL["night"]), 288, 288, (144 - 50 * 1.6, 1.6)))
write("values-v31/themes.xml", '''<resources>
    <style name="Theme.TheBrowser" parent="android:Theme.DeviceDefault.NoActionBar">
        <item name="android:windowSplashScreenBackground">@color/ic_launcher_background</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_splash_icon</item>
    </style>
</resources>
''')
write("drawable/ic_hydrogen_mark.xml", vector(paths(PAL["brand"]), 100, 100))
write("values/colors.xml", '<resources>\n    <color name="ic_launcher_background">#E6F3FA</color>\n</resources>\n')
write("values-night/colors.xml", '<resources>\n    <color name="ic_launcher_background">#1B2733</color>\n</resources>\n')
adaptive = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n'
write("mipmap-anydpi-v26/ic_launcher.xml", adaptive)
write("mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
print("android vectors written")
