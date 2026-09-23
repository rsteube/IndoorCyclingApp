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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256

// The key exchange and AES are not implemented on iOS yet: do them alongside
// the iOS BLE stack, so they can be tested against a real controller rather
// than guessed. HMAC is here already because companion pairing signs with it
// too (CompanionSecurity), and it is covered by the common reference vectors.
private const val UNSUPPORTED = "Zwift controller crypto is not implemented on iOS"

actual class ZwiftKeyPair {
    actual val publicKey: ByteArray
        get() = throw NotImplementedError(UNSUPPORTED)

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray =
        throw NotImplementedError(UNSUPPORTED)
}

actual fun generateZwiftKeyPair(): ZwiftKeyPair = throw NotImplementedError(UNSUPPORTED)

@OptIn(ExperimentalForeignApi::class)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // addressOf(0) is out of bounds on an empty array; CCHmac takes null there.
    key.usePinned { k ->
        data.usePinned { d ->
            out.usePinned { o ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    if (key.isEmpty()) null else k.addressOf(0),
                    key.size.convert(),
                    if (data.isEmpty()) null else d.addressOf(0),
                    data.size.convert(),
                    o.addressOf(0),
                )
            }
        }
    }
    return out
}

actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
    throw NotImplementedError(UNSUPPORTED)
