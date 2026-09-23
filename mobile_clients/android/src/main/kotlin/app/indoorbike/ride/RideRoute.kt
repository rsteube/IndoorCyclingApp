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
package app.indoorbike.ride

import app.indoorbike.shared.route.rideRoute
import app.indoorbike.AppContainer
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.courseTrackOf
import app.indoorbike.shared.workout.simulationRouteId

/**
 * The road a finished ride was on, whichever mode put it there.
 *
 * There are two answers and one question, which is the whole reason this
 * exists. A route ride keeps a pointer: its `workoutId` names a course in the
 * catalogue and the road is fetched back from the GPX. A companion ride keeps
 * the road itself, because the game it came from is not something the app can
 * go and ask. Everything downstream - the post-ride summary, the share image,
 * the history entry - wants a [RouteTrack] and should not have to care which
 * of the two it is looking at.
 *
 * Null means the ride had no road: a structured workout or a free ride, a
 * route since deleted from the catalogue, or a companion that chose not to
 * disclose its course. Every caller treats those the same way, by leaving the
 * road out rather than drawing an empty panel.
 *
 * Not cheap. It decodes a polyline and rebuilds a track, so callers should
 * `remember` it against the session rather than recomputing it per frame.
 */
fun rideRoute(container: AppContainer, session: WorkoutSession): RouteTrack? =
    rideRoute(session, container.routeCatalog::find)
