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
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import kotlinx.cinterop.IntVar

// The four primitives on Apple's own crypto: P-256 and ECDH through the
// Security framework, HMAC-SHA256 and the AES block through CommonCrypto. Byte
// formats match the Android actuals exactly - public points as X || Y with no
// 0x04 prefix - and the common reference-vector tests hold both to them.

private const val COORD_BYTES = 32

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { bytes[it].toByte() }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef = usePinned {
    CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert())!!
}

@OptIn(ExperimentalForeignApi::class)
private fun ecAttributes(publicOnly: Boolean): CFMutableDictionaryRef = memScoped {
    val dict = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
    CFDictionarySetValue(dict, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    val bits = alloc<IntVar>().apply { value = 256 }
    val number = CFNumberCreate(null, kCFNumberIntType, bits.ptr)
    CFDictionarySetValue(dict, kSecAttrKeySizeInBits, number)
    CFRelease(number)
    if (publicOnly) CFDictionarySetValue(dict, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    dict
}

@OptIn(ExperimentalForeignApi::class)
actual class ZwiftKeyPair internal constructor(private val privateKey: SecKeyRef) {
    actual val publicKey: ByteArray
        get() {
            val public = SecKeyCopyPublicKey(privateKey) ?: error("No public key")
            val data = SecKeyCopyExternalRepresentation(public, null) ?: error("Public key not exportable")
            // 0x04 || X || Y; the Android format drops the 0x04.
            return data.toByteArray().copyOfRange(1, 1 + COORD_BYTES * 2).also {
                CFRelease(data)
                CFRelease(public)
            }
        }

    actual fun sharedSecret(devicePublicKey: ByteArray): ByteArray {
        require(devicePublicKey.size == COORD_BYTES * 2) {
            "device public key must be 64 bytes, was ${devicePublicKey.size}"
        }
        val attributes = ecAttributes(publicOnly = true)
        val data = (byteArrayOf(0x04) + devicePublicKey).toCFData()
        val devicePublic = SecKeyCreateWithData(data, attributes, null)
        CFRelease(data)
        CFRelease(attributes)
        devicePublic ?: error("The controller's public key is not a P-256 point")
        val parameters = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val secret = SecKeyCopyKeyExchangeResult(privateKey, kSecKeyAlgorithmECDHKeyExchangeStandard, devicePublic, parameters, null)
        CFRelease(parameters)
        CFRelease(devicePublic)
        secret ?: error("ECDH failed")
        return secret.toByteArray().also { CFRelease(secret) }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun generateZwiftKeyPair(): ZwiftKeyPair {
    val attributes = ecAttributes(publicOnly = false)
    val key = SecKeyCreateRandomKey(attributes, null)
    CFRelease(attributes)
    return ZwiftKeyPair(key ?: error("Couldn't make a P-256 key"))
}

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

@OptIn(ExperimentalForeignApi::class)
actual fun aesEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
    val out = ByteArray(block.size)
    memScoped {
        val moved = alloc<ULongVar>()
        val status = key.usePinned { k ->
            block.usePinned { b ->
                out.usePinned { o ->
                    CCCrypt(
                        kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode,
                        k.addressOf(0), key.size.convert(),
                        null,
                        b.addressOf(0), block.size.convert(),
                        o.addressOf(0), out.size.convert(),
                        moved.ptr,
                    )
                }
            }
        }
        check(status == kCCSuccess) { "AES failed ($status)" }
    }
    return out
}
