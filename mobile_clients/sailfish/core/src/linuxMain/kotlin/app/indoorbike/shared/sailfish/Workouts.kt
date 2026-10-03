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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.WorkoutSource
import app.indoorbike.shared.workout.PowerZoneBand
import app.indoorbike.shared.workout.toSummary
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The workout library as the `workouts` topic: every workout the rider can
 * ride, their own first, with the numbers a list shows worked out at their FTP.
 * Plus the seven zones on `zones`, so QML draws them from the one shared table.
 */
internal fun SailfishCore.installWorkouts() {
    bus.publish("zones", flowOf(Unit)) {
        buildJsonArray {
            PowerZoneBand.entries.forEach { zone ->
                add(buildJsonObject {
                    put("number", zone.number)
                    put("label", zone.label)
                    put("color", argb(zone.color))
                    put("tint", argb(zone.tint))
                    put("ftpHighPct", zone.ftpHighPct)
                })
            }
        }
    }

    bus.publish(
        "workouts",
        combine(workoutLibrary.entries, userSettings.ftp, userSettings.favouriteWorkoutIds) { entries, ftp, favourites ->
            Triple(entries, ftp, favourites)
        },
    ) { (entries, ftp, favourites) ->
        buildJsonArray {
            entries.forEach { entry ->
                add(encodeWorkout(entry.workout, ftp, favourite = entry.workout.id in favourites).let { json ->
                    JsonObject(json + ("source" to JsonPrimitive(entry.source.attribution)) +
                        ("imported" to JsonPrimitive(entry.source is WorkoutSource.Imported)))
                })
            }
        }
    }

    bus.command("workouts.favourite") { args ->
        userSettings.setWorkoutFavourite(args.requireString("id"), args.bool("favourite") ?: true)
        JsonNull
    }
}

/** A workout as QML lists and draws it: its card numbers at [ftp], and its segments. */
internal fun encodeWorkout(workout: Workout, ftp: Int, favourite: Boolean = false): JsonObject {
    val summary = workout.toSummary(ftp)
    return buildJsonObject {
        put("id", workout.id)
        put("name", workout.name)
        put("description", workout.description)
        put("tags", buildJsonArray { workout.tags.forEach { add(JsonPrimitive(it)) } })
        put("tag", summary.tag)
        put("totalSeconds", summary.totalSeconds)
        put("intensityFactor", summary.intensityFactor)
        put("tss", summary.tss)
        put("kilojoules", summary.kilojoules)
        put("normalizedPower", summary.normalizedPowerWatts)
        put("favourite", favourite)
        put("segments", encodeSegments(workout.segments))
    }
}

internal fun encodeSegments(segments: List<PowerZone>): JsonArray = buildJsonArray {
    segments.forEach { segment ->
        add(buildJsonObject {
            put("seconds", segment.lengthInSeconds)
            put("pct", segment.powerPercentFTP)
            put("type", intervalLabel(segment.intervalType))
            put("zone", PowerZoneBand.fromFtpPercent(segment.powerPercentFTP).number)
            put("cadenceMin", segment.cadenceMinRpm)
            put("cadenceMax", segment.cadenceMaxRpm)
        })
    }
}

/**
 * An interval's type in the app's voice: workout files carry it in whatever
 * case their author wrote ("CONSTANT", "ramp"). As Android's ride screen.
 */
internal fun intervalLabel(type: String): String = type.lowercase().replaceFirstChar { it.uppercase() }

/** 0xAARRGGBB as QML's "#AARRGGBB". */
internal fun argb(value: Long): JsonElement = JsonPrimitive("#" + value.toString(16).padStart(8, '0').uppercase())
