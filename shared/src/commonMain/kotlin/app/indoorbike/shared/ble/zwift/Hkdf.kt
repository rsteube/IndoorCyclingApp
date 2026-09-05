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

private const val HASH_BYTES = 32

/**
 * HKDF-SHA256 (RFC 5869), extract-then-expand.
 *
 * Zwift's controllers derive their session key this way: salt is the two
 * public keys concatenated, input keying material is the ECDH shared secret,
 * and 36 bytes come out - 32 for the AES key and 4 that prefix every nonce.
 */
fun hkdfSha256(salt: ByteArray, ikm: ByteArray, length: Int, info: ByteArray = ByteArray(0)): ByteArray {
    require(length > 0 && length <= HASH_BYTES * 255) { "invalid HKDF output length $length" }
    val prk = hmacSha256(salt, ikm)
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val take = minOf(previous.size, length - written)
        previous.copyInto(out, written, 0, take)
        written += take
        counter++
    }
    return out
}
