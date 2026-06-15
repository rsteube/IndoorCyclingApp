/* ============================================================
   Indoor Bike - "Open in editor" link badges
   Single source of truth: every badge is generated as a
   self-contained SVG string. The same string is used for the
   live gallery preview, the copy-paste embed code, and the
   downloadable .svg file.

   Geometry is computed from real font metrics (canvas
   measureText) so widths are pixel-exact once Bebas Neue is
   loaded on the page.
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

  var FAM_DISPLAY = '"Bebas Neue","Oswald","Arial Narrow",sans-serif';
  var FAM_BODY    = '"Inter","Helvetica Neue",Arial,sans-serif';

  // The chainring + pulse mark, designed for a dark tile.
  // gradient id is suffixed so multiple inline badges don't collide.
  function markMarkup(id) {
    return ''
      + '<defs><linearGradient id="g' + id + '" x1="0" y1="0" x2="64" y2="64" gradientUnits="userSpaceOnUse">'
      + '<stop offset="0%" stop-color="#FF9847"/><stop offset="100%" stop-color="#D54F0A"/></linearGradient></defs>'
      + '<path d="M 4 28 L 14 18 L 22 24 L 32 12 L 44 22 L 54 16 L 60 22" stroke="url(#g' + id + ')" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'
      + '<circle cx="16" cy="48" r="8" stroke="#F4ECDF" stroke-width="2.6" fill="none"/>'
      + '<circle cx="48" cy="48" r="8" stroke="#F4ECDF" stroke-width="2.6" fill="none"/>'
      + '<g stroke="#F4ECDF" stroke-width="1" stroke-linecap="round" opacity="0.7">'
      + '<line x1="16" y1="38" x2="16" y2="40"/><line x1="16" y1="56" x2="16" y2="58"/><line x1="6" y1="48" x2="8" y2="48"/><line x1="24" y1="48" x2="26" y2="48"/>'
      + '<line x1="48" y1="38" x2="48" y2="40"/><line x1="48" y1="56" x2="48" y2="58"/><line x1="38" y1="48" x2="40" y2="48"/><line x1="56" y1="48" x2="58" y2="48"/></g>'
      + '<g stroke="#F47A1F" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" fill="none">'
      + '<line x1="32" y1="48" x2="28" y2="32"/><line x1="28" y1="32" x2="44" y2="30"/><line x1="32" y1="48" x2="44" y2="38"/><line x1="32" y1="48" x2="16" y2="48"/>'
      + '<line x1="16" y1="48" x2="28" y2="32"/><line x1="44" y1="30" x2="44" y2="38"/><line x1="44" y1="38" x2="48" y2="48"/></g>'
      + '<path d="M 40 28 Q 44 26 48 28 Q 50 30 49 33" stroke="#F47A1F" stroke-width="2.4" stroke-linecap="round" fill="none"/>'
      + '<line x1="24" y1="32" x2="32" y2="32" stroke="#F47A1F" stroke-width="2.4" stroke-linecap="round"/>';
  }

  // label -> text content
  var LABELS = {
    'open-in': { eyebrow: 'OPEN IN', main: 'INDOOR BIKE', split: 'INDOOR', kind: 'brand' },
    'edit-in': { eyebrow: 'EDIT IN', main: 'INDOOR BIKE', split: 'INDOOR', kind: 'brand' },
    'ride':    { eyebrow: 'INDOOR BIKE', main: 'RIDE THIS WORKOUT', kind: 'action' },
    'open-wk': { eyebrow: 'INDOOR BIKE', main: 'OPEN THIS WORKOUT', kind: 'action' }
  };
  var HUMAN = {
    'open-in': 'Open in Indoor Bike',
    'edit-in': 'Edit in Indoor Bike',
    'ride': 'Ride this workout',
    'open-wk': 'Open this workout'
  };

  // size presets (two-line)
  var SIZE = {
    std: { H: 50, r: 12, padL: 14, padR: 18, tile: 34, gap: 12,
           ey: 9.5, eyLS: 1.6, mn: 23, mnLS: 0.6, eyY: 20, mnY: 39 },
    sm:  { H: 38, r: 10, padL: 11, padR: 14, tile: 26, gap: 10,
           ey: 8,   eyLS: 1.2, mn: 17.5, mnLS: 0.4, eyY: 15.5, mnY: 30 }
  };
  // chip presets (single line)
  var CHIP = {
    std: { H: 36, r: 10, padL: 10, padR: 14, tile: 26, gap: 9,  mn: 18,   mnLS: 0.6 },
    sm:  { H: 28, r: 8,  padL: 8,  padR: 11, tile: 20, gap: 7,  mn: 14.5, mnLS: 0.4 }
  };

  function palette(style, theme) {
    if (style === 'filled') {
      return { bg: 'url(#bg__ID__)', grad: true, border: 'rgba(0,0,0,0.16)',
               tile: '#100C08', eyebrow: 'rgba(26,10,0,0.62)', main: '#1A0A00',
               brand: '#1A0A00', twoTone: false, arrow: 'rgba(26,10,0,0.55)' };
    }
    if (theme === 'light') {
      return { bg: '#FFFFFF', border: 'rgba(20,16,12,0.14)', tile: '#0F0C09',
               eyebrow: '#8A7F76', main: '#1A1715', brand: '#E0660D',
               twoTone: true, arrow: '#E0660D' };
    }
    return { bg: '#181410', border: 'rgba(255,236,210,0.16)', tile: '#0F0C09',
             eyebrow: '#8A7E6C', main: '#F4ECDF', brand: '#F47A1F',
             twoTone: true, arrow: '#F47A1F' };
  }

  var _uid = 0;

  function build(opts) {
    opts = opts || {};
    var key = opts.label || 'open-in';
    var L = LABELS[key];
    var style = opts.style || 'filled';     // filled | outline
    var theme = opts.theme || 'dark';        // dark | light  (background the badge sits on)
    var size = opts.size || 'std';           // std | sm
    var chip = !!opts.chip;
    var pal = palette(style, theme);
    var id = (++_uid);

    var tile, x0, W, H, r, body;

    if (chip) {
      var c = CHIP[size];
      H = c.H; r = c.r;
      tile = c.tile;
      x0 = c.padL + tile + c.gap;
      var chipText = 'INDOOR BIKE';
      var mainW = measure(chipText, '400', c.mn, FAM_DISPLAY, c.mnLS);
      var arrW = measure('  \u2192', '400', c.mn, FAM_DISPLAY, c.mnLS);
      W = Math.round(x0 + mainW + arrW + c.padR);
      var tY = (H - tile) / 2;
      var baseline = H / 2 + c.mn * 0.34;
      body = ''
        + tileGroup(c.padL, tY, tile, pal.tile, id)
        + '<text x="' + x0 + '" y="' + r0(baseline) + '" xml:space="preserve" '
        +   'font-family=\'' + FAM_DISPLAY + '\' font-weight="400" font-size="' + c.mn + '" '
        +   'letter-spacing="' + c.mnLS + '" text-transform="uppercase">'
        +   tspans(chipText, pal, true)
        +   '<tspan fill="' + pal.arrow + '">  \u2192</tspan>'
        + '</text>';
    } else {
      var s = SIZE[size];
      H = s.H; r = s.r;
      tile = s.tile;
      x0 = s.padL + tile + s.gap;
      var eyW = measure(L.eyebrow, (L.kind === 'action' ? '700' : '600'), s.ey, FAM_BODY, s.eyLS);
      var mnW = measure(L.main, '400', s.mn, FAM_DISPLAY, s.mnLS);
      W = Math.round(x0 + Math.max(eyW, mnW) + s.padR);
      var tY2 = (H - tile) / 2;
      var eyFill = (L.kind === 'action') ? pal.brand : pal.eyebrow;
      var eyWeight = (L.kind === 'action') ? '700' : '600';
      body = ''
        + tileGroup(s.padL, tY2, tile, pal.tile, id)
        + '<text x="' + x0 + '" y="' + s.eyY + '" '
        +   'font-family=\'' + FAM_BODY + '\' font-weight="' + eyWeight + '" font-size="' + s.ey + '" '
        +   'letter-spacing="' + s.eyLS + '" fill="' + eyFill + '">' + esc(L.eyebrow) + '</text>'
        + '<text x="' + x0 + '" y="' + s.mnY + '" xml:space="preserve" '
        +   'font-family=\'' + FAM_DISPLAY + '\' font-weight="400" font-size="' + s.mn + '" '
        +   'letter-spacing="' + s.mnLS + '">'
        +   mainTspans(L, pal)
        + '</text>';
    }

    var defs = pal.grad
      ? '<defs><linearGradient id="bg' + id + '" x1="0" y1="0" x2="0" y2="1">'
        + '<stop offset="0%" stop-color="#FF9847"/><stop offset="55%" stop-color="#F47A1F"/>'
        + '<stop offset="100%" stop-color="#E0660D"/></linearGradient></defs>'
      : '';
    var bgFill = pal.grad ? pal.bg.replace('__ID__', id) : pal.bg;

    var svg = ''
      + '<svg xmlns="http://www.w3.org/2000/svg" width="' + W + '" height="' + H + '" '
      +   'viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="' + esc(HUMAN[key]) + '" '
      +   'font-family=\'' + FAM_BODY + '\'>'
      + '<style>@import url(\'https://fonts.googleapis.com/css2?family=Bebas+Neue&amp;family=Inter:wght@600;700&amp;display=swap\');</style>'
      + defs
      + '<rect x="0.5" y="0.5" width="' + (W - 1) + '" height="' + (H - 1) + '" rx="' + r + '" '
      +   'fill="' + bgFill + '" stroke="' + pal.border + '" stroke-width="1"/>'
      + body
      + '</svg>';
    return { svg: svg, w: W, h: H, human: HUMAN[key] };
  }

  function tileGroup(x, y, size, fill, id) {
    var pad = size * 0.13;
    var inner = size - pad * 2;
    var scale = inner / 64;
    var rad = r0(size * 0.27);
    return ''
      + '<rect x="' + r0(x) + '" y="' + r0(y) + '" width="' + size + '" height="' + size + '" rx="' + rad + '" fill="' + fill + '"/>'
      + '<g transform="translate(' + r0(x + pad) + ',' + r0(y + pad) + ') scale(' + r3(scale) + ')">'
      + markMarkup(id) + '</g>';
  }

  function mainTspans(L, pal) {
    if (pal.twoTone && L.split && L.main.indexOf(L.split) === 0) {
      var rest = L.main.slice(L.split.length); // e.g. " BIKE"
      return '<tspan fill="' + pal.main + '">' + esc(L.split) + '</tspan>'
           + '<tspan fill="' + pal.brand + '">' + esc(rest) + '</tspan>';
    }
    return '<tspan fill="' + pal.main + '">' + esc(L.main) + '</tspan>';
  }
  // chip wordmark two-tone "INDOOR BIKE"
  function tspans(text, pal, twoTone) {
    if (twoTone && pal.twoTone) {
      return '<tspan fill="' + pal.main + '">INDOOR</tspan><tspan fill="' + pal.brand + '"> BIKE</tspan>';
    }
    return '<tspan fill="' + pal.main + '">' + esc(text) + '</tspan>';
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function r0(n) { return Math.round(n); }
  function r3(n) { return Math.round(n * 1000) / 1000; }

  global.IBBadge = { build: build, LABELS: LABELS, HUMAN: HUMAN };
})(window);
