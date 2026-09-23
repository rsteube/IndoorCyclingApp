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
package app.indoorbike.shared.ios

import app.indoorbike.shared.repo.TextFetcher
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTPS for the iOS app, under the rules the Android app's `HttpFetcher`
 * keeps: https only, no redirects (a 302 from a trusted-looking host must not
 * silently land on another origin), short timeouts, and a cap on every body.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosHttp {
    private const val TIMEOUT_SECONDS = 12.0
    private const val DOWNLOAD_TIMEOUT_SECONDS = 45.0
    private const val USER_AGENT = "IndoorBike/1.0 (iOS)"

    /** Cap on remote JSON and on a workout payload, as on Android. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    /** The shared repository reader's view of this. */
    val textFetcher = TextFetcher { url -> getText(url) }

    class HttpResponse(val code: Int, val body: ByteArray)

    /** Refuses every redirect: the request finishes with the 3xx itself. */
    private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) = completionHandler(null)
    }

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            timeoutIntervalForRequest = TIMEOUT_SECONDS
            timeoutIntervalForResource = DOWNLOAD_TIMEOUT_SECONDS * 2
        }
        NSURLSession.sessionWithConfiguration(config, delegate = NoRedirects(), delegateQueue = null)
    }

    private fun requireHttps(url: String): NSURL {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Only https:// URLs are allowed (got ${url.substringBefore(':')})"
        }
        return requireNotNull(NSURL.URLWithString(url)) { "Not a URL: $url" }
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
        timeoutSeconds: Double = TIMEOUT_SECONDS,
    ): HttpResponse {
        val request = NSMutableURLRequest.requestWithURL(requireHttps(url)).apply {
            setHTTPMethod(method)
            setTimeoutInterval(timeoutSeconds)
            setValue(USER_AGENT, forHTTPHeaderField = "User-Agent")
            for ((key, value) in headers) setValue(value, forHTTPHeaderField = key)
            if (body != null) setHTTPBody(body.toNSData())
        }
        return suspendCancellableCoroutine { continuation ->
            val task = session.dataTaskWithRequest(request) { data: NSData?, response: NSURLResponse?, error: NSError? ->
                when {
                    error != null -> continuation.resumeWithException(
                        IllegalStateException(error.localizedDescription)
                    )
                    data != null && data.length.toLong() > maxBytes -> continuation.resumeWithException(
                        IllegalStateException("Response too large (>${maxBytes / 1024} KB)")
                    )
                    else -> continuation.resume(
                        HttpResponse((response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0, data?.toByteArray() ?: ByteArray(0))
                    )
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    /** GET a text body, failing on anything but a 2xx. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = request(url, headers = headers + ("Accept" to "application/json, text/plain, */*"))
        check(response.code in 200..299) { "HTTP ${response.code} from $url" }
        return response.body.decodeToString()
    }

    /** GET a file of up to [maxBytes], failing on anything but a 2xx. */
    suspend fun getBytes(url: String, maxBytes: Long): ByteArray {
        val response = request(url, maxBytes = maxBytes, timeoutSeconds = DOWNLOAD_TIMEOUT_SECONDS)
        check(response.code in 200..299) { "HTTP ${response.code} from $url" }
        return response.body
    }

    /** Lower-case hex SHA-256, for checking a download against its listing. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { out ->
            if (bytes.isEmpty()) {
                CC_SHA256(null, 0u, out.addressOf(0))
            } else {
                bytes.usePinned { input -> CC_SHA256(input.addressOf(0), bytes.size.convert(), out.addressOf(0)) }
            }
        }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
