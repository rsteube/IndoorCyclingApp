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

import app.indoorbike.shared.activity.TcxBuilder
import app.indoorbike.shared.activity.exportFileName
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.fit.VirtualWorld
import app.indoorbike.shared.progression.BADGE_CATALOG
import app.indoorbike.shared.progression.computeBadges
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.progression.computeRiderLevel
import app.indoorbike.shared.progression.computeStreak
import app.indoorbike.shared.progression.formatXp
import app.indoorbike.shared.progression.riderLevelTitle
import app.indoorbike.shared.progression.sessionKilojoules
import app.indoorbike.shared.progression.timeInZoneSeconds
import app.indoorbike.shared.util.PosixFiles
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.util.localUtcOffsetMs
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * History and progress: the `history` topic (every saved ride, newest first),
 * `progress` (level, streak and badges, worked out by shared/progression as on
 * Android), and the requests a ride's own page makes: its detail, export,
 * deletion.
 */
internal fun SailfishCore.installHistory() {
    bus.publish("history", historyRepository.getAllSessions()) { sessions ->
        buildJsonArray {
            sessions.sortedByDescending { it.startTimeEpoch }.forEach { add(encodeSession(it)) }
        }
    }

    bus.publish(
        "progress",
        combine(historyRepository.getAllSessions(), userSettings.weightKg) { sessions, kg -> sessions to kg },
    ) { (sessions, kg) ->
        val now = getCurrentEpochMillis()
        val offset = localUtcOffsetMs(now)
        val level = computeRiderLevel(sessions, kg)
        val streak = computeStreak(sessions, now, timeZoneOffsetMs = offset)
        val earned = computeBadges(sessions, now, timeZoneOffsetMs = offset).associateBy { it.id }
        buildJsonObject {
            put("rides", sessions.size)
            put("seconds", sessions.sumOf { it.durationSeconds })
            put("kilojoules", sessions.sumOf { sessionKilojoules(it) })
            put("level", level.level)
            put("title", riderLevelTitle(level.level))
            put("xp", formatXp(level.totalXp))
            put("xpToNext", formatXp(level.xpToNextLevel))
            put("levelProgress", level.progressFraction)
            put("streakWeeks", streak.currentWeeks)
            put("longestStreakWeeks", streak.longestWeeks)
            put("weeks", buildJsonArray { streak.recentWeekActivity.forEach { add(JsonPrimitive(it.daysActive)) } })
            put("badges", buildJsonArray {
                BADGE_CATALOG.forEach { spec ->
                    val badge = earned[spec.id]
                    add(buildJsonObject {
                        put("id", spec.id)
                        put("title", spec.title)
                        put("hint", badge?.subtitle ?: spec.lockedHint)
                        put("icon", spec.iconHint)
                        put("unlocked", badge != null)
                        put("unlockedAt", badge?.unlockedAtEpochMs)
                    })
                }
            })
        }
    }

    /** One ride, and everything its page shows: stats at today's FTP and weight, as on Android. */
    bus.request("history.ride") { args ->
        val id = args.requireLong("id")
        val session = historyRepository.getSessionById(id) ?: throw UserFacing("That ride is gone.")
        val telemetry = historyRepository.getTelemetryForSession(id)
        val watts = telemetry.map { it.power }
        val ftp = session.ftp.toInt().takeIf { it > 0 } ?: userSettings.ftp.value
        val workout = workoutLibrary.find(session.workoutId)
            ?.takeIf { !it.isFreeRide && !it.isSimulation && !it.isCompanion }
        val stats = computeRideStats(watts, session.durationSeconds, ftp, userSettings.weightKg.value)
        JsonObject(encodeSession(session) + buildJsonObject {
            put("ftp", ftp)
            put("normalizedPower", stats.normalizedPowerWatts)
            put("intensityFactor", stats.intensityFactor)
            put("tss", stats.tss)
            put("kilojoules", stats.kilojoules)
            put("distanceMeters", stats.distanceMeters)
            put("avgSpeedKmh", stats.avgSpeedKmh)
            put("canExport", telemetry.isNotEmpty())
            put("power", downsample(watts.map { it.toDouble() }))
            put("heartRate", downsample(telemetry.map { it.heartRate.toDouble() }))
            put("timeInZone", buildJsonArray { timeInZoneSeconds(watts, ftp).forEach { add(JsonPrimitive(it)) } })
            put("plan", if (workout == null) JsonNull else encodeSegments(workout.segments))
        })
    }

    bus.request("history.delete") { args ->
        historyRepository.deleteSession(args.requireLong("id"))
        JsonNull
    }

    /**
     * Write a ride as FIT or TCX into [dir] (the cache for the share sheet, or
     * Downloads), and answer with the file's path.
     */
    bus.request("history.export") { args ->
        val id = args.requireLong("id")
        val fit = (args.string("format") ?: "fit") == "fit"
        val dir = args.string("dir") ?: SailfishPaths.cacheDir
        withContext(Dispatchers.IO) {
            val session = historyRepository.getSessionById(id) ?: throw UserFacing("That ride is gone.")
            val telemetry = historyRepository.getTelemetryForSession(id)
            if (telemetry.isEmpty()) throw UserFacing("This ride has no samples to export.")
            val workout = workoutLibrary.find(session.workoutId)
            val ftp = userSettings.ftp.value
            val kg = userSettings.weightKg.value
            PosixFiles.mkdirs(dir)
            val path = "${dir.trimEnd('/')}/${exportFileName(session, if (fit) "fit" else "tcx")}"
            if (fit) {
                PosixFiles.writeAtomically(
                    path,
                    RideFitBuilder.build(
                        session, telemetry, workout, ftp, kg,
                        appVersion = appVersion,
                        // A companion ride's game, which milestone 9 keeps; none until then.
                        world = VirtualWorld.of(session, null),
                    ),
                )
            } else {
                PosixFiles.writeTextAtomically(path, TcxBuilder.build(session, telemetry, workout, ftp, kg))
            }
            JsonPrimitive(path)
        }
    }
}

private fun encodeSession(session: WorkoutSession): JsonObject = buildJsonObject {
    put("id", session.id.toString())
    put("workoutId", session.workoutId)
    put("name", session.workoutName)
    put("start", session.startTimeEpoch / 1000)
    put("startMs", session.startTimeEpoch)
    put("durationSeconds", session.durationSeconds)
    put("avgPower", session.avgPower)
    put("avgCadence", session.avgCadence)
    put("avgHeartRate", session.avgHeartRate)
    put("kilojoules", sessionKilojoules(session))
    put("intervalsUploaded", session.intervalsUploadedEpoch > 0)
    put("fitTrackeeUploaded", session.fitTrackeeUploadedEpoch > 0)
}

/** At most [points] values, each the mean of its stretch: enough for a chart a phone wide. */
private fun downsample(values: List<Double>, points: Int = 360) = buildJsonArray {
    if (values.size <= points) {
        values.forEach { add(JsonPrimitive(it.toInt())) }
        return@buildJsonArray
    }
    val step = values.size.toDouble() / points
    for (i in 0 until points) {
        val from = (i * step).toInt()
        val to = ((i + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
        add(JsonPrimitive(values.subList(from, to).average().toInt()))
    }
}
