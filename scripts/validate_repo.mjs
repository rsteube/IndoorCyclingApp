#!/usr/bin/env node
/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
 * Validates a community workout repository the way both consumers see it:
 * the Android app (RepositoryManager.fetchRepoWorkouts) and the website
 * browser (website/community/community.js). Run it before merging a registry
 * pull request, or as a contributor before opening one.
 *
 *   node scripts/validate_repo.mjs https://example.org/manifest.json
 *   node scripts/validate_repo.mjs --registry          # every entry in website/registry.json
 *   node scripts/validate_repo.mjs --registry path/to/registry.json
 *
 * Exits 1 if any ERROR was found; warnings alone still exit 0.
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// Mirrors of the constants in shared/.../repo/Manifests.kt. Keep in sync.
const MAX_MANIFEST_SCHEMA = 2;
const MAX_BUNDLE_SCHEMA = 1;
const MAX_WORKOUT_SCHEMA = 2;

// Segment bounds as documented in website/community/contribute.html.
const MAX_SEGMENT_SECONDS = 14400;
const MAX_POWER_PCT = 300;
const CADENCE_MIN_RPM = 20;
const CADENCE_MAX_RPM = 200;

// ── Reporting ────────────────────────────────────────────────────────────

let errors = 0;
let warnings = 0;

const err = (msg) => { errors++; console.log(`  ERROR  ${msg}`); };
const warn = (msg) => { warnings++; console.log(`  WARN   ${msg}`); };
const ok = (msg) => console.log(`  ok     ${msg}`);
const section = (msg) => console.log(`\n${msg}`);

// ── Ids already in use, so a new repo doesn't collide ────────────────────

/**
 * Workout ids the rider may already have: the app's bundled sample pack plus
 * the starter bundle mirrored on the website. A collision matters because the
 * library dedup rule (Imported > Bundled, first-wins) may not pick the copy
 * the rider expected.
 */
function loadReservedWorkoutIds() {
  const ids = new Map(); // id → {label, ownerRepoId}
  // `ownerRepoId` keeps a repo from colliding with itself: the starter pack is
  // deliberately the same content as the app's bundled sample set, and both
  // are published as the `indoorbike-starter` repo.
  const sources = [
    ['bundled sample pack', 'indoorbike-starter', 'mobile_clients/android/src/main/assets/workouts_sample.json'],
    ['starter pack', 'indoorbike-starter', 'website/community/indoorbike-starter/main.json'],
  ];
  for (const [label, ownerRepoId, rel] of sources) {
    try {
      const raw = JSON.parse(readFileSync(join(REPO_ROOT, rel), 'utf8'));
      const list = Array.isArray(raw) ? raw : (raw.workouts ?? []);
      for (const w of list) if (w?.id) ids.set(w.id, { label, ownerRepoId });
    } catch {
      // Running outside a checkout is fine; we just skip the collision check.
    }
  }
  return ids;
}

// ── Fetching ─────────────────────────────────────────────────────────────

/**
 * Fetch with an Origin header so the response's CORS headers reflect what a
 * browser on indoorbike.app would actually get. Node's fetch doesn't enforce
 * CORS itself, which is exactly why we inspect the header by hand.
 */
async function fetchJson(url) {
  const res = await fetch(url, { headers: { Origin: 'https://indoorbike.app' } });
  const text = await res.text();
  return { res, text };
}

