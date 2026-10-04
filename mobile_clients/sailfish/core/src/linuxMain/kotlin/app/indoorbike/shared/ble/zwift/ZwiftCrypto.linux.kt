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

import app.indoorbike.shared.ble.zwift.openssl.EVP_CIPHER_CTX_free
import app.indoorbike.shared.ble.zwift.openssl.EVP_CIPHER_CTX_new
import app.indoorbike.shared.ble.zwift.openssl.EVP_CIPHER_CTX_set_padding
import app.indoorbike.shared.ble.zwift.openssl.EVP_EncryptInit_ex
import app.indoorbike.shared.ble.zwift.openssl.EVP_EncryptUpdate
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_CTX_free
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_CTX_new_from_name
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_CTX_new_from_pkey
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_CTX_set_group_name
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_derive
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_derive_init
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_derive_set_peer
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_free
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_fromdata
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_fromdata_init
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_generate
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_get_octet_string_param
import app.indoorbike.shared.ble.zwift.openssl.EVP_PKEY_keygen_init
import app.indoorbike.shared.ble.zwift.openssl.EVP_aes_128_ecb
import app.indoorbike.shared.ble.zwift.openssl.EVP_aes_192_ecb
import app.indoorbike.shared.ble.zwift.openssl.EVP_aes_256_ecb
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_BLD_free
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_BLD_new
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_BLD_push_octet_string
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_BLD_push_utf8_string
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_BLD_to_param
import app.indoorbike.shared.ble.zwift.openssl.OSSL_PARAM_free
import app.indoorbike.shared.util.sha256
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.size_tVar

// The curve and the block cipher on OpenSSL 3, the copy Ktor's curl engine
// links into the core (see libcrypto.def). Byte formats match the Android
// and iOS actuals exactly - public points as X || Y with no 0x04 prefix - and
// the common reference-vector tests hold all three to them. HMAC is only a
// construction over SHA-256, so it stays in Kotlin, tested against RFC 4231.

private const val COORD_BYTES = 32
private const val CURVE = "P-256"

// OpenSSL's names for the parameters, and which part of a key is imported.
private const val PARAM_GROUP = "group"
private const val PARAM_PUBLIC_KEY = "pub"
private const val SELECT_PUBLIC_KEY = 0x86 // EVP_PKEY_PUBLIC_KEY: every parameter and the public key

/**
 * The names and the curve as C strings that never go away. OpenSSL's parameter
 * builder keeps the pointers it is given, and the finished parameters point at
 * the names, so a Kotlin string converted for the call was gone before OpenSSL
 * read it, and every controller's key was refused as not being on the curve.
 */
@OptIn(ExperimentalForeignApi::class)
private val groupName = permanentCString(PARAM_GROUP)
@OptIn(ExperimentalForeignApi::class)
private val publicKeyName = permanentCString(PARAM_PUBLIC_KEY)
@OptIn(ExperimentalForeignApi::class)
private val curveName = permanentCString(CURVE)

