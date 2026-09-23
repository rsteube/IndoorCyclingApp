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

import app.indoorbike.shared.db.WorkoutSession

/**
 * The name an exported ride file is shared under: "indoorbike-sweet-spot-42.fit".
 * The workout's name, lower-cased with everything but letters and digits
 * collapsed to single dashes, then the ride's id so two rides of the same
 * workout never share a name.
 */
fun exportFileName(session: WorkoutSession, extension: String): String {
    val safe = session.workoutName
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "ride" }
    return "indoorbike-$safe-${session.id}.$extension"
}
