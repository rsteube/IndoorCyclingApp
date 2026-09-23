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
package app.indoorbike.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small HTTPS GET wrapper. Stays on `HttpURLConnection` to avoid pulling in
 * OkHttp/Ktor for what amounts to fetching tiny JSON files - keeps the
 * dependency surface small, and one fewer library is one fewer thing to vet
 * against the project's no-Google rule.
 *
 * Returns the response body as a string, or throws on any non-2xx / IO error.
 * Caller is responsible for catching and surfacing user-facing messages.
 */
object HttpFetcher {

    private const val TIMEOUT_MS = 12_000

    /**
     * Cap on remote JSON, and on a workout payload however it arrives: a
     * shared file or a link's decompressed data is held to the same size.
     */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /**
     * How much of a POST or DELETE reply is kept: enough for a token, an id
     * or an error message. Only this much is read, so a server that answers
     * with something enormous (the FitTrackee server is the rider's own
     * choice) costs no more than this.
     */
    private const val RESPONSE_CHARS = 4096

    /** [RESPONSE_CHARS] in bytes, at UTF-8's widest, so the kept text is always whole. */
    private const val RESPONSE_BYTES = RESPONSE_CHARS * 4

    /**
     * Read timeout for [downloadTo]. Longer than [TIMEOUT_MS] because a route
     * GPX on a phone connection is a real transfer rather than a request that
     * either answers promptly or is broken.
     */
    private const val DOWNLOAD_READ_TIMEOUT_MS = 45_000

    /**
     * Cap on a single route GPX. Roughly a 500 km route at the point spacing
     * a listing should be published at, so it is generous for anything
     * rideable while still refusing a file that would fill the device.
     */
    const val MAX_ROUTE_BYTES = 12L * 1024 * 1024

    suspend fun getString(
        url: String,
        /** Extra request headers, e.g. a bearer token for a rider's own server. */
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
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
            for ((k, v) in headers) setRequestProperty(k, v)
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
    /**
     * POST a JSON body and return the raw response. Used by the FitTrackee
     * sign-in, which trades an email and password for a token; everything
     * after that is a multipart upload like the Intervals one.
     *
     * Same HTTPS-only and no-redirect posture as the rest of this object.
     */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): PostResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readPrefix(RESPONSE_BYTES), StandardCharsets.UTF_8) } ?: ""
            PostResult(code = code, body = text.take(RESPONSE_CHARS))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST an `application/x-www-form-urlencoded` body. OAuth2 token endpoints
     * take their parameters this way rather than as JSON.
     */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): PostResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val body = fields.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }.toByteArray(StandardCharsets.UTF_8)

        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readPrefix(RESPONSE_BYTES), StandardCharsets.UTF_8) } ?: ""
            PostResult(code = code, body = text.take(RESPONSE_CHARS))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * DELETE with headers and no body. Used to revoke an Intervals.icu token,
     * which is the one place a connection is undone on the server rather than
     * only forgotten here.
     */
    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): PostResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "DELETE"
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readPrefix(RESPONSE_BYTES), StandardCharsets.UTF_8) } ?: ""
            PostResult(code = code, body = text.take(RESPONSE_CHARS))
        } finally {
            connection.disconnect()
        }
    }

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
            val text = stream?.use { String(it.readPrefix(RESPONSE_BYTES), StandardCharsets.UTF_8) } ?: ""
            PostResult(code = code, body = text.take(RESPONSE_CHARS))
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

    /**
     * Streams a file to disk instead of into a string. Used for route GPX,
     * which is the one thing this app downloads that is not tiny.
     *
     * [getString] caps at [MAX_BODY_BYTES] and buffers the whole body in
     * memory, which is exactly right for a manifest and exactly wrong for a
     * route: a 100 km GPX runs to a couple of megabytes, and it is going
     * straight to disk anyway. So this streams, caps separately at
     * [MAX_ROUTE_BYTES], and hashes as it goes, since the bytes are already
     * passing through.
     *
     * The download lands on a `.part` sibling and is renamed onto
     * [destination] only once it is complete, so a connection that dies
     * halfway can never leave a truncated GPX where a valid one is expected.
     *
     * Same posture as the rest of this object: HTTPS only, redirects not
     * followed. Redirects bite harder here than elsewhere, because a route
     * often points at a third-party host that moved, so the thrown message
     * names the situation instead of surfacing a bare failure.
     */
    suspend fun downloadTo(
        url: String,
        destination: File,
        maxBytes: Long = MAX_ROUTE_BYTES,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${parsed.protocol})"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/gpx+xml, application/xml, text/xml, */*")
            setRequestProperty("User-Agent", "IndoorBike/1.0 (Android)")
        }
        val part = File(destination.parentFile, destination.name + ".part")
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                throw IllegalStateException(
                    "$url redirects (HTTP $code). Ask the repository maintainer for the direct file URL."
                )
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url")
            }
            destination.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { stream ->
                part.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buf)
                        if (read == -1) break
                        total += read
                        if (total > maxBytes) {
                            throw IllegalStateException(
                                "Route file is larger than ${maxBytes / (1024 * 1024)} MB"
                            )
                        }
                        digest.update(buf, 0, read)
                        out.write(buf, 0, read)
                    }
                }
            }
            if (total == 0L) throw IllegalStateException("Empty response from $url")
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
            }
            DownloadResult(
                bytes = total,
                sha256 = digest.digest().joinToString("") { b -> "%02x".format(b) },
            )
        } finally {
            if (part.exists()) part.delete()
            connection.disconnect()
        }
    }

    data class PostResult(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299
    }

    /** What [downloadTo] wrote, so a caller can verify it against a listing. */
    data class DownloadResult(val bytes: Long, val sha256: String)
}
