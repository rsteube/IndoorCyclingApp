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
package app.indoorbike.shared.ios

import app.indoorbike.shared.companion.CompanionAnnouncement
import app.indoorbike.shared.companion.CompanionBeacon
import app.indoorbike.shared.companion.CompanionDiscovery
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.util.Log
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_BROADCAST
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.close
import platform.posix.errno
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

/**
 * The UDP discovery beacon, as Android's `UdpCompanionBeacon` sends it: the
 * same datagram to every interface's broadcast address, so a companion that
 * has no DNS-SD (Godot has none in core) can still find the phone.
 *
 * iOS only lets an app broadcast with Apple's multicast entitlement
 * (`com.apple.developer.networking.multicast`). Without it the send is
 * refused, which is logged once and costs nothing else: Bonjour and the typed
 * address keep working.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCompanionBeacon(private val scope: CoroutineScope) : CompanionBeacon {

    private var job: Job? = null

    override fun start(payload: () -> CompanionAnnouncement?) {
        stop()
        job = scope.launch {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            if (fd < 0) {
                Log.w(TAG, "Discovery beacon has no socket: " + strerror(errno)?.toKString())
                return@launch
            }
            try {
                memScoped {
                    val on = alloc<IntVar>()
                    on.value = 1
                    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, on.ptr, sizeOf<IntVar>().convert())
                }
                // The first outcome is logged, and a change after it: the
                // log should say whether this network, or iOS, lets it out.
                var lastOutcome: String? = null
                while (isActive) {
                    val announcement = payload()
                    if (announcement != null) {
                        val bytes = CompanionDiscovery.encode(announcement).encodeToByteArray()
                        // Read every tick: joining another Wi-Fi changes them.
                        for (address in broadcastAddresses()) {
                            val error = send(fd, bytes, address)
                            val outcome = if (error == null) "sending to $address" else "refused to $address: $error"
                            if (outcome != lastOutcome) {
                                Log.i(TAG, "Discovery beacon " + outcome)
                                lastOutcome = outcome
                            }
                        }
                    }
                    delay(
                        if (announcement?.paired == true) CompanionProtocol.DISCOVERY_INTERVAL_PAIRED_MS
                        else CompanionProtocol.DISCOVERY_INTERVAL_MS
                    )
                }
            } finally {
                close(fd)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends one datagram; null when it went, else why not. */
    private fun send(fd: Int, bytes: ByteArray, address: BroadcastAddress): String? = memScoped {
        val target = alloc<sockaddr_in>()
        target.sin_len = sizeOf<sockaddr_in>().convert()
        target.sin_family = AF_INET.convert()
        // Network byte order; every iOS device is little-endian.
        val port = CompanionProtocol.DISCOVERY_PORT
        target.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
        target.sin_addr.s_addr = address.networkOrder
        val sent = bytes.usePinned { pinned ->
            sendto(fd, pinned.addressOf(0), bytes.size.convert(), 0, target.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        if (sent < 0) strerror(errno)?.toKString() ?: "error $errno" else null
    }

    private class BroadcastAddress(val networkOrder: UInt, val text: String) {
        override fun toString() = text
    }

    /** Every up, non-loopback IPv4 interface's broadcast address, once each. */
    private fun broadcastAddresses(): List<BroadcastAddress> = memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
        val out = LinkedHashMap<UInt, BroadcastAddress>()
        try {
            var node = head.value
            while (node != null) {
                val entry = node.pointed
                val flags = entry.ifa_flags.toInt()
                val address = entry.ifa_addr
                // On a broadcast interface, ifa_dstaddr is its broadcast address.
                val broadcast = entry.ifa_dstaddr
                if (address != null && broadcast != null && address.pointed.sa_family.toInt() == AF_INET &&
                    flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0 && flags and IFF_BROADCAST != 0
                ) {
                    val value = broadcast.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                    // 169.254/16 is the cable to a Mac, not a network a game is on.
                    val bytes = (0 until 4).map { (value shr (8 * it)) and 0xFFu }
                    if (!(bytes[0] == 169u && bytes[1] == 254u)) {
                        out.getOrPut(value) { BroadcastAddress(value, bytes.joinToString(".")) }
                    }
                }
                node = entry.ifa_next
            }
        } finally {
            freeifaddrs(head.value)
        }
        out.values.toList()
    }

    private companion object {
        const val TAG = "CompanionBeacon"
    }
}
