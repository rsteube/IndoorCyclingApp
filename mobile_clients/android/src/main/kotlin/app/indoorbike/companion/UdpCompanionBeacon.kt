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
package app.indoorbike.companion

import app.indoorbike.shared.companion.CompanionBeacon
import app.indoorbike.shared.companion.CompanionAnnouncement
import app.indoorbike.shared.companion.CompanionDiscovery
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Shouts a [CompanionAnnouncement] onto the local network so nobody has to type
 * an address.
 *
 * ## Why this exists next to mDNS
 *
 * [CompanionAdvertiser] publishes the same facts over DNS-SD, which is the
 * standard answer and stays the preferred one. This is the answer for the
 * clients that cannot easily consume it: Godot has no DNS-SD in core, and a
 * developer's first hour on a companion should not be spent shopping for an
 * mDNS library. Every environment has a UDP socket, so a broadcast is the one
 * discovery mechanism that is a dozen lines everywhere.
 *
 * The two are not redundant so much as aimed at different clients, and neither
 * is load-bearing: the pairing screen still shows the address and a QR, because
 * plenty of networks drop broadcast and multicast alike and discovery failing
 * must cost a rider a scan rather than the feature.
 *
 * ## What goes out
 *
 * Everything a companion needs to open the socket, and nothing that authorises
 * it to. The pairing code is not in the datagram: discovery answers "which
 * phone", pairing answers "may you drive it", and broadcasting the code to the
 * whole subnet would collapse the two and hand a trainer to anything listening.
 */
class UdpCompanionBeacon(private val scope: CoroutineScope) : CompanionBeacon {

    private var job: Job? = null

    /**
     * Start broadcasting.
     *
     * [payload] is read afresh each tick rather than captured once, so the
     * announcement tracks the live session - most usefully `paired`, which
     * lets a picker on the other end grey out a phone that is already busy.
     * Returning null pauses the beacon without tearing the socket down.
     */
    override fun start(payload: () -> CompanionAnnouncement?) {
        stop()
        job = scope.launch {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    while (isActive) {
                        val announcement = payload()
                        if (announcement == null) {
                            delay(CompanionProtocol.DISCOVERY_INTERVAL_MS)
                            continue
                        }
                        val bytes = CompanionDiscovery.encode(announcement).encodeToByteArray()
                        // Recomputed every tick rather than cached: a phone that
                        // drops off Wi-Fi and rejoins, or moves from Wi-Fi to a
                        // hotspot mid-ride, changes its broadcast addresses
                        // underneath us, and a cached list would keep shouting
                        // into a network that is no longer there.
                        broadcastAddresses().forEach { address ->
                            runCatching {
                                socket.send(
                                    DatagramPacket(
                                        bytes,
                                        bytes.size,
                                        address,
                                        CompanionProtocol.DISCOVERY_PORT,
                                    )
                                )
                            }
                        }
                        delay(
                            if (announcement.paired) CompanionProtocol.DISCOVERY_INTERVAL_PAIRED_MS
                            else CompanionProtocol.DISCOVERY_INTERVAL_MS
                        )
                    }
                }
            }.onFailure {
                // Never fatal. A network that refuses broadcast leaves the QR
                // and the typed address working, and the ride is unaffected.
                Log.w(TAG, "Discovery beacon stopped: " + (it.message ?: it::class.simpleName))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Every address a broadcast should go to, each exactly once.
     *
     * The per-interface broadcast address is the one that actually works.
     * 255.255.255.255 is only a *fallback*, used when no interface reports one,
     * because a few Android builds report none for an interface that plainly
     * has a network on it. Sending to both is tempting and wrong: the listener
     * receives every announcement twice, which doubles the traffic and invites
     * a naive client to think it has found two phones.
     */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        val perInterface = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .distinct()
            .toList()
        perInterface.ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
    }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "CompanionBeacon"
    }
}
