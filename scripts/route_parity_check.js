#!/usr/bin/env node
/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Checks website/assets/routelib.js against the values the Kotlin asserts in
 * shared/.../route/RouteListingTest.kt.
 *
 *   node scripts/route_parity_check.js
 *
 * Why this exists. routelib.js is a JavaScript port of RouteTrack, RouteStats,
 * RouteEstimate and RoutePolyline, and the route publishing tool uses it to
 * generate the numbers a maintainer publishes. The app then shows a rider the
 * route those numbers described. If the two implementations drift, a card
 * promises one climb and the trainer delivers another, and nothing in either
 * codebase would notice on its own. Run this after touching either side.
 *
 * Exits 1 on any mismatch.
 */

// routelib attaches itself to a global called `window`.
global.window = global;
require('../website/assets/routelib.js');
const R = global.RouteLib;

let failed = 0;
const fail = (msg) => { failed++; console.error(`FAIL  ${msg}`); };
const ok = (msg) => console.log(`ok    ${msg}`);
const check = (pass, msg) => (pass ? ok(msg) : fail(msg));

/** The synthetic road the Kotlin test builds: straight north, 10 m spacing. */
function road(lengthMeters, elevationAt) {
  const points = [];
  for (let d = 0; d <= lengthMeters; d += 10) {
    points.push({ lat: 45.0 + d / 111320.0, lon: 5.0, ele: elevationAt(d) });
  }
  return R.buildTrack(points);
}

// The polyline has to be byte-identical, not merely round-trippable: the tool
// encodes in JavaScript and the app decodes in Kotlin, so a private dialect
// would pass every test on both sides and work nowhere.
check(
  R.encodePolyline([[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]]) ===
    '_p~iF~ps|U_ulLnnqC_mqNvxq`@',
  'polyline matches the reference encoding'
);

const climb = road(5000, (d) => d * 0.05);
const histTotal = R.gradientHistogram(climb).reduce((a, b) => a + b, 0);
check(
  Math.abs(histTotal - climb.totalDistanceMeters) < 1.0,
  `histogram accounts for the whole route (${histTotal.toFixed(1)} m)`
);

const steady = R.gradientHistogram(road(4000, (d) => d * 0.05));
const bucket5 = R.BUCKET_CENTRE_PERCENT.indexOf(5);
check(steady[bucket5] > 4000 * 0.9, `a steady climb lands in one bucket (${steady[bucket5].toFixed(0)} m)`);

// VirtualSpeed's documented calibration: 200 W at about 32 km/h for 75 kg.
// The Kotlin asserts equality with VirtualSpeed directly; this is the same
// number seen from the other side.
const flat = R.steadySpeedMps(200, 0, 75 + 9);
check(flat * 3.6 > 31 && flat * 3.6 < 33, `flat 200 W = ${(flat * 3.6).toFixed(2)} km/h`);

const up = R.steadySpeedMps(200, 0.08, 84);
const down = R.steadySpeedMps(200, -0.08, 84);
check(up < flat && down > flat, `climbing is slower and descending faster (${up.toFixed(2)} / ${down.toFixed(2)} m/s)`);

// The reason the histogram is in the format at all.
const meta = { id: 'x', name: 'X', gpxUrl: 'x.gpx' };
const flatSecs = R.estimateSeconds(R.buildEntry(road(8000, () => 100), meta), 250, 75, 9);
const rollSecs = R.estimateSeconds(
  R.buildEntry(road(8000, (d) => 100 + 40 * Math.sin(d / 400)), meta), 250, 75, 9);
check(rollSecs > flatSecs, `a hilly route takes longer than a flat one (${rollSecs}s vs ${flatSecs}s over 8 km)`);

const entry = R.buildEntry(road(3000, (d) => d * 0.04), {
  id: 'test-climb', name: 'Test Climb', gpxUrl: 'routes/test-climb.gpx',
});
check(entry.schemaVersion === R.ROUTE_SCHEMA_VERSION, `entry declares schemaVersion ${entry.schemaVersion}`);
check(entry.gradientMeters.length === R.BUCKET_COUNT, `gradientMeters has ${R.BUCKET_COUNT} buckets`);
check(entry.profile.length === R.ROUTE_PROFILE_SAMPLES, `profile has ${R.ROUTE_PROFILE_SAMPLES} samples`);
check(Array.isArray(entry.bounds) && entry.bounds.length === 4, 'bounds is four numbers');
check(entry.ascentMeters > 100, `a 4 % 3 km climb gains ${entry.ascentMeters} m`);
check(Math.abs(entry.maxGradientPercent - 4.0) < 1.0, `max gradient ${entry.maxGradientPercent} % is about 4 %`);

const decoded = R.decodePolyline(entry.shape);
check(
  decoded.length > 2 && Math.abs(decoded[0][0] - 45.0) < 1e-4,
  `the published shape decodes back (${decoded.length} points)`
);

check(
  R.estimateSeconds(entry, 200, 0, 9) === null && R.estimateSeconds(entry, 0, 75, 9) === null,
  'no estimate without both FTP and weight'
);

console.log(failed ? `\n${failed} mismatch(es).` : '\nAll parity checks passed.');
process.exit(failed ? 1 : 0);
