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

// There is no iOS client in this repository yet. The declarations exist so the
// iOS targets still compile; implement them alongside an iOS BLE stack, not
// before, so they can be tested against a real controller rather than guessed.
private const val UNSUPPORTED = "Zwift controller crypto is not implemented on iOS"

actual class ZwiftKeyPair {
    actual val publicKey: ByteArray
        get() = throw NotImplementedError(UNSUPPORTED)

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray =
        throw NotImplementedError(UNSUPPORTED)
}

actual fun generateZwiftKeyPair(): ZwiftKeyPair = throw NotImplementedError(UNSUPPORTED)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    throw NotImplementedError(UNSUPPORTED)

actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
    throw NotImplementedError(UNSUPPORTED)
