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

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val COORD_BYTES = 32

/** Left-pad to [size], and drop the sign byte BigInteger may prepend. */
private fun BigInteger.toFixed(size: Int): ByteArray {
    val raw = toByteArray()
    if (raw.size == size) return raw
    val out = ByteArray(size)
    if (raw.size > size) {
        // Leading zero sign byte.
        raw.copyInto(out, 0, raw.size - size, raw.size)
    } else {
        raw.copyInto(out, size - raw.size, 0, raw.size)
    }
    return out
}

actual class ZwiftKeyPair(private val keyPair: KeyPair) {
    actual val publicKey: ByteArray
        get() {
            val point = (keyPair.public as ECPublicKey).w
            return point.affineX.toFixed(COORD_BYTES) + point.affineY.toFixed(COORD_BYTES)
        }

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray {
        require(devicePublicKey.size == COORD_BYTES * 2) {
            "device public key must be 64 bytes, was ${devicePublicKey.size}"
        }
        val x = BigInteger(1, devicePublicKey.copyOfRange(0, COORD_BYTES))
        val y = BigInteger(1, devicePublicKey.copyOfRange(COORD_BYTES, COORD_BYTES * 2))
        val params = (keyPair.public as ECPublicKey).params
        val devicePublic = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        return KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(devicePublic, true)
            generateSecret()
        }
    }
}

actual fun generateZwiftKeyPair(): ZwiftKeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return ZwiftKeyPair(generator.generateKeyPair())
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        // An all-zero salt is legal HKDF but an illegal HMAC key on the JCE,
        // so give it something rather than let it throw.
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        doFinal(data)
    }

actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(block)
    }
