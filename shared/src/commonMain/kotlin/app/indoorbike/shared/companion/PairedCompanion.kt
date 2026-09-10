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

import kotlinx.serialization.Serializable

/**
 * A companion the rider has decided to trust, and the secret they share.
 *
 * One record per companion, keyed by [deviceId]. The rider agreed to this once,
 * on both devices, and everything after that happens without them: a paired
 * companion that finds the phone connects straight into the ride.
 *
 * [name] is what the companion called itself and is only ever shown to the
 * rider, so they can recognise a row in a list and take the trust back. It is
 * self-declared and therefore not an identity: two companions can claim the
 * same name and only [deviceId] and [keyHex] decide anything.
 */
@Serializable
data class PairedCompanion(
    /** Stable, opaque, chosen by the companion and never reused between them. */
    val deviceId: String,
    /** What it calls itself. For the rider's eyes, never for a decision. */
    val name: String,
    /**
     * The shared secret, as hex.
     *
     * The only thing in this app worth stealing from disk. It lives in
     * app-private storage, which on Android means another app cannot read it
     * without root, and it is not in the ride record, not in a backup document
     * and not in any log line. If it leaks, the answer is to forget the pairing
     * and make another: it authenticates a companion and nothing else, so the
     * blast radius is one companion driving one trainer.
     */
    val keyHex: String,
    val pairedAtEpochMs: Long,
    /** When it last proved itself. Shown to the rider so a stale row is obvious. */
    val lastSeenEpochMs: Long = 0,
)

/**
 * Where paired companions are kept.
 *
 * Suspending because it is disk, and an interface because the protocol core has
 * no business knowing what disk looks like. The session state machine never
 * touches this: it is handed a lookup function so it can stay synchronous and
 * testable with no store at all.
 */
interface PairedCompanionStore {
    suspend fun all(): List<PairedCompanion>
    suspend fun find(deviceId: String): PairedCompanion?
    suspend fun save(companion: PairedCompanion)
    suspend fun forget(deviceId: String)
    /** Take back every trust at once. The rider's panic button. */
    suspend fun forgetAll()
}
