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
package app.indoorbike.shared.ble

import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.VirtualDrivetrain
import kotlin.math.roundToInt

/**
 * Which targets a fitness machine accepts on its control point: the Target
 * Setting Features half of Fitness Machine Feature (0x2ACC).
 *
 * A smart trainer usually takes all three. A smart bike or a spin bike with a
 * motorised brake often takes only a resistance level, and a trainer can take
 * a wattage but not a road. Each gap is filled from what the machine does
 * have (see [TrainerControl]), so every ride mode works on every machine that
 * can be told anything at all.
 */
data class FtmsTargets(
    /** Set Target Power (0x05): ERG. */
    val power: Boolean,
    /** Set Target Resistance Level (0x04). */
    val resistance: Boolean,
    /** Set Indoor Bike Simulation Parameters (0x11): a road. */
    val simulation: Boolean,
) {
    companion object {
        /**
         * What a machine with a control point is taken to accept when it does
         * not say: the two commands this app sent every trainer before it
         * asked. A machine that turns out not to accept one says so when it is
         * sent, and is routed around from then on.
         */
        val ASSUMED = FtmsTargets(power = true, resistance = false, simulation = true)

        private const val RESISTANCE_BIT = 1 shl 2
        private const val POWER_BIT = 1 shl 3
        private const val SIMULATION_BIT = 1 shl 13

        /**
         * Fitness Machine Feature is two little-endian uint32s: the machine's
         * features, then its target-setting features. Only the second matters
         * here.
         */
        fun parse(bytes: ByteArray): FtmsTargets? {
            if (bytes.size < 8) return null
            val targets = (bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24)
            return FtmsTargets(
                power = targets and POWER_BIT != 0,
                resistance = targets and RESISTANCE_BIT != 0,
                simulation = targets and SIMULATION_BIT != 0,
            )
        }
    }
}

/**
 * The resistance levels a machine accepts, from Supported Resistance Level
 * Range (0x2AD6), in the machine's own unitless levels.
 *
 * Nothing here says what a level *means* in watts, and machines differ
 * wildly: 1 to 32 on one bike, 0 to 100 on the next, each notch a different
 * share of the rider's effort. That is why levels are only ever steered by
 * measuring the power they produce ([ResistanceHold]), never computed.
 */
data class ResistanceRange(val min: Double, val max: Double, val increment: Double) {
    companion object {
        /**
         * Three little-endian fields at 0.1 resolution: minimum and maximum as
         * sint16, the smallest step as uint16. A range that is empty or runs
         * backwards is rejected rather than steered into.
         */
        fun parse(bytes: ByteArray): ResistanceRange? {
            if (bytes.size < 6) return null
            fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            val min = u16(0).toShort() / 10.0
            val max = u16(2).toShort() / 10.0
            val increment = (u16(4) / 10.0).takeIf { it > 0.0 } ?: 0.1
            if (max <= min) return null
            return ResistanceRange(min, max, increment.coerceAtMost(max - min))
        }
    }
}

/**
 * Set Target Resistance Level (0x04).
 *
 * The specification's own tables disagree about this one: the command's
 * parameter is written as a single byte at 0.1 resolution, which cannot say
 * anything above 25.5, while the range the same machine reports is two bytes.
 * Machines went both ways. So the width starts from the range (anything that
 * cannot fit a byte must be two) and flips once if the machine rejects the
 * parameter; see [TrainerManager.handleControlPointResponse].
 */
object FtmsResistanceCodec {
    const val OP_SET_TARGET_RESISTANCE: Byte = 0x04

    /** Two bytes when the range reaches past what one byte at 0.1 can say. */
    fun startsWide(range: ResistanceRange): Boolean = range.max * 10.0 > 255.0

