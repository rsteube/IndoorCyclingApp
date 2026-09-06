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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrivetrainTest {

    @Test
    fun `every preset runs easiest to hardest with no duplicates`() {
        for (drivetrain in Drivetrain.ALL) {
            val gears = drivetrain.gears
            assertTrue(gears.size >= 8, "${drivetrain.name} only has ${gears.size} gears")
            for (i in 1 until gears.size) {
                val step = gears[i].developmentMeters / gears[i - 1].developmentMeters
                assertTrue(
                    step > 1.0 + Drivetrain.DUPLICATE_RATIO_TOLERANCE,
                    "${drivetrain.name} gear $i is only ${step}x the one below it",
                )
            }
        }
    }

    @Test
    fun `a double chainring collapses its duplicate ratios`() {
        // 2 rings x 12 sprockets is 24 combinations and nothing like 24 useful
        // gears: the small ring's top overlaps most of the big ring's bottom.
        val combinations = Drivetrain.ROAD.chainrings.size * Drivetrain.ROAD.sprockets.size
        assertEquals(24, combinations)
        assertTrue(
            Drivetrain.ROAD.gearCount < combinations,
            "no duplicates were collapsed: ${Drivetrain.ROAD.gearCount} of $combinations",
        )
    }

    @Test
    fun `presets cover the range their bikes really have`() {
        // Easiest: a bail-out gear you can turn up a wall.
        // Hardest: something you can only spin out on a descent.
        assertTrue(Drivetrain.ROAD.gears.first().ratio in 0.9..1.2)
        assertTrue(Drivetrain.ROAD.gears.last().ratio in 4.0..5.0)

        // A 1x mountain bike is far lower geared at both ends than a road bike.
        assertTrue(
            Drivetrain.MOUNTAIN.gears.first().developmentMeters <
                Drivetrain.ROAD.gears.first().developmentMeters,
            "an MTB should climb better than a road bike",
        )
        assertTrue(
            Drivetrain.MOUNTAIN.gears.last().developmentMeters <
                Drivetrain.ROAD.gears.last().developmentMeters,
            "an MTB should run out of gears before a road bike does",
        )

        // Gravel sits between the two at the top end.
        assertTrue(
            Drivetrain.GRAVEL.gears.last().developmentMeters in
                Drivetrain.MOUNTAIN.gears.last().developmentMeters..
                Drivetrain.ROAD.gears.last().developmentMeters
        )
    }

    @Test
    fun `the reference gear carries about the road's own force`() {
        // Nearest gear rather than an exact one: the assumed real gear is a
        // fixed development, and where it lands in a given cassette is
        // whatever that cassette's teeth allow.
        for (drivetrain in Drivetrain.ALL) {
            val factor = drivetrain.loadFactor(drivetrain.referenceIndex)
            assertTrue(
                abs(factor - 1.0) < 0.12,
                "${drivetrain.name} reference gear carries ${factor}x the road",
            )
        }
    }

    @Test
    fun `presets are measured against each other, not each against itself`() {
        // A mountain bike genuinely has less at the top and more at the bottom
        // than a road bike. That only shows up because both are ratios against
        // the same assumed real gear.
        val road = Drivetrain.ROAD
        val mtb = Drivetrain.MOUNTAIN
        assertTrue(
            mtb.loadFactor(mtb.gearCount - 1) < road.loadFactor(road.gearCount - 1),
            "an MTB should run out of top gear before a road bike",
        )
        assertTrue(
            mtb.loadFactor(0) < road.loadFactor(0),
            "an MTB should have a lower bail-out gear than a road bike",
        )
    }

    @Test
    fun `the top gear has real headroom over the reference`() {
        // The whole point of the reference sitting low in the ladder: a rider
        // on a descent has to have something left to push against, or they
        // spin out against nothing.
        for (drivetrain in Drivetrain.ALL) {
            val top = drivetrain.loadFactor(drivetrain.gearCount - 1)
            assertTrue(
                top >= 2.0,
                "${drivetrain.name} top gear is only ${top}x the road",
            )
        }
    }

    @Test
    fun `the easiest gear really does flatten a climb`() {
        for (drivetrain in Drivetrain.ALL) {
            val bottom = drivetrain.loadFactor(0)
            assertTrue(bottom < 0.75, "${drivetrain.name} easiest gear is ${bottom}x the road")
        }
    }

    @Test
    fun `load factors rise monotonically and stay inside the guard rails`() {
        for (drivetrain in Drivetrain.ALL) {
            var previous = 0.0
            for (i in 0 until drivetrain.gearCount) {
                val factor = drivetrain.loadFactor(i)
                assertTrue(factor > previous, "${drivetrain.name} gear $i did not get harder")
                assertTrue(
                    factor in Drivetrain.MIN_LOAD_FACTOR..Drivetrain.MAX_LOAD_FACTOR,
                    "${drivetrain.name} gear $i asked for ${factor}x",
                )
                previous = factor
            }
        }
    }

    @Test
    fun `indices off the ends clamp rather than wrap`() {
        val d = Drivetrain.ROAD
        assertEquals(0, d.clampIndex(-9))
        assertEquals(d.gearCount - 1, d.clampIndex(999))
        assertEquals(d.developmentMeters(0), d.developmentMeters(-3))
    }

    @Test
    fun `cadence and speed are inverses of each other`() {
        val d = Drivetrain.ROAD
        val gear = d.gearCount - 4
        val speed = 9.0
        val rpm = d.cadenceRpmFor(speed, gear)
        assertTrue(abs(d.speedMpsFor(rpm, gear) - speed) < 0.2)
    }

    @Test
    fun `a harder gear asks for a lower cadence at the same speed`() {
        val d = Drivetrain.ROAD
        assertTrue(d.cadenceRpmFor(8.0, d.gearCount - 1) < d.cadenceRpmFor(8.0, 0))
    }

    @Test
    fun `the opening gear is one you can pull away in`() {
        for (drivetrain in Drivetrain.ALL) {
            val rpm = drivetrain.cadenceRpmFor(20.0 / 3.6, drivetrain.startIndex)
            assertTrue(rpm in 55..110, "${drivetrain.name} opens asking $rpm rpm at 20 km/h")
        }
    }

    @Test
    fun `a stationary bike asks for no cadence`() {
        assertEquals(0, Drivetrain.ROAD.cadenceRpmFor(0.0, 5))
        assertEquals(0.0, Drivetrain.ROAD.speedMpsFor(0, 5))
    }

    @Test
    fun `ids round-trip and unknown ones fall back rather than failing`() {
        for (drivetrain in Drivetrain.ALL) {
            assertEquals(drivetrain, Drivetrain.byId(drivetrain.id))
        }
        assertEquals(Drivetrain.DEFAULT, Drivetrain.byId("something-from-a-newer-build"))
        assertEquals(Drivetrain.DEFAULT, Drivetrain.byId(null))
    }

    @Test
    fun `gears are labelled from one and name their teeth`() {
        val d = Drivetrain.ROAD
        assertEquals(1, d.label(0))
        assertEquals(d.gearCount, d.label(d.gearCount - 1))
        val hardest = d.gearAt(d.gearCount - 1)
        assertEquals("${hardest.chainring} x ${hardest.sprocket}", hardest.label)
    }
}
