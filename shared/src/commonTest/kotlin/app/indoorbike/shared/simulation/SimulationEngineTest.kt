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
package app.indoorbike.shared.simulation

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.route.RawRoutePoint
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.routeTrackOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The closed loop, driven with a clock of the test's own: watts in, ground
 * covered out, and a road handed back to the trainer.
 */
class SimulationEngineTest {

    private class Harness(
        var power: Int = 0,
        /**
         * Held rather than derived, and that is the point: with the cadence
         * pinned, the gear and the road are the only things left that can move
         * the commanded force, so a test can attribute what it sees.
         */
        var cadence: Int = 0,
        val riderKg: Float = 75f,
        val drivetrain: Drivetrain = Drivetrain.ROAD,
    ) {
        val roads = mutableListOf<SimulationParameters>()
        val engine = SimulationEngine(
            powerWatts = { power },
            cadenceRpm = { cadence },
            applyRoad = { roads.add(it) },
            riderMassKg = { riderKg },
            bikeMassKg = { 9f },
            drivetrainProvider = { drivetrain },
        )

        /** Ride for [seconds] at [power], in the loop's own quarter-second steps. */
        fun ride(seconds: Int) {
            repeat(seconds * 4) { engine.advance(0.25) }
        }
    }

    private fun road(
        lengthMeters: Double,
        elevationAt: (Double) -> Double,
    ): RouteTrack {
        val points = ArrayList<RawRoutePoint>()
        var d = 0.0
        while (d <= lengthMeters) {
            points.add(RawRoutePoint(60.0 + d / 111_320.0, 24.9, elevationAt(d)))
            d += 10.0
        }
        return routeTrackOf("test", "Test Road", points)
    }

    private fun flatRoad(lengthMeters: Double) = road(lengthMeters) { 0.0 }

    @Test
    fun `watts turn into ground covered`() {
        val h = Harness(power = 200)
        h.engine.load(flatRoad(5_000.0))
        h.ride(seconds = 60)

        val distance = h.engine.distanceMeters.value
        // A minute at 200 W on the flat is roughly half a kilometre, less the
        // acceleration from a standstill.
        assertTrue(distance in 350.0..600.0, "covered $distance m in a minute")
        assertTrue(h.engine.speedMps.value > 7.0, "settled at ${h.engine.speedMps.value} m/s")
    }

    @Test
    fun `no effort, no progress`() {
        val h = Harness(power = 0)
        h.engine.load(flatRoad(5_000.0))
        h.ride(seconds = 30)
        assertEquals(0.0, h.engine.distanceMeters.value)
        assertEquals(0.0, h.engine.speedMps.value)
    }

    @Test
    fun `the same effort covers less ground uphill`() {
        val flat = Harness(power = 220).also { it.engine.load(flatRoad(5_000.0)) }
        val hill = Harness(power = 220).also { it.engine.load(road(5_000.0) { d -> d * 0.07 }) }
        flat.ride(seconds = 120)
        hill.ride(seconds = 120)

        assertTrue(
            hill.engine.distanceMeters.value < flat.engine.distanceMeters.value / 2,
            "hill ${hill.engine.distanceMeters.value} m vs flat ${flat.engine.distanceMeters.value} m",
        )
    }

    @Test
    fun `the gradient under the rider follows the road`() {
        val h = Harness(power = 250)
        // Flat for 500 m, then a 6 % ramp.
        h.engine.load(road(3_000.0) { d -> if (d <= 500.0) 0.0 else (d - 500.0) * 0.06 })
        h.ride(seconds = 10)
        assertTrue(abs(h.engine.gradient.value) < 0.02, "should still be flat")

        h.ride(seconds = 120)
        assertTrue(h.engine.distanceMeters.value > 700.0, "should be on the ramp by now")
        assertTrue(h.engine.gradient.value > 0.04, "gradient ${h.engine.gradient.value} on a 6 % ramp")
    }

    @Test
    fun `climbing is counted as the rider passes`() {
        val h = Harness(power = 260)
        h.engine.load(road(2_000.0) { d -> d * 0.05 })
        h.ride(seconds = 180)

        val expected = h.engine.distanceMeters.value * 0.05
        val climbed = h.engine.elevationGainMeters.value
        assertTrue(
            abs(climbed - expected) < expected * 0.2 + 3.0,
            "climbed $climbed m over ${h.engine.distanceMeters.value} m of 5 %",
        )
    }

