"""Converts branding/lefty-foreground.svg into Android adaptive-icon vector drawables.

Handles the small SVG subset the icon uses (path, ellipse, one transform group).
Usage: python3 branding/svg2vd.py
"""
import re
import xml.etree.ElementTree as ET

SRC = "branding/lefty-foreground.svg"
RES = "androidApp/src/main/res/drawable"
NS = "{http://www.w3.org/2000/svg}"


def hexcolor(value, opacity=1.0):
    value = value.lstrip("#")
    alpha = round(255 * opacity)
    return f"#{alpha:02X}{value.upper()}" if opacity < 1 else f"#{value.upper()}"


def ellipse_path(cx, cy, rx, ry):
    return f"M{cx - rx},{cy} a{rx},{ry} 0 1,0 {2 * rx},0 a{rx},{ry} 0 1,0 {-2 * rx},0 Z"


def path_attrs(el, mono):
    d = el.get("d") if el.tag == NS + "path" else ellipse_path(*(float(el.get(k)) for k in ("cx", "cy", "rx", "ry")))
    opacity = float(el.get("opacity", "1"))
    attrs = {"android:pathData": d}
    stroke = el.get("stroke")
    # SVG's default fill is black; stroke-only strokes in this icon never mean a fill.
    fill = el.get("fill", "none" if stroke else "#000000")
    if fill != "none":
        attrs["android:fillColor"] = "#FFFFFFFF" if mono else hexcolor(fill, opacity * float(el.get("fill-opacity", "1")))
    if stroke:
        attrs["android:strokeColor"] = "#FFFFFFFF" if mono else hexcolor(stroke, opacity)
        attrs["android:strokeWidth"] = el.get("stroke-width", "1")
        if el.get("stroke-linecap"):
            attrs["android:strokeLineCap"] = el.get("stroke-linecap")
        if el.get("stroke-linejoin"):
            attrs["android:strokeLineJoin"] = el.get("stroke-linejoin")
    return attrs


def convert(mono_keep=None):
    root = ET.parse(SRC).getroot()
    group = root.find(NS + "g")
    tx, ty, sc = map(float, re.match(r"translate\(([\d.]+),([\d.]+)\) scale\(([\d.]+)\)", group.get("transform")).groups())
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated from branding/lefty-foreground.svg by branding/svg2vd.py -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">',
           f'  <group android:translateX="{tx}" android:translateY="{ty}" android:scaleX="{sc}" android:scaleY="{sc}">']
    for i, el in enumerate(e for e in group if e.tag in (NS + "path", NS + "ellipse")):
        if mono_keep is not None and i not in mono_keep:
            continue
        attrs = path_attrs(el, mono_keep is not None)
        out.append("    <path " + "\n        ".join(f'{k}="{v}"' for k, v in attrs.items()) + "/>")
    out += ["  </group>", "</vector>", ""]
    return "\n".join(out)


# Shapes kept for the themed (monochrome) icon: jacket, hair, brows, glasses, nose, moustache, mouth.
MONO_SHAPES = {0, 8, 10, 11, 12, 13, 14, 15, 17, 18, 19}

if __name__ == "__main__":
    open(f"{RES}/ic_launcher_foreground.xml", "w").write(convert())
    open(f"{RES}/ic_launcher_monochrome.xml", "w").write(convert(MONO_SHAPES))
