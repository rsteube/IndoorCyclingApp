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
import app.indoorbike.shared.workout.PowerZoneBand

/**
 * A power zone as Compose draws it. The zones themselves, their limits and
 * colours, are [PowerZoneBand] in shared/, which every app reads; see there
 * for how the colours were chosen.
 */
enum class PowerZone(private val band: PowerZoneBand) {
    Z1(PowerZoneBand.Z1),
    Z2(PowerZoneBand.Z2),
    Z3(PowerZoneBand.Z3),
    Z4(PowerZoneBand.Z4),
    Z5(PowerZoneBand.Z5),
    Z6(PowerZoneBand.Z6),
    Z7(PowerZoneBand.Z7);

    val number: Int get() = band.number
    val label: String get() = band.label
    val color: Color = Color(band.color)
    val bgColor: Color = Color(band.tint)
    val ftpHighPct: Int get() = band.ftpHighPct
    val targetMultiplier: Float get() = band.targetMultiplier

    companion object {
        fun fromFtpPercent(pct: Int): PowerZone = entries.first { pct <= it.ftpHighPct }
        fun byNumber(n: Int): PowerZone = entries.first { it.number == n.coerceIn(1, 7) }
    }
}
