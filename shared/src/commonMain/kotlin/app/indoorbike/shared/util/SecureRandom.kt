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
 * [n] bytes from the platform's cryptographically secure source.
 *
 * A separate declaration from `kotlin.random.Random` on purpose, and the
 * distinction is the whole point of the file. `Random.nextBytes` is seeded for
 * speed and statistical quality, not unpredictability, and it is exactly the
 * function somebody reaches for when they want "some random bytes". Pairing
 * keys and challenge nonces are not that: a predictable nonce turns a
 * challenge-response into a replay, and a predictable key is not a key.
 *
 * Never use this for anything where a fast, reproducible sequence would do.
 * The platform source is slower and, on a phone, can block briefly.
 */
expect fun secureRandomBytes(n: Int): ByteArray
