package app.indoorbike.shared.progression

import app.indoorbike.shared.db.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MS_PER_DAY: Long = 86_400_000L

private fun session(
    id: Long = 0,
    epoch: Long = 0L,
    durationSeconds: Long = 1800,
    avgPower: Long = 200,
    avgCadence: Long = 85,
    avgHr: Long = 140,
    workoutId: String = "w-$id",
    name: String = "Workout $id",
) = WorkoutSession(
    id = id,
    workoutId = workoutId,
    workoutName = name,
    startTimeEpoch = epoch,
    durationSeconds = durationSeconds,
    avgPower = avgPower,
    avgCadence = avgCadence,
    avgHeartRate = avgHr,
)

class StreakTest {
    @Test
    fun emptySessionsHasZeroStreak() {
        val info = computeStreak(emptyList(), nowEpochMs = 0L)
        assertEquals(0, info.currentDays)
        assertEquals(0, info.longestDays)
    }

    @Test
    fun threeConsecutiveDaysCounts() {
        val now = 10L * MS_PER_DAY
        val sessions = listOf(
            session(epoch = now),
            session(epoch = now - MS_PER_DAY),
            session(epoch = now - 2 * MS_PER_DAY),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(3, info.currentDays)
        assertEquals(3, info.longestDays)
    }

    @Test
    fun gapResetsCurrentButLongestRemembers() {
        val now = 10L * MS_PER_DAY
        val sessions = listOf(
            session(epoch = now),
            session(epoch = now - MS_PER_DAY),
            // gap on day -2
            session(epoch = now - 3 * MS_PER_DAY),
            session(epoch = now - 4 * MS_PER_DAY),
            session(epoch = now - 5 * MS_PER_DAY),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(2, info.currentDays)
        assertEquals(3, info.longestDays)
    }

    @Test
    fun missingTodayWithYesterdayStillCounts() {
        // Grace: we don't drop the streak the moment today rolls over without a ride.
        val now = 10L * MS_PER_DAY + 4 * 3_600_000  // 4 AM
        val sessions = listOf(
            session(epoch = now - MS_PER_DAY),       // yesterday
            session(epoch = now - 2 * MS_PER_DAY),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(2, info.currentDays)
    }

    @Test
    fun timezoneOffsetShiftsDayBoundary() {
        // Session at UTC midnight; a +3h offset puts it on the previous local day.
        val baseDay = 100L * MS_PER_DAY
        val sessionAtUtcMidnight = baseDay
        val now = baseDay + 12 * 3_600_000L
        val plus3h = 3 * 3_600_000L

        val withoutTz = computeStreak(
            listOf(session(epoch = sessionAtUtcMidnight)),
            nowEpochMs = now,
            timeZoneOffsetMs = 0
        )
        val withTz = computeStreak(
            listOf(session(epoch = sessionAtUtcMidnight)),
            nowEpochMs = now,
            timeZoneOffsetMs = plus3h
        )
        // Both should count today as active since the local day is the same in both cases.
        assertEquals(1, withoutTz.currentDays)
        assertEquals(1, withTz.currentDays)
    }
}

class RiderLevelTest {
    @Test
    fun zeroSessionsLevelOne() {
        val level = computeRiderLevel(emptyList())
        assertEquals(1, level.tier)
        assertEquals(0L, level.totalKj)
    }

    @Test
    fun kjAccumulationAdvancesTier() {
        // 200W * 1800s = 360 kJ — past tier 1 floor (200 kJ), in tier 2.
        val sessions = listOf(session(durationSeconds = 1800, avgPower = 200))
        val level = computeRiderLevel(sessions)
        assertTrue(level.tier >= 2, "expected tier ≥ 2, got ${level.tier}")
        assertEquals(360L, level.totalKj)
    }

    @Test
    fun progressFractionIsNormalized() {
        val sessions = listOf(session(durationSeconds = 5000, avgPower = 200))
        val level = computeRiderLevel(sessions)
        assertTrue(level.progressFraction in 0f..1f, "fraction outside bounds: ${level.progressFraction}")
    }
}

class TimeInZoneTest {
    @Test
    fun emptyTelemetryReturnsZeros() {
        val ttz = timeInZoneSeconds(emptyList(), ftp = 200)
        assertEquals(7, ttz.size)
        assertTrue(ttz.all { it == 0 })
    }

    @Test
    fun zoneBucketingMatchesFtpFraction() {
        // FTP 200; samples at 50W (25% → Z1), 140W (70% → Z2),
        // 200W (100% → Z4), 230W (115% → Z5), 280W (140% → Z6).
        val telemetry = listOf(50L, 140L, 200L, 230L, 280L)
        val ttz = timeInZoneSeconds(telemetry, ftp = 200)
        assertEquals(1, ttz[0]) // Z1
        assertEquals(1, ttz[1]) // Z2
        assertEquals(0, ttz[2]) // Z3 not hit
        assertEquals(1, ttz[3]) // Z4
        assertEquals(1, ttz[4]) // Z5
        assertEquals(1, ttz[5]) // Z6
        assertEquals(0, ttz[6]) // Z7 not hit
    }

    @Test
    fun zeroFtpReturnsZeros() {
        val ttz = timeInZoneSeconds(listOf(100L, 200L), ftp = 0)
        assertTrue(ttz.all { it == 0 })
    }
}

class RideStatsTest {
    @Test
    fun steadyTelemetryGivesExpectedTss() {
        // 1 hour at exactly FTP → IF=1.0, TSS≈100
        val ftp = 200
        val telemetry = List(3600) { ftp.toLong() }
        val stats = computeRideStats(telemetry, durationSeconds = 3600, ftp = ftp)
        assertEquals(ftp, stats.avgPowerWatts)
        // NP equals FTP for steady power → IF≈1.0
        assertTrue(stats.intensityFactor in 0.99f..1.01f, "IF=${stats.intensityFactor}")
        assertTrue(stats.tss in 99..101, "TSS=${stats.tss}")
    }

    @Test
    fun emptyTelemetryYieldsZeros() {
        val stats = computeRideStats(emptyList(), 3600, ftp = 200)
        assertEquals(0, stats.avgPowerWatts)
        assertEquals(0, stats.tss)
    }

    @Test
    fun normalizedPowerExceedsAverageWhenSpiky() {
        // 30 min at 100W, 30 min at 300W → avg 200, but NP > 200 because of 4th-power weighting.
        val telemetry = (List(1800) { 100L } + List(1800) { 300L })
        val stats = computeRideStats(telemetry, durationSeconds = 3600, ftp = 200)
        assertEquals(200, stats.avgPowerWatts)
        assertTrue(stats.normalizedPowerWatts > stats.avgPowerWatts,
            "NP ${stats.normalizedPowerWatts} should exceed avg ${stats.avgPowerWatts}")
    }
}

class BadgeTest {
    @Test
    fun firstRideUnlocksFirstRideBadge() {
        val now = 10L * MS_PER_DAY
        val badges = computeBadges(listOf(session(epoch = now)), nowEpochMs = now)
        assertTrue(badges.any { it.id == "first-ride" })
    }

    @Test
    fun fiveRidesUnlocksFiveStrong() {
        val now = 10L * MS_PER_DAY
        val sessions = (0L..4L).map { i -> session(id = i, epoch = now - i * MS_PER_DAY) }
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "five-rides" }, "expected five-rides; got ${badges.map { it.id }}")
    }

    @Test
    fun streak7UnlocksOnFire() {
        val now = 10L * MS_PER_DAY
        val sessions = (0L..6L).map { i -> session(id = i, epoch = now - i * MS_PER_DAY) }
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "consistency-7" })
    }
}
