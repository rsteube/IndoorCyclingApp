# Editor URL Workout API

A way to open the [Indoor Bike workout editor](https://indoorbike.app/editor/) with a
workout **already loaded**, using nothing but plain URL query parameters. No backend, no
account, no SDK - a third-party site (or an LLM) just builds a link and opens it.

> **Quick human-readable version (public website):** [`https://indoorbike.app/api/`](https://indoorbike.app/api/)
> (source: [website/api/index.html](../website/api/index.html)) - the concise, link-it-and-go page.
> This document is the full reference behind it.
>
> **Machine-readable copy for LLMs / codegen:** [`https://indoorbike.app/editor/llms.txt`](https://indoorbike.app/editor/llms.txt)
> (source: [website/editor/llms.txt](../website/editor/llms.txt)). Point a model at that URL
> and it has everything it needs to emit a valid link.

## Why this exists

Another project - say **VertMatch** - can turn training data into a structured power
workout, then deep-link it straight into our editor:

```
https://indoorbike.app/editor/?v=1&name=VertMatch+Threshold&ftp=250&s=10m@50,3x(8m@100;4m@55),5m@50
```

The user opens that link, sees the workout rendered on the timeline, tweaks it if they want,
then hands it to their phone the usual way - **Show QR & link** or **Share to phone** - and
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
| `s` | **yes** | The segments - see [Segment syntax](#segment-syntax) below. This is what makes it a workout. |
| `name` | no | Workout name. Trimmed to 80 characters. |
| `desc` | no | Description. Trimmed to 600 characters. (`description` is accepted as an alias.) |
| `tags` | no | Comma-separated labels, e.g. `tags=Threshold,Sweet Spot`. |
| `ftp` | no | Rider FTP in watts (50–600). Sets the editor's FTP field so the watt preview is correct. Not stored in the workout itself - the workout stays FTP-relative (`% FTP`). |
| `v` | no | API version. Currently `1`. Reserved for forward compatibility; unknown values are ignored. |

Standard URL encoding applies: encode spaces as `+` or `%20`, and percent-encode any `&`, `#`,
or `%` that appear *inside* a value. The structural characters of the segment syntax
(`@ , ; ( ) x`) are URL-safe and may be left as-is.

## Segment syntax

The `s` parameter is a **comma-separated list**. Each item is one of:

### 1. A single segment - `DURATION@POWER[rCADENCE]`

- **`DURATION`** - a number with an optional unit:
  - bare number or `s` suffix → **seconds**: `300`, `300s`, `90s`
  - `m` suffix → **minutes**, decimals allowed: `5m`, `2.5m`
- **`POWER`** - target as an integer **percentage of FTP** (`0`–`300`). `100` = FTP.
- **`rCADENCE`** *(optional)* - a cadence target in rpm, introduced by `r`:
  - `r90` → a **single** target of 90 rpm
  - `r85-95` → a **range**, 85 to 95 rpm

Examples: `300@50` (5 min at 50% FTP), `5m@50` (same), `30s@120`,
`45m@78r70-80` (45 min at 78% FTP spinning 70-80 rpm), `10m@60r95`.

Cadence is optional and independent of power. The trainer holds the power in ERG mode either
way; a cadence target just tells the rider how to spin while it does, which is how you express
low-cadence climbing work or high-cadence leg speed. Values must land in **20–200 rpm**;
anything outside is ignored rather than clamped, because a cadence nobody can hold is more
likely a typo than an intention.

### 2. A repeat group - `Nx(SEG;SEG;…)`

Repeat a parenthesised sequence `N` times. Inside the group, segments are separated by
**semicolons** (`;`), because commas separate top-level items.

```
3x(8m@100;4m@55)
```

→ work 8 min @ 100%, recover 4 min @ 55%, three times (six segments).

### 3. Repeat shorthand - `NxSEG`

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

**Sweet-spot base** - 4×10 min at 90% with 5-min recoveries:

```
https://indoorbike.app/editor/?name=Sweet+Spot+4x10&ftp=230&s=10m@55,4x(10m@90;5m@60),8m@50
```

**VO2 max** - 6×3 min at 115%:

```
https://indoorbike.app/editor/?name=VO2+6x3&tags=VO2+Max&s=12m@55,6x(3m@115;3m@50),8m@50
```

**Flat list** (no repeats), durations in seconds:

```
https://indoorbike.app/editor/?name=Pyramid&s=300@50,300@70,300@90,300@105,300@90,300@70,300@50
```

**Cadence work** - a climbing simulation at grinder cadence, then leg speed at 100+ rpm:

```
https://indoorbike.app/editor/?name=Climb+%2B+Spin&ftp=230&s=10m@55,45m@78r70-80,5m@45,4x(2m@70r105;2m@50),5m@45
```

## Behaviour & limits

- **Segments cap:** 200. Anything beyond is dropped.
- **Clamping:** duration `1s`–`4h`, power `0%`–`300%`, repeat count `1`–`40`.
- **Cadence:** `20`–`200` rpm. Out-of-range or malformed values drop the cadence target and
  keep the segment, rather than discarding the whole segment. A reversed range (`r95-85`) is
  read as the range that was obviously meant.
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
That round-trips the full workout JSON and is what the phone app scans - but it's meant for
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
4. The Indoor Bike phone app imports it via QR scan or the OS share sheet - exactly the same
   path as any other shared workout (see
   [ARCHITECTURE.md → Library screen + QR scanner](ARCHITECTURE.md#library-screen--qr-scanner)).

## Link badges (optional)

For third parties who want a polished call-to-action, there's a set of ready-made
"Open in Indoor Bike" badges (SVG): filled / outline / chip, two sizes, four wordings. They're
hosted on the website with a live gallery, copy-paste embed code, and per-file downloads.

- **Gallery:** [`https://indoorbike.app/badges/`](https://indoorbike.app/badges/)
  (source: [website/badges/](../website/badges/))
- **Hosted SVGs:** `https://indoorbike.app/badges/indoorbike-<label>-<style>[-<theme>][-sm].svg`
  (e.g. `indoorbike-open-in-filled.svg`, `indoorbike-open-in-outline-light.svg`,
  `indoorbike-open-in-chip.svg`).

Two embed forms, both just an anchor wrapping the badge:

```html
<!-- Inline SVG (crispest; full Bebas Neue) -->
<a href="https://indoorbike.app/editor/?s=10m@55,4x(10m@90;5m@60),8m@50" target="_blank" rel="noopener">
  <!-- paste the badge SVG here -->
</a>

<!-- Image tag (simplest; condensed-sans fallback when loaded as an image) -->
<a href="https://indoorbike.app/editor/?s=10m@55,4x(10m@90;5m@60),8m@50" target="_blank" rel="noopener">
  <img src="https://indoorbike.app/badges/indoorbike-open-in-filled.svg" alt="Open in Indoor Bike" width="..." height="...">
</a>
```

**These assets are completely optional and free to use.** No one needs a badge, or our
permission, to link to the editor - a plain text link works just as well. They exist purely as a
convenience. The only ask is to keep them recognisable: don't recolor, stretch, or rotate the
mark, and don't imply an official partnership (it's a link, not a co-brand). Implementation
detail: every badge is generated from a single source (`website/badges/badges.js`,
`IBBadge.build()`), so the gallery preview, the copy embed code, and the downloadable file are
always the same SVG.

## Privacy & data handling

**In plain terms:** the editor has no server, no database, and no account. Everything happens
in your browser, in JavaScript, on your own device. A workout you load or build is **never
uploaded to us** - we don't store any of it, anywhere.

How that holds up technically:

- **Static site, no backend.** `indoorbike.app/editor/` is plain HTML/CSS/JS served as static
  files (Codeberg Pages). There is no application server, no database, and no endpoint that
  receives your workout. The editor contains no upload code - it makes no network request that
  carries workout data.
- **The workout rides in the URL, then stays on the device.** With this API the workout is
  encoded in the query string. Your browser reads it client-side and the editor immediately
  strips the parameters from the address bar (`history.replaceState`). From that point the
  workout lives only in your browser.
- **Local persistence is on *your* device.** So your work survives a refresh, the editor keeps
  the current workout and your FTP in your browser's `localStorage` (keys
  `indoorbike.editor.workout.v1` and `indoorbike.editor.ftp.v1`). That's stored on your machine
  only - it isn't a cookie, it's never sent anywhere, and clearing your site data removes it.
- **No cookies, no analytics, no trackers.** The editor sets no cookies and loads no analytics
  or tracking scripts.
- **Sharing is local too.** *Download .json* builds the file in-browser, *Share to phone* uses
  your operating system's native share sheet, and *Show QR & link* renders the QR code in the
  browser. None of these round-trip through a server.

Two honest caveats, so the picture is complete:

- **A URL is still a URL.** Because the workout lives in the query string, the link can land in
  your browser history, and - like any web request - that URL is part of the HTTP request your
  browser sends to the static host to fetch the page. Don't put anything sensitive in a workout
  name or description, and treat a generated link the way you'd treat any shareable link.
- **Two libraries load from a CDN.** The editor pulls `qrcode-generator` and `pako` from
  `cdn.jsdelivr.net`. Your browser contacts that CDN to download them (it sees your IP, as with
  any CDN-hosted asset) - but **no workout data is sent there**; they're just code the editor
  runs locally.

The phone app keeps the same posture - an imported workout (QR scan, share sheet, or file) is
handled on the device. See the full [privacy policy](https://indoorbike.app/privacy.html).

## Versioning

`v=1` is the current contract. Additive changes (new optional params, new duration units) won't
bump the version. A breaking change would introduce `v=2` and keep `v=1` parsing intact.
