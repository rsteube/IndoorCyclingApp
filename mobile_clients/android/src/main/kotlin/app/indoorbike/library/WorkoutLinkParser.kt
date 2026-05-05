package app.indoorbike.library

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Decodes a workout (or repository manifest) embedded directly inside a URL
 * — the path the website's editor uses to hand workouts off without needing
 * a backend to host the JSON.
 *
 * Two URL shapes are recognised:
 *
 *   https://indoorbike.app/share/#w=<base64-url-safe-utf8-json>
 *   indoorbike://import?data=<base64-url-safe-utf8-json>
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

        // Form 1: anything with `#w=…` in the fragment.
        val hashIdx = url.indexOf('#')
        if (hashIdx >= 0) {
            val fragment = url.substring(hashIdx + 1)
            val payload = paramFromFragment(fragment, "w")
            if (payload != null) return decodeBase64Utf8(payload)
        }

        // Form 2: indoorbike://import?data=…
        if (url.startsWith("indoorbike://", ignoreCase = true)) {
            val q = url.indexOf('?')
            if (q >= 0) {
                val payload = paramFromFragment(url.substring(q + 1), "data")
                if (payload != null) return decodeBase64Utf8(payload)
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

    private fun decodeBase64Utf8(encoded: String): String? = try {
        // URL_SAFE matches the JS encoder in editor.js (`+`/`/` -> `-`/`_`,
        // padding stripped). NO_PADDING + NO_WRAP make the decoder lenient.
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        String(bytes, StandardCharsets.UTF_8)
    } catch (t: Throwable) {
        null
    }
}
