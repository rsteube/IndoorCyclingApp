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
 */package app.indoorbike.shared.companion

import app.indoorbike.shared.util.secureRandomBytes

/**
 * The device's IPv4 addresses a companion on the same network could reach:
 * up, not loopback, not link-local. Each platform lists its interfaces.
 */
expect fun localIPv4Addresses(): List<String>

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

    /**
     * The code a debug build always uses.
     *
     * A companion developer pairs dozens of times an hour, and reading six
     * fresh characters off a screen each time is friction with no payoff: a
     * debug build is a development tool on a development network, and the code
     * is protecting a mock trainer from a machine sitting next to it.
     *
     * It is emphatically *not* a fallback or an override. Release builds never
     * reach this branch, and because [BuildConfig.DEBUG] is a compile-time
     * constant R8 removes both the constant and the test from them entirely.
     */
    const val DEBUG_TOKEN = "AAAAAA"

    /**
     * The code is what stands between the network and the rider's trainer, so
     * it comes from the platform's cryptographic source. It came from
     * `kotlin.random`, whose output is predictable from enough of what it has
     * already produced.
     */
    /**
     * A fresh pairing code, for a session. It is not stored, and it is
     * replaced early if too many wrong ones are tried against it: see
     * `CompanionHost.rotateCode`.
     *
     * Except in a debug build, where it is always [DEBUG_TOKEN].
     */
    /** A fresh pairing code; always [DEBUG_TOKEN] in a debug build, so a test rig can hard-code it. */
    fun newToken(debug: Boolean): String {
        if (debug) return DEBUG_TOKEN
        // Rejection sampling: a byte is only used below the largest multiple of
        // the alphabet's size, so every letter is equally likely.
        val limit = 256 - 256 % ALPHABET.length
        val code = StringBuilder()
        while (code.length < CODE_LENGTH) {
            for (byte in secureRandomBytes(CODE_LENGTH * 2)) {
                val b = byte.toInt() and 0xFF
                if (b < limit && code.length < CODE_LENGTH) code.append(ALPHABET[b % ALPHABET.length])
            }
        }
        return code.toString()
    }

    /** A short id for this session, so a reconnecting companion can tell it is the same one. */
    fun newSessionId(): String =
        secureRandomBytes(6).map { "0123456789abcdef"[(it.toInt() and 0xFF) % 16] }.joinToString("")

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
    fun localAddresses(): List<String> = localIPv4Addresses()

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
