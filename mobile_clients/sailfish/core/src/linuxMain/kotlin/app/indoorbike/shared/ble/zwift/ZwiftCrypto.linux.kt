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
package app.indoorbike.shared.ble.zwift

import app.indoorbike.shared.util.sha256

/**
 * Zwift controllers on Sailfish OS wait for milestone 10 of
 * docs/SAILFISH_PORT.md, where the curve and the cipher come from the phone's
 * OpenSSL. They are not written here by hand, for the reason the expect
 * declarations give. HMAC is only a construction over SHA-256, so it is here
 * already, tested against RFC 4231 in linuxTest.
 */
private const val NOT_YET = "Zwift controllers are not supported on Sailfish OS yet"

actual class ZwiftKeyPair internal constructor() {
    actual val publicKey: ByteArray
        get() = throw UnsupportedOperationException(NOT_YET)

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray = throw UnsupportedOperationException(NOT_YET)
}

actual fun generateZwiftKeyPair(): ZwiftKeyPair = throw UnsupportedOperationException(NOT_YET)

/** HMAC (RFC 2104) over SHA-256, block size 64. */
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val block = 64
    val k = (if (key.size > block) sha256(key) else key).copyOf(block)
    val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(block) { (k[it].toInt() xor 0x5c).toByte() }
    return sha256(outer + sha256(inner + data))
}

actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray = throw UnsupportedOperationException(NOT_YET)
