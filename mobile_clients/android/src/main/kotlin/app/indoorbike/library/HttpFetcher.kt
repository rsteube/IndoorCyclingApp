package app.indoorbike.library

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small HTTPS GET wrapper. Stays on `HttpURLConnection` to avoid pulling in
 * OkHttp/Ktor for what amounts to fetching tiny JSON files — keeps the F-Droid
 * footprint small and removes one dep that auditors would otherwise check.
 *
 * Returns the response body as a string, or throws on any non-2xx / IO error.
 * Caller is responsible for catching and surfacing user-facing messages.
 */
object HttpFetcher {

    private const val TIMEOUT_MS = 12_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024 // 2 MB cap on remote JSON

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            // We only allow HTTPS so a malicious link in a chat message can't
            // ship plaintext payloads onto someone's device.
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url")
            }
            connection.inputStream.use { stream ->
                val buf = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BODY_BYTES) {
                        throw IllegalStateException("Response too large (>${MAX_BODY_BYTES / 1024} KB)")
                    }
                    out.write(buf, 0, read)
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}
