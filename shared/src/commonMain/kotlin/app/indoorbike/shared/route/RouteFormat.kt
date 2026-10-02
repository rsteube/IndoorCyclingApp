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
package app.indoorbike.shared.route

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a road's numbers read on a route ride, the same on both phones. The
 * colours are each app's; which colour a gradient gets is [GradientBand].
 */

/** How the gradient reads on the HUD, e.g. "6.4" for a 6.4 % climb, "-2.0" for a descent. */
fun formatGradientPercent(gradient: Double): String {
    val percent = gradient * 100.0
    val rounded = (abs(percent) * 10).roundToInt() / 10.0
    val sign = if (percent < -0.05) "-" else ""
    val whole = rounded.toInt()
    val tenth = ((rounded - whole) * 10).roundToInt()
    return "$sign$whole.$tenth"
}

/** Distance in the unit the HUD shows: metres under a kilometre, else km to two places. */
fun formatDistance(meters: Double): String {
    if (meters < 1_000.0) return "${meters.roundToInt()} m"
    val km = meters / 1_000.0
    var whole = km.toInt()
    var decimals = ((km - whole) * 100).roundToInt()
    // Rounding the fraction can carry, and the carry has to reach the
    // kilometres. Without this, 7999.5 m renders as "7.100 km": the fraction
    // rounds to 100, and padding a three-digit number to two digits leaves it
    // alone. Any distance within half a metre of a whole kilometre hits it,
    // which on a route HUD counting down to the finish is exactly the moment
    // the rider is looking.
    if (decimals >= 100) {
        whole += 1
        decimals -= 100
    }
    return "$whole.${decimals.toString().padStart(2, '0')} km"
}

/** A route's length on a card: "12.3 km", or "123 km" from a hundred up. */
fun formatKm(meters: Double): String {
    val km = meters / 1000.0
    return if (km >= 100) "${km.roundToInt()} km" else "${(km * 10).roundToInt() / 10.0} km"
}

/** One decimal place, never negative and never NaN: speeds and weights. */
fun formatOneDecimal(value: Double): String {
    val safe = if (value.isFinite()) abs(value) else 0.0
    val scaled = (safe * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

/** Which colour band a gradient falls in, from a steep descent to a wall. */
enum class GradientBand { SteepDescent, Descent, Flat, Gentle, Moderate, Hard, VeryHard, Wall }

fun gradientBand(gradient: Double): GradientBand {
    val percent = gradient * 100.0
    return when {
        percent <= -6.0 -> GradientBand.SteepDescent
        percent <= -1.5 -> GradientBand.Descent
        percent < 1.5 -> GradientBand.Flat
        percent < 4.0 -> GradientBand.Gentle
        percent < 7.0 -> GradientBand.Moderate
        percent < 10.0 -> GradientBand.Hard
        percent < 14.0 -> GradientBand.VeryHard
        else -> GradientBand.Wall
    }
}
