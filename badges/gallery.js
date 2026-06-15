/* Gallery wiring for the Indoor Bike link badges.
   Builds every badge from IBBadge, renders preview cards with
   copy-paste embed code, and prepares the downloadable SVG set. */
(function () {
  'use strict';

  var EDITOR = 'https://indoorbike.app/editor/';
  // a representative workout for the embed examples
  var SAMPLE = '?v=1&name=Sweet+Spot+4x10&ftp=250&s=10m@55,4x(10m@90;5m@60),8m@50';
  var HOST = 'https://indoorbike.app/badges/';

  function fname(o) {
    var p = ['indoorbike', o.label];
    if (o.chip) { p.push('chip'); if (o.style === 'outline') p.push('outline'); }
    else {
      p.push(o.style);
      if (o.style === 'outline') p.push(o.theme);
    }
    if (o.size === 'sm') p.push('sm');
    return p.join('-') + '.svg';
  }

  function prettySvg(svg) {
    // one tag per line for readable code blocks
    return svg.replace(/></g, '>\n<');
  }

  function embedInline(o, built) {
    return '<a href="' + EDITOR + SAMPLE + '"\n   target="_blank" rel="noopener">\n  '
      + prettySvg(built.svg).replace(/\n/g, '\n  ')
      + '\n</a>';
  }
  function embedImg(o, built) {
    return '<a href="' + EDITOR + SAMPLE + '" target="_blank" rel="noopener">\n'
      + '  <img src="' + HOST + fname(o) + '"\n'
      + '       alt="' + built.human + '" width="' + built.w + '" height="' + built.h + '">\n'
      + '</a>';
  }

  var EXPORTS = [];      // {fname, svg} for download + project export
  var seen = {};
  function registerExport(o, built) {
    var fn = fname(o);
    if (seen[fn]) return fn;
    seen[fn] = 1;
    EXPORTS.push({ fname: fn, svg: built.svg, w: built.w, h: built.h, label: built.human, bg: o.theme === 'light' ? 'light' : 'dark' });
    return fn;
  }

  function makeLink(o, built) {
    var a = document.createElement('a');
    a.className = 'badge-link';
    a.href = EDITOR + SAMPLE;
    a.target = '_blank';
    a.rel = 'noopener';
    a.title = built.human;
    a.innerHTML = built.svg;
    return a;
  }

  // build a full preview card with code panel + actions
  function card(o) {
    var built = IBBadge.build(o);
    var fn = registerExport(o, built);

    var el = document.createElement('div');
    el.className = 'card';

    var stage = document.createElement('div');
    stage.className = 'stage';
    stage.setAttribute('data-bg', o.bg || (o.theme === 'light' ? 'light' : 'dark'));
    stage.appendChild(makeLink(o, built));
    el.appendChild(stage);

    var info = document.createElement('div');
    info.className = 'info';
    info.innerHTML =
      '<div class="nm"><h3>' + o.title + '</h3><span class="dim">' + built.w + '×' + built.h + '</span></div>'
      + (o.desc ? '<p class="desc">' + o.desc + '</p>' : '');
    el.appendChild(info);

    // code panel
    var inline = embedInline(o, built);
    var img = embedImg(o, built);
    var code = document.createElement('div');
    code.className = 'code';
    code.innerHTML =
      '<div class="tabs">'
      + '<button data-t="inline" aria-selected="true">Inline SVG</button>'
      + '<button data-t="img">Image tag</button>'
      + '</div>'
      + '<div class="panel">'
      + '<button class="copy">Copy</button>'
      + '<pre data-inline="1"><code></code></pre>'
      + '<pre data-img="1" hidden><code></code></pre>'
      + '</div>';
    code.querySelector('[data-inline] code').textContent = inline;
    code.querySelector('[data-img] code').textContent = img;
    el.appendChild(code);

    var actions = document.createElement('div');
    actions.className = 'actions';
    var dl = document.createElement('button');
    dl.className = 'mini';
    dl.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> ' + fn;
    dl.addEventListener('click', function () { downloadSvg(fn, built.svg); });
    actions.appendChild(dl);
    el.appendChild(actions);

    // tab switching
    var tabs = code.querySelectorAll('.tabs button');
    tabs.forEach(function (b) {
      b.addEventListener('click', function () {
        tabs.forEach(function (x) { x.setAttribute('aria-selected', 'false'); });
        b.setAttribute('aria-selected', 'true');
        var t = b.getAttribute('data-t');
        code.querySelector('[data-inline]').hidden = (t !== 'inline');
        code.querySelector('[data-img]').hidden = (t !== 'img');
      });
    });
    // copy
    code.querySelector('.copy').addEventListener('click', function () {
      var shown = code.querySelector('.panel pre:not([hidden]) code').textContent;
      copy(shown, this);
    });

    return el;
  }

  function copy(text, btn) {
    var done = function () { btn.textContent = 'Copied'; btn.classList.add('ok');
      setTimeout(function () { btn.textContent = 'Copy'; btn.classList.remove('ok'); }, 1400); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
    } else { fallbackCopy(text); done(); }
  }
  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }

  function downloadSvg(name, svg) {
    var blob = new Blob([svg], { type: 'image/svg+xml' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
  }

  // ---------- render ----------
  function mount(id, el) { var t = document.getElementById(id); if (t) t.appendChild(el); }

  document.fonts.ready.then(function () {
    render();
  });
  // fallback if fonts API stalls
  setTimeout(function () { if (!EXPORTS.length) render(); }, 1500);

  var rendered = false;
  function render() {
    if (rendered) return; rendered = true;

    // hero - big filled, standard
    mount('heroBadge', makeLink({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std' },
      IBBadge.build({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std' })));

    // styles
    mount('styleGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Filled · ember', desc: 'The default. Warm, high-contrast, reads on any background.' }));
    mount('styleGrid', card({ label: 'open-in', style: 'outline', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Outline · dark', desc: 'Quieter. For dark UIs where a filled badge would shout.' }));
    mount('styleGrid', card({ label: 'open-in', style: 'outline', theme: 'light', size: 'std', bg: 'light',
      title: 'Outline · light', desc: 'Dark ink on light pages. Same hairline, inverted.' }));
    mount('styleGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std', chip: true, bg: 'dark',
      title: 'Chip · compact', desc: 'Mark, wordmark, arrow. For tight rows and toolbars.' }));

    // sizes
    mount('sizeGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Standard', desc: 'Beside a button or a workout title.' }));
    mount('sizeGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'sm', bg: 'dark',
      title: 'Small', desc: 'For table rows, cards, and dense lists.' }));

    // wording
    mount('wordGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Open in Indoor Bike', desc: 'Brand-forward. The recommended default.' }));
    mount('wordGrid', card({ label: 'edit-in', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Edit in Indoor Bike', desc: 'When the rider will tweak before riding.' }));
    mount('wordGrid', card({ label: 'ride', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Ride this workout', desc: 'Action-forward, brand in the eyebrow.' }));
    mount('wordGrid', card({ label: 'open-wk', style: 'filled', theme: 'dark', size: 'std', bg: 'dark',
      title: 'Open this workout', desc: 'Neutral action when context is clear.' }));

    // context
    mount('ctxGrid', card({ label: 'open-in', style: 'filled', theme: 'dark', size: 'std', bg: 'busy',
      title: 'Filled on a busy surface', desc: 'The ember holds up against noise and photography.' }));
    mount('ctxGrid', card({ label: 'open-in', style: 'outline', theme: 'light', size: 'std', bg: 'light',
      title: 'Outline on light', desc: 'Sits politely inside a light third-party page.' }));

    buildDownloads();
    wireDownloadAll();
    // expose for project export
    window.IB_EXPORTS = EXPORTS;
  }

  function buildDownloads() {
    var grid = document.getElementById('dlGrid');
    if (!grid) return;
    EXPORTS.forEach(function (e) {
      var row = document.createElement('div');
      row.className = 'dl';
      var kb = (new Blob([e.svg]).size / 1024).toFixed(1);
      row.innerHTML =
        '<div class="prev" data-bg="' + e.bg + '">' + e.svg + '</div>'
        + '<div class="meta"><div class="fn">' + e.fname + '</div><div class="sz">SVG · ' + kb + ' KB · ' + e.w + '×' + e.h + '</div></div>'
        + '<span class="arr">↓</span>';
      row.addEventListener('click', function () { downloadSvg(e.fname, e.svg); });
      grid.appendChild(row);
    });
  }

  function wireDownloadAll() {
    var btn = document.getElementById('dlAll');
    if (!btn) return;
    btn.addEventListener('click', function () {
      EXPORTS.forEach(function (e, i) {
        setTimeout(function () { downloadSvg(e.fname, e.svg); }, i * 250);
      });
    });
  }
})();
