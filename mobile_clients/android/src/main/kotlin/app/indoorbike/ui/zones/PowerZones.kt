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

enum class PowerZone(
    val number: Int,
    val label: String,
    val color: Color,
    val bgColor: Color,
    val ftpHighPct: Int,
    val targetMultiplier: Float
) {
    Z1(1, "Recovery",    Color(0xFF6B8AA8), Color(0x296B8AA8), 55,  0.50f),
    Z2(2, "Endurance",   Color(0xFF2E7BFF), Color(0x2E2E7BFF), 75,  0.65f),
    Z3(3, "Tempo",       Color(0xFF1FB373), Color(0x2E1FB373), 90,  0.83f),
    Z4(4, "Threshold",   Color(0xFFF0C419), Color(0x33F0C419), 105, 0.98f),
    Z5(5, "VO2",         Color(0xFFFF8A1F), Color(0x33FF8A1F), 120, 1.13f),
    Z6(6, "Anaerobic",   Color(0xFFEB3B3B), Color(0x33EB3B3B), 150, 1.35f),
    Z7(7, "Neuro",       Color(0xFFC53BFF), Color(0x33C53BFF), 999, 1.60f);

    companion object {
        fun fromFtpPercent(pct: Int): PowerZone = entries.first { pct <= it.ftpHighPct }
        fun byNumber(n: Int): PowerZone = entries.first { it.number == n.coerceIn(1, 7) }
    }
}
