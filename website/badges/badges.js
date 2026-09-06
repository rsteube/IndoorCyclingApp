/* ============================================================
   Indoor Bike - "Open in editor" link badges
   Single source of truth: every badge is generated as a
   self-contained SVG string. The same string is used for the
   live gallery preview, the copy-paste embed code, and the
   downloadable .svg file.

   Slate: one typeface (Manrope), no accent colour, sentence
   case. The badge embeds no webfont and makes no third-party
   request - it names Manrope first and falls back to the
   reader's system sans, so it renders anywhere.

   Geometry is computed from real font metrics (canvas
   measureText) plus a small slack so a fallback face that
   sets a touch wider still fits inside the pill.
   ============================================================ */
(function (global) {
  'use strict';

  // ---- shared canvas for text measurement ----
  var _cv = document.createElement('canvas');
  var _ctx = _cv.getContext('2d');
  function measure(text, weight, size, family, ls) {
    _ctx.font = weight + ' ' + size + 'px ' + family;
    var w = _ctx.measureText(text).width;
    if (ls) w += ls * Math.max(0, text.length - 1);
    return w;
  }

  /* Manrope first (the site and the app ship it); after that the
     reader's system sans. No @import, so an embedded badge stays a
     single request. */
  var FAM = '"Manrope",system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif';
  var FAM_DISPLAY = FAM;
  var FAM_BODY = FAM;
  // slack for a fallback face that measures wider than Manrope
  var SLACK = 1.05;

  // The Slate mark, always on a dark slate tile. At badge size this is the
  // mark's own 32 px simplification: no saddle, no seat stay, and a heavier
  // stroke, because both are a pixel of noise inside a 26 px tile.
  function markMarkup() {
    return ''
      + '<g fill="none" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">'
      + '<path d="M4 26 14 16 22 22 32 10 44 20 54 14 60 20" stroke="#9AA1B0"/>'
      + '<g stroke="#F2F4F7">'
      + '<circle cx="48" cy="48" r="8"/>'
      + '<path d="M32 48 28 32 44 30 44 38 48 48 M32 48 16 48 28 32 M24 32 32 32"/>'
      + '<circle cx="16" cy="48" r="7" fill="#F2F4F7" stroke="none"/>'
      + '<path d="M16 55 16 58 M6 58 26 58"/>'
      + '</g></g>';
  }

  // label -> text content (sentence case; Slate has no shouting)
  var LABELS = {
    'open-in': { eyebrow: 'Open in', main: 'Indoor Bike', kind: 'brand' },
    'edit-in': { eyebrow: 'Edit in', main: 'Indoor Bike', kind: 'brand' },
    'ride':    { eyebrow: 'Indoor Bike', main: 'Ride this workout', kind: 'action' },
    'open-wk': { eyebrow: 'Indoor Bike', main: 'Open this workout', kind: 'action' }
  };
  var HUMAN = {
    'open-in': 'Open in Indoor Bike',
    'edit-in': 'Edit in Indoor Bike',
    'ride': 'Ride this workout',
    'open-wk': 'Open this workout'
  };

  // size presets (two-line)
  var SIZE = {
    std: { H: 50, r: 25, padL: 8, padR: 18, tile: 34, gap: 12,
           ey: 11, eyLS: 0, mn: 17, mnLS: -0.2, eyY: 21, mnY: 38 },
    sm:  { H: 38, r: 19, padL: 6, padR: 14, tile: 26, gap: 10,
           ey: 9,  eyLS: 0, mn: 13.5, mnLS: -0.15, eyY: 16, mnY: 30 }
  };
  // chip presets (single line)
  var CHIP = {
    std: { H: 36, r: 18, padL: 5, padR: 14, tile: 26, gap: 9, mn: 15,   mnLS: -0.2 },
    sm:  { H: 28, r: 14, padL: 4, padR: 11, tile: 20, gap: 7, mn: 12.5, mnLS: -0.15 }
  };

  function palette(style, theme) {
    if (style === 'filled') {
      // Slate's primary action: the cool white fill, slate ink.
      return { bg: '#F2F4F7', border: 'rgba(49,62,80,0.16)', tile: '#313e50',
               eyebrow: 'rgba(49,62,80,0.66)', main: '#313e50', arrow: 'rgba(49,62,80,0.66)' };
    }
    if (theme === 'light') {
      return { bg: '#FFFFFF', border: 'rgba(27,35,48,0.14)', tile: '#313e50',
               eyebrow: '#7D8798', main: '#1B2330', arrow: '#4A5566' };
    }
    return { bg: '#3a435e', border: 'rgba(242,244,247,0.16)', tile: '#313e50',
             eyebrow: '#9AA1B0', main: '#F2F4F7', arrow: '#C5CAD6' };
  }

  function build(opts) {
    opts = opts || {};
    var key = opts.label || 'open-in';
    var L = LABELS[key];
    var style = opts.style || 'filled';     // filled | outline
    var theme = opts.theme || 'dark';        // dark | light  (background the badge sits on)
    var size = opts.size || 'std';           // std | sm
    var chip = !!opts.chip;
    var pal = palette(style, theme);

    var tile, x0, W, H, r, body;

    if (chip) {
      var c = CHIP[size];
      H = c.H; r = c.r;
      tile = c.tile;
      x0 = c.padL + tile + c.gap;
      var chipText = 'Indoor Bike';
      var mainW = measure(chipText, '800', c.mn, FAM_DISPLAY, c.mnLS) * SLACK;
      var arrW = measure('  →', '800', c.mn, FAM_DISPLAY, c.mnLS) * SLACK;
      W = Math.round(x0 + mainW + arrW + c.padR);
      var tY = (H - tile) / 2;
      var baseline = H / 2 + c.mn * 0.35;
      body = ''
        + tileGroup(c.padL, tY, tile, pal.tile)
        + '<text x="' + x0 + '" y="' + r0(baseline) + '" xml:space="preserve" '
        +   'font-family=\'' + FAM_DISPLAY + '\' font-weight="800" font-size="' + c.mn + '" '
        +   'letter-spacing="' + c.mnLS + '">'
        +   '<tspan fill="' + pal.main + '">' + esc(chipText) + '</tspan>'
        +   '<tspan fill="' + pal.arrow + '">  →</tspan>'
        + '</text>';
    } else {
      var s = SIZE[size];
      H = s.H; r = s.r;
      tile = s.tile;
      x0 = s.padL + tile + s.gap;
      var eyW = measure(L.eyebrow, '600', s.ey, FAM_BODY, s.eyLS) * SLACK;
      var mnW = measure(L.main, '800', s.mn, FAM_DISPLAY, s.mnLS) * SLACK;
      W = Math.round(x0 + Math.max(eyW, mnW) + s.padR);
      var tY2 = (H - tile) / 2;
      body = ''
        + tileGroup(s.padL, tY2, tile, pal.tile)
        + '<text x="' + x0 + '" y="' + s.eyY + '" '
        +   'font-family=\'' + FAM_BODY + '\' font-weight="600" font-size="' + s.ey + '" '
        +   'fill="' + pal.eyebrow + '">' + esc(L.eyebrow) + '</text>'
        + '<text x="' + x0 + '" y="' + s.mnY + '" xml:space="preserve" '
        +   'font-family=\'' + FAM_DISPLAY + '\' font-weight="800" font-size="' + s.mn + '" '
        +   'letter-spacing="' + s.mnLS + '" fill="' + pal.main + '">' + esc(L.main) + '</text>';
    }

    var svg = ''
      + '<svg xmlns="http://www.w3.org/2000/svg" width="' + W + '" height="' + H + '" '
      +   'viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(HUMAN[key]) + '" '
      +   'font-family=\'' + FAM_BODY + '\'>'
      + '<rect x="0.5" y="0.5" width="' + (W - 1) + '" height="' + (H - 1) + '" rx="' + r + '" '
      +   'fill="' + pal.bg + '" stroke="' + pal.border + '" stroke-width="1"/>'
      + body
      + '</svg>';
    return { svg: svg, w: W, h: H, human: HUMAN[key] };
  }

  function tileGroup(x, y, size, fill) {
    var pad = size * 0.13;
    var inner = size - pad * 2;
    var scale = inner / 64;
    return ''
      + '<circle cx="' + r0(x + size / 2) + '" cy="' + r0(y + size / 2) + '" r="' + r0(size / 2) + '" fill="' + fill + '"/>'
      + '<g transform="translate(' + r0(x + pad) + ',' + r0(y + pad) + ') scale(' + r3(scale) + ')">'
      + markMarkup() + '</g>';
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function r0(n) { return Math.round(n); }
  function r3(n) { return Math.round(n * 1000) / 1000; }

  global.IBBadge = { build: build, LABELS: LABELS, HUMAN: HUMAN };
})(window);
