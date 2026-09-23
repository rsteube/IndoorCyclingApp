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
package app.indoorbike.shared.net

/**
 * The HTTPS a sync or upload needs, as each platform does it. Every
 * implementation keeps the same posture: https only, no redirects followed,
 * short timeouts, and a cap on what is read back.
 *
 * [getText] throws on anything but a 2xx; the rest return the status and the
 * reply, whatever the status, so a caller can say what the server said.
 */
interface HttpClient {
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): PostResult
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): PostResult
    suspend fun postMultipartFile(
        url: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String = "application/octet-stream",
        extraFormFields: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): PostResult
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): PostResult
}

data class PostResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/**
 * A multipart/form-data body: each plain field, then the file part, then the
 * closing boundary, all with CRLF line ends.
 */
fun buildMultipartBody(
    boundary: String,
    fileFieldName: String,
    fileName: String,
    fileBytes: ByteArray,
    fileMimeType: String,
    extraFormFields: Map<String, String>,
): ByteArray {
    val out = ArrayList<Byte>(fileBytes.size + 512)
    fun write(text: String) { out.addAll(text.encodeToByteArray().toList()) }
    for ((k, v) in extraFormFields) {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
        write("$v\r\n")
    }
    write("--$boundary\r\n")
    write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n")
    write("Content-Type: $fileMimeType\r\n\r\n")
    out.addAll(fileBytes.toList())
    write("\r\n")
    write("--$boundary--\r\n")
    return out.toByteArray()
}

/**
 * `application/x-www-form-urlencoded` encoding, as Java's URLEncoder writes it:
 * letters, digits and `.-*_` kept, a space as `+`, everything else as `%XX`
 * of its UTF-8 bytes.
 */
fun formEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }
}

fun formBody(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (k, v) -> formEncode(k) + "=" + formEncode(v) }

private const val HEX = "0123456789ABCDEF"

/** The query parameters of [url], decoded; the last of a repeated name wins. */
fun queryParameters(url: String): Map<String, String> {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return emptyMap()
    return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        formDecode(key) to formDecode(value)
    }
}

private fun formDecode(text: String): String {
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            c == '%' && i + 2 < text.length ->
                text.substring(i + 1, i + 3).toIntOrNull(16)?.let { bytes.add(it.toByte()); i += 3 }
                    ?: run { bytes.addAll("%".encodeToByteArray().toList()); i++ }
            else -> { bytes.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}
