# Indoor Bike — logo assets (Slate)

Direction 3c: the original ridge + frame geometry, rear wheel replaced by a direct-drive trainer (flywheel disc on the rear axle, one leg, foot). Ridge in fg-3 `#9AA1B0`, bike in fg-1 `#F2F4F7`, single 3 px stroke on a 64 grid. No orange, no gradients.

| File | Use |
|---|---|
| logo-mark.svg | Mark on dark surfaces (site header, app bar, footer) |
| logo-mark-light.svg | Mark on light surfaces (badges, print) |
| logo-mark-mono.svg | `currentColor` mark for inline use |
| logo-wordmark.svg / -light.svg | Horizontal lockup, Manrope 800 −0.03em. Convert text to outlines before shipping (the SVG text is a placeholder; viewBox 300×64 leaves room for fallback fonts) |
| app-icon-foreground.svg | Android adaptive icon foreground (108 dp canvas, mark 52 dp in the 66 dp safe zone) |
| app-icon-background.svg | Adaptive icon background, flat `#3a435e`. Debug build: swap to `#6c6f7f` |
| app-icon-monochrome.svg | Android 13+ themed icon layer (single colour, simplified frame) |
| favicon-32.svg | 32 px favicon, seat and fork dropped, stroke 4 |
| favicon-16.svg | 16 px favicon, ridge + frame triangle + wheels only, stroke 6 |
| badge-open-in-dark.svg / -light.svg | "Open in Indoor Bike" badges, 212×44, pill |

Android: `res/mipmap-anydpi-v26/ic_launcher.xml` → `<foreground>`, `<background>`, `<monochrome>` pointing at vector drawables converted from the three app-icon SVGs (Android Studio › New › Image Asset, or `svg2vector`). Legacy `ic_launcher.png` sizes: render the squircle mask at 48/72/96/144/192 px.

Website: replace `assets/logo-mark.svg` and `assets/logo-wordmark.svg`; drop `badges/indoorbike-*.svg` in favour of the two badges here; `<link rel="icon" href="favicon-32.svg">`.
