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
package app.indoorbike.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Small files that must survive a crash or a power cut at any instant, with
 * either the old contents or the new but never neither.
 *
 * Every store here used to write a temporary file, delete the real one, then
 * rename the temporary over it, ignoring whether the rename worked. A process
 * killed between the delete and the rename left no file at all; a power cut
 * left a temporary file whose bytes had never reached the disk, because nothing
 * synced it. Deleting first also threw away the one protection the filesystem
 * gives for free: rename replaces its target atomically.
 */
object AtomicFiles {

    /** Replace [file]'s contents with [text]: written, synced, then renamed over it. */
    fun writeText(file: File, text: String) {
        val tmp = staging(file)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Couldn't move ${tmp.name} over ${file.name}")
        }
    }

    /**
     * [file]'s contents, or the staged copy's when [file] does not exist yet:
     * a first write that was synced but not renamed before the process died
     * is still a complete file. Null when there is neither.
     */
    fun readText(file: File): String? = when {
        file.exists() -> file.readText()
        staging(file).exists() -> staging(file).readText()
        else -> null
    }

    /**
     * Move an unreadable [file] out of the way rather than deleting it, so the
     * next write can proceed and the bytes are still there to look at.
     */
    fun setAside(file: File) {
        val aside = File(file.parentFile, file.name + ".unreadable")
        aside.delete()
        if (!file.renameTo(aside)) file.delete()
    }

    fun delete(file: File) {
        file.delete()
        staging(file).delete()
    }

    private fun staging(file: File) = File(file.parentFile, file.name + ".tmp")
}
