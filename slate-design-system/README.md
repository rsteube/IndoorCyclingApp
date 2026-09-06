# Handoff: Indoor Bike — Slate design system redesign

## Overview

Full visual redesign of the Indoor Bike Android app (`mobile_clients/android`, Jetpack Compose). The current "Cadence" theme (peat-brown darks, ember orange `#F47A1F`, Bebas Neue / Inter / JetBrains Mono) is replaced by **Slate**: five slate-blue surfaces, cool-white foreground, a single typeface (Manrope), and **no accent colour**. Power-zone colours are unchanged. No screen changes what it does; every screen changes how it looks.

## About the design files

The `.dc.html` files in this bundle are **design references built in HTML**, not code to ship. Recreate them in the existing Compose codebase using its established patterns (`ui/theme/*`, `ui/components/Primitives.kt`, per-screen files under `ui/` and `ui/screens/`). Open the HTML files in a browser to inspect exact spacing and colours.

## Fidelity

**High-fidelity.** Colours, type sizes, weights, radii and spacing are final. Recreate them exactly. Copy in the mocks is representative (sample workout names, numbers); keep the app's real strings and data.

## Where the design lives in the codebase

| Concern | Files to change |
|---|---|
| Colours | `ui/theme/Color.kt` → replace with `slate/Color.kt` (same object/member names, compiles as-is) |
| Type | `ui/theme/Type.kt` → replace with `slate/Type.kt`; add `res/font/manrope_variable.ttf`; delete Bebas Neue, Inter, JetBrains Mono font resources and their `R.font` references; update `NOTICE.md` |
| Material scheme | `ui/theme/Theme.kt` — no change needed; it reads `CadenceColors` |
| Radii | `ui/theme/Spacing.kt` → `Radii.lg = 20.dp`, `Radii.md = 12.dp`, `Radii.sm = 6.dp` |
| Shared components | `ui/components/Primitives.kt`, `DeviceStatus.kt` |
| Screens | `HomeScreen.kt`, `WorkoutPickerScreen.kt`, `WorkoutDetailScreen.kt`, `WorkoutOverviewScreen.kt`, `HistoryTab.kt`, `ProgressTab.kt`, `SettingsTab.kt`, `TrainerPairingScreen.kt`, `Onboarding.kt`, `RideScreen.kt`, `RideHero.kt`, `RidgeTimeline.kt` |
| Website (optional, later) | `website/styles.css`, `website/badges/tokens.css` use the old palette; out of scope here |

## Design tokens

### Colour

| Token | Hex | Use |
|---|---|---|
| `Bg0` | `#313E50` | Canvas (every screen background, tab-bar area) |
| `Bg1` | `#3A435E` | Cards, bottom sheets, tab-bar pill, icon buttons on canvas |
| `Bg2` | `#455561` | Icon wells, number inputs, inactive bars/chart ghosts, dividers inside a card, secondary buttons, unselected chips |
| `Bg3` | `#5C6672` | Pressed state, dashed outline of locked/empty cards |
| `Bg4` | `#6C6F7F` | Disabled ink, "not paired" status dot |
| `Fg1` | `#F2F4F7` | Primary text, primary button fill, active tab pill, progress fills, earned-badge disc, timeline cursor |
| `Fg2` | `#C5CAD6` | Secondary text, inactive tab icon/label, list-row leading icons |
| `Fg3` | `#9AA1B0` | Section labels, sub-lines, units, chevrons, chart ticks |
| `OnBrand` | `#313E50` | Text/icon on an `Fg1` fill |
| `Line1` | `#455561` | 1 dp divider inside a card, inset 20 dp |
| `Line2` | `#5C6672` | 1 dp dashed outline |
| Zones Z1–Z7 | `#6B8AA8 #2E7BFF #1FB373 #F0C419 #FF8A1F #EB3B3B #C53BFF` | Unchanged. Zone badge background = zone colour at 20 % alpha |
| `Success / Warning / Danger` | Z3 / Z4 / Z6 | Status dots, destructive button |

