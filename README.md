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

The website is hosted using CodeFloe Pages. The content of this directory is pushed to the `pages` branch of the CodeFloe repository by [scripts/deploy_website.ps1](../scripts/deploy_website.ps1).

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

The `.domains` file inside this directory ensures the site is served from `indoorbike.app`.
