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

import kotlin.math.abs
import kotlin.math.pow

/** One rideable gear: a chainring, a sprocket, and the ground they cover. */
data class VirtualGear(
    val chainring: Int,
    val sprocket: Int,
    /** Metres travelled per crank revolution. */
    val developmentMeters: Double,
) {
    /** Chainring over sprocket, the number stamped on a gear chart. */
    val ratio: Double get() = chainring.toDouble() / sprocket.toDouble()

    /** "50 x 15", for the HUD. */
    val label: String get() = "$chainring x $sprocket"
}

/**
 * The bike the rider is pretending to be on.
 *
 * Real chainrings and real sprockets rather than an abstract ladder, because
 * the whole point of a virtual drivetrain is that it should ask the same things
 * of the rider that their own bike would. A 1x MTB running out of gears at
 * 40 km/h and a road double spinning a 52x11 down a descent are both correct
 * behaviour, and neither falls out of a made-up geometric range.
 *
 * ## What a gear does
 *
 * A gear does not create speed. Speed comes from power and the road; the gear
 * decides how that power is split between force at the pedals and cadence. So a
 * virtual gear is modelled as one number, [loadFactor]: how much of the road's
 * force the trainer should push back with, relative to the gear the rider's
 * real bike is actually in.
 *
 * That last part is the subtle bit, and it is why [ASSUMED_REAL_DEVELOPMENT_M]
 * exists. The rider's real chain sits on one real sprocket for the whole ride,
 * and the trainer's resistance reaches their legs through *that* ratio. A
 * virtual gear is therefore only ever meaningful as a ratio against it:
 *
 *     force at the trainer = road force x (virtual development / real development)
 *
 * The real development is a property of the rider's bike, not of the drivetrain
 * they chose to simulate, so it is one constant shared by every preset rather
 * than a position in each preset's ladder. That is what makes the presets mean
 * something against each other: picking the mountain bike really does give less
 * at the top and more at the bottom than the road bike, because both are
 * measured against the same real gear, exactly as they would be if the rider
 * owned both bikes.
 *
 * ## Duplicate ratios
 *
 * A 2x drivetrain has fifty-odd chainring and sprocket combinations and maybe
 * twenty distinct ratios. Shifting through near-identical gears feels broken,
 * so combinations within [DUPLICATE_RATIO_TOLERANCE] of one already in the
 * ladder are dropped, keeping the one with the larger chainring (the one a
 * rider would actually choose, for chainline).
 */
