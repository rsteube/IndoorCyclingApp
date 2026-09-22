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

import app.indoorbike.shared.physics.RideDynamics

/**
 * The bike's speed as the phone models it: taken from the legs while pulling
 * away, carried by the bike's mass from then on.
 *
 * Two answers to "how fast is the rider going" were available, and each is
 * wrong where the other is right:
 *
 *  - [DriveSpeed], `cadence x development`, is known on the first turn of the
 *    crank. But right after a shift the legs are still at the old cadence, so it
 *    multiplies that by the *new* gear and answers with a speed the bike does
 *    not have: on an upshift, one sprocket faster, instantly and for free.
 *  - Integrating `m . dv/dt = P/v - F_road` has the bike's mass in it, so a shift
 *    changes nothing until the rider's power does. But from a standstill it
 *    solves for a bicycle that is genuinely stationary, and speed sits near zero
 *    for seconds while the cranks are already turning at 80 rpm.
 *
 * So this uses each where it is right. While the bike is barely moving and the
 * legs are turning faster than it, the drivetrain's speed is adopted: that is a
 * rider pulling away. From there on the speed is integrated, with the surface's
 * rolling resistance and the bike's mass.
 *
 * ## Why companion mode needs this
 *
 * A companion is told the rider's speed in telemetry and, as the protocol asks,
 * rides its world at it and sends it back as the road's speed, which is what the
 * trainer's resistance is evaluated at. When that number was `cadence x
 * development`, every upshift came back from the game as an instant
 * acceleration, the air term squared it, and the rider met the cube of the gear
 * ratio instead of the ratio: three times the change a bicycle makes, felt as a
 * wall. A speed with mass in it cannot jump, so a game that echoes it cannot
 * turn a shift into one.
 */
class BikeSpeed {

    /** The modelled speed, in metres per second. */
    var mps: Double = 0.0
        private set

    /** A standstill. Called whenever a ride is loaded or stopped. */
    fun reset() {
        mps = 0.0
    }

    /**
     * Advance by [dtSeconds] of riding at [powerWatts] on [gradient] and
     * [crr], for a rider and bike of [totalMassKg].
     *
     * [driveSpeedMps] is the drivetrain's own speed, or null when no crank has
     * turned yet. It is only ever adopted while pulling away.
     */
    fun update(
        powerWatts: Double,
        driveSpeedMps: Double?,
        gradient: Double,
        crr: Double,
        totalMassKg: Double,
        dtSeconds: Double,
    ) {
        if (driveSpeedMps != null && mps < PULL_AWAY_BELOW_MPS && driveSpeedMps > mps) {
            mps = driveSpeedMps
            return
        }
        if (totalMassKg <= 0.0) return
        mps = RideDynamics.step(
            speedMps = mps,
            powerWatts = powerWatts,
            gradient = gradient,
            totalMassKg = totalMassKg,
            dtSeconds = dtSeconds,
            crr = crr,
        )
    }

    companion object {
        /**
         * Below this the bike counts as pulling away, and a faster drivetrain is
         * adopted rather than integrated towards. Walking pace, where the air is
         * worth almost nothing: a shift made down here moves the road by its
         * ratio whichever speed is believed.
         */
        const val PULL_AWAY_BELOW_MPS = 2.0
    }
}
