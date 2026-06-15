/* ============================================================
   indoorbike.app/share/ - landing page that decodes a workout
   from a hash-fragment URL and lets the user save it or hand
   it off to the Indoor Bike app via the OS share sheet.
   ============================================================ */

(function () {
  'use strict';

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

  function fmtClock(s) {
    s = Math.max(0, Math.round(s));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const ss = s % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
    return `${m}:${String(ss).padStart(2, '0')}`;
  }

  function b64urlDecodeBytes(str) {
    const padded = str.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((str.length + 3) % 4);
    const bin = atob(padded);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }
  function b64urlDecode(str) {
    return new TextDecoder().decode(b64urlDecodeBytes(str));
  }

  /**
   * Two payload forms are accepted:
   *   `#wz=…` - gzip + base64url (current QR / link form, much shorter URL)
   *   `#w=…`  - plain base64url JSON (legacy, still supported)
   */
  function readWorkoutFromHash() {
    const hash = location.hash || '';
    const gz = hash.match(/[#&]wz=([^&]+)/);
    if (gz && typeof pako !== 'undefined' && pako.ungzip) {
      try {
        const bytes = b64urlDecodeBytes(decodeURIComponent(gz[1]));
        const json = pako.ungzip(bytes, { to: 'string' });
        const obj = JSON.parse(json);
        if (obj && Array.isArray(obj.segments)) return obj;
      } catch (e) { /* fall through to plain form */ }
    }
    const plain = hash.match(/[#&]w=([^&]+)/);
    if (plain) {
      try {
        const json = b64urlDecode(decodeURIComponent(plain[1]));
        const obj = JSON.parse(json);
        if (obj && Array.isArray(obj.segments)) return obj;
      } catch (e) { /* fall through */ }
    }
    return null;
  }

  function el(tag, props = {}, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'class') n.className = v;
      else if (k === 'style') Object.assign(n.style, v);
      else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v !== undefined && v !== null) n.setAttribute(k, v);
    }
    for (const c of kids.flat()) {
      if (c == null) continue;
      n.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return n;
  }

  function buildBar(workout) {
    const total = workout.segments.reduce((a, s) => a + s.lengthInSeconds, 0) || 1;
    const bar = el('div', { class: 'share-bar' });
    for (const s of workout.segments) {
      const z = zoneFor(s.powerPercentFTP);
      bar.appendChild(el('div', {
        style: { background: ZONE_COLORS[z], flex: String(s.lengthInSeconds / total) }
      }));
    }
    return bar;
  }

  function downloadJson(workout) {
    const text = JSON.stringify(workout, null, 2);
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const safe = (workout.name || 'workout').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'workout';
    a.href = url;
    a.download = `${safe}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  async function shareToApp(workout) {
    const text = JSON.stringify(workout, null, 2);
    const safe = (workout.name || 'workout').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'workout';
    const file = new File([text], `${safe}.json`, { type: 'application/json' });
    try {
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: workout.name || 'Workout' });
      } else {
        // Fallback: trigger download
        downloadJson(workout);
      }
    } catch (e) {
      if (e && e.name !== 'AbortError') {
        downloadJson(workout);
      }
    }
  }

  function totalSeconds(w) { return w.segments.reduce((a, s) => a + s.lengthInSeconds, 0); }

  function avgPct(w) {
    const t = totalSeconds(w);
    if (t === 0) return 0;
    return Math.round(w.segments.reduce((a, s) => a + s.powerPercentFTP * s.lengthInSeconds, 0) / t);
  }

  function tss(w) {
    const t = totalSeconds(w);
    if (t === 0) return 0;
    let sum4 = 0;
    for (const s of w.segments) sum4 += Math.pow(s.powerPercentFTP, 4) * s.lengthInSeconds;
    const np = Math.pow(sum4 / t, 0.25);
    const intensity = np / 100;
    return Math.round((t / 3600) * intensity * intensity * 100);
  }

  function render(workout) {
    const root = document.getElementById('content');
    root.innerHTML = '';

    const card = el('div', { class: 'share-card' },
      el('h2', {}, workout.name || 'Untitled workout'),
      workout.description ? el('p', {}, workout.description) : null,
      buildBar(workout),
      el('div', { class: 'share-meta' },
        el('span', {}, `${fmtClock(totalSeconds(workout))} TOTAL`),
        el('span', {}, `${workout.segments.length} SEGMENTS`),
        el('span', {}, `AVG ${avgPct(workout)}% FTP`),
        el('span', {}, `TSS ${tss(workout)}`)
      ),
      el('div', { class: 'share-actions' },
        el('button', {
          class: 'btn btn-primary',
          type: 'button',
          onClick: () => shareToApp(workout)
        }, 'Send to Indoor Bike'),
        el('button', {
          class: 'btn btn-secondary',
          type: 'button',
          onClick: () => downloadJson(workout)
        }, 'Download .json')
      ),
      el('p', { style: { fontSize: '13px', color: 'var(--fg-3)', marginTop: 'var(--s-4)' } },
        'Tap "Send to Indoor Bike" and pick the app from the share sheet. ' +
        'On desktop, download and email/AirDrop the file to your phone.'
      )
    );
    root.appendChild(card);
  }

  function renderError(message) {
    const root = document.getElementById('content');
    root.innerHTML = '';
    root.appendChild(el('div', { class: 'share-card share-error' },
      el('h2', {}, 'No workout in this link'),
      el('p', {}, message)
    ));
  }

  function init() {
    const w = readWorkoutFromHash();
    if (!w) {
      renderError('This link is missing the workout data, or it was malformed. ' +
        'Open the editor at indoorbike.app/editor to build one.');
      return;
    }
    render(w);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