`Brand500` and friends now alias `Fg1` so old call sites compile; still, **search and remove** every `Brand500.copy(alpha = …)` tint: tinted icon wells become plain `Bg2` fills, tinted borders are removed, gradients collapse to flat `Fg1`.

### Type (Manrope only, `fontFeatureSettings = "tnum"` on all numbers)

| Style | Size / weight | Where |
|---|---|---|
| `displayXl` | 132 sp / 800, tracking −0.04em | Ride HUD power number (phone landscape) |
| `displayL` | 48 sp / 800, −0.02em | Ride-summary hero (Duration, Avg power) |
| `displayM` | 40 sp / 800, −0.02em | HUD flank values (cadence, HR) |
| `h1` | 28 sp / 800, −0.02em | Screen titles |
| `h2` | 22 sp / 800 | Stat values, sheet titles |
| `h3` | 18 sp / 700 | Card titles (mode cards), HUD workout name |
| `body` | 15 sp / 500, lh 22 | Body copy |
| `bodyStrong` | 15 sp / 700 | Row titles, button labels (16 sp on primary buttons) |
| `small` | 13 sp / 500, lh 18 | Row sub-lines |
| `eyebrow` | 13 sp / 600 | Section labels. **Sentence case** — remove every `.uppercase()` on labels and titles |
| `tick` | 12 sp / 600, tnum | Stat labels, week-header totals, units |
| `micro` | 11 sp / 600–700 | Tab labels, chart axis |

Units follow the value at 12–16 sp, weight 600, `Fg3`, one space before (`130 W`, `87 rpm`).

### Spacing and shape

- Screen horizontal padding **20 dp**; title block padding 24 dp.
- Cards: `Bg1`, radius **20 dp**, **no border, no shadow**. Vertical padding inside a list card 6 dp; rows 14 dp vertical / 20 dp horizontal.
- Stat / hero cards: 20–24 dp padding.
- Inputs and date tiles: radius 12 dp, `Bg2`.
- Buttons, chips, badges, tab bar, icon buttons: pill / circle.
- Streak boxes: 32 dp tall, radius 6 dp, `Bg2`, fill `Fg1` bottom-up.
- Divider inside a card: 1 dp `Line1`, `Modifier.padding(horizontal = 20.dp)`.

## Components (`Primitives.kt`, `DeviceStatus.kt`)

- **AppBar** → replace with a title block: optional 44 dp circular `Bg1` back button row (padding 8 dp 16 dp 0), then `eyebrow` in `Fg3` + `h1` title, padding 16 dp 24 dp 0. Titles are sentence case ("Your progress", "Connect a trainer").
- **PrimaryBtn**: 56 dp tall, `CircleShape`, `Fg1` fill, `OnBrand` text 16 sp / 700, optional 20 dp filled icon. Remove the glow border. Disabled: `Bg2` fill, `Fg4` text. Full width, bottom of screen, 12 dp above / 24 dp below.
- **SecondaryBtn**: 56 dp, pill, `Bg2` fill, `Fg1` text.
- **Destructive**: as primary with `Danger` fill, `Fg1` text.
- **IconBtn**: 44 dp circle, `Bg1` fill (on canvas), 22 dp glyph `Fg1`.
- **FilterChip**: pill, padding 10 dp 16 dp, 13 sp; selected `Fg1`/`OnBrand` 700, unselected `Bg2`/`Fg2` 600.
- **ZoneBadge**: pill, padding 4 dp 10 dp, 12 sp / 700, text = zone colour, background = zone colour @ 20 %. Drop the leading dot.
- **MetaPill / RideAgainPill**: pill, `Bg2`, 13 sp / 700, 16 dp icon, padding 8 dp 14 dp.
- **Count pill** ("2 new"): `Fg1` fill, `OnBrand` 12 sp / 700, padding 6 dp 12 dp.
- **ZoneBar** (picker rows): 6 dp tall, radius 3 dp, 2 dp gaps, no track background.
- **TabBar**: `Bg1` pill, margin 12 dp 16 dp 20 dp, padding 6 dp. Each tab: column, 22 dp icon + 11 sp label, gap 2 dp, padding 8 dp 4 dp. Active tab = `Fg1` pill with `OnBrand` icon (weight 500) and 700 label; inactive `Fg2` / 600. Icons: `directions_bike`, `schedule`, `trending_up` (was `emoji_events`), `settings`.
- **DeviceStatusCard / DeviceRow**: card as above; leading icon 22 dp `Fg2` with **no tinted well**; title `bodyStrong`; sub `small` `Fg3`; trailing 8 dp status dot (`Success` / `Bg4`) then optional 22 dp chevron `Fg3`.
- **SearchField**: pill, `Bg1`, padding 14 dp 18 dp, 20 dp search icon `Fg3`, placeholder `Fg3`.
- **Number input** (Settings, Onboarding): `Bg2`, radius 12 dp, padding 14 dp 18 dp; value 18 sp / 700 tnum left, unit 15 sp / 600 `Fg3` right. Focus ring: 2 dp `Fg1` outline (no OS default).
- **Locked / empty card**: transparent, 1 dp dashed `Line2`, radius 20 dp.

