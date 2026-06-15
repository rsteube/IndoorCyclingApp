/* ============================================================
   Indoor Bike — Workout Editor
   Timeline-style. Multi-select, rubber-band, drag-to-reorder,
   alt-drag to duplicate, drag handles to resize.
   No backend. State persists in localStorage.
   ============================================================ */

(function () {
  'use strict';

  // ── Zone definitions ────────────────────────────────────────────────────
  // Match the app's `PowerZone` enum (mobile_clients/.../ui/zones/PowerZones.kt).
  const ZONES = [
    { num: 1, name: 'Recovery',  color: '#6B8AA8', low:  0,  high:  55, defaultPct:  50, defaultMin:  5 },
    { num: 2, name: 'Endurance', color: '#2E7BFF', low: 56,  high:  75, defaultPct:  65, defaultMin: 10 },
    { num: 3, name: 'Tempo',     color: '#1FB373', low: 76,  high:  90, defaultPct:  85, defaultMin:  8 },
    { num: 4, name: 'Threshold', color: '#F0C419', low: 91,  high: 105, defaultPct: 100, defaultMin:  5 },
    { num: 5, name: 'VO2',       color: '#FF8A1F', low:106,  high: 120, defaultPct: 115, defaultMin:  3 },
    { num: 6, name: 'Anaerobic', color: '#EB3B3B', low:121,  high: 150, defaultPct: 140, defaultMin:  1 },
    { num: 7, name: 'Neuro',     color: '#C53BFF', low:151,  high: 999, defaultPct: 170, defaultMin:  0.5 }
  ];
  const FREE_TILE = { num: 'F', name: 'Free', color: '#8A7E6C', defaultPct: 60, defaultMin: 5 };
  function zoneFor(pct) { for (const z of ZONES) if (pct <= z.high) return z; return ZONES[6]; }

  const TAGS = [
    'Recovery', 'Endurance', 'Tempo', 'Sweet Spot',
    'Threshold', 'VO2 Max', 'Anaerobic', 'Sprint',
    'Warm-up', 'Cool-down'
  ];

  // ── State ───────────────────────────────────────────────────────────────
  const state = {
    name: '',
    description: '',
    tags: [],
    /** Each segment: { id, lengthInSeconds:int, powerPercentFTP:int } */
    segments: [],
    /** Multi-select: ordered Set so we can grab the "last" for insertion. */
    selectedIds: new Set(),
  };

  /** Internal clipboard for Ctrl+C / Ctrl+V — array (multi-select). */
  let clipboardSegments = [];

  let ftp = 200;

  const STORAGE_KEY = 'indoorbike.editor.workout.v1';
  const FTP_KEY = 'indoorbike.editor.ftp.v1';

  const SNAP_SECONDS = 5;
  const SNAP_PCT = 5;

  function nextId() { return 's' + Math.random().toString(36).slice(2, 9); }
  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }
  function snap(value, step) { return Math.round(value / step) * step; }

  function defaultSegment(zone) {
    return {
      id: nextId(),
      lengthInSeconds: Math.round((zone.defaultMin || 5) * 60),
      powerPercentFTP: zone.defaultPct || 60,
    };
  }
  function fmtClock(totalSeconds) {
    const s = Math.max(0, Math.round(totalSeconds));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = s % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
    return `${m}:${String(ss).padStart(2, '0')}`;
  }

  // ── DOM helpers ─────────────────────────────────────────────────────────
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => document.querySelectorAll(s);

  /**
   * Lightweight createElement helper. Special handling for `style` so that
   * CSS custom properties (`--zone-fill: …`) actually take effect — they
   * have to be set via setProperty(), not via direct property assignment.
   */
  function el(tag, props = {}, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'class') n.className = v;
      else if (k === 'style') {
        for (const [sk, sv] of Object.entries(v)) {
          if (sk.startsWith('--')) n.style.setProperty(sk, String(sv));
          else n.style[sk] = sv;
        }
      }
      else if (k === 'dataset') Object.assign(n.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'html') n.innerHTML = v;
      else if (v !== undefined && v !== null && v !== false) n.setAttribute(k, v);
    }
    for (const c of kids.flat()) {
      if (c == null || c === false) continue;
      n.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return n;
  }

  // ── Persistence ─────────────────────────────────────────────────────────
  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        name: state.name, description: state.description,
        tags: state.tags, segments: state.segments,
      }));
    } catch (_) {}
  }
  function persistFtp() { try { localStorage.setItem(FTP_KEY, String(ftp)); } catch (_) {} }
  function hydrate() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const p = JSON.parse(raw);
        state.name = p.name || '';
        state.description = p.description || '';
        state.tags = Array.isArray(p.tags) ? p.tags : [];
        state.segments = Array.isArray(p.segments)
          ? p.segments.map(s => ({
              id: s.id || nextId(),
              lengthInSeconds: clamp(parseInt(s.lengthInSeconds, 10) || 0, 0, 60 * 60 * 4),
              powerPercentFTP: clamp(parseInt(s.powerPercentFTP, 10) || 0, 0, 300),
            }))
          : [];
      }
    } catch (_) {}
    try {
      const f = parseInt(localStorage.getItem(FTP_KEY), 10);
      if (Number.isFinite(f) && f >= 50 && f <= 600) ftp = f;
    } catch (_) {}
  }

  // ── Stats ───────────────────────────────────────────────────────────────
  function totalSeconds() { return state.segments.reduce((a, s) => a + s.lengthInSeconds, 0); }
  function avgPct() {
    const t = totalSeconds(); if (t === 0) return 0;
    return state.segments.reduce((a, s) => a + s.powerPercentFTP * s.lengthInSeconds, 0) / t;
  }
  function peakPct() { return state.segments.reduce((m, s) => Math.max(m, s.powerPercentFTP), 0); }
  function npPct() {
    let sum4 = 0, n = 0;
    for (const seg of state.segments) {
      sum4 += Math.pow(seg.powerPercentFTP, 4) * seg.lengthInSeconds;
      n += seg.lengthInSeconds;
    }
    if (n === 0) return 0;
    return Math.pow(sum4 / n, 0.25);
  }
  function intensityFactor() { return npPct() / 100; }
  function tss() {
    const i = intensityFactor();
    return Math.round((totalSeconds() / 3600) * i * i * 100);
  }
  function kJ() { return Math.round(((avgPct() / 100) * ftp * totalSeconds()) / 1000); }
  function renderStats() {
    $('#stat-duration').textContent = fmtClock(totalSeconds());
    $('#stat-tss').textContent = tss();
    $('#stat-if').textContent = intensityFactor().toFixed(2);
    const a = Math.round(avgPct());
    const p = Math.round(peakPct());
    $('#stat-avg').innerHTML = `${a}<span class="stat-suffix">%</span>`;
    $('#stat-peak').innerHTML = `${p}<span class="stat-suffix">%</span>`;
    $('#stat-kj').textContent = kJ();
  }

  // ── Y-axis scale ────────────────────────────────────────────────────────
  function yMaxPct() { return Math.max(130, Math.ceil(peakPct() / 25) * 25); }

  // ── Render: zone-band grid (timeline background) ────────────────────────
  function renderGrid() {
    const grid = $('#grid');
    grid.innerHTML = '';
    const yMax = yMaxPct();
    const breaks = [0, 55, 75, 90, 105, 120, 150];
    for (const pct of breaks) {
      if (pct > yMax) continue;
      const yPct = (pct / yMax) * 100;
      const top = (100 - yPct) + '%';
      grid.appendChild(el('div', { class: 'timeline-grid-band', style: { top } }));
      grid.appendChild(el('div', { class: 'timeline-grid-label', style: { top } }, `${pct}%`));
    }
    grid.appendChild(el('div', { class: 'timeline-grid-label', style: { top: '0%' } }, `${yMax}%`));
  }

  // ── Render: time axis ───────────────────────────────────────────────────
  function renderAxis() {
    const axis = $('#time-axis');
    axis.innerHTML = '';
    const total = totalSeconds();
    if (total > 0) {
      const ticks = 6;
      for (let i = 0; i <= ticks; i++) axis.appendChild(el('span', {}, fmtClock((total / ticks) * i)));
    } else {
      axis.appendChild(el('span', {}, '0:00'));
    }
  }

  // ── Render: clips on the timeline ───────────────────────────────────────
  function renderClips() {
    const wrap = $('#clips');
    wrap.innerHTML = '';
    const total = totalSeconds();
    const yMax = yMaxPct();

    if (state.segments.length === 0) {
      wrap.appendChild(el('div', { class: 'timeline-empty' },
        'Drag a tile from the palette above, or tap one to append.'
      ));
      return;
    }

    let cumSec = 0;
    state.segments.forEach((seg, idx) => {
      const z = zoneFor(seg.powerPercentFTP);
      const leftPct = (cumSec / total) * 100;
      const widthPct = (seg.lengthInSeconds / total) * 100;
      const heightPct = (seg.powerPercentFTP / yMax) * 100;
      cumSec += seg.lengthInSeconds;

      const isSelected = state.selectedIds.has(seg.id);

      const clip = el('div', {
        class: 'clip' + (isSelected ? ' is-selected' : ''),
        dataset: { id: seg.id, idx: String(idx) },
        draggable: 'true',
        style: {
          left: leftPct + '%',
          width: 'calc(' + widthPct + '% - 1px)',
          height: heightPct + '%',
          '--zone-fill': z.color,
        },
        title: `Z${z.num} ${z.name} · ${fmtClock(seg.lengthInSeconds)} · ${seg.powerPercentFTP}% FTP`,
      },
        el('div', { class: 'clip-handle clip-handle--top', dataset: { resize: 'top' } }),
        el('div', { class: 'clip-handle clip-handle--right', dataset: { resize: 'right' } }),
        widthPct > 6 ? el('span', { class: 'clip-label' }, `Z${z.num} · ${seg.powerPercentFTP}%`) : null,
      );

      attachClipInteractions(clip, seg, idx);
      wrap.appendChild(clip);
    });
  }

  // ── Selection helpers ───────────────────────────────────────────────────
  function setSelection(ids, mode = 'replace') {
    const next = new Set(mode === 'replace' ? [] : state.selectedIds);
    for (const id of ids) {
      if (mode === 'toggle' && next.has(id)) next.delete(id);
      else next.add(id);
    }
    state.selectedIds = next;
    renderClips();
    renderInspector();
  }
  function clearSelection() { setSelection([], 'replace'); }
  function selectedSegments() {
    // Returns segments in their current array order, filtered to selected.
    return state.segments.filter(s => state.selectedIds.has(s.id));
  }
  function lastSelectedIndex() {
    let last = -1;
    state.segments.forEach((s, i) => { if (state.selectedIds.has(s.id)) last = i; });
    return last;
  }

  // ── Clip interactions: select, drag-reorder, drag-resize, alt-duplicate ─
  function attachClipInteractions(clip, seg, idx) {
    let downX = 0, downY = 0, moved = false;
    clip.addEventListener('mousedown', (e) => {
      if (e.target.matches('[data-resize]')) return;
      downX = e.clientX; downY = e.clientY; moved = false;
    });
    clip.addEventListener('mousemove', (e) => {
      if (Math.hypot(e.clientX - downX, e.clientY - downY) > 4) moved = true;
    });
    clip.addEventListener('click', (e) => {
      if (moved || e.target.matches('[data-resize]')) return;
      e.stopPropagation();
      if (e.ctrlKey || e.metaKey) {
        setSelection([seg.id], 'toggle');
      } else if (e.shiftKey && state.selectedIds.size > 0) {
        // Range select: from the closest selected clip to this one.
        const idxs = state.segments.map((s, i) => state.selectedIds.has(s.id) ? i : -1).filter(i => i >= 0);
        const anchor = idxs.reduce((a, i) => Math.abs(i - idx) < Math.abs(a - idx) ? i : a, idxs[0]);
        const lo = Math.min(anchor, idx), hi = Math.max(anchor, idx);
        const ids = state.segments.slice(lo, hi + 1).map(s => s.id);
        setSelection(ids, 'replace');
      } else {
        setSelection([seg.id], 'replace');
      }
    });

    // Resize handles
    clip.querySelectorAll('[data-resize]').forEach((handle) => {
      const kind = handle.dataset.resize;
      handle.addEventListener('mousedown', (e) => startResize(e, seg, kind));
      handle.addEventListener('touchstart', (e) => {
        const t = e.touches[0];
        startResize({
          clientX: t.clientX, clientY: t.clientY,
          preventDefault: () => e.preventDefault(),
          stopPropagation: () => e.stopPropagation(),
        }, seg, kind, /*touch*/ true);
        e.preventDefault();
      }, { passive: false });
    });

    // HTML5 drag — reorder OR alt-duplicate. Multi-select ⇒ all selected move/copy.
    clip.addEventListener('dragstart', (e) => {
      if (e.target.matches('[data-resize]')) { e.preventDefault(); return; }
      // If the clip we started dragging isn't in the current selection, replace
      // selection with just it. Standard behavior in editors / file managers.
      if (!state.selectedIds.has(seg.id)) {
        setSelection([seg.id], 'replace');
      }
      currentDrag = {
        kind: e.altKey ? 'duplicate-existing' : 'reorder',
        ids: [...state.selectedIds],
      };
      e.dataTransfer.effectAllowed = e.altKey ? 'copy' : 'move';
      try { e.dataTransfer.setData('text/plain', currentDrag.ids.join(',')); } catch (_) {}
      // Visual: dim every dragged clip.
      $$('.clip').forEach(c => {
        if (state.selectedIds.has(c.dataset.id)) c.classList.add('is-dragging');
      });
      showGapIndicators();
    });
    clip.addEventListener('dragend', () => {
      $$('.clip').forEach(c => c.classList.remove('is-dragging'));
      hideGapIndicators();
      currentDrag = null;
    });
  }

  // ── Resize state ────────────────────────────────────────────────────────
  let resizing = null;
  function startResize(e, seg, kind, isTouch) {
    e.preventDefault?.();
    e.stopPropagation?.();
    setSelection([seg.id], 'replace');

    const tl = $('#timeline');
    const rect = tl.getBoundingClientRect();
    const totalAtStart = totalSeconds();
    const yMaxAtStart = yMaxPct();
    const secsPerPx = totalAtStart / rect.width;
    const pctPerPx = yMaxAtStart / rect.height;

    resizing = {
      seg, kind,
      startX: e.clientX, startY: e.clientY,
      startLength: seg.lengthInSeconds,
      startPct: seg.powerPercentFTP,
      secsPerPx, pctPerPx,
    };
    if (isTouch) {
      window.addEventListener('touchmove', onResizeMoveTouch, { passive: false });
      window.addEventListener('touchend', stopResize);
      window.addEventListener('touchcancel', stopResize);
    } else {
      window.addEventListener('mousemove', onResizeMove);
      window.addEventListener('mouseup', stopResize);
    }
  }
  function onResizeMoveTouch(e) {
    if (!resizing) return;
    e.preventDefault();
    const t = e.touches[0];
    onResizeMove({ clientX: t.clientX, clientY: t.clientY });
  }
  function onResizeMove(e) {
    if (!resizing) return;
    const dx = e.clientX - resizing.startX;
    const dy = e.clientY - resizing.startY;
    if (resizing.kind === 'right') {
      const newLen = clamp(snap(resizing.startLength + dx * resizing.secsPerPx, SNAP_SECONDS), 5, 60 * 60 * 2);
      if (newLen !== resizing.seg.lengthInSeconds) {
        resizing.seg.lengthInSeconds = newLen;
        renderClips(); renderStats(); renderAxis(); renderInspector();
      }
    } else if (resizing.kind === 'top') {
      const newPct = clamp(snap(resizing.startPct - dy * resizing.pctPerPx, SNAP_PCT), 0, 250);
      if (newPct !== resizing.seg.powerPercentFTP) {
        resizing.seg.powerPercentFTP = newPct;
        renderGrid(); renderClips(); renderStats(); renderInspector();
      }
    }
  }
  function stopResize() {
    if (!resizing) return;
    resizing = null;
    window.removeEventListener('mousemove', onResizeMove);
    window.removeEventListener('mouseup', stopResize);
    window.removeEventListener('touchmove', onResizeMoveTouch);
    window.removeEventListener('touchend', stopResize);
    window.removeEventListener('touchcancel', stopResize);
    persist();
  }

  // ── Gap indicators (drop targets between clips) ─────────────────────────
  let currentDrag = null; // { kind, ids?, zone? }
  function showGapIndicators() {
    const tl = $('#timeline');
    const total = totalSeconds() || 1;
    tl.querySelectorAll('.gap-indicator').forEach(g => g.remove());
    let cum = 0;
    const positions = [0];
    for (const s of state.segments) {
      cum += s.lengthInSeconds;
      positions.push(cum);
    }
    positions.forEach((sec, i) => {
      const leftPct = (sec / total) * 100;
      tl.appendChild(el('div', {
        class: 'gap-indicator is-visible',
        dataset: { insertAt: String(i) },
        style: { left: `calc(${leftPct}% - 2px)` },
      }));
    });
  }
  function hideGapIndicators() {
    $('#timeline').querySelectorAll('.gap-indicator').forEach(g => g.remove());
  }
  /** Closest insertion index given a cursor X coord. */
  function indexForDropX(clientX) {
    const tl = $('#timeline');
    const rect = tl.getBoundingClientRect();
    const x = clamp(clientX - rect.left, 0, rect.width);
    const total = totalSeconds() || 1;
    const targetSec = (x / rect.width) * total;
    let cum = 0;
    let best = 0, bestDist = Infinity;
    for (let i = 0; i <= state.segments.length; i++) {
      const dist = Math.abs(cum - targetSec);
      if (dist < bestDist) { bestDist = dist; best = i; }
      if (i < state.segments.length) cum += state.segments[i].lengthInSeconds;
    }
    return best;
  }

  // ── Render: palette tiles ───────────────────────────────────────────────
  function renderPalette() {
    const root = $('#palette');
    root.innerHTML = '';
    for (const z of ZONES) root.appendChild(buildPaletteTile(z));
    root.appendChild(buildPaletteTile(FREE_TILE, /*isFree*/ true));
  }
  function buildPaletteTile(zoneOrFree, isFree = false) {
    const tile = el('button', {
      class: 'palette-tile',
      type: 'button',
      title: isFree ? 'Add a custom segment' : `Add ${zoneOrFree.name} (Z${zoneOrFree.num})`,
      draggable: 'true',
      style: { '--zone-fill': zoneOrFree.color },
    },
      el('span', { class: 'palette-tile-z' }, isFree ? 'F' : `Z${zoneOrFree.num}`),
      el('span', { class: 'palette-tile-name' }, zoneOrFree.name)
    );
    tile.addEventListener('click', () => {
      const seg = isFree
        ? { id: nextId(), lengthInSeconds: 300, powerPercentFTP: 60 }
        : defaultSegment(zoneOrFree);
      // Insert after the *last* selected clip. With no selection, append.
      const insertAt = lastSelectedIndex() + 1; // -1 + 1 = 0… but we want end. Adjust:
      const idx = state.selectedIds.size === 0 ? state.segments.length : insertAt;
      state.segments.splice(idx, 0, seg);
      afterChange([seg.id]);
    });
    tile.addEventListener('dragstart', (e) => {
      currentDrag = { kind: 'palette', zone: zoneOrFree };
      try { e.dataTransfer.setData('text/plain', 'palette:' + zoneOrFree.num); } catch (_) {}
      e.dataTransfer.effectAllowed = 'copy';
      tile.classList.add('is-dragging');
      showGapIndicators();
    });
    tile.addEventListener('dragend', () => {
      tile.classList.remove('is-dragging');
      hideGapIndicators();
      currentDrag = null;
    });
    return tile;
  }

  // ── Timeline: drop handling ─────────────────────────────────────────────
  function setupTimelineDropTarget() {
    const tl = $('#timeline');
    tl.addEventListener('dragover', (e) => {
      if (!currentDrag) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = (currentDrag.kind === 'palette' || currentDrag.kind === 'duplicate-existing')
        ? 'copy' : 'move';
      const idx = indexForDropX(e.clientX);
      tl.querySelectorAll('.gap-indicator').forEach(g => {
        g.classList.toggle('is-active', parseInt(g.dataset.insertAt, 10) === idx);
      });
    });
    tl.addEventListener('drop', (e) => {
      if (!currentDrag) return;
      e.preventDefault();
      const idx = indexForDropX(e.clientX);

      if (currentDrag.kind === 'palette') {
        const seg = currentDrag.zone === FREE_TILE
          ? { id: nextId(), lengthInSeconds: 300, powerPercentFTP: 60 }
          : defaultSegment(currentDrag.zone);
        state.segments.splice(idx, 0, seg);
        hideGapIndicators(); currentDrag = null;
        afterChange([seg.id]);

      } else if (currentDrag.kind === 'reorder') {
        moveSelectedTo(idx, currentDrag.ids);
        hideGapIndicators(); currentDrag = null;

      } else if (currentDrag.kind === 'duplicate-existing') {
        duplicateSelectedTo(idx, currentDrag.ids);
        hideGapIndicators(); currentDrag = null;
      }
    });

    // ── Click empty timeline = clear selection ──────────────────────────
    // ── Drag empty timeline = rubber-band selection ─────────────────────
    tl.addEventListener('mousedown', (e) => {
      if (e.target !== tl &&
          !e.target.classList.contains('timeline-grid') &&
          !e.target.classList.contains('timeline-grid-band') &&
          !e.target.classList.contains('timeline-grid-label') &&
          !e.target.classList.contains('timeline-empty') &&
          !e.target.classList.contains('timeline-clips')) {
        return; // pressing on a clip / handle / gap — let other handlers run
      }
      if (e.button !== 0) return;
      startRubberBand(e);
    });
  }

  function moveSelectedTo(dropIdx, ids) {
    // Snapshot the clips in their current array order, then splice them out.
    const toMove = state.segments.filter(s => ids.includes(s.id));
    if (toMove.length === 0) return;

    const remaining = state.segments.filter(s => !ids.includes(s.id));
    // Recompute drop index relative to the remaining array — for each id we
    // remove that sits before dropIdx, dropIdx shifts down by 1.
    let adjusted = dropIdx;
    state.segments.forEach((s, i) => {
      if (ids.includes(s.id) && i < dropIdx) adjusted--;
    });
    adjusted = clamp(adjusted, 0, remaining.length);
    remaining.splice(adjusted, 0, ...toMove);
    state.segments = remaining;
    afterChange(ids);
  }

  function duplicateSelectedTo(dropIdx, ids) {
    const toCopy = state.segments.filter(s => ids.includes(s.id));
    if (toCopy.length === 0) return;
    const copies = toCopy.map(s => ({ ...s, id: nextId() }));
    state.segments.splice(dropIdx, 0, ...copies);
    afterChange(copies.map(c => c.id));
  }

  // ── Rubber-band selection ───────────────────────────────────────────────
  let rubberBand = null;
  function startRubberBand(e) {
    const tl = $('#timeline');
    const rect = tl.getBoundingClientRect();
    const x0 = clamp(e.clientX - rect.left, 0, rect.width);
    const y0 = clamp(e.clientY - rect.top, 0, rect.height);

    rubberBand = {
      x0, y0, rect,
      box: el('div', { class: 'rubberband', style: { left: x0 + 'px', top: y0 + 'px', width: '0px', height: '0px' } }),
      shift: e.shiftKey,
      moved: false,
    };
    tl.appendChild(rubberBand.box);
    tl.classList.add('is-selecting');

    window.addEventListener('mousemove', moveRubberBand);
    window.addEventListener('mouseup', endRubberBand);
    e.preventDefault();
  }
  function moveRubberBand(e) {
    if (!rubberBand) return;
    const r = rubberBand.rect;
    const x = clamp(e.clientX - r.left, 0, r.width);
    const y = clamp(e.clientY - r.top, 0, r.height);
    const left = Math.min(x, rubberBand.x0);
    const top = Math.min(y, rubberBand.y0);
    const w = Math.abs(x - rubberBand.x0);
    const h = Math.abs(y - rubberBand.y0);
    rubberBand.box.style.left = left + 'px';
    rubberBand.box.style.top = top + 'px';
    rubberBand.box.style.width = w + 'px';
    rubberBand.box.style.height = h + 'px';
    if (w > 3 || h > 3) rubberBand.moved = true;

    // Live select clips that intersect the rectangle.
    const tlRect = r;
    const selX0 = left, selX1 = left + w, selY0 = top, selY1 = top + h;
    const newIds = [];
    $$('.clip').forEach((clipEl) => {
      const cr = clipEl.getBoundingClientRect();
      const cx0 = cr.left - tlRect.left;
      const cx1 = cr.right - tlRect.left;
      const cy0 = cr.top - tlRect.top;
      const cy1 = cr.bottom - tlRect.top;
      const intersects = cx0 < selX1 && cx1 > selX0 && cy0 < selY1 && cy1 > selY0;
      if (intersects) newIds.push(clipEl.dataset.id);
    });
    setSelection(newIds, rubberBand.shift ? 'add' : 'replace');
  }
  function endRubberBand(e) {
    if (!rubberBand) return;
    const tl = $('#timeline');
    rubberBand.box.remove();
    tl.classList.remove('is-selecting');
    // If user just clicked (no drag), clear selection.
    if (!rubberBand.moved && !rubberBand.shift) clearSelection();
    rubberBand = null;
    window.removeEventListener('mousemove', moveRubberBand);
    window.removeEventListener('mouseup', endRubberBand);
  }

  // ── Inspector ───────────────────────────────────────────────────────────
  function renderInspector() {
    const root = $('#inspector');
    root.innerHTML = '';
    const sel = selectedSegments();

    // Empty state
    if (sel.length === 0) {
      root.appendChild(el('div', { class: 'inspector-empty' },
        el('span', {}, 'Click a clip to edit. Drag empty space to box-select. Alt-drag to duplicate.'),
        el('span', {},
          el('kbd', {}, 'Click'), ' select · ',
          el('kbd', {}, 'Ctrl/⌘+Click'), ' toggle · ',
          el('kbd', {}, 'Shift+Click'), ' range · ',
          el('kbd', {}, 'Del'), ' delete · ',
          el('kbd', {}, 'Ctrl+D'), ' duplicate · ',
          el('kbd', {}, '↑↓'), ' nudge power · ',
          el('kbd', {}, '←→'), ' nudge duration · ',
          el('kbd', {}, 'Esc'), ' deselect'
        )
      ));
      return;
    }

    // Multi-selection: show summary + bulk actions only.
    if (sel.length > 1) {
      const totalSec = sel.reduce((a, s) => a + s.lengthInSeconds, 0);
      const avgPctSel = Math.round(sel.reduce((a, s) => a + s.powerPercentFTP * s.lengthInSeconds, 0) / Math.max(1, totalSec));
      root.appendChild(el('div', { class: 'inspector-zone' },
        el('div', {},
          el('div', { class: 'inspector-zone-label' }, `${sel.length} clips selected`),
          el('div', { class: 'inspector-zone-sub' }, `${fmtClock(totalSec)} · avg ${avgPctSel}% FTP`)
        )
      ));
      root.appendChild(el('span', { class: 'inspector-spacer' }));
      root.appendChild(el('button', {
        class: 'inspector-btn',
        type: 'button',
        onClick: () => duplicateMany(sel.map(s => s.id)),
      }, 'Duplicate'));
      root.appendChild(el('button', {
        class: 'inspector-btn is-danger',
        type: 'button',
        onClick: () => deleteMany(sel.map(s => s.id)),
      }, 'Delete'));
      return;
    }

    // Single selection: inline editors.
    const seg = sel[0];
    const z = zoneFor(seg.powerPercentFTP);
    const watts = Math.round(ftp * seg.powerPercentFTP / 100);
    const min = Math.floor(seg.lengthInSeconds / 60);
    const sec = seg.lengthInSeconds % 60;

    const minInput = el('input', {
      type: 'number', min: '0', max: '240', value: String(min), inputmode: 'numeric',
      'aria-label': 'Minutes',
      onInput: (e) => {
        const m = clamp(parseInt(e.target.value, 10) || 0, 0, 240);
        seg.lengthInSeconds = m * 60 + (seg.lengthInSeconds % 60);
        afterChange([seg.id], /*skipInspector*/ true);
      }
    });
    const secInput = el('input', {
      type: 'number', min: '0', max: '59', value: String(sec).padStart(2, '0'), inputmode: 'numeric',
      'aria-label': 'Seconds',
      onInput: (e) => {
        const s = clamp(parseInt(e.target.value, 10) || 0, 0, 59);
        seg.lengthInSeconds = Math.floor(seg.lengthInSeconds / 60) * 60 + s;
        afterChange([seg.id], true);
      }
    });
    const pctInput = el('input', {
      type: 'number', min: '0', max: '300', value: String(seg.powerPercentFTP), inputmode: 'numeric',
      'aria-label': 'Power percent of FTP',
      onInput: (e) => {
        seg.powerPercentFTP = clamp(parseInt(e.target.value, 10) || 0, 0, 300);
        afterChange([seg.id], true);
      }
    });

    root.appendChild(el('div', { class: 'inspector-zone' },
      el('div', { class: 'inspector-swatch', style: { '--zone-fill': z.color } }),
      el('div', {},
        el('div', { class: 'inspector-zone-label' }, `Z${z.num} · ${z.name}`),
        el('div', { class: 'inspector-zone-sub' }, `≈ ${watts} W`)
      )
    ));
    root.appendChild(el('div', { class: 'inspector-field' },
      el('label', {}, 'DURATION'),
      minInput,
      el('span', { class: 'unit' }, ':'),
      secInput,
      el('span', { class: 'unit' }, 'min:sec')
    ));
    root.appendChild(el('div', { class: 'inspector-field' },
      el('label', {}, 'POWER'),
      pctInput,
      el('span', { class: 'unit' }, '% FTP')
    ));
    root.appendChild(el('span', { class: 'inspector-spacer' }));
    root.appendChild(el('button', {
      class: 'inspector-btn',
      type: 'button',
      onClick: () => duplicateMany([seg.id]),
    }, 'Duplicate'));
    root.appendChild(el('button', {
      class: 'inspector-btn is-danger',
      type: 'button',
      onClick: () => deleteMany([seg.id]),
    }, 'Delete'));
  }

  // ── Mutations ───────────────────────────────────────────────────────────
  function duplicateMany(ids) {
    const toCopy = state.segments.filter(s => ids.includes(s.id));
    if (toCopy.length === 0) return;
    // Insert each copy immediately after the last of its source's bloc.
    const lastIdx = ids.reduce((m, id) => Math.max(m, state.segments.findIndex(s => s.id === id)), -1);
    const copies = toCopy.map(s => ({ ...s, id: nextId() }));
    state.segments.splice(lastIdx + 1, 0, ...copies);
    afterChange(copies.map(c => c.id));
  }
  function deleteMany(ids) {
    state.segments = state.segments.filter(s => !ids.includes(s.id));
    state.selectedIds = new Set([...state.selectedIds].filter(id => !ids.includes(id)));
    afterChange();
  }
  function moveSelectedByOffset(delta) {
    if (state.selectedIds.size === 0) return;
    const sel = selectedSegments();
    const ids = sel.map(s => s.id);
    const firstIdx = state.segments.findIndex(s => state.selectedIds.has(s.id));
    const lastIdx = lastSelectedIndex();
    const targetIdx = clamp(
      delta > 0 ? lastIdx + 1 + delta : firstIdx + delta,
      0,
      state.segments.length
    );
    moveSelectedTo(targetIdx, ids);
  }

  /** Recompute everything that depends on segment data. */
  function afterChange(focusIds, skipInspector) {
    if (Array.isArray(focusIds)) state.selectedIds = new Set(focusIds);
    persist();
    renderGrid();
    renderClips();
    renderAxis();
    renderStats();
    if (!skipInspector) renderInspector();
  }

  // ── Tags ────────────────────────────────────────────────────────────────
  function renderTags() {
    const root = $('#tag-grid');
    root.innerHTML = '';
    for (const tag of TAGS) {
      const pressed = state.tags.includes(tag);
      root.appendChild(el('button', {
        type: 'button',
        class: 'tag-chip',
        'aria-pressed': pressed ? 'true' : 'false',
        onClick: () => {
          if (state.tags.includes(tag)) state.tags = state.tags.filter(t => t !== tag);
          else state.tags = [...state.tags, tag];
          persist(); renderTags();
        }
      }, tag));
    }
  }

  // ── Export ──────────────────────────────────────────────────────────────
  function slug(name) {
    return name.toLowerCase()
      .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'workout';
  }
  function buildWorkoutObject() {
    return {
      id: `editor-${slug(state.name || 'workout')}-${Date.now().toString(36)}`,
      name: (state.name || 'Untitled workout').trim(),
      description: state.description.trim() || null,
      tags: state.tags.length ? state.tags : undefined,
      segments: state.segments.map(s => ({
        lengthInSeconds: s.lengthInSeconds,
        powerPercentFTP: s.powerPercentFTP,
        intervalType: 'CONSTANT',
      })),
    };
  }
  function downloadJson() {
    const w = buildWorkoutObject();
    const text = JSON.stringify(w, null, 2);
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = `${slug(w.name)}.json`;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('Downloaded ' + a.download);
  }
  async function shareToPhone() {
    const w = buildWorkoutObject();
    const text = JSON.stringify(w, null, 2);
    const file = new File([text], `${slug(w.name)}.json`, { type: 'application/json' });
    try {
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: w.name });
      } else if (navigator.share) {
        await navigator.share({ title: w.name, text });
      } else {
        toast('Web Share isn\'t available on this device — use Download instead.', true);
      }
    } catch (e) {
      if (e && e.name !== 'AbortError') toast('Share failed: ' + (e.message || e), true);
    }
  }
  async function copyJson() {
    const text = JSON.stringify(buildWorkoutObject(), null, 2);
    try {
      await navigator.clipboard.writeText(text);
      toast('JSON copied to clipboard');
    } catch (_) { toast('Copy failed — clipboard access blocked.', true); }
  }

  function b64urlEncodeBytes(bytes) {
    let bin = '';
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function b64urlEncode(str) {
    return b64urlEncodeBytes(new TextEncoder().encode(str));
  }
  function b64urlDecodeBytes(str) {
    const padded = str.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((str.length + 3) % 4);
    const bin = atob(padded);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  /**
   * Decode an inbound `#wz=…` (gzipped) or `#w=…` (plain) hash payload.
   * Used when the user arrives via "Open in editor" from the community
   * browser — same wire format the share page and Android app accept, so
   * one URL works everywhere a workout might land.
   * Returns the parsed workout, or null if no usable payload is present.
   */
  function readWorkoutFromHash() {
    const raw = (location.hash || '').replace(/^#/, '');
    if (!raw) return null;
    const wz = raw.match(/(?:^|&)wz=([^&]+)/);
    if (wz && typeof pako !== 'undefined' && pako.ungzip) {
      try {
        const json = pako.ungzip(b64urlDecodeBytes(decodeURIComponent(wz[1])), { to: 'string' });
        const obj = JSON.parse(json);
        if (obj && Array.isArray(obj.segments)) return obj;
      } catch (_) { /* fall through to plain form */ }
    }
    const w = raw.match(/(?:^|&)w=([^&]+)/);
    if (w) {
      try {
        const json = new TextDecoder().decode(b64urlDecodeBytes(decodeURIComponent(w[1])));
        const obj = JSON.parse(json);
        if (obj && Array.isArray(obj.segments)) return obj;
      } catch (_) { /* ignore */ }
    }
    return null;
  }
  // ── Inbound URL-param API (third-party workout hand-off) ────────────────
  // A 3rd-party site (e.g. VertMatch) can deep-link a ready-made workout into
  // the editor with plain, human-readable query params instead of the binary
  // #w= / #wz= blob — no gzip, no base64, nothing to compute. Full spec:
  //   docs/EDITOR_URL_API.md  +  the machine copy at  /editor/llms.txt
  // Example:
  //   /editor/?v=1&name=Threshold+3x8&ftp=250&s=10m@50,3x(8m@100;4m@55),5m@50
  const MAX_PARAM_SEGMENTS = 200;

  /** Split on `sep` at the top level only (ignoring separators inside parens). */
  function splitTopLevel(str, sep) {
    const parts = [];
    let depth = 0, cur = '';
    for (const ch of str) {
      if (ch === '(') { depth++; cur += ch; }
      else if (ch === ')') { depth = Math.max(0, depth - 1); cur += ch; }
      else if (ch === sep && depth === 0) { parts.push(cur); cur = ''; }
      else cur += ch;
    }
    parts.push(cur);
    return parts;
  }

  /** "300" | "300s" | "5m" | "2.5m" → integer seconds, or null when unparseable. */
  function parseDurationToken(tok) {
    const m = String(tok).trim().match(/^(\d+(?:\.\d+)?)\s*(m|s)?$/i);
    if (!m) return null;
    let n = parseFloat(m[1]);
    if (!Number.isFinite(n)) return null;
    if ((m[2] || 's').toLowerCase() === 'm') n *= 60;
    return clamp(Math.round(n), 1, 60 * 60 * 4);
  }

  /** "DUR@PCT" → a segment object, or null when unparseable. */
  function parseSegmentToken(tok) {
    const at = String(tok).indexOf('@');
    if (at < 0) return null;
    const secs = parseDurationToken(tok.slice(0, at));
    const pct = parseInt(tok.slice(at + 1), 10);
    if (secs == null || !Number.isFinite(pct)) return null;
    return { id: nextId(), lengthInSeconds: secs, powerPercentFTP: clamp(pct, 0, 300) };
  }

  /**
   * Parse the `s=` segment DSL: a comma-separated list where each item is
   * either a single `DUR@PCT` segment or a repeat group `Nx(seg;seg;…)` /
   * shorthand `NxDUR@PCT`. Invalid items are skipped. Returns [] on no match.
   */
  function parseSegmentsDSL(s) {
    const out = [];
    for (let item of splitTopLevel(String(s), ',')) {
      item = item.trim();
      if (!item) continue;
      const rep = item.match(/^(\d+)\s*[x*]\s*(.+)$/i);
      if (rep) {
        const reps = clamp(parseInt(rep[1], 10) || 1, 1, 40);
        const body = rep[2].trim();
        const inner = (body.startsWith('(') && body.endsWith(')'))
          ? splitTopLevel(body.slice(1, -1), ';').map(t => parseSegmentToken(t.trim())).filter(Boolean)
          : [parseSegmentToken(body)].filter(Boolean);
        for (let r = 0; r < reps; r++) {
          for (const seg of inner) out.push({ ...seg, id: nextId() });
          if (out.length >= MAX_PARAM_SEGMENTS) break;
        }
      } else {
        const seg = parseSegmentToken(item);
        if (seg) out.push(seg);
      }
      if (out.length >= MAX_PARAM_SEGMENTS) break;
    }
    return out.slice(0, MAX_PARAM_SEGMENTS);
  }

  /**
   * Read a workout from the query string (the documented third-party API).
   * Requires `s=` to be present; `name` / `desc` / `tags` / `ftp` ride along.
   * Returns the parsed workout, or null when there's no `s=` param. When `s=`
   * is present but yields no valid segments, returns null and toasts an error.
   */
  function readWorkoutFromQuery() {
    const params = new URLSearchParams(location.search);
    const s = params.get('s');
    if (s == null) return null;
    const segments = parseSegmentsDSL(s);
    if (segments.length === 0) {
      toast('Couldn\'t read a workout from this link — check the `s` parameter.', true);
      return null;
    }
    const desc = params.get('desc') ?? params.get('description') ?? '';
    const tags = params.get('tags');
    const out = {
      name: (params.get('name') || '').slice(0, 80),
      description: desc.slice(0, 600),
      tags: tags ? tags.split(',').map(t => t.trim()).filter(Boolean) : [],
      segments,
    };
    const ftpParam = parseInt(params.get('ftp'), 10);
    if (Number.isFinite(ftpParam) && ftpParam >= 50 && ftpParam <= 600) out._ftp = ftpParam;
    return out;
  }

  /**
   * Build the share URL. We gzip-then-base64url the JSON whenever pako is
   * loaded — typical workout JSON has so many repeated keys that this cuts
   * URL length in half, which drops the QR a few versions and makes modules
   * chunky enough for phone cameras to lock onto. The decoder side
   * (share.js, WorkoutLinkParser.kt) accepts both `#w=` (plain) and `#wz=`
   * (gzipped) so older links keep working.
   */
  function buildShareUrl(w) {
    const json = JSON.stringify(w);
    if (typeof pako !== 'undefined' && pako.gzip) {
      try {
        const gz = pako.gzip(json);
        return `https://indoorbike.app/share/#wz=${b64urlEncodeBytes(gz)}`;
      } catch (_) { /* fall through to plain */ }
    }
    return `https://indoorbike.app/share/#w=${b64urlEncode(json)}`;
  }
  function showQrModal() {
    const w = buildWorkoutObject();
    const url = buildShareUrl(w);
    $('#qr-link').value = url;
    const stage = $('#qr-stage');
    stage.innerHTML = '';
    try {
      const qr = qrcode(0, 'L');
      qr.addData(url); qr.make();
      // SVG renders crisply at any modal size. createImgTag produces a fixed
      // bitmap that browsers re-scale to fit, which fragments QR modules into
      // uneven pixel widths and breaks camera scanners.
      stage.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 4, scalable: true });
    } catch (_) {
      stage.innerHTML = '<p style="padding:24px;color:#444;text-align:center;font-family:Inter;">Workout too large for a QR code.<br>Use <b>Download</b> or <b>Share to phone</b> instead.</p>';
    }
    const sizeKb = (new Blob([JSON.stringify(w)]).size / 1024).toFixed(2);
    $('#qr-size-hint').textContent = `Workout size: ${sizeKb} KB · URL length: ${url.length} chars`;
    openModal('#qr-modal');
  }

  // ── Load file ───────────────────────────────────────────────────────────
  function handleLoadFile(file) {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const parsed = JSON.parse(reader.result);
        loadWorkout(Array.isArray(parsed) ? parsed[0] : parsed);
      } catch (e) { toast('Couldn\'t parse: ' + e.message, true); }
    };
    reader.readAsText(file);
  }
  function loadWorkout(w) {
    if (!w || !Array.isArray(w.segments)) { toast('Not a workout file (no segments)', true); return; }
    state.name = w.name || '';
    state.description = w.description || '';
    state.tags = Array.isArray(w.tags) ? w.tags : [];
    state.segments = w.segments
      .filter(s => s && Number.isFinite(s.lengthInSeconds) && Number.isFinite(s.powerPercentFTP))
      .map(s => ({
        id: nextId(),
        lengthInSeconds: clamp(parseInt(s.lengthInSeconds, 10), 0, 60 * 60 * 4),
        powerPercentFTP: clamp(parseInt(s.powerPercentFTP, 10), 0, 300),
      }));
    state.selectedIds = new Set();
    $('#workout-name').value = state.name;
    $('#workout-description').value = state.description;
    afterChange();
    renderTags();
    toast(`Loaded "${state.name || 'workout'}" with ${state.segments.length} segments`);
  }
  function resetAll() {
    const hadWork = state.segments.length > 0 || !!state.name || !!state.description;
    const snapshot = hadWork ? captureState() : null;
    state.name = ''; state.description = ''; state.tags = [];
    state.segments = []; state.selectedIds = new Set();
    $('#workout-name').value = ''; $('#workout-description').value = '';
    afterChange(); renderTags();
    if (snapshot) {
      toast('Cleared', false, { label: 'Undo', duration: 12000, onClick: () => restoreState(snapshot) });
    } else {
      toast('Cleared');
    }
  }

  // ── Undo support (snapshot / restore the whole workout) ──────────────────
  // Used to make destructive loads/resets reversible instead of gating them
  // behind a blocking confirm() dialog. A snapshot is a deep-ish copy of the
  // user-visible workout plus the FTP preview value.
  let pendingLoadToast = null; // fired at end of init() once renderers are wired
  function captureState() {
    return {
      name: state.name,
      description: state.description,
      tags: [...state.tags],
      segments: state.segments.map(s => ({ ...s })),
      ftp,
    };
  }
  function restoreState(snap) {
    state.name = snap.name || '';
    state.description = snap.description || '';
    state.tags = Array.isArray(snap.tags) ? [...snap.tags] : [];
    state.segments = snap.segments.map(s => ({ ...s, id: nextId() }));
    state.selectedIds = new Set();
    if (Number.isFinite(snap.ftp)) { ftp = snap.ftp; persistFtp(); $('#ftp-input').value = String(ftp); }
    $('#workout-name').value = state.name;
    $('#workout-description').value = state.description;
    afterChange();
    renderTags();
    toast('Restored your previous workout');
  }

  // ── Modals ──────────────────────────────────────────────────────────────
  function openModal(sel) { $(sel).hidden = false; }
  function closeAllModals() { $$('.modal').forEach(m => m.hidden = true); }
  function bindModalCloses() {
    document.addEventListener('click', (e) => {
      if (e.target.closest('[data-modal-close]')) {
        const m = e.target.closest('.modal');
        if (m) m.hidden = true;
      }
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && document.querySelector('.modal:not([hidden])')) {
        e.preventDefault();
        closeAllModals();
      }
    });
  }

  // ── Set builder ─────────────────────────────────────────────────────────
  function addIntervalSet() {
    const reps = clamp(parseInt($('#set-reps').value, 10) || 0, 1, 40);
    const wMin = clamp(parseFloat($('#set-work-min').value) || 0, 0, 60);
    const wPct = clamp(parseInt($('#set-work-pct').value, 10) || 0, 0, 300);
    const rMin = clamp(parseFloat($('#set-rec-min').value) || 0, 0, 60);
    const rPct = clamp(parseInt($('#set-rec-pct').value, 10) || 0, 0, 300);
    if ((wMin === 0 && rMin === 0) || reps === 0) {
      toast('Need at least one rep with a positive duration.', true); return;
    }
    const insertAt = state.selectedIds.size === 0 ? state.segments.length : lastSelectedIndex() + 1;
    const newIds = [];
    const generated = [];
    for (let i = 0; i < reps; i++) {
      if (wMin > 0) {
        const id = nextId();
        generated.push({ id, lengthInSeconds: Math.round(wMin * 60), powerPercentFTP: wPct });
        newIds.push(id);
      }
      if (rMin > 0) {
        const id = nextId();
        generated.push({ id, lengthInSeconds: Math.round(rMin * 60), powerPercentFTP: rPct });
        newIds.push(id);
      }
    }
    state.segments.splice(insertAt, 0, ...generated);
    closeAllModals();
    afterChange(newIds);
    toast(`Added ${reps} reps`);
  }

  // ── Toast ───────────────────────────────────────────────────────────────
  // Optional `action` = { label, onClick, duration } renders an inline button
  // (e.g. "Undo") and holds the toast open longer so it's actually clickable.
  let toastTimer = null;
  function toast(msg, isError = false, action = null) {
    const t = $('#toast');
    t.classList.toggle('toast-error', isError);
    t.innerHTML = '';
    t.appendChild(el('span', { class: 'toast-msg' }, msg));
    let duration = 2400;
    if (action) {
      duration = action.duration || 9000;
      t.appendChild(el('button', {
        class: 'toast-action', type: 'button',
        onClick: () => {
          t.hidden = true;
          if (toastTimer) clearTimeout(toastTimer);
          action.onClick();
        },
      }, action.label || 'Undo'));
    }
    t.hidden = false;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, duration);
  }

  // ── Keyboard shortcuts ──────────────────────────────────────────────────
  function bindKeyboard() {
    document.addEventListener('keydown', (e) => {
      const tag = (e.target.tagName || '').toLowerCase();
      const editing = ['input', 'textarea', 'select'].includes(tag) || e.target.isContentEditable;
      if (editing) return;

      const sel = selectedSegments();
      const ids = sel.map(s => s.id);

      // Select all
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
        e.preventDefault();
        setSelection(state.segments.map(s => s.id), 'replace');
        return;
      }
      // Copy / Cut / Paste — work on the multi-selection
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c' && sel.length) {
        clipboardSegments = sel.map(s => ({ ...s }));
        toast(`${sel.length} clip${sel.length === 1 ? '' : 's'} copied`);
        e.preventDefault(); return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'x' && sel.length) {
        clipboardSegments = sel.map(s => ({ ...s }));
        deleteMany(ids);
        toast(`${sel.length} clip${sel.length === 1 ? '' : 's'} cut`);
        e.preventDefault(); return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v' && clipboardSegments.length) {
        const insertAt = sel.length ? lastSelectedIndex() + 1 : state.segments.length;
        const copies = clipboardSegments.map(c => ({ ...c, id: nextId() }));
        state.segments.splice(insertAt, 0, ...copies);
        afterChange(copies.map(c => c.id));
        e.preventDefault(); return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd' && sel.length) {
        e.preventDefault();
        duplicateMany(ids); return;
      }
      if (e.key === 'Escape') {
        if (state.selectedIds.size) { clearSelection(); e.preventDefault(); }
        return;
      }
      if (sel.length === 0) return;
      if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault(); deleteMany(ids); return;
      }
      if (e.key === 'ArrowLeft' && e.shiftKey) {
        e.preventDefault(); moveSelectedByOffset(-1); return;
      }
      if (e.key === 'ArrowRight' && e.shiftKey) {
        e.preventDefault(); moveSelectedByOffset(1); return;
      }
      // Power / duration nudges apply per selected clip.
      if (e.key === 'ArrowLeft') {
        e.preventDefault();
        sel.forEach(s => { s.lengthInSeconds = clamp(s.lengthInSeconds - SNAP_SECONDS, 5, 60 * 60 * 2); });
        afterChange(ids, true); renderInspector(); return;
      }
      if (e.key === 'ArrowRight') {
        e.preventDefault();
        sel.forEach(s => { s.lengthInSeconds = clamp(s.lengthInSeconds + SNAP_SECONDS, 5, 60 * 60 * 2); });
        afterChange(ids, true); renderInspector(); return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        sel.forEach(s => { s.powerPercentFTP = clamp(s.powerPercentFTP + SNAP_PCT, 0, 300); });
        afterChange(ids, true); renderInspector(); return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        sel.forEach(s => { s.powerPercentFTP = clamp(s.powerPercentFTP - SNAP_PCT, 0, 300); });
        afterChange(ids, true); renderInspector(); return;
      }
    });
  }

  // ── Init ────────────────────────────────────────────────────────────────
  function init() {
    hydrate();

    // If the URL carries a workout, load it instead of localStorage. Two
    // sources, query first: the documented `?s=…` third-party URL API (e.g.
    // VertMatch hands off a generated workout), then the binary `#wz=`/`#w=`
    // hash blob (the "Open in editor" handoff from the community browser).
    //
    // We load it immediately — no blocking confirm() — and protect against an
    // accidental clobber with an Undo toast that snapshots whatever the user
    // had. Undo beats a modal: it's non-blocking and reversible. The URL is
    // cleared so a refresh / share doesn't reload-and-clobber a second time.
    const incoming = readWorkoutFromQuery() ||
      ((typeof readWorkoutFromHash === 'function') ? readWorkoutFromHash() : null);
    if (incoming) {
      const hadExistingWork = state.segments.length > 0 || !!state.name || !!state.description;
      const snapshot = hadExistingWork ? captureState() : null;
      try { history.replaceState(null, '', location.pathname); } catch (_) {}

      state.name = incoming.name || '';
      state.description = incoming.description || '';
      state.tags = Array.isArray(incoming.tags) ? incoming.tags : [];
      state.segments = incoming.segments
        .filter(s => s && Number.isFinite(s.lengthInSeconds) && Number.isFinite(s.powerPercentFTP))
        .map(s => ({
          id: nextId(),
          lengthInSeconds: clamp(parseInt(s.lengthInSeconds, 10), 0, 60 * 60 * 4),
          powerPercentFTP: clamp(parseInt(s.powerPercentFTP, 10), 0, 300),
        }));
      state.selectedIds = new Set();
      if (Number.isFinite(incoming._ftp)) { ftp = incoming._ftp; persistFtp(); }
      persist();

      // Defer the toast until after init() has wired up the DOM/renderers, so
      // an Undo tap can repaint cleanly.
      const loadedName = incoming.name || 'workout';
      if (snapshot) {
        pendingLoadToast = () => toast(`Loaded "${loadedName}" — replaced your previous workout`, false, {
          label: 'Undo', duration: 12000, onClick: () => restoreState(snapshot),
        });
      } else {
        pendingLoadToast = () => toast(`Loaded "${loadedName}"`);
      }
    }

    if (state.segments.length === 0 && !state.name && !state.description) {
      state.name = 'My workout';
      state.segments = [
        { id: nextId(), lengthInSeconds: 5 * 60, powerPercentFTP: 50 },
        { id: nextId(), lengthInSeconds: 5 * 60, powerPercentFTP: 65 },
        { id: nextId(), lengthInSeconds: 8 * 60, powerPercentFTP: 100 },
        { id: nextId(), lengthInSeconds: 5 * 60, powerPercentFTP: 55 },
        { id: nextId(), lengthInSeconds: 5 * 60, powerPercentFTP: 50 },
      ];
    }

    $('#ftp-input').value = String(ftp);
    $('#workout-name').value = state.name;
    $('#workout-description').value = state.description;

    $('#ftp-input').addEventListener('input', (e) => {
      const v = parseInt(e.target.value, 10);
      if (Number.isFinite(v) && v >= 50 && v <= 600) {
        ftp = v; persistFtp(); renderStats(); renderInspector();
      }
    });
    $('#workout-name').addEventListener('input', (e) => { state.name = e.target.value; persist(); });
    $('#workout-description').addEventListener('input', (e) => { state.description = e.target.value; persist(); });

    $('#open-set-builder').addEventListener('click', () => openModal('#set-modal'));
    $('#set-add').addEventListener('click', addIntervalSet);

    $('#btn-download').addEventListener('click', downloadJson);
    $('#btn-share').addEventListener('click', shareToPhone);
    $('#btn-copy').addEventListener('click', copyJson);
    $('#btn-qr').addEventListener('click', showQrModal);
    $('#btn-load').addEventListener('click', () => $('#file-input').click());
    $('#file-input').addEventListener('change', (e) => {
      const f = e.target.files && e.target.files[0];
      if (f) handleLoadFile(f);
      e.target.value = '';
    });
    $('#btn-reset').addEventListener('click', resetAll);
    $('#qr-copy-link').addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText($('#qr-link').value);
        toast('Link copied');
      } catch (_) {
        $('#qr-link').select(); document.execCommand('copy'); toast('Link copied');
      }
    });

    try {
      const probe = new File(['{}'], 'probe.json', { type: 'application/json' });
      if (navigator.canShare && navigator.canShare({ files: [probe] })) {
        $('#btn-share').hidden = false;
      }
    } catch (_) {}

    bindModalCloses();
    bindKeyboard();
    renderPalette();
    renderTags();
    setupTimelineDropTarget();
    afterChange();
    window.addEventListener('resize', () => { renderClips(); });

    // Now that everything is rendered, surface the "loaded from URL" toast
    // (with its Undo action, if we replaced existing work).
    if (pendingLoadToast) { pendingLoadToast(); pendingLoadToast = null; }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
