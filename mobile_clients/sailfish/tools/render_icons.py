#!/usr/bin/env python3
"""Render the Sailfish launcher icons from the Slate logo.

The same drawing as mobile_clients/ios/scripts/render_icon.swift and
slate-design-system/logo/app-icon-*.svg: the ridge and the rider on slate.
Sailfish puts no mask over a launcher icon, so the slate is a rounded square
here rather than the full square iOS masks for itself.

Writes icons/<size>x<size>/harbour-indoorbike.png for the four sizes Harbour
expects. Needs Pillow. From mobile_clients/sailfish:

    python3 tools/render_icons.py
"""
import os

from PIL import Image, ImageDraw

SIZES = (86, 108, 128, 172)
SUPERSAMPLE = 8
SLATE = (0x3A, 0x43, 0x5E, 255)
RIDGE = (0x9A, 0xA1, 0xB0, 255)
RIDER = (0xF2, 0xF4, 0xF7, 255)


def render(size: int) -> Image.Image:
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    unit = big / 108.0
    draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=SLATE)

    # The 64-unit drawing, inset by 28 and scaled by 0.8125, as in the SVG.
    def at(x: float, y: float):
        return ((28 + x * 0.8125) * unit, (28 + y * 0.8125) * unit)

    width = 3 * 0.8125 * unit

    def line(points, colour):
        xy = [at(*p) for p in points]
        draw.line(xy, fill=colour, width=round(width), joint="curve")
        for x, y in (xy[0], xy[-1]):  # round caps
            r = width / 2
            draw.ellipse((x - r, y - r, x + r, y + r), fill=colour)

    def circle(cx, cy, r, colour, filled):
        x0, y0 = at(cx - r, cy - r)
        x1, y1 = at(cx + r, cy + r)
        if filled:
            draw.ellipse((x0, y0, x1, y1), fill=colour)
        else:
            draw.ellipse((x0, y0, x1, y1), outline=colour, width=round(width))

    def quad(p0, c, p1, steps=24):
        return [
            (
                (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t ** 2 * p1[0],
                (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t ** 2 * p1[1],
            )
            for t in (i / steps for i in range(steps + 1))
        ]

    line([(4, 26), (14, 16), (22, 22), (32, 10), (44, 20), (54, 14), (60, 20)], RIDGE)
    circle(48, 48, 8, RIDER, filled=False)
    line([(32, 48), (28, 32), (44, 30), (44, 38), (48, 48)], RIDER)
    line([(32, 48), (44, 38)], RIDER)
    line([(32, 48), (16, 48), (28, 32)], RIDER)
    line([(24, 32), (32, 32)], RIDER)
    line(quad((40, 28), (44, 26), (48, 28)) + quad((48, 28), (50, 30), (49, 33))[1:], RIDER)
    circle(16, 48, 7, RIDER, filled=True)
    line([(16, 55), (16, 58)], RIDER)
    line([(6, 58), (26, 58)], RIDER)
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    for size in SIZES:
        folder = os.path.join(root, "icons", f"{size}x{size}")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "harbour-indoorbike.png")
        render(size).save(path, optimize=True)
        print(path)


if __name__ == "__main__":
    main()
