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

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.ActiveTelemetryCodec
import app.indoorbike.shared.simulation.Drivetrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun session(state: String = CompanionState.ACTIVE) = CompanionSession(
    config = CompanionConfig(appVersion = "2.9.0", sessionId = "3f9a1c", token = TOKEN),
    snapshot = ::snapshot,
    rideState = { state },
).also {
    it.onFrame(
        """{"t":"hello","protoMin":"0.1","protoMax":"0.11","token":"$TOKEN","client":{"name":"Col de Berg"}}""",
        nowMs = 0,
    )
}

private fun world(seq: Long, extra: String) =
    """{"t":"world","seq":$seq,"gradient":0.05,"speedMps":8.0$extra}"""

private fun List<CompanionEffect>.position(): CompanionPosition? =
    filterIsInstance<CompanionEffect.ReportPosition>().singleOrNull()?.position

private fun List<CompanionEffect>.warnings(): List<WarningFrame> =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<WarningFrame>()

/**
 * Where the rider is on a companion's course, from the game's own mouth, into
 * the ride record, and never worked out on the phone.
 */
class CompanionPositionTest {

    @Test
    fun `the welcome says this build records a position`() {
        val welcome = CompanionSession(
            config = CompanionConfig(appVersion = "2.9.0", sessionId = "3f9a1c", token = TOKEN),
            snapshot = ::snapshot,
        ).onFrame(
            """{"t":"hello","protoMin":"0.1","protoMax":"0.11","token":"$TOKEN","client":{"name":"Col de Berg"}}""",
            nowMs = 0,
        ).filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<WelcomeFrame>().single()

        assertEquals("0.11", welcome.proto)
        assertTrue(welcome.honours.containsAll(listOf("lat", "lon", "altM")), "honours ${welcome.honours}")
    }

    @Test
    fun `a world frame with a position reports it as sent`() {
        val effects = session().onFrame(world(1, ""","lat":47.6123456,"lon":11.3456789,"altM":812.4"""), nowMs = 100)
        assertEquals(CompanionPosition(47.6123456, 11.3456789, 812.4), effects.position())
        assertTrue(effects.warnings().isEmpty(), "warned ${effects.warnings()}")
    }

    @Test
    fun `a world frame without one reports nothing - as before`() {
        assertNull(session().onFrame(world(1, ""), nowMs = 100).position())
    }

    @Test
    fun `an altitude may come without a place`() {
        assertEquals(
            CompanionPosition(null, null, 812.0),
            session().onFrame(world(1, ""","altM":812"""), nowMs = 100).position(),
        )
    }

    @Test
    fun `half a position is refused and said so - once`() {
        val s = session()
        val first = s.onFrame(world(1, ""","lat":47.6"""), nowMs = 100)
        assertNull(first.position())
        // The warning names what was sent and ignored.
        assertEquals(listOf("lat"), first.warnings().map { it.field })
        assertEquals(CompanionWarning.REJECTED, first.warnings().single().code)

        val second = s.onFrame(world(2, ""","lat":47.6"""), nowMs = 200)
        assertTrue(second.warnings().isEmpty(), "said again at frame rate")
    }

    @Test
    fun `a position off the earth is refused rather than clamped`() {
        val effects = session().onFrame(world(1, ""","lat":91.0,"lon":11.3,"altM":812"""), nowMs = 100)
        // The altitude still stands; the place does not, and nothing was moved.
        assertEquals(CompanionPosition(null, null, 812.0), effects.position())
        assertEquals(listOf("lat"), effects.warnings().map { it.field })

        val alt = session().onFrame(world(1, ""","lat":47.6,"lon":11.3,"altM":12000"""), nowMs = 100)
        assertEquals(CompanionPosition(47.6, 11.3, null), alt.position())
        assertEquals(listOf("altM"), alt.warnings().map { it.field })
    }

