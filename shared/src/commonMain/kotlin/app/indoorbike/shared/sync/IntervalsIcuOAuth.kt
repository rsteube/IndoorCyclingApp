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
package app.indoorbike.shared.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The parts of signing in to Intervals.icu that involve no network and no
 * platform: the consent URL, reading what the browser hands back, and reading
 * the token.
 *
 * ## The flow
 *
 *   browser -> https://intervals.icu/oauth/authorize?client_id=...&state=...
 *   back    -> https://indoorbike.app/oauth/intervals?code=...&state=...
 *   POST       https://intervals.icu/api/oauth/token  (client_id, client_secret, code)
 *
 * Intervals.icu asks apps used by more than one person to use OAuth rather
 * than a personal API key, and its OAuth has no PKCE: the token exchange needs
 * the client secret. This project is open source, so the secret is not in the
 * repository. It is injected at build time from a gitignored file, the same
 * way the release signing key is, and a build without it offers the personal
 * API key instead. What a secret recovered from a shipped APK allows is posing
 * as this app on the consent screen; it reads nobody's data unless that rider
 * approves.
 *
 * ## Why the redirect is an https link and not `indoorbike://`
 *
 * Any app on a phone can claim a custom scheme, and the code travels in the
 * redirect. An https link to indoorbike.app is verified against the site's
 * `/.well-known/assetlinks.json`, so only this app, signed with this project's
 * key, is handed it.
 *
 * Intervals.icu issues no refresh token: an access token lasts until the rider
 * authorises again or disconnects, and the code must be exchanged within two
 * minutes of the redirect.
 */
object IntervalsIcuOAuth {

    const val AUTHORIZE_URL = "https://intervals.icu/oauth/authorize"
    const val TOKEN_URL = "https://intervals.icu/api/oauth/token"

    /** Revokes this app's token for the athlete. `DELETE`, with the bearer token. */
    const val DISCONNECT_URL = "https://intervals.icu/api/v1/disconnect-app"

    /**
     * Registered with Intervals.icu for this app, and matched by the manifest's
     * verified app link. Both must change together.
     */
    const val REDIRECT_URI = "https://indoorbike.app/oauth/intervals"
    const val REDIRECT_HOST = "indoorbike.app"
    const val REDIRECT_PATH = "/oauth/intervals"

    /**
     * Only what the app does with it today: upload a finished ride. Reading the
     * calendar or sport settings would need `CALENDAR:READ` or `SETTINGS:READ`,
     * and a rider should be asked for those when a feature uses them, not
     * before. Asking again replaces the token.
     */
    const val SCOPES = "ACTIVITY:WRITE"

    /** Uploads with an OAuth token go to athlete `0`, which means whoever the token belongs to. */
    const val ACTIVITIES_URL = "https://intervals.icu/api/v1/athlete/0/activities"

    fun authorizeUrl(clientId: String, state: String): String =
        AUTHORIZE_URL +
            "?client_id=" + encode(clientId) +
            "&redirect_uri=" + encode(REDIRECT_URI) +
            "&scope=" + encode(SCOPES) +
            "&state=" + encode(state)

    /** Whether a link that opened the app is the Intervals.icu redirect. */
    fun isRedirect(scheme: String?, host: String?, path: String?): Boolean =
        scheme.equals("https", ignoreCase = true) &&
            host.equals(REDIRECT_HOST, ignoreCase = true) &&
            path?.trimEnd('/') == REDIRECT_PATH

    /** What the browser handed back. */
    sealed interface Callback {
        data class Code(val code: String) : Callback

        /** Declined, unverifiable, or not ours. [message] is for the rider. */
        data class Refused(val message: String) : Callback
    }

    /**
     * Read the redirect's query parameters.
     *
     * A `state` that does not match the one stored when the browser was opened
     * means the redirect did not come from the request this app made, so its
     * code is not ours to spend. No stored state at all means the sign-in was
     * not started here, or was already used.
     */
    fun readCallback(parameters: Map<String, String?>, expectedState: String?): Callback {
        parameters["error"]?.let { error ->
            return Callback.Refused(
                if (error == "access_denied") "Intervals.icu access was not allowed."
                else "Intervals.icu declined: $error",
            )
        }
        if (expectedState == null) {
            return Callback.Refused("This sign-in wasn't started here. Try again.")
        }
        if (parameters["state"] != expectedState) {
            return Callback.Refused("Sign-in couldn't be verified. Try again.")
        }
        val code = parameters["code"]?.takeIf { it.isNotBlank() }
            ?: return Callback.Refused("No authorization code came back.")
        return Callback.Code(code)
    }

    /** A signed-in rider, as the token endpoint describes them. */
    data class Connection(
        val accessToken: String,
        val athleteId: String?,
        val athleteName: String?,
        val scope: String?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Read a token response:
     * `{"token_type":"Bearer","access_token":"...","scope":"ACTIVITY:WRITE","athlete":{"id":"i12345","name":"..."}}`.
     * Null when there is no access token in it.
     */
    fun parseToken(body: String): Connection? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val token = root.string("access_token")?.takeIf { it.isNotBlank() } ?: return null
        val athlete = root["athlete"] as? JsonObject
        return Connection(
            accessToken = token,
            athleteId = athlete?.string("id"),
            athleteName = athlete?.string("name")?.takeIf { it.isNotBlank() },
            scope = root.string("scope"),
        )
    }

    /** The most readable complaint in an error response, if there is one. */
    fun errorMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.let { it.string("error_description") ?: it.string("error") ?: it.string("message") }
            ?: body.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
        return message?.trim()?.take(160)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else it.contentOrNull }

    /** Percent-encoding for a query value: everything but RFC 3986's unreserved characters. */
    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
                c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
