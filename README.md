# Indoor Bike App Website & Editor

This directory contains the promotional website and workout editor for the Indoor Bike App.

## Design system

The site runs on **Slate**, the same system as the app: five slate-blue surfaces
(`--bg-0` … `--bg-4`), three cool-white foregrounds (`--fg-1` … `--fg-3`), one
typeface (Manrope), and no accent colour. Power zones are the only saturated
colour anywhere. Section labels are sentence case - there are no tracked caps.

- Tokens live in [`styles.css`](styles.css) `:root`. Every page except
  [`badges/`](badges/) loads that file; the badges page repeats the same tokens
  in [`badges/tokens.css`](badges/tokens.css) because it is self-contained.
- Cards are `--bg-1` on a `--bg-0` section and `--bg-0` on a `--bg-1` section
  (`.section-alt`), 20 px radius, no border, no shadow.
- Buttons are pills: primary is a `--fg-1` fill with `--on-inverse` text,
  secondary sits one surface step above whatever it is on.

## No third-party requests

The home page, and every page that does not need a JS library, loads nothing
from another origin:

- **Fonts** - `fonts/manrope_variable.ttf` is self-hosted (OFL), the same file
  the Android app ships.
- **Icons** - Material Symbols Outlined at weight 300 (Apache 2.0), inlined as
  an SVG `<symbol>` sprite at the top of the `<body>` of each page that uses
  them (today: the home page) and referenced with
  `<svg class="icon"><use href="#i-name"></use></svg>`. A page carries only the
  glyphs it uses. To add one, copy the path from
  `https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/<name>/materialsymbolsoutlined/<name>_wght300_24px.svg`
  into that page's sprite as `<symbol id="i-<name>" viewBox="0 -960 960 960">`.
- **Badges** - the SVGs in [`badges/`](badges/) embed no webfont either. They
  ask for Manrope first and fall back to the reader's system sans, so an
  embedded badge stays a single request on someone else's page.

The editor, the community browser and the share page do load `pako` (and
`qrcode-generator` in the editor) from jsDelivr - they need those to build and
read the compressed `#wz=` workout links.

## Deployment to CodeFloe Pages

The website is hosted on CodeFloe Pages, which since 2026-09-20 runs on
CodeFloe's own servers (git-pages) instead of statichost.eu. This directory is
plain HTML with no build step: it is pushed to the `pages` branch of the
CodeFloe repository by [scripts/deploy_website.ps1](../scripts/deploy_website.ps1),
and CodeFloe publishes whatever that branch holds.

**To deploy an update:**
1. Make your changes and commit them to the `main` branch.
2. Run the deployment script from the project root:
   ```powershell
   .\scripts\deploy_website.ps1
   ```
   Or run the git command manually:
   ```powershell
   git subtree push --prefix website origin pages
   ```
3. Check it arrived. The branch is published within seconds at
   `https://snapptechnology.codefloe.page/indoorcyclingapp/` (lowercase) and the
   older `https://snapptechnology-indoorcyclingapp.codefloe.page/`, before and
   regardless of the custom domain. Then check the domain itself, e.g.
   `curl -s https://indoorbike.app/.well-known/assetlinks.json`.

**Where the setup lives.** Two places outside this directory, both needed:

- **Repository Pages settings on CodeFloe**: the branch to publish (`pages`),
  and the Domains section, with `indoorbike.app` as the primary domain and
  `www.indoorbike.app` beside it. The Domains section is what tells CodeFloe
  which repository a domain belongs to. The `.domains` file here was
  statichost.eu's way of saying the same thing and is kept only as a record.
- **DNS at GoDaddy** (`ns21/ns22.domaincontrol.com`). GoDaddy has no ALIAS
  record for the bare domain, so the apex gets A and AAAA records for CodeFloe's
  Pages servers, and `www` a CNAME:

  ```
  indoorbike.app.      A     142.132.154.74
  indoorbike.app.      A     142.132.154.75
  indoorbike.app.      A     142.132.154.80
  indoorbike.app.      AAAA  2a01:4f8:261:2a16::2
  indoorbike.app.      AAAA  2a01:4f8:261:2a14::2
  indoorbike.app.      AAAA  2a01:4f8:261:2a0b::2
  www.indoorbike.app.  CNAME snapptechnology.codefloe.page.
  ```

  Those addresses can change: `nslookup snapptechnology.codefloe.page` gives the
  current ones, and [CodeFloe's Pages docs](https://docs.codefloe.com/pages/configuration/#custom-domains)
  list them. CodeFloe issues and renews the certificate itself once the records
  resolve.

**Load-bearing paths.** Every shipped APK fetches `/registry.json`, Android
verifies the Intervals.icu sign-in link against `/.well-known/assetlinks.json`
(served as `application/json`, no redirect), and QR codes in the wild point at
`/editor/` and `/share/`. All of them must keep answering at the root of
`indoorbike.app`, which a custom domain on CodeFloe Pages does.
