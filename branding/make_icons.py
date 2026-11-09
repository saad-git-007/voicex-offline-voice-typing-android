#!/usr/bin/env python3
"""VoiceX logo generator: one geometry -> SVG, Android vector drawables, PNGs and mask previews.

The mark (v3, "minimal line mic"): a white line-art microphone (capsule, cradle, stem, base, all one stroke
weight with round caps) on a full-bleed violet -> blue gradient with a soft highlight. A small solid dot inside the
capsule is the "live" indicator, and the same dot VoiceX floats above the keyboard. No dark frame: the gradient
fills every launcher mask edge to edge.

Every shape is built from M/L/C/Z commands only, so the Android vector, the SVG and the cairo render are
guaranteed to be the same drawing.

Usage:  python3 branding/make_icons.py            (from the project root; needs pycairo + Pillow)
Writes: branding/*.svg, branding/voicex-512.png, branding/contrast.txt, branding/previews/*.png,
        app/src/main/res/drawable/{ic_launcher_*,ic_stat_voicex,voicex_mark}.xml, mipmap-*/ic_launcher*.png
"""
import math
import os
import sys

import cairo
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BR = os.path.join(ROOT, "branding")

# ---- palette (theme tokens; mirrored in res/values/colors.xml and ui/theme/Theme.kt) -------------------------
VIOLET = "#6C4CF1"  # gradient start, primary
BLUE = "#2F6BF0"  # gradient end (#3A7BFF darkened to keep white text >= 4.5:1)
INK = "#16132E"  # icon background / splash background / dark surface
WHITE = "#FFFFFF"

# ---- geometry, in the 108 x 108 adaptive-icon canvas (safe zone: circle r = 33 around the centre) -------------
C = 54.0
STROKE = 3.6  # one stroke weight for the whole mic
SCALE = 1.08  # drawn in a 108 box, then scaled around the centre and shifted so the mic is vertically centred
SHIFT_Y = 5.5
HIGHLIGHT = (30.0, 24.0, 60.0)  # soft white radial highlight: centre x, y, radius
K = 0.5522847498  # cubic approximation of a quarter circle


# ---- path building (commands: ('M',x,y) ('L',x,y) ('C',x1,y1,x2,y2,x,y) ('Z',)) -------------------------------
def arc(cx, cy, r, a0, a1, move=True):
    """Arc from angle a0 to a1 (degrees, y down: 0 = right, 90 = down) as cubic segments of <= 90 degrees."""
    cmds = []
    n = max(1, math.ceil(abs(a1 - a0) / 90))
    step = math.radians((a1 - a0) / n)
    a = math.radians(a0)
    if move:
        cmds.append(("M", cx + r * math.cos(a), cy + r * math.sin(a)))
    for _ in range(n):
        b = a + step
        h = 4 / 3 * math.tan((b - a) / 4) * r
        cmds.append(
            (
                "C",
                cx + r * math.cos(a) - h * math.sin(a),
                cy + r * math.sin(a) + h * math.cos(a),
                cx + r * math.cos(b) + h * math.sin(b),
                cy + r * math.sin(b) - h * math.cos(b),
                cx + r * math.cos(b),
                cy + r * math.sin(b),
            )
        )
        a = b
    return cmds


def circle(cx, cy, r):
    return arc(cx, cy, r, 0, 360) + [("Z",)]


def rrect(x, y, w, h, r):
    """Rounded rectangle, clockwise."""
    r = min(r, w / 2, h / 2)
    cmds = [("M", x + r, y), ("L", x + w - r, y)]
    cmds += arc(x + w - r, y + r, r, -90, 0, move=False)
    cmds += [("L", x + w, y + h - r)]
    cmds += arc(x + w - r, y + h - r, r, 0, 90, move=False)
    cmds += [("L", x + r, y + h)]
    cmds += arc(x + r, y + h - r, r, 90, 180, move=False)
    cmds += [("L", x, y + r)]
    cmds += arc(x + r, y + r, r, 180, 270, move=False)
    return cmds + [("Z",)]


