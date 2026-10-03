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
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.umask

/**
 * The file operations the Linux side needs, on POSIX: the Sailfish counterpart
 * of `IosFiles` and the Android app's `AtomicFiles`. Paths are absolute, inside
 * [app.indoorbike.shared.sailfish.SailfishPaths]'s folders.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixFiles {
    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun delete(path: String) {
        remove(path)
    }

    fun move(from: String, to: String): Boolean = rename(from, to) == 0

    /** Make [path] and any folder above it that is missing. */
    fun mkdirs(path: String) {
        var at = ""
        for (part in path.split('/')) {
            if (part.isEmpty()) {
                if (at.isEmpty()) at = "/"
                continue
            }
            at = if (at.endsWith('/')) "$at$part" else "$at/$part"
            if (!exists(at)) mkdir(at, 0x1C0u) // 0700: the app's own folders hold the rider's data
        }
    }

    /** The names in a folder, without "." and "..", or none when it is not there. */
    fun list(path: String): List<String> {
        val dir = opendir(path) ?: return emptyList()
        val names = ArrayList<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally {
            closedir(dir)
        }
        return names.sorted()
    }

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

    fun appendText(path: String, text: String) = writeBytes(path, text.encodeToByteArray(), "ab", sync = false)

    /** Append and sync to disk before returning: the ride's samples, which a crash must not lose. */
    fun appendSynced(path: String, text: String) = writeBytes(path, text.encodeToByteArray(), "ab", sync = true)

    /**
     * Write [bytes] beside [path], sync it, and rename it into place, so a
     * reader finds the old contents or the new, never half of either.
     */
    fun writeAtomically(path: String, bytes: ByteArray) {
        val aside = "$path.tmp"
        writeBytes(aside, bytes, "wb", sync = true)
        if (!move(aside, path)) error("Could not move $aside into place")
    }

    fun writeTextAtomically(path: String, text: String) = writeAtomically(path, text.encodeToByteArray())

    /** As [writeAtomically], readable by the owner only: a file holding credentials. */
    fun writePrivately(path: String, bytes: ByteArray) {
        val before = umask(0x3Fu) // 077
        try {
            writeAtomically(path, bytes)
        } finally {
            umask(before)
        }
    }

    /**
     * Move an unreadable file out of the way rather than deleting it, so the
     * next write can go ahead and the bytes are still there to look at.
     */
    fun setAside(path: String) {
        val aside = "$path.unreadable"
        delete(aside)
        if (!move(path, aside)) delete(path)
    }

    private fun writeBytes(path: String, bytes: ByteArray, mode: String, sync: Boolean) {
        val file = fopen(path, mode) ?: error("Could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
                if (written.toInt() != bytes.size) error("Short write to $path")
            }
            if (sync) {
                fflush(file)
                fsync(fileno(file))
            }
        } finally {
            fclose(file)
        }
    }
}
