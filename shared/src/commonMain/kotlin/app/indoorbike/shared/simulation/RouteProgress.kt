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
package app.indoorbike.shared.simulation

import kotlinx.serialization.Serializable

/**
 * How far along a route the rider got before they stopped.
 *
 * Published routes are as long as the roads they came from - a Tour stage is
 * eight hours - and almost nobody rides one in a sitting. This is what lets
 * the next sitting carry on: the rider is offered the road from here or from
 * the start, and either way the choice is theirs.
 *
 * Distinct from [app.indoorbike.shared.session.ActiveSessionSnapshot], which
 * exists to survive a crash and describes a ride still in progress. This
 * describes a ride that ended, deliberately, partway along.
 */
@Serializable
data class RouteProgress(
    val routeId: String,
    val distanceMeters: Double,
    /**
     * Climbing already done. Carried so a resumed ride's total is the whole
     * route's, not just the part after the break.
     */
    val elevationGainMeters: Double = 0.0,
    /** Seconds already spent on the road, summed across every sitting. */
    val elapsedSeconds: Int = 0,
    val savedAtEpochMs: Long = 0L,
)

/**
 * Where partly-ridden routes are remembered. One record per route: picking a
 * route up again replaces the old mark rather than accumulating them, because
 * "where I stopped" only ever has one answer.
 */
interface RouteProgressStore {
    /** Every route with something to carry on from, keyed by route id. */
    suspend fun read(): Map<String, RouteProgress>

    suspend fun save(progress: RouteProgress)

    /** Forget a route: it was finished, or the rider chose to start it again. */
    suspend fun clear(routeId: String)
}

/**
 * Below this, "continue" is not worth offering: the rider rolled a few metres
 * and stopped, and being asked where to start reads as the app losing track of
 * what they did rather than remembering it.
 */
const val MIN_RESUMABLE_METERS = 250.0
