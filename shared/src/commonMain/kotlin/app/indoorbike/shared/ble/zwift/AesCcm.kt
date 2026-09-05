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

private const val BLOCK = 16

/**
 * AES-CCM decryption, RFC 3610, with no associated data.
 *
 * Hand-rolled rather than taken from a provider because Android's JCE does not
 * offer CCM (Conscrypt has GCM only), and pulling in a full crypto provider for
 * one mode is a poor trade. CCM is counter mode for the payload plus CBC-MAC
 * for the tag, both over the same block cipher, so the only platform primitive
 * needed is a raw AES block encrypt - even for decryption, since CTR decrypts
 * by encrypting the counter blocks.
 */
internal object AesCcm {

    /**
     * @param ciphertext payload followed by the [macLength]-byte MIC.
     * @return the plaintext, or null if the MIC does not match - a wrong key, a
     *   replayed counter and a corrupted frame are all indistinguishable here
     *   and all mean the same thing to the caller.
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, macLength: Int): ByteArray? {
        val lengthFieldSize = 15 - nonce.size
        require(lengthFieldSize in 2..8) { "nonce length ${nonce.size} leaves no room for a length field" }
        require(macLength in intArrayOf(4, 6, 8, 10, 12, 14, 16)) { "invalid MIC length $macLength" }
        if (ciphertext.size < macLength) return null

        val payloadSize = ciphertext.size - macLength
        val payload = ciphertext.copyOfRange(0, payloadSize)
        val mic = ciphertext.copyOfRange(payloadSize, ciphertext.size)

        // S_0 masks the MIC; S_1.. mask the payload.
        val plaintext = ByteArray(payloadSize)
        var offset = 0
        var counter = 1
        while (offset < payloadSize) {
            val stream = aesEncryptBlock(key, counterBlock(nonce, lengthFieldSize, counter))
            val take = minOf(BLOCK, payloadSize - offset)
            for (i in 0 until take) {
                plaintext[offset + i] = (payload[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += take
            counter++
        }

        val s0 = aesEncryptBlock(key, counterBlock(nonce, lengthFieldSize, 0))
        val expected = cbcMac(key, nonce, lengthFieldSize, plaintext, macLength)
        for (i in 0 until macLength) {
            if ((mic[i].toInt() xor s0[i].toInt()).toByte() != expected[i]) return null
        }
        return plaintext
    }

    /** A_i: flags, nonce, then the counter big-endian in the length field. */
    private fun counterBlock(nonce: ByteArray, lengthFieldSize: Int, counter: Int): ByteArray {
        val block = ByteArray(BLOCK)
        block[0] = (lengthFieldSize - 1).toByte()
        nonce.copyInto(block, 1)
        writeBigEndian(block, BLOCK - lengthFieldSize, lengthFieldSize, counter)
        return block
    }

    /** B_0 followed by the zero-padded payload, chained through AES. */
    private fun cbcMac(
        key: ByteArray,
        nonce: ByteArray,
        lengthFieldSize: Int,
        plaintext: ByteArray,
        macLength: Int,
    ): ByteArray {
        val b0 = ByteArray(BLOCK)
        // No associated data, so the Adata bit stays clear.
        b0[0] = (((macLength - 2) / 2) shl 3 or (lengthFieldSize - 1)).toByte()
        nonce.copyInto(b0, 1)
        writeBigEndian(b0, BLOCK - lengthFieldSize, lengthFieldSize, plaintext.size)

        var x = aesEncryptBlock(key, b0)
        var offset = 0
        while (offset < plaintext.size) {
            val block = ByteArray(BLOCK)
            val take = minOf(BLOCK, plaintext.size - offset)
            plaintext.copyInto(block, 0, offset, offset + take)
            for (i in 0 until BLOCK) {
                block[i] = (block[i].toInt() xor x[i].toInt()).toByte()
            }
            x = aesEncryptBlock(key, block)
            offset += take
        }
        return x.copyOfRange(0, macLength)
    }

    private fun writeBigEndian(target: ByteArray, at: Int, size: Int, value: Int) {
        var remaining = value
        for (i in size - 1 downTo 0) {
            target[at + i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
    }
}
