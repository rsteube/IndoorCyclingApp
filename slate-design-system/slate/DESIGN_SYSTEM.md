# Indoor Bike — Slate Design System

A monochrome, dark-only system for a privacy-first indoor cycling app. Five slate blues carry every surface; a cool white carries every action. Power zones are the only colour.

## Why it changed

The previous system (peat-brown darks, ember orange, Bebas Neue condensed caps, JetBrains Mono telemetry) read as generic AI-generated UI to some riders. Slate replaces it with a palette the product owner chose, one quiet typeface, and no accent colour at all, so the app looks like a tool someone specified rather than a template.

## Index

```
slate/
├─ README.md        ← you are here
├─ tokens.css       ← CSS variables + type classes
├─ Color.kt         ← drop-in replacement for ui/theme/Color.kt (same object + member names)
└─ Type.kt          ← drop-in replacement for ui/theme/Type.kt (Manrope only)

Slate Components.dc.html   ← component sheet
Slate Screens.dc.html      ← the eight redesigned screens
Current UI.dc.html         ← what shipped before, recreated from source, for comparison
```

## Palette

| Token | Hex | Name | Use |
|---|---|---|---|
| `bg-0` | `#313e50` | Charcoal Blue | Canvas |
| `bg-1` | `#3a435e` | Twilight Indigo | Cards, sheets, tab bar |
| `bg-2` | `#455561` | Charcoal Blue 2 | Icon wells, inputs, inactive bars, in-card dividers |
| `bg-3` | `#5c6672` | Blue Slate | Pressed state, dashed outlines |
| `bg-4` | `#6c6f7f` | Slate Grey | Disabled ink, unpaired status dot |
| `fg-1` | `#F2F4F7` | — | Primary text, primary button fill, active tab, progress fills |
| `fg-2` | `#C5CAD6` | — | Secondary text, inactive tab |
| `fg-3` | `#9AA1B0` | — | Labels, hints, chevrons |

The slates are surfaces and chrome only. None of them is light enough to be text on `bg-0` (`#6c6f7f` is 2.3:1), so the three foreground whites are derived, cool-tinted neutrals rather than palette members.

**There is no accent colour.** Anything that used to be orange is now `fg-1`: the Start button, the active tab, the XP bar, streak fills, earned badges. Emphasis comes from contrast and weight, not hue.

**Power zones are unchanged** (Z1 `#6B8AA8` → Z7 `#C53BFF`). They are the only saturated colour in the app and only ever mean a zone. Success / warning / danger borrow Z3 / Z4 / Z6.

## Type

One family, **Manrope**, bundled as a variable TTF. No display face, no mono.

- Screen title: 28 / 800, `-0.02em`
- Stat value: 22 / 800, tabular
- Card title: 18 / 700
- Body: 15 / 500 · Strong: 15 / 700
- Small and section labels: 13 / 500–600, `fg-3`, **sentence case**
- Tab labels, ticks: 11 / 600–700

Numbers always use `tabular-nums`. Units sit after the value at small size in `fg-3` (`130 W`). The old ALL-CAPS tracked eyebrows are gone; section heads are plain sentence-case labels.

## Shape

- Cards and sheets: `bg-1`, **20 px** radius, no border, no shadow. Separation comes from the one-step lift over the canvas.
- Inputs and small tiles: 12 px.
- Buttons, chips, tab bar, zone badges, icon buttons: pill / circle.
- Dividers inside a card: 1 px `bg-2`, inset 20 px from the card edge.
- Locked or empty states: 1 px dashed `bg-3` outline on the canvas, no fill.

## Components

- **Primary button** — 56 px pill, `fg-1` fill, `on-inverse` text, 700. Icon optional, 20 px. One per screen, always full-width at the bottom.
- **Secondary button** — 56 px pill, `bg-2` fill, `fg-1` text.
- **Icon button** — 44 px circle, `bg-1` (or `bg-2` on a card), 22 px glyph.
- **Chip** — pill; selected `fg-1` on `on-inverse`, unselected `bg-2` / `fg-2`.
- **List row** — 14 px vertical padding inside a card, 22 px leading icon in `fg-2`, title 15 / 700, sub 13 / 500 `fg-3`, trailing chevron or 8 px status dot.
- **Zone badge** — pill, zone colour at 20 % behind zone-coloured 12 / 700 text.
- **Tab bar** — floating `bg-1` pill inset 16 px, 6 px padding; active tab is a `fg-1` pill with `on-inverse` icon and label.
- **Stat** — label 12 / 600 `fg-3` above value 22 / 800.

## Ride HUD

The one screen where zone colour touches type: the live power number and its status line take the current zone colour, so the rider reads zone from the biggest thing on screen. Everything else stays monochrome: flank cards are `bg-1` at 70 %, target/stepper pills `bg-1`, the progress cursor `fg-1`. The horizon ridge backdrop survives, redrawn in `bg-1` / `bg-2` with only its crest line tinted by zone. Timeline: completed and current steps in full zone colour, upcoming steps at 45 %.

## Icons

Material Symbols Outlined, weight 300, 22–24 px, `fg-1` or `fg-2`. Never tinted with a zone colour except the HRM heart when it shows a live HR zone.

## Voice

Unchanged from before: direct, second person, sentence case, no exclamation marks, no emoji.

## Adopting in Compose

`Color.kt` and `Type.kt` keep the `CadenceColors` / `CadenceType` object and member names, so the app compiles after a file swap. Then:

1. Add `res/font/manrope_variable.ttf`; remove Bebas Neue, Inter, JetBrains Mono.
2. Search `Brand500.copy(alpha` — tinted icon wells become `Bg2` fills.
3. Search `.uppercase()` in `AppBar`, `WeekHeader`, `SectionHead(er)` — remove; labels are sentence case.
4. `PrimaryBtn`: `RoundedCornerShape(14.dp)` → `CircleShape`, height 56 dp, drop the glow border.
5. `TabBar`: wrap in a pill `Bg1` container inset 16 dp; active item gets a `Fg1` pill.
6. Cards: remove `.border(1.dp, Line2, …)`, radius `Radii.lg` → 20 dp.