def line(x0, y0, x1, y1):
    return [("M", x0, y0), ("L", x1, y1)]


def transform(cmds, s, dx, dy):
    out = []
    for c in cmds:
        if c[0] == "Z":
            out.append(c)
        else:
            v = list(c[1:])
            out.append((c[0], *[v[i] * s + (dx if i % 2 == 0 else dy) for i in range(len(v))]))
    return out


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def d(cmds):
    parts = []
    for c in cmds:
        parts.append(c[0] + ("" if c[0] == "Z" else ",".join(f(v) for v in c[1:])))
    return " ".join(parts)


# ---- the mark ---------------------------------------------------------------------------------------------------
def mark_shapes():
    """[(cmds, kind, width, alpha, evenodd)] in the 108 canvas: the mic only (the background is its own layer)."""
    capsule = rrect(C - 8, 28, 16, 26, 8)
    shapes = [
        (capsule, "stroke", STROKE, 1.0, False),
        (circle(C, 36.5, 2.9), "fill", 0, 1.0, False),  # the "live" dot
        (arc(C, 45, 15.5, 8, 172), "stroke", STROKE, 1.0, False),  # cradle
        (line(C, 60.5, C, 69), "stroke", STROKE, 1.0, False),  # stem
        (line(C - 5.5, 69, C + 5.5, 69), "stroke", STROKE, 1.0, False),  # base
    ]
    # scale around the centre, then centre vertically (the mic spans y 26.2..70.8 before this)
    s = SCALE
    return [(transform(cm, s, C - C * s, C - C * s + SHIFT_Y), k, w * s, a, eo) for (cm, k, w, a, eo) in shapes]


