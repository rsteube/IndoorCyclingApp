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
package app.indoorbike.shared.ble.zwift

/**
 * The four primitives the Zwift controller session needs from the platform.
 *
 * Deliberately small: everything above these - HKDF, AES-CCM, the frame
 * layout, the handshake - lives in common code where it can be tested, because
 * that is where the bugs are. These four are here only because a curve and a
 * block cipher are not things to hand-roll.
 */
expect class ZwiftKeyPair {
    /** Uncompressed P-256 public point as X || Y, 64 bytes, no 0x04 prefix. */
    val publicKey: ByteArray

    /** ECDH against the controller's 64-byte public point. Returns 32 bytes. */
    fun sharedSecret(devicePublicKey: ByteArray): ByteArray
}

/** A fresh ephemeral P-256 key pair, one per connection. */
expect fun generateZwiftKeyPair(): ZwiftKeyPair

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** Raw AES block encrypt, no padding, no chaining. [block] is 16 bytes. */
expect fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray
