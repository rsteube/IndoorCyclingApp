/* ============================================================
   Indoor Bike - community browser
   Three views (registry → repo → workout) routed via URL hash:
     #                       → registry list
     #repo=<id>              → repo detail
     #repo=<id>&workout=<id> → workout preview
   No backend; fetches static JSON files served from the same site.
   Workouts hand off to the editor / share page via #wz= URLs that
   reuse the gzip+base64url encoding the editor and Android app
   already understand.
   ============================================================ */

(function () {
  'use strict';

  const REGISTRY_URL = '../registry.json';

  // ── Zone palette (mirrored from editor.js / share.js) ──────────────────
  const ZONE_COLORS = {
    1: '#6B8AA8', 2: '#2E7BFF', 3: '#1FB373',
    4: '#F0C419', 5: '#FF8A1F', 6: '#EB3B3B', 7: '#C53BFF',
  };
  function zoneFor(pct) {
    if (pct <= 55) return 1;
    if (pct <= 75) return 2;
    if (pct <= 90) return 3;
    if (pct <= 105) return 4;
    if (pct <= 120) return 5;
    if (pct <= 150) return 6;
    return 7;
  }

  // ── Tiny DOM helpers ───────────────────────────────────────────────────
  const $ = (s) => document.querySelector(s);
  function el(tag, props = {}, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'class') n.className = v;
      else if (k === 'style') Object.assign(n.style, v);
      else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v != null && v !== false) n.setAttribute(k, v);
    }
    for (const c of kids.flat()) {
      if (c == null || c === false) continue;
      n.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return n;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  // ── Workout math (kept identical to editor for "what TSS would I see" parity) ─
  function totalSeconds(w) { return w.segments.reduce((a, s) => a + (s.lengthInSeconds | 0), 0); }
  function avgPct(w) {
    const t = totalSeconds(w); if (t === 0) return 0;
    return w.segments.reduce((a, s) => a + s.powerPercentFTP * s.lengthInSeconds, 0) / t;
  }
  function peakPct(w) { return w.segments.reduce((m, s) => Math.max(m, s.powerPercentFTP), 0); }
  function npPct(w) {
    let sum4 = 0, n = 0;
    for (const s of w.segments) {
      sum4 += Math.pow(s.powerPercentFTP, 4) * s.lengthInSeconds;
      n += s.lengthInSeconds;
    }
    return n === 0 ? 0 : Math.pow(sum4 / n, 0.25);
  }
  function intensityFactor(w) { return npPct(w) / 100; }
  function tss(w) {
    const i = intensityFactor(w);
    return Math.round((totalSeconds(w) / 3600) * i * i * 100);
  }
  function fmtClock(s) {
    s = Math.max(0, Math.round(s));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = s % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
    return `${m}:${String(ss).padStart(2, '0')}`;
  }
  function fmtDurationShort(s) {
    if (s < 60) return `${s}s`;
    const m = Math.round(s / 60);
    if (m < 60) return `${m} min`;
    const h = Math.floor(m / 60);
    const rem = m % 60;
    return rem === 0 ? `${h}h` : `${h}h ${rem}m`;
  }

  // ── Encoders for the editor / share handoff ────────────────────────────
  // Match website/editor/editor.js exactly so the receiver decodes the
  // same `#wz=` URL the editor and Android app already understand.
  function b64urlEncodeBytes(bytes) {
    let bin = '';
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function buildWzPayload(workout) {
    const json = JSON.stringify(workout);
    if (typeof pako !== 'undefined' && pako.gzip) {
      try {
        return 'wz=' + b64urlEncodeBytes(pako.gzip(json));
      } catch (_) { /* fall through to plain */ }
    }
    // Plain base64url fallback for older / restricted browsers.
    const bytes = new TextEncoder().encode(json);
    return 'w=' + b64urlEncodeBytes(bytes);
  }
  function editorHrefFor(workout) {
    return `../editor/index.html#${buildWzPayload(workout)}`;
  }
  function shareHrefFor(workout) {
    return `../share/index.html#${buildWzPayload(workout)}`;
  }

  // ── Cache ──────────────────────────────────────────────────────────────
  // Repos load once per session; the registry might be re-fetched on the
  // back arrow but the hit is small. No persistence - that's the share/
  // page's job once the workout is on the user's phone.
  let registryPromise = null;
  const manifestCache = new Map(); // repoId → manifest

  async function loadRegistry() {
    if (registryPromise) return registryPromise;
    registryPromise = (async () => {
      const res = await fetch(REGISTRY_URL, { cache: 'default' });
      if (!res.ok) throw new Error(`Registry HTTP ${res.status}`);
      return res.json();
    })().catch((err) => {
      // Reset so the user can retry.
      registryPromise = null;
      throw err;
    });
    return registryPromise;
  }

  // Highest schema versions this build of the page understands. The Android
  // app keeps its own constants - we deliberately mirror them here so the
  // website fails gracefully on a future-format manifest instead of trying
  // to render an unknown shape.
  const MAX_MANIFEST_SCHEMA = 2;
  const MAX_BUNDLE_SCHEMA = 1;
  const MAX_WORKOUT_SCHEMA = 1;

  /**
   * Load a repo: fetch the small manifest, then fetch each referenced bundle
   * (in parallel) and merge their workouts. Bundles whose `schemaVersion`
   * exceeds [MAX_BUNDLE_SCHEMA] are skipped without download. The returned
   * object exposes `.workouts` for the rest of the page so existing render
   * code keeps working unchanged.
   */
  async function loadManifest(repoId, manifestUrl) {
    if (manifestCache.has(repoId)) return manifestCache.get(repoId);
    const res = await fetch(manifestUrl, { cache: 'default' });
    if (!res.ok) throw new Error(`Manifest HTTP ${res.status}`);
    const manifest = await res.json();
    if ((manifest.schemaVersion ?? 1) > MAX_MANIFEST_SCHEMA) {
      throw new Error(
        `Manifest format v${manifest.schemaVersion} is newer than this site understands.`
      );
    }
    const bundles = Array.isArray(manifest.bundles) ? manifest.bundles : [];
    const supported = bundles.filter((b) => (b.schemaVersion ?? 1) <= MAX_BUNDLE_SCHEMA);
    // Parallel fetch - bundles are independent. A single failure logs and
    // resolves to []; the rest of the repo still renders.
    const bundleResults = await Promise.all(supported.map((b) =>
      fetchBundle(resolveBundleUrl(b.url, manifestUrl)).catch((err) => {
        console.warn(`Bundle "${b.id}" failed: ${err.message || err}`);
        return [];
      })
    ));
    const workouts = bundleResults.flat()
      .filter((w) => (w.schemaVersion ?? 1) <= MAX_WORKOUT_SCHEMA);
    const resolved = Object.assign({}, manifest, { workouts });
    manifestCache.set(repoId, resolved);
    return resolved;
  }

  async function fetchBundle(url) {
    const res = await fetch(url, { cache: 'default' });
    if (!res.ok) throw new Error(`Bundle HTTP ${res.status}`);
    const data = await res.json();
    if (!Array.isArray(data)) throw new Error('Bundle must be a JSON array of workouts');
    return data;
  }

  function resolveBundleUrl(bundleUrl, manifestUrl) {
    if (/^https?:\/\//i.test(bundleUrl)) return bundleUrl;
    const baseEnd = manifestUrl.lastIndexOf('/');
    return manifestUrl.substring(0, baseEnd + 1) + bundleUrl;
  }

  // ── Routing ────────────────────────────────────────────────────────────
  function parseHash() {
    const raw = (location.hash || '').replace(/^#/, '');
    const params = {};
    for (const pair of raw.split('&')) {
      const eq = pair.indexOf('=');
      if (eq <= 0) continue;
      params[pair.slice(0, eq)] = decodeURIComponent(pair.slice(eq + 1));
    }
    return params;
  }
  function hashFor({ repo, workout }) {
    const parts = [];
    if (repo) parts.push(`repo=${encodeURIComponent(repo)}`);
    if (workout) parts.push(`workout=${encodeURIComponent(workout)}`);
    return parts.length ? '#' + parts.join('&') : '#';
  }

  // ── Crumbs ─────────────────────────────────────────────────────────────
  function renderCrumbs({ repo, repoName, workoutName }) {
    const crumbs = $('#crumbs');
    clear(crumbs);
    crumbs.appendChild(el('a', { href: '#' }, 'Community'));
    if (repo) {
      crumbs.appendChild(el('span', { class: 'sep' }, '›'));
      crumbs.appendChild(el('a', { href: hashFor({ repo }) }, repoName || repo));
    }
    if (workoutName) {
      crumbs.appendChild(el('span', { class: 'sep' }, '›'));
      crumbs.appendChild(el('span', {}, workoutName));
    }
  }

  // ── View switcher ──────────────────────────────────────────────────────
  function showView(id) {
    document.querySelectorAll('.view').forEach((v) => { v.hidden = (v.id !== id); });
  }

  // ── Registry view ──────────────────────────────────────────────────────
  async function renderRegistry() {
    showView('view-registry');
    renderCrumbs({});
    const stage = $('#registry-state');
    clear(stage);
    stage.appendChild(buildSkeletonGrid(4, 140));
    try {
      const reg = await loadRegistry();
      clear(stage);
      const repos = Array.isArray(reg.repositories) ? reg.repositories : [];
      if (repos.length === 0) {
        stage.appendChild(el('div', { class: 'status' },
          el('p', {}, 'The community registry is empty right now.'),
          el('p', { class: 'status-action' },
            'Want to share your own workouts? See the ',
            el('a', { href: 'contribute.html' }, 'contributor docs'),
            '.'
          )
        ));
        return;
      }
      const grid = el('div', { class: 'repo-grid' });
      for (const r of repos) {
        grid.appendChild(buildRepoCard(r));
      }
      stage.appendChild(grid);
      // Inline pointer to the docs so contributors discover it without
      // having to hit an empty registry first.
      stage.appendChild(el('p', { class: 'contribute-cta' },
        'Got a pack to share? ',
        el('a', { href: 'contribute.html' }, 'Publish your own community repository →')
      ));
    } catch (err) {
      clear(stage);
      stage.appendChild(el('div', { class: 'status is-error' },
        el('p', {}, `Couldn't load the community registry - ${err.message || err}.`),
        el('p', { class: 'status-action' },
          el('a', { class: 'btn btn-secondary', href: '#', onClick: (e) => { e.preventDefault(); renderRegistry(); } }, 'Retry')
        )
      ));
    }
  }

  function buildRepoCard(r) {
    return el('a', { class: 'repo-card', href: hashFor({ repo: r.id }) },
      el('h3', {}, r.name || r.id),
      r.description ? el('p', {}, r.description) : null,
      r.author ? el('p', { class: 'author' }, `by ${r.author}`) : null,
      Array.isArray(r.tags) && r.tags.length
        ? el('div', { class: 'repo-tags' }, ...r.tags.map((t) => el('span', { class: 'tag-chip' }, t)))
        : null
    );
  }

  // ── Repo view ──────────────────────────────────────────────────────────
  async function renderRepo(repoId) {
    showView('view-repo');
    const stage = $('#repo-state');
    clear(stage);
    stage.appendChild(buildSkeletonGrid(6, 180));

    let registry;
    try { registry = await loadRegistry(); }
    catch (err) {
      clear(stage);
      stage.appendChild(el('div', { class: 'status is-error' },
        `Couldn't load the registry: ${err.message || err}`));
      return;
    }
    const entry = (registry.repositories || []).find((r) => r.id === repoId);
    if (!entry) {
      // Stale link, deleted repo, or a fresh page load with an unknown id.
      // Show a graceful fallback rather than an error toast.
      $('#repo-eyebrow').textContent = 'Repository';
      $('#repo-title').textContent = 'Repository not found';
      $('#repo-description').textContent =
        `No repository with id "${repoId}" is in the current registry. It may have been removed.`;
      $('#repo-meta').textContent = '';
      renderCrumbs({ repo: repoId, repoName: 'Not found' });
      clear(stage);
      stage.appendChild(el('div', { class: 'status' },
        el('a', { class: 'btn btn-secondary', href: '#' }, '← Back to repositories')
      ));
      return;
    }

    $('#repo-eyebrow').textContent = entry.author ? `By ${entry.author}` : 'Repository';
    $('#repo-title').textContent = entry.name || entry.id;
    $('#repo-description').textContent = entry.description || '';
    const meta = $('#repo-meta');
    clear(meta);
    if (entry.homepage) {
      meta.appendChild(el('a', { href: entry.homepage, target: '_blank', rel: 'noopener' }, 'Homepage'));
    }
    renderCrumbs({ repo: entry.id, repoName: entry.name });

    let manifest;
    try { manifest = await loadManifest(entry.id, entry.manifestUrl); }
    catch (err) {
      clear(stage);
      stage.appendChild(el('div', { class: 'status is-error' },
        el('p', {}, `Couldn't load the repository manifest - ${err.message || err}.`),
        el('p', { class: 'status-action' },
          el('a', { class: 'btn btn-secondary', href: '#', onClick: (e) => { e.preventDefault(); renderRepo(repoId); } }, 'Retry')
        )
      ));
      return;
    }

    clear(stage);
    const workouts = Array.isArray(manifest.workouts) ? manifest.workouts : [];
    if (workouts.length === 0) {
      stage.appendChild(el('div', { class: 'status' }, 'This repository has no workouts yet.'));
      return;
    }
    // Add count to meta line now that we know it.
    meta.appendChild(el('span', {}, `${workouts.length} workout${workouts.length === 1 ? '' : 's'}`));
    if (manifest.updatedEpoch) {
      meta.appendChild(el('span', {}, `· updated ${formatRelativeDate(manifest.updatedEpoch)}`));
    }

    const grid = el('div', { class: 'workout-grid' });
    for (const w of workouts) grid.appendChild(buildWorkoutCard(entry.id, w));
    stage.appendChild(grid);
  }

  function buildWorkoutCard(repoId, w) {
    const total = totalSeconds(w) || 1;
    const bar = el('div', { class: 'workout-bar' });
    for (const s of w.segments) {
      bar.appendChild(el('div', {
        style: { background: ZONE_COLORS[zoneFor(s.powerPercentFTP)], flex: String(s.lengthInSeconds / total) }
      }));
    }
    const tags = Array.isArray(w.tags) ? w.tags.slice(0, 3) : [];
    return el('a',
      { class: 'workout-card', href: hashFor({ repo: repoId, workout: w.id }) },
      el('h3', {}, w.name || w.id),
      el('p', { class: 'meta' },
        `${fmtDurationShort(totalSeconds(w))} · TSS ${tss(w)} · IF ${intensityFactor(w).toFixed(2)}`),
      bar,
      tags.length ? el('div', { class: 'workout-tags' }, ...tags.map((t) => el('span', { class: 'tag-chip' }, t))) : null
    );
  }

  // ── Workout view ───────────────────────────────────────────────────────
  async function renderWorkout(repoId, workoutId) {
    showView('view-workout');
    const stage = $('#workout-state');
    clear(stage);
    stage.appendChild(el('div', { class: 'status' }, 'Loading…'));

    let registry;
    try { registry = await loadRegistry(); }
    catch (err) {
      clear(stage);
      stage.appendChild(el('div', { class: 'status is-error' },
        `Couldn't load the registry: ${err.message || err}`));
      return;
    }
    const entry = (registry.repositories || []).find((r) => r.id === repoId);
    if (!entry) {
      $('#workout-eyebrow').textContent = '';
      $('#workout-title').textContent = 'Workout not found';
      $('#workout-description').textContent = '';
      renderCrumbs({ repo: repoId, repoName: 'Not found' });
      clear(stage);
      stage.appendChild(el('div', { class: 'status' },
        el('a', { class: 'btn btn-secondary', href: '#' }, '← Back to repositories')
      ));
      return;
    }

    let manifest;
    try { manifest = await loadManifest(entry.id, entry.manifestUrl); }
    catch (err) {
      clear(stage);
      stage.appendChild(el('div', { class: 'status is-error' },
        `Couldn't load the repository: ${err.message || err}`));
      return;
    }
    const workout = (manifest.workouts || []).find((w) => w.id === workoutId);
    if (!workout) {
      $('#workout-eyebrow').textContent = entry.name;
      $('#workout-title').textContent = 'Workout not found';
      $('#workout-description').textContent = '';
      renderCrumbs({ repo: entry.id, repoName: entry.name, workoutName: 'Not found' });
      clear(stage);
      stage.appendChild(el('div', { class: 'status' },
        el('a', { class: 'btn btn-secondary', href: hashFor({ repo: entry.id }) }, '← Back to repository')
      ));
      return;
    }

    $('#workout-eyebrow').textContent = entry.name;
    $('#workout-title').textContent = workout.name || workout.id;
    $('#workout-description').textContent = workout.description || '';
    renderCrumbs({ repo: entry.id, repoName: entry.name, workoutName: workout.name });

    clear(stage);
    stage.appendChild(buildWorkoutDetail(workout));
  }

  function buildWorkoutDetail(w) {
    const total = totalSeconds(w) || 1;
    const yMax = Math.max(130, Math.ceil(peakPct(w) / 25) * 25);

    const chartBar = el('div', { class: 'detail-chart-bar' });
    for (const s of w.segments) {
      chartBar.appendChild(el('div', {
        style: {
          background: ZONE_COLORS[zoneFor(s.powerPercentFTP)],
          flex: String(s.lengthInSeconds / total),
          height: ((s.powerPercentFTP / yMax) * 100) + '%',
        }
      }));
    }

    const chart = el('div', { class: 'detail-chart' },
      chartBar,
      el('div', { class: 'detail-axis' },
        el('span', {}, '0:00'),
        el('span', {}, fmtClock(totalSeconds(w)))
      ),
      el('div', { class: 'detail-stats' },
        statTile('DURATION', fmtClock(totalSeconds(w))),
        statTile('TSS', String(tss(w))),
        statTile('IF', intensityFactor(w).toFixed(2)),
        statTile('AVG % FTP', String(Math.round(avgPct(w))))
      )
    );

    const stepsList = el('ul', { class: 'detail-steps' });
    for (const s of w.segments) {
      const z = zoneFor(s.powerPercentFTP);
      stepsList.appendChild(el('li', { class: 'detail-step' },
        el('span', { class: 'step-rail', style: { background: ZONE_COLORS[z] } }),
        el('span', {}, `Z${z} · ${s.powerPercentFTP}% FTP`),
        el('span', { class: 'step-meta' }, fmtClock(s.lengthInSeconds))
      ));
    }

    const side = el('div', { class: 'detail-side' },
      el('div', { class: 'detail-actions' },
        el('a', { class: 'btn btn-primary', href: editorHrefFor(w) },
          'Open in editor ', el('span', { class: 'arrow' }, '→')),
        el('a', { class: 'btn btn-secondary', href: shareHrefFor(w) },
          'Send to phone'),
        el('button', {
          class: 'btn btn-ghost',
          type: 'button',
          onClick: () => downloadJson(w),
        }, 'Download .json')
      ),
      el('h4', {}, `Steps · ${w.segments.length}`),
      stepsList
    );

    return el('div', { class: 'workout-detail' }, chart, side);
  }

  function statTile(label, value) {
    return el('div', { class: 'detail-stat' },
      el('div', { class: 'stat-label' }, label),
      el('div', { class: 'stat-value' }, value)
    );
  }

  function downloadJson(workout) {
    const text = JSON.stringify(workout, null, 2);
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const safe = (workout.name || workout.id || 'workout')
      .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'workout';
    a.href = url;
    a.download = `${safe}.json`;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  // ── Skeleton helpers ───────────────────────────────────────────────────
  function buildSkeletonGrid(count, height) {
    const grid = el('div', { class: 'repo-grid' });
    for (let i = 0; i < count; i++) {
      grid.appendChild(el('div', {
        class: 'skeleton',
        style: { height: height + 'px', borderRadius: 'var(--r-md)' }
      }));
    }
    return grid;
  }

  // ── Misc ───────────────────────────────────────────────────────────────
  function formatRelativeDate(epoch) {
    const ms = Number(epoch);
    if (!Number.isFinite(ms) || ms <= 0) return '';
    const now = Date.now();
    const days = Math.floor((now - ms) / 86_400_000);
    if (days < 1) return 'today';
    if (days < 30) return `${days} day${days === 1 ? '' : 's'} ago`;
    const months = Math.floor(days / 30);
    if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
    const years = Math.floor(days / 365);
    return `${years} year${years === 1 ? '' : 's'} ago`;
  }

  // ── Boot ───────────────────────────────────────────────────────────────
  function route() {
    const params = parseHash();
    if (params.workout && params.repo) {
      renderWorkout(params.repo, params.workout);
    } else if (params.repo) {
      renderRepo(params.repo);
    } else {
      renderRegistry();
    }
    window.scrollTo(0, 0);
  }

  window.addEventListener('hashchange', route);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', route);
  else route();
})();