    @Test
    fun `reaching the end finishes the ride once`() {
        var finishes = 0
        val h = Harness(power = 300)
        h.engine.onRouteFinished = { finishes++ }
        h.engine.load(flatRoad(300.0))
        h.ride(seconds = 120)

        assertTrue(h.engine.finished.value)
        assertEquals(1, finishes)
        assertEquals(300.0, h.engine.distanceMeters.value, absoluteTolerance = 1.5)

        // Further ticks must not re-fire it or push the rider past the finish.
        h.ride(seconds = 30)
        assertEquals(1, finishes)
    }

    @Test
    fun `a resumed ride picks up where it stopped`() {
        val h = Harness(power = 200)
        h.engine.load(
            route = flatRoad(5_000.0),
            startDistanceMeters = 2_400.0,
            gearIndex = 12,
            elevationGainMeters = 88.0,
        )
        assertEquals(2_400.0, h.engine.distanceMeters.value)
        assertEquals(12, h.engine.gearIndex.value)
        assertEquals(88.0, h.engine.elevationGainMeters.value)

        h.ride(seconds = 10)
        assertTrue(h.engine.distanceMeters.value > 2_400.0)
    }

    @Test
    fun `the road reaches the trainer as a pure force`() {
        val h = Harness(power = 250)
        h.engine.load(road(3_000.0) { d -> d * 0.06 })
        h.ride(seconds = 1)

        val first = assertNotNull(h.roads.firstOrNull(), "nothing was sent to the trainer")
        assertTrue(first.gradePercent > 0.0, "sent ${first.gradePercent} % for a 6 % climb")
        assertEquals(0.0, first.windSpeedMps)
        // Everything the rider should feel is in the grade. The other two terms
        // would be applied against the trainer's own flywheel speed, which
        // belongs to the real gear rather than the virtual one.
        assertEquals(0.0, first.rollingResistance)
        assertEquals(0.0, first.windResistanceKgPerM)
    }

    @Test
    fun `shifting on the flat changes what the rider feels`() {
        // The bug this whole model exists to fix: with the gradient passed
        // straight through, a shift on flat ground scaled zero and the rider
        // felt nothing at all.
        val h = Harness(power = 200)
        h.engine.load(flatRoad(5_000.0), gearIndex = h.drivetrain.referenceIndex)
        h.ride(seconds = 60)

        val reference = h.engine.trainerForceNewtons(h.drivetrain.referenceIndex)
        val easiest = h.engine.trainerForceNewtons(0)
        val hardest = h.engine.trainerForceNewtons(h.drivetrain.gearCount - 1)

        assertTrue(reference > 5.0, "flat ground should still cost something: $reference N")
        assertTrue(
            hardest > reference * 1.8,
            "top gear on the flat is only $hardest N against $reference N",
        )
        assertTrue(
            easiest < reference * 0.75,
            "bottom gear on the flat is $easiest N against $reference N",
        )
    }

    @Test
    fun `a descent still leaves something to push against`() {
        val h = Harness(power = 0)
        h.engine.load(road(3_000.0) { d -> -d * 0.02 })
        h.ride(seconds = 45)

        val easiest = h.engine.trainerForceNewtons(0)
        val hardest = h.engine.trainerForceNewtons(h.drivetrain.gearCount - 1)
        assertTrue(easiest > 0.0, "the pedals went completely dead on a descent")
        assertTrue(
            hardest > easiest * 2.0,
            "choosing a big gear on a descent should give more to push against",
        )
    }

    @Test
    fun `every downshift is felt, even arriving at a climb off a fast descent`() {
        // The case that exposed the hard ceiling: 50 km/h onto a climb in a big
        // gear stacks gravity, a large aerodynamic term and the gear multiplier
        // into a force far past anything a trainer applies. Clipped flat, the
        // first six downshifts commanded exactly the same thing, so the rider
        // worked the shifter in the one moment they most needed it to respond
        // and felt nothing at all.
        val h = Harness(power = 300)
        h.engine.load(road(4_000.0) { d -> d * 0.08 })
        // Carry real speed into the climb rather than starting from a standstill.
        h.engine.advance(0.25)
        repeat(200) { h.engine.advance(0.25) }

        val top = h.drivetrain.gearCount - 1
        val forces = (top downTo top - 6).map { h.engine.trainerForceNewtons(it) }
        for (i in 1 until forces.size) {
            assertTrue(
                forces[i] < forces[i - 1] - 1.0,
                "shifting from gear ${top - i + 1} to ${top - i} changed the force by only " +
                    "${forces[i - 1] - forces[i]} N (${forces.joinToString()})",
            )
        }
    }

