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
package app.indoorbike.route

import app.indoorbike.shared.repo.RouteAttribution

/**
 * Where a route in the catalogue came from, the route-side counterpart of
 * [app.indoorbike.library.WorkoutSource].
 *
 * It carries one thing the workout version does not: [RouteAttribution]. A
 * workout is somebody's own numbers, but route geometry is usually derived
 * from a licensed database whose licence asks for credit wherever the data is
 * shown. So attribution is not a nice-to-have on a detail screen, it is a
 * condition of using the route at all, and the catalogue keeps it attached
 * rather than leaving it in a file nobody reads.
 */
sealed class RouteSource {

    /** Ships with the APK in `assets/routes/`. */
    data object Bundled : RouteSource()

    /**
     * A local copy under `filesDir/user_routes/`, added from a community
     * repository or from a file the rider picked. [sourceRepoId] and
     * [sourceRepoName] are null for a file import, which has no upstream.
     */
    data class Imported(
        val sourceRepoId: String? = null,
        val sourceRepoName: String? = null,
        val attribution: RouteAttribution? = null,
        /** The id the repository lists it under, which the local id need not be. */
        val sourceEntryId: String? = null,
    ) : RouteSource()

    /** Short label for the route card, matching how workout cards are tagged. */
    val label: String
        get() = when (this) {
            is Bundled -> "Bundled"
            is Imported -> sourceRepoName ?: "Imported"
        }

    /**
     * The credit line to show wherever the route is, or null when there is
     * nothing to credit. Reads as "OpenStreetMap contributors (ODbL-1.0)".
     */
    val creditLine: String?
        get() {
            val attribution = (this as? Imported)?.attribution ?: return null
            val source = attribution.source?.takeIf { it.isNotBlank() }
            val licence = attribution.licence?.takeIf { it.isNotBlank() }
            return when {
                source != null && licence != null -> "$source ($licence)"
                source != null -> source
                licence != null -> licence
                else -> null
            }
        }
}
