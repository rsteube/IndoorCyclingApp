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

import app.indoorbike.shared.companion.ScreenshotImage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One picture kept with a ride. */
data class RideScreenshot(
    val file: File,
    val takenAtEpochMs: Long,
    /** How far into the ride the rider asked for it, on the ride's own clock. */
    val rideSeconds: Int,
)

/**
 * Screenshots a companion sent, kept on this phone with the ride they were taken on.
 *
 * ## Why files, and why by start time
 *
 * A ride is identified here by when it started, which is the one thing about it
 * that exists from its first second: a companion ride has no history row until
 * its game says it is over, and a ride the app was killed in the middle of has
 * none until the rider saves it from the banner. The saved row carries the same
 * start time, so the pictures find their ride whichever way it reached history.
 *
 * Files rather than database rows because a PNG of a game frame can be several
 * megabytes, and a row that size does not fit through an Android cursor.
 *
 * Nothing here leaves the phone. Screenshots are not exported, uploaded or synced.
 */
class RideScreenshotStore(filesDir: File) {

    private val root = File(filesDir, "ride_screenshots")

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so a screen showing a ride's pictures sees one arrive. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun save(
        rideStartEpochMs: Long,
        image: ScreenshotImage,
        takenAtEpochMs: Long,
        rideSeconds: Int,
    ): RideScreenshot {
        val dir = File(root, rideStartEpochMs.toString())
        if (!dir.isDirectory && !dir.mkdirs()) error("Could not create " + dir)
        val extension = if (image.format == ScreenshotImage.PNG) "png" else "jpg"
        var file = File(dir, "$takenAtEpochMs-$rideSeconds.$extension")
        var n = 2
        while (file.exists()) file = File(dir, "$takenAtEpochMs-$rideSeconds-${n++}.$extension")
        // Written aside and renamed, so a screen listing the folder never draws half a picture.
        val partial = File(dir, file.name + ".part")
        partial.writeBytes(image.bytes)
        if (!partial.renameTo(file)) {
            partial.delete()
            error("Could not store the screenshot as " + file)
        }
        _revision.update { it + 1 }
        return RideScreenshot(file, takenAtEpochMs, rideSeconds)
    }

    /** The ride's pictures, oldest first. */
    fun list(rideStartEpochMs: Long): List<RideScreenshot> =
        File(root, rideStartEpochMs.toString()).listFiles()
            .orEmpty()
            .mapNotNull(::read)
            .sortedBy { it.takenAtEpochMs }

    fun deleteRide(rideStartEpochMs: Long) {
        if (rideStartEpochMs == 0L) return
        if (File(root, rideStartEpochMs.toString()).deleteRecursively()) _revision.update { it + 1 }
    }

    /**
     * Delete the pictures of every ride not in [keep], and return how many rides that was.
     *
     * The one sweep that catches every way a ride can disappear: deleted in
     * history, removed by a sync, abandoned from the banner, or ended by a game
     * before it ever started. A folder touched in the last [recentMs] is left
     * alone, because a ride can start between [keep] being read and this running.
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

    private fun read(file: File): RideScreenshot? {
        if (file.extension != "jpg" && file.extension != "png") return null
        val parts = file.nameWithoutExtension.split('-')
        val takenAt = parts.getOrNull(0)?.toLongOrNull() ?: return null
        return RideScreenshot(file, takenAt, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}
