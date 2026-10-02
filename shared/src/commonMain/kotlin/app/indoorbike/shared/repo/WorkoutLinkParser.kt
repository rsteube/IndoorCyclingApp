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
package app.indoorbike.shared.repo

import app.indoorbike.shared.util.gunzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decodes a workout (or repository manifest) embedded directly inside a URL
 * — the path the website's editor uses to hand workouts off without needing
 * a backend to host the JSON. Shared by both apps.
 *
 * Four URL shapes are recognised:
 *
 *   https://indoorbike.app/share/#wz=<base64-url-safe-gzip-utf8-json>   (current)
 *   https://indoorbike.app/share/#w=<base64-url-safe-utf8-json>         (legacy plain)
 *   indoorbike://import?dataz=<base64-url-safe-gzip-utf8-json>
 *   indoorbike://import?data=<base64-url-safe-utf8-json>                (legacy plain)
 *
 * The gzipped forms shrink typical workout payloads roughly in half, which
 * drops the QR a few versions and makes modules chunky enough for phone
 * cameras to scan reliably. Plain forms remain supported so older links and
 * NFC tags keep working.
 *
 * Returns the decoded JSON string, or `null` when the URL has no embedded
 * payload (in which case the caller should fall back to its normal HTTP
 * fetch path).
 *
 * The fragment is found with a plain `indexOf('#')` rather than URL parsing:
 * the share page's fragment dangles off a path with no `?`, and this survives
 * whatever a QR scanner hands over (extra whitespace, a trailing `/`).
 */
object WorkoutLinkParser {

    /** Cap on a decompressed payload: the same as a fetched workout's body. */
    const val MAX_PAYLOAD_BYTES = 2L * 1024 * 1024

    fun extractEmbeddedJson(rawUrl: String): String? {
        val url = rawUrl.trim().ifEmpty { return null }

        // Hash-fragment forms (https://…/share/#wz=… or #w=…).
        val hashIdx = url.indexOf('#')
        if (hashIdx >= 0) {
            val fragment = url.substring(hashIdx + 1)
            param(fragment, "wz")?.let { decodeBase64GzipUtf8(it) }?.let { return it }
            param(fragment, "w")?.let { decodeBase64Utf8(it) }?.let { return it }
        }

        // Deep-link forms (indoorbike://import?dataz=… or data=…).
        if (url.startsWith("indoorbike://", ignoreCase = true)) {
            val q = url.indexOf('?')
            if (q >= 0) {
                val query = url.substring(q + 1)
                param(query, "dataz")?.let { decodeBase64GzipUtf8(it) }?.let { return it }
                param(query, "data")?.let { decodeBase64Utf8(it) }?.let { return it }
            }
        }
        return null
    }

    /** A `k=v&k=v` value by key, ignoring case; fragments and queries share the shape. */
    private fun param(pairs: String, key: String): String? {
        for (pair in pairs.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).equals(key, ignoreCase = true)) {
                return pair.substring(eq + 1).ifEmpty { null }
            }
        }
        return null
    }

    /** URL-safe alphabet, padding optional: what the website's encoder writes. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Bytes(encoded: String): ByteArray? = try {
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded.trim())
    } catch (t: Throwable) {
        null
    }

    private fun decodeBase64Utf8(encoded: String): String? =
        decodeBase64Bytes(encoded)?.decodeToString()

    private fun decodeBase64GzipUtf8(encoded: String): String? =
        decodeBase64Bytes(encoded)?.let { gunzip(it, MAX_PAYLOAD_BYTES) }?.decodeToString()
}