    fun encode(level: Double, wide: Boolean): ByteArray {
        val raw = (level * 10.0).roundToInt()
        return if (wide) {
            val v = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            byteArrayOf(OP_SET_TARGET_RESISTANCE, (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        } else {
            byteArrayOf(OP_SET_TARGET_RESISTANCE, raw.coerceIn(0, 255).toByte())
        }
    }
}

/** How one kind of command reaches a machine. */
enum class ControlPath {
    /** Sent as itself. */
    NATIVE,

    /** A road, held as the wattage it asks of the rider at their cadence ([RoadAsPower]). */
    VIA_POWER,

    /** Held by steering the resistance level against measured power ([ResistanceHold]). */
    VIA_RESISTANCE,

    /** Nothing the app can send changes this machine's resistance: the rider sets it. */
    MANUAL,
}

/**
 * What the connected trainer can be told, and so how each ride mode reaches
 * it.
 *
 * Two kinds of command come from the rides: a wattage (workouts, Just Ride, a
 * companion's `setPower`) and a road (routes, and a companion's world). Each
 * takes the first path the machine supports, in order of how faithfully it
 * reproduces the command:
 *
 *  - A wattage: native ERG, else a resistance level steered onto it.
 *  - A road: native simulation, else ERG at the wattage the road asks for,
 *    else a resistance level steered onto that same wattage.
 *
 * And a machine with none of them is ridden by hand in every mode, which is
 * still a ride: the power it reports is real, so the numbers, the route's
 * progress and the companion's world all follow the rider.
 */
data class TrainerControl(
    val targets: FtmsTargets,
    val resistanceRange: ResistanceRange?,
    val hasControlPoint: Boolean,
) {
    private val canSteerResistance: Boolean
        get() = targets.resistance && resistanceRange != null

    /** How a wattage reaches this machine. */
    val power: ControlPath
        get() = when {
            !hasControlPoint -> ControlPath.MANUAL
            targets.power -> ControlPath.NATIVE
            canSteerResistance -> ControlPath.VIA_RESISTANCE
            else -> ControlPath.MANUAL
        }

    /** How a road reaches this machine. */
    val road: ControlPath
        get() = when {
            !hasControlPoint -> ControlPath.MANUAL
            targets.simulation -> ControlPath.NATIVE
            targets.power -> ControlPath.VIA_POWER
            canSteerResistance -> ControlPath.VIA_RESISTANCE
            else -> ControlPath.MANUAL
        }

    fun withoutPower() = copy(targets = targets.copy(power = false))
    fun withoutSimulation() = copy(targets = targets.copy(simulation = false))
    fun withoutResistance() = copy(targets = targets.copy(resistance = false))

    companion object {
        /** No trainer, or one with no control point. */
        val NONE = TrainerControl(FtmsTargets.ASSUMED, resistanceRange = null, hasControlPoint = false)
    }
}

/**
 * A road, as the wattage it asks of a rider turning the cranks at a cadence.
 *
 * This is how a trainer that can hold a wattage but not simulate a road still
 * gives the rider their hills, and how a bike with only a resistance level
 * gets a target to steer at.
 *
 * The road is applied the way a trainer in simulation mode would apply it: at
 * the speed the trainer's own wheel turns, which for a rider in the real gear
 * the virtual drivetrain assumes is `cadence x` [Drivetrain.ASSUMED_REAL_DEVELOPMENT_M],
 * against [VirtualDrivetrain.ASSUMED_TRAINER_MASS_KG]. Both assumptions are the
 * ones the force was turned into a grade with, so the two cancel and the
 * wattage is exactly the force the drivetrain asked for at the speed it
 * assumed.
 *
 * The result rises and falls with cadence, which is the point: on a road, a
 * rider who spins faster in the same gear goes faster and pays for it, while
 * a fixed ERG wattage would make every cadence cost the same.
 */
object RoadAsPower {
    /** A ceiling no rider reaches, so a nonsense road cannot command a wall. */
    const val MAX_WATTS = 2_000

    fun watts(road: SimulationParameters, cadenceRpm: Int): Int {
        if (cadenceRpm <= 0) return 0
        val speed = cadenceRpm / 60.0 * Drivetrain.ASSUMED_REAL_DEVELOPMENT_M
        val weight = VirtualDrivetrain.ASSUMED_TRAINER_MASS_KG * VirtualSpeed.GRAVITY_MS2
        val air = speed + road.windSpeedMps
        val force = weight * road.gradePercent / 100.0 +
            weight * road.rollingResistance +
            road.windResistanceKgPerM * air * kotlin.math.abs(air)
        return (force * speed).roundToInt().coerceIn(0, MAX_WATTS)
    }
}
