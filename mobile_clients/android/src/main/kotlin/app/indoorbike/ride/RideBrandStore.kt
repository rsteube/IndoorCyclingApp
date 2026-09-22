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
package app.indoorbike.ride

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who drove a ride: the name a companion gave itself, and its icon if it sent one. */
data class RideBrand(
    val name: String,
    /** The PNG the companion sent, already validated when it arrived. Null if it sent none. */
    val icon: File?,
)

/**
 * The companion that drove a ride, kept with the ride.
 *
 * ## Why this is stored at all
 *
 * A companion's name and icon arrive over a socket and live as long as it is
 * connected. The ride outlives the connection: it is read back from history
 * days later, on a share card that should say what the rider rode with. Nothing
 * else in the record can answer that. The course name says where, the workout
 * id says "companion", and neither names the game.
 *
 * ## Why by start time, and why files
 *
 * Both for the same reasons as [RideScreenshotStore], which this sits beside: a
 * ride is identified by when it started, because that is the one thing about it
 * that exists before it has a history row, and an icon is an image rather than
 * a column. The two stores prune on the same sweep.
 *
 * Written when the ride starts rather than when it is saved, so a ride the app
 * was killed in the middle of, or one whose game disconnected before the rider
 * saved it, still knows who it was with.
 *
 * Nothing here leaves the phone on its own. It is drawn on a share card the
 * rider chooses to make, which is the rider crediting the game they rode.
 */
class RideBrandStore(filesDir: File) {

    private val root = File(filesDir, "ride_brands")

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so a screen showing a ride's credit sees one arrive. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Remember who is driving this ride.
     *
     * Idempotent on purpose: a companion may send its brand before the ride, and
     * again during it, and the ride starts once. Rewriting the same name and the
     * same icon changes nothing and bumps nothing, so a screen watching
     * [revision] is not redrawn by a repeat.
     */
    fun save(rideStartEpochMs: Long, name: String, iconPng: ByteArray?) {
        if (rideStartEpochMs == 0L || name.isBlank()) return
        val dir = File(root, rideStartEpochMs.toString())
        if (!dir.isDirectory && !dir.mkdirs()) return

        var changed = false
        val nameFile = File(dir, NAME)
        if (!nameFile.exists() || nameFile.readText() != name) {
            nameFile.writeText(name)
            changed = true
        }
        val iconFile = File(dir, ICON)
        when {
            iconPng == null -> if (iconFile.exists()) {
                iconFile.delete()
                changed = true
            }
            !iconFile.exists() || !iconFile.readBytes().contentEquals(iconPng) -> {
                // Written aside and renamed, so a card drawing this folder never
                // loads half an icon.
                val partial = File(dir, ICON + ".part")
                partial.writeBytes(iconPng)
                if (partial.renameTo(iconFile)) changed = true else partial.delete()
            }
        }
        if (changed) _revision.update { it + 1 }
    }

    /** Who drove the ride that started then, or null for a ride that had nobody. */
    fun find(rideStartEpochMs: Long): RideBrand? {
        if (rideStartEpochMs == 0L) return null
        val dir = File(root, rideStartEpochMs.toString())
        val name = File(dir, NAME).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            ?: return null
        return RideBrand(name = name, icon = File(dir, ICON).takeIf { it.isFile })
    }

    fun deleteRide(rideStartEpochMs: Long) {
        if (rideStartEpochMs == 0L) return
        if (File(root, rideStartEpochMs.toString()).deleteRecursively()) _revision.update { it + 1 }
    }

    /**
     * Forget who drove every ride not in [keep], and return how many rides that was.
     *
     * The same sweep and the same grace period as the screenshots, for the same
     * reason: a ride can start between [keep] being read and this running.
     */
    fun prune(keep: Set<Long>, nowMs: Long = System.currentTimeMillis(), recentMs: Long = 10 * 60_000L): Int {
        val gone = root.listFiles().orEmpty().filter { dir ->
            val start = dir.name.toLongOrNull()
            dir.isDirectory && (start == null || start !in keep) && nowMs - dir.lastModified() > recentMs
        }
        gone.forEach { it.deleteRecursively() }
        if (gone.isNotEmpty()) _revision.update { it + 1 }
        return gone.size
    }

    private companion object {
        const val NAME = "name.txt"
        const val ICON = "icon.png"
    }
}
