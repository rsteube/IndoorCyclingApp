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
package app.indoorbike.shared.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `finish` a Godot game sent at the end of a real ride, byte for byte.
 *
 * Its route pack holds whole-metre elevations, but Godot's `JSON.parse_string`
 * makes every number a float and `JSON.stringify` writes each one back as
 * `921.0`. This build refused it as malformed and closed the socket, so the
 * ride never ended, and the rider's next ride was saved on top of it.
 */
internal const val GODOT_FINISH_FRAME =
    """{"summary":{"profile":[921.0,919.0,918.0,919.0,919.0,919.0,919.0,919.0,919.0,923.0,931.0,939.0,950.0,959.0,966.0,972.0,976.0,979.0,985.0,993.0,1002.0,1011.0,1018.0,1026.0,1031.0,1035.0,1037.0,1038.0,1036.0,1032.0,1027.0,1022.0,1019.0,1017.0,1016.0,1017.0,1019.0,1020.0,1020.0,1021.0,1021.0,1022.0,1023.0,1028.0,1032.0,1037.0,1042.0,1044.0,1040.0,1031.0,1019.0,1007.0,993.0,978.0,964.0,950.0,934.0,924.0,919.0,917.0,916.0,915.0,915.0,915.0],"shape":"e_q`HmhvcAt@Dv@Dt@@t@Dt@At@Gt@?t@Kr@Ot@Ot@Dp@^r@Xr@Pt@Jt@Ft@Dt@Ft@Pr@Tr@Rt@Pt@Fp@[n@e@r@Ot@Ft@J`@\\WdAg@t@]bA[bAWfAQjAKlAHlAKlAYbAUfAAnAKjAg@v@GjAAnACnAInAShA[bAa@|@YdAo@Zo@Ze@v@a@~@e@t@i@n@CnA@nABnAHlARjAFlAJnAHlANlATfAThAThAThANjAPjARhAXdAXfAXdAFlAWbAQjAGnACnACnACnAAnA?nAHlAThANhAIfAFnAGnAGlABnANjAHlACpAQjA?lAJlALlAKlAKlA]`Aq@\\u@?u@@u@Du@Du@Eq@]q@?q@_@i@q@[cAQiAYgAi@q@SiAUiASkAUiAYeAYcAe@a@o@_@]{@KmAMmAKmAKmAGeA`@}@TiAPgAa@_A]_ASgACoAJkAPkANkA\\cAj@Ip@Nb@}@`@{@RkAPkAPkATgATiAd@u@b@s@NkARkAImAImA_@{@ImATeAd@w@n@c@t@Al@`@b@z@J]OmAMmAYcAi@s@ImAGoAUgAEoAAgAWiA[cAa@}@q@_@u@Ha@q@[cA_@_Ag@u@c@{@YgAWeAYgAUiASiAWgA[cA[eA[_Ak@o@MiAOeAg@q@Y{@"},"t":"finish"}"""

/** Frames that are right in value but not in spelling, or right in all but one field. */
class CompanionFrameToleranceTest {

    private fun ok(text: String): Decoded.Ok =
        assertIs<Decoded.Ok>(CompanionCodec.decodeClientFrame(text), text.take(120))

    @Test
    fun `the finish a Godot game sent is read whole`() {
        val decoded = ok(GODOT_FINISH_FRAME)
        val summary = assertIs<FinishFrame>(decoded.frame).summary
        assertEquals(64, summary?.profile?.size)
        assertEquals(921, summary?.profile?.first())
        assertEquals(1044, summary?.profile?.max())
        assertTrue(summary?.shape.orEmpty().startsWith("e_q`H"))
        assertEquals(emptyMap(), decoded.unreadable)
    }

    @Test
    fun `a whole number spelled with a fraction is the integer it is`() {
        assertEquals(1, assertIs<RequestShiftFrame>(ok("""{"t":"requestShift","steps":1.0}""").frame).steps)
        assertEquals(412L, assertIs<WorldFrame>(ok("""{"t":"world","seq":4.12e2,"gradient":0.0,"speedMps":0}""").frame).seq)
        val drivetrain = assertIs<DrivetrainFrame>(
            ok("""{"t":"drivetrain","chainrings":[50.0,34.0],"sprockets":[11.0,28.0]}""").frame
        )
        assertEquals(listOf(50, 34), drivetrain.chainrings)
    }

    /** A spelling is forgiven; a count with a fraction in it is a bug, and still said to be. */
    @Test
    fun `a real fraction where a count belongs is still malformed`() {
        assertIs<Decoded.Malformed>(CompanionCodec.decodeClientFrame("""{"t":"requestShift","steps":1.5}"""))
    }

    @Test
    fun `text that looks like a number stays text`() {
        assertEquals("1.0", assertIs<LabelFrame>(ok("""{"t":"label","name":"1.0"}""").frame).name)
    }

    @Test
    fun `profile samples are rounded to the metre`() {
        val course = assertIs<CourseFrame>(ok("""{"t":"course","profile":[921.4,919.6,-3.4]}""").frame)
        assertEquals(listOf(921, 920, -3), course.profile)
        val finish = assertIs<FinishFrame>(ok("""{"t":"finish","summary":{"profile":[12.7]}}""").frame)
        assertEquals(listOf(13), finish.summary?.profile)
    }

    @Test
    fun `a finish with an unreadable profile keeps its shape and still ends the ride`() {
        val decoded = ok("""{"t":"finish","summary":{"shape":"abc","profile":["high","low"]}}""")
        val summary = assertIs<FinishFrame>(decoded.frame).summary
        assertEquals("abc", summary?.shape)
        assertEquals(emptyList(), summary?.profile)
        assertEquals(setOf("summary.profile"), decoded.unreadable.keys)
    }

    @Test
    fun `a finish whose summary is not an object is still a finish`() {
        val decoded = ok("""{"t":"finish","summary":"yes please"}""")
        assertNull(assertIs<FinishFrame>(decoded.frame).summary)
        assertEquals(setOf("summary"), decoded.unreadable.keys)
    }

    @Test
    fun `a course keeps its name when its distance is unreadable`() {
        val decoded = ok("""{"t":"course","name":"Weilheim loop","distanceM":"3.4 km"}""")
        val course = assertIs<CourseFrame>(decoded.frame)
        assertEquals("Weilheim loop", course.name)
        assertNull(course.distanceM)
        assertEquals(setOf("distanceM"), decoded.unreadable.keys)
    }

    /** Only the two frames that describe a ride are taken in part. Steering is all or nothing. */
    @Test
    fun `every other frame is still all or nothing`() {
        assertIs<Decoded.Malformed>(
            CompanionCodec.decodeClientFrame("""{"t":"world","seq":1,"gradient":"steep","speedMps":4.0}""")
        )
        assertIs<Decoded.Malformed>(
            CompanionCodec.decodeClientFrame("""{"t":"drivetrain","chainrings":["big"],"sprockets":[11]}""")
        )
    }
}
