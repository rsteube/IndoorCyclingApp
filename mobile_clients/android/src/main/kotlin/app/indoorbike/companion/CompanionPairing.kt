/*
 * Copyright (C) 2026 Juhani Lehtimäki
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

import app.indoorbike.shared.companion.CompanionProtocol
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * The pairing code, the addresses to reach the phone on, and the QR payload
 * that carries both.
 *
 * ## Why there is a code at all
 *
 * A companion holding a valid token can change the resistance of a trainer
 * somebody is clipped into. That is a physical-safety boundary, not a
 * preference, so control is never implicit: the rider starts the mode
 * deliberately, the code is shown on the phone, and it lasts exactly one ride.
 * Anything that cannot read the code off the screen is not in the room.
 *
 * The alphabet leaves out the characters people misread when they are typing
 * from a phone propped on a handlebar - no `O` against `0`, no `I` or `1`, no
 * `S` against `5` - because the alternative to a readable code is a rider
 * retyping it with sweat in their eyes.
 */
object CompanionPairing {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRTUVWXY2346789"
    const val CODE_LENGTH = 6

    /** A fresh pairing code. One per ride: it is not stored and not reused. */
    fun newToken(): String =
        (1..CODE_LENGTH).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")

    /** A short id for this ride, so a reconnecting companion can tell it is the same one. */
    fun newSessionId(): String =
        (1..6).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    /**
     * Every IPv4 address this phone can plausibly be reached on.
     *
     * Plural on purpose. A phone on Wi-Fi while tethering, or on a VPN, has
     * more than one, and the app has no way to know which one the companion's
     * machine can see. Showing them all and letting the rider pick the one that
     * looks like their network beats guessing and being wrong silently.
     *
     * Loopback and link-local are dropped: neither is reachable from the other
     * device, so offering them would only waste a rider's time.
     */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /**
     * The deep link a companion scans.
     *
     * Carries everything needed to connect without discovery, which is the
     * whole point: mDNS is blocked on plenty of networks, and a rider whose
     * game cannot find the phone needs a path that does not depend on the
     * network being friendly.
     */
    fun qrPayload(host: String, port: Int, token: String, sessionId: String): String =
        "indoorbike://companion?host=" + host +
            "&port=" + port +
            "&token=" + token +
            "&session=" + sessionId

    /** The plain `ws://` URL, for a developer typing it into a tool by hand. */
    fun websocketUrl(host: String, port: Int): String =
        "ws://" + host + ":" + port + CompanionProtocol.PATH
}