## Screens

Read each against `Slate Screens.dc.html` (frames are 412 × 892 dp; HUD 892 × 412).

### Home (`HomeScreen.kt`)
Title block: eyebrow = weekday, title **"What are you riding?"** (replaces "Ride"); QR icon button top-right. Three **ModeCard**s: `Bg1` card, padding 20 dp, 48 dp circular `Bg2` well with 24 dp icon, title `h3`, body 14 sp `Fg2`, trailing chevron `Fg3`; gap 12 dp. Section labels "Before you start", "Repositories" (13 sp / 600 `Fg3`, padding 20 dp 4 dp 0). DeviceStatusCard as above. RepositoriesCard: rows with name + status line, trailing "N new" count pill.

### Workouts (`WorkoutPickerScreen.kt`)
Back button; eyebrow "Choose a ride", title "Workouts". SearchField, chip row (gap 6 dp). **All rows live in one `Bg1` card** separated by inset dividers, instead of one card per row: row padding 14 dp 12 dp 14 dp 20 dp; name 15 sp / 700 ellipsised, ZoneBar under it (8 dp gap); right column duration 13 sp / 700 tnum and "TSS n" 12 sp `Fg3`; 36 dp star target `Fg3`. AddMoreWorkoutsCard becomes a dashed locked-style card with `library_books` icon.

### Workout detail (`WorkoutDetailScreen.kt`)
Back and star icon buttons; eyebrow "Tempo · 15 min", title = name. **ZoneProfileCard** merges the chart and StatsGrid: chart 110 dp, bars radius 4 dp top, gap 2 dp; below it a 4-column grid NP / TSS / IF / kJ (label 12 sp / 600 `Fg3`, value `h2`). Section label "Steps · 9". Steps in one `Bg1` card: ZoneBadge, zone name 15 sp / 600 `Fg2` (flex), duration `m:ss` 15 sp / 700 tnum, watts 14 sp / 600 `Fg3` right-aligned 56 dp. Remove the 4 dp coloured left bar. "+ n more · tap to expand" 13 sp `Fg3` centred inside the card. Bottom: PrimaryBtn "Start workout" with filled `play_arrow`; when no trainer, helper line 14 sp `Fg3` centred above "Pair a trainer to ride".

### Ride summary (`WorkoutOverviewScreen.kt`)
Back and share buttons; eyebrow "Ride complete · date, time", title = workout. Hero card: Duration left, Avg power right, values `displayL`, labels 13 sp `Fg3`. Second card: 3-column grid Cadence, Heart rate, NP, IF, TSS, kJ (`h2` values). Remove all orange/red value tints — `Fg1` everywhere. Power adherence card: title 15 sp / 700 + "target · actual" caption; target steps as `Bg2` ghost bars, actual as 1.6 dp `Fg1` polyline. Time in zone card: 7 bars zone-coloured, empty zones `Bg2` 4 dp stubs, Z1–Z7 axis 11 sp `Fg3`. PrimaryBtn "Done" when arriving from a ride.

