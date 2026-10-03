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
package app.indoorbike.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

/**
 * The few file operations the Linux side needs, on POSIX: the Sailfish
 * counterpart of `IosFiles`. Paths are absolute, inside [app.indoorbike.shared.sailfish.SailfishPaths].
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixFiles {
    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun delete(path: String) {
        remove(path)
    }

    fun move(from: String, to: String): Boolean = rename(from, to) == 0

    fun readBytes(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            val out = ArrayList<ByteArray>()
            var total = 0
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = chunk.usePinned { fread(it.addressOf(0), 1u, chunk.size.convert(), file) }.toInt()
                if (n <= 0) break
                out += chunk.copyOf(n)
                total += n
            }
            val result = ByteArray(total)
            var at = 0
            for (part in out) {
                part.copyInto(result, at)
                at += part.size
            }
            return result
        } finally {
            fclose(file)
        }
    }

    fun readText(path: String): String? = readBytes(path)?.decodeToString()

    fun appendText(path: String, text: String) = writeBytes(path, text.encodeToByteArray(), "ab")

    /** Write [bytes] beside [path] and rename it into place, so a reader never sees half a file. */
    fun writeAtomically(path: String, bytes: ByteArray) {
        val aside = "$path.tmp"
        writeBytes(aside, bytes, "wb")
        if (!move(aside, path)) error("Could not move $aside into place")
    }

    private fun writeBytes(path: String, bytes: ByteArray, mode: String) {
        val file = fopen(path, mode) ?: error("Could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
                if (written.toInt() != bytes.size) error("Short write to $path")
            }
        } finally {
            fclose(file)
        }
    }
}
