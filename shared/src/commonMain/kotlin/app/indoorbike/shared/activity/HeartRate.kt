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
package app.indoorbike.shared.activity

/**
 * Average heart rate over the samples that have one, or 0 when none do.
 *
 * A sample records 0 bpm when nothing was measuring: no strap yet, or a strap
 * that dropped out. Averaging those in as zeros made a ride whose strap
 * connected ten minutes into an hour at 150 bpm read about 125, in history and
 * in the FIT file, while the TCX of the same ride said 150. Every average of
 * heart rate goes through here so they all agree.
 */
fun averageHeartRate(samples: Iterable<Long>): Long {
    var sum = 0L
    var n = 0
    for (bpm in samples) {
        if (bpm > 0) {
            sum += bpm
            n += 1
        }
    }
    return if (n == 0) 0L else sum / n
}
