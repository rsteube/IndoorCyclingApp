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
 * Heart-rate zones, Garmin's default five as shares of maximum heart rate:
 * where each ends, its name and its colour. One table, so a zone is the same
 * colour on every phone.
 *
 *   Z1 50-60 %  Warm Up    slate, as power Z1 Recovery
 *   Z2 60-70 %  Easy       blue, as power Z2 Endurance
 *   Z3 70-80 %  Aerobic    green, as power Z3 Tempo
 *   Z4 80-90 %  Threshold  yellow, as power Z4 Threshold
 *   Z5 90 %+    Maximum    red, as power Z6 Anaerobic: the same "danger"
 *
 * The colours are [PowerZoneBand]'s, so power and heart rate speak one visual
 * language. The two power shades not used here, Z5's orange and Z7's magenta,
 * belong to efforts a heart cannot follow in real time anyway.
 *
 * Colours are 0xAARRGGBB.
 */
enum class HeartRateZoneBand(
    val number: Int,
    val label: String,
    val color: Long,
    val tint: Long,
    /** The top of the zone, as a percentage of maximum heart rate. */
    val maxHrHighPct: Int,
) {
    Z1(1, "Warm Up", 0xFF87A0B9, 0x296B8AA8, 60),
    Z2(2, "Easy", 0xFF639CFF, 0x2E2E7BFF, 70),
    Z3(3, "Aerobic", 0xFF1FB373, 0x2E1FB373, 80),
    Z4(4, "Threshold", 0xFFF0C419, 0x33F0C419, 90),
    Z5(5, "Maximum", 0xFFF17A7A, 0x33EB3B3B, 999);

    companion object {
        /** Below half of maximum there is no training zone to name: below Z1, null. */
        const val FLOOR_PCT = 50

        /**
         * The zone [currentHr] is in for a rider whose maximum is [maxHr], or
         * null below [FLOOR_PCT] or without both numbers.
         */
        fun fromHr(currentHr: Int, maxHr: Int): HeartRateZoneBand? {
            if (currentHr <= 0 || maxHr <= 0) return null
            val pct = (currentHr.toFloat() / maxHr * 100f).toInt()
            if (pct < FLOOR_PCT) return null
            return entries.first { pct <= it.maxHrHighPct }
        }
    }
}
