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
package app.indoorbike.shared.sync

/**
 * This device's current state, dated, so it can take part in a merge.
 *
 * The merge used to stand this device's last published slice in for it, which
 * meant anything changed since the last sync was not in the merge at all, and
 * the merged answer - last sync's value - was then written back over it. Set
 * FTP to 250 after a sync at 200 and the next launch put it back to 200. A
 * renamed imported workout went back to its old name the same way.
 *
 * Nothing records when a setting changes, so the date is worked out against
 * the last published slice: a value that differs from what this device last
 * published changed since then, and is dated now; one that does not keeps the
 * date it already had. That is exact for one device, and for several it
 * orders changes by the sync that first published them.
 */
object LocalChanges {

    const val FTP = "ftp"
    const val WEIGHT = "weightKg"
    const val MAX_HR = "maxHr"
    const val ONBOARDED = "hasCompletedOnboarding"
    const val STARTER_DISMISSED = "hasDismissedStarterCard"
    const val INTERVALS_ATHLETE = "intervalsAthleteId"
    const val INTERVALS_AUTO_UPLOAD = "intervalsAutoUpload"
    const val FAVOURITES = "favouriteWorkoutIds"

    val FIELDS = listOf(
        FTP, WEIGHT, MAX_HR, ONBOARDED, STARTER_DISMISSED,
        INTERVALS_ATHLETE, INTERVALS_AUTO_UPLOAD, FAVOURITES,
    )

    /**
     * [current] settings, each field dated against [previous], this device's
     * last published settings. With no previous slice every field is dated
     * zero: a device joining for the first time takes what the others have.
     */
    fun settings(current: BackupSettings, previous: BackupSettings?, nowMs: Long): BackupSettings {
        fun dated(field: String, now: Any?, before: Any?): Long = when {
            previous == null -> 0L
            now == before -> previous.epochOf(field)
            else -> nowMs
        }
        val epochs = mapOf(
            FTP to dated(FTP, current.ftp, previous?.ftp),
            WEIGHT to dated(WEIGHT, current.weightKg, previous?.weightKg),
            MAX_HR to dated(MAX_HR, current.maxHr, previous?.maxHr),
            ONBOARDED to dated(ONBOARDED, current.hasCompletedOnboarding, previous?.hasCompletedOnboarding),
            STARTER_DISMISSED to dated(STARTER_DISMISSED, current.hasDismissedStarterCard, previous?.hasDismissedStarterCard),
            INTERVALS_ATHLETE to dated(INTERVALS_ATHLETE, current.intervalsAthleteId, previous?.intervalsAthleteId),
            INTERVALS_AUTO_UPLOAD to dated(INTERVALS_AUTO_UPLOAD, current.intervalsAutoUpload, previous?.intervalsAutoUpload),
            // A set, whatever order it happens to be listed in.
            FAVOURITES to dated(FAVOURITES, current.favouriteWorkoutIds?.toSet(), previous?.favouriteWorkoutIds?.toSet()),
        )
        return current.copy(epoch = epochs.values.max(), fieldEpochs = epochs)
    }

    /**
     * [current] imported workouts, each dated against [previous]: unchanged
     * keeps its date, changed since the last publish is dated now, and one
     * that is new keeps the date it was imported.
     */
    fun imports(
        current: List<BackupImportedWorkout>,
        previous: List<BackupImportedWorkout>?,
        nowMs: Long,
    ): List<BackupImportedWorkout> {
        val before = previous.orEmpty().associateBy { it.workout.id }
        return current.map { now ->
            val was = before[now.workout.id] ?: return@map now
            val unchanged = was.workout == now.workout &&
                was.sourceRepoId == now.sourceRepoId &&
                was.sourceRepoName == now.sourceRepoName
            now.copy(importedEpoch = if (unchanged) was.importedEpoch else nowMs)
        }
    }
}
