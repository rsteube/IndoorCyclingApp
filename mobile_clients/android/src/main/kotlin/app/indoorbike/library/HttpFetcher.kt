package app.indoorbike.library

import java.io.ByteArrayOutputStream
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
            // Off by design: a 302 from a trusted-looking host to attacker-
            // controlled HTTPS would otherwise let a malicious deep-link
            // (`indoorbike://import?url=trusted.example`) silently land on
            // a different origin. Servers that genuinely move endpoints can
            // surface a 4xx or 410 instead and the user can paste the new URL.
            instanceFollowRedirects = false
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

    /**
     * Multipart-form POST of a single binary file. Used for activity uploads
     * to Intervals.icu (and is generic enough for any other "POST a file"
     * endpoint we might add later).
     *
     * Returns [PostResult] with the HTTP status code and response body so the
     * caller can surface platform-specific error text in toasts.
     */
    suspend fun postMultipartFile(
        url: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String = "application/octet-stream",
        extraFormFields: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): PostResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val boundary = "----IndoorBike${System.nanoTime().toString(16)}"
        val body = buildMultipartBody(boundary, fileFieldName, fileName, fileBytes, fileMimeType, extraFormFields)

        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS * 4   // uploads can take longer than fetches
            requestMethod = "POST"
            // Off by design: a 302 from a trusted-looking host to attacker-
            // controlled HTTPS would otherwise let a malicious deep-link
            // (`indoorbike://import?url=trusted.example`) silently land on
            // a different origin. Servers that genuinely move endpoints can
            // surface a 4xx or 410 instead and the user can paste the new URL.
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            // Read whichever stream actually has bytes (success vs error).
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            PostResult(code = code, body = text.take(4096))
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String,
        extraFormFields: Map<String, String>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n".toByteArray(StandardCharsets.UTF_8)

        // Plain string form fields
        for ((k, v) in extraFormFields) {
            out.write(("--$boundary").toByteArray(StandardCharsets.UTF_8)); out.write(crlf)
            out.write(("Content-Disposition: form-data; name=\"$k\"").toByteArray(StandardCharsets.UTF_8)); out.write(crlf); out.write(crlf)
            out.write(v.toByteArray(StandardCharsets.UTF_8)); out.write(crlf)
        }
        // File part
        out.write(("--$boundary").toByteArray(StandardCharsets.UTF_8)); out.write(crlf)
        out.write(("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"").toByteArray(StandardCharsets.UTF_8)); out.write(crlf)
        out.write(("Content-Type: $fileMimeType").toByteArray(StandardCharsets.UTF_8)); out.write(crlf); out.write(crlf)
        out.write(fileBytes); out.write(crlf)

        // Closing boundary
        out.write(("--$boundary--").toByteArray(StandardCharsets.UTF_8)); out.write(crlf)
        return out.toByteArray()
    }

    data class PostResult(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299
    }
}