    @Test
    fun `nothing is reported while the ride is untracked`() {
        val effects = session(CompanionState.UNTRACKED)
            .onFrame(world(1, ""","lat":47.6,"lon":11.3,"altM":812"""), nowMs = 100)
        assertNull(effects.position())
    }

    // The engine, which the ride recorder reads once a second ──────────────

    private fun engine() = CompanionEngine(
        powerWatts = { 200 },
        cadenceRpm = { 0 },
        applyRoad = { _: SimulationParameters -> },
        riderMassKg = { 75f },
        bikeMassKg = { 9f },
        drivetrainProvider = { Drivetrain.DEFAULT },
    ).also { it.load() }

    @Test
    fun `a second is only placed while the report is fresh`() {
        val e = engine()
        assertEquals(CompanionSample(), e.sample(nowMs = 0), "nothing said, nothing recorded")

        e.setPosition(CompanionPosition(47.6, 11.3, 812.0), nowMs = 10_000)
        assertEquals(CompanionSample(latitude = 47.6, longitude = 11.3, altitudeMeters = 812.0), e.sample(nowMs = 10_900))

        // The game went quiet: a gap in the track, not a rider standing still.
        val later = e.sample(nowMs = 10_000 + CompanionProtocol.WORLD_TIMEOUT_MS + 1)
        assertNull(later.latitude)
        assertNull(later.altitudeMeters)
    }

    @Test
    fun `the distance is the game's count once it has given one`() {
        val e = engine()
        assertNull(e.sample().distanceMeters, "a zero nobody said is not recorded")
        e.setProgress(distanceM = 1234.5, ascentM = null)
        assertEquals(1234.5, e.sample().distanceMeters)
    }

    @Test
    fun `a new ride starts with nothing`() {
        val e = engine()
        e.setProgress(distanceM = 100.0, ascentM = 5.0)
        e.setPosition(CompanionPosition(47.6, 11.3, 812.0), nowMs = 1_000)
        e.load()
        assertEquals(CompanionSample(), e.sample(nowMs = 1_100))
    }

    // The crash log, which carries it across a crash ───────────────────────

    @Test
    fun `the crash log keeps a placed second whole`() {
        val placed = TelemetryPoint(
            timestampEpoch = 1_700_000_000_000L, power = 230, cadence = 88, heartRate = 150,
            distanceMeters = 8412.5, latitude = -33.8688197, longitude = 151.2092955, altitudeMeters = -3.5,
        )
        assertEquals(placed, ActiveTelemetryCodec.decode(ActiveTelemetryCodec.encode(placed)))

        val partly = placed.copy(latitude = null, longitude = null)
        assertEquals(partly, ActiveTelemetryCodec.decode(ActiveTelemetryCodec.encode(partly)))
    }

    @Test
    fun `an unplaced second is written exactly as before`() {
        val plain = TelemetryPoint(1_700_000_000_000L, 230, 88, 150)
        assertEquals("1700000000000,230,88,150", ActiveTelemetryCodec.encode(plain))
    }

    @Test
    fun `a line torn inside its last number is dropped - not misread`() {
        val points = (0 until 3).map {
            TelemetryPoint(1_700_000_000_000L + it * 1000L, 230, 88, 150, 100.0 * it, 47.6, 11.3, 812.25)
        }
        val whole = ActiveTelemetryCodec.encodeBlock(points)
        // Cut two characters short: every field is still there, the last shorter.
        val torn = whole.dropLast(3)
        assertEquals(points.dropLast(1), ActiveTelemetryCodec.decodeBlock(torn))
        assertEquals(points, ActiveTelemetryCodec.decodeBlock(whole))
    }

    @Test
    fun `a road field that is not a number spoils only its own line`() {
        assertNull(ActiveTelemetryCodec.decode("1700000000000,230,88,150,12.5,north,11.3,"))
        assertNull(ActiveTelemetryCodec.decode("1700000000000,230,88,150,12.5,47.6"))
    }
}
