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
package app.indoorbike.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutLinkParserTest {

    private val json = "{\"id\":\"link-test\",\"name\":\"Link Test\",\"segments\":[{\"lengthInSeconds\":60,\"powerPercentFTP\":50,\"intervalType\":\"CONSTANT\"}]}"

    @Test
    fun `a gzipped share link decodes to the workout`() {
        assertEquals(json, WorkoutLinkParser.extractEmbeddedJson("https://indoorbike.app/share/#wz=H4sIAAAAAAAC_6tWykxRslLKyczL1i1JLS5R0lHKS8xNBQr5AIUUQiBCxanpual5JcVKVtHVSjmpeeklGZ55wanJ-XkpQDEzAx2lgvzy1KKA1KJkoDK3kAAlK1OgYGZeSWpRWWJOSGUByERnf7_gEEe_EKXa2FoA0A8dB3gAAAA"))
    }

    @Test
    fun `a legacy plain share link decodes too`() {
        assertEquals(json, WorkoutLinkParser.extractEmbeddedJson("https://indoorbike.app/share/#w=eyJpZCI6ImxpbmstdGVzdCIsIm5hbWUiOiJMaW5rIFRlc3QiLCJzZWdtZW50cyI6W3sibGVuZ3RoSW5TZWNvbmRzIjo2MCwicG93ZXJQZXJjZW50RlRQIjo1MCwiaW50ZXJ2YWxUeXBlIjoiQ09OU1RBTlQifV19"))
    }

    @Test
    fun `the app scheme carries the same payloads`() {
        assertEquals(json, WorkoutLinkParser.extractEmbeddedJson("indoorbike://import?dataz=H4sIAAAAAAAC_6tWykxRslLKyczL1i1JLS5R0lHKS8xNBQr5AIUUQiBCxanpual5JcVKVtHVSjmpeeklGZ55wanJ-XkpQDEzAx2lgvzy1KKA1KJkoDK3kAAlK1OgYGZeSWpRWWJOSGUByERnf7_gEEe_EKXa2FoA0A8dB3gAAAA"))
        assertEquals(json, WorkoutLinkParser.extractEmbeddedJson("indoorbike://import?data=eyJpZCI6ImxpbmstdGVzdCIsIm5hbWUiOiJMaW5rIFRlc3QiLCJzZWdtZW50cyI6W3sibGVuZ3RoSW5TZWNvbmRzIjo2MCwicG93ZXJQZXJjZW50RlRQIjo1MCwiaW50ZXJ2YWxUeXBlIjoiQ09OU1RBTlQifV19"))
    }

    @Test
    fun `whitespace around a scanned link is ignored`() {
        assertEquals(json, WorkoutLinkParser.extractEmbeddedJson("  https://indoorbike.app/share/#wz=H4sIAAAAAAAC_6tWykxRslLKyczL1i1JLS5R0lHKS8xNBQr5AIUUQiBCxanpual5JcVKVtHVSjmpeeklGZ55wanJ-XkpQDEzAx2lgvzy1KKA1KJkoDK3kAAlK1OgYGZeSWpRWWJOSGUByERnf7_gEEe_EKXa2FoA0A8dB3gAAAA\n"))
    }

    @Test
    fun `a link without a payload is left for fetching`() {
        assertNull(WorkoutLinkParser.extractEmbeddedJson("https://example.com/workout.json"))
        assertNull(WorkoutLinkParser.extractEmbeddedJson("indoorbike://import?url=https://example.com/w.json"))
        assertNull(WorkoutLinkParser.extractEmbeddedJson(""))
    }

    @Test
    fun `a payload that is not gzip is refused rather than guessed at`() {
        assertNull(WorkoutLinkParser.extractEmbeddedJson("https://indoorbike.app/share/#wz=eyJpZCI6ImxpbmstdGVzdCIsIm5hbWUiOiJMaW5rIFRlc3QiLCJzZWdtZW50cyI6W3sibGVuZ3RoSW5TZWNvbmRzIjo2MCwicG93ZXJQZXJjZW50RlRQIjo1MCwiaW50ZXJ2YWxUeXBlIjoiQ09OU1RBTlQifV19"))
    }
}