    @Test
    fun `the soft ceiling leaves ordinary riding untouched`() {
        // Compression must only engage where the alternative was clipping.
        // Everything below the knee has to come through exactly as computed.
        val h = Harness(power = 200)
        h.engine.load(flatRoad(5_000.0))
        h.ride(seconds = 60)

        val force = h.engine.trainerForceNewtons(h.drivetrain.referenceIndex)
        assertTrue(
            force < VirtualDrivetrain.SOFT_KNEE_NEWTONS,
            "flat-road riding should be nowhere near the knee: $force N",
        )
    }

    @Test
    fun `the commanded force never exceeds what a trainer will take`() {
        // A wall, in the biggest gear, with a heavy rider.
        val h = Harness(power = 400, riderKg = 120f)
        h.engine.load(road(3_000.0) { d -> d * 0.30 })
        h.ride(seconds = 30)
        val force = h.engine.trainerForceNewtons(h.drivetrain.gearCount - 1)
        assertTrue(
            force <= VirtualDrivetrain.MAX_TRAINER_FORCE_NEWTONS,
            "commanded $force N",
        )
    }

    @Test
    fun `a harder gear commands a harder road`() {
        fun roadAtGear(gear: Int): SimulationParameters {
            val h = Harness(power = 250)
            h.engine.load(road(3_000.0) { d -> d * 0.06 }, gearIndex = gear)
            h.ride(seconds = 1)
            return assertNotNull(h.roads.firstOrNull())
        }

        val easy = roadAtGear(0)
        val reference = roadAtGear(Drivetrain.ROAD.referenceIndex)
        val hard = roadAtGear(Drivetrain.ROAD.gearCount - 1)

        assertTrue(
            easy.gradePercent < reference.gradePercent,
            "an easier gear should flatten the hill: ${easy.gradePercent} vs ${reference.gradePercent}",
        )
        assertTrue(
            hard.gradePercent > reference.gradePercent,
            "a harder gear should steepen it: ${hard.gradePercent} vs ${reference.gradePercent}",
        )
    }

    @Test
    fun `the drivetrain is fixed for the ride it was loaded with`() {
        val h = Harness(power = 200, drivetrain = Drivetrain.MOUNTAIN)
        h.engine.load(flatRoad(2_000.0))
        assertEquals(Drivetrain.MOUNTAIN, h.engine.drivetrain.value)
        assertEquals(Drivetrain.MOUNTAIN.startIndex, h.engine.gearIndex.value)
    }

    @Test
    fun `a heavier rider feels more of the same hill`() {
        fun roadForRider(kg: Float): SimulationParameters {
            val h = Harness(power = 250, riderKg = kg)
            h.engine.load(road(3_000.0) { d -> d * 0.06 })
            h.ride(seconds = 1)
            return assertNotNull(h.roads.firstOrNull())
        }
        assertTrue(roadForRider(100f).gradePercent > roadForRider(60f).gradePercent)
    }

    @Test
    fun `shifting stays inside the ladder`() {
        val h = Harness(power = 0)
        h.engine.load(flatRoad(1_000.0), gearIndex = 0)
        repeat(5) { h.engine.shiftDown() }
        assertEquals(0, h.engine.gearIndex.value)

        repeat(h.drivetrain.gearCount + 10) { h.engine.shiftUp() }
        assertEquals(h.drivetrain.gearCount - 1, h.engine.gearIndex.value)
    }

    @Test
    fun `shifting without a route does nothing`() {
        val h = Harness(power = 0)
        val before = h.engine.gearIndex.value
        h.engine.shiftUp()
        assertEquals(before, h.engine.gearIndex.value)
    }

