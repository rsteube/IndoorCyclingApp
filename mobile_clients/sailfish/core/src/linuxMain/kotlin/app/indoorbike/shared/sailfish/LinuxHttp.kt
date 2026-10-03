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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.net.HttpClient
import app.indoorbike.shared.net.PostResult
import app.indoorbike.shared.net.buildMultipartBody
import app.indoorbike.shared.net.formBody
import app.indoorbike.shared.repo.TextFetcher
import app.indoorbike.shared.util.secureRandomBytes
import app.indoorbike.shared.util.sha256
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength

/**
 * HTTPS for the Sailfish app, under the rules the Android app's `HttpFetcher`
 * and iOS's `IosHttp` keep: https only, no redirects (a 302 from a
 * trusted-looking host must not silently land on another origin), short
 * timeouts, and a cap on every body.
 *
 * On Ktor's curl engine, never Qt's network stack: Qt 5.6's TLS fails its
 * handshake with current servers on Sailfish OS 5.1, and took the process
 * down with it in the Companion for UNA. Curl brings its own TLS and uses the
 * phone's certificates. The engine is named here rather than found: in a
 * Kotlin/Native shared library the linker drops the registration a bare
 * `HttpClient()` looks for.
 */
internal object LinuxHttp {
    private const val TIMEOUT_MS = 12_000L
    private const val DOWNLOAD_TIMEOUT_MS = 45_000L
    private const val USER_AGENT = "IndoorBike/1.0 (Sailfish OS)"

    /** Cap on remote JSON and on a workout payload, as on Android. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    private val client = io.ktor.client.HttpClient(Curl) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS * 2
            connectTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
        }
    }

    /** The shared repository reader's view of this. */
    val textFetcher = TextFetcher { url -> getText(url) }

    class Response(val code: Int, val body: ByteArray)

    private fun requireHttps(url: String) {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${url.substringBefore(':')})"
        }
    }

    /**
     * Send a request and return the status and body, whatever the status. The
     * body is capped at [maxBytes]: a longer one fails rather than being cut.
     */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Long = MAX_BODY_BYTES,
        timeoutMs: Long = TIMEOUT_MS,
    ): Response {
        requireHttps(url)
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            timeout { requestTimeoutMillis = timeoutMs }
            header("User-Agent", USER_AGENT)
            for ((key, value) in headers) header(key, value)
            if (body != null) setBody(body)
        }
        response.contentLength()?.let { check(it <= maxBytes) { "Response too large (>${maxBytes / 1024} KB)" } }
        val bytes = response.bodyAsBytes()
        check(bytes.size <= maxBytes) { "Response too large (>${maxBytes / 1024} KB)" }
        return Response(response.status.value, bytes)
    }

    /** GET a text body, failing on anything but a 2xx. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = request(url, headers = headers + ("Accept" to "application/json, text/plain, */*"))
        check(response.code in 200..299) { "HTTP ${response.code} from $url" }
        return response.body.decodeToString()
    }

    /** GET a file of up to [maxBytes], failing on anything but a 2xx. */
    suspend fun getBytes(url: String, maxBytes: Long): ByteArray {
        val response = request(url, maxBytes = maxBytes, timeoutMs = DOWNLOAD_TIMEOUT_MS)
        check(response.code in 200..299) { "HTTP ${response.code} from $url" }
        return response.body
    }

    /** Lower-case hex SHA-256, for checking a download against its listing. */
    fun sha256Hex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** The shared uploaders' and sync's [HttpClient], on [LinuxHttp]. The iOS `IosHttpClient`'s counterpart. */
internal object LinuxHttpClient : HttpClient {
    override suspend fun getText(url: String, headers: Map<String, String>): String = LinuxHttp.getText(url, headers)

    override suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>): PostResult =
        post(url, formBody(fields).encodeToByteArray(), "application/x-www-form-urlencoded", headers)

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): PostResult =
        post(url, json.encodeToByteArray(), "application/json", headers)

    override suspend fun postMultipartFile(
        url: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String,
        extraFormFields: Map<String, String>,
        headers: Map<String, String>,
    ): PostResult {
        val boundary = "IndoorBike" + secureRandomBytes(12).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val body = buildMultipartBody(boundary, fileFieldName, fileName, fileBytes, fileMimeType, extraFormFields)
        return post(url, body, "multipart/form-data; boundary=$boundary", headers, timeoutMs = 48_000L)
    }

    override suspend fun delete(url: String, headers: Map<String, String>): PostResult {
        val response = LinuxHttp.request(url, method = "DELETE", headers = headers)
        return PostResult(response.code, response.body.decodeToString())
    }

    private suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long = 12_000L,
    ): PostResult {
        val response = LinuxHttp.request(
            url,
            method = "POST",
            headers = headers + ("Content-Type" to contentType),
            body = body,
            timeoutMs = timeoutMs,
        )
        return PostResult(response.code, response.body.decodeToString())
    }
}