@OptIn(ExperimentalForeignApi::class)
private fun permanentCString(text: String): CPointer<ByteVar> {
    val bytes = text.encodeToByteArray()
    val out = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    bytes.forEachIndexed { i, b -> out[i] = b }
    out[bytes.size] = 0
    return out
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual class ZwiftKeyPair internal constructor(private val key: CPointer<EVP_PKEY>) {

    // One key pair per controller connection, freed once it is collected.
    @Suppress("unused")
    private val cleaner = createCleaner(key) { EVP_PKEY_free(it) }

    actual val publicKey: ByteArray
        get() = memScoped {
            val point = ByteArray(1 + COORD_BYTES * 2)
            val length = alloc<size_tVar>()
            val ok = point.usePinned {
                EVP_PKEY_get_octet_string_param(key, PARAM_PUBLIC_KEY, it.addressOf(0).reinterpret(), point.size.convert(), length.ptr)
            }
            check(ok == 1 && length.value.toInt() == point.size) { "The public key could not be read" }
            // 0x04 || X || Y; the Android format drops the 0x04.
            point.copyOfRange(1, point.size)
        }

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray {
        require(devicePublicKey.size == COORD_BYTES * 2) {
            "device public key must be 64 bytes, was ${devicePublicKey.size}"
        }
        val peer = publicKeyFrom(byteArrayOf(0x04) + devicePublicKey)
        try {
            val ctx = EVP_PKEY_CTX_new_from_pkey(null, key, null) ?: error("No ECDH context")
            try {
                check(EVP_PKEY_derive_init(ctx) == 1) { "ECDH could not start" }
                check(EVP_PKEY_derive_set_peer(ctx, peer) == 1) { "The controller's public key was refused" }
                return memScoped {
                    val length = alloc<size_tVar>()
                    length.value = COORD_BYTES.convert()
                    val secret = ByteArray(COORD_BYTES)
                    val ok = secret.usePinned { EVP_PKEY_derive(ctx, it.addressOf(0).reinterpret(), length.ptr) }
                    check(ok == 1 && length.value.toInt() == COORD_BYTES) { "ECDH failed" }
                    secret
                }
            } finally {
                EVP_PKEY_CTX_free(ctx)
            }
        } finally {
            EVP_PKEY_free(peer)
        }
    }
}

/** A P-256 public key from its uncompressed point, which OpenSSL checks is on the curve. */
@OptIn(ExperimentalForeignApi::class)
private fun publicKeyFrom(point: ByteArray): CPointer<EVP_PKEY> {
    val builder = OSSL_PARAM_BLD_new() ?: error("Out of memory")
    try {
        // The builder copies the values only in to_param, so the point stays
        // pinned until then; the names are the permanent ones below.
        val params = point.usePinned { pinned ->
            check(OSSL_PARAM_BLD_push_utf8_string(builder, groupName, curveName, 0u) == 1) { "No curve" }
            val pushed = OSSL_PARAM_BLD_push_octet_string(builder, publicKeyName, pinned.addressOf(0), point.size.convert())
            check(pushed == 1) { "No point" }
            OSSL_PARAM_BLD_to_param(builder) ?: error("Out of memory")
        }
        try {
            val ctx = EVP_PKEY_CTX_new_from_name(null, "EC", null) ?: error("No EC support")
            try {
                return memScoped {
                    val key = alloc<CPointerVar<EVP_PKEY>>()
                    check(EVP_PKEY_fromdata_init(ctx) == 1) { "No EC import" }
                    check(EVP_PKEY_fromdata(ctx, key.ptr, SELECT_PUBLIC_KEY, params) == 1 && key.value != null) {
                        "The controller's public key is not a P-256 point"
                    }
                    key.value!!
                }
            } finally {
                EVP_PKEY_CTX_free(ctx)
            }
        } finally {
            OSSL_PARAM_free(params)
        }
    } finally {
        OSSL_PARAM_BLD_free(builder)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun generateZwiftKeyPair(): ZwiftKeyPair {
    val ctx = EVP_PKEY_CTX_new_from_name(null, "EC", null) ?: error("No EC support")
    try {
        check(EVP_PKEY_keygen_init(ctx) == 1) { "No EC key generation" }
        check(EVP_PKEY_CTX_set_group_name(ctx, CURVE) == 1) { "No $CURVE" }
        return memScoped {
            val key = alloc<CPointerVar<EVP_PKEY>>()
            check(EVP_PKEY_generate(ctx, key.ptr) == 1 && key.value != null) { "The key pair could not be made" }
            ZwiftKeyPair(key.value!!)
        }
    } finally {
        EVP_PKEY_CTX_free(ctx)
    }
}

/** HMAC (RFC 2104) over SHA-256, block size 64. */
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val block = 64
    val k = (if (key.size > block) sha256(key) else key).copyOf(block)
    val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(block) { (k[it].toInt() xor 0x5c).toByte() }
    return sha256(outer + sha256(inner + data))
}

@OptIn(ExperimentalForeignApi::class)
actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    require(block.size == 16) { "AES block must be 16 bytes, was ${block.size}" }
    val cipher = when (key.size) {
        16 -> EVP_aes_128_ecb()
        24 -> EVP_aes_192_ecb()
        32 -> EVP_aes_256_ecb()
        else -> throw IllegalArgumentException("AES key must be 16, 24 or 32 bytes, was ${key.size}")
    }
    val ctx = EVP_CIPHER_CTX_new() ?: error("Out of memory")
    try {
        // Room for a second block, which OpenSSL may write into even with no padding.
        val out = ByteArray(32)
        memScoped {
            val written = alloc<IntVar>()
            val started = key.usePinned { k ->
                EVP_EncryptInit_ex(ctx, cipher, null, k.addressOf(0).reinterpret<UByteVar>(), null)
            }
            check(started == 1) { "AES could not start" }
            EVP_CIPHER_CTX_set_padding(ctx, 0)
            val ok = out.usePinned { o ->
                block.usePinned { b ->
                    EVP_EncryptUpdate(ctx, o.addressOf(0).reinterpret(), written.ptr, b.addressOf(0).reinterpret(), block.size)
                }
            }
            check(ok == 1 && written.value == 16) { "AES failed" }
        }
        return out.copyOf(16)
    } finally {
        EVP_CIPHER_CTX_free(ctx)
    }
}
