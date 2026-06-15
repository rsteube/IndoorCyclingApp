# Editor URL Workout API

A way to open the [Indoor Bike workout editor](https://indoorbike.app/editor/) with a
workout **already loaded**, using nothing but plain URL query parameters. No backend, no
account, no SDK — a third-party site (or an LLM) just builds a link and opens it.

> **Machine-readable copy for LLMs / codegen:** [`https://indoorbike.app/editor/llms.txt`](https://indoorbike.app/editor/llms.txt)
> (source: [website/editor/llms.txt](../website/editor/llms.txt)). Point a model at that URL
> and it has everything it needs to emit a valid link.

## Why this exists

Another project — say **VertMatch** — can turn training data into a structured power
workout, then deep-link it straight into our editor:

```
https://indoorbike.app/editor/?v=1&name=VertMatch+Threshold&ftp=250&s=10m@50,3x(8m@100;4m@55),5m@50
```

The user opens that link, sees the workout rendered on the timeline, tweaks it if they want,
then hands it to their phone the usual way — **Show QR & link** or **Share to phone** — and
rides it in the Indoor Bike app. The third party never touches our storage or the device; the
URL *is* the integration.

## Endpoint

```
https://indoorbike.app/editor/
```

The workout is described entirely in the **query string** (`?key=value&…`). All parameters are
optional except `s`, which carries the segments. If `s` is absent the editor just opens
normally (with the user's last-edited workout or a sample).

## Parameters

| Param | Required | Description |
| --- | --- | --- |
| `s` | **yes** | The segments — see [Segment syntax](#segment-syntax) below. This is what makes it a workout. |
| `name` | no | Workout name. Trimmed to 80 characters. |
| `desc` | no | Description. Trimmed to 600 characters. (`description` is accepted as an alias.) |
| `tags` | no | Comma-separated labels, e.g. `tags=Threshold,Sweet Spot`. |
| `ftp` | no | Rider FTP in watts (50–600). Sets the editor's FTP field so the watt preview is correct. Not stored in the workout itself — the workout stays FTP-relative (`% FTP`). |
| `v` | no | API version. Currently `1`. Reserved for forward compatibility; unknown values are ignored. |

Standard URL encoding applies: encode spaces as `+` or `%20`, and percent-encode any `&`, `#`,
or `%` that appear *inside* a value. The structural characters of the segment syntax
(`@ , ; ( ) x`) are URL-safe and may be left as-is.

## Segment syntax

The `s` parameter is a **comma-separated list**. Each item is one of:

### 1. A single segment — `DURATION@POWER`

- **`DURATION`** — a number with an optional unit:
  - bare number or `s` suffix → **seconds**: `300`, `300s`, `90s`
  - `m` suffix → **minutes**, decimals allowed: `5m`, `2.5m`
- **`POWER`** — target as an integer **percentage of FTP** (`0`–`300`). `100` = FTP.

Examples: `300@50` (5 min at 50% FTP), `5m@50` (same), `30s@120`.

### 2. A repeat group — `Nx(SEG;SEG;…)`

Repeat a parenthesised sequence `N` times. Inside the group, segments are separated by
**semicolons** (`;`), because commas separate top-level items.

```
3x(8m@100;4m@55)
```

→ work 8 min @ 100%, recover 4 min @ 55%, three times (six segments).

### 3. Repeat shorthand — `NxSEG`

Repeat a single segment `N` times, no parentheses needed:

```
8x30s@120
```

→ eight 30-second efforts at 120% FTP.

### Putting it together

```
s=10m@50,3x(8m@100;4m@55),5m@50
```

A 10-min warm-up, three 8-min threshold blocks with 4-min recoveries, and a 5-min cool-down.

## Worked examples

**Sweet-spot base** — 4×10 min at 90% with 5-min recoveries:

```
https://indoorbike.app/editor/?name=Sweet+Spot+4x10&ftp=230&s=10m@55,4x(10m@90;5m@60),8m@50
```

**VO2 max** — 6×3 min at 115%:

```
https://indoorbike.app/editor/?name=VO2+6x3&tags=VO2+Max&s=12m@55,6x(3m@115;3m@50),8m@50
```

**Flat list** (no repeats), durations in seconds:

```
https://indoorbike.app/editor/?name=Pyramid&s=300@50,300@70,300@90,300@105,300@90,300@70,300@50
```

## Behaviour & limits

- **Segments cap:** 200. Anything beyond is dropped.
- **Clamping:** duration `1s`–`4h`, power `0%`–`300%`, repeat count `1`–`40`.
- **Garbage tolerance:** unparseable items are skipped, not fatal. If `s` is present but
  yields *zero* valid segments, the editor shows an error toast and opens normally.
- **No per-segment labels:** the editor's segment model is duration + power only; it always
  records the interval type as `CONSTANT`. Labels are intentionally not part of v1 because the
  editor can't round-trip them.
- **Overwrite guard (non-blocking):** the workout loads immediately. If the visitor already had
  work in the editor, a toast offers **Undo** for ~12 s, which restores their previous workout.
  No blocking confirmation dialog.
- **URL is cleared** after load (`history.replaceState`) so a refresh doesn't re-prompt.
- **Precedence:** a `?s=` query wins over the binary `#wz=`/`#w=` hash blob if both are present.

## Relationship to the QR / hash format

The app and the editor's own **Show QR & link** button use a different, binary form:
`https://indoorbike.app/share/#wz=<gzip+base64url-json>` (and the legacy `#w=<base64url-json>`).
That round-trips the full workout JSON and is what the phone app scans — but it's meant for
machines, not hand-authoring. The query-param API documented here is the one to generate from
another website: it's readable, diffable, and needs no compression library. Both ultimately
load the same workout model:

```jsonc
{ "name": "...", "description": "...", "tags": ["..."],
  "segments": [ { "lengthInSeconds": 600, "powerPercentFTP": 50, "intervalType": "CONSTANT" }, ... ] }
```

## The full hand-off flow

1. Third-party site builds an `https://indoorbike.app/editor/?…` URL and links/redirects to it.
2. Editor opens with the workout on the timeline.
3. User taps **Show QR & link** (desktop) or **Share to phone** (mobile).
4. The Indoor Bike phone app imports it via QR scan or the OS share sheet — exactly the same
   path as any other shared workout (see
   [ARCHITECTURE.md → Library screen + QR scanner](ARCHITECTURE.md#library-screen--qr-scanner)).

## Versioning

`v=1` is the current contract. Additive changes (new optional params, new duration units) won't
bump the version. A breaking change would introduce `v=2` and keep `v=1` parsing intact.
