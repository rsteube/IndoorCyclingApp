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
package app.indoorbike.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/*
 * Reading something that came from outside the app - a shared file, a link's
 * payload, a server's reply - without letting its size decide how much memory
 * the app spends on it. `readBytes()` reads whatever is there, and a workout
 * link carrying a gzip bomb, or a GPX that is really a disk image, used to be
 * read whole before anything looked at its size.
 */

/**
 * All of this stream, or an [IOException] saying [tooLarge] as soon as it
 * passes [limit] bytes. Reading stops there; the rest is never buffered.
 */
internal fun InputStream.readAtMost(limit: Long, tooLarge: String): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(buf)
        if (read == -1) break
        total += read
        if (total > limit) throw IOException(tooLarge)
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}

/**
 * The first [limit] bytes of this stream, and no more: for a reply that is
 * only ever shown or searched in part, where the rest need not be read.
 */
internal fun InputStream.readPrefix(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (read == -1) break
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}