data class Drivetrain(
    val id: String,
    val name: String,
    /** One line for the settings screen, e.g. "50/34 with an 11-32 cassette". */
    val summary: String,
    val chainrings: List<Int>,
    val sprockets: List<Int>,
    /** Rolling circumference of the rear wheel with its tyre, in millimetres. */
    val wheelCircumferenceMm: Int,
) {
    /** Every distinct gear, easiest first. */
    val gears: List<VirtualGear> = buildGears(chainrings, sprockets, wheelCircumferenceMm)

    val gearCount: Int get() = gears.size

    /**
     * The gear closest to carrying the road's own force, i.e. whose
     * [loadFactor] is nearest 1. Derived rather than chosen: it is wherever
     * [ASSUMED_REAL_DEVELOPMENT_M] happens to fall in this drivetrain's ladder,
     * which is low on a road bike and higher on a mountain bike.
     */
    val referenceIndex: Int = gears
        .indices
        .minByOrNull { abs(gears[it].developmentMeters - ASSUMED_REAL_DEVELOPMENT_M) }
        ?: 0

    fun clampIndex(index: Int): Int = index.coerceIn(0, (gears.size - 1).coerceAtLeast(0))

    fun gearAt(index: Int): VirtualGear =
        gears.getOrElse(clampIndex(index)) { VirtualGear(1, 1, 1.0) }

    /** Metres per crank revolution in gear [index]. */
    fun developmentMeters(index: Int): Double = gearAt(index).developmentMeters

    /**
     * How much of the road's force gear [index] asks the rider to carry, as a
     * multiple of the assumed real gear. Clamped so that neither end of an
     * unusually wide drivetrain can command a resistance a trainer will not
     * track.
     */
    fun loadFactor(index: Int): Double =
        (developmentMeters(index) / ASSUMED_REAL_DEVELOPMENT_M)
            .pow(GEAR_AUTHORITY)
            .coerceIn(MIN_LOAD_FACTOR, MAX_LOAD_FACTOR)

    /**
     * The cadence gear [index] asks for at [speedMps]. Shown next to the
     * rider's actual cadence, because a gear number means nothing on its own
     * and "this gear wants 78 rpm at the speed you're doing" means everything.
     */
    fun cadenceRpmFor(speedMps: Double, index: Int): Int {
        if (speedMps <= 0.0) return 0
        return (speedMps / developmentMeters(index) * 60.0).toInt().coerceIn(0, 250)
    }

    /** Speed gear [index] produces at [cadenceRpm], the inverse of [cadenceRpmFor]. */
    fun speedMpsFor(cadenceRpm: Int, index: Int): Double =
        if (cadenceRpm <= 0) 0.0 else cadenceRpm / 60.0 * developmentMeters(index)

    /** One-based, like a cassette is counted. */
    fun label(index: Int): Int = clampIndex(index) + 1

    /**
     * A sensible gear to pull away in.
     *
     * Chosen by cadence rather than by position in the ladder, because
     * position does not survive changing bikes: the gear four up from the
     * bottom is a fine opener on a road bike and a spun-out 130 rpm on a
     * mountain bike. The gear that wants [START_CADENCE_RPM] at
     * [START_SPEED_MPS] is the right opener on all three.
     */
    val startIndex: Int = gears
        .indices
        .minByOrNull { abs(cadenceRpmFor(START_SPEED_MPS, it) - START_CADENCE_RPM) }
        ?: 0

    companion object {
        /**
         * The gear the rider's own bike is assumed to be in on the trainer,
         * in metres per crank revolution. Roughly a 34x21, or a 50x30.
         *
         * Deliberately low, and that choice is doing real work. Every virtual
         * gear's resistance is a ratio against this, so setting it high leaves
         * the top of the range with nothing to give and the rider spinning out
         * against air on every descent. Setting it low costs nothing by
         * comparison: the easy gears are still easy, and there is headroom at
         * the top where it is needed.
         *
         * This is the one number to expose if riders ever want to tell the app
         * which gear their bike is actually in, and it is the largest
         * remaining guess in the model. It can be measured rather than assumed: the rider's
         * power, their cadence and the force last commanded give
         * `development = P / (F . cadence)` directly, which folds in whatever
         * mass the trainer applies a grade against as well. Until that lands,
         * a rider whose real gear is taller than this simply rides further
         * down the virtual ladder than the numbers suggest.
         *
         * [Freewheel] already measures the same ratio for itself, from cadence
         * and the world's speed while the rider rides steadily, because it has
         * to know when legs are really behind the wheel. It is not fed back
         * into the gears.
         */
        const val ASSUMED_REAL_DEVELOPMENT_M = 3.35

        /** The speed the opening gear is chosen for: an easy rolling pace. */
        const val START_SPEED_MPS = 6.1

        /** And the cadence it should want there. */
        const val START_CADENCE_RPM = 85

        /** Ratios closer than this to one already in the ladder are dropped. */
        const val DUPLICATE_RATIO_TOLERANCE = 0.025

        /**
         * Bounds on the load multiplier. A guard rather than a design
         * constraint: they exist so that an unusually wide drivetrain cannot
         * quietly command a trainer several times a steep climb, which is a
         * resistance that is neither rideable nor, on some machines, accepted.
         */
        const val MIN_LOAD_FACTOR = 0.20
        const val MAX_LOAD_FACTOR = 5.00

        /**
         * How hard a shift bites, as an exponent on the gear's ratio against
         * the rider's real one.
         *
         * At 1.0 the model is exactly faithful to a bike: a gear is leverage,
         * and at the instant of a shift the pedals feel the road through the
         * new ratio and nothing else. That is the same on the flat and on a
         * climb. What differs by terrain is what the rider does next.
         *
         * It has been both 1.35 and, in effect, 3: first inflated because a
         * shift felt like nothing, then replaced by evaluating the road at the
         * speed the new gear would turn out at the old cadence, which cubes
         * the ratio on the flat. Neither was the physics, and the second made
         * one sprocket at speed ask for three times what a bicycle does. A
         * shift that reads as too little is a latency problem - the write, or
         * the trainer's own smoothing of a grade - and
         * [VirtualDrivetrain.SHIFT_RELEASE_SECONDS] is where a downshift is
         * helped past that, not here.
         *
         * Left as a constant rather than deleted because it is the one knob
         * worth having if the ladder ever needs widening, but anything other
         * than 1.0 is a thumb on the scale.
         */
        const val GEAR_AUTHORITY = 1.0

        /**
         * A modern endurance road bike: compact double, wide-range cassette.
         * The default, and the setup most riders on a trainer are sitting on.
         */
        val ROAD = Drivetrain(
            id = "road",
            name = "Road",
            summary = "50/34 compact with an 11-32 cassette, 700x25",
            chainrings = listOf(50, 34),
            sprockets = listOf(11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32),
            wheelCircumferenceMm = 2105,
        )

        /**
         * A 1x gravel bike: one ring, a wide cassette, fatter and taller tyres.
         * The cassette is SRAM's XPLR 10-44, sprocket for sprocket.
         */
        val GRAVEL = Drivetrain(
            id = "gravel",
            name = "Gravel 1x",
            summary = "40T single ring with a 10-44 cassette, 700x40",
            chainrings = listOf(40),
            sprockets = listOf(10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44),
            wheelCircumferenceMm = 2200,
        )

        /**
         * A modern 1x trail bike: small ring, enormous cassette, 29in wheels.
         * The cassette is SRAM's Eagle 10-52, sprocket for sprocket.
         */
        val MOUNTAIN = Drivetrain(
            id = "mtb",
            name = "Mountain 1x",
            summary = "32T single ring with a 10-52 cassette, 29in",
            chainrings = listOf(32),
            sprockets = listOf(10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 52),
            wheelCircumferenceMm = 2295,
        )

        /** Everything the settings screen offers, in the order it offers them. */
        val ALL: List<Drivetrain> = listOf(ROAD, GRAVEL, MOUNTAIN)

        val DEFAULT: Drivetrain = ROAD

        /** Look one up by [Drivetrain.id], falling back to [DEFAULT]. */
        fun byId(id: String?): Drivetrain = ALL.find { it.id == id } ?: DEFAULT

        // Bounds on a drivetrain somebody else describes ───────────────────

        /**
         * Teeth counts that exist on real bicycles, give or take.
         *
         * Deliberately wider than any catalogue: the point is to refuse
         * nonsense, not to have an opinion about the rider's bike. A 20-tooth
         * inner ring and a 70-tooth outer are both absurd on the same bike and
         * both real on some bike, so both are allowed. What is not allowed is
         * a zero, a negative, or a number that would put a rider in a gear no
         * chain could reach.
         */
        const val MIN_CHAINRING_TEETH = 20
        const val MAX_CHAINRING_TEETH = 70
        const val MIN_SPROCKET_TEETH = 9
        const val MAX_SPROCKET_TEETH = 60

        /** 1x, 2x, 3x. There is no 4x, and there is no 0x. */
        const val MAX_CHAINRINGS = 3

        /** A single-speed is one sprocket, and a real bike. Beyond 16 is not. */
        const val MAX_SPROCKETS = 16

        /**
         * Rolling circumference bounds, in millimetres. A 20-inch wheel is
         * about 1 500 and a fat-bike 29er about 2 400, so this brackets
         * everything with a tyre on it.
         */
        const val MIN_WHEEL_CIRCUMFERENCE_MM = 900
        const val MAX_WHEEL_CIRCUMFERENCE_MM = 2_500

        /**
         * Why a described drivetrain cannot be ridden, or null if it can.
         *
         * Returns a sentence rather than a boolean because the sentence is the
         * whole value: it goes straight into the warning the companion
         * receives, and "sprocket 0 is not a sprocket" saves an afternoon that
         * "invalid drivetrain" does not.
         */
        fun rejectionReason(
            chainrings: List<Int>,
            sprockets: List<Int>,
            wheelCircumferenceMm: Int,
        ): String? {
            if (chainrings.isEmpty()) return "A bike needs at least one chainring."
            if (chainrings.size > MAX_CHAINRINGS) {
                return "There is no " + chainrings.size + "x drivetrain; the most is " +
                    MAX_CHAINRINGS + "x."
            }
            if (sprockets.isEmpty()) return "A bike needs at least one sprocket."
            if (sprockets.size > MAX_SPROCKETS) {
                return "That is " + sprockets.size + " sprockets; the most is " +
                    MAX_SPROCKETS + "."
            }
            chainrings.firstOrNull { it < MIN_CHAINRING_TEETH || it > MAX_CHAINRING_TEETH }
                ?.let {
                    return "Chainring of " + it + " teeth is outside " +
                        MIN_CHAINRING_TEETH + " to " + MAX_CHAINRING_TEETH + "."
                }
            sprockets.firstOrNull { it < MIN_SPROCKET_TEETH || it > MAX_SPROCKET_TEETH }
                ?.let {
                    return "Sprocket of " + it + " teeth is outside " +
                        MIN_SPROCKET_TEETH + " to " + MAX_SPROCKET_TEETH + "."
                }
            if (wheelCircumferenceMm < MIN_WHEEL_CIRCUMFERENCE_MM ||
                wheelCircumferenceMm > MAX_WHEEL_CIRCUMFERENCE_MM
            ) {
                return "Wheel circumference of " + wheelCircumferenceMm + " mm is outside " +
                    MIN_WHEEL_CIRCUMFERENCE_MM + " to " + MAX_WHEEL_CIRCUMFERENCE_MM + "."
            }
            return null
        }

        /**
         * A drivetrain somebody else described, with a summary written for it.
         *
         * The caller is expected to have run [rejectionReason] first; this
         * clamps rather than refuses, so a spec that slipped through cannot
         * produce a ladder nobody could ride.
         *
         * Not added to [ALL] and never persisted. It belongs to the ride that
         * asked for it, and the rider's own setting is waiting untouched
         * underneath.
         */
        fun custom(
            name: String?,
            chainrings: List<Int>,
            sprockets: List<Int>,
            wheelCircumferenceMm: Int = ROAD.wheelCircumferenceMm,
        ): Drivetrain {
            val rings = chainrings
                .map { it.coerceIn(MIN_CHAINRING_TEETH, MAX_CHAINRING_TEETH) }
                .distinct()
                .take(MAX_CHAINRINGS)
                .sortedDescending()
            val cogs = sprockets
                .map { it.coerceIn(MIN_SPROCKET_TEETH, MAX_SPROCKET_TEETH) }
                .distinct()
                .take(MAX_SPROCKETS)
                .sorted()
            val circumference = wheelCircumferenceMm
                .coerceIn(MIN_WHEEL_CIRCUMFERENCE_MM, MAX_WHEEL_CIRCUMFERENCE_MM)
            return Drivetrain(
                id = CUSTOM_ID,
                name = name?.takeIf { it.isNotBlank() } ?: describe(rings, cogs),
                summary = describe(rings, cogs) + ", " + circumference + " mm",
                chainrings = rings,
                sprockets = cogs,
                wheelCircumferenceMm = circumference,
            )
        }

        /** "2x, 50/34 with 11-32", which is how a rider would say it. */
        private fun describe(chainrings: List<Int>, sprockets: List<Int>): String {
            val rings = chainrings.joinToString("/")
            val cogs = if (sprockets.size < 2) {
                sprockets.firstOrNull()?.toString() ?: "?"
            } else {
                sprockets.first().toString() + "-" + sprockets.last()
            }
            return chainrings.size.toString() + "x, " + rings + " with " + cogs
        }

        /** Marks a drivetrain a companion described rather than one we ship. */
        const val CUSTOM_ID = "custom"

        private fun buildGears(
            chainrings: List<Int>,
            sprockets: List<Int>,
            wheelCircumferenceMm: Int,
        ): List<VirtualGear> {
            val circumference = wheelCircumferenceMm / 1_000.0
            val all = chainrings
                .flatMap { ring -> sprockets.map { cog -> VirtualGear(ring, cog, ring.toDouble() / cog * circumference) } }
                // Easiest first, and where two combinations tie, the bigger
                // ring first so the dedupe below keeps it.
                .sortedWith(compareBy({ it.developmentMeters }, { -it.chainring }))

            val kept = ArrayList<VirtualGear>(all.size)
            for (gear in all) {
                val previous = kept.lastOrNull()
                if (previous != null &&
                    gear.developmentMeters <= previous.developmentMeters * (1.0 + DUPLICATE_RATIO_TOLERANCE)
                ) {
                    continue
                }
                kept.add(gear)
            }
            return kept
        }
    }
}
