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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntervalsIcuOAuthTest {

    @Test
    fun `the consent url carries the client, the verified redirect, the scope and the state`() {
        val url = IntervalsIcuOAuth.authorizeUrl(clientId = "123", state = "s t/ate")
        assertEquals(
            "https://intervals.icu/oauth/authorize?client_id=123" +
                "&redirect_uri=https%3A%2F%2Findoorbike.app%2Foauth%2Fintervals" +
                "&scope=ACTIVITY%3AWRITE&state=s%20t%2Fate",
            url,
        )
    }

    @Test
    fun `only the https link to indoorbike app is the redirect`() {
        assertTrue(IntervalsIcuOAuth.isRedirect("https", "indoorbike.app", "/oauth/intervals"))
        assertTrue(IntervalsIcuOAuth.isRedirect("HTTPS", "IndoorBike.app", "/oauth/intervals/"))
        assertFalse(IntervalsIcuOAuth.isRedirect("indoorbike", "oauth", null))
        assertFalse(IntervalsIcuOAuth.isRedirect("https", "evil.example", "/oauth/intervals"))
        assertFalse(IntervalsIcuOAuth.isRedirect("http", "indoorbike.app", "/oauth/intervals"))
        assertFalse(IntervalsIcuOAuth.isRedirect("https", "indoorbike.app", "/oauth/fittrackee"))
    }

    @Test
    fun `a code with the state that was stored is ours to spend`() {
        val callback = IntervalsIcuOAuth.readCallback(mapOf("code" to "abc", "state" to "xyz"), expectedState = "xyz")
        assertEquals(IntervalsIcuOAuth.Callback.Code("abc"), callback)
    }

    /** A redirect this app did not ask for must not be able to sign the rider in to somebody else's account. */
    @Test
    fun `a code with the wrong state, or none stored, is refused`() {
        assertIs<IntervalsIcuOAuth.Callback.Refused>(
            IntervalsIcuOAuth.readCallback(mapOf("code" to "abc", "state" to "other"), expectedState = "xyz")
        )
        assertIs<IntervalsIcuOAuth.Callback.Refused>(
            IntervalsIcuOAuth.readCallback(mapOf("code" to "abc", "state" to "xyz"), expectedState = null)
        )
        assertIs<IntervalsIcuOAuth.Callback.Refused>(
            IntervalsIcuOAuth.readCallback(mapOf("state" to "xyz"), expectedState = "xyz")
        )
    }

    @Test
    fun `a rider who says no is told so in plain words`() {
        val refused = assertIs<IntervalsIcuOAuth.Callback.Refused>(
            IntervalsIcuOAuth.readCallback(mapOf("error" to "access_denied", "state" to "xyz"), expectedState = "xyz")
        )
        assertEquals("Intervals.icu access was not allowed.", refused.message)
    }

    @Test
    fun `a token response gives the token and who it belongs to`() {
        val connection = IntervalsIcuOAuth.parseToken(
            """{"token_type":"Bearer","access_token":"t0k3n","scope":"ACTIVITY:WRITE",""" +
                """"athlete":{"id":"i12345","name":"Juhani"}}"""
        )
        assertEquals("t0k3n", connection?.accessToken)
        assertEquals("i12345", connection?.athleteId)
        assertEquals("Juhani", connection?.athleteName)
        assertEquals("ACTIVITY:WRITE", connection?.scope)
    }

    @Test
    fun `a response without a token is not a connection`() {
        assertNull(IntervalsIcuOAuth.parseToken("""{"error":"invalid_grant"}"""))
        assertNull(IntervalsIcuOAuth.parseToken("<html>Bad gateway</html>"))
        assertNull(IntervalsIcuOAuth.parseToken(""))
    }

    @Test
    fun `an error response is read for the rider, and html is not shown to them`() {
        assertEquals("Code expired", IntervalsIcuOAuth.errorMessage("""{"error":"invalid_grant","error_description":"Code expired"}"""))
        assertEquals("Unauthorized", IntervalsIcuOAuth.errorMessage("Unauthorized"))
        assertNull(IntervalsIcuOAuth.errorMessage("<html><body>502</body></html>"))
    }
}
