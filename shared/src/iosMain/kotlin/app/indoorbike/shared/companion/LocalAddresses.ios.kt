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
package app.indoorbike.shared.companion

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.INET_ADDRSTRLEN
import platform.posix.sockaddr_in

/** getifaddrs: every up, non-loopback IPv4 address that isn't link-local. */
@OptIn(ExperimentalForeignApi::class)
actual fun localIPv4Addresses(): List<String> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
    val out = LinkedHashSet<String>()
    try {
        var node = head.value
        while (node != null) {
            val entry = node.pointed
            val flags = entry.ifa_flags.toInt()
            val address = entry.ifa_addr
            if (address != null && address.pointed.sa_family.toInt() == AF_INET &&
                flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0
            ) {
                val buffer = allocArray<ByteVar>(INET_ADDRSTRLEN)
                val inet = address.reinterpret<sockaddr_in>().pointed
                inet_ntop(AF_INET, inet.sin_addr.ptr, buffer, INET_ADDRSTRLEN.toUInt())
                val text = buffer.toKString()
                if (!text.startsWith("169.254.") && !text.startsWith("127.")) out += text
            }
            node = entry.ifa_next
        }
    } finally {
        freeifaddrs(head.value)
    }
    out.toList()
}
