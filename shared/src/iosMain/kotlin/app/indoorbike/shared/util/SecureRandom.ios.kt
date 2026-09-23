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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

/**
 * `arc4random_buf` is Darwin's recommended source: it draws from the kernel
 * CSPRNG, never blocks and has no error path to forget. Do not fall back to
 * `kotlin.random.Random`: see the expect declaration for why.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "cannot ask for $n bytes" }
    val bytes = ByteArray(n)
    if (n > 0) bytes.usePinned { arc4random_buf(it.addressOf(0), n.convert()) }
    return bytes
}