### History (`HistoryTab.kt`)
Eyebrow "Past activity", title "History". TotalsCard: 3-column grid, left-aligned. Week groups: header row (label 13 sp / 600 `Fg3`, totals 12 sp `Fg3` tnum) then **one card per week** containing SessionRows with inset dividers. DateTile: 44 × 48 dp, radius 12 dp, `Bg2`, weekday 10 sp / 600 `Fg3` (no orange), day 18 sp / 800. Time format 24 h. RideAgainPill as above.

### Progress (`ProgressTab.kt`)
Eyebrow "Stats & XP", title "Your progress". LevelCard: plain `Bg1` card (remove gradient and tinted border), 64 dp circular `Bg2` medallion with level number 28 sp / 800 (no ring, no "LVL"), eyebrow "Rider level", title 20 sp / 800, "142 XP total"; XP bar 10 dp pill `Bg2` with flat `Fg1` fill (no gradient); footer row 13 sp / 600 `Fg2`; explainer 13 sp `Fg3`. StreakCard: "Weekly streak", value 28 sp / 800 + "weeks" 16 sp `Fg3`, "Best · n"; WeekBoxes `Bg2` → `Fg1` fill. **Remove the flame icon.** Badges header + "2 / 6" both `Fg3`. BadgeTile earned: `Bg1` card, 48 dp `Fg1` disc with `OnBrand` icon, title 15 sp / 700, sub 12 sp `Fg3`. Locked: dashed `Line2` outline, `Bg1` disc, `lock` icon `Fg3`, title `Fg2`. Keep the existing entry animations; they just animate the new colours.

### Settings (`SettingsTab.kt`)
Eyebrow "Preferences", title "Settings". Section labels sentence case ("Profile", "Devices", "More"). Profile card rows: short titles ("FTP", "Body weight", "Max heart rate") with one-line subs, number input right (min width 96 dp), value `Fg1` (no orange/red). Devices = DeviceStatusCard. Connections, Bike & gearing, Backup & sync become three NavRows in one "More" card (22 dp `Fg2` icon, no well, chevron `Fg3`). About / Feedback follow as further sections (not mocked; same row pattern).

### Trainer pairing (`TrainerPairingScreen.kt`)
Back button; eyebrow "Pairing", title "Connect a trainer". Status card: 96 dp `Bg2` disc containing a 72 dp spinner ring (3 dp `Bg3` track, `Fg1` head, 1.2 s linear) around a 28 dp `bluetooth` icon; "Scanning · n found" 18 sp / 700; helper 14 sp `Fg3`. Idle state uses the same card with a static icon, "Find your trainer", and PrimaryBtn "Scan for devices". DeviceList: one card, rows with `bluetooth_searching` icon, name, "FTMS · signal" sub, trailing "Connect" pill (`Fg1` for strongest, `Bg2` others). Bottom SecondaryBtn "Stop scanning". Mock trainer button (debug only) stays a SecondaryBtn.

### Onboarding (`Onboarding.kt`)
WelcomeBanner is **inverted**: `Fg1` card, `OnBrand` text, eyebrow `#5C6672`, body `#455561`, button `Bg0` fill with `Fg1` text, radius 20 dp. Sheet: `Bg1`, top radius 28 dp, 36 × 4 dp `Bg4` handle; eyebrow "Step 1 of 2 · About you", title `h2`, body `Fg2`; field label row (title 15 sp / 700 left, hint 13 sp `Fg3` right), number input, "Don't know your FTP? Start at 150 W" 13 sp / 600 `Fg2` underlined; PrimaryBtn "Continue". Scrim `rgba(21,27,36,0.55)`.

