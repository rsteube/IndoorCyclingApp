/* ============================================================
   Indoor Bike - route publishing tool

   Turns a GPX into the JSON a community repository publishes.
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
      $('.drop-title').textContent = file.name;
      $('.drop-sub').textContent =
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
    if (!$('#f-gpxurl').value) $('#f-gpxurl').value = `routes/${slug(base)}.gpx`;
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
    drawShape(entry.shape);
    drawProfile(entry.profile);
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
      ['DISTANCE', R.formatDistance(entry.distanceMeters)],
      ['CLIMBING', `${Math.round(entry.ascentMeters)} m`],
      ['DESCENT', `${Math.round(entry.descentMeters)} m`],
      ['MAX GRADIENT', `${entry.maxGradientPercent.toFixed(1)} %`],
      ['EST. TIME', R.formatDuration(seconds)],
      ['SHAPE', entry.loop ? 'Loop' : 'Point to point'],
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
  function drawProfile(profile) {
    const canvas = $('#profile-canvas');
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!profile || profile.length < 2) return;

    const low = Math.min(...profile);
    const high = Math.max(...profile);
    const span = Math.max(high - low, R.PROFILE_MIN_SPAN_METERS);
    const stepX = canvas.width / (profile.length - 1);
    const y = (v) => canvas.height - ((v - low) / span) * (canvas.height - 8) - 4;

    ctx.beginPath();
    ctx.moveTo(0, y(profile[0]));
    profile.forEach((v, i) => ctx.lineTo(i * stepX, y(v)));
    ctx.lineTo(canvas.width, canvas.height);
    ctx.lineTo(0, canvas.height);
    ctx.closePath();
    ctx.fillStyle = 'rgba(244,122,31,0.18)';
    ctx.fill();

    ctx.beginPath();
    profile.forEach((v, i) => {
      const px = i * stepX;
      if (i === 0) ctx.moveTo(px, y(v)); else ctx.lineTo(px, y(v));
    });
    ctx.strokeStyle = '#F47A1F';
    ctx.lineWidth = 2;
    ctx.lineJoin = 'round';
    ctx.stroke();
  }

  function drawShape(encoded) {
    const canvas = $('#shape-canvas');
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
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

    const inset = 10;
    const scale = Math.min(
      (canvas.width - inset * 2) / spanLon,
      (canvas.height - inset * 2) / spanLat
    );
    const offsetX = (canvas.width - spanLon * scale) / 2;
    const offsetY = (canvas.height - spanLat * scale) / 2;
    const px = (lon) => offsetX + (lon - minLon) * squeeze * scale;
    const py = (lat) => canvas.height - offsetY - (lat - minLat) * scale;

    ctx.beginPath();
    points.forEach(([lat, lon], i) => {
      if (i === 0) ctx.moveTo(px(lon), py(lat)); else ctx.lineTo(px(lon), py(lat));
    });
    ctx.strokeStyle = '#C2B5A1';
    ctx.lineWidth = 2.5;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.stroke();

    ctx.beginPath();
    ctx.arc(px(points[0][1]), py(points[0][0]), 4, 0, Math.PI * 2);
    ctx.fillStyle = '#F47A1F';
    ctx.fill();
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

  renderManifest();
})();
