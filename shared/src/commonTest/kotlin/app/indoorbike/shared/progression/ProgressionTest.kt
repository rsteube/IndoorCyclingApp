/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
package app.indoorbike.shared.progression

import app.indoorbike.shared.db.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MS_PER_DAY: Long = 86_400_000L
private const val MS_PER_WEEK: Long = MS_PER_DAY * 7

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
        assertEquals(0, info.currentWeeks)
        assertEquals(0, info.longestWeeks)
        // Window is filled with empty entries so the UI can render them.
        assertTrue(info.recentWeekActivity.all { it.daysActive == 0 })
    }

    @Test
    fun singleRideThisWeekCountsOne() {
        val now = 100L * MS_PER_WEEK
        val info = computeStreak(listOf(session(epoch = now)), nowEpochMs = now)
        assertEquals(1, info.currentWeeks)
        assertEquals(1, info.longestWeeks)
        // Last entry in the window is "this week" with at least one active day.
        assertTrue(info.recentWeekActivity.last().daysActive >= 1)
    }

    @Test
    fun threeConsecutiveWeeksCounts() {
        val now = 100L * MS_PER_WEEK
        val sessions = listOf(
            session(id = 0, epoch = now),
            session(id = 1, epoch = now - MS_PER_WEEK),
            session(id = 2, epoch = now - 2 * MS_PER_WEEK),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(3, info.currentWeeks)
        assertEquals(3, info.longestWeeks)
    }

    @Test
    fun gapResetsCurrentButLongestRemembers() {
        val now = 100L * MS_PER_WEEK
        val sessions = listOf(
            session(id = 0, epoch = now),                       // this week
            session(id = 1, epoch = now - MS_PER_WEEK),         // last week
            // gap: no ride 2 weeks ago
            session(id = 2, epoch = now - 3 * MS_PER_WEEK),
            session(id = 3, epoch = now - 4 * MS_PER_WEEK),
            session(id = 4, epoch = now - 5 * MS_PER_WEEK),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(2, info.currentWeeks)
        assertEquals(3, info.longestWeeks)
    }

    @Test
    fun missingThisWeekWithLastWeekStillCountsGrace() {
        // No ride yet this week — grace should keep the streak alive.
        val now = 100L * MS_PER_WEEK + 12 * 3_600_000L  // Monday-ish, partway through the week
        val sessions = listOf(
            session(id = 0, epoch = now - MS_PER_WEEK),         // last week
            session(id = 1, epoch = now - 2 * MS_PER_WEEK),     // two weeks ago
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(2, info.currentWeeks)
    }

    @Test
    fun multipleRidesInOneWeekStillCountAsOneWeek() {
        val now = 100L * MS_PER_WEEK
        val sessions = listOf(
            session(id = 0, epoch = now),
            session(id = 1, epoch = now - MS_PER_DAY),
            session(id = 2, epoch = now - 2 * MS_PER_DAY),
        )
        val info = computeStreak(sessions, nowEpochMs = now)
        assertEquals(1, info.currentWeeks)
        // All three days should fall in this week's recent activity entry.
        assertEquals(3, info.recentWeekActivity.last().daysActive)
    }

    @Test
    fun recentWeekActivityHasRequestedWindowSize() {
        val now = 100L * MS_PER_WEEK
        val info = computeStreak(emptyList(), nowEpochMs = now, windowWeeks = 8)
        assertEquals(8, info.recentWeekActivity.size)
    }

    @Test
    fun timezoneOffsetShiftsDayBoundary() {
        // Session at UTC midnight Wednesday; with +3h offset the session is "Wednesday 03:00 local",
        // still on Wednesday — so the active-day count for this week stays the same.
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
        assertEquals(1, withoutTz.currentWeeks)
        assertEquals(1, withTz.currentWeeks)
    }
}

class RiderLevelTest {
    @Test
    fun emptySessionsLevelZero() {
        val level = computeRiderLevel(emptyList(), weightKg = 75f)
        assertEquals(0, level.level)
        assertEquals(0.0, level.totalXp)
        assertEquals(FIRST_LEVEL_XP, level.levelCeilingXp)
        assertEquals(0.0, level.levelFloorXp)
    }

    @Test
    fun zeroWeightYieldsZeroXp() {
        // Guard against division by zero when weight isn't set yet.
        val sessions = listOf(session(durationSeconds = 3600, avgPower = 200))
        val level = computeRiderLevel(sessions, weightKg = 0f)
        assertEquals(0.0, level.totalXp)
        assertEquals(0, level.level)
    }

    @Test
    fun negativeOrZeroSessionFieldsContributeNothing() {
        val s = session(durationSeconds = 0, avgPower = 200)
        assertEquals(0.0, xpFromSession(s, weightKg = 75f))
        val s2 = session(durationSeconds = 1800, avgPower = 0)
        assertEquals(0.0, xpFromSession(s2, weightKg = 75f))
    }

    @Test
    fun xpMatchesWattMinutesPerKilo() {
        // 200 W * 1800 s / 60 / 75 kg = 80 W·min/kg
        val s = session(durationSeconds = 1800, avgPower = 200)
        val xp = xpFromSession(s, weightKg = 75f)
        assertEquals(80.0, xp, "xp should be 80 W·min/kg, got $xp")
    }

    @Test
    fun crossingFirstLevelThresholdAdvancesToLevelOne() {
        // 20 xp is exactly the L1 threshold. 30 xp puts us comfortably in L1.
        // 200 W * 600 s / 60 / 75 kg = ~26.7 W·min/kg, which is between L1 (20) and L2 (50).
        val sessions = listOf(session(durationSeconds = 600, avgPower = 200))
        val level = computeRiderLevel(sessions, weightKg = 75f)
        assertEquals(1, level.level)
        assertEquals(FIRST_LEVEL_XP, level.levelFloorXp)
        assertEquals(FIRST_LEVEL_XP * LEVEL_GROWTH_FACTOR, level.levelCeilingXp)
    }

    @Test
    fun progressFractionStaysInZeroToOne() {
        // Pick a session that puts the rider mid-L1.
        val sessions = listOf(session(durationSeconds = 900, avgPower = 200))
        val level = computeRiderLevel(sessions, weightKg = 75f)
        assertTrue(level.progressFraction in 0f..1f, "fraction outside bounds: ${level.progressFraction}")
    }

    @Test
    fun xpToNextLevelIsNonNegative() {
        val sessions = listOf(session(durationSeconds = 1800, avgPower = 200))
        val level = computeRiderLevel(sessions, weightKg = 75f)
        assertTrue(level.xpToNextLevel >= 0.0)
    }

    @Test
    fun levelGrowthIsGeometric() {
        // Construct enough XP to push the rider to L3:
        //   L1 = 20, L2 = 50, L3 = 125.
        // 350 W·min/kg at 75 kg requires ~26 250 W·min — use one fat session.
        val s = session(durationSeconds = 7800, avgPower = 250)  // 32 500 W·min ~ 433 xp
        val level = computeRiderLevel(listOf(s), weightKg = 75f)
        assertTrue(level.level >= 3, "expected level >= 3, got ${level.level}")
        // Floor and ceiling should bracket totalXp.
        assertTrue(level.totalXp >= level.levelFloorXp)
        assertTrue(level.totalXp < level.levelCeilingXp)
        // Ceiling should equal floor * growth factor.
        assertEquals(level.levelFloorXp * LEVEL_GROWTH_FACTOR, level.levelCeilingXp, 0.0001)
    }
}

class TimeInZoneTest {
    @Test
    fun emptyTelemetryReturnsAllZeros() {
        val ttz = timeInZoneSeconds(emptyList(), ftp = 200)
        assertEquals(7, ttz.size)
        assertTrue(ttz.all { it == 0 })
    }

    @Test
    fun zeroFtpReturnsAllZeros() {
        val ttz = timeInZoneSeconds(listOf(100L, 200L), ftp = 0)
        assertEquals(7, ttz.size)
        assertTrue(ttz.all { it == 0 })
    }

    @Test
    fun zoneBucketingMatchesFtpFraction() {
        // FTP 200; samples at 50 W (25 % → Z1), 140 W (70 % → Z2),
        // 200 W (100 % → Z4), 230 W (115 % → Z5), 280 W (140 % → Z6),
        // 350 W (175 % → Z7).
        val telemetry = listOf(50L, 140L, 200L, 230L, 280L, 350L)
        val ttz = timeInZoneSeconds(telemetry, ftp = 200)
        assertEquals(1, ttz[0])  // Z1
        assertEquals(1, ttz[1])  // Z2
        assertEquals(0, ttz[2])  // Z3 not hit
        assertEquals(1, ttz[3])  // Z4
        assertEquals(1, ttz[4])  // Z5
        assertEquals(1, ttz[5])  // Z6
        assertEquals(1, ttz[6])  // Z7
    }

    @Test
    fun sampleSecondsScalesBuckets() {
        // 5 samples at 1 Hz → 5 s in Z2; if sampleSeconds=2, → 10 s.
        val ttz = timeInZoneSeconds(List(5) { 140L }, ftp = 200, sampleSeconds = 2)
        assertEquals(10, ttz[1])
    }

    @Test
    fun zonesSumToTotalSamples() {
        val telemetry = listOf(50L, 140L, 200L, 230L, 280L, 350L, 100L, 100L)
        val ttz = timeInZoneSeconds(telemetry, ftp = 200)
        assertEquals(telemetry.size, ttz.sum())
    }
}

class RideStatsTest {
    @Test
    fun steadyAtFtpYieldsIfOneAndTssOneHundred() {
        val ftp = 200
        val telemetry = List(3600) { ftp.toLong() }
        val stats = computeRideStats(telemetry, durationSeconds = 3600, ftp = ftp)
        assertEquals(ftp, stats.avgPowerWatts)
        // NP equals FTP for steady power → IF ≈ 1.0
        assertTrue(stats.intensityFactor in 0.99f..1.01f, "IF=${stats.intensityFactor}")
        assertTrue(stats.tss in 99..101, "TSS=${stats.tss}")
    }

    @Test
    fun emptyTelemetryYieldsZeros() {
        val stats = computeRideStats(emptyList(), durationSeconds = 3600, ftp = 200)
        assertEquals(0, stats.avgPowerWatts)
        assertEquals(0, stats.tss)
        assertEquals(0, stats.normalizedPowerWatts)
        assertEquals(0, stats.kilojoules)
    }

    @Test
    fun zeroFtpYieldsZeros() {
        val stats = computeRideStats(List(60) { 200L }, durationSeconds = 60, ftp = 0)
        assertEquals(0, stats.tss)
        assertEquals(0f, stats.intensityFactor)
    }

    @Test
    fun zeroDurationYieldsZeros() {
        val stats = computeRideStats(List(60) { 200L }, durationSeconds = 0, ftp = 200)
        assertEquals(0, stats.tss)
    }

    @Test
    fun normalizedPowerExceedsAverageWhenSpiky() {
        // 30 min at 100 W, 30 min at 300 W → avg 200, NP > 200 because of 4th-power weighting.
        val telemetry = List(1800) { 100L } + List(1800) { 300L }
        val stats = computeRideStats(telemetry, durationSeconds = 3600, ftp = 200)
        assertEquals(200, stats.avgPowerWatts)
        assertTrue(
            stats.normalizedPowerWatts > stats.avgPowerWatts,
            "NP ${stats.normalizedPowerWatts} should exceed avg ${stats.avgPowerWatts}"
        )
    }

    @Test
    fun kilojoulesMatchAvgWattsTimesDuration() {
        // 200 W × 3600 s = 720 000 W·s = 720 kJ
        val stats = computeRideStats(List(3600) { 200L }, durationSeconds = 3600, ftp = 200)
        assertEquals(720, stats.kilojoules)
    }
}

class BadgeTest {
    @Test
    fun emptyHistoryHasNoBadges() {
        val badges = computeBadges(emptyList(), nowEpochMs = 100L * MS_PER_DAY)
        assertTrue(badges.isEmpty())
    }

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
        assertTrue(badges.any { it.id == "five-rides" }, "got ${badges.map { it.id }}")
    }

    @Test
    fun oneHourSaddleTimeUnlocksFirstHour() {
        val now = 10L * MS_PER_DAY
        // Two 30-min rides → 1 h cumulative.
        val sessions = listOf(
            session(id = 0, epoch = now - MS_PER_DAY, durationSeconds = 1800),
            session(id = 1, epoch = now, durationSeconds = 1800),
        )
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "first-hour" }, "got ${badges.map { it.id }}")
    }

    @Test
    fun thousandKjUnlocksKilojouleClub() {
        val now = 10L * MS_PER_DAY
        // 250 W * 4000 s = 1 000 kJ exactly.
        val sessions = listOf(session(epoch = now, durationSeconds = 4000, avgPower = 250))
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "kilojoule-club" }, "got ${badges.map { it.id }}")
    }

    @Test
    fun threeWeekStreakUnlocksConsistencyThree() {
        val now = 100L * MS_PER_WEEK
        val sessions = (0L..2L).map { i -> session(id = i, epoch = now - i * MS_PER_WEEK) }
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "consistency-3" })
    }

    @Test
    fun sevenWeekStreakUnlocksConsistencySeven() {
        val now = 100L * MS_PER_WEEK
        val sessions = (0L..6L).map { i -> session(id = i, epoch = now - i * MS_PER_WEEK) }
        val badges = computeBadges(sessions, nowEpochMs = now)
        assertTrue(badges.any { it.id == "consistency-7" })
    }

    @Test
    fun shortHistoryDoesNotUnlockMilestones() {
        val now = 10L * MS_PER_DAY
        // One short ride: only first-ride should unlock.
        val sessions = listOf(session(epoch = now, durationSeconds = 600, avgPower = 100))
        val badges = computeBadges(sessions, nowEpochMs = now)
        val ids = badges.map { it.id }.toSet()
        assertTrue("first-ride" in ids)
        assertTrue("five-rides" !in ids)
        assertTrue("first-hour" !in ids)
        assertTrue("kilojoule-club" !in ids)
    }
}

class ProgressionUtilsTest {
    @Test
    fun totalKjAcrossSumsKilojoules() {
        val sessions = listOf(
            session(id = 0, durationSeconds = 1800, avgPower = 200),  // 360 kJ
            session(id = 1, durationSeconds = 3600, avgPower = 250),  // 900 kJ
        )
        assertEquals(1260L, totalKjAcross(sessions))
    }

    @Test
    fun totalDurationSecondsSums() {
        val sessions = listOf(
            session(id = 0, durationSeconds = 600),
            session(id = 1, durationSeconds = 1500),
        )
        assertEquals(2100L, totalDurationSeconds(sessions))
    }

    @Test
    fun durationLabelLongHandlesEdges() {
        assertEquals("<1 min", durationLabelLong(0L))
        assertEquals("<1 min", durationLabelLong(15L))
        assertEquals("1 min", durationLabelLong(45L))
        assertEquals("30 min", durationLabelLong(30L * 60))
        assertEquals("1 h", durationLabelLong(60L * 60))
        assertEquals("1 h 30 min", durationLabelLong(90L * 60))
        assertEquals("2 h", durationLabelLong(2L * 60 * 60))
    }
}
