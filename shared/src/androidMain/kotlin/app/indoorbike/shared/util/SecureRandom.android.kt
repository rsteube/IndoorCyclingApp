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
package app.indoorbike.shared.util

import java.security.SecureRandom

/**
 * One instance rather than one per call. `SecureRandom` seeds itself from the
 * system entropy pool on construction, which is the expensive part; reseeding
 * for every 16-byte nonce would make a handshake noticeably slower for no gain.
 * It is thread-safe.
 */
private val source = SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "cannot ask for $n bytes" }
    return ByteArray(n).also { if (n > 0) source.nextBytes(it) }
}