    @Test
    fun `clearing forgets the route`() {
        val h = Harness(power = 200)
        h.engine.load(flatRoad(2_000.0))
        h.ride(seconds = 20)
        assertTrue(h.engine.distanceMeters.value > 0.0)

        h.engine.clear()
        assertEquals(null, h.engine.route.value)
        assertEquals(0.0, h.engine.distanceMeters.value)
        assertEquals(0.0, h.engine.speedMps.value)

        // And a tick with no route is a no-op rather than a crash.
        h.ride(seconds = 5)
        assertEquals(0.0, h.engine.distanceMeters.value)
    }

    @Test
    fun `a rider with no weight set doesn't move`() {
        // The road model needs a mass. Rather than guess one, the ride stays
        // put, which is the same answer VirtualSpeed gives on the other HUDs.
        val h = Harness(power = 250, riderKg = 0f)
        h.engine.load(flatRoad(2_000.0))
        h.ride(seconds = 30)
        assertEquals(0.0, h.engine.distanceMeters.value)
    }

    @Test
    fun `the marker keeps up with the rider`() {
        val h = Harness(power = 220)
        h.engine.load(flatRoad(3_000.0))
        h.ride(seconds = 30)
        val position = assertNotNull(h.engine.position.value)
        assertEquals(h.engine.distanceMeters.value, position.distanceMeters, absoluteTolerance = 0.01)
        assertTrue(position.latitude > 60.0, "should have moved north up the road")
    }

    // The drivetrain ─────────────────────────────────────────────────────────

    /**
     * The behaviour this whole model was rebuilt for.
     *
     * A gear decides how fast the road goes past for a given cadence, so a
     * shift moves the speed the road force is evaluated at. On the flat, where
     * air is nearly all of the resistance, that lands as the square of the
     * ratio on top of the ratio itself. On a climb, where gravity is doing the
     * work and there is barely any air term to square, it lands as very little
     * more than the ratio. Riders know that difference by feel, and a model
     * that scales one number by a fixed exponent cannot produce it.
     */
    @Test
    fun `a shift bites harder at speed than it does on a climb`() {
        val gear = Drivetrain.ROAD.gearCount - 8

        val flat = Harness(power = 220, cadence = 90)
        flat.engine.load(flatRoad(20_000.0))
        flat.ride(seconds = 60)

        val climb = Harness(power = 220, cadence = 90)
        climb.engine.load(road(20_000.0) { d -> d * 0.06 })
        climb.ride(seconds = 60)

        fun drop(h: Harness) =
            1.0 - h.engine.trainerForceNewtons(gear - 1) / h.engine.trainerForceNewtons(gear)

        val onFlat = drop(flat)
        val onClimb = drop(climb)
        assertTrue(onFlat > 0.12, "one downshift at speed moved only ${onFlat * 100} %")
        assertTrue(
            onFlat > onClimb * 1.4,
            "the same shift moved ${onFlat * 100} % on the flat and " +
                "${onClimb * 100} % on a climb, which is not a gear changing",
        )
    }

    /**
     * The fourth force of the road load. A bike whose speed is changing weighs
     * something, and the trainer used never to say so: [RideDynamics.step]
     * integrated the acceleration term and then the commanded force threw it
     * away, so every acceleration was weightless and no crest handed anything
     * back.
     */
    @Test
    fun `accelerating costs more than holding the same speed`() {
        val h = Harness(power = 150, cadence = 90)
        h.engine.load(flatRoad(30_000.0))
        h.ride(seconds = 90)
        val gear = h.drivetrain.gearCount - 8
        val settled = h.engine.trainerForceNewtons(gear)

        // The gear and the cadence are pinned, so the road term cannot move.
        // Everything that shows up from here is the bike's own mass.
        h.power = 600
        h.ride(seconds = 2)
        val accelerating = h.engine.trainerForceNewtons(gear)
        assertTrue(
            accelerating > settled + 15.0,
            "a hard acceleration added only ${accelerating - settled} N",
        )

        h.power = 0
        h.ride(seconds = 3)
        val coasting = h.engine.trainerForceNewtons(gear)
        assertTrue(
            coasting < settled,
            "momentum handed nothing back: $coasting N against $settled N settled",
        )
    }

