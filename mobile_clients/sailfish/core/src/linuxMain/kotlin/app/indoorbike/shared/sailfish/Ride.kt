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

import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.session.ActiveSessionSnapshot
import app.indoorbike.shared.session.RideRecord
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.session.UnfinishedRide
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.session.describeUnfinishedRide
import app.indoorbike.shared.session.writeRideToHistory
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.workout.PowerZoneBand
import app.indoorbike.shared.workout.cadenceTarget
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation
import app.indoorbike.shared.workout.resolveRideWorkout
import app.indoorbike.shared.workout.simulationRouteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The ride, as the `ride` and `rideWorkout` topics and the `ride.*` commands:
 * getting a workout or a free ride ready, pausing, skipping, making it harder
 * or easier, ending it, and saving it when it completes. The ride itself (the
 * clock, auto-pause, ERG, the record kept against a crash) is the shared
 * [app.indoorbike.shared.session.WorkoutSessionManager]'s.
 *
 * Also `unfinishedRide`: one a previous run was stopped in the middle of, to
 * continue, save or abandon.
 */
internal fun SailfishCore.installRide() {
    val sm = sessionManager

    bus.publish("rideWorkout", combine(sm.activeWorkout, userSettings.ftp) { w, ftp -> w to ftp }) { (workout, ftp) ->
        if (workout == null) JsonNull else JsonObject(encodeWorkout(workout, ftp) + ("kind" to JsonPrimitive(kindOf(workout))))
    }

    val clocks: List<Flow<Any?>> = listOf(
        sm.state, sm.activeWorkout, sm.elapsedSeconds, sm.actualRideSeconds, sm.countdownSeconds,
        sm.currentSegmentIndex, sm.elapsedInSegment, sm.targetBiasPercent, sm.freeRideTargetWatts,
        sm.untrackedLabel, sm.virtualDistanceMeters, userSettings.ftp, trainerManager.power, userSettings.weightKg,
    )
    bus.publish("ride", combine(clocks) { it.toList() }, minIntervalMs = 250) { v ->
        encodeRide(
            state = v[0] as WorkoutState,
            workout = v[1] as Workout?,
            elapsed = v[2] as Int,
            rideSeconds = v[3] as Int,
            countdown = v[4] as Int,
            segmentIndex = v[5] as Int,
            inSegment = v[6] as Int,
            bias = v[7] as Int,
            freeRideWatts = v[8] as Int,
            untracked = v[9] as String?,
            distanceMeters = v[10] as Double,
            ftp = v[11] as Int,
            virtualSpeedKmh = (v[13] as Float).takeIf { it > 0f }?.let { VirtualSpeed.speedKmh(v[12] as Int, it) },
        )
    }

    bus.publish(
        "unfinishedRide",
        combine(sm.persistedSnapshot, workoutLibrary.entries, sm.state) { snapshot, entries, state ->
            // Only between rides: a ride in progress has its own snapshot, which is not "left behind".
            if (snapshot == null || (state != WorkoutState.Idle && state != WorkoutState.Completed)) null
            else unfinishedRide(snapshot, entries.map { it.workout })
        },
    ) { ride ->
        if (ride == null) JsonNull else buildJsonObject {
            put("workoutId", ride.workout.id)
            put("name", ride.workout.name)
            put("kind", kindOf(ride.workout))
            put("finished", ride.isFinished)
            put("openEnded", ride.openEnded)
            put("rideSeconds", ride.rideSeconds)
            put("plannedSeconds", ride.plannedSeconds)
            put("progress", ride.progress)
        }
    }

    // Every ride that completes is saved, once, and then said so.
    var savingStart = 0L
    sm.state.onEach { state ->
        val workout = sm.activeWorkout.value
        if (state != WorkoutState.Completed || workout == null) return@onEach
        // A companion ride the host is hosting is saved by the host, the
        // moment the game says it is over; saving it here too would save it twice.
        if (workout.isCompanion && companionHost.hosting.value) return@onEach
        val start = sm.sessionStartTimeEpoch
        if (start == 0L || start == savingStart) return@onEach
        savingStart = start
        // Read everything now, before anything else can start a new ride.
        val duration = sm.actualRideSeconds.value
        val averagePower = sm.averagePowerWatts()
        val telemetry = sm.telemetrySnapshot()
        val road = sm.pendingRideRoad()
        val record = sm.pendingRideRecord()
        scope.launch {
            val id = saveRide(workout, start, duration, telemetry, road, record)
            bus.event("ride.saved", buildJsonObject {
                put("id", id)
                put("ok", id != null)
                put("rideSeconds", duration)
                put("averagePower", averagePower)
            })
        }
    }.launchIn(scope)

    bus.command("ride.prepareWorkout") { args ->
        val id = args.requireString("id")
        val workout = workoutLibrary.find(id) ?: throw UserFacing("That workout is not in the library any more.")
        sm.prepareWorkout(workout, userSettings.ftp.value)
        JsonNull
    }
    bus.command("ride.prepareFreeRide") {
        sm.prepareFreeRide(userSettings.ftp.value)
        JsonNull
    }
    bus.command("ride.pause") { sm.pauseWorkout(); JsonNull }
    bus.command("ride.resume") { sm.resumeWorkout(); JsonNull }
    bus.command("ride.skip") { sm.skipSegment(); JsonNull }
    /** {"direction": 1} harder, -1 easier, as the ride in progress understands it. */
    bus.command("ride.step") { args ->
        stepEffort(args.requireInt("direction").coerceIn(-1, 1))
        JsonNull
    }
    /** End and save: the ride completes now, and is saved as any completed ride is. */
    bus.command("ride.end") { sm.endWorkoutEarly(); JsonNull }
    /** Back out: no save, but the ride stays on offer as unfinished. */
    bus.command("ride.leave") { sm.stopWorkout(); JsonNull }
    /** Done with the completed ride's summary. */
    bus.command("ride.close") {
        if (sm.state.value == WorkoutState.Completed) sm.stopWorkout()
        JsonNull
    }

    bus.command("unfinished.continue") {
        val ride = currentUnfinished() ?: throw UserFacing("There is no ride to continue.")
        // A route ride takes its road back with it, as on iOS.
        val route = simulationRouteId(ride.workout.id)?.let { routeLibrary.find(it) }
        sm.restoreFromSnapshot(ride.snapshot, ride.workout, route)
        JsonNull
    }
    bus.request("unfinished.save") {
        val ride = currentUnfinished() ?: throw UserFacing("There is no ride to save.")
        val snapshot = ride.snapshot
        val id = saveRide(
            workout = ride.workout,
            startTimeEpoch = snapshot.sessionStartTimeEpochMs,
            durationSeconds = ride.rideSeconds,
            telemetry = sm.pendingRideTelemetry(snapshot),
            road = sm.pendingRideRoad(snapshot),
            record = sm.pendingRideRecord(snapshot),
        ) ?: throw UserFacing("The ride couldn't be saved.")
        JsonPrimitive(id)
    }
    bus.command("unfinished.abandon") {
        // Its pictures and the game it was ridden with go with it, as on Android.
        sm.persistedSnapshot.value?.sessionStartTimeEpochMs?.let { start ->
            screenshots.deleteRide(start)
            brands.deleteRide(start)
        }
        sm.abandonUnfinishedRide()
        JsonNull
    }
}