# ---- contrast (WCAG 2.x) ----------------------------------------------------------------------------------------
def lum(hexc):
    c = [int(hexc[i : i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def contrast(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True)
    return (la + 0.05) / (lb + 0.05)


# ---- writers -----------------------------------------------------------------------------------------------------
def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)


def vector(paths, viewport=108, size=108, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    body = "\n".join(paths)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/make_icons.py: edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}
    android:width="{size}dp" android:height="{size}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
{body}
</vector>
"""


def v_background():
    hx, hy, hr = HIGHLIGHT
    return f"""    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"
                android:startColor="#FF{VIOLET[1:]}" android:endColor="#FF{BLUE[1:]}" />
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{f(hx)}" android:centerY="{f(hy)}" android:gradientRadius="{f(hr)}"
                android:startColor="#38FFFFFF" android:endColor="#00FFFFFF" />
        </aapt:attr>
    </path>"""


def v_shape(cmds, kind, width, alpha, evenodd, color="#FFFFFFFF"):
    if kind == "fill":
        rule = ' android:fillType="evenOdd"' if evenodd else ""
        return f'    <path android:pathData="{d(cmds)}" android:fillColor="{color}" android:fillAlpha="{f(alpha)}"{rule} />'
    return (
        f'    <path android:pathData="{d(cmds)}" android:strokeColor="{color}" android:strokeAlpha="{f(alpha)}" '
        f'android:strokeWidth="{f(width)}" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />'
    )


def svg_shape(cmds, kind, width, alpha, evenodd, color=WHITE):
    if kind == "fill":
        return f'<path d="{d(cmds)}" fill="{color}" fill-opacity="{f(alpha)}"{" fill-rule=\"evenodd\"" if evenodd else ""}/>'
    return f'<path d="{d(cmds)}" fill="none" stroke="{color}" stroke-opacity="{f(alpha)}" stroke-width="{f(width)}" stroke-linecap="round" stroke-linejoin="round"/>'


def cairo_path(cr, cmds):
    for c in cmds:
        if c[0] == "M":
            cr.move_to(c[1], c[2])
        elif c[0] == "L":
            cr.line_to(c[1], c[2])
        elif c[0] == "C":
            cr.curve_to(*c[1:])
        else:
            cr.close_path()


def hexrgb(h):
    return tuple(int(h[i : i + 2], 16) / 255 for i in (1, 3, 5))


def main():
    shapes = mark_shapes()
    hx, hy, hr = HIGHLIGHT

    # ---- Android vector drawables ---------------------------------------------------------------------------
    write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), vector([v_background()], aapt=True))
    mic = [v_shape(*sh) for sh in shapes]
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), vector(mic))
    # the whole icon in one drawable: splash screen, wizard header (shown clipped to a circle)
    write(os.path.join(RES, "drawable", "voicex_mark.xml"), vector([v_background()] + mic, aapt=True))
    # themed icon: alpha-only silhouette (the launcher tints it)
    write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), vector(mic))
    # notification small icon: the mic alone, white, 24 dp, transparent background (mic spans ~49 units -> 22 dp)
    s = 22 / 50.0
    stat = [v_shape(transform(cm, s, 12 - C * s, 12 - C * s), k, w * s * 1.3, 1.0, eo) for (cm, k, w, _, eo) in shapes]
    write(os.path.join(RES, "drawable", "ic_stat_voicex.xml"), vector(stat, viewport=24, size=24))

    # ---- SVG sources ------------------------------------------------------------------------------------------
    defs = (
        f'<defs><linearGradient id="g" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{VIOLET}"/><stop offset="1" stop-color="{BLUE}"/></linearGradient>'
        f'<radialGradient id="h" cx="{f(hx)}" cy="{f(hy)}" r="{f(hr)}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff" stop-opacity="0.22"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
    )
    bg = '<rect width="108" height="108" fill="url(#g)"/><rect width="108" height="108" fill="url(#h)"/>'
    body = "".join(svg_shape(*sh) for sh in shapes)
    write(os.path.join(BR, "voicex-logo.svg"), f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">{defs}{bg}{body}</svg>\n')
    write(os.path.join(BR, "voicex-mark.svg"), f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512">{defs}<circle cx="54" cy="54" r="36" fill="url(#g)"/><circle cx="54" cy="54" r="36" fill="url(#h)"/>{body}</svg>\n')
    write(os.path.join(BR, "voicex-monochrome.svg"), f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">{"".join(svg_shape(cm, k, w, 1.0, eo, "#000") for (cm, k, w, _, eo) in shapes)}</svg>\n')

    # ---- raster renders ---------------------------------------------------------------------------------------
    def render(px, out, mono=None):
        """Full 108-unit canvas at px x px. mono = (bg, fg) renders the themed-icon look."""
        surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, px, px)
        cr = cairo.Context(surf)
        cr.scale(px / 108, px / 108)
        cr.set_line_cap(cairo.LINE_CAP_ROUND)
        cr.set_line_join(cairo.LINE_JOIN_ROUND)
        if mono:
            cr.set_source_rgb(*hexrgb(mono[0]))
            cr.paint()
            color = hexrgb(mono[1])
        else:
            grad = cairo.LinearGradient(0, 0, 108, 108)
            grad.add_color_stop_rgb(0, *hexrgb(VIOLET))
            grad.add_color_stop_rgb(1, *hexrgb(BLUE))
            cr.set_source(grad)
            cr.paint()
            hl = cairo.RadialGradient(hx, hy, 0, hx, hy, hr)
            hl.add_color_stop_rgba(0, 1, 1, 1, 0.22)
            hl.add_color_stop_rgba(1, 1, 1, 1, 0)
            cr.set_source(hl)
            cr.paint()
            color = (1, 1, 1)
        for cmds, kind, width, alpha, evenodd in shapes:
            cr.new_path()
            cairo_path(cr, cmds)
            cr.set_source_rgba(*color, 1.0 if mono else alpha)
            if kind == "fill":
                cr.set_fill_rule(cairo.FILL_RULE_EVEN_ODD if evenodd else cairo.FILL_RULE_WINDING)
                cr.fill()
            else:
                cr.set_line_width(width)
                cr.stroke()
        surf.write_to_png(out)

    tmp = os.path.join(BR, "previews", "_full.png")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)

    def masked(px, shape, mono=None):
        """What a launcher shows: the middle 72/108 of the canvas, clipped to a mask."""
        big = px * 108 // 72
        render(big, tmp, mono)
        im = Image.open(tmp).convert("RGBA")
        off = (big - px) // 2
        im = im.crop((off, off, off + px, off + px))
        m = px * 4
        mask = Image.new("L", (m, m), 0)
        dr = ImageDraw.Draw(mask)
        if shape == "circle":
            dr.ellipse((0, 0, m - 1, m - 1), fill=255)
        elif shape == "squircle":
            n = 5.0  # superellipse, close to One UI / Pixel squircles
            pts = []
            for i in range(720):
                t = 2 * math.pi * i / 720
                ct, st = math.cos(t), math.sin(t)
                pts.append(((math.copysign(abs(ct) ** (2 / n), ct) + 1) * (m - 1) / 2, (math.copysign(abs(st) ** (2 / n), st) + 1) * (m - 1) / 2))
            dr.polygon(pts, fill=255)
        else:
            dr.rounded_rectangle((0, 0, m - 1, m - 1), radius=m * 0.18, fill=255)
        mask = mask.resize((px, px), Image.LANCZOS)
        out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        out.paste(im, (0, 0), mask)
        return out

    render(512, os.path.join(BR, "voicex-512.png"))
    for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        dd = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(dd, exist_ok=True)
        masked(px, "rounded").save(os.path.join(dd, "ic_launcher.png"))
        masked(px, "circle").save(os.path.join(dd, "ic_launcher_round.png"))

    light_theme = ("#D9E2FF", "#1A2C61")
    sheet = Image.new("RGBA", (3 * 170 + 20, 260), (246, 244, 255, 255))
    ds = ImageDraw.Draw(sheet)
    for i, (name, shape, mono) in enumerate([("circle", "circle", None), ("squircle", "squircle", None), ("themed (mono)", "circle", light_theme)]):
        x = 20 + i * 170
        big, small = masked(144, shape, mono), masked(48, shape, mono)
        sheet.paste(big, (x, 20), big)
        sheet.paste(small, (x + 48, 180), small)
        ds.text((x, 168), name, fill=(40, 30, 80, 255))
        big.save(os.path.join(BR, "previews", f"icon-48dp-xxhdpi-{name.split()[0]}.png"))
        small.save(os.path.join(BR, "previews", f"icon-48dp-1x-{name.split()[0]}.png"))
    sheet.save(os.path.join(BR, "previews", "icon-masks.png"))
    masked(144, "circle", ("#1A2C61", "#D9E2FF")).save(os.path.join(BR, "previews", "icon-48dp-xxhdpi-themed-dark.png"))
    os.remove(tmp)

    report = [
        ("white on violet " + VIOLET, contrast(WHITE, VIOLET)),
        ("white on blue " + BLUE, contrast(WHITE, BLUE)),
        ("white on #3A7BFF (spec blue)", contrast(WHITE, "#3A7BFF")),
        ("violet dot on ink " + INK, contrast(VIOLET, INK)),
        ("blue dot on ink", contrast(BLUE, INK)),
        ("white on ink", contrast(WHITE, INK)),
        ("#B9A8FF (dark-theme primary) on ink", contrast("#B9A8FF", INK)),
    ]
    with open(os.path.join(BR, "contrast.txt"), "w") as fh:
        for k, v in report:
            fh.write(f"{v:5.2f}:1  {k}\n")
    for k, v in report:
        print(f"{v:5.2f}:1  {k}")


if __name__ == "__main__":
    sys.exit(main())
