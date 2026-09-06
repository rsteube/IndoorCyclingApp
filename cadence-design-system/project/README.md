# Indoor Bike App - Design System

A privacy-first indoor cycling app for smart trainers. Dark by default, telemetry-first, and free of Google dependencies so it runs on any Android.

> **Product name: Indoor Bike App · indoorbike.app.** Confirm or change - see the *Naming* note at the bottom.

---

## Product context

**Cadence** is an Android (later iOS) app that connects to a smart trainer (initial target: Wahoo Kickr Core) over standard Bluetooth LE, runs structured workouts in **ERG mode**, and visualises ride progress via FTP-based **power zones**. Workouts are defined as JSON, completed rides are saved locally as files. The app must work fully offline with no account and no Google services, so it runs on a device that has none. Optional sync via CleverCloud + Auth0 is post-MVP.

Core experiences in priority order:

1. **Pick a workout** - browse a structured library (JSON-defined intervals).
2. **Ride it** - full-screen telemetry: power, cadence, HR, zone, time-in-step, ERG target.
3. **Review it** - a finished-ride summary with zone time, TSS-style score, files saved.
4. **Progress** - local rider progression / streaks / badges (no backend required).

### Design siblings

The user has another app at **vertmatch.run** (running / vertical-distance). Cadence shares **typographic DNA, motion language, and a single accent color "pulse"** with that sibling - but no logo overlap. The shared accent is **cyan-to-azure** (suggesting altitude / sky / motion). The two apps should feel like cousins, not twins.

> **Sources used.** vertmatch.run was given as a reference but only the homepage title was extractable via fetch. The visual link is therefore inferred (cyan/azure pulse, geometric sans, dark-first telemetry feel). **Please confirm or share screenshots so we can tighten the family resemblance.**

### Tech stack (informs design)

| Layer | Choice | Design implication |
|---|---|---|
| Core | Kotlin Multiplatform Mobile | Components must translate to Compose & SwiftUI primitives |
| UI (Android) | Jetpack Compose | Material3-shaped components, but heavily restyled |
| UI (iOS, later) | SwiftUI | Component shapes must work in either runtime |
| BLE | Standard BLE APIs | No vendor SDKs - generic device chooser screen |
| Distribution | Signed APK via CodeFloe releases + ObtainX | **No Google Services, no proprietary analytics, no Firebase, no Crashlytics** - the app has to run on a device with no Google services on it. Auth0 + CleverCloud are optional and must be lazy-loaded behind a flag. |

### Non-free dependency watchlist

Flag anything that introduces a non-free network service, a non-free dependency, tracking, or advertising. The login (Auth0) and sync (CleverCloud) paths are non-free services - they must be **opt-in** with a clear "Local only" default. Anything requiring Google Play Services is out entirely, since the app has to run without it.

---

## Index

```
/
├─ README.md                    ← you are here
├─ SKILL.md                     ← agent skill manifest
├─ colors_and_type.css          ← all design tokens (CSS vars + utility classes)
├─ assets/                      ← logos, glyphs, icons
│   ├─ logo-mark.svg            ← square mark (chainring + pulse)
│   ├─ logo-wordmark.svg        ← horizontal lockup
│   └─ icon-pulse.svg           ← brand glyph (waveform)
├─ fonts/                       ← (using Google Fonts CDN; see Type)
├─ preview/                     ← design-system tab cards
│   ├─ type-display.html
│   ├─ type-body.html
│   ├─ colors-neutrals.html
│   ├─ colors-brand.html
│   ├─ colors-zones.html
│   ├─ colors-semantic.html
│   ├─ spacing.html
│   ├─ radii.html
│   ├─ elevation.html
│   ├─ buttons.html
│   ├─ form-controls.html
│   ├─ cards.html
│   ├─ badges-zones.html
│   ├─ logo.html
│   └─ iconography.html
└─ ui_kits/
    └─ indoorbike_android/
        ├─ README.md
        ├─ index.html              ← interactive Android prototype
        └─ *.jsx                   ← components (StatusBar, WorkoutCard, RideHud, etc.)
```

---

## Content fundamentals

**Voice.** Direct, technical, athlete-respectful. Not chirpy, not gamified-cringe. Think **a coach who trusts you** - short imperatives, real numbers, no exclamation marks.

**Person.** Second person ("you"). Avoid first-person plural ("we"). Avoid the app referring to itself.