    @Test
    fun `a trainer that cannot count cadence rides the road it always did`() {
        val h = Harness(power = 200)
        h.engine.load(flatRoad(10_000.0))
        h.ride(seconds = 60)
        assertTrue(h.engine.speedMps.value > 5.0, "the test never got going")
        assertEquals(
            h.engine.speedMps.value,
            h.engine.driveSpeedMps.value,
            absoluteTolerance = 1e-9,
        )
    }

    /**
     * The claim the whole design rests on, driven end to end.
     *
     * Route mode integrates the world's speed out of power. The drivetrain
     * derives its own out of cadence and the gear. Nothing forces those two
     * numbers to agree, and if they did not, the rider would be resisted by
     * one road while being credited with another. They do agree, because both
     * are the same equation seen from opposite ends:
     *
     *     world:      P = F_road(v) . v      (integrated forward in time)
     *     drivetrain: P = F_cmd . cadence . real development
     *                   = F_road(v) . (virtual / real) . cadence . real
     *                   = F_road(v) . v
     *
     * So this closes the loop with a rider in it: someone holding their watts,
     * whose legs speed up or slow down to whatever cadence the commanded force
     * leaves them, with their real chain on the sprocket the model assumes.
     * Where they settle is the test.
     */
    @Test
    fun `a rider and the road they are on converge on one speed`() {
        val h = Harness(power = 220)
        h.engine.load(flatRoad(60_000.0))
        h.engine.shiftBy(h.drivetrain.gearCount - 8 - h.engine.gearIndex.value)

        var cadence = 60.0
        repeat(4 * 300) {
            val force = h.engine.trainerForceNewtons(h.engine.gearIndex.value)
            val wanted = if (force > 0.5) {
                h.power / (force * Drivetrain.ASSUMED_REAL_DEVELOPMENT_M) * 60.0
            } else {
                200.0
            }
            // Legs have mass too, so the rider eases towards the cadence the
            // resistance is asking for rather than teleporting onto it.
            cadence += (wanted.coerceIn(0.0, 200.0) - cadence) * 0.05
            h.cadence = cadence.toInt()
            h.engine.advance(0.25)
        }

        assertTrue(h.engine.speedMps.value > 8.0, "the rider never got up to speed")
        assertEquals(
            h.engine.speedMps.value,
            h.engine.driveSpeedMps.value,
            // Not exact: the world model bills the rider for drivetrain losses
            // and the drivetrain model does not, which is worth about 1 %.
            absoluteTolerance = 0.6,
        )
    }

    // Resuming ───────────────────────────────────────────────────────────────

    /**
     * Picking a route back up from the library is a *new* ride, and it starts
     * where the rider rejoins it. That is what the default is for.
     */
    @Test
    fun `a route picked up part way starts where it is picked up`() {
        val h = Harness(power = 200)
        h.engine.load(flatRoad(20_000.0), startDistanceMeters = 6_000.0, elevationGainMeters = 120.0)
        assertEquals(6_000.0, h.engine.startedAtMeters.value)
        assertEquals(120.0, h.engine.startedAtElevationGainMeters.value)
    }

    /**
     * Crash recovery is not that. The ride being restored is the same ride and
     * began wherever it began; letting the origin default to the crash point
     * quietly stops counting everything ridden before the process died.
     */
    @Test
    fun `a recovered ride keeps the origin it actually started from`() {
        val h = Harness(power = 200)
        h.engine.load(
            route = flatRoad(20_000.0),
            startDistanceMeters = 14_000.0,
            elevationGainMeters = 400.0,
            startedAtMeters = 6_000.0,
            startedAtElevationGainMeters = 120.0,
        )
        assertEquals(14_000.0, h.engine.distanceMeters.value)
        assertEquals(400.0, h.engine.elevationGainMeters.value)
        assertEquals(6_000.0, h.engine.startedAtMeters.value)
        assertEquals(120.0, h.engine.startedAtElevationGainMeters.value)
    }

    @Test
    fun `an origin ahead of where the ride is placed is refused`() {
        // Nonsense in, something rideable out: an origin past the current
        // position would report a negative distance for the ride.
        val h = Harness(power = 200)
        h.engine.load(
            route = flatRoad(20_000.0),
            startDistanceMeters = 3_000.0,
            startedAtMeters = 9_999.0,
        )
        assertEquals(3_000.0, h.engine.startedAtMeters.value)
    }
}
