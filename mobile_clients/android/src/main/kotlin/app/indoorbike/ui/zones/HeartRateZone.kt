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
package app.indoorbike.ui.zones

import androidx.compose.ui.graphics.Color
import app.indoorbike.shared.workout.HeartRateZoneBand

/**
 * A heart-rate zone as Compose draws it. The zones themselves, their limits and
 * colours, are [HeartRateZoneBand] in shared/, which every app reads; see there
 * for how the colours were chosen.
 */
enum class HeartRateZone(private val band: HeartRateZoneBand) {
    Z1(HeartRateZoneBand.Z1),
    Z2(HeartRateZoneBand.Z2),
    Z3(HeartRateZoneBand.Z3),
    Z4(HeartRateZoneBand.Z4),
    Z5(HeartRateZoneBand.Z5);

    val number: Int get() = band.number
    val label: String get() = band.label
    val color: Color = Color(band.color)
    val bgColor: Color = Color(band.tint)
    /** Inclusive upper bound, as percentage of Max HR. */
    val maxHrHighPct: Int get() = band.maxHrHighPct

    companion object {
        /**
         * The zone the given heart rate falls into for the rider's max HR, or
         * null below 50 % of it, where there is no training zone to pretend.
         */
        fun fromHr(currentHr: Int, maxHr: Int): HeartRateZone? =
            HeartRateZoneBand.fromHr(currentHr, maxHr)?.let { band -> entries.first { it.band == band } }
    }
}
