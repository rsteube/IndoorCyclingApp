package app.indoorbike.library

import android.util.Base64
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Decodes a workout (or repository manifest) embedded directly inside a URL
 * — the path the website's editor uses to hand workouts off without needing
 * a backend to host the JSON.
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
 * No Android-framework `Uri` parsing on the hash-fragment branch — `Uri`
 * exposes `getFragment()` only after stripping query strings cleanly, but
 * our share-page URL has the fragment dangling off a path with no `?`. A
 * direct `indexOf('#')` is faster and survives whatever quirks the QR
 * scanner hands us (extra whitespace, trailing `/`, etc.).
 */
object WorkoutLinkParser {

    fun extractEmbeddedJson(rawUrl: String): String? {
        val url = rawUrl.trim().ifEmpty { return null }

        // Hash-fragment forms (https://…/share/#wz=… or #w=…).
        val hashIdx = url.indexOf('#')
        if (hashIdx >= 0) {
            val fragment = url.substring(hashIdx + 1)
            paramFromFragment(fragment, "wz")?.let { decodeBase64GzipUtf8(it) }?.let { return it }
            paramFromFragment(fragment, "w")?.let { decodeBase64Utf8(it) }?.let { return it }
        }

        // Deep-link forms (indoorbike://import?dataz=… or data=…).
        if (url.startsWith("indoorbike://", ignoreCase = true)) {
            val q = url.indexOf('?')
            if (q >= 0) {
                val query = url.substring(q + 1)
                paramFromFragment(query, "dataz")?.let { decodeBase64GzipUtf8(it) }?.let { return it }
                paramFromFragment(query, "data")?.let { decodeBase64Utf8(it) }?.let { return it }
            }
        }
        return null
    }

    private fun paramFromFragment(fragment: String, key: String): String? {
        // Fragments and queries follow the same `k=v&k=v` shape.
        for (pair in fragment.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals(key, ignoreCase = true)) {
                return pair.substring(eq + 1).ifEmpty { null }
            }
        }
        return null
    }

    private fun decodeBase64Bytes(encoded: String): ByteArray? = try {
        // URL_SAFE matches the JS encoder (`+`/`/` -> `-`/`_`, padding stripped).
        // NO_PADDING + NO_WRAP make the decoder lenient.
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    private fun decodeBase64Utf8(encoded: String): String? =
        decodeBase64Bytes(encoded)?.let { String(it, StandardCharsets.UTF_8) }

    private fun decodeBase64GzipUtf8(encoded: String): String? {
        val bytes = decodeBase64Bytes(encoded) ?: return null
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                gz.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }
}
