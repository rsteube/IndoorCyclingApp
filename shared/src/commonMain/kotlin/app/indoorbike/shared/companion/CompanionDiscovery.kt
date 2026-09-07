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
package app.indoorbike.shared.companion

import kotlinx.serialization.Serializable

/**
 * What the phone shouts on the local network so a companion can find it
 * without anybody typing an address.
 *
 * ## Why this exists when there is already mDNS
 *
 * mDNS is the right answer and stays the primary one: it is standard, other
 * tools already speak it, and Android publishes it through the platform. The
 * problem is the other end. Godot has no DNS-SD in core, Unity needs a package,
 * and a developer's first hour should not be spent shopping for an mDNS
 * library. Meanwhile every engine and language has a UDP socket.
 *
 * So the phone does both. A companion that can browse `_indoorbike._tcp` should;
 * one that cannot listens on [CompanionProtocol.DISCOVERY_PORT] for these, which
 * is a dozen lines in any environment.
 *
 * ## What is deliberately not in here
 *
 * **The pairing code.** Discovery answers "which phone", pairing answers "may
 * you drive it". Broadcasting the code to the whole subnet would collapse those
 * two questions into one and hand control of a trainer to anything listening.
 *
 * **The phone's address.** A datagram already carries its source, and that
 * address is by definition one the listener can reach - which is more than can
 * be said for any address the phone might nominate, given it usually has
 * several and cannot know which of them the other machine can see.
 */
@Serializable
data class CompanionAnnouncement(
    /** Magic, so a listener can ignore whatever else shares the port. */
    val t: String = CompanionProtocol.DISCOVERY_MAGIC,
    /** Supported protocol range, `min-max`. Lets a companion rule the phone out before connecting. */
    val proto: String = CompanionProtocol.SUPPORTED.toString(),
    val path: String = CompanionProtocol.PATH,
    /** The WebSocket port. Ephemeral, which is the whole reason this is broadcast. */
    val port: Int,
    val app: String,
    /** Stable for the life of one ride, so a reconnecting companion can tell it is the same one. */
    val session: String,
    /** What to show in a device picker. */
    val name: String,
    /** True when a controller already holds this ride. A picker can grey it out. */
    val paired: Boolean = false,
)

/**
 * Reads announcements off the wire.
 *
 * Tolerant in the same way the frame codec is: anything that is not a
 * well-formed announcement with the right magic is simply not one, and never an
 * error. A discovery port is a place where stray traffic is normal.
 */
object CompanionDiscovery {

    fun encode(announcement: CompanionAnnouncement): String =
        CompanionCodec.json.encodeToString(CompanionAnnouncement.serializer(), announcement)

    /** Null when [text] is not an announcement this build understands. */
    fun decode(text: String): CompanionAnnouncement? {
        val parsed = runCatching {
            CompanionCodec.json.decodeFromString(CompanionAnnouncement.serializer(), text)
        }.getOrNull() ?: return null
        if (parsed.t != CompanionProtocol.DISCOVERY_MAGIC) return null
        if (parsed.port !in 1..65535) return null
        return parsed
    }
}