**Casing.** Sentence case for everything UI-facing - buttons, headers, settings labels. Reserve **ALL CAPS** for tiny eyebrow / unit / tab labels with letter-spacing (`W`, `BPM`, `RPM`, `ZONE 4`).

**Numbers.** Always tabular (`font-feature-settings: "tnum"`). Units are smaller than values and tracked-out (`248` `W`). Power in watts, cadence in rpm, HR in bpm, distance metric, percentages of FTP for power zones.

**Tone examples.**

| Don't | Do |
|---|---|
| "Awesome workout! 🔥 Crushed it!" | "Workout complete. 247 TSS." |
| "Hey there, ready to ride?" | "Connect a trainer to start." |
| "Oops! Couldn't find your sensor." | "No trainer found. Tap to scan again." |
| "Let's get you signed in!" | "Sign in (optional)." |
| "Pro tip: try a longer warmup!" | "Tip: warm up at Z2 for 10 min." |

**Emoji.** No. Not in copy, not in icons. **Unicode arrows / dots are fine** sparingly (`→`, `·`, `•`).

**Empty states.** State the situation, then the action. No mascots, no jokes.
> "No workouts saved yet. Import a `.zwo` or `.json` file to start."

**Workout titles.** Reuse community conventions (Sweet Spot, Threshold 2x20, VO2 30/30s) - they're already part of the cycling vernacular.

---

## Visual foundations

### Color

**Dark first.** A near-black with a slight blue undertone (`#07090C`) is the canvas. Surfaces step up in 4 stops (`bg-1` … `bg-4`). Light-mode is a v2 - design tokens accommodate it but every screen ships dark.

**Brand ember.** A single warm orange **`#F47A1F`** is the hero accent - the same family as VertMatch's "Add Race" CTA, TEP value, and Mastering TEP hero. Use it for primary actions, the active zone halo during a ride, and brand surfaces. Pair with deep ember **`#D54F0A`** in two-stop gradients only - never as a solid fill.

**Sibling visual link to VertMatch.** Cadence inverts VertMatch's daylight palette (light cream + black nav + warm orange) into a night-ride palette (near-black + topographic-line accent + same warm orange). The wordmark mirrors VertMatch's two-tone treatment ("Vert*Match*" → "Cad*ence*"). The topographic line motif from VertMatch's nav (`assets/topo-strip.svg`) reappears subtly in Cadence's splash, app bar bottom, and ride-summary header.

**Power zones.** A 7-step calibrated spectrum (cool → hot) drives the entire ride UI. Zones are *systematic*, not decorative - every zone has a fill, a translucent bg, a label color, and an order. Use them for the workout timeline, current zone halo, distribution chart, and zone badges. Never substitute a zone color for anything that isn't a zone.

**Semantic.** Success = Z3 green, Warning = Z4 yellow, Danger = Z6 red, Info = brand cyan. (Zone palette doubles as semantic palette - keeps the app coherent.)

### Type

- **Display: Space Grotesk** - geometric, slightly mechanical, athletic. Used for headings, screen titles, and large telemetry readouts.
- **Body: Inter** - neutral, dense numerics, legible.
- **Mono: JetBrains Mono** - telemetry, axis ticks, file names, countdown timers.

> **Substitution flag.** No source font files were provided. All three are loaded from Google Fonts CDN. **Shipping implication:** the fonts are open-licensed, but the *prototype* loads them from a Google CDN at runtime. The shipped Android app must **bundle the TTFs locally** - please confirm we can ship Space Grotesk + Inter + JetBrains Mono (all OFL-licensed).

### Spacing & layout

A 4-px base grid. Major rhythm is **`s-4` (16)** between text blocks and **`s-6` (24)** between cards. The ride HUD breaks the system on purpose - it uses bigger margins and one giant centered metric to be readable from the saddle.

Mobile content padding: **`s-5` (20)** horizontal, never less than `s-4`.

### Backgrounds

- **No imagery in chrome.** No hero photos, no stock cyclists. The app is a tool - backgrounds are flat dark surfaces.
- **One exception:** the in-ride view uses a **subtle radial halo** behind the power readout, tinted with the *current zone color*. This is the only background that animates.
- **No gradients in flat surfaces.** Gradients only appear on (a) the brand mark, (b) the cyan→azure progress fill on the workout timeline, and (c) the in-ride zone halo.

### Motion

