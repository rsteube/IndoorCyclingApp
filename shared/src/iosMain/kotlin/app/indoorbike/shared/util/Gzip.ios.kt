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
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** zlib, told to expect a gzip header (window bits + 16). */
@OptIn(ExperimentalForeignApi::class)
actual fun gunzip(bytes: ByteArray, limit: Long): ByteArray? {
    if (bytes.isEmpty()) return null
    return memScoped {
        val stream = alloc<z_stream>()
        if (inflateInit2_(stream.ptr, MAX_WBITS + 16, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) {
            return@memScoped null
        }
        val out = ArrayList<Byte>()
        val chunk = ByteArray(16 * 1024)
        try {
            bytes.usePinned { input ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = bytes.size.convert()
                while (true) {
                    val status = chunk.usePinned { buffer ->
                        stream.next_out = buffer.addressOf(0).reinterpret()
                        stream.avail_out = chunk.size.convert()
                        inflate(stream.ptr, Z_NO_FLUSH)
                    }
                    val produced = chunk.size - stream.avail_out.toInt()
                    if (out.size + produced > limit) return@memScoped null
                    for (i in 0 until produced) out.add(chunk[i])
                    when (status) {
                        Z_STREAM_END -> return@memScoped out.toByteArray()
                        Z_OK -> if (produced == 0 && stream.avail_in.toInt() == 0) return@memScoped null
                        else -> return@memScoped null
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
