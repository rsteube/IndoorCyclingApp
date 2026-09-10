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

/**
 * Heart-rate zones, Garmin's default 5-zone scheme as percentages of Max HR.
 *
 *   Z1 50-60%   Warm Up    — slate (matches Power Z1 Recovery)
 *   Z2 60-70%   Easy       — blue  (matches Power Z2 Endurance)
 *   Z3 70-80%   Aerobic    — green (matches Power Z3 Tempo)
 *   Z4 80-90%   Threshold  — yellow (matches Power Z4 Threshold)
 *   Z5 90-100%  Maximum    — red   (matches Power Z6 Anaerobic — same "danger" code)
 *
 * Colours intentionally re-use values from [PowerZone] so the rider sees a
 * consistent visual language across power and HR. The two power-zone shades
 * we *don't* re-use here (Z5 orange / Z7 magenta) belong to fast-twitch /
 * neuromuscular efforts that don't have a clean HR analogue — the rider's
 * heart can't keep up with sprints in real time anyway.
 */
enum class HeartRateZone(
    val number: Int,
    val label: String,
    val color: Color,
    val bgColor: Color,
    /** Inclusive upper bound, as percentage of Max HR. */
    val maxHrHighPct: Int,
) {
    Z1(1, "Warm Up",   Color(0xFF6B8AA8), Color(0x296B8AA8), 60),
    Z2(2, "Easy",      Color(0xFF2E7BFF), Color(0x2E2E7BFF), 70),
    Z3(3, "Aerobic",   Color(0xFF1FB373), Color(0x2E1FB373), 80),
    Z4(4, "Threshold", Color(0xFFF0C419), Color(0x33F0C419), 90),
    Z5(5, "Maximum",   Color(0xFFEB3B3B), Color(0x33EB3B3B), 999);

    companion object {
        /**
         * Returns the zone the given current heart rate falls into, given the
         * rider's max HR. Anything below 50% Max HR (the start of Z1) returns
         * `null` — at that intensity we shouldn't pretend it's a training zone.
         */
        fun fromHr(currentHr: Int, maxHr: Int): HeartRateZone? {
            if (currentHr <= 0 || maxHr <= 0) return null
            val pct = (currentHr.toFloat() / maxHr * 100f).toInt()
            if (pct < 50) return null
            return entries.first { pct <= it.maxHrHighPct }
        }
    }
}
