/* ============================================================
   Indoor Bike - route library (shared by the community browser
   and the route publishing tool)

   This is a JavaScript port of the Kotlin that the app runs:

     shared/.../route/RouteTrack.kt      geometry and smoothing
     shared/.../route/RouteStats.kt      the published numbers
     shared/.../route/RouteEstimate.kt   duration for a rider
     shared/.../route/RoutePolyline.kt   the shape encoding
     shared/.../repo/RouteManifests.kt   the wire format

   The constants below are copies, and they have to stay copies.
   A maintainer publishes numbers generated here and a rider sees
   the route those numbers described; if the two implementations
   drift, a card promises one climb and the trainer delivers
   another. When the Kotlin changes, change this with it and bump
   ROUTE_SCHEMA_VERSION on both sides.
   ============================================================ */

(function (global) {
  'use strict';

  // ── Format version ─────────────────────────────────────────────────────
  const ROUTE_SCHEMA_VERSION = 1;
  const ROUTE_BUNDLE_SCHEMA_VERSION = 1;
  const ROUTE_PROFILE_SAMPLES = 64;
  const ROUTE_SHAPE_POINTS = 160;

  /**
   * Smallest elevation range a profile is drawn against, in metres. Mirrored
   * in the app as RoutePreview.PROFILE_MIN_SPAN_METERS. Without a floor a
   * sparkline autoscales, and four metres of drift over twenty kilometres
   * draws as a mountain range - the flattest route in a list ends up looking
   * like the hilliest.
   */
  const PROFILE_MIN_SPAN_METERS = 30;

  // ── Geometry constants (RouteTrack.kt) ─────────────────────────────────
  const GRADIENT_SAMPLE_METERS = 10.0;
  const GRADIENT_WINDOW_METERS = 40.0;
  const MAX_GRADIENT = 0.30;
  const LOOP_CLOSE_METERS = 35.0;
  const MIN_POINT_SPACING_METERS = 1.0;
  const SMOOTHING_HALF_WINDOW = 5;

  // ── Physics constants (VirtualSpeed.kt / RideDynamics.kt) ──────────────
  const GRAVITY_MS2 = 9.8067;
  const AIR_DENSITY_KG_M3 = 1.225;
  const CRR = 0.005;
  const CDA_M2 = 0.36;
  const DRIVETRAIN_EFFICIENCY = 0.97;
  const MAX_DESCENT_SPEED_MPS = 22.0;
  const MIN_ESTIMATE_SPEED_MPS = 1.1;
  const DEFAULT_BIKE_MASS_KG = 9.0;

  /** Share of FTP the duration estimate assumes. See RouteEstimate.kt. */
  const ESTIMATE_FTP_FRACTION = 0.65;

  // ── Gradient buckets (RouteGradientBuckets) ────────────────────────────
  const BUCKET_EDGES_PERCENT = [
    -100, -10, -7, -4, -2, 0, 2, 4, 6, 8, 10, 12, 15, 20, 100,
  ];
  const BUCKET_CENTRE_PERCENT = [
    -14, -8.5, -5.5, -3, -1, 1, 3, 5, 7, 9, 11, 13.5, 17.5, 24,
  ];
  const BUCKET_COUNT = BUCKET_CENTRE_PERCENT.length;

  function bucketIndex(gradientPercent) {
    for (let i = 0; i < BUCKET_COUNT; i++) {
      if (gradientPercent < BUCKET_EDGES_PERCENT[i + 1]) return i;
    }
    return BUCKET_COUNT - 1;
  }

  // ── Distance ───────────────────────────────────────────────────────────
  const EARTH_RADIUS_M = 6371008.8;

  function haversineMeters(lat1, lon1, lat2, lon2) {
    const toRad = Math.PI / 180;
    const dLat = (lat2 - lat1) * toRad;
    const dLon = (lon2 - lon1) * toRad;
    const a = Math.sin(dLat / 2) ** 2 +
      Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  // ── GPX parsing ────────────────────────────────────────────────────────
  /**
   * Pull the raw points out of a GPX. Track points first; a file with only a
   * route (<rtept>) or only waypoints still describes a road, and refusing it
   * would turn a planner export into an error the maintainer cannot act on.
   *
   * Returns { points, name, description, hasElevation }.
   */
  function parseGpx(xmlText) {
    const doc = new DOMParser().parseFromString(xmlText, 'application/xml');
    if (doc.querySelector('parsererror')) {
      throw new Error('That file is not valid XML.');
    }
    let nodes = Array.from(doc.getElementsByTagName('trkpt'));
    if (nodes.length === 0) nodes = Array.from(doc.getElementsByTagName('rtept'));
    if (nodes.length === 0) nodes = Array.from(doc.getElementsByTagName('wpt'));
    if (nodes.length < 2) {
      throw new Error('No track points found - a route needs at least two.');
    }

    let hasElevation = false;
    const points = [];
    for (const node of nodes) {
      const lat = parseFloat(node.getAttribute('lat'));
      const lon = parseFloat(node.getAttribute('lon'));
      if (!isFinite(lat) || !isFinite(lon)) continue;
      const eleNode = node.getElementsByTagName('ele')[0];
      const ele = eleNode ? parseFloat(eleNode.textContent) : NaN;
      if (isFinite(ele)) hasElevation = true;
      points.push({ lat, lon, ele: isFinite(ele) ? ele : 0 });
    }
    if (points.length < 2) throw new Error('No usable track points found.');

    const meta = doc.getElementsByTagName('metadata')[0];
    const trk = doc.getElementsByTagName('trk')[0];
    const pick = (parent, tag) => {
      if (!parent) return '';
      const node = Array.from(parent.children).find((c) => c.tagName === tag);
      return node ? (node.textContent || '').trim() : '';
    };
    const name = pick(trk, 'name') || pick(meta, 'name') || '';
    const description = pick(trk, 'desc') || pick(meta, 'desc') || '';

    return { points, name, description, hasElevation };
  }

  // ── Track building (mirror of routeTrackOf) ────────────────────────────
  /**
   * Turn raw points into the same smoothed track the app builds: cumulative
   * distance, elevation resampled onto a fixed grid, smoothed, and
   * differentiated into gradient.
   *
   * The smoothing is not cosmetic. Consumer GPS altitude wanders by metres
   * between points a few metres apart, and differentiating that noise invents
   * gradients no road has. Publishing numbers measured on the raw points would
   * describe a route nobody is ever made to ride.
   */
  function buildTrack(rawPoints) {
    const kept = [];
    let cumulative = 0;
    for (const p of rawPoints) {
      const previous = kept[kept.length - 1];
      if (!previous) {
        kept.push({ lat: p.lat, lon: p.lon, ele: p.ele, distance: 0 });
        continue;
      }
      const step = haversineMeters(previous.lat, previous.lon, p.lat, p.lon);
      if (step < MIN_POINT_SPACING_METERS) continue;
      cumulative += step;
      kept.push({ lat: p.lat, lon: p.lon, ele: p.ele, distance: cumulative });
    }
    if (kept.length < 2) throw new Error('Points are all in the same place.');

    const total = kept[kept.length - 1].distance;
    const sampleCount = Math.floor(total / GRADIENT_SAMPLE_METERS) + 1;
    const elevation = resampleElevation(kept, sampleCount);
    smoothInPlace(elevation, SMOOTHING_HALF_WINDOW);
    const gradient = differentiate(elevation);

    const lats = kept.map((p) => p.lat);
    const lons = kept.map((p) => p.lon);

    return {
      points: kept,
      totalDistanceMeters: total,
      elevationProfile: elevation,
      gradientProfile: gradient,
      bounds: {
        minLat: Math.min(...lats), maxLat: Math.max(...lats),
        minLon: Math.min(...lons), maxLon: Math.max(...lons),
      },
      isLoop: kept.length > 2 && haversineMeters(
        kept[0].lat, kept[0].lon,
        kept[kept.length - 1].lat, kept[kept.length - 1].lon
      ) <= LOOP_CLOSE_METERS,
    };
  }

  function resampleElevation(points, sampleCount) {
    const out = new Float64Array(Math.max(sampleCount, 1));
    let cursor = 0;
    for (let i = 0; i < out.length; i++) {
      const d = i * GRADIENT_SAMPLE_METERS;
      while (cursor < points.length - 2 && points[cursor + 1].distance < d) cursor++;
      const a = points[cursor];
      const b = points[cursor + 1] || a;
      const span = b.distance - a.distance;
      const t = span > 0 ? Math.min(1, Math.max(0, (d - a.distance) / span)) : 0;
      out[i] = a.ele + (b.ele - a.ele) * t;
    }
    return out;
  }

  function smoothInPlace(values, halfWindow) {
    if (values.length < 3 || halfWindow < 1) return;
    const source = Float64Array.from(values);
    for (let i = 0; i < values.length; i++) {
      let sum = 0, n = 0;
      for (let k = i - halfWindow; k <= i + halfWindow; k++) {
        if (k >= 0 && k < source.length) { sum += source[k]; n++; }
      }
      values[i] = sum / n;
    }
  }

  function differentiate(elevation) {
    const out = new Float64Array(elevation.length);
    if (elevation.length < 2) return out;
    const half = Math.max(1, Math.round(GRADIENT_WINDOW_METERS / GRADIENT_SAMPLE_METERS / 2));
    for (let i = 0; i < elevation.length; i++) {
      const lo = Math.min(Math.max(i - half, 0), elevation.length - 1);
      const hi = Math.min(Math.max(i + half, 0), elevation.length - 1);
      const run = (hi - lo) * GRADIENT_SAMPLE_METERS;
      const raw = run > 0 ? (elevation[hi] - elevation[lo]) / run : 0;
      out[i] = Math.min(MAX_GRADIENT, Math.max(-MAX_GRADIENT, raw));
    }
    return out;
  }

  // ── Sampling helpers ───────────────────────────────────────────────────
  function sampleProfile(profile, track, distanceMeters) {
    if (profile.length === 0) return 0;
    if (profile.length === 1) return profile[0];
    const d = Math.min(Math.max(distanceMeters, 0), track.totalDistanceMeters);
    const exact = d / GRADIENT_SAMPLE_METERS;
    const i = Math.min(Math.max(Math.floor(exact), 0), profile.length - 1);
    const j = Math.min(i + 1, profile.length - 1);
    const t = Math.min(1, Math.max(0, exact - i));
    return profile[i] + (profile[j] - profile[i]) * t;
  }

  const elevationAt = (track, d) => sampleProfile(track.elevationProfile, track, d);
  const gradientAt = (track, d) => sampleProfile(track.gradientProfile, track, d);

  /** Interpolated lat/lon at a distance along the track. */
  function positionAt(track, distanceMeters) {
    const points = track.points;
    const d = Math.min(Math.max(distanceMeters, 0), track.totalDistanceMeters);
    let lo = 0, hi = points.length - 1;
    while (lo < hi) {
      const mid = Math.ceil((lo + hi) / 2);
      if (points[mid].distance <= d) lo = mid; else hi = mid - 1;
    }
    const a = points[lo];
    const b = points[lo + 1] || a;
    const span = b.distance - a.distance;
    const t = span > 0 ? Math.min(1, Math.max(0, (d - a.distance) / span)) : 0;
    return {
      lat: a.lat + (b.lat - a.lat) * t,
      lon: a.lon + (b.lon - a.lon) * t,
    };
  }

  // ── Published statistics (mirror of RouteStats) ────────────────────────
  function ascentDescent(track) {
    let gain = 0, loss = 0;
    const e = track.elevationProfile;
    for (let i = 1; i < e.length; i++) {
      const delta = e[i] - e[i - 1];
      if (delta > 0) gain += delta; else loss -= delta;
    }
    return { ascent: gain, descent: loss };
  }

  function gradientHistogram(track) {
    const buckets = new Array(BUCKET_COUNT).fill(0);
    const total = track.totalDistanceMeters;
    if (total <= 0) return buckets;
    for (let d = 0; d < total; d += GRADIENT_SAMPLE_METERS) {
      const span = (d + GRADIENT_SAMPLE_METERS > total) ? total - d : GRADIENT_SAMPLE_METERS;
      const percent = gradientAt(track, d + span / 2) * 100;
      buckets[bucketIndex(percent)] += span;
    }
    return buckets;
  }

  function maxGradientPercent(track) {
    let steepest = 0;
    for (let d = 0; d < track.totalDistanceMeters; d += GRADIENT_SAMPLE_METERS) {
      const percent = gradientAt(track, d) * 100;
      if (Math.abs(percent) > Math.abs(steepest)) steepest = percent;
    }
    return steepest;
  }

  function elevationSamples(track, count) {
    const n = Math.max(2, count);
    const step = track.totalDistanceMeters / (n - 1);
    const out = [];
    for (let i = 0; i < n; i++) out.push(elevationAt(track, i * step));
    return out;
  }

  function shapeOf(track) {
    const count = Math.min(ROUTE_SHAPE_POINTS, track.points.length);
    if (count < 2) return '';
    const step = track.totalDistanceMeters / (count - 1);
    const pts = [];
    for (let i = 0; i < count; i++) {
      const p = positionAt(track, i * step);
      pts.push([p.lat, p.lon]);
    }
    return encodePolyline(pts);
  }

  // ── Polyline (mirror of RoutePolyline) ─────────────────────────────────
  function encodePolyline(points) {
    let out = '';
    let prevLat = 0, prevLon = 0;
    for (const [lat, lon] of points) {
      const la = Math.round(lat * 1e5);
      const lo = Math.round(lon * 1e5);
      out += encodeValue(la - prevLat) + encodeValue(lo - prevLon);
      prevLat = la;
      prevLon = lo;
    }
    return out;
  }

  function encodeValue(value) {
    // Zig-zag so small negatives stay short, then five bits at a time.
    let v = value < 0 ? ~(value << 1) : (value << 1);
    let out = '';
    while (v >= 0x20) {
      out += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
      v >>= 5;
    }
    out += String.fromCharCode(v + 63);
    return out;
  }

  function decodePolyline(encoded) {
    if (!encoded) return [];
    const out = [];
    let index = 0, lat = 0, lon = 0;
    while (index < encoded.length) {
      let shift = 0, result = 0, byte;
      do {
        if (index >= encoded.length) return out;
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 0x1f) << shift;
        shift += 5;
      } while (byte >= 0x20);
      lat += (result & 1) ? ~(result >> 1) : (result >> 1);

      shift = 0; result = 0;
      do {
        if (index >= encoded.length) return out;
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 0x1f) << shift;
        shift += 5;
      } while (byte >= 0x20);
      lon += (result & 1) ? ~(result >> 1) : (result >> 1);

      out.push([lat / 1e5, lon / 1e5]);
    }
    return out;
  }

  // ── Duration estimate (mirror of RouteEstimate) ────────────────────────
  /**
   * Steady-state speed on a gradient: the fixed point where drive force
   * balances gravity, rolling resistance and drag. Newton on a convex cubic,
   * seeded above the root so the iteration cannot overshoot into the region
   * where the cubic turns back on itself.
   */
  function steadySpeedMps(powerWatts, gradient, totalMassKg) {
    if (totalMassKg <= 0) return 0;
    const hyp = Math.sqrt(1 + gradient * gradient);
    const sinTheta = gradient / hyp;
    const cosTheta = 1 / hyp;

    const drag = 0.5 * AIR_DENSITY_KG_M3 * CDA_M2;
    const resistance = totalMassKg * GRAVITY_MS2 * (sinTheta + CRR * cosTheta);
    const wheelWatts = powerWatts * DRIVETRAIN_EFFICIENCY;
    if (wheelWatts <= 0 && resistance >= 0) return 0;

    let v = Math.cbrt(Math.max(wheelWatts, 0) / drag) +
      Math.sqrt(Math.max(-resistance, 0) / drag);
    if (v <= 0) return 0;

    for (let i = 0; i < 24; i++) {
      const f = drag * v * v * v + resistance * v - wheelWatts;
      const slope = 3 * drag * v * v + resistance;
      if (slope <= 0) break;
      const step = f / slope;
      v -= step;
      if (Math.abs(step) < 1e-7) break;
    }
    return Math.min(MAX_DESCENT_SPEED_MPS, Math.max(MIN_ESTIMATE_SPEED_MPS, v));
  }

  /**
   * Seconds to complete an entry for a given rider. Null when there is not
   * enough to answer honestly - a duration built on a guessed rider is worse
   * than no duration.
   */
  function estimateSeconds(entry, ftpWatts, riderKg, bikeKg) {
    if (!(ftpWatts > 0) || !(riderKg > 0) || !(entry.distanceMeters > 0)) return null;
    const power = ftpWatts * ESTIMATE_FTP_FRACTION;
    const mass = riderKg + (bikeKg > 0 ? bikeKg : DEFAULT_BIKE_MASS_KG);

    const histogram = entry.gradientMeters;
    if (Array.isArray(histogram) && histogram.length === BUCKET_COUNT) {
      let seconds = 0;
      for (let i = 0; i < BUCKET_COUNT; i++) {
        const metres = histogram[i];
        if (!(metres > 0)) continue;
        const speed = steadySpeedMps(power, BUCKET_CENTRE_PERCENT[i] / 100, mass);
        if (speed > 0) seconds += metres / speed;
      }
      return seconds > 0 ? Math.round(seconds) : null;
    }
    const flat = steadySpeedMps(power, 0, mass);
    return flat > 0 ? Math.round(entry.distanceMeters / flat) : null;
  }

  // ── Entry generation ───────────────────────────────────────────────────
  const round1 = (v) => Math.round(v * 10) / 10;
  const round5 = (v) => Math.round(v * 1e5) / 1e5;

  /**
   * Build the JSON object a maintainer publishes. `meta` supplies what the
   * GPX cannot know: the id, where the file will be hosted, and who to credit.
   */
  function buildEntry(track, meta) {
    const { ascent, descent } = ascentDescent(track);
    const elevations = Array.from(track.elevationProfile);
    const entry = {
      schemaVersion: ROUTE_SCHEMA_VERSION,
      id: meta.id,
      name: meta.name,
    };
    if (meta.description) entry.description = meta.description;
    if (meta.tags && meta.tags.length) entry.tags = meta.tags;
    if (meta.place) entry.place = meta.place;
    entry.version = meta.version || 1;
    entry.gpxUrl = meta.gpxUrl;
    if (meta.gpxBytes) entry.gpxBytes = meta.gpxBytes;
    if (meta.gpxSha256) entry.gpxSha256 = meta.gpxSha256;

    entry.distanceMeters = round1(track.totalDistanceMeters);
    entry.ascentMeters = round1(ascent);
    entry.descentMeters = round1(descent);
    entry.minElevationMeters = round1(Math.min(...elevations));
    entry.maxElevationMeters = round1(Math.max(...elevations));
    entry.maxGradientPercent = round1(maxGradientPercent(track));
    entry.loop = track.isLoop;
    entry.gradientMeters = gradientHistogram(track).map(round1);
    entry.profile = elevationSamples(track, ROUTE_PROFILE_SAMPLES).map(round1);
    entry.shape = shapeOf(track);
    entry.bounds = [
      round5(track.bounds.minLat), round5(track.bounds.minLon),
      round5(track.bounds.maxLat), round5(track.bounds.maxLon),
    ];
    if (meta.attribution) entry.attribution = meta.attribution;
    return entry;
  }

  // ── Formatting ─────────────────────────────────────────────────────────
  function formatDistance(meters) {
    const km = meters / 1000;
    return km >= 100 ? `${Math.round(km)} km` : `${Math.round(km * 10) / 10} km`;
  }

  function formatDuration(seconds) {
    if (seconds == null) return '--';
    const mins = Math.round(seconds / 60);
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m === 0 ? `${h} h` : `${h} h ${m} min`;
  }

  /** SHA-256 as lowercase hex, matching what the app checks a download against. */
  async function sha256Hex(arrayBuffer) {
    const digest = await crypto.subtle.digest('SHA-256', arrayBuffer);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }

  global.RouteLib = {
    ROUTE_SCHEMA_VERSION,
    ROUTE_BUNDLE_SCHEMA_VERSION,
    ROUTE_PROFILE_SAMPLES,
    PROFILE_MIN_SPAN_METERS,
    ESTIMATE_FTP_FRACTION,
    BUCKET_EDGES_PERCENT,
    BUCKET_CENTRE_PERCENT,
    BUCKET_COUNT,
    DEFAULT_BIKE_MASS_KG,
    parseGpx,
    buildTrack,
    buildEntry,
    gradientHistogram,
    elevationSamples,
    encodePolyline,
    decodePolyline,
    steadySpeedMps,
    estimateSeconds,
    haversineMeters,
    formatDistance,
    formatDuration,
    sha256Hex,
  };
})(window);
