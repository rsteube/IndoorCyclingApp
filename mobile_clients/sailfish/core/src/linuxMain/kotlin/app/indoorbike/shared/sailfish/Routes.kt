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

import app.indoorbike.shared.route.RouteEstimate
import app.indoorbike.shared.route.RouteSource
import app.indoorbike.shared.route.RouteStats
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.formatGradientPercent
import app.indoorbike.shared.route.gradientBand
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.util.PosixFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Routes and riding them: the `routes` topic (every route, with its card
 * numbers, how long it should take this rider, and where they left off), the
 * road of the ride in progress on `rideRoute`, and the live HUD on
 * `simulation` (gradient, the road ahead, gear, speed, distance, climbing).
 * The physics and the trainer's road are the shared SimulationEngine's.
 */
internal fun SailfishCore.installRoutes() {
    val progress = MutableStateFlow<Map<String, RouteProgress>>(emptyMap())
    fun reloadProgress() = scope.launch { progress.value = runCatching { routeProgressStore.read() }.getOrDefault(emptyMap()) }
    reloadProgress()
    // A ride that ends, saved or not, may have moved where the rider got to.
    sessionManager.state.map { it == WorkoutState.Idle || it == WorkoutState.Completed }
        .distinctUntilChanged()
        .onEach { if (it) reloadProgress() }
        .launchIn(scope)

    val rider: Flow<Triple<Int, Float, Float>> =
        combine(userSettings.ftp, userSettings.weightKg, userSettings.bikeWeightKg) { ftp, kg, bike -> Triple(ftp, kg, bike) }

    bus.publish(
        "routes",
        combine(routeLibrary.routes, routeLibrary.sources, progress, rider) { routes, sources, done, r ->
            routes.map { route -> encodeRoute(route, sources[route.id], done[route.id], r) }
        },
    ) { JsonArray(it) }

    bus.publish("rideRoute", simulationEngine.route) { route ->
        if (route == null) JsonNull else buildJsonObject {
            put("id", route.id)
            put("name", route.name)
            put("totalMeters", route.totalDistanceMeters)
            put("ascentMeters", route.totalAscentMeters)
            put("minElevation", route.minElevationMeters)
            put("maxElevation", route.maxElevationMeters)
            put("shape", shapeOf(route, 400))
            put("profile", profileOf(route, 240))
            put("bands", buildJsonArray {
                route.gradientSamples(240).forEach { add(JsonPrimitive(gradientBand(it).name)) }
            })
        }
    }

    val e = simulationEngine
    val live: List<Flow<Any?>> = listOf(
        e.distanceMeters, e.speedMps, e.gradient, e.position, e.gearIndex, e.drivetrain,
        e.elevationGainMeters, e.finished, e.route, e.startedAtMeters,
    )
    bus.publish("simulation", combine(live) { it.toList() }, minIntervalMs = 250) { v ->
        val route = v[8] as RouteTrack?
        if (route == null) return@publish JsonNull
        val distance = v[0] as Double
        val gradient = v[2] as Double
        val position = v[3] as app.indoorbike.shared.route.RoutePosition?
        val gear = v[4] as Int
        val drivetrain = v[5] as app.indoorbike.shared.simulation.Drivetrain
        buildJsonObject {
            put("distanceMeters", distance)
            put("totalMeters", route.totalDistanceMeters)
            put("remainingMeters", (route.totalDistanceMeters - distance).coerceAtLeast(0.0))
            put("fraction", if (route.totalDistanceMeters > 0) distance / route.totalDistanceMeters else 0.0)
            put("speedKmh", (v[1] as Double) * 3.6)
            put("gradient", gradient)
            put("gradientText", formatGradientPercent(gradient))
            put("band", gradientBand(gradient).name)
            put("climbedMeters", v[6] as Double)
            put("finished", v[7] as Boolean)
            put("startedAtMeters", v[9] as Double)
            put("gear", gear + 1)
            put("gears", drivetrain.gears.size)
            // What the gear asks of the legs at this speed: the number that says whether to shift.
            put("askedCadence", drivetrain.cadenceRpmFor(v[1] as Double, gear))
            drivetrain.gears.getOrNull(gear)?.let { put("gearLabel", "${it.chainring} x ${it.sprocket}") }
            position?.let {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("bearing", it.bearingDegrees)
                put("elevation", it.elevationMeters)
            }
            // The next 500 m, in 50 m steps: what the legs are about to meet.
            put("ahead", buildJsonArray {
                for (i in 0 until 10) {
                    val at = distance + i * 50.0
                    if (at > route.totalDistanceMeters) break
                    add(JsonPrimitive(gradientBand(route.gradientAt(at)).name))
                }
            })
        }
    }

    /** {"id", "fromStart"}: a route, from where the rider left it unless told otherwise. */
    bus.command("ride.prepareRoute") { args ->
        val route = routeLibrary.find(args.requireString("id")) ?: throw UserFacing("That route is not here any more.")
        val resume = if (args.bool("fromStart") == true) null else progress.value[route.id]
        sessionManager.prepareSimulation(
            route = route,
            ftp = userSettings.ftp.value,
            startDistanceMeters = resume?.distanceMeters ?: 0.0,
            startElevationGainMeters = resume?.elevationGainMeters ?: 0.0,
        )
        JsonNull
    }
    bus.command("sim.shift") { args ->
        simulationEngine.shiftBy(args.requireInt("steps"))
        JsonNull
    }

    bus.request("routes.delete") { args ->
        val id = args.requireString("id")
        val removed = routeLibrary.delete(id)
        if (removed) runCatching { routeProgressStore.clear(id) }
        reloadProgress()
        JsonPrimitive(removed)
    }
    /** A GPX the rider picked, or opened with the app, by its path. */
    bus.request("routes.importFile") { args ->
        val path = args.requireString("path")
        val bytes = PosixFiles.readBytes(path) ?: throw UserFacing("That file could not be read.")
        when (val result = routeLibrary.importFile(path.substringAfterLast('/'), bytes)) {
            is RouteImportResult.Added -> JsonPrimitive(result.track.id)
            is RouteImportResult.Failure -> throw UserFacing(result.message)
        }
    }
}

