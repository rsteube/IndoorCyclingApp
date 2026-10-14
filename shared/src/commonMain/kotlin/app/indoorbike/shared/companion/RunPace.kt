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
package app.indoorbike.shared.companion

import kotlin.math.roundToInt

/**
 * A runner's speed as a runner reads it: minutes and seconds per kilometre.
 *
 * Shared so every platform that shows a run (protocol 0.14) says the same
 * thing about the same speed, rounding included.
 */
object RunPace {

    /**
     * Slower than this is standing, not running or walking: an hour a
     * kilometre. Shown as no pace at all rather than as a number in the
     * hundreds of minutes that only says the rider has stopped.
     */
    const val SLOWEST_SECONDS_PER_KM = 3_600

    /** Seconds per kilometre at [speedMps], to the second, or null when stopped. */
    fun secondsPerKm(speedMps: Double): Int? {
        if (!speedMps.isFinite() || speedMps <= 0.0) return null
        val seconds = (1_000.0 / speedMps).roundToInt()
        return seconds.takeIf { it <= SLOWEST_SECONDS_PER_KM }
    }

    /** "5:07", or null when stopped. The unit, "/km", is the caller's to draw. */
    fun format(speedMps: Double): String? {
        val seconds = secondsPerKm(speedMps) ?: return null
        return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
    }
}