- Ease: `--ease-out cubic-bezier(0.2,0.7,0.2,1)` for entrances, `--ease-spring` for tactile press feedback.
- Durations: `120ms` for micro feedback, `200ms` for state changes, `320ms` for screen transitions.
- **No bounces, no flourishes.** Movement is functional. The exception: the cadence pulse waveform on the splash + the zone halo "breathing" during a ride.
- **Reduced motion is respected** - the halo flattens to a static fill.

### States

- **Hover (web/preview only).** Lighten surface by one `bg` step (`bg-1 → bg-2`) over 120ms.
- **Press.** Solid surface darkens 6%; primary buttons compress 2% scale; haptic (Compose: `LocalHapticFeedback`).
- **Focus.** 2-px `--brand-500` ring, 2-px offset. Never use OS default focus.
- **Selected.** Surface = `bg-3`, 1-px `--brand-500` left border, brand color text.
- **Disabled.** `--fg-4` text on the unchanged surface; no opacity tricks.

### Borders & lines

Three weights of hairline: `--line-1` (dividers), `--line-2` (default card border), `--line-3` (prominent / focus). Borders are **always 1px** - heavier feels app-from-2014.

### Shadows & elevation

Two soft shadows (`shadow-2`, `shadow-3`) for popovers and modals. **Cards are flat** - they earn their separation from a 1-px border, not a shadow. Reserve `--shadow-glow` for the primary CTA on first launch and the "Start workout" button.

### Corner radii

- **`r-md` 12px** is the default - cards, inputs, buttons.
- **`r-lg` 16px** for screen-size sheets and modals.
- **`r-pill`** for zone badges and segmented controls.
- **`r-xs` 4px** for chart tooltips and inline tags.
- Avoid mixing radii within a card.

### Cards

Flat. `bg-1` fill, 1-px `--line-2` border, `r-md`. Padding `s-5`. No drop shadow. The interaction signal is the border lighting up to `--line-3` on hover - never a shadow appearing.

### Transparency & blur

Used for one thing: the **bottom sheet scrim** during BLE device selection (`rgba(7,9,12,0.7)` + 12px backdrop-blur). Not for cards, not for headers. The status bar in the ride view is **fully opaque** - a translucent overlay over telemetry is unreadable in motion.

### Imagery vibe

If imagery enters later (badges, progression artwork): cool-toned, high-contrast, slightly desaturated. Think **night ride / pre-dawn light**, not summer crit. Subtle grain is permitted, no warm filters.

---

## Iconography

**System:** **[Lucide](https://lucide.dev/)** at **2px stroke**, `currentColor`, 24px default. Lucide is MIT-licensed and bundleable, so it ships with the app rather than being fetched.

- The app ships its own SVG sprite from the Lucide set (no CDN at runtime in the shipped app - only in this prototype).
- **No emoji.** Anywhere.
- **Unicode allowed sparingly:** `→` (next), `·` (separator), `•` (status dot). Used for inline copy only, never as a primary icon.
- **Brand glyph** (`assets/icon-pulse.svg`) is the only custom icon - a 7-point ECG-style waveform reused as a loading indicator and on the splash.

**Sizes.** 16 (inline), 20 (compact UI), 24 (default), 32 (large action), 48+ (illustrative).

**Common mappings.** play → `play`, settings → `settings`, ride → `bike`, history → `clock`, profile → `user`, sensors → `bluetooth`, power → `zap`, heart rate → `heart`, cadence → `gauge`, zones → `bar-chart-3`.

> **Substitution flag.** No icon set was provided in the brief. Lucide is the closest match for the technical-but-friendly tone we want. **Confirm Lucide, or point us at a different set.**

---

## Naming

The brief did not provide a product name. **"Indoor Bike App"** is a working title - it's evocative, technical-cyclist-native, and pairs well alongside "VertMatch". Other directions worth considering:

- **Pulse** - leans into the brand glyph (waveform).
- **Watt** - telemetry-forward, very short.
- **Cog** - playful, mechanical.
- **Rolla** / **Coast** - softer, more lifestyle.

**Confirm a name and we'll lock the wordmark.**

---

## Open questions for you

1. **Visual link to VertMatch** - share screenshots? The current cyan/azure pulse is a guess.
2. **App name** - confirm "Indoor Bike App" or pick another.
3. **Icon set** - Lucide OK?
4. **Workout file format** - `.zwo` (Zwift workout) compatible? Or a Cadence-native JSON only?
5. **Imperial units** - required, or metric-only?
6. **Progression mechanics** - what *kinds* of rewards? Streaks, badges, levels, XP, all of the above? This will materially shape the UI later.
