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
 * The "RideOn" handshake that opens a Zwift controller's session.
 *
 * Both directions carry the same shape: the six bytes of "RideOn", two version
 * bytes, then an uncompressed P-256 public point as X || Y. The version bytes
 * differ per device and firmware - a Click answers 01 03 where the published
 * notes describe 00 09 - so they are read but deliberately not checked.
 */
object ZwiftHandshake {
    /** "RideOn" in ASCII. Also the plaintext handshake used by Ride controllers. */
    val RIDE_ON: ByteArray = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)

    private val HELLO_VERSION = byteArrayOf(0x01, 0x02)

    const val PUBLIC_KEY_BYTES = 64
    private const val HEADER_BYTES = 8

    /** What the phone writes to the control point to start a session. */
    fun hello(publicKey: ByteArray): ByteArray {
        require(publicKey.size == PUBLIC_KEY_BYTES) {
            "public key must be $PUBLIC_KEY_BYTES bytes, was ${publicKey.size}"
        }
        return RIDE_ON + HELLO_VERSION + publicKey
    }

    /**
     * The controller's public point from its reply, or null if this is not a
     * handshake response - which is the normal answer on a device that speaks
     * the older plaintext protocol and simply echoes "RideOn" back.
     */
    fun devicePublicKey(response: ByteArray): ByteArray? {
        if (response.size != HEADER_BYTES + PUBLIC_KEY_BYTES) return null
        if (!response.startsWith(RIDE_ON)) return null
        return response.copyOfRange(HEADER_BYTES, response.size)
    }

    /** True for the bare "RideOn" an unencrypted controller answers with. */
    fun isPlaintextAck(response: ByteArray): Boolean =
        response.size < HEADER_BYTES + PUBLIC_KEY_BYTES && response.startsWith(RIDE_ON)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}

/**
 * A live encrypted session with a Zwift controller.
 *
 * Every frame the controller sends is a four-byte little-endian counter, the
 * ciphertext, then a four-byte MIC. The counter is both the replay guard and
 * part of the nonce, so it is used exactly as it arrives on the wire.
 */
class ZwiftSession internal constructor(
    private val key: ByteArray,
    private val noncePrefix: ByteArray,
) {
    /**
     * Plaintext of [frame], or null if it is too short or fails its MIC. A
     * failure is not recoverable by retrying: it means the frame was not
     * written by the peer this session was established with.
     */
    fun decrypt(frame: ByteArray): ByteArray? {
        if (frame.size <= COUNTER_BYTES + MIC_BYTES) return null
        val nonce = noncePrefix + frame.copyOfRange(0, COUNTER_BYTES)
        return AesCcm.decrypt(
            key = key,
            nonce = nonce,
            ciphertext = frame.copyOfRange(COUNTER_BYTES, frame.size),
            macLength = MIC_BYTES,
        )
    }

    companion object {
        const val COUNTER_BYTES = 4
        const val MIC_BYTES = 4

        /** 32 bytes of AES key followed by the 4 bytes every nonce starts with. */
        private const val KEY_MATERIAL_BYTES = 36
        private const val AES_KEY_BYTES = 32

        /**
         * Derive the session from the controller's handshake reply, or null if
         * the reply is not one.
         */
        fun establish(keyPair: ZwiftKeyPair, response: ByteArray): ZwiftSession? {
            val devicePublicKey = ZwiftHandshake.devicePublicKey(response) ?: return null
            val material = hkdfSha256(
                // Salt order matters and is theirs, not ours: device first.
                salt = devicePublicKey + keyPair.publicKey,
                ikm = keyPair.sharedSecret(devicePublicKey),
                length = KEY_MATERIAL_BYTES,
            )
            return ZwiftSession(
                key = material.copyOfRange(0, AES_KEY_BYTES),
                noncePrefix = material.copyOfRange(AES_KEY_BYTES, KEY_MATERIAL_BYTES),
            )
        }
    }
}
