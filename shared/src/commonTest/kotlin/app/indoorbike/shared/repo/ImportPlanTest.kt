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

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An import link or file replacing a workout without asking. */
class ImportPlanTest {

    private fun workout(id: String, name: String = id, watts: Int = 60) = Workout(
        id = id, name = name,
        segments = listOf(PowerZone(lengthInSeconds = 60, powerPercentFTP = watts, intervalType = "CONSTANT")),
    )

    private fun plan(
        incoming: List<Workout>,
        imported: Map<String, Workout> = emptyMap(),
        bundled: Map<String, Workout> = emptyMap(),
        replace: Boolean = false,
    ) = ImportPlan.plan(
        incoming = incoming,
        imported = { imported[it] },
        bundled = bundled,
        isTaken = { it in imported },
        replace = replace,
    )

    @Test
    fun `a new workout is written as it is`() {
        val step = plan(listOf(workout("a"))).single()
        assertEquals(ImportPlan.Write(workout("a")), step)
    }

    @Test
    fun `the same workout again changes nothing`() {
        val mine = workout("a")
        assertEquals(ImportPlan.Unchanged(mine), plan(listOf(mine), imported = mapOf("a" to mine)).single())
    }

    /** The report: a link carrying a starter workout's id replaced the starter. */
    @Test
    fun `a starter workout is never replaced`() {
        val starter = workout("sweet-spot", watts = 88)
        val link = workout("sweet-spot", name = "Not what it says", watts = 200)
        val step = plan(listOf(link), bundled = mapOf(starter.id to starter), replace = true).single()
        assertEquals("sweet-spot-2", step.workout.id)
        assertEquals("Not what it says", step.workout.name)
    }

    @Test
    fun `an unchanged starter shared back is already there`() {
        val starter = workout("sweet-spot")
        val step = plan(listOf(starter), bundled = mapOf(starter.id to starter)).single()
        assertTrue(step is ImportPlan.Unchanged)
    }

    @Test
    fun `a different workout under one of the rider's ids is added beside it unless they say replace`() {
        val mine = workout("a", watts = 70)
        val incoming = workout("a", watts = 90)
        assertEquals(listOf(mine), ImportPlan.replaced(listOf(incoming)) { if (it == "a") mine else null })

        assertEquals("a-2", plan(listOf(incoming), imported = mapOf("a" to mine)).single().workout.id)
        assertEquals("a", plan(listOf(incoming), imported = mapOf("a" to mine), replace = true).single().workout.id)
    }

    @Test
    fun `a new id skips the ones already in use`() {
        val imported = mapOf("a" to workout("a"), "a-2" to workout("a-2"))
        assertEquals("a-3", plan(listOf(workout("a", watts = 99)), imported = imported).single().workout.id)
    }

    @Test
    fun `two workouts in one import with the same id are both kept`() {
        val steps = plan(listOf(workout("a", watts = 50), workout("a", watts = 60)))
        assertEquals(listOf("a", "a-2"), steps.map { it.workout.id })
    }
}
