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
package app.indoorbike.shared.fit

import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.sync.IntervalsIcuUpload
import app.indoorbike.shared.workout.COMPANION_NAME
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who made a ride, read back out of the file the way another service reads it.
 *
 * The report behind this: VertMatch showed Indoor Bike rides as "Recorded with
 * 1", because the file said manufacturer 255, product 1 and nothing else, and
 * nothing in a ride said it had been ridden in Col de Berg.
 */
class RideAttributionTest {

    private val colDeBerg = VirtualWorld(platform = "Col de Berg", route = "Stage 3: Kesselberg")

    @Test
    fun `every file names the app that wrote it`() {
        val file = read(build(appVersion = "2.8.1"))

        val fileId = file.single(FitMessage.FILE_ID)
        assertEquals(255L, fileId.number(1))
        assertEquals(1L, fileId.number(2))
        assertEquals("Indoor Bike", fileId.text(8))

        assertEquals(281L, file.single(FitMessage.FILE_CREATOR).number(0))

        val creator = file.single(FitMessage.DEVICE_INFO)
        assertEquals(0L, creator.number(0), "device index 0 is the creator of the file")
        assertEquals(255L, creator.number(2))
        assertEquals(1L, creator.number(4))
        assertEquals(281L, creator.number(5))
        assertEquals(5L, creator.number(25), "source type local")
        assertEquals("Indoor Bike", creator.text(27))

        assertEquals(281L, file.single(FitMessage.DEVELOPER_DATA_ID).number(4))
    }

    @Test
    fun `a ride no game drove says nothing about a world`() {
        val file = read(build())
        val names = file.all(FitMessage.FIELD_DESCRIPTION).map { it.text(3) }
        assertTrue(names.none { it.startsWith("virtual_") }, "declared $names")

        val session = file.single(FitMessage.SESSION)
        assertEquals(6L, session.number(6), "sub_sport indoor_cycling")
        assertTrue(session.developer.isEmpty())
    }

    @Test
    fun `a ride a game drove names the game and its course on the session`() {
        val file = read(build(world = colDeBerg))

        val worldFields = file.all(FitMessage.FIELD_DESCRIPTION)
            .filter { it.text(3).startsWith("virtual_") }
            .associateBy { it.text(3) }
        val platform = worldFields.getValue("virtual_platform")
        val route = worldFields.getValue("virtual_route")
        for (field in listOf(platform, route)) {
            assertEquals(0L, field.number(0), "developer data index")
            assertEquals(7L, field.number(2), "a string")
            assertEquals(18L, field.number(14), "carried on the session")
        }
        // A field number is unique within its namespace, and the lap targets
        // already hold 0 to 3 there.
        val numbers = file.all(FitMessage.FIELD_DESCRIPTION).map { it.number(1) }
        assertEquals(numbers.size, numbers.toSet().size, "field numbers $numbers")

        val session = file.single(FitMessage.SESSION)
        assertEquals(58L, session.number(6), "sub_sport virtual_activity")
        assertEquals("Col de Berg", session.developerText(platform.number(1).toInt()))
        assertEquals("Stage 3: Kesselberg", session.developerText(route.number(1).toInt()))
    }

    @Test
    fun `a game that named no course is credited without one`() {
        val file = read(build(world = VirtualWorld(platform = "Col de Berg")))
        val names = file.all(FitMessage.FIELD_DESCRIPTION).map { it.text(3) }
        assertTrue("virtual_platform" in names)
        assertTrue("virtual_route" !in names)
        assertEquals(1, file.single(FitMessage.SESSION).developer.size)
    }

    @Test
    fun `a long name is cut on a character boundary`() {
        val w = FitWriter()
        w.utf8("Col de Berg: Brücke über die Isar", 15)
        val bytes = w.bytes()
        assertEquals(15, bytes.size)
        val text = bytes.decodeToString().trimEnd('\u0000')
        // Fifteen bytes, one of them the terminator: fourteen of text.
        assertEquals("Col de Berg: B", text)
        assertEquals(0, bytes.last().toInt())

        val w2 = FitWriter()
        w2.utf8("Brücke", 4) // "Br" plus half of "ü" would fit three bytes
        assertEquals("Br", w2.bytes().decodeToString().trimEnd('\u0000'))
    }

    @Test
    fun `the app version is written as FIT reads a version`() {
        assertEquals(281, RideAttribution.softwareVersion("2.8.1"))
        assertEquals(281, RideAttribution.softwareVersion("2.8.1-debug"))
        assertEquals(290, RideAttribution.softwareVersion("2.9"))
        assertEquals(300, RideAttribution.softwareVersion("3"))
        // Two-digit minor versions have no column of their own; held, not carried.
        assertEquals(290, RideAttribution.softwareVersion("2.12"))
        assertNull(RideAttribution.softwareVersion(""))
        assertNull(RideAttribution.softwareVersion("dev"))
    }

    @Test
    fun `an unknown version is written as unknown`() {
        val file = read(build(appVersion = ""))
        assertEquals(0xFFFFL, file.single(FitMessage.FILE_CREATOR).number(0))
        assertEquals(0xFFFF_FFFFL, file.single(FitMessage.DEVELOPER_DATA_ID).number(4))
    }

