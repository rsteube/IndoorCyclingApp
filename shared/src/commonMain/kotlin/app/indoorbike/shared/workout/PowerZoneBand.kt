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
package app.indoorbike.shared.workout

/**
 * The seven power zones, as every app draws them: where each ends as a share
 * of FTP, its name, its colour and the tint behind a chip of it. One table, so
 * a zone is the same colour on every phone.
 *
 * Colours are 0xAARRGGBB. The zone colour is the ride screen's largest text:
 * the wattage itself is drawn in it. Four of these used to sit between 2.7:1
 * and 3.0:1 against the canvas, under even the 3:1 floor large text gets, and
 * the worst of them was Z6, the zone a rider is in when they are suffering
 * hardest and least able to squint. Each has been lifted along its own hue
 * until it clears 3.6:1 on a card and about 4:1 on the canvas, which is enough
 * to read at arm's length while sweating and still leaves red red and blue
 * blue. Z3, Z4 and Z5 already cleared it and are untouched.
 *
 * The tint behind a zone chip is a fill, not text, and the label on it is the
 * zone colour.
 */
enum class PowerZoneBand(
    val number: Int,
    val label: String,
    val color: Long,
    val tint: Long,
    /** The top of the zone, as a percentage of FTP. */
    val ftpHighPct: Int,
    val targetMultiplier: Float,
) {
    Z1(1, "Recovery", 0xFF87A0B9, 0x296B8AA8, 55, 0.50f),
    Z2(2, "Endurance", 0xFF639CFF, 0x2E2E7BFF, 75, 0.65f),
    Z3(3, "Tempo", 0xFF1FB373, 0x2E1FB373, 90, 0.83f),
    Z4(4, "Threshold", 0xFFF0C419, 0x33F0C419, 105, 0.98f),
    Z5(5, "VO2", 0xFFFF8A1F, 0x33FF8A1F, 120, 1.13f),
    Z6(6, "Anaerobic", 0xFFF17A7A, 0x33EB3B3B, 150, 1.35f),
    Z7(7, "Neuro", 0xFFD674FF, 0x33C53BFF, 999, 1.60f);

    companion object {
        fun fromFtpPercent(pct: Int): PowerZoneBand = entries.first { pct <= it.ftpHighPct }
        fun byNumber(n: Int): PowerZoneBand = entries.first { it.number == n.coerceIn(1, 7) }
    }
}
