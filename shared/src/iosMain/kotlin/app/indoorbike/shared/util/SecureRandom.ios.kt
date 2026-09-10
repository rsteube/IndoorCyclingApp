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

/**
 * Not implemented, and deliberately not guessed at.
 *
 * There is no iOS client in this repository, and the iOS targets cannot be
 * built on the machine this was written on, so an implementation here would be
 * interop nobody had compiled. For a random number generator that is a bad
 * trade: the failure mode of "almost right" is a key that looks fine and is
 * predictable, which nothing downstream can detect.
 *
 * Whoever adds the iOS client should reach for `arc4random_buf` from
 * `platform.posix`, which is Darwin's recommended source, never blocks and has
 * no error path to forget. `SecRandomCopyBytes` is the other correct answer and
 * returns a status worth checking. Do not fall back to `kotlin.random.Random`:
 * see the expect declaration for why.
 */
actual fun secureRandomBytes(n: Int): ByteArray =
    throw NotImplementedError(
        "secureRandomBytes is not implemented on iOS; see the KDoc before writing one"
    )
