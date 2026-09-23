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
package app.indoorbike.shared.repo

import app.indoorbike.shared.Workout

/**
 * What an import does with each workout it brings, given what the library
 * already holds.
 *
 * An import used to write every workout under its own id, so one that shared
 * an id with something already there replaced it without a word. Ids come
 * from whoever made the file or link, and the starter workouts' ids are
 * public, so that was anyone's to do. The rules now:
 *
 *  - The same workout, exactly, is already there: nothing to write.
 *  - A different workout with the id of one the rider imported: replaced only
 *    if the rider said so ([replace]); otherwise added beside it under a new
 *    id.
 *  - A different workout with a starter workout's id: always added beside it.
 *    An import shadowing a starter is how one would replace it, and there is
 *    no reason a real import needs to.
 *  - Two workouts in one import with the same id: the second gets a new id.
 */
object ImportPlan {

    sealed interface Step {
        val workout: Workout
    }

    /** Already in the library exactly as it is: nothing to write. */
    data class Unchanged(override val workout: Workout) : Step

    /** To be written. [workout] carries the id it is written under. */
    data class Write(override val workout: Workout) : Step

    /**
     * The rider's own workouts that [incoming] would replace with something
     * different: what to ask about before planning with `replace = true`.
     * [imported] is the rider's workout stored under an id, if any.
     */
    fun replaced(incoming: List<Workout>, imported: (String) -> Workout?): List<Workout> =
        incoming.mapNotNull { w -> imported(w.id)?.takeIf { it != w } }.distinctBy { it.id }

    /**
     * One step per incoming workout, in order. [bundled] are the starter
     * workouts by id; [isTaken] says whether an id is already in use in the
     * library, for choosing a new one.
     */
    fun plan(
        incoming: List<Workout>,
        imported: (String) -> Workout?,
        bundled: Map<String, Workout>,
        isTaken: (String) -> Boolean,
        replace: Boolean,
    ): List<Step> {
        val used = mutableSetOf<String>()
        fun fresh(base: String): String {
            var n = 2
            while (true) {
                val candidate = "$base-$n"
                if (candidate !in used && candidate !in bundled && !isTaken(candidate)) return candidate
                n++
            }
        }
        return incoming.map { w ->
            val current = imported(w.id)
            val step = when {
                w.id in used -> Write(w.copy(id = fresh(w.id)))
                current == w -> Unchanged(w)
                current == null && bundled[w.id] == w -> Unchanged(w)
                current == null && w.id !in bundled -> Write(w)
                current == null -> Write(w.copy(id = fresh(w.id)))
                replace -> Write(w)
                else -> Write(w.copy(id = fresh(w.id)))
            }
            used += step.workout.id
            step
        }
    }
}