private fun encodeRoute(
    route: RouteTrack,
    source: RouteSource?,
    progress: RouteProgress?,
    rider: Triple<Int, Float, Float>,
): JsonObject {
    val entry = RouteStats.entryFor(route, gpxUrl = "local")
    return buildJsonObject {
        put("id", route.id)
        put("name", route.name)
        put("distanceMeters", route.totalDistanceMeters)
        put("ascentMeters", route.totalAscentMeters)
        put("descentMeters", route.totalDescentMeters)
        put("maxGradientPercent", entry.maxGradientPercent)
        put("loop", route.isLoop)
        put("estimateSeconds", RouteEstimate.secondsFor(entry, rider.first, rider.second, rider.third))
        put("imported", source != null && source != RouteSource.Bundled)
        put("progressMeters", progress?.distanceMeters?.takeIf { it > 0.0 })
        put("profile", profileOf(route, 48))
        put("shape", shapeOf(route, 64))
    }
}

/** Elevation in [samples] steps, scaled 0..1 between the route's lowest and highest point. */
internal fun profileOf(route: RouteTrack, samples: Int): JsonArray {
    val low = route.minElevationMeters
    val span = (route.maxElevationMeters - low).coerceAtLeast(1.0)
    return buildJsonArray {
        route.elevationSamples(samples).forEach { add(JsonPrimitive(((it - low) / span * 1000).roundToInt() / 1000.0)) }
    }
}

/** The route's shape as at most [points] [latitude, longitude] pairs, evenly along it. */
internal fun shapeOf(route: RouteTrack, points: Int): JsonArray = buildJsonArray {
    if (route.isEmpty) return@buildJsonArray
    for (i in 0 until points) {
        val p = route.positionAt(route.totalDistanceMeters * i / (points - 1))
        add(buildJsonArray {
            add(JsonPrimitive(p.latitude))
            add(JsonPrimitive(p.longitude))
        })
    }
}
