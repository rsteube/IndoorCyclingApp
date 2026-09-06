# Handoff: Indoor Bike — logo and app icon (Slate)

## Overview

Replace the current orange mark (ridge + bike, gradient crest, spoke ticks) with its Slate fine-tune: the same ridge and frame geometry, recoloured to the Slate palette, one stroke weight, and the rear wheel replaced by a direct-drive smart trainer (flywheel disc on the rear axle, one leg, a foot). Ships as an Android adaptive icon, favicons, wordmark lockups and "Open in Indoor Bike" badges.

## About the files

`logo/*.svg` are **final vector assets**, ready to convert. `Slate Logo Assets.dc.html` previews them in context (open in a browser with `support.js` beside it). `Logo Explorations.dc.html` is the exploration history; only direction **3c** was chosen. `ref/` holds the old mark for comparison.

## Fidelity

High-fidelity. Geometry, colours and stroke weights are final.

## The mark

64 × 64 grid, `fill:none`, `stroke-width:3`, round caps and joins.

- Ridge: `M4 26 14 16 22 22 32 10 44 20 54 14 60 20`, stroke `#9AA1B0` (fg-3).
- Frame: `M32 48 28 32 44 30 44 38 48 48 M32 48 44 38 M32 48 16 48 28 32 M24 32 32 32` + saddle `M40 28 Q44 26 48 28 Q50 30 49 33`, stroke `#F2F4F7` (fg-1).
- Front wheel: circle (48,48) r8.
- Trainer: filled circle (16,48) r7 (flywheel, on the rear axle), leg `M16 55 16 58`, foot `M6 58 26 58`.
- Light variant: ridge `#6c6f7f`, bike `#313e50`. Mono variant: `currentColor`.
- Small sizes drop detail: 32 px drops saddle and fork (stroke 4); 16 px keeps ridge, frame triangle, wheel and flywheel only (stroke 6).

Never: orange, gradients, spoke ticks, drop shadows, or a rear wheel.

## Files

| File | Use |
|---|---|
| `logo/logo-mark.svg` | Mark on dark surfaces: website header/footer, app bar, splash |
| `logo/logo-mark-light.svg` | Mark on light surfaces |
| `logo/logo-mark-mono.svg` | `currentColor` inline mark |
| `logo/logo-wordmark.svg`, `-light.svg` | Horizontal lockup, mark + "Indoor Bike" in Manrope 800, −0.03em. **Text is live SVG text: convert to outlines** (Inkscape/Illustrator/Figma) before shipping so it renders without the font |
| `logo/app-icon-foreground.svg` | Android adaptive foreground, 108 dp canvas, mark at 52 dp inside the 66 dp safe zone |
| `logo/app-icon-background.svg` | Adaptive background, flat `#3a435e` |
| `logo/app-icon-monochrome.svg` | Android 13+ themed-icon layer, single colour, simplified frame |
| `logo/favicon-32.svg`, `favicon-16.svg` | Favicons on a `#3a435e` rounded square (rx 14/64) |
| `logo/badge-open-in-dark.svg`, `-light.svg` | 212 × 44 pill badges (text also needs outlining) |

## Android integration (`mobile_clients/android`)

1. Convert `app-icon-foreground.svg`, `app-icon-background.svg`, `app-icon-monochrome.svg` to vector drawables (`res/drawable/ic_launcher_foreground.xml` etc.). Android Studio › New › Image Asset accepts the SVGs directly; keep the 108 dp canvas.
2. `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`: `<foreground>`, `<background>`, `<monochrome>`.
3. Legacy PNGs (`mipmap-mdpi…xxxhdpi`): render the squircle-masked composite at 48/72/96/144/192 px.
4. **Debug build** keeps its distinct icon: same foreground on a `#6c6f7f` background (replaces the current greyscale variant). Put it in `src/debug/res`.
5. Splash / in-app logo: `logo-mark.svg` as a vector drawable, tint-free.

## Website integration (`website/`)

- Replace `assets/logo-mark.svg` and `assets/logo-wordmark.svg` with the new files (outlined wordmark).
- `<link rel="icon" type="image/svg+xml" href="assets/favicon-32.svg">`; add a 16 px PNG fallback rendered from `favicon-16.svg` if needed.
- `badges/`: replace the eight `indoorbike-*.svg` badges with `badge-open-in-dark.svg` / `-light.svg` and update `badges/index.html` and `badges.js` embed snippets.
- `<meta name="theme-color" content="#313e50">`.
- Remove the old `assets/icon-pulse.svg` and `assets/topo-strip.svg` references if still present (no longer part of the brand).

## Colour

Only Slate tokens: `#F2F4F7` fg-1, `#9AA1B0` fg-3, `#3a435e` bg-1 (icon background), `#313e50` bg-0, `#6c6f7f` bg-4 (debug background, light-variant ridge). No zone colours in the logo.
