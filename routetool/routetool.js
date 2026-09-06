/* ============================================================
   Indoor Bike - route publishing tool

   Turns a GPX into the JSON a community repository publishes,
   and keeps hold of the file being built so routes can be added
   to it one at a time without the earlier ones being lost.

   Everything runs in the tab: the file is read with FileReader
   and the statistics come from assets/routelib.js, which is the
   JavaScript port of the same maths the app runs. The only
   request that carries route data is the elevation lookup, and
   that is opt-in.
   ============================================================ */

(function () {
  'use strict';

  const R = window.RouteLib;
  const $ = (s) => document.querySelector(s);

  // Open-Meteo takes a list of coordinates per request. Batching keeps a long
  // route to a handful of calls instead of one per point, which would be both
  // slow and rude.
  const ELEVATION_BATCH = 100;
  const ELEVATION_URL = 'https://api.open-meteo.com/v1/elevation';

  // Reverse geocoding for the Place field. Nominatim's usage policy caps this
  // at one request a second, forbids systematic or gridded querying, and
  // requires that the result be attributed. A button the maintainer presses
  // once per file satisfies all three; doing it automatically on every drop
  // would not, and would also send coordinates to a third party without
  // anyone deciding to.
  const NOMINATIM_URL = 'https://nominatim.openstreetmap.org/reverse';
  // Region rather than street: a route is somewhere, it does not have an
  // address. zoom 10 is the level where Nominatim answers with the county and
  // state around a point rather than the nearest building.
  const NOMINATIM_ZOOM = 10;
  /**
   * Client-side spacing between lookups. The policy limit is one a second and
   * exceeding it earns a 403 rather than a queue, so a maintainer who taps the
   * button twice should be made to wait rather than be locked out.
   */
  const NOMINATIM_MIN_INTERVAL_MS = 1500;
  let lastLookupAt = 0;

  /**
   * Where the GPX files are expected to sit. The app reads any gpxUrl that
   * does not start with https:// as relative to the bundle file, so a bare
   * folder like this is the portable form: it keeps working when the
   * repository moves host, which an absolute URL does not.
   *
   * Most repositories keep the GPX beside the listing that points at them,
   * hence a folder rather than a domain.
   */
  const DEFAULT_GPX_FOLDER = 'gpx_files/';

  /**
   * The folder the last route actually used, so a maintainer who publishes to
   * somewhere else types it once rather than once per route. Same reasoning as
   * attribution surviving a clear: it describes the repository, not the route.
   */
  let gpxFolder = DEFAULT_GPX_FOLDER;

  /** Everything the page knows about the loaded file. */
  const state = {
    fileName: '',
    fileBytes: 0,
    sha256: '',
    raw: null,      // points in play, mutated in place by the backfill
    original: null, // pristine copy, so unticking the backfill really restores
    track: null,    // built track, the thing all numbers come from
    hasElevation: false,
    filled: false,  // elevation was looked up rather than in the file
  };

  // ── File input ─────────────────────────────────────────────────────────
  const drop = $('#drop');
  const fileInput = $('#file');

  drop.addEventListener('click', () => fileInput.click());
  drop.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });
  drop.addEventListener('dragover', (e) => {
    e.preventDefault();
    drop.classList.add('is-over');
  });
  drop.addEventListener('dragleave', () => drop.classList.remove('is-over'));
  drop.addEventListener('drop', (e) => {
    e.preventDefault();
    drop.classList.remove('is-over');
    const file = e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) loadFile(file);
  });
  fileInput.addEventListener('change', () => {
    const file = fileInput.files && fileInput.files[0];
    if (file) loadFile(file);
  });

  async function loadFile(file) {
    showError('');
    try {
      const buffer = await file.arrayBuffer();
      const text = new TextDecoder('utf-8').decode(buffer);
      const parsed = R.parseGpx(text);

      state.fileName = file.name;
      state.fileBytes = buffer.byteLength;
      state.sha256 = await R.sha256Hex(buffer);
      state.raw = parsed.points;
      state.original = parsed.points.map((p) => ({ lat: p.lat, lon: p.lon, ele: p.ele }));
      state.hasElevation = parsed.hasElevation && hasRealElevation(parsed.points);
      state.filled = false;
      state.track = R.buildTrack(parsed.points);

      drop.classList.add('is-loaded');
      drop.querySelector('.drop-title').textContent = file.name;
      drop.querySelector('.drop-sub').textContent =
        `${parsed.points.length.toLocaleString()} points · ${formatBytes(buffer.byteLength)} · click to replace`;

      prefill(parsed, file.name);
      $('#ele-warning').hidden = state.hasElevation;
      $('#ele-fill').checked = false;
      $('#ele-status').hidden = true;

      $('#preview-step').hidden = false;
      $('#details-step').hidden = false;
      $('#output-step').hidden = false;
      render();
    } catch (err) {
      showError(err.message || String(err));
    }
  }

  /**
   * A GPX can carry an <ele> on every point and still be flat, because some
   * planners write zeros. Treating that as elevation would hide the warning
   * on exactly the files that most need it.
   */
  function hasRealElevation(points) {
    let min = Infinity, max = -Infinity;
    for (const p of points) {
      if (p.ele < min) min = p.ele;
      if (p.ele > max) max = p.ele;
    }
    return max - min > 1.0;
  }

  function prefill(parsed, fileName) {
    const base = fileName.replace(/\.gpx$/i, '');
    if (!$('#f-id').value) $('#f-id').value = slug(base);
    if (!$('#f-name').value) $('#f-name').value = parsed.name || titleCase(base);
    if (!$('#f-description').value && parsed.description) {
      $('#f-description').value = parsed.description;
    }
    if (!$('#f-gpxurl').value) $('#f-gpxurl').value = `${gpxFolder}${slug(base)}.gpx`;
  }

  function slug(text) {
    return text.toLowerCase()
      // Strip combining marks so "Bedoin" and "Bedoin" with an accent give
      // the same id, then reduce anything else to hyphens.
      .normalize('NFD').replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'route';
  }

  function titleCase(text) {
    return text.replace(/[-_]+/g, ' ').replace(/\s+/g, ' ').trim()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  function formatBytes(n) {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
    return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  }

  function showError(message) {
    const box = $('#file-error');
    box.textContent = message;
    box.hidden = !message;
  }

  // ── Elevation backfill ─────────────────────────────────────────────────
  $('#ele-fill').addEventListener('change', async (e) => {
    if (!e.target.checked) {
      // Unticking restores the file as it actually is, so what is published
      // always matches what the checkbox currently says.
      if (state.filled && state.original) {
        // Restore the file as it actually is, rather than zeroing elevation,
        // so what gets published always matches what the checkbox says.
        state.raw = state.original.map((p) => ({ lat: p.lat, lon: p.lon, ele: p.ele }));
        state.track = R.buildTrack(state.raw);
        state.filled = false;
        $('#ele-status').hidden = true;
        render();
      }
      return;
    }
    const status = $('#ele-status');
    status.hidden = false;
    status.textContent = 'Looking up elevation…';
    try {
      await fillElevation((done, total) => {
        status.textContent = `Looking up elevation… ${done} / ${total}`;
      });
      state.track = R.buildTrack(state.raw);
      state.filled = true;
      status.textContent =
        'Elevation added from the Copernicus DEM. The attribution below now says the track was modified.';
      $('#f-attr-modified').checked = true;
      render();
    } catch (err) {
      e.target.checked = false;
      status.textContent = `Elevation lookup failed: ${err.message || err}`;
    }
  });

  async function fillElevation(onProgress) {
    const points = state.raw;
    let done = 0;
    for (let start = 0; start < points.length; start += ELEVATION_BATCH) {
      const slice = points.slice(start, start + ELEVATION_BATCH);
      const lat = slice.map((p) => p.lat.toFixed(5)).join(',');
      const lon = slice.map((p) => p.lon.toFixed(5)).join(',');
      const res = await fetch(`${ELEVATION_URL}?latitude=${lat}&longitude=${lon}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      const elevations = data.elevation || [];
      for (let i = 0; i < slice.length; i++) {
        const value = elevations[i];
        if (typeof value === 'number' && isFinite(value)) slice[i].ele = value;
      }
      done += slice.length;
      onProgress(done, points.length);
    }
  }

  // ── Place lookup ───────────────────────────────────────────────────────
  const placeStatusDefault = () => $('#place-status').dataset.default;

  $('#place-lookup').addEventListener('click', async () => {
    const button = $('#place-lookup');
    const status = $('#place-status');
    if (!state.track) {
      setPlaceStatus('Load a GPX first.', true);
      return;
    }
    const since = Date.now() - lastLookupAt;
    if (since < NOMINATIM_MIN_INTERVAL_MS) {
      setPlaceStatus('One lookup a second, please - that is the OpenStreetMap limit.', true);
      return;
    }
    lastLookupAt = Date.now();
    button.disabled = true;
    setPlaceStatus('Asking OpenStreetMap…', false);
    try {
      const mid = midpoint(state.track);
      const url = `${NOMINATIM_URL}?format=jsonv2&lat=${mid.lat.toFixed(5)}` +
        `&lon=${mid.lon.toFixed(5)}&zoom=${NOMINATIM_ZOOM}&addressdetails=1&accept-language=en`;
      const res = await fetch(url);
      // 403 here means the shared OpenStreetMap service is rate limiting, not
      // that anything is wrong with the route. Saying so stops a maintainer
      // hunting for a fault in their own file.
      if (res.status === 403 || res.status === 429) {
        throw new Error('OpenStreetMap is rate limiting right now. Wait a moment and try again');
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      const place = placeFrom(data.address || {});
      if (!place) {
        setPlaceStatus('OpenStreetMap had no region name for that spot. Type one in.', true);
      } else {
        $('#f-place').value = place;
        // Nominatim's policy requires the result be attributed, and it is OSM
        // data, so it is credited the same way the route data would be.
        setPlaceStatus(`From OpenStreetMap contributors (ODbL). Edit it if it is not what you would call the place.`, false);
        render();
      }
    } catch (err) {
      setPlaceStatus(`Lookup failed: ${err.message || err}. Type a place in instead.`, true);
    } finally {
      button.disabled = false;
    }
  });

  function setPlaceStatus(text, isError) {
    const status = $('#place-status');
    if (!status.dataset.default) status.dataset.default = status.textContent.trim();
    status.textContent = text || placeStatusDefault();
    status.classList.toggle('is-error', !!isError);
  }

  /**
   * The point halfway along the route by distance. Better than the start for a
   * point-to-point: a climb that begins in a valley town is usually better
   * described by the massif it goes up than by the car park it leaves from.
   */
  function midpoint(track) {
    const half = track.totalDistanceMeters / 2;
    const points = track.points;
    let lo = 0, hi = points.length - 1;
    while (lo < hi) {
      const mid = Math.ceil((lo + hi) / 2);
      if (points[mid].distance <= half) lo = mid; else hi = mid - 1;
    }
    return points[lo];
  }

  /**
   * "Region, Country" out of a Nominatim address. State first because that is
   * the name a rider would recognise - "Provence-Alpes-Cote d'Azur, France"
   * rather than the commune or the postcode district.
   */
  function placeFrom(address) {
    const region = address.state || address.region || address.county ||
      address.city || address.municipality || null;
    const country = address.country || null;
    if (region && country) return `${region}, ${country}`;
    return region || country || null;
  }

  // ── Rendering ──────────────────────────────────────────────────────────
  ['#ftp', '#weight', '#bike'].forEach((sel) => {
    $(sel).addEventListener('input', render);
  });
  [
    '#f-id', '#f-name', '#f-description', '#f-place', '#f-tags',
    '#f-gpxurl', '#f-version', '#f-attr-source', '#f-attr-licence',
    '#f-attr-url', '#f-attr-modified',
  ].forEach((sel) => {
    $(sel).addEventListener('input', render);
    $(sel).addEventListener('change', render);
  });
  $('#manifest-in').addEventListener('input', renderManifest);

  function render() {
    if (!state.track) return;
    const entry = buildEntry();
    drawShape($('#shape-canvas'), entry.shape);
    drawProfile($('#profile-canvas'), entry.profile);
    renderStats(entry);
    renderHistogram(entry);
    renderValidation(entry);
    $('#entry-json').textContent = JSON.stringify(entry, null, 2);
    renderManifest();
  }

  function buildEntry() {
    const tags = $('#f-tags').value.split(',').map((t) => t.trim()).filter(Boolean);
    const attribution = {};
    const source = $('#f-attr-source').value.trim();
    const licence = $('#f-attr-licence').value;
    const url = $('#f-attr-url').value.trim();
    if (source) attribution.source = source;
    if (url) attribution.url = url;
    if (licence) attribution.licence = licence;
    if ($('#f-attr-modified').checked) attribution.modified = true;

    return R.buildEntry(state.track, {
      id: slug($('#f-id').value || state.fileName.replace(/\.gpx$/i, '')),
      name: $('#f-name').value.trim() || 'Untitled route',
      description: $('#f-description').value.trim(),
      place: $('#f-place').value.trim(),
      tags,
      version: parseInt($('#f-version').value, 10) || 1,
      gpxUrl: $('#f-gpxurl').value.trim(),
      gpxBytes: state.fileBytes,
      // The hash describes the file on disk. Once elevation has been added in
      // the browser the published GPX is a different file, so publishing the
      // old hash would make every download fail its integrity check.
      gpxSha256: state.filled ? '' : state.sha256,
      attribution: Object.keys(attribution).length ? attribution : null,
    });
  }

  function renderStats(entry) {
    const ftp = num('#ftp'), weight = num('#weight'), bike = num('#bike');
    const seconds = R.estimateSeconds(entry, ftp, weight, bike);
    const tiles = [
      ['Distance', R.formatDistance(entry.distanceMeters)],
      ['Climbing', `${Math.round(entry.ascentMeters)} m`],
      ['Descent', `${Math.round(entry.descentMeters)} m`],
      ['Max gradient', `${entry.maxGradientPercent.toFixed(1)} %`],
      ['Est. time', R.formatDuration(seconds)],
      ['Shape', entry.loop ? 'Loop' : 'Point to point'],
    ];
    const box = $('#stats');
    box.innerHTML = '';
    for (const [label, value] of tiles) {
      const tile = document.createElement('div');
      tile.className = 'stat-tile';
      const l = document.createElement('span');
      l.className = 'stat-label';
      l.textContent = label;
      const v = document.createElement('span');
      v.className = 'stat-value';
      v.textContent = value;
      tile.append(l, v);
      box.appendChild(tile);
    }
  }

  function renderHistogram(entry) {
    const box = $('#histogram');
    box.innerHTML = '';
    const max = Math.max(...entry.gradientMeters, 1);
    entry.gradientMeters.forEach((metres, i) => {
      const lo = R.BUCKET_EDGES_PERCENT[i];
      const hi = R.BUCKET_EDGES_PERCENT[i + 1];
      const row = document.createElement('div');
      row.className = 'hist-row';

      const label = document.createElement('span');
      label.className = 'hist-label';
      label.textContent = i === 0 ? `< ${hi} %`
        : i === entry.gradientMeters.length - 1 ? `> ${lo} %`
          : `${lo} to ${hi} %`;

      const barWrap = document.createElement('span');
      barWrap.className = 'hist-bar-wrap';
      const bar = document.createElement('span');
      bar.className = 'hist-bar';
      bar.style.width = `${(metres / max) * 100}%`;
      barWrap.appendChild(bar);

      const value = document.createElement('span');
      value.className = 'hist-value';
      value.textContent = metres >= 1000
        ? `${(metres / 1000).toFixed(1)} km`
        : `${Math.round(metres)} m`;

      row.append(label, barWrap, value);
      box.appendChild(row);
    });
  }

  function renderValidation(entry) {
    const problems = [];
    const warnings = [];

    if (!entry.id) problems.push('A route id is required.');
    if (!entry.name || entry.name === 'Untitled route') problems.push('Give the route a name.');
    if (!entry.gpxUrl) problems.push('A GPX URL is required - the app has nothing to download without it.');
    if (/^http:\/\//i.test(entry.gpxUrl)) {
      problems.push('The app only accepts https:// URLs. Plain HTTP will fail on every device.');
    }
    if (!state.hasElevation && !state.filled) {
      warnings.push('No elevation: this route will simulate as flat.');
    }
    if (!entry.attribution || !entry.attribution.licence) {
      warnings.push('No licence stated. If the data came from somewhere, say where and under what terms.');
    }
    if (state.fileBytes > 4 * 1024 * 1024) {
      warnings.push(
        'This GPX is large. The app caps a route download at 12 MB, and the app resamples ' +
        'to 10 m anyway, so points closer than that are bytes every rider pays for and nobody feels.'
      );
    }

    const box = $('#validation');
    box.innerHTML = '';
    box.className = 'status';
    if (!problems.length && !warnings.length) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    if (problems.length) box.classList.add('is-error');
    const add = (prefix, messages) => {
      for (const message of messages) {
        const p = document.createElement('p');
        p.textContent = prefix + message;
        box.appendChild(p);
      }
    };
    add('Problem: ', problems);
    add('Check: ', warnings);
  }

  // ── Manifest merge ─────────────────────────────────────────────────────
  function renderManifest() {
    const out = $('#manifest-json');
    const raw = $('#manifest-in').value.trim();
    const bundlePointer = {
      id: 'routes',
      name: 'Routes',
      schemaVersion: R.ROUTE_BUNDLE_SCHEMA_VERSION,
      version: 1,
      url: 'routes.json',
    };

    if (!raw) {
      out.textContent = JSON.stringify({
        schemaVersion: 2,
        id: 'your-repo-id',
        name: 'Your repository',
        description: 'What this repository is.',
        author: 'Your name',
        routeBundles: [bundlePointer],
      }, null, 2);
      return;
    }

    let manifest;
    try {
      manifest = JSON.parse(raw);
    } catch (err) {
      out.textContent = `That is not valid JSON: ${err.message}`;
      return;
    }

    const existing = Array.isArray(manifest.routeBundles) ? manifest.routeBundles.slice() : [];
    // Do not add a second pointer to a bundle they already declare; bump the
    // version of the one that is there so caching sees the change.
    const match = existing.findIndex((b) => b && b.url === bundlePointer.url);
    if (match >= 0) {
      existing[match] = Object.assign({}, existing[match], {
        version: (parseInt(existing[match].version, 10) || 1) + 1,
      });
    } else {
      existing.push(bundlePointer);
    }
    manifest.routeBundles = existing;
    // Left at 2 deliberately. See the note above the output.
    if (!manifest.schemaVersion) manifest.schemaVersion = 2;
    out.textContent = JSON.stringify(manifest, null, 2);
  }

  // ── Drawing ────────────────────────────────────────────────────────────
  /*
   * Both take the canvas rather than looking one up, because the app preview
   * draws the same two pictures once per route card. `opts.width`/`.height`
   * are the logical size: a preview canvas is oversampled for a dense screen
   * and its context pre-scaled, so canvas.width is not the size to draw at.
   */
  function drawProfile(canvas, profile, opts) {
    const o = Object.assign(
      { fill: '#455561', stroke: '#F2F4F7', lineWidth: 2, pad: 4 }, opts || {});
    const w = o.width || canvas.width;
    const h = o.height || canvas.height;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    if (!profile || profile.length < 2) return;

    const low = Math.min(...profile);
    const high = Math.max(...profile);
    const span = Math.max(high - low, R.PROFILE_MIN_SPAN_METERS);
    const stepX = w / (profile.length - 1);
    const y = (v) => h - ((v - low) / span) * (h - o.pad * 2) - o.pad;

    ctx.beginPath();
    ctx.moveTo(0, y(profile[0]));
    profile.forEach((v, i) => ctx.lineTo(i * stepX, y(v)));
    ctx.lineTo(w, h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fillStyle = o.fill;
    ctx.fill();

    ctx.beginPath();
    profile.forEach((v, i) => {
      const px = i * stepX;
      if (i === 0) ctx.moveTo(px, y(v)); else ctx.lineTo(px, y(v));
    });
    ctx.strokeStyle = o.stroke;
    ctx.lineWidth = o.lineWidth;
    ctx.lineJoin = 'round';
    ctx.stroke();
  }

  function drawShape(canvas, encoded, opts) {
    const o = Object.assign(
      { stroke: '#C5CAD6', dot: '#F2F4F7', lineWidth: 2.5, inset: 10, dotRadius: 4 },
      opts || {});
    const w = o.width || canvas.width;
    const h = o.height || canvas.height;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    const points = R.decodePolyline(encoded);
    if (points.length < 2) return;

    const lats = points.map((p) => p[0]);
    const lons = points.map((p) => p[1]);
    const minLat = Math.min(...lats), maxLat = Math.max(...lats);
    const minLon = Math.min(...lons), maxLon = Math.max(...lons);
    // A degree of longitude is shorter than a degree of latitude everywhere
    // but the equator; without this every European route comes out stretched.
    const squeeze = Math.cos(((minLat + maxLat) / 2) * Math.PI / 180);
    const spanLat = Math.max(maxLat - minLat, 1e-9);
    const spanLon = Math.max((maxLon - minLon) * squeeze, 1e-9);

    const scale = Math.min(
      (w - o.inset * 2) / spanLon,
      (h - o.inset * 2) / spanLat
    );
    const offsetX = (w - spanLon * scale) / 2;
    const offsetY = (h - spanLat * scale) / 2;
    const px = (lon) => offsetX + (lon - minLon) * squeeze * scale;
    const py = (lat) => h - offsetY - (lat - minLat) * scale;

    ctx.beginPath();
    points.forEach(([lat, lon], i) => {
      if (i === 0) ctx.moveTo(px(lon), py(lat)); else ctx.lineTo(px(lon), py(lat));
    });
    ctx.strokeStyle = o.stroke;
    ctx.lineWidth = o.lineWidth;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.stroke();

    ctx.beginPath();
    ctx.arc(px(points[0][1]), py(points[0][0]), o.dotRadius, 0, Math.PI * 2);
    ctx.fillStyle = o.dot;
    ctx.fill();
  }

  /**
   * A canvas sized in CSS pixels but backed at the screen's density, with the
   * context already scaled so everything after this can draw in CSS pixels.
   * Capped at 3x: past that the memory costs more than the sharpness is worth.
   */
  function hiDpiCanvas(w, h, className) {
    const ratio = Math.min(window.devicePixelRatio || 1, 3);
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(w * ratio);
    canvas.height = Math.round(h * ratio);
    canvas.style.width = `${w}px`;
    canvas.style.height = `${h}px`;
    if (className) canvas.className = className;
    canvas.getContext('2d').setTransform(ratio, 0, 0, ratio, 0, 0);
    return canvas;
  }

  // ── Copy / download ────────────────────────────────────────────────────
  $('#copy-entry').addEventListener('click', () => copy($('#entry-json').textContent, $('#copy-entry')));
  $('#copy-manifest').addEventListener('click', () => copy($('#manifest-json').textContent, $('#copy-manifest')));
  $('#download-entry').addEventListener('click', () => {
    // A first route is a whole bundle, so hand over a file that is already
    // valid rather than a fragment they have to wrap in brackets.
    const entry = JSON.parse($('#entry-json').textContent);
    download('routes.json', JSON.stringify([entry], null, 2));
  });

  async function copy(text, button) {
    try {
      await navigator.clipboard.writeText(text);
      const original = button.textContent;
      button.textContent = 'Copied';
      setTimeout(() => { button.textContent = original; }, 1400);
    } catch (_) {
      // Clipboard is blocked in some contexts; selecting the block is the
      // fallback that always works.
      const range = document.createRange();
      range.selectNodeContents(button.closest('.output-block').querySelector('.output-json'));
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }

  function download(name, text) {
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
  }

  function num(selector) {
    const v = parseFloat($(selector).value);
    return isFinite(v) ? v : 0;
  }


  // ── The file being built ───────────────────────────────────────────────
  /*
   * A repository's routes.json is a plain JSON array of entries. The tool used
   * to hand over one entry and leave the merging to the maintainer, which is
   * the step where a route gets lost: paste in the wrong place, or replace the
   * file with a single-entry one, and the other routes are gone from every
   * rider's app at the next refresh. Holding the array here means the download
   * is always the whole file, existing routes included.
   */
  const BUNDLE_KEY = 'indoorbike.routetool.bundle.v1';

  const bundle = {
    open: false,
    /** Where it came from, shown so a maintainer can tell one file from another. */
    origin: '',
    entries: [],
  };

  const bundleDrop = $('#bundle-drop');
  const bundleFileInput = $('#bundle-file');

  bundleDrop.addEventListener('click', () => bundleFileInput.click());
  bundleDrop.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); bundleFileInput.click(); }
  });
  bundleDrop.addEventListener('dragover', (e) => {
    e.preventDefault();
    bundleDrop.classList.add('is-over');
  });
  bundleDrop.addEventListener('dragleave', () => bundleDrop.classList.remove('is-over'));
  bundleDrop.addEventListener('drop', (e) => {
    e.preventDefault();
    bundleDrop.classList.remove('is-over');
    const file = e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) loadBundleFile(file);
  });
  bundleFileInput.addEventListener('change', () => {
    const file = bundleFileInput.files && bundleFileInput.files[0];
    if (file) loadBundleFile(file);
  });

  $('#bundle-new').addEventListener('click', () => {
    openBundle([], 'New file', '');
  });

  /*
   * Held back after Start over so it can be undone. The editor settled this
   * question already: a destructive action gets an undo rather than a blocking
   * confirm(), because a modal interrupts everyone to protect the one person
   * who pressed it by accident.
   */
  let cleared = null;

  $('#bundle-reset').addEventListener('click', () => {
    cleared = { origin: bundle.origin, entries: bundle.entries };
    bundle.open = false;
    bundle.origin = '';
    bundle.entries = [];
    forget();
    $('#bundle-start').hidden = false;
    $('#bundle-open').hidden = true;
    bundleDrop.classList.remove('is-loaded');
    bundleFileInput.value = '';
    // The list is hidden rather than gone, and leaving last week's routes
    // sitting in it means Undo has something to disagree with.
    renderBundle();
    const count = cleared.entries.length;
    setBundleNotice(
      count
        ? `Cleared ${count} route${count === 1 ? '' : 's'} from this page. Nothing you have already published changes.`
        : '',
      false,
      count ? { label: 'Undo', onClick: undoClear } : null);
  });

  function undoClear() {
    if (!cleared) return;
    openBundle(cleared.entries, cleared.origin, '');
    cleared = null;
  }

  $('#bundle-download').addEventListener('click', () => {
    download('routes.json', JSON.stringify(bundle.entries, null, 2));
  });

  $('#add-to-bundle').addEventListener('click', addCurrentEntry);

  async function loadBundleFile(file) {
    setBundleNotice('', false);
    try {
      const text = await file.text();
      const result = parseBundle(text);
      openBundle(result.entries, file.name, result.note);
    } catch (err) {
      setBundleNotice(err.message || String(err), true);
    }
  }

  /**
   * Read a routes.json. Deliberately lenient about what is inside each entry -
   * the app is the authority on that and the preview shows its verdict - but
   * strict about the shape of the file, because silently treating a manifest
   * or a single entry as a bundle would throw away whatever was really in it.
   */
  function parseBundle(text) {
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (err) {
      throw new Error(`That file is not valid JSON: ${err.message}`);
    }
    if (parsed && !Array.isArray(parsed) && typeof parsed === 'object' &&
        (parsed.routeBundles || parsed.workouts || parsed.schemaVersion)) {
      throw new Error(
        'That looks like a manifest, not a route bundle. The bundle is the file ' +
        'your manifest’s routeBundles points at - usually routes.json.');
    }
    if (!Array.isArray(parsed)) {
      if (parsed && typeof parsed === 'object' && parsed.gpxUrl) {
        // A single downloaded entry. Wrapping it is what they meant.
        return { entries: [parsed], note: '' };
      }
      throw new Error(
        'A route bundle is a JSON array of route entries. This file is a ' +
        `${parsed === null ? 'null' : typeof parsed}.`);
    }
    const entries = parsed.filter((e) => e && typeof e === 'object' && !Array.isArray(e));
    const dropped = parsed.length - entries.length;
    return {
      entries,
      note: dropped
        ? `${dropped} item${dropped === 1 ? '' : 's'} in that file ${dropped === 1 ? 'was' : 'were'} not a route entry and ${dropped === 1 ? 'was' : 'were'} left out.`
        : '',
    };
  }

  function openBundle(entries, origin, note) {
    bundle.open = true;
    bundle.origin = origin || '';
    bundle.entries = entries;
    $('#bundle-start').hidden = true;
    $('#bundle-open').hidden = false;
    setBundleNotice(note || '', false);
    renderBundle();
    remember();
  }

  /**
   * One box, two kinds of message: a file that could not be read, and a file
   * that was read with something left out. Only the first is a failure, and
   * dressing the second in red would send a maintainer looking for a fault
   * they do not have.
   */
  function setBundleNotice(message, isError, action) {
    const box = $('#bundle-notice');
    box.innerHTML = '';
    box.hidden = !message;
    box.classList.toggle('is-error', !!isError);
    if (!message) return;
    const text = document.createElement('span');
    text.textContent = message;
    box.appendChild(text);
    if (action) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'mini-btn';
      button.textContent = action.label;
      button.addEventListener('click', action.onClick);
      box.appendChild(button);
    }
  }

  function renderBundle() {
    const count = bundle.entries.length;
    $('#bundle-count').textContent = count === 0
      ? 'No routes yet'
      : `${count} route${count === 1 ? '' : 's'}`;
    $('#bundle-origin').textContent = bundle.origin ? `from ${bundle.origin}` : '';
    $('#bundle-empty').hidden = count > 0;
    $('#bundle-download').disabled = count === 0;
    $('#bundle-preview').disabled = count === 0;

    const list = $('#bundle-list');
    list.innerHTML = '';
    bundle.entries.forEach((entry, index) => {
      const row = document.createElement('li');
      row.className = 'bundle-row';

      const main = document.createElement('div');
      main.className = 'bundle-row-main';
      const name = document.createElement('strong');
      name.textContent = entry.name || '(unnamed)';
      const meta = document.createElement('span');
      const bits = [entry.id || '(no id)'];
      if (entry.distanceMeters > 0) bits.push(R.formatDistance(entry.distanceMeters));
      if (entry.ascentMeters > 0) bits.push(`${Math.round(entry.ascentMeters)} m up`);
      meta.textContent = bits.join(' · ');
      main.append(name, meta);

      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'mini-btn';
      remove.textContent = 'Remove';
      remove.addEventListener('click', () => {
        bundle.entries.splice(index, 1);
        renderBundle();
        remember();
      });

      row.append(main, remove);
      list.appendChild(row);
    });
  }

  function addCurrentEntry() {
    const json = $('#entry-json').textContent;
    if (!json) return;
    const entry = JSON.parse(json);
    if (!entry.id || !entry.gpxUrl || !entry.name || entry.name === 'Untitled route') {
      setAddStatus('Fill in the id, the name and the GPX URL first - the app drops an entry missing any of them.', true);
      return;
    }
    // Starting the file implicitly is kinder than refusing: someone who went
    // straight to the GPX still meant to end up with a routes.json.
    if (!bundle.open) openBundle([], 'New file', '');

    const at = bundle.entries.findIndex((e) => e && e.id === entry.id);
    if (at >= 0) {
      bundle.entries[at] = entry;
      setAddStatus(`Replaced "${entry.name}" - an entry with the id ${entry.id} was already in the file.`, false);
    } else {
      bundle.entries.push(entry);
      setAddStatus(`Added "${entry.name}". The file now has ${bundle.entries.length} route${bundle.entries.length === 1 ? '' : 's'}.`, false);
    }
    const lastSlash = entry.gpxUrl.lastIndexOf('/');
    gpxFolder = lastSlash >= 0 ? entry.gpxUrl.slice(0, lastSlash + 1) : DEFAULT_GPX_FOLDER;

    renderBundle();
    remember();
    clearRoute();
    $('#bundle').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function setAddStatus(message, isError) {
    const box = $('#add-status');
    box.textContent = message;
    box.hidden = !message;
    box.classList.toggle('is-error', !!isError);
  }

  /**
   * Back to an empty step 1, ready for the next GPX. The source, the licence
   * and the rider figures survive on purpose: they are properties of the
   * repository and its preview, not of the route, and retyping them per route
   * is how a licence line ends up missing from one card in ten. "Modified" is
   * the exception - it is a claim about this track, and the elevation backfill
   * sets it, so carrying it over would put it on a route that never was.
   */
  function clearRoute() {
    state.fileName = '';
    state.fileBytes = 0;
    state.sha256 = '';
    state.raw = null;
    state.original = null;
    state.track = null;
    state.hasElevation = false;
    state.filled = false;

    ['#f-id', '#f-name', '#f-description', '#f-place', '#f-tags', '#f-gpxurl']
      .forEach((sel) => { $(sel).value = ''; });
    $('#f-version').value = '1';
    $('#f-attr-modified').checked = false;
    setPlaceStatus('', false);

    drop.classList.remove('is-loaded');
    drop.querySelector('.drop-title').textContent = 'Drop a .gpx file here';
    drop.querySelector('.drop-sub').textContent = 'or click to choose one';
    fileInput.value = '';
    showError('');
    $('#ele-warning').hidden = true;
    $('#ele-fill').checked = false;
    $('#ele-status').hidden = true;
    $('#preview-step').hidden = true;
    $('#details-step').hidden = true;
    $('#output-step').hidden = true;
    $('#entry-json').textContent = '';
  }

  // ── Remembering it across a reload ─────────────────────────────────────
  /*
   * Building a repository is not a sitting: routes get added over an evening,
   * with tab reloads in between. This is the whole file, so it stays in the
   * browser and never leaves it.
   */
  function remember() {
    try {
      window.localStorage.setItem(BUNDLE_KEY, JSON.stringify({
        origin: bundle.origin,
        entries: bundle.entries,
      }));
    } catch (_) {
      // Private windows and full quotas both land here. The file is still in
      // the page, and the download still works, so this is worth a word rather
      // than an interruption.
      $('#bundle-origin').textContent =
        `${bundle.origin ? `from ${bundle.origin} · ` : ''}not saved in this browser - download before closing the tab`;
    }
  }

  function forget() {
    try { window.localStorage.removeItem(BUNDLE_KEY); } catch (_) { /* nothing to do */ }
  }

  function restore() {
    let saved;
    try {
      saved = window.localStorage.getItem(BUNDLE_KEY);
    } catch (_) {
      return;
    }
    if (!saved) return;
    try {
      const parsed = JSON.parse(saved);
      if (!parsed || !Array.isArray(parsed.entries)) return;
      openBundle(parsed.entries, parsed.origin || '', '');
    } catch (_) {
      forget();
    }
  }

  // ── The app preview ────────────────────────────────────────────────────
  /*
   * The route browser as the app draws it, from the same entries and the same
   * rules. Faithfulness is the point: a maintainer cannot otherwise see their
   * card until it is live in front of riders, and by then a name that wraps to
   * three lines or a route the app quietly drops has already shipped.
   */
  const ROUTE_SCHEMA_MAX_SUPPORTED = 1;
  const PHONE_WIDTH = 360;
  const CARD_INNER = PHONE_WIDTH - 16 * 2 - 16 * 2;

  let previewTag = 'All';

  const dialog = $('#preview-dialog');
  $('#bundle-preview').addEventListener('click', () => {
    previewTag = 'All';
    renderPreview();
    if (typeof dialog.showModal === 'function') dialog.showModal(); else dialog.setAttribute('open', '');
  });
  $('#preview-close').addEventListener('click', closePreview);
  // A click on the backdrop lands on the dialog itself, never on its content.
  dialog.addEventListener('click', (e) => { if (e.target === dialog) closePreview(); });

  function closePreview() {
    if (typeof dialog.close === 'function') dialog.close(); else dialog.removeAttribute('open');
  }

  /** The app's own two tests, so the preview drops exactly what the app drops. */
  function isSupported(entry) {
    const version = entry.schemaVersion;
    return !(typeof version === 'number') || version <= ROUTE_SCHEMA_MAX_SUPPORTED;
  }

  function isUsable(entry) {
    return !!(entry.id && String(entry.id).trim()) &&
      !!(entry.name && String(entry.name).trim()) &&
      !!(entry.gpxUrl && String(entry.gpxUrl).trim()) &&
      entry.distanceMeters > 0;
  }

  function renderPreview() {
    const screen = $('#phone-screen');
    screen.innerHTML = '';
    $('#preview-note').innerHTML = '';

    const shown = bundle.entries.filter((e) => isSupported(e) && isUsable(e));
    const dropped = bundle.entries.length - shown.length;

    screen.appendChild(previewHeader());

    const tags = ['All'].concat(
      Array.from(new Set(shown.flatMap((e) => (Array.isArray(e.tags) ? e.tags : [])))).sort()
    );
    if (!tags.includes(previewTag)) previewTag = 'All';
    if (tags.length > 1) screen.appendChild(previewChips(tags));

    const list = document.createElement('div');
    list.className = 'rp-list';
    const visible = previewTag === 'All'
      ? shown
      : shown.filter((e) => Array.isArray(e.tags) && e.tags.includes(previewTag));

    if (!visible.length) {
      const empty = document.createElement('p');
      empty.className = 'rp-empty';
      empty.textContent = shown.length
        ? 'No routes with that tag.'
        : 'This repository has no routes yet.';
      list.appendChild(empty);
    } else {
      visible.forEach((entry) => list.appendChild(previewCard(entry)));
    }
    screen.appendChild(list);

    // The app drops a bad entry without a word to the rider. The maintainer is
    // the one person who can fix it, so they are the one person who is told -
    // outside the phone, because this is the tool speaking, not the app.
    if (dropped) {
      const warn = document.createElement('p');
      warn.className = 'rp-dropped';
      warn.textContent =
        `${dropped} entr${dropped === 1 ? 'y is' : 'ies are'} missing from this listing: ` +
        'the app skips any route without an id, a name, a GPX URL and a distance, ' +
        'or written against a newer schema than it understands.';
      $('#preview-note').appendChild(warn);
    }
  }

  function previewHeader() {
    const head = document.createElement('div');
    head.className = 'rp-head';
    const eyebrow = document.createElement('p');
    eyebrow.className = 'rp-eyebrow';
    eyebrow.textContent = 'Community';
    const title = document.createElement('h3');
    title.className = 'rp-title';
    title.textContent = repoName();
    head.append(eyebrow, title);
    return head;
  }

  /** The name the app shows is the manifest's, so take it from theirs if given. */
  function repoName() {
    const raw = $('#manifest-in').value.trim();
    if (raw) {
      try {
        const manifest = JSON.parse(raw);
        if (manifest && typeof manifest.name === 'string' && manifest.name.trim()) {
          return manifest.name.trim();
        }
      } catch (_) { /* the manifest block already says it is invalid */ }
    }
    return 'Your repository';
  }

  function previewChips(tags) {
    const row = document.createElement('div');
    row.className = 'rp-chips';
    tags.forEach((tag) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'rp-chip' + (tag === previewTag ? ' is-on' : '');
      chip.textContent = tag;
      chip.addEventListener('click', () => { previewTag = tag; renderPreview(); });
      row.appendChild(chip);
    });
    return row;
  }

  function previewCard(entry) {
    const card = document.createElement('article');
    card.className = 'rp-card';

    const top = document.createElement('div');
    top.className = 'rp-card-top';

    const thumb = document.createElement('div');
    thumb.className = 'rp-thumb';
    if (entry.shape) {
      const canvas = hiDpiCanvas(56, 56);
      drawShape(canvas, entry.shape, {
        width: 56, height: 56, stroke: '#9AA1B0', lineWidth: 1.6,
        inset: 4, dotRadius: 2.5,
      });
      thumb.appendChild(canvas);
    }
    top.appendChild(thumb);

    const titles = document.createElement('div');
    titles.className = 'rp-card-titles';
    const name = document.createElement('strong');
    name.textContent = entry.name;
    titles.appendChild(name);
    if (entry.place && String(entry.place).trim()) {
      const place = document.createElement('span');
      place.textContent = entry.place;
      titles.appendChild(place);
    }
    top.appendChild(titles);

    const add = document.createElement('span');
    add.className = 'rp-add';
    add.innerHTML =
      '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">' +
      '<path d="M6.5 19a4.5 4.5 0 0 1-.4-8.98 6 6 0 0 1 11.66-1.2A4.25 4.25 0 0 1 17.5 19" ' +
      'fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>' +
      '<path d="M12 11v7m0 0-2.6-2.6M12 18l2.6-2.6" fill="none" stroke="currentColor" ' +
      'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    top.appendChild(add);
    card.appendChild(top);

    if (Array.isArray(entry.profile) && entry.profile.length >= 2) {
      const spark = hiDpiCanvas(CARD_INNER, 44, 'rp-spark');
      drawProfile(spark, entry.profile, {
        width: CARD_INNER, height: 44, pad: 0, lineWidth: 1.8,
        fill: 'rgba(242, 244, 247, 0.18)', stroke: '#F2F4F7',
      });
      card.appendChild(spark);
    }

    const stats = document.createElement('div');
    stats.className = 'rp-stats';
    const seconds = R.estimateSeconds(entry, num('#ftp'), num('#weight'), num('#bike'));
    [
      ['Distance', R.formatDistance(entry.distanceMeters)],
      ['Climbing', `${Math.round(entry.ascentMeters || 0)} m`],
      ['Time', R.formatDuration(seconds)],
    ].forEach(([label, value]) => {
      const stat = document.createElement('div');
      const l = document.createElement('span');
      l.className = 'rp-stat-label';
      l.textContent = label;
      const v = document.createElement('span');
      v.className = 'rp-stat-value';
      v.textContent = value;
      stat.append(l, v);
      stats.appendChild(stat);
    });
    card.appendChild(stats);

    if (entry.description && String(entry.description).trim()) {
      const description = document.createElement('p');
      description.className = 'rp-desc';
      description.textContent = entry.description;
      card.appendChild(description);
    }

    const credit = creditLine(entry);
    if (credit) {
      const line = document.createElement('p');
      line.className = 'rp-credit';
      line.textContent = credit;
      card.appendChild(line);
    }
    return card;
  }

  /** The same fallback chain the card uses: source, licence, or nothing. */
  function creditLine(entry) {
    const attribution = entry.attribution;
    if (!attribution) return null;
    const source = (attribution.source || '').trim();
    const licence = (attribution.licence || '').trim();
    if (source && licence) return `${source} (${licence})`;
    return source || licence || null;
  }

  renderManifest();
  restore();
})();
