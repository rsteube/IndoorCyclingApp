# Indoor Bike - Android UI kit

Interactive, click-thru recreation of the Cadence indoor cycling app at MVP scope. Dark, telemetry-first, F-Droid friendly.

## Files

- `index.html` - interactive prototype with view switcher (all screens or one)
- `Primitives.jsx` - `IBDevice`, `AppBar`, `IconBtn`, `PrimaryBtn`, `SecondaryBtn`, `ZoneBadge`, `MetaPill`, `ZoneBar`, `TabBar`, plus `C` (color tokens) and `FF` (font families)
- `WorkoutScreens.jsx` - `WorkoutListScreen`, `WorkoutDetailScreen`
- `RideScreens.jsx` - `RideScreen` (live HUD), `SummaryScreen`, `ProgressScreen`
- `android-frame.jsx` - original Material 3 starter frame (unused; kept for reference)

## Screens

1. **Workout list** - trainer status strip, filter chips, workout cards with zone profile bar.
2. **Workout detail** - stepped zone profile chart, stats grid, step list, large primary CTA.
3. **In-ride HUD** - full-screen power readout, breathing zone halo, secondary metrics row, timeline strip with progress, controls.
4. **Summary** - hero stats grid, time-in-zone bars, saved-file confirmation.
5. **Progress** - local streak bars, rider level + XP, recent badges. **No backend required.**

## Click-thru flow

`list → (pick) → detail → (start) → ride → (end) → summary → (done) → list`

Tab bar `Progress` icon takes you to the progress screen; back to list any other tab.

## Notes

- The HUD "ticks" every second to demonstrate live telemetry. Pause toggles the timer.
- The zone halo behind the power readout updates live as the active zone changes - this is the only animated background in the entire app, by design.
- All icons are Lucide @ 2px stroke. The cyan/azure brand pulse is the only custom glyph.
- Frame is a custom dark Android shell (38px radius, full-bleed dark) - not the default Material 3 starter, because Indoor Bike App is dark-by-default.