/** The workout's kind, as the ride screens choose their layout by. */
internal fun kindOf(workout: Workout): String = when {
    workout.isFreeRide -> "free"
    workout.isSimulation -> "route"
    workout.isCompanion -> "companion"
    else -> "workout"
}

private fun SailfishCore.currentUnfinished(): UnfinishedRide? =
    sessionManager.persistedSnapshot.value?.let { unfinishedRide(it, workoutLibrary.entries.value.map { e -> e.workout }) }

/**
 * The ride a previous run left behind, or null when there is none worth
 * showing. A finished one with no samples to keep is discarded here rather
 * than offered: saving it would add an empty row to history. As on iOS.
 */
private fun SailfishCore.unfinishedRide(
    snapshot: ActiveSessionSnapshot,
    library: List<Workout>,
): UnfinishedRide? {
    val workout = resolveRideWorkout(snapshot.workoutId, library, routeLibrary.routeNames()) ?: return null
    val ride = describeUnfinishedRide(snapshot, workout)
    if (ride.isFinished && sessionManager.pendingRideTelemetry(snapshot).isEmpty()) {
        sessionManager.abandonUnfinishedRide()
        return null
    }
    return ride
}

/**
 * Write a ride to history under the save lock; its row id, or null if it
 * failed. Every way a ride reaches history comes through here, so this is
 * where automatic upload starts, as on Android and iOS.
 */