### Ride HUD (`RideScreen.kt`, `RideHero.kt`, `RidgeTimeline.kt`)
Landscape. Header: 44 dp circular back, workout name `h3`, spacer, clock "6:14 / 15:00" 18 sp / 700 tnum (`Fg2`, total `Fg3`), Skip = `Bg1` pill 44 dp with `skip_next`, Pause = `Fg1` pill with filled `pause`. Mock button (debug) same as Skip.
Hero: status line 13 sp / 600 in **zone colour** with 8 dp dot ("Power · on target" / "Power · ERG locked" / "Power · ERG idle", sentence case); power number `displayXl` in **zone colour**, "W" 28 sp / 700 `Fg2`; W/kg bar 220 × 3 dp `Bg2` with `Fg1` fill (no gradient); "2.10 W/kg" 12 sp `Fg3`; stepper row: 44 dp `Bg1` circles "−" / "+", centre pill `Bg1` "Target 170 W · 0%". Keep the on-target breathing halo but tint it with the zone colour at 16 % as today.
Flanks: 170 dp cards, `Bg1` @ 70 % alpha, radius 20 dp, padding 16 dp 20 dp, centred; label 12 sp `Fg3`; value `displayM` `Fg1` + unit 14 sp `Fg3`. Cadence footer: 4 dp target band bar (`Bg2` track, `Fg3` band, 3 × 10 dp `Fg1` marker) + "Target 85–95" 11 sp. HR footer: ZoneBadge "Z3 · Aerobic". **HR value is `Fg1`, not red.**
Segment row: 10 dp square in zone colour (replaces "▲"), "Tempo · 83% FTP" 15 sp / 700, timer chip (30 dp pill `Bg1`, `timer` icon `Fg3`, `m:ss` 14 sp / 700 — keep the last-5-seconds beat), spacer, "Up next · Z1 Recovery 1:00" 13 sp `Fg3` with zone name in zone colour.
Timeline: 56 dp, bars radius 3 dp top, gap 2 dp; completed and current steps full zone colour, upcoming at 45 % alpha; cursor 2 dp `Fg1`.
Backdrop (`HorizonRidgeBackdrop`): far ridge `Bg1` @ 55 %, mid ridge (workout profile) `Bg2` @ 50 % with a 1.2 dp crest stroke in zone colour @ 55 %, near silhouette `Bg0` @ 90 %. Remove the star field and the dashed contour line.
"FINISHED!" overlay: trophy icon → `Fg1`, text `h1` "Finished" (sentence case), button = PrimaryBtn "View summary".

## Interactions & motion

Unchanged from the current app (durations 120 / 200 / 320 ms, `cubic-bezier(0.2,0.7,0.2,1)`). Press: surface darkens one step (`Bg1` → `Bg2`, `Bg2` → `Bg3`); primary button scales to 0.98. Focus: 2 dp `Fg1` ring. Reduced motion: halo and spinner become static.

## State management

No new state. Every screen keeps its existing view model and flows; only the composables' styling changes.

## Assets

- **Font**: Manrope variable TTF (OFL) — download from Google Fonts and add as `res/font/manrope_variable.ttf`. Remove `bebas_neue_regular.ttf`, `inter_variable.ttf`, `jetbrains_mono_variable.ttf`.
- **Icons**: Material Symbols Outlined, weight 300 (Compose `Icons.Outlined.*` is acceptable). New mappings: Progress tab `TrendingUp`; HUD segment timer `Timer`; no `LocalFireDepartment` flame.
- No images.

## Files in this bundle

- `Slate Screens.dc.html` — the ten redesigned screens (open in a browser; needs `support.js` alongside).
- `Slate Components.dc.html` — component sheet with palette, type, buttons, chips, cards, inputs, tab bar.
- `slate/DESIGN_SYSTEM.md` — system rationale and rules.
- `slate/tokens.css` — CSS variables (for the website later).
- `slate/Color.kt`, `slate/Type.kt` — drop-in replacements for `ui/theme/Color.kt` and `ui/theme/Type.kt`.

## Suggested order of work

1. Swap `Color.kt`, `Type.kt`, add Manrope, bump `Radii`. Build. The app is now slate-coloured but still shaped like the old one.
2. Rework `Primitives.kt` and `DeviceStatus.kt` (buttons, tab bar, cards, chips, badges, AppBar → title block).
3. Remove `.uppercase()`, tinted wells, gradients and borders screen by screen, following the Screens section.
4. Ride HUD last: `RideHero.kt` backdrop and hero, `RideScreen.kt` header, flanks and segment row, `RidgeTimeline.kt` colours.
