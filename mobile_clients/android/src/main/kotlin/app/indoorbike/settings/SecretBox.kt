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
package app.indoorbike.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.indoorbike.shared.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the credentials the app keeps - the Intervals.icu token and API key,
 * the FitTrackee client secret and tokens - with a key that never leaves the
 * Android Keystore, so the settings file on its own no longer gives them away.
 *
 * They used to be stored as plain text. The file is private to the app and
 * kept out of backups, which covers an ordinary phone; this covers one that is
 * rooted or has been compromised, where the file can be read but the Keystore
 * key cannot be taken out of it.
 *
 * A sealed value is `ks1:` followed by base64 of the GCM nonce and ciphertext.
 * Anything without the prefix is a value stored before sealing existed, read
 * as it is and sealed at the next write.
 */
internal class SecretBox(private val alias: String = "indoorbike.credentials") {

    /**
     * [plain], sealed. Throws when the Keystore cannot be used; the caller
     * decides what to do about a device where it is broken.
     */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /**
     * The value behind [stored]: opened if sealed, as it is if not. Null for
     * nothing stored, and for a sealed value that cannot be opened (its key is
     * gone, as it is after the app's data moves to another device), which
     * reads as not connected rather than as a credential made of ciphertext.
     */
    fun open(stored: String?): String? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey() ?: return null,
                GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
            )
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "A stored credential could not be opened: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    companion object {
        private const val PREFIX = "ks1:"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val LOG_TAG = "SecretBox"

        /** Whether [stored] is a sealed value rather than one kept in the clear. */
        fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)
    }
}