    @Test
    fun `the world comes from the game that drove the ride and the course it named`() {
        val ride = session(workoutId = COMPANION_WORKOUT_ID, workoutName = "Stage 3: Kesselberg")
        assertEquals(colDeBerg, VirtualWorld.of(ride, "Col de Berg"))
        assertEquals(
            VirtualWorld("Col de Berg", route = null),
            VirtualWorld.of(session(workoutId = COMPANION_WORKOUT_ID, workoutName = COMPANION_NAME), "Col de Berg"),
        )
        assertNull(VirtualWorld.of(ride, null), "a game that never said who it was")
        assertNull(VirtualWorld.of(ride, "  "))
        assertNull(VirtualWorld.of(session(workoutId = "sweet-spot", workoutName = "Sweet spot"), "Col de Berg"))
    }

    @Test
    fun `an Intervals upload names the device and where the ride was ridden`() {
        assertEquals(
            listOf(
                "device_name" to "Indoor Bike",
                "name" to "Col de Berg: Stage 3: Kesselberg",
                "description" to "Ridden in Col de Berg on Indoor Bike",
                "external_id" to "phone:42",
            ),
            IntervalsIcuUpload.parameters("Stage 3: Kesselberg", "phone:42", colDeBerg),
        )
        assertEquals(
            listOf("device_name" to "Indoor Bike", "name" to "Sweet spot", "external_id" to "phone:43"),
            IntervalsIcuUpload.parameters("Sweet spot", "phone:43", world = null),
        )
        assertEquals("Col de Berg", IntervalsIcuUpload.activityName(COMPANION_NAME, VirtualWorld("Col de Berg")))
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun session(workoutId: String = "test", workoutName: String = "Test") = WorkoutSession(
        id = 7, workoutId = workoutId, workoutName = workoutName,
        startTimeEpoch = START, durationSeconds = 120,
        avgPower = 150L, avgCadence = 90L, avgHeartRate = 140L,
        externalId = "test:7",
        intervalsUploadedEpoch = 0L,
        fitTrackeeUploadedEpoch = 0L,
        routeDistanceMeters = 0.0,
        routeElevationGainMeters = 0.0,
        routeStartMeters = 0.0,
        routeStartElevationGainMeters = 0.0,
        routeShape = "",
        routeProfile = "",
        ftp = 0L,
        segmentMarks = "",
    )

    private fun build(appVersion: String = "2.8.1", world: VirtualWorld? = null): ByteArray {
        val telemetry = (0 until 120).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 7, timestampEpoch = START + i * 1000L,
                power = 150L, cadence = 90L, heartRate = 140L,
            )
        }
        return RideFitBuilder.build(
            session(workoutId = if (world != null) COMPANION_WORKOUT_ID else "test"),
            telemetry, workout = null, ftp = 200,
            appVersion = appVersion, world = world,
        )
    }

    // ── A FIT reader that keeps every field's bytes ─────────────────────────

    private class Message(val global: Int, val fields: Map<Int, ByteArray>, val developer: Map<Int, ByteArray>) {
        fun number(field: Int): Long {
            val bytes = fields.getValue(field)
            var value = 0L
            for (i in bytes.indices) value = value or ((bytes[i].toLong() and 0xFF) shl (8 * i))
            return value
        }

        fun text(field: Int): String = fields.getValue(field).decodeToString().substringBefore('\u0000')

        fun developerText(field: Int): String = developer.getValue(field).decodeToString().substringBefore('\u0000')
    }

    private class File(val messages: List<Message>) {
        fun all(global: Int) = messages.filter { it.global == global }
        fun single(global: Int) = all(global).single()
    }

    private fun read(bytes: ByteArray): File {
        class Def(val global: Int, val fields: List<Pair<Int, Int>>, val developer: List<Pair<Int, Int>>)
        val definitions = HashMap<Int, Def>()
        val out = mutableListOf<Message>()
        fun u8At(i: Int) = bytes[i].toInt() and 0xFF
        var pos = 14
        val end = bytes.size - 2
        while (pos < end) {
            val header = u8At(pos); pos += 1
            val localType = header and 0x0F
            if (header and 0x40 != 0) {
                pos += 2
                val global = u8At(pos) or (u8At(pos + 1) shl 8); pos += 2
                val count = u8At(pos); pos += 1
                val fields = (0 until count).map { i -> u8At(pos + i * 3) to u8At(pos + i * 3 + 1) }
                pos += count * 3
                val developer = if (header and 0x20 == 0) emptyList() else {
                    val devCount = u8At(pos); pos += 1
                    (0 until devCount).map { i -> u8At(pos + i * 3) to u8At(pos + i * 3 + 1) }
                        .also { pos += devCount * 3 }
                }
                definitions[localType] = Def(global, fields, developer)
            } else {
                val def = definitions[localType] ?: error("data message for undefined local type $localType")
                val fields = def.fields.associate { (num, size) ->
                    (num to bytes.copyOfRange(pos, pos + size)).also { pos += size }
                }
                val developer = def.developer.associate { (num, size) ->
                    (num to bytes.copyOfRange(pos, pos + size)).also { pos += size }
                }
                out += Message(def.global, fields, developer)
            }
        }
        assertEquals(end, pos, "reader must consume the body exactly")
        return File(out)
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