internal suspend fun SailfishCore.saveRide(
    workout: Workout,
    startTimeEpoch: Long,
    durationSeconds: Int,
    telemetry: List<TelemetryPoint>,
    road: RideRoad,
    record: RideRecord,
): Long? {
    val id = try {
        saveLock.withLock {
            writeRideToHistory(
                history = historyRepository,
                sessionManager = sessionManager,
                deviceId = userSettings.deviceId,
                workout = workout,
                startTimeEpoch = startTimeEpoch,
                durationSeconds = durationSeconds,
                telemetry = telemetry,
                road = road,
                record = record,
            )
        }
    } catch (e: Exception) {
        Log.e("Ride", "Couldn't save the ride", e)
        null
    }
    if (id != null) rideUploads.autoUpload(id, workout, record.ftp.takeIf { it > 0 } ?: userSettings.ftp.value)
    return id
}

/**
 * Whether "End and save" is offered, as the Android ride screen decides it:
 * paused, or stopped pedalling in a ride that has started, and while riding
 * too in an open-ended ride, whose only way to finish is ending it. A structured
 * workout riding along finishes by itself, so it is paused first; a ride that
 * never started has nothing to save.
 */
private fun canEndAndSave(state: WorkoutState, workout: Workout?, rideSeconds: Int): Boolean {
    val openEnded = workout != null && (workout.isFreeRide || workout.isSimulation)
    return state == WorkoutState.Paused ||
        (state == WorkoutState.Standby && rideSeconds > 0) ||
        (state == WorkoutState.Untracked && rideSeconds > 0) ||
        (openEnded && state == WorkoutState.Active && rideSeconds > 0)
}

private fun encodeRide(
    state: WorkoutState,
    workout: Workout?,
    elapsed: Int,
    rideSeconds: Int,
    countdown: Int,
    segmentIndex: Int,
    inSegment: Int,
    bias: Int,
    freeRideWatts: Int,
    untracked: String?,
    distanceMeters: Double,
    ftp: Int,
    virtualSpeedKmh: Double?,
): JsonElement = buildJsonObject {
    put("state", state.name)
    put("active", state != WorkoutState.Idle && state != WorkoutState.Completed)
    // Riding has been recorded: a ride that has started, as opposed to one
    // still waiting for the trainer or the pedals.
    put("started", rideSeconds > 0)
    put("openEnded", workout != null && (workout.isFreeRide || workout.isSimulation || workout.isCompanion))
    put("canEndAndSave", canEndAndSave(state, workout, rideSeconds))
    put("virtualSpeedKmh", virtualSpeedKmh)
    put("workoutId", workout?.id)
    put("kind", workout?.let(::kindOf))
    put("elapsed", elapsed)
    put("rideSeconds", rideSeconds)
    put("countdown", countdown)
    put("biasPercent", bias)
    put("freeRideTarget", freeRideWatts)
    put("untracked", untracked)
    put("distanceMeters", distanceMeters)
    if (workout == null) return@buildJsonObject
    val segments = workout.segments
    val total = segments.sumOf { it.lengthInSeconds }
    put("totalSeconds", total)
    put("remainingSeconds", (total - elapsed).coerceAtLeast(0))
    put("segmentIndex", segmentIndex)
    put("segmentCount", segments.size)
    val structured = !workout.isFreeRide && !workout.isSimulation && !workout.isCompanion
    fun target(pct: Int) = (pct / 100.0 * ftp * (1.0 + bias / 100.0)).roundToInt().coerceAtLeast(0)
    segments.getOrNull(segmentIndex)?.let { segment ->
        put("segment", buildJsonObject {
            put("type", intervalLabel(segment.intervalType))
            put("pct", segment.powerPercentFTP)
            put("seconds", segment.lengthInSeconds)
            put("remaining", (segment.lengthInSeconds - inSegment).coerceAtLeast(0))
            put("zone", PowerZoneBand.fromFtpPercent(segment.powerPercentFTP).number)
            put("targetWatts", if (workout.isFreeRide) freeRideWatts else if (structured) target(segment.powerPercentFTP) else 0)
            put("cadenceMin", segment.cadenceMinRpm)
            put("cadenceMax", segment.cadenceMaxRpm)
            segment.cadenceTarget?.let { cadence ->
                put("cadence", buildJsonObject {
                    put("label", cadence.label)
                    put("low", cadence.lowBoundRpm)
                    put("high", cadence.highBoundRpm)
                })
            }
        })
    }
    if (structured) {
        segments.getOrNull(segmentIndex + 1)?.let { next ->
            put("next", buildJsonObject {
                put("type", intervalLabel(next.intervalType))
                put("pct", next.powerPercentFTP)
                put("seconds", next.lengthInSeconds)
                put("zone", PowerZoneBand.fromFtpPercent(next.powerPercentFTP).number)
                put("targetWatts", target(next.powerPercentFTP))
            })
        }
    }
}
