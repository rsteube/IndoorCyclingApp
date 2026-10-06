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
package app.indoorbike.shared

import kotlinx.serialization.Serializable

/**
 * Represents a single segment/power zone within a workout.
 *
 * Cadence is an *optional* second dimension. Power is what the trainer holds in
 * ERG mode, so the rider's cadence is theirs to choose; a segment can ask for a
 * particular spin (grinding low-rpm climbs, high-rpm leg speed work) without
 * changing the effort. Both fields default to null, which means "any cadence"
 * and is what every workout written before this existed says.
 */
@Serializable
data class PowerZone(
    val lengthInSeconds: Int,
    val powerPercentFTP: Int,
    val intervalType: String,
    /** Bottom of the cadence target in rpm, or null for no target. */
    val cadenceMinRpm: Int? = null,
    /**
     * Top of the cadence target in rpm. Null alongside a [cadenceMinRpm] means
     * a single target rather than a range - see [CadenceTarget], which is how
     * callers should read this pair.
     */
    val cadenceMaxRpm: Int? = null,
    /**
     * Bottom of a power range in % of FTP, or null for one constant power.
     * Set together with [powerMaxPercentFTP]; [powerPercentFTP] is then
     * where the trainer starts inside the range, and what an app from before
     * ranges rides instead. See [app.indoorbike.shared.workout.PowerRange].
     */
    val powerMinPercentFTP: Int? = null,
    /** Top of the power range in % of FTP. See [powerMinPercentFTP]. */
    val powerMaxPercentFTP: Int? = null,
)

/**
 * Represents a structured workout based on power.
 *
 * [schemaVersion] is the on-disk format version of this workout file. Older
 * apps that encounter a workout whose `schemaVersion` exceeds
 * [WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED] should skip it gracefully. The field
 * defaults to 1 so files predating its introduction parse cleanly.
 */
@Serializable
data class Workout(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    // Defaulted to null so workouts whose JSON omits the field parse cleanly.
    // Some community bundles ship without descriptions; the website's JS
    // tolerated the missing field but kotlinx.serialization treats nullable
    // fields without a default as required in the input.
    val description: String? = null,
    val screenshot: String? = null,
    val url: String? = null,
    // Optional free-form labels (e.g. "Threshold", "VO2 Max"). Documented in
    // the contributor schema; defaulted to empty so older or hand-rolled
    // bundles without the field still parse.
    val tags: List<String> = emptyList(),
    val segments: List<PowerZone>
)

/**
 * Format version emitted for workouts the app writes today.
 *
 * v2 added the optional cadence target on [PowerZone]. Writers should only
 * stamp 2 on workouts that actually carry one: a v1 workout still parses
 * everywhere, whereas a v2 workout is skipped wholesale by older builds. There
 * is no reason to lock a plain power workout out of an app that could ride it.
 * See [workoutSchemaVersionFor].
 *
 * A power range needs no version of its own: an older app ignores the two
 * bounds and rides [PowerZone.powerPercentFTP], which is a fair reading of the
 * workout rather than a wrong one.
 */
const val WORKOUT_SCHEMA_VERSION_CURRENT = 2
/** Highest format version this build can parse — entries above are skipped. */
const val WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED = 2

/**
 * The lowest schema version that can faithfully represent [segments].
 *
 * Stamping the minimum keeps reach as wide as the content allows: a workout
 * only claims v2 once dropping a field would change what the rider is asked
 * to do.
 */
fun workoutSchemaVersionFor(segments: List<PowerZone>): Int =
    if (segments.any { it.cadenceMinRpm != null || it.cadenceMaxRpm != null }) 2 else 1
