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

import app.indoorbike.shared.util.Log
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

// <net/if.h> values, the same on every Linux ABI.
private const val IFF_UP = 0x1
private const val IFF_LOOPBACK = 0x8

/** getifaddrs: every up, non-loopback IPv4 address that isn't link-local. The iOS code, on Linux. */
@OptIn(ExperimentalForeignApi::class)
actual fun localIPv4Addresses(): List<String> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
    val out = LinkedHashSet<String>()
    val seen = ArrayList<String>()
    try {
        var node = head.value
        while (node != null) {
            val entry = node.pointed
            val flags = entry.ifa_flags.toInt()
            val address = entry.ifa_addr
            if (address != null && address.pointed.sa_family.toInt() == AF_INET &&
                flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0
            ) {
                // The address is four bytes in network order, so read it as
                // bytes; inet_ntop is not in the platform library.
                val octets = address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<UByteVar>()
                val text = (0 until 4).joinToString(".") { octets[it].toString() }
                seen += (entry.ifa_name?.toKString() ?: "?") + "=" + text
                if (!text.startsWith("169.254.") && !text.startsWith("127.")) out += text
            }
            node = entry.ifa_next
        }
    } finally {
        freeifaddrs(head.value)
    }
    // Which interfaces were up, so a "No network" can be told apart from
    // Wi-Fi being off.
    Log.i("LocalAddresses", "IPv4 interfaces up: ${seen.ifEmpty { listOf("none") }.joinToString()}")
    out.toList()
}