function resolveBundleUrl(bundleUrl, manifestUrl) {
  if (/^https?:\/\//i.test(bundleUrl)) return bundleUrl;
  const baseEnd = manifestUrl.lastIndexOf('/');
  return manifestUrl.substring(0, baseEnd + 1) + bundleUrl;
}

// ── Checks ───────────────────────────────────────────────────────────────

function checkTransport(url, res) {
  if (!url.startsWith('https://')) {
    err(`${url} is not https. The app rejects plain http on import.`);
  }
  if (!res.ok) {
    err(`HTTP ${res.status} fetching ${url}`);
    return false;
  }
  return true;
}

/**
 * The check that catches the most common self-hosting mistake. Opening the
 * URL in a browser tab is same-origin and passes even with no CORS at all, so
 * contributors reasonably believe their host is fine. Only a cross-origin
 * fetch (the website browser's) fails, and it fails silently from their side.
 */
function checkCors(url, res) {
  // Same-origin fetches never involve CORS, so a repo hosted on indoorbike.app
  // itself (the starter pack) needs no header.
  if (new URL(url).host === 'indoorbike.app') {
    ok('CORS: not applicable (same origin as the community browser)');
    return;
  }
  const allow = res.headers.get('access-control-allow-origin');
  if (!allow) {
    err(
      `No Access-Control-Allow-Origin on ${url}. Works in the Android app, ` +
      `but the website community browser cannot read it.`
    );
    return;
  }
  if (allow !== '*' && !allow.includes('indoorbike.app')) {
    err(`Access-Control-Allow-Origin is "${allow}", which excludes indoorbike.app.`);
    return;
  }
  ok(`CORS: ${allow}`);
}

/**
 * updatedEpoch is milliseconds. Contributors routinely paste seconds from an
 * online converter, which renders as "updated 56 years ago" on the repo card.
 * A seconds value for any plausible date is 10 digits; ms is 13.
 */
function checkUpdatedEpoch(epoch) {
  if (epoch == null) return;
  if (!Number.isFinite(epoch) || epoch <= 0) {
    err(`updatedEpoch must be a positive integer, got ${JSON.stringify(epoch)}.`);
    return;
  }
  if (epoch < 1e11) {
    const asMs = new Date(epoch * 1000).toISOString().slice(0, 10);
    err(
      `updatedEpoch ${epoch} looks like seconds, not milliseconds. ` +
      `Use ${epoch * 1000} (${asMs}).`
    );
    return;
  }
  const now = Date.now();
  if (epoch > now + 86_400_000) {
    warn(`updatedEpoch ${epoch} is in the future (${new Date(epoch).toISOString().slice(0, 10)}).`);
    return;
  }
  ok(`updatedEpoch ${new Date(epoch).toISOString().slice(0, 10)}`);
}

function checkManifestShape(m) {
  const schema = m.schemaVersion ?? 1;
  if (schema > MAX_MANIFEST_SCHEMA) {
    err(`Manifest schemaVersion ${schema} exceeds the supported ${MAX_MANIFEST_SCHEMA}. Nothing can read it.`);
  } else if (schema < 2) {
    err(`Manifest schemaVersion ${schema} is the legacy inline format. v2 (bundles) is required.`);
  }
  for (const field of ['id', 'name']) {
    if (typeof m[field] !== 'string' || !m[field].trim()) {
      err(`Manifest is missing a non-empty "${field}".`);
    }
  }
  if (m.id && !/^[a-z0-9][a-z0-9-]*$/.test(m.id)) {
    warn(`Manifest id "${m.id}" is not lowercase kebab-case. It is used as a cache key.`);
  }
  if (!Array.isArray(m.bundles) || m.bundles.length === 0) {
    err('Manifest has no bundles. Riders would see an empty repository.');
  }
  if (m.homepage && !m.homepage.startsWith('https://')) {
    warn(`homepage "${m.homepage}" is not https.`);
  }
  checkUpdatedEpoch(m.updatedEpoch);
}

function checkSegments(w, label) {
  if (!Array.isArray(w.segments) || w.segments.length === 0) {
    err(`${label}: no segments.`);
    return 0;
  }
  let total = 0;
  const workoutSchema = w.schemaVersion ?? 1;
  // Style warnings are counted rather than printed per segment: an interval
  // session legitimately repeats the same shape 20 times, and one line each
  // buries the genuine errors.
  const brief = [];   // segments too short to reach target in ERG
  const spicy = [];   // segments above sprint territory
  w.segments.forEach((s, i) => {
    const at = `${label} segment ${i + 1}`;
    const secs = s.lengthInSeconds;
    if (!Number.isInteger(secs) || secs < 1 || secs > MAX_SEGMENT_SECONDS) {
      err(`${at}: lengthInSeconds must be an integer 1-${MAX_SEGMENT_SECONDS}, got ${JSON.stringify(secs)}.`);
    } else {
      total += secs;
      if (secs < 10) brief.push(secs);
    }
    const pct = s.powerPercentFTP;
    if (typeof pct !== 'number' || pct < 0 || pct > MAX_POWER_PCT) {
      err(`${at}: powerPercentFTP must be 0-${MAX_POWER_PCT}, got ${JSON.stringify(pct)}.`);
    } else if (pct > 150) {
      spicy.push(pct);
    }
    if (s.intervalType !== undefined && s.intervalType !== 'CONSTANT') {
      err(`${at}: intervalType must be "CONSTANT", got ${JSON.stringify(s.intervalType)}.`);
    }
    for (const key of ['cadenceMinRpm', 'cadenceMaxRpm']) {
      if (s[key] === undefined) continue;
      if (!Number.isInteger(s[key]) || s[key] < CADENCE_MIN_RPM || s[key] > CADENCE_MAX_RPM) {
        err(`${at}: ${key} must be an integer ${CADENCE_MIN_RPM}-${CADENCE_MAX_RPM}, got ${JSON.stringify(s[key])}.`);
      } else if (workoutSchema < 2) {
        err(`${at}: ${key} requires the workout's schemaVersion to be 2, but it is ${workoutSchema}.`);
      }
    }
    if (Number.isInteger(s.cadenceMinRpm) && Number.isInteger(s.cadenceMaxRpm) &&
        s.cadenceMinRpm > s.cadenceMaxRpm) {
      err(`${at}: cadenceMinRpm ${s.cadenceMinRpm} is above cadenceMaxRpm ${s.cadenceMaxRpm}.`);
    }
  });
  if (brief.length) {
    warn(
      `${label}: ${brief.length} segment(s) under 10s (shortest ${Math.min(...brief)}s). ` +
      `Trainers ramp slowly in ERG, so the rider may never reach the target.`
    );
  }
  if (spicy.length) {
    warn(
      `${label}: ${spicy.length} segment(s) above 150% FTP (peak ${Math.max(...spicy)}%). ` +
      `Sprint territory; most trainers struggle to hold it in ERG.`
    );
  }
  return total;
}

const EXAMPLE_IDS = new Set(['alpine-warmup-2min', 'alpine-coach', 'alpine-tempo-3x10']);

function checkWorkout(w, index, seenIds, reservedIds, repoId) {
  const label = `Workout ${index + 1} (${w?.id ?? 'no id'})`;
  if (!w || typeof w !== 'object') {
    err(`${label}: not an object.`);
    return null;
  }
  const schema = w.schemaVersion ?? 1;
  if (schema > MAX_WORKOUT_SCHEMA) {
    err(`${label}: schemaVersion ${schema} exceeds the supported ${MAX_WORKOUT_SCHEMA}; it will be skipped everywhere.`);
  }
  for (const field of ['id', 'name']) {
    if (typeof w[field] !== 'string' || !w[field].trim()) {
      err(`${label}: missing a non-empty "${field}".`);
    }
  }
  if (w.id) {
    if (seenIds.has(w.id)) err(`${label}: duplicate id within this repository.`);
    seenIds.add(w.id);
    const reserved = reservedIds.get(w.id);
    if (reserved && reserved.ownerRepoId !== repoId) {
      err(`${label}: id collides with the ${reserved.label} already in riders' libraries. Prefix it with your repo id.`);
    }
    if (EXAMPLE_IDS.has(w.id)) {
      err(`${label}: this is the example workout from the contributor docs, published verbatim. Replace it with your own.`);
    }
    if (repoId && !w.id.startsWith(repoId) && !w.id.includes('-')) {
      warn(`${label}: bare id. Prefixing with "${repoId}-" avoids collisions with other repos.`);
    }
  }
  // "FTP Test" and "Ramp Test" are real session names, so a bare "test" match
  // is useless here. Look for placeholder-shaped names instead.
  if (w.name && /\btest[- ]?(repo|repository|bundle|workout)\b|\bplaceholder\b|\bdummy\b|\buntitled\b|^\s*test\s*\d*\s*$/i.test(w.name)) {
    warn(`${label}: name "${w.name}" looks like a placeholder.`);
  }
  const total = checkSegments(w, label);
  if (total > 0) {
    const mins = Math.round(total / 60);
    if (total < 300) warn(`${label}: only ${mins} min long. Fine for a warm-up, odd for a session.`);
    ok(`${label}: ${w.segments.length} segments, ${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`);
  }
  return w.id ?? null;
}

// ── Repo-level driver ────────────────────────────────────────────────────

async function validateRepo(manifestUrl, registryEntry, reservedIds) {
  section(`=== ${registryEntry?.name ?? manifestUrl} ===`);
  console.log(`  manifest: ${manifestUrl}`);

  let manifest;
  try {
    const { res, text } = await fetchJson(manifestUrl);
    if (!checkTransport(manifestUrl, res)) return;
    checkCors(manifestUrl, res);
    try {
      manifest = JSON.parse(text);
    } catch (e) {
      err(`Manifest is not valid JSON: ${e.message}`);
      return;
    }
  } catch (e) {
    err(`Could not reach the manifest: ${e.message}`);
    return;
  }

  checkManifestShape(manifest);

  if (registryEntry) {
    if (registryEntry.id !== manifest.id) {
      err(`Registry id "${registryEntry.id}" does not match manifest id "${manifest.id}".`);
    }
    for (const field of ['name', 'author', 'description']) {
      const a = registryEntry[field];
      const b = manifest[field];
      if (a && b && a !== b) {
        warn(`Registry ${field} "${a}" differs from manifest ${field} "${b}". Riders see both.`);
      }
    }
    // The repo page links manifest.homepage, so a homepage that exists only in
    // the registry silently renders nothing.
    if (registryEntry.homepage && !manifest.homepage) {
      warn(
        `Registry entry has homepage "${registryEntry.homepage}" but the manifest has none, ` +
        `so no link renders. Add it to the manifest, or drop it from the registry entry.`
      );
    }
  }

  const seenIds = new Set();
  let workoutCount = 0;
  for (const entry of manifest.bundles ?? []) {
    const bundleSchema = entry.schemaVersion ?? 1;
    if (typeof entry.id !== 'string' || !entry.id.trim()) {
      err('A bundle entry has no id.');
    }
    if (bundleSchema > MAX_BUNDLE_SCHEMA) {
      err(`Bundle "${entry.id}" declares schemaVersion ${bundleSchema} > ${MAX_BUNDLE_SCHEMA}; it is skipped without downloading.`);
      continue;
    }
    if (typeof entry.url !== 'string' || !entry.url) {
      err(`Bundle "${entry.id}" has no url.`);
      continue;
    }
    const bundleUrl = resolveBundleUrl(entry.url, manifestUrl);
    section(`  bundle "${entry.id}" -> ${bundleUrl}`);
    let bundle;
    try {
      const { res, text } = await fetchJson(bundleUrl);
      if (!checkTransport(bundleUrl, res)) continue;
      checkCors(bundleUrl, res);
      try {
        bundle = JSON.parse(text);
      } catch (e) {
        err(`Bundle is not valid JSON: ${e.message}`);
        continue;
      }
    } catch (e) {
      err(`Could not reach the bundle: ${e.message}`);
      continue;
    }
    if (!Array.isArray(bundle)) {
      err('Bundle must be a JSON array of workout objects.');
      continue;
    }
    bundle.forEach((w, i) => {
      checkWorkout(w, i, seenIds, reservedIds, manifest.id);
      workoutCount++;
    });
  }

  if (workoutCount === 0) {
    err('The repository resolved to zero workouts.');
  } else {
    section(`  ${workoutCount} workout(s) resolved.`);
  }
}

// ── Entry point ──────────────────────────────────────────────────────────

async function main() {
  const args = process.argv.slice(2);
  const reservedIds = loadReservedWorkoutIds();

  if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
    console.log(`Usage:
  node scripts/validate_repo.mjs <manifestUrl>       validate one repository
  node scripts/validate_repo.mjs --registry [path]   validate every entry in a registry
                                                     (default: website/registry.json)`);
    process.exit(args.length === 0 ? 1 : 0);
  }

  if (args[0] === '--registry') {
    const path = args[1] ? args[1] : join(REPO_ROOT, 'website', 'registry.json');
    let registry;
    try {
      registry = JSON.parse(readFileSync(path, 'utf8'));
    } catch (e) {
      console.log(`Could not read registry at ${path}: ${e.message}`);
      process.exit(1);
    }
    const repos = registry.repositories ?? [];
    console.log(`Validating ${repos.length} repositories from ${path}`);
    const seenRepoIds = new Set();
    for (const entry of repos) {
      if (seenRepoIds.has(entry.id)) {
        section(`=== ${entry.name} ===`);
        err(`Duplicate registry id "${entry.id}".`);
      }
      seenRepoIds.add(entry.id);
      await validateRepo(entry.manifestUrl, entry, reservedIds);
    }
  } else {
    await validateRepo(args[0], null, reservedIds);
  }

  section(`Done: ${errors} error(s), ${warnings} warning(s).`);
  process.exit(errors > 0 ? 1 : 0);
}

main();
