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
package app.indoorbike.shared.library

import app.indoorbike.shared.Workout

/**
 * Where a [Workout] in the in-app library originated. Used for the small
 * attribution shown on the workout card and to know how a workout was added.
 *
 * Two kinds:
 *  - [Bundled]: ships with the app (`workouts*.json`).
 *  - [Imported]: a local copy in `user_workouts/`. Imports come
 *    from file pick, share-sheet, QR, or the community-repo browser. When the
 *    rider added a workout from a known community repo, [sourceRepoId] and
 *    [sourceRepoName] carry that provenance for attribution display. The local
 *    copy is *static* — it isn't re-fetched if the upstream repo changes.
 *
 * The previous `Repository` source (live mirror of a subscribed repo) was
 * removed when subscriptions were dropped in favour of the explicit-add model.
 */
sealed class WorkoutSource {
    data object Bundled : WorkoutSource()

    data class Imported(
        val sourceRepoId: String? = null,
        val sourceRepoName: String? = null,
    ) : WorkoutSource()

    val attribution: String
        get() = when (this) {
            is Bundled -> "Bundled"
            is Imported -> sourceRepoName ?: "Imported"
        }
}

/** Workout + provenance. The Rides list and Library both consume this. */
data class WorkoutEntry(
    val workout: Workout,
    val source: WorkoutSource,
)
